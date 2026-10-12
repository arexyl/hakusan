package app.hakusan.extensions

import kotlin.coroutines.cancellation.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class CorrelatedSourceTest {
  private val source = SourceIdentity("source")
  private val otherSource = SourceIdentity("other-source")
  private val title = SourceTitleKey(source, "title")
  private val chapter = SourceChapterKey(title, "chapter")
  private val page = SourceContentUnit(SourceContentUnitKind.PAGE, "page")

  @Test
  fun `results that belong to their request pass unchanged`() {
    val browse =
      SourceBrowseResult.create(source, listOf(SourceTitle(title, "T")))

    val details =
      SourceResult.Success(SourceTitleDetails(SourceTitle(title, "T"), null))

    val content = SourceChapterContent.create(chapter, listOf(page))

    val correlated =
      CorrelatedSource(
        ScriptedBackend(
          identity = source,
          onBrowse = { browse },
          onDetails = { details },
          onContent = { content },
        )
      )

    assertSame(browse, completeWithoutSuspension { correlated.browse() })
    assertSame(details, completeWithoutSuspension { correlated.details(title) })

    assertSame(
      content,
      completeWithoutSuspension { correlated.content(chapter) },
    )
  }

  @Test
  fun `a browse result owned by another source is invalid`() {
    val foreignTitles = listOf(SourceTitle(foreignTitle(), "T"))

    for (titles in listOf(emptyList(), foreignTitles)) {
      val foreign = SourceBrowseResult.create(otherSource, titles)

      val correlated =
        CorrelatedSource(
          ScriptedBackend(identity = source, onBrowse = { foreign })
        )

      assertEquals(
        SourceFailure.InvalidBrowseResult(BrowseResultRejection.FOREIGN_OWNER),
        completeWithoutSuspension { correlated.browse() }.failureValue(),
      )
    }
  }

  @Test
  fun `details of another title are invalid`() {
    val otherTitle = SourceTitleKey(source, "other-title")

    val details = SourceTitleDetails(SourceTitle(otherTitle, "Other"), null)

    val correlated =
      CorrelatedSource(
        ScriptedBackend(
          identity = source,
          onDetails = { SourceResult.Success(details) },
        )
      )

    assertEquals(
      SourceFailure.InvalidTitleDetails(TitleDetailsRejection.FOREIGN_TITLE),
      completeWithoutSuspension { correlated.details(title) }.failureValue(),
    )
  }

  @Test
  fun `content of another chapter is invalid`() {
    val otherChapter = SourceChapterKey(title, "other-chapter")

    val content = SourceChapterContent.create(otherChapter, listOf(page))

    val correlated =
      CorrelatedSource(
        ScriptedBackend(identity = source, onContent = { content })
      )

    assertEquals(
      SourceFailure.InvalidChapterContent(
        ChapterContentRejection.FOREIGN_CHAPTER
      ),
      completeWithoutSuspension { correlated.content(chapter) }.failureValue(),
    )
  }

  @Test
  fun `backend failures pass unchanged`() {
    val unavailable = SourceResult.Failure(SourceFailure.Unavailable)

    val emptyContent = SourceChapterContent.create(chapter, emptyList())

    val correlated =
      CorrelatedSource(
        ScriptedBackend(
          identity = source,
          onBrowse = { unavailable },
          onDetails = { unavailable },
          onContent = { emptyContent },
        )
      )

    assertSame(unavailable, completeWithoutSuspension { correlated.browse() })

    assertSame(
      unavailable,
      completeWithoutSuspension { correlated.details(title) },
    )

    assertSame(
      emptyContent,
      completeWithoutSuspension { correlated.content(chapter) },
    )
  }

  @Test
  fun `a refresh completion passes unchanged`() {
    val request = ChapterRefreshGate(title).issue()

    val completion =
      ChapterRefreshCompletion.failed(request, SourceFailure.Unavailable)

    val correlated =
      CorrelatedSource(
        ScriptedBackend(identity = source, onRefresh = { completion })
      )

    assertSame(
      completion,
      completeWithoutSuspension { correlated.refreshChapters(request) },
    )
  }

  @Test
  fun `a completion for another request stays arbitration`() {
    val gate = ChapterRefreshGate(title)

    val stale = gate.issue()

    val current = gate.issue()

    val completion =
      ChapterRefreshCompletion.completed(
        request = stale,
        status = ChapterSequenceStatus.COMPLETE,
        chapters = emptyList(),
      )

    val correlated =
      CorrelatedSource(
        ScriptedBackend(identity = source, onRefresh = { completion })
      )

    val returned = completeWithoutSuspension {
      correlated.refreshChapters(current)
    }

    assertSame(completion, returned)

    assertSame(
      ChapterRefreshAcceptance.RejectedNotCurrent,
      gate.accept(returned),
    )
  }

  @Test
  fun `a key of another source violates the caller precondition`() {
    val foreignChapter = SourceChapterKey(foreignTitle(), "chapter")

    val foreignRequest = ChapterRefreshGate(foreignTitle()).issue()

    val correlated = CorrelatedSource(ScriptedBackend(identity = source))

    assertThrows(IllegalArgumentException::class.java) {
      completeWithoutSuspension { correlated.details(foreignTitle()) }
    }

    assertThrows(IllegalArgumentException::class.java) {
      completeWithoutSuspension { correlated.refreshChapters(foreignRequest) }
    }

    assertThrows(IllegalArgumentException::class.java) {
      completeWithoutSuspension { correlated.content(foreignChapter) }
    }
  }

  @Test
  fun `cancellation and defects of the backend propagate unchanged`() {
    val request = ChapterRefreshGate(title).issue()

    val cancellation = CancellationException("Cancelled")

    for (thrown in listOf(cancellation, IllegalStateException())) {
      val correlated =
        CorrelatedSource(
          ScriptedBackend(
            identity = source,
            onBrowse = { throw thrown },
            onDetails = { throw thrown },
            onRefresh = { throw thrown },
            onContent = { throw thrown },
          )
        )

      val calls =
        listOf<suspend () -> Any>(
          { correlated.browse() },
          { correlated.details(title) },
          { correlated.refreshChapters(request) },
          { correlated.content(chapter) },
        )

      for (call in calls) {
        val propagated =
          assertThrows(Throwable::class.java) {
            completeWithoutSuspension(call)
          }

        assertSame(thrown, propagated)
      }
    }
  }

  @Test
  fun `identity and display metadata come from the backend`() {
    val correlated = CorrelatedSource(ScriptedBackend(identity = source))

    assertEquals(source, correlated.identity)
    assertEquals("Scripted", correlated.displayName)
  }

  private fun foreignTitle(): SourceTitleKey =
    SourceTitleKey(otherSource, "title")
}

