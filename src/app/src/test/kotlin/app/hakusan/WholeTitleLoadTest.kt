package app.hakusan

import app.hakusan.extensions.ChapterRefreshCompletion
import app.hakusan.extensions.ChapterRefreshGate
import app.hakusan.extensions.ChapterRefreshRequest
import app.hakusan.extensions.ChapterSequenceStatus
import app.hakusan.extensions.SourceBackend
import app.hakusan.extensions.SourceChapter
import app.hakusan.extensions.SourceChapterKey
import app.hakusan.extensions.SourceDetailsFailure
import app.hakusan.extensions.SourceFailure
import app.hakusan.extensions.SourceIdentity
import app.hakusan.extensions.SourceResult
import app.hakusan.extensions.SourceTitle
import app.hakusan.extensions.SourceTitleDetails
import app.hakusan.extensions.SourceTitleKey
import app.hakusan.sdk.DetailsScreenFailure
import app.hakusan.titles.AcceptedTitleObservation
import app.hakusan.titles.ProgressSnapshot
import app.hakusan.titles.SourceTitleAlias
import app.hakusan.titles.TitleId
import app.hakusan.titles.Titles
import app.hakusan.titles.chapter
import app.hakusan.titles.chapterId
import app.hakusan.titles.progressSnapshot
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WholeTitleLoadTest {
  private val source = ControlledSource(SOURCE)

  /** The display names of the reconciled observations, in call order. */
  private val reconciled = ArrayList<String>()

  /** Titles that record each reconciliation and return its progress. */
  private val recordingTitles =
    FakeTitles(
      onReconcileTitle = { observation ->
        reconciled += observation.displayName

        progressOf(observation)
      }
    )

  @Test
  fun `a load reconciles the accepted details and chapters once`() {
    val progress = ArrayList<ProgressSnapshot>()

    val titles =
      FakeTitles(
        onReconcileTitle = { observation ->
          progressOf(observation).also { progress += it }
        }
      )

    val backend =
      ScriptedBackend(
        identity = SOURCE,
        onDisplayName = { "Source name" },
        onDetails = { title -> details(title, "Title", "About") },
        onRefreshChapters = { request ->
          completed(request, "c10", "c1", "c2")
        },
      )

    val load = wholeTitleLoad(titles, backend)

    val result =
      assertInstanceOf(
        TitleLoadResult.Reconciled::class.java,
        runBlocking { load.load(TITLE) },
      )

    val observation = result.observation

    assertEquals("Source name", result.sourceDisplayName)
    assertEquals(SourceTitleAlias.of("source", "title"), observation.alias)
    assertEquals("Title", observation.displayName)
    assertEquals("About", observation.description)

    assertEquals(
      listOf("c10", "c1", "c2"),
      observation.chapters.map { it.alias.localKey.text },
    )

    assertSame(progress.single(), result.progress)
    assertEquals(0, load.activeTitleCount)
  }

  @Test
  fun `an unregistered source is not found before any source call`() {
    // The registered backend fails the test on any call.
    val load = wholeTitleLoad(FakeTitles(), ScriptedBackend(OTHER_SOURCE))

    val result = runBlocking { load.load(TITLE) }

    assertEquals(
      TitleLoadResult.Failed(DetailsScreenFailure.SourceNotFound),
      result,
    )

    assertEquals(0, load.activeTitleCount)
  }

  @Test
  fun `a details failure is decided without a refresh or Titles`() {
    val unavailable: DetailsScript = {
      SourceResult.Failure(SourceFailure.Unavailable)
    }

    val foreignTitle: DetailsScript = { details(OTHER_TITLE, "Other", null) }

    val cases =
      listOf(
        unavailable to DetailsScreenFailure.DetailsUnavailable,
        foreignTitle to DetailsScreenFailure.InvalidTitleObservation,
      )

    for ((onDetails, failure) in cases) {
      // An unscripted refresh or Titles call fails the test.
      val backend = ScriptedBackend(identity = SOURCE, onDetails = onDetails)

      val load = wholeTitleLoad(FakeTitles(), backend)

      val result = runBlocking { load.load(TITLE) }

      assertEquals(TitleLoadResult.Failed(failure), result)
      assertEquals(0, load.activeTitleCount)
    }
  }

  @Test
  fun `an accepted refresh failure is decided without Titles`() {
    val chapters = listOf(SourceChapter(SourceChapterKey(TITLE, "c1"), "C1"))

    val foreignChapters =
      listOf(SourceChapter(SourceChapterKey(OTHER_TITLE, "c1"), "C1"))

    val unavailable: RefreshScript = { request ->
      ChapterRefreshCompletion.failed(request, SourceFailure.Unavailable)
    }

    val ambiguous: RefreshScript = { request ->
      completion(request, ChapterSequenceStatus.AMBIGUOUS, chapters)
    }

    val partial: RefreshScript = { request ->
      completion(request, ChapterSequenceStatus.PARTIAL, chapters)
    }

    val foreignTitle: RefreshScript = { request ->
      completion(request, ChapterSequenceStatus.COMPLETE, foreignChapters)
    }

    val duplicate: RefreshScript = { request ->
      completion(request, ChapterSequenceStatus.COMPLETE, chapters + chapters)
    }

    val foreignRequest: RefreshScript = {
      completed(ChapterRefreshGate(TITLE).issue(), "c1")
    }

    val cases =
      listOf(unavailable to DetailsScreenFailure.ChaptersUnavailable) +
        listOf(ambiguous, partial, foreignTitle, duplicate, foreignRequest)
          .map { it to DetailsScreenFailure.InvalidChapterSnapshot }

    for ((onRefresh, failure) in cases) {
      val backend =
        ScriptedBackend(
          identity = SOURCE,
          onDetails = { title -> details(title, "Title", null) },
          onRefreshChapters = onRefresh,
        )

      // FakeTitles fails the test on any call.
      val load = wholeTitleLoad(FakeTitles(), backend)

      val result = runBlocking { load.load(TITLE) }

      assertEquals(TitleLoadResult.Failed(failure), result)
      assertEquals(0, load.activeTitleCount)
    }
  }

  @Test
  fun `stale details are rejected before any refresh request`() = loadTest {
    val load = wholeTitleLoad(recordingTitles, source.backend)

    val older = startLoad(load, TITLE)

    val olderDetails = source.nextDetails()

    val newer = startLoad(load, TITLE)

    val newerDetails = source.nextDetails()

    olderDetails.succeed("Older")
    assertSame(TitleLoadResult.RejectedNotCurrent, older.await())
    assertEquals(0, source.refreshCount)
    newerDetails.succeed("Newer")
    source.nextRefresh().complete("c1")
    assertInstanceOf(TitleLoadResult.Reconciled::class.java, newer.await())
    assertEquals(listOf("Newer"), reconciled)
    assertEquals(1, source.refreshCount)
    assertEquals(0, load.activeTitleCount)
  }

  @Test
  fun `a stale attempt whose details failed is rejected`() = loadTest {
    val load = wholeTitleLoad(recordingTitles, source.backend)

    val older = startLoad(load, TITLE)

    val olderDetails = source.nextDetails()

    val newer = startLoad(load, TITLE)

    val newerDetails = source.nextDetails()

    olderDetails.fail()
    assertSame(TitleLoadResult.RejectedNotCurrent, older.await())
    newerDetails.succeed("Newer")
    source.nextRefresh().complete("c1")
    assertInstanceOf(TitleLoadResult.Reconciled::class.java, newer.await())
    assertEquals(listOf("Newer"), reconciled)
    assertEquals(0, load.activeTitleCount)
  }

  @Test
  fun `an attempt that became stale during its refresh is rejected`() =
    loadTest {
      val load = wholeTitleLoad(recordingTitles, source.backend)

      val older = startLoad(load, TITLE)

      source.nextDetails().succeed("Older")

      val olderRefresh = source.nextRefresh()

      // The newer attempt has not issued a request yet, so the gate alone
      // would still accept the older completion.
      val newer = startLoad(load, TITLE)

      val newerDetails = source.nextDetails()

      olderRefresh.complete("c1")
      assertSame(TitleLoadResult.RejectedNotCurrent, older.await())
      assertEquals(emptyList<String>(), reconciled)
      newerDetails.succeed("Newer")
      source.nextRefresh().complete("c1", "c2")
      assertInstanceOf(TitleLoadResult.Reconciled::class.java, newer.await())
      assertEquals(listOf("Newer"), reconciled)
      assertEquals(0, load.activeTitleCount)
    }

  @Test
  fun `a foreign completion fails when current and is rejected when stale`() =
    loadTest {
      // FakeTitles fails the test on any call.
      val load = wholeTitleLoad(FakeTitles(), source.backend)

      val current = startLoad(load, TITLE)

      source.nextDetails().succeed("Current")
      source.nextRefresh().completeForeign()

      assertEquals(
        TitleLoadResult.Failed(DetailsScreenFailure.InvalidChapterSnapshot),
        current.await(),
      )

      val older = startLoad(load, TITLE)

      source.nextDetails().succeed("Older")

      val olderRefresh = source.nextRefresh()

      val newer = startLoad(load, TITLE)

      val newerDetails = source.nextDetails()

      olderRefresh.completeForeign()
      assertSame(TitleLoadResult.RejectedNotCurrent, older.await())
      newerDetails.fail()

      assertEquals(
        TitleLoadResult.Failed(DetailsScreenFailure.DetailsUnavailable),
        newer.await(),
      )

      assertEquals(0, load.activeTitleCount)
    }

  @Test
  fun `details failures and stale rejections never wait to reconcile`() =
    loadTest {
      val entered = CompletableDeferred<Unit>()

      val release = CompletableDeferred<Unit>()

      val titles =
        FakeTitles(
          onReconcileTitle = { observation ->
            entered.complete(Unit)
            release.await()

            progressOf(observation)
          }
        )

      val load = wholeTitleLoad(titles, source.backend)

      val reconciling = startLoad(load, TITLE)

      source.nextDetails().succeed("Reconciling")
      source.nextRefresh().complete("c1")
      entered.await()

      val stale = startLoad(load, TITLE)

      val staleDetails = source.nextDetails()

      val failing = startLoad(load, TITLE)

      val failingDetails = source.nextDetails()

      failingDetails.fail()

      assertEquals(
        TitleLoadResult.Failed(DetailsScreenFailure.DetailsUnavailable),
        failing.await(),
      )

      staleDetails.succeed("Stale")
      assertSame(TitleLoadResult.RejectedNotCurrent, stale.await())
      assertFalse(reconciling.isCompleted)
      assertEquals(1, source.refreshCount)
      release.complete(Unit)

      assertInstanceOf(
        TitleLoadResult.Reconciled::class.java,
        reconciling.await(),
      )

      assertEquals(0, load.activeTitleCount)
    }

  @Test
  fun `a cancellation after the commit publishes nothing`() = loadTest {
    val committed = ArrayList<String>()

    val entered = CompletableDeferred<Unit>()

    val titles =
      FakeTitles(
        onReconcileTitle = { observation ->
          committed += observation.displayName

          if (observation.displayName == "Cancelled") {
            entered.complete(Unit)
            awaitCancellation()
          }

          progressOf(observation)
        }
      )

    val load = wholeTitleLoad(titles, source.backend)

    val cancelled = startLoad(load, TITLE)

    source.nextDetails().succeed("Cancelled")
    source.nextRefresh().complete("c1")
    entered.await()
    cancelled.cancelAndJoin()
    assertTrue(cancelled.isCancelled)
    assertEquals(0, load.activeTitleCount)

    // Only reconcileTitle is scripted, so a read or any other call after the
    // cancellation fails the test.
    val next = startLoad(load, TITLE)

    source.nextDetails().succeed("Next")
    source.nextRefresh().complete("c1", "c2")
    assertInstanceOf(TitleLoadResult.Reconciled::class.java, next.await())
    assertEquals(listOf("Cancelled", "Next"), committed)
    assertEquals(0, load.activeTitleCount)
  }

  @Test
  fun `a cancelled reconciliation ends before the next one enters`() =
    loadTest {
      val calls = ArrayList<String>()

      val entered = CompletableDeferred<Unit>()

      val titles =
        FakeTitles(
          onReconcileTitle = { observation ->
            val name = observation.displayName

            calls += "enter $name"

            try {
              if (name == "Older") {
                entered.complete(Unit)
                awaitCancellation()
              }
            } finally {
              calls += "exit $name"
            }

            progressOf(observation)
          }
        )

      val load = wholeTitleLoad(titles, source.backend)

      val older = startLoad(load, TITLE)

      source.nextDetails().succeed("Older")
      source.nextRefresh().complete("c1")
      entered.await()

      val newer = startLoad(load, TITLE)

      source.nextDetails().succeed("Newer")
      source.nextRefresh().complete("c1", "c2")
      // The newer load now waits for the serialization point.
      yield()
      assertFalse(newer.isCompleted)
      assertEquals(listOf("enter Older"), calls)
      older.cancelAndJoin()
      assertInstanceOf(TitleLoadResult.Reconciled::class.java, newer.await())

      assertEquals(
        listOf("enter Older", "exit Older", "enter Newer", "exit Newer"),
        calls,
      )

      assertEquals(0, load.activeTitleCount)
    }

  @Test
  fun `a load cancelled while it waits to reconcile accepts nothing`() =
    loadTest {
      val entered = CompletableDeferred<Unit>()

      val release = CompletableDeferred<Unit>()

      val titles =
        FakeTitles(
          onReconcileTitle = { observation ->
            reconciled += observation.displayName

            if (observation.displayName == "Holder") {
              entered.complete(Unit)
              release.await()
            }

            progressOf(observation)
          }
        )

      val load = wholeTitleLoad(titles, source.backend)

      val holder = startLoad(load, TITLE)

      source.nextDetails().succeed("Holder")
      source.nextRefresh().complete("c1")
      entered.await()

      val waiting = startLoad(load, TITLE)

      source.nextDetails().succeed("Waiting")
      source.nextRefresh().complete("c1", "c2")
      // The waiting load now waits for the serialization point.
      yield()
      assertFalse(waiting.isCompleted)
      waiting.cancelAndJoin()
      assertTrue(waiting.isCancelled)
      release.complete(Unit)
      assertInstanceOf(TitleLoadResult.Reconciled::class.java, holder.await())

      val next = startLoad(load, TITLE)

      source.nextDetails().succeed("Next")
      source.nextRefresh().complete("c1", "c2", "c3")
      assertInstanceOf(TitleLoadResult.Reconciled::class.java, next.await())
      // An accepted success always reaches reconcileTitle without another
      // suspension, so the waiting load accepted nothing.
      assertEquals(listOf("Holder", "Next"), reconciled)
      assertEquals(0, load.activeTitleCount)
    }

  @Test
  fun `a cancellation before the commit persists nothing`() = loadTest {
    val committed = ArrayList<String>()

    val entered = CompletableDeferred<Unit>()

    val commit = CompletableDeferred<Unit>()

    val titles =
      FakeTitles(
        onReconcileTitle = { observation ->
          if (observation.displayName == "Cancelled") {
            entered.complete(Unit)
            commit.await()
          }

          committed += observation.displayName

          progressOf(observation)
        }
      )

    val load = wholeTitleLoad(titles, source.backend)

    val cancelled = startLoad(load, TITLE)

    source.nextDetails().succeed("Cancelled")
    source.nextRefresh().complete("c1")
    entered.await()
    cancelled.cancelAndJoin()
    assertTrue(cancelled.isCancelled)
    assertEquals(emptyList<String>(), committed)
    assertEquals(0, load.activeTitleCount)

    val next = startLoad(load, TITLE)

    source.nextDetails().succeed("Next")
    source.nextRefresh().complete("c1")
    assertInstanceOf(TitleLoadResult.Reconciled::class.java, next.await())
    assertEquals(listOf("Next"), committed)
  }

  @Test
  fun `a cancellation at a source suspension reaches no Titles call`() =
    loadTest {
      // FakeTitles fails the test on any call.
      val load = wholeTitleLoad(FakeTitles(), source.backend)

      val inDetails = startLoad(load, TITLE)

      source.nextDetails()
      inDetails.cancelAndJoin()
      assertTrue(inDetails.isCancelled)
      assertEquals(0, load.activeTitleCount)

      val inRefresh = startLoad(load, TITLE)

      source.nextDetails().succeed("Title")
      source.nextRefresh()
      inRefresh.cancelAndJoin()
      assertTrue(inRefresh.isCancelled)
      assertEquals(0, load.activeTitleCount)
    }

  @Test
  fun `the coordination of a title lives only while a load of it is active`() =
    loadTest {
      val other = ControlledSource(OTHER_SOURCE)

      // FakeTitles fails the test on any call.
      val load = wholeTitleLoad(FakeTitles(), source.backend, other.backend)

      assertEquals(0, load.activeTitleCount)

      val older = startLoad(load, TITLE)

      val olderDetails = source.nextDetails()

      val newer = startLoad(load, TITLE)

      val newerDetails = source.nextDetails()

      assertEquals(1, load.activeTitleCount)

      val elsewhere = startLoad(load, SAME_KEY_ELSEWHERE)

      other.nextDetails()
      assertEquals(2, load.activeTitleCount)

      elsewhere.cancelAndJoin()
      assertEquals(1, load.activeTitleCount)
      olderDetails.succeed("Older")
      assertSame(TitleLoadResult.RejectedNotCurrent, older.await())
      assertEquals(1, load.activeTitleCount)
      newerDetails.fail()

      assertEquals(
        TitleLoadResult.Failed(DetailsScreenFailure.DetailsUnavailable),
        newer.await(),
      )

      assertEquals(0, load.activeTitleCount)
    }

  @Test
  fun `a cancelled newer load leaves the older one rejected`() = loadTest {
    // FakeTitles fails the test on any call.
    val load = wholeTitleLoad(FakeTitles(), source.backend)

    val older = startLoad(load, TITLE)

    val olderDetails = source.nextDetails()

    val newer = startLoad(load, TITLE)

    source.nextDetails()
    newer.cancelAndJoin()
    olderDetails.succeed("Older")
    assertSame(TitleLoadResult.RejectedNotCurrent, older.await())
    assertEquals(0, source.refreshCount)
    assertEquals(0, load.activeTitleCount)
  }

  @Test
  fun `an older accepted snapshot never overwrites a newer one`() = loadTest {
    val calls = ArrayList<String>()

    val entered = CompletableDeferred<Unit>()

    val release = CompletableDeferred<Unit>()

    val titles =
      FakeTitles(
        onReconcileTitle = { observation ->
          val name = observation.displayName

          calls += "enter $name"

          if (name == "Older") {
            entered.complete(Unit)
            release.await()
          }

          calls += "exit $name"

          progressOf(observation)
        }
      )

    val load = wholeTitleLoad(titles, source.backend)

    val older = startLoad(load, TITLE)

    source.nextDetails().succeed("Older")
    source.nextRefresh().complete("c1")
    entered.await()

    val newer = startLoad(load, TITLE)

    source.nextDetails().succeed("Newer")
    source.nextRefresh().complete("c1", "c2")
    // The newer snapshot now waits for the serialization point.
    yield()
    assertFalse(newer.isCompleted)
    release.complete(Unit)

    val olderResult =
      assertInstanceOf(TitleLoadResult.Reconciled::class.java, older.await())

    val newerResult =
      assertInstanceOf(TitleLoadResult.Reconciled::class.java, newer.await())

    assertEquals(
      listOf("enter Older", "exit Older", "enter Newer", "exit Newer"),
      calls,
    )

    assertEquals(listOf("c1"), localKeys(olderResult.observation))
    assertEquals(listOf("c1", "c2"), localKeys(newerResult.observation))
    assertEquals(0, load.activeTitleCount)
  }

  @Test
  fun `equal local keys under two sources load separately`() = loadTest {
    val other = ControlledSource(OTHER_SOURCE)

    val aliases = ArrayList<SourceTitleAlias>()

    val titles =
      FakeTitles(
        onReconcileTitle = { observation ->
          aliases += observation.alias

          progressOf(observation)
        }
      )

    val load = wholeTitleLoad(titles, source.backend, other.backend)

    val first = startLoad(load, TITLE)

    val firstDetails = source.nextDetails()

    val second = startLoad(load, SAME_KEY_ELSEWHERE)

    val secondDetails = other.nextDetails()

    assertEquals(2, load.activeTitleCount)
    // The second load began later, but it supersedes nothing of the first.
    firstDetails.succeed("Same name")
    secondDetails.succeed("Same name")
    source.nextRefresh().complete("c1")
    other.nextRefresh().complete("c1")
    assertInstanceOf(TitleLoadResult.Reconciled::class.java, first.await())
    assertInstanceOf(TitleLoadResult.Reconciled::class.java, second.await())

    assertEquals(
      listOf(
        SourceTitleAlias.of("source", "title"),
        SourceTitleAlias.of("other-source", "title"),
      ),
      aliases,
    )

    assertEquals(0, load.activeTitleCount)
  }

  @Test
  fun `a defect propagates unchanged and releases the title`() {
    val defect = IllegalStateException()

    val failingSource =
      ScriptedBackend(identity = SOURCE, onDetails = { throw defect })

    val sourceLoad = wholeTitleLoad(FakeTitles(), failingSource)

    val fromSource =
      assertThrows(IllegalStateException::class.java) {
        runBlocking { sourceLoad.load(TITLE) }
      }

    assertSame(defect, fromSource)
    assertEquals(0, sourceLoad.activeTitleCount)

    var reconciliations = 0

    val titles =
      FakeTitles(
        onReconcileTitle = { observation ->
          reconciliations += 1

          if (reconciliations == 1) {
            throw defect
          }

          progressOf(observation)
        }
      )

    val backend =
      ScriptedBackend(
        identity = SOURCE,
        onDetails = { title -> details(title, "Title", null) },
        onRefreshChapters = { request -> completed(request, "c1") },
      )

    val titlesLoad = wholeTitleLoad(titles, backend)

    val fromTitles =
      assertThrows(IllegalStateException::class.java) {
        runBlocking { titlesLoad.load(TITLE) }
      }

    assertSame(defect, fromTitles)
    assertEquals(0, titlesLoad.activeTitleCount)

    // The coordination of the title was dropped, so the next load starts a
    // fresh one and reconciles.
    val next = runBlocking { titlesLoad.load(TITLE) }

    assertInstanceOf(TitleLoadResult.Reconciled::class.java, next)
    assertEquals(2, reconciliations)
  }
}

