package app.hakusan.debug.source

import app.hakusan.extensions.ChapterRefreshAcceptance
import app.hakusan.extensions.ChapterRefreshGate
import app.hakusan.extensions.ChapterSnapshot
import app.hakusan.extensions.SourceChapterContent
import app.hakusan.extensions.SourceChapterKey
import app.hakusan.extensions.SourceContentUnitKind
import app.hakusan.extensions.SourceFailure
import app.hakusan.extensions.SourceIdentity
import app.hakusan.extensions.SourceResult
import app.hakusan.extensions.SourceTitle
import app.hakusan.extensions.SourceTitleKey
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class DeterministicSourceTest {
  @Test
  fun `instances reproduce stable browse and details`() = runImmediateTest {
    val first = DeterministicSource()
    val second = DeterministicSource()

    assertEquals(SourceIdentity("app.hakusan.debug.source"), first.identity)
    assertEquals(first.identity, second.identity)
    assertEquals("Deterministic source", first.displayName)

    val firstBrowse = first.browse().successValue()
    val secondBrowse = second.browse().successValue()
    assertEquals(firstBrowse, secondBrowse)
    assertEquals(first.identity, firstBrowse.source)
    assertEquals(1, firstBrowse.titles.size)

    val title = firstBrowse.titles.single()
    assertEquals("canonical-order-fixture", title.key.key)
    assertEquals("Canonical Order Fixture", title.displayName)

    val details = first.details(title.key).successValue()
    assertEquals(title, details.title)
    assertEquals(
      "Deterministic content for Hakusan checkpoint verification.",
      details.description,
    )
    assertEquals(details, second.details(title.key).successValue())

    val firstSnapshot = fixtureSnapshot(first)
    val secondSnapshot = fixtureSnapshot(second)
    assertEquals(firstSnapshot, secondSnapshot)
    assertEquals(
      firstSnapshot.chapters.map { first.content(it.key) },
      secondSnapshot.chapters.map { second.content(it.key) },
    )
  }

  @Test
  fun `browse unavailability is explicit and repeatable`() = runImmediateTest {
    val source = DeterministicSource(UnavailableOperation.BROWSE)

    assertEquals(
      SourceResult.Failure(SourceFailure.Unavailable),
      source.browse(),
    )
    assertEquals(source.browse(), source.browse())
  }

  @Test
  fun `details validate ownership before availability`() = runImmediateTest {
    val source = DeterministicSource(UnavailableOperation.DETAILS)
    val valid = fixtureTitle(DeterministicSource()).key
    val foreign = SourceTitleKey(SourceIdentity("foreign"), valid.key)
    val unknown = SourceTitleKey(source.identity, "unknown-title")

    assertEquals(
      SourceResult.Failure(SourceFailure.Unavailable),
      source.details(valid),
    )
    assertEquals(
      SourceFailure.Unavailable,
      source.details(unknown).failureValue(),
    )
    assertIllegalArgument {
      source.details(foreign)
    }
  }

  @Test
  fun `chapter refresh preserves canonical order and request`() =
    runImmediateTest {
      val source = DeterministicSource()
      val title = fixtureTitle(source).key
      val gate = ChapterRefreshGate(title)
      val firstRequest = gate.issue()
      val firstCompletion = source.refreshChapters(firstRequest)
      val currentRequest = gate.issue()
      val currentCompletion = source.refreshChapters(currentRequest)

      assertEquals(firstRequest, firstCompletion.request)
      assertEquals(currentRequest, currentCompletion.request)

      val snapshot = gate.accept(currentCompletion)
        .acceptedResult()
        .successValue()
      assertEquals(title, snapshot.title)
      assertEquals(
        listOf("opening", "middle", "final"),
        snapshot.chapters.map { it.key.key },
      )
      assertEquals(
        listOf("Chapter 10", "Chapter 2", "Chapter 1"),
        snapshot.chapters.map { it.displayName },
      )
      assertNotEquals(
        snapshot.chapters.sortedBy { it.displayName },
        snapshot.chapters,
      )
      assertSame(
        ChapterRefreshAcceptance.RejectedNotCurrent,
        gate.accept(firstCompletion),
      )
    }

  @Test
  fun `chapter failures preserve correlation and validate ownership`() =
    runImmediateTest {
      val source = DeterministicSource(UnavailableOperation.CHAPTERS)
      val valid = fixtureTitle(DeterministicSource()).key
      val unknown = SourceTitleKey(source.identity, "unknown-title")
      val foreign = SourceTitleKey(SourceIdentity("foreign"), valid.key)
      val gate = ChapterRefreshGate(valid)
      val firstRequest = gate.issue()
      val firstFailure = source.refreshChapters(firstRequest)
      val currentRequest = gate.issue()
      val currentFailure = source.refreshChapters(currentRequest)

      assertEquals(firstRequest, firstFailure.request)
      assertEquals(currentRequest, currentFailure.request)
      assertSame(
        ChapterRefreshAcceptance.RejectedNotCurrent,
        gate.accept(firstFailure),
      )
      assertSame(
        SourceFailure.Unavailable,
        gate.accept(currentFailure)
          .acceptedResult()
          .failureValue(),
      )
      assertSame(
        ChapterRefreshAcceptance.RejectedNotCurrent,
        gate.accept(currentFailure),
      )
      assertSame(SourceFailure.Unavailable, refreshFailure(source, unknown))

      val foreignGate = ChapterRefreshGate(foreign)
      assertIllegalArgument {
        source.refreshChapters(foreignGate.issue())
      }
    }

  @Test
  fun `chapter content preserves all controlled provider units`() =
    runImmediateTest {
      val source = DeterministicSource()
      val snapshot = fixtureSnapshot(source)

      val content = snapshot.chapters.associate { chapter ->
        chapter.key to source.content(chapter.key).successValue()
      }

      assertContent(
        chapter = snapshot.chapters[0].key,
        content = content.getValue(snapshot.chapters[0].key),
        kinds = listOf(
          SourceContentUnitKind.PAGE,
          SourceContentUnitKind.PAGE,
          SourceContentUnitKind.PAGE,
        ),
        references = listOf(
          "opening-page-10",
          "opening-page-2",
          "opening-page-1",
        ),
      )
      assertContent(
        chapter = snapshot.chapters[1].key,
        content = content.getValue(snapshot.chapters[1].key),
        kinds = listOf(
          SourceContentUnitKind.PROVIDER_SEGMENT,
          SourceContentUnitKind.PROVIDER_SEGMENT,
          SourceContentUnitKind.PROVIDER_SEGMENT,
        ),
        references = listOf(
          "middle-segment-10",
          "middle-segment-2",
          "middle-segment-1",
        ),
      )
      assertContent(
        chapter = snapshot.chapters[2].key,
        content = content.getValue(snapshot.chapters[2].key),
        kinds = listOf(SourceContentUnitKind.PAGE),
        references = listOf("final-page"),
      )

      val opening = snapshot.chapters.first().key
      assertEquals(source.content(opening), source.content(opening))
    }

  @Test
  fun `content validates ownership before availability`() = runImmediateTest {
    val source = DeterministicSource(UnavailableOperation.CONTENT)
    val title = fixtureTitle(DeterministicSource()).key
    val valid = SourceChapterKey(title, "opening")
    val foreign = SourceChapterKey(
      SourceTitleKey(SourceIdentity("foreign"), title.key),
      valid.key,
    )
    val unknownTitle = SourceChapterKey(
      SourceTitleKey(source.identity, "unknown-title"),
      valid.key,
    )
    val unknownChapter = SourceChapterKey(title, "unknown-chapter")

    assertEquals(
      SourceResult.Failure(SourceFailure.Unavailable),
      source.content(valid),
    )
    assertEquals(
      SourceFailure.Unavailable,
      source.content(unknownTitle).failureValue(),
    )
    assertEquals(
      SourceFailure.Unavailable,
      source.content(unknownChapter).failureValue(),
    )
    assertIllegalArgument {
      source.content(foreign)
    }
  }

  private suspend fun fixtureTitle(
    source: DeterministicSource,
  ): SourceTitle = source.browse().successValue().titles.single()

  private suspend fun fixtureSnapshot(
    source: DeterministicSource,
  ): ChapterSnapshot {
    val title = fixtureTitle(source).key
    val gate = ChapterRefreshGate(title)
    return gate.accept(source.refreshChapters(gate.issue()))
      .acceptedResult()
      .successValue()
  }

  private suspend fun refreshFailure(
    source: DeterministicSource,
    title: SourceTitleKey,
  ): SourceFailure {
    val gate = ChapterRefreshGate(title)
    return gate.accept(source.refreshChapters(gate.issue()))
      .acceptedResult()
      .failureValue()
  }

  private fun assertContent(
    chapter: SourceChapterKey,
    content: SourceChapterContent,
    kinds: List<SourceContentUnitKind>,
    references: List<String>,
  ) {
    assertEquals(chapter, content.chapter)
    assertEquals(kinds, content.units.map { it.kind })
    assertEquals(references, content.units.map { it.reference })
  }
}

