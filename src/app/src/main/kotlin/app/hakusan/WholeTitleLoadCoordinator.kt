package app.hakusan

import app.hakusan.extensions.ChapterRefreshAcceptance
import app.hakusan.extensions.ChapterRefreshCompletion
import app.hakusan.extensions.ChapterRefreshGate
import app.hakusan.extensions.ChapterRefreshRequest
import app.hakusan.extensions.SourceFailure
import app.hakusan.extensions.SourceResult
import app.hakusan.extensions.SourceTitleDetails
import app.hakusan.extensions.SourceTitleKey
import app.hakusan.sdk.DetailsScreenFailure
import app.hakusan.sdk.ScreenTitleKey
import app.hakusan.titles.ChapterReconciliationFailure
import app.hakusan.titles.ChapterReconciliationResult
import app.hakusan.titles.ReconcileChapterSnapshot
import app.hakusan.titles.TitleId
import app.hakusan.titles.TitleReadingProgress
import app.hakusan.titles.Titles
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coordinates one whole-title source load through durable reconciliation.
 *
 * A whole-title attempt begins before source details suspend. Its identity is
 * application-local and distinct from the chapter-refresh request issued only
 * after current details are validated. Per title, an accepted source snapshot
 * completes reconciliation before a later accepted snapshot may enter it.
 * Cancellation and unexpected defects propagate to the caller.
 */
@Inject
@SingleIn(AppScope::class)
internal class WholeTitleLoadCoordinator(
  private val sourceRegistry: SourceRegistry,
  private val titles: Titles,
) {
  private val activeTitles =
    ConcurrentHashMap<ScreenTitleKey, ActiveTitleLoad>()

  suspend fun load(
    titleKey: ScreenTitleKey,
  ): WholeTitleLoadResult {
    val registration = sourceRegistry.find(titleKey.sourceId)
      ?: return WholeTitleLoadResult.Failure(
        DetailsScreenFailure.SourceNotFound,
      )
    val sourceTitleKey = titleKey.toSourceKey()
    val active = acquire(titleKey, sourceTitleKey)
    val attempt = active.begin()
    try {
      val details = when (
        val result = registration.backend.details(sourceTitleKey)
      ) {
        is SourceResult.Failure -> {
          val failure = when (result.error) {
            SourceFailure.Unavailable ->
              DetailsScreenFailure.DetailsUnavailable
          }
          return active.finishBeforeChapters(
            attempt = attempt,
            result = WholeTitleLoadResult.Failure(failure),
          )
        }

        is SourceResult.Success -> result.value
      }
      if (details.title.key != sourceTitleKey) {
        return active.finishBeforeChapters(
          attempt = attempt,
          result = WholeTitleLoadResult.Failure(
            DetailsScreenFailure.InvalidTitleObservation,
          ),
        )
      }

      val request = active.issueChapterRefresh(attempt)
        ?: return WholeTitleLoadResult.RejectedNotCurrent
      val completion = registration.backend.refreshChapters(request)
      return active.acceptAndRead(
        attempt = attempt,
        completion = completion,
        details = details,
        sourceDisplayName = registration.catalogItem.displayName,
        titles = titles,
      )
    } finally {
      release(titleKey, active)
    }
  }

  private fun acquire(
    titleKey: ScreenTitleKey,
    sourceTitleKey: SourceTitleKey,
  ): ActiveTitleLoad = checkNotNull(
    activeTitles.compute(titleKey) { _, current ->
      (current ?: ActiveTitleLoad(sourceTitleKey)).also { it.retain() }
    },
  )

  private fun release(
    titleKey: ScreenTitleKey,
    active: ActiveTitleLoad,
  ) {
    activeTitles.compute(titleKey) { _, current ->
      check(current === active) {
        "A whole-title coordinator must retain its active title owner."
      }
      if (active.release()) null else active
    }
  }
}

internal sealed interface WholeTitleLoadResult {
  data class Success(
    val sourceDisplayName: String,
    val details: SourceTitleDetails,
    val progress: TitleReadingProgress,
  ) : WholeTitleLoadResult

  data class Failure(
    val error: DetailsScreenFailure,
  ) : WholeTitleLoadResult

  data object RejectedNotCurrent : WholeTitleLoadResult
}

private class WholeTitleLoadAttempt {
  var phase: WholeTitleLoadPhase = WholeTitleLoadPhase.BeforeChapters
}

private sealed interface WholeTitleLoadPhase {
  data object BeforeChapters : WholeTitleLoadPhase

  data class AwaitingChapters(
    val request: ChapterRefreshRequest,
  ) : WholeTitleLoadPhase

  data object Completed : WholeTitleLoadPhase
}