private typealias TitleDetailsOutcome =
  SourceResult<SourceTitleDetails, SourceDetailsFailure>

private typealias DetailsScript =
  suspend (SourceTitleKey) -> TitleDetailsOutcome

private typealias RefreshScript =
  suspend (ChapterRefreshRequest) -> ChapterRefreshCompletion

/** One details call of a [ControlledSource] that waits for the test. */
private class PendingDetails(private val title: SourceTitleKey) {
  private val outcome = CompletableDeferred<TitleDetailsOutcome>()

  suspend fun await(): TitleDetailsOutcome = outcome.await()

  /** Completes the call with details named [displayName]. */
  fun succeed(displayName: String) {
    outcome.complete(details(title, displayName, null))
  }

  /** Completes the call as currently unavailable. */
  fun fail() {
    outcome.complete(SourceResult.Failure(SourceFailure.Unavailable))
  }
}

/** One chapter refresh of a [ControlledSource] that waits for the test. */
private class PendingRefresh(private val request: ChapterRefreshRequest) {
  private val completion = CompletableDeferred<ChapterRefreshCompletion>()

  suspend fun await(): ChapterRefreshCompletion = completion.await()

  /** Completes the request with the chapters [localKeys] in order. */
  fun complete(vararg localKeys: String) {
    completion.complete(completed(request, *localKeys))
  }

