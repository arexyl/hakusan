package app.hakusan

import app.hakusan.extensions.ChapterRefreshAcceptance
import app.hakusan.extensions.ChapterRefreshCompletion
import app.hakusan.extensions.ChapterRefreshFailure
import app.hakusan.extensions.ChapterRefreshGate
import app.hakusan.extensions.ChapterRefreshRequest
import app.hakusan.extensions.ChapterSnapshot
import app.hakusan.extensions.SourceDetailsFailure
import app.hakusan.extensions.SourceFailure
import app.hakusan.extensions.SourceResult
import app.hakusan.extensions.SourceTitleDetails
import app.hakusan.extensions.SourceTitleKey
import app.hakusan.sdk.DetailsScreenFailure
import app.hakusan.titles.AcceptedTitleObservation
import app.hakusan.titles.ProgressSnapshot
import app.hakusan.titles.Titles
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Moves the observations of one source title into storage.
 *
 * A load starts an attempt before its first source suspension, and a newer load
 * of the same title makes every older attempt stale. The attempt validates the
 * current details before it issues a chapter-refresh request. It decides a
 * details failure and its own staleness before the refresh, without the
 * serialization point of its title, and a stale attempt returns
 * [TitleLoadResult.RejectedNotCurrent] even when its details failed.
 *
 * Acceptance and reconciliation hold the serialization point, one mutex per
 * title. Under it, an attempt that is no longer current is rejected before the
 * refresh gate of its title accepts its completion, because a newer attempt may
 * not have issued its request yet. The accepted details and the accepted
 * complete snapshot reconcile in one `:titles` transaction that also reads the
 * progress. The mutex is held until the reconciliation returns or throws, so an
 * older accepted snapshot never overwrites a newer one, and a newer load never
 * rejects an accepted handoff.
 *
 * Nothing persists when the details or the chapter refresh fail, or when the
 * caller cancels before the transaction commits. A cancellation that surfaces
 * after the commit propagates, and the load publishes nothing, reads nothing,
 * and marks nothing. The caller owns the task, its execution, and its
 * cancellation. The coordination of a title exists only while a load of that
 * title is active.
 */
@Inject
@SingleIn(AppScope::class)
internal class WholeTitleLoad(
  private val registry: SourceRegistry,
  private val titles: Titles,
) {
  /**
   * The coordination of each title with an active load. Its monitor guards the
   * map and the load count of every coordination.
   */
  private val coordinations = HashMap<SourceTitleKey, TitleCoordination>()

  /** The number of titles with an active load, for tests. */
  val activeTitleCount: Int
    get() = synchronized(coordinations) { coordinations.size }

  /**
   * Loads the details and the chapters of [titleKey] and reconciles them.
   *
   * The source registered for the identity of [titleKey] serves the load, so
   * the source always owns the key. The load never adds the title to the
   * Library. Cancellation, the failures that [Titles.reconcileTitle] documents,
   * and unexpected defects of the source propagate unchanged.
   */
  suspend fun load(titleKey: SourceTitleKey): TitleLoadResult {
    val registration =
      registry.find(titleKey.source)
        ?: return TitleLoadResult.Failed(DetailsScreenFailure.SourceNotFound)

    val coordination = retain(titleKey)

    try {
      return runAttempt(coordination, registration)
    } finally {
      release(coordination)
    }
  }

  private suspend fun runAttempt(
    coordination: TitleCoordination,
    registration: SourceRegistration,
  ): TitleLoadResult {
    val attempt = coordination.begin()

    val details =
      when (val result = registration.source.details(coordination.title)) {
        is SourceResult.Success -> result.value

        is SourceResult.Failure ->
          return coordination.fail(attempt, result.error.toDetailsFailure())
      }

    val request =
      coordination.issue(attempt) ?: return TitleLoadResult.RejectedNotCurrent

    val completion = registration.source.refreshChapters(request)

    return coordination.reconciliation.withLock {
      when (val acceptance = coordination.accept(attempt, completion)) {
        ChapterRefreshAcceptance.RejectedNotCurrent ->
          TitleLoadResult.RejectedNotCurrent

        is ChapterRefreshAcceptance.Accepted ->
          reconcileAccepted(registration, details, acceptance.result)
      }
    }
  }

  /**
   * Reconciles [details] with the accepted chapter-refresh [result], or returns
   * its failure without reaching [titles].
   */
  private suspend fun reconcileAccepted(
    registration: SourceRegistration,
    details: SourceTitleDetails,
    result: SourceResult<ChapterSnapshot, ChapterRefreshFailure>,
  ): TitleLoadResult {
    val snapshot =
      when (result) {
        is SourceResult.Success -> result.value

        is SourceResult.Failure ->
          return TitleLoadResult.Failed(result.error.toChaptersFailure())
      }

    val observation = details.toAcceptedTitleObservation(snapshot)

    val progress = titles.reconcileTitle(observation)

    return TitleLoadResult.Reconciled(
      sourceDisplayName = registration.catalogItem.displayName,
      observation = observation,
      progress = progress,
    )
  }

  /** Returns the coordination of [title] with one more active load. */
  private fun retain(title: SourceTitleKey): TitleCoordination =
    synchronized(coordinations) {
      val coordination =
        coordinations.getOrPut(title) { TitleCoordination(title) }

      coordination.loads += 1

      coordination
    }

  /**
   * Ends one active load of [coordination] and drops the coordination with its
   * last load.
   *
   * Each [retain] pairs with exactly one release in a `finally` block, so the
   * count is positive here and the map holds [coordination]. The release
   * neither suspends nor throws, so it never replaces a propagating failure or
   * cancellation.
   */
  private fun release(coordination: TitleCoordination) {
    synchronized(coordinations) {
      coordination.loads -= 1

      if (coordination.loads == 0) {
        coordinations.remove(coordination.title)
      }
    }
  }
}