private class ActiveTitleLoad(
  titleKey: SourceTitleKey,
) {
  private val refreshGate = ChapterRefreshGate(titleKey)
  private val state = Any()
  private val reconciliation = Mutex()
  private var currentAttempt: WholeTitleLoadAttempt? = null
  private var activeLoads = 0

  fun retain() {
    activeLoads += 1
  }

  /** Returns true when no load still owns this title coordinator. */
  fun release(): Boolean {
    check(activeLoads > 0) {
      "A whole-title coordinator was released without an owner."
    }
    activeLoads -= 1
    return activeLoads == 0
  }

  fun begin(): WholeTitleLoadAttempt = synchronized(state) {
    WholeTitleLoadAttempt().also { currentAttempt = it }
  }

  fun issueChapterRefresh(
    attempt: WholeTitleLoadAttempt,
  ): ChapterRefreshRequest? = synchronized(state) {
    if (currentAttempt !== attempt) {
      return@synchronized null
    }
    check(attempt.phase === WholeTitleLoadPhase.BeforeChapters) {
      "A whole-title attempt issued more than one chapter refresh."
    }
    refreshGate.issue().also { request ->
      attempt.phase = WholeTitleLoadPhase.AwaitingChapters(request)
    }
  }

  suspend fun finishBeforeChapters(
    attempt: WholeTitleLoadAttempt,
    result: WholeTitleLoadResult.Failure,
  ): WholeTitleLoadResult = reconciliation.withLock {
    val accepted = synchronized(state) {
      if (currentAttempt !== attempt) {
        false
      } else {
        check(attempt.phase === WholeTitleLoadPhase.BeforeChapters) {
          "A pre-chapter completion retained a chapter refresh request."
        }
        attempt.phase = WholeTitleLoadPhase.Completed
        currentAttempt = null
        true
      }
    }
    if (accepted) result else WholeTitleLoadResult.RejectedNotCurrent
  }

  suspend fun acceptAndRead(
    attempt: WholeTitleLoadAttempt,
    completion: ChapterRefreshCompletion,
    details: SourceTitleDetails,
    sourceDisplayName: String,
    titles: Titles,
  ): WholeTitleLoadResult = reconciliation.withLock {
    val acceptance = synchronized(state) {
      val phase = attempt.phase
      if (
        currentAttempt !== attempt ||
        phase !is WholeTitleLoadPhase.AwaitingChapters ||
        phase.request != completion.request
      ) {
        ChapterRefreshAcceptance.RejectedNotCurrent
      } else {
        refreshGate.accept(completion).also { result ->
          check(result is ChapterRefreshAcceptance.Accepted) {
            "The current title load must retain its refresh request."
          }
          attempt.phase = WholeTitleLoadPhase.Completed
          currentAttempt = null
        }
      }
    }
    when (acceptance) {
      ChapterRefreshAcceptance.RejectedNotCurrent ->
        WholeTitleLoadResult.RejectedNotCurrent

      is ChapterRefreshAcceptance.Accepted -> reconcileAndRead(
        acceptance = acceptance,
        details = details,
        sourceDisplayName = sourceDisplayName,
        titles = titles,
      )
    }
  }

  private suspend fun reconcileAndRead(
    acceptance: ChapterRefreshAcceptance.Accepted,
    details: SourceTitleDetails,
    sourceDisplayName: String,
    titles: Titles,
  ): WholeTitleLoadResult {
    val titleId = titles.reconcileSourceTitle(details.toReconcileTitle())
    val progress = when (val result = acceptance.result) {
      is SourceResult.Failure -> return WholeTitleLoadResult.Failure(
        when (result.error) {
          SourceFailure.Unavailable ->
            DetailsScreenFailure.ChaptersUnavailable

          is SourceFailure.InvalidChapterSnapshot ->
            DetailsScreenFailure.InvalidChapterSnapshot
        },
      )

      is SourceResult.Success -> reconcileChapters(
        titles = titles,
        titleId = titleId,
        snapshot = result.value.toReconcileSnapshot(),
      )
    }
    return WholeTitleLoadResult.Success(
      sourceDisplayName = sourceDisplayName,
      details = details,
      progress = progress,
    )
  }

  private suspend fun reconcileChapters(
    titles: Titles,
    titleId: TitleId,
    snapshot: ReconcileChapterSnapshot,
  ): TitleReadingProgress {
    when (val result = titles.reconcileChapterSnapshot(snapshot)) {
      is ChapterReconciliationResult.Success -> Unit
      is ChapterReconciliationResult.Failure -> when (result.error) {
        ChapterReconciliationFailure.TitleNotFound -> error(
          "A reconciled title disappeared during chapter reconciliation.",
        )
      }
    }
    return checkNotNull(titles.readReadingProgress(titleId)) {
      "A reconciled title disappeared after chapter reconciliation."
    }
  }
}