  /** Completes the request with a completion bound to another request. */
  fun completeForeign() {
    val foreign = ChapterRefreshGate(request.title).issue()

    completion.complete(completed(foreign, "c1"))
  }
}

/**
 * A source whose details and chapter refreshes wait until the test completes
 * them, in call order.
 */
private class ControlledSource(identity: SourceIdentity) {
  private val details = Channel<PendingDetails>(Channel.UNLIMITED)
  private val refreshes = Channel<PendingRefresh>(Channel.UNLIMITED)

  /** The number of chapter refreshes that the source received. */
  var refreshCount = 0
    private set

  val backend: SourceBackend =
    ScriptedBackend(
      identity = identity,
      onDetails = { title ->
        val pending = PendingDetails(title)

        details.send(pending)

        pending.await()
      },
      onRefreshChapters = { request ->
        val pending = PendingRefresh(request)

        refreshCount += 1
        refreshes.send(pending)

        pending.await()
      },
    )

  suspend fun nextDetails(): PendingDetails = details.receive()

  suspend fun nextRefresh(): PendingRefresh = refreshes.receive()
}

/** Runs [block] on one thread, failing a hung test instead of blocking. */
private fun loadTest(block: suspend CoroutineScope.() -> Unit) {
  runBlocking { withTimeout(5.seconds) { block() } }
}