private typealias BrowseOutcome =
  SourceResult<SourceBrowseResult, SourceBrowseFailure>

private typealias DetailsOutcome =
  SourceResult<SourceTitleDetails, SourceDetailsFailure>

private typealias ContentOutcome =
  SourceResult<SourceChapterContent, SourceContentFailure>

/** A backend that answers with scripted outcomes and never suspends. */
private class ScriptedBackend(
  override val identity: SourceIdentity,
  private val onBrowse: () -> BrowseOutcome = { unexpected() },
  private val onDetails: (SourceTitleKey) -> DetailsOutcome = { unexpected() },
  private val onRefresh: (ChapterRefreshRequest) -> ChapterRefreshCompletion = {
    unexpected()
  },
  private val onContent: (SourceChapterKey) -> ContentOutcome = {
    unexpected()
  },
) : SourceBackend {
  override val displayName: String = "Scripted"

  override suspend fun browse(): BrowseOutcome = onBrowse()

  override suspend fun details(title: SourceTitleKey): DetailsOutcome =
    onDetails(title)

  override suspend fun refreshChapters(
    request: ChapterRefreshRequest
  ): ChapterRefreshCompletion = onRefresh(request)

  override suspend fun content(chapter: SourceChapterKey): ContentOutcome =
    onContent(chapter)
}

private fun unexpected(): Nothing = fail("The backend was not expected to run.")