private fun <Value> SourceResult<Value>.successValue(): Value = when (this) {
  is SourceResult.Success -> value
  is SourceResult.Failure -> fail("Expected success, got $error")
}

private fun SourceResult<*>.failureValue(): SourceFailure = when (this) {
  is SourceResult.Success -> fail("Expected failure, got $value")
  is SourceResult.Failure -> error
}

private fun ChapterRefreshAcceptance.acceptedResult(
): SourceResult<ChapterSnapshot> =
  when (this) {
    is ChapterRefreshAcceptance.Accepted -> result
    ChapterRefreshAcceptance.RejectedNotCurrent -> {
      fail("Expected current completion")
    }
  }

private fun runImmediateTest(
  block: suspend () -> Unit,
) {
  var completion: Result<Unit>? = null
  block.startCoroutine(
    object : Continuation<Unit> {
      override val context = EmptyCoroutineContext

      override fun resumeWith(result: Result<Unit>) {
        completion = result
      }
    },
  )
  checkNotNull(completion) {
    "Deterministic source operation suspended unexpectedly."
  }.getOrThrow()
}

private suspend fun assertIllegalArgument(
  block: suspend () -> Unit,
) {
  try {
    block()
    fail("Expected IllegalArgumentException")
  } catch (_: IllegalArgumentException) {
    // Expected.
  }
}