/** The outcome of one whole-title load. */
internal sealed interface TitleLoadResult {
  /**
   * The load reconciled [observation] and read [progress] in the same
   * transaction.
   *
   * [sourceDisplayName] is the source name that the registry captured.
   */
  data class Reconciled(
    val sourceDisplayName: String,
    val observation: AcceptedTitleObservation,
    val progress: ProgressSnapshot,
  ) : TitleLoadResult

  /** The load failed with [error], and it persisted nothing. */
  data class Failed(val error: DetailsScreenFailure) : TitleLoadResult

  /**
   * A newer load of the same title superseded this one, which persisted
   * nothing.
   *
   * This is arbitration, not a failure.
   */
  data object RejectedNotCurrent : TitleLoadResult
}

/** The identity of one load attempt of a title. */
private class LoadAttempt

/**
 * The coordination of the active loads of one title.
 *
 * Its monitor guards the current attempt, so beginning an attempt, deciding
 * whether it is current, issuing its request, and accepting its completion are
 * atomic. [reconciliation] is the serialization point of acceptance and
 * reconciliation. The monitor of the coordination map guards [loads].
 */
private class TitleCoordination(val title: SourceTitleKey) {
  val reconciliation = Mutex()

  /** The number of active loads of the title. */
  var loads = 0

  private val gate = ChapterRefreshGate(title)

  /** The newest attempt, until it fails or is accepted. */
  private var current: LoadAttempt? = null

  /** Starts a new current attempt, which makes every older attempt stale. */
  fun begin(): LoadAttempt =
    synchronized(this) {
      val attempt = LoadAttempt()

      current = attempt

      attempt
    }

  /**
   * Ends [attempt] with [failure] before its refresh, or rejects it when it is
   * no longer current.
   */
  fun fail(
    attempt: LoadAttempt,
    failure: DetailsScreenFailure,
  ): TitleLoadResult =
    synchronized(this) {
      if (current !== attempt) {
        return TitleLoadResult.RejectedNotCurrent
      }

      current = null

      TitleLoadResult.Failed(failure)
    }

  /**
   * Issues the refresh request of [attempt], or returns null when it is no
   * longer current.
   */
  fun issue(attempt: LoadAttempt): ChapterRefreshRequest? =
    synchronized(this) { if (current === attempt) gate.issue() else null }

  /**
   * Rejects [attempt] when it is no longer current, and otherwise lets the gate
   * arbitrate its [completion] and ends the attempt.
   */
  fun accept(
    attempt: LoadAttempt,
    completion: ChapterRefreshCompletion,
  ): ChapterRefreshAcceptance =
    synchronized(this) {
      if (current !== attempt) {
        return ChapterRefreshAcceptance.RejectedNotCurrent
      }

      val acceptance = gate.accept(completion)

      current = null

      acceptance
    }
}

/** Returns the screen failure of this title-details failure. */
private fun SourceDetailsFailure.toDetailsFailure(): DetailsScreenFailure =
  when (this) {
    SourceFailure.Unavailable -> DetailsScreenFailure.DetailsUnavailable
    is SourceFailure.InvalidTitleDetails ->
      DetailsScreenFailure.InvalidTitleObservation
  }

/** Returns the screen failure of this chapter-refresh failure. */
private fun ChapterRefreshFailure.toChaptersFailure(): DetailsScreenFailure =
  when (this) {
    SourceFailure.Unavailable -> DetailsScreenFailure.ChaptersUnavailable
    is SourceFailure.InvalidChapterSnapshot ->
      DetailsScreenFailure.InvalidChapterSnapshot
  }