/** Starts a load of [titleKey] that runs until its first suspension. */
private fun CoroutineScope.startLoad(
  load: WholeTitleLoad,
  titleKey: SourceTitleKey,
): Deferred<TitleLoadResult> =
  async(start = CoroutineStart.UNDISPATCHED) { load.load(titleKey) }

private fun wholeTitleLoad(
  titles: Titles,
  vararg backends: SourceBackend,
): WholeTitleLoad = WholeTitleLoad(SourceRegistry.of(backends.toList()), titles)

private fun details(
  title: SourceTitleKey,
  displayName: String,
  description: String?,
): TitleDetailsOutcome =
  SourceResult.Success(
    SourceTitleDetails(SourceTitle(title, displayName), description)
  )

private fun completion(
  request: ChapterRefreshRequest,
  status: ChapterSequenceStatus,
  chapters: List<SourceChapter>,
): ChapterRefreshCompletion =
  ChapterRefreshCompletion.completed(request, status, chapters)

/** Completes [request] with the chapters [localKeys] of its title in order. */
private fun completed(
  request: ChapterRefreshRequest,
  vararg localKeys: String,
): ChapterRefreshCompletion {
  val chapters = localKeys.map { key ->
    SourceChapter(SourceChapterKey(request.title, key), "Chapter $key")
  }

  return completion(request, ChapterSequenceStatus.COMPLETE, chapters)
}

/** Returns the progress of a title that [observation] has just reconciled. */
private fun progressOf(
  observation: AcceptedTitleObservation
): ProgressSnapshot {
  val chapters =
    observation.chapters.mapIndexed { index, accepted ->
      chapter(
        chapterId(index),
        accepted.alias.localKey.text,
        accepted.displayName,
      )
    }

  return progressSnapshot(
    titleId = TITLE_ID,
    alias = observation.alias,
    chapters = chapters,
  )
}

private fun localKeys(observation: AcceptedTitleObservation): List<String> =
  observation.chapters.map { it.alias.localKey.text }

private val SOURCE = SourceIdentity("source")

private val OTHER_SOURCE = SourceIdentity("other-source")

private val TITLE = SourceTitleKey(SOURCE, "title")

private val OTHER_TITLE = SourceTitleKey(SOURCE, "other-title")

/** The local key of [TITLE] under another source. */
private val SAME_KEY_ELSEWHERE = SourceTitleKey(OTHER_SOURCE, "title")

/** The UUIDv7 title identity with a zero timestamp and zero random bits. */
private val TITLE_ID =
  checkNotNull(TitleId.ofOrNull(Uuid.fromLongs(0x7000L, Long.MIN_VALUE)))
