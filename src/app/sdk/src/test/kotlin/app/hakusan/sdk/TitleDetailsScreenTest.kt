package app.hakusan.sdk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TitleDetailsScreenTest {
  private val beginning: ScreenReadingStart = ScreenReadingStart.Beginning
  private val id = titleId(1)
  private val key = titleKey("title")
  private val first = chapter(1, "c1", isRead = true)
  private val second = chapter(2, "c2", isRead = false)
  private val third = chapter(3, "c3", isRead = false)
  private val omittedKey = ScreenChapterKey(key, "omitted")

  private val omittedPosition =
    ScreenReadingPosition(id, chapterId(9), ScreenContentUnitKind.PAGE, 4)

  @Test
  fun detailsKeepAnOwnedCopyInCanonicalOrder() {
    val chapters = mutableListOf(third, first, second)

    val expected = chapters.toList()

    val screen = details(chapters, ready(third))

    chapters.clear()
    assertEquals(expected, screen.chapters)

    assertThrows<UnsupportedOperationException> {
      (screen.chapters as MutableList).clear()
    }
  }

  @Test
  fun emptyDetailsWithoutAChapterAreValid() {
    val screen =
      details(
        emptyList(),
        ContinueState.Unavailable(ContinueUnavailableReason.NoAvailableChapter),
      )

    assertTrue(screen.chapters.isEmpty())
  }

  @Test
  fun detailsRejectAChapterOfAnotherTitle() {
    val otherId = chapter(4, "c4", isRead = false).copy(titleId = titleId(2))

    val otherKey =
      chapter(4, "c4", isRead = false)
        .copy(key = ScreenChapterKey(titleKey("other"), "c4"))

    val otherSourceKey =
      chapter(4, "c4", isRead = false)
        .copy(key = ScreenChapterKey(titleKey("title", otherSource), "c4"))

    listOf(otherId, otherKey, otherSourceKey).forEach { foreign ->
      assertThrows<IllegalArgumentException> {
        details(listOf(second, foreign), ready(second))
      }
    }
  }

  @Test
  fun detailsRejectARepeatedChapterIdentityOrKey() {
    val repeatedId = chapter(5, "c5", isRead = false).copy(id = second.id)

    val repeatedKey = chapter(5, "c5", isRead = false).copy(key = second.key)

    assertThrows<IllegalArgumentException> {
      details(listOf(second, repeatedId), ready(second))
    }

    assertThrows<IllegalArgumentException> {
      details(listOf(second, repeatedKey), ready(second))
    }
  }

  @Test
  fun readyTargetNamesOneListedChapter() {
    val absent = ContinueTarget(id, chapterId(9), omittedKey, beginning)

    val mismatchedKey =
      ContinueTarget(id, second.id, third.key, ScreenReadingStart.Beginning)

    assertEquals(
      ready(second),
      details(chapters(), ready(second)).continueState,
    )

    listOf(absent, mismatchedKey).forEach { target ->
      assertThrows<IllegalArgumentException> {
        details(chapters(), ContinueState.Ready(target))
      }
    }
  }

  @Test
  fun readyTargetOnAReadChapterStartsAtTheBeginning() {
    val allRead = listOf(first, second.copy(isRead = true))

    assertEquals(
      ready(allRead.last()),
      details(allRead, ready(allRead.last())).continueState,
    )
  }

  @Test
  fun resumedTargetNamesAnUnreadChapter() {
    val resumed = resume(second, unitIndex = 3)

    assertEquals(resumed, details(chapters(), resumed).continueState)

    assertThrows<IllegalArgumentException> {
      details(chapters(), resume(first, unitIndex = 0))
    }
  }

  @Test
  fun noAvailableChapterRequiresAnEmptyList() {
    assertThrows<IllegalArgumentException> {
      details(
        chapters(),
        ContinueState.Unavailable(ContinueUnavailableReason.NoAvailableChapter),
      )
    }
  }

  @Test
  fun unavailableSavedTargetNamesNoListedChapter() {
    val state = unavailable(omittedKey, omittedPosition)

    assertEquals(state, details(chapters(), state).continueState)
    assertEquals(state, details(emptyList(), state).continueState)

    val listedId =
      unavailable(omittedKey, omittedPosition.copy(chapterId = second.id))

    val listedKey = unavailable(second.key, omittedPosition)

    listOf(listedId, listedKey).forEach { contradiction ->
      assertThrows<IllegalArgumentException> {
        details(chapters(), contradiction)
      }
    }
  }

  @Test
  fun unavailableSavedTargetBelongsToTheTitle() {
    val foreignKey =
      unavailable(
        ScreenChapterKey(titleKey("other"), "omitted"),
        omittedPosition,
      )

    val foreignPosition =
      unavailable(omittedKey, omittedPosition.copy(titleId = titleId(2)))

    listOf(foreignKey, foreignPosition).forEach { contradiction ->
      assertThrows<IllegalArgumentException> {
        details(chapters(), contradiction)
      }
    }
  }

  @Test
  fun resumedPositionNamesTheTargetTitleAndChapter() {
    val position =
      ScreenReadingPosition(id, second.id, ScreenContentUnitKind.PAGE, 0)

    assertThrows<IllegalArgumentException> {
      ContinueTarget(
        id,
        third.id,
        third.key,
        ScreenReadingStart.Resume(position),
      )
    }

    assertThrows<IllegalArgumentException> {
      ContinueTarget(
        id,
        second.id,
        second.key,
        ScreenReadingStart.Resume(position.copy(titleId = titleId(2))),
      )
    }
  }

  @Test
  fun readingPositionRejectsANegativeUnit() {
    assertThrows<IllegalArgumentException> {
      ScreenReadingPosition(id, second.id, ScreenContentUnitKind.PAGE, -1)
    }
  }

  private fun chapters() = listOf(first, second, third)

  private fun chapter(suffix: Int, local: String, isRead: Boolean) =
    DetailsChapterItem(
      id = chapterId(suffix),
      titleId = id,
      key = ScreenChapterKey(key, local),
      displayName = "Chapter $suffix",
      isRead = isRead,
    )

  private fun details(
    chapters: List<DetailsChapterItem>,
    continueState: ContinueState,
  ) =
    TitleDetailsScreen.of(
      id = id,
      key = key,
      sourceDisplayName = "Source",
      displayName = "Title",
      description = null,
      chapters = chapters,
      continueState = continueState,
    )

  private fun target(chapter: DetailsChapterItem, start: ScreenReadingStart) =
    ContinueTarget(id, chapter.id, chapter.key, start)

  private fun ready(chapter: DetailsChapterItem): ContinueState =
    ContinueState.Ready(target(chapter, beginning))

  private fun resume(
    chapter: DetailsChapterItem,
    unitIndex: Int,
  ): ContinueState {
    val position =
      ScreenReadingPosition(
        id,
        chapter.id,
        ScreenContentUnitKind.PROVIDER_SEGMENT,
        unitIndex,
      )

    return ContinueState.Ready(
      target(chapter, ScreenReadingStart.Resume(position))
    )
  }

  private fun unavailable(
    chapterKey: ScreenChapterKey,
    position: ScreenReadingPosition,
  ): ContinueState =
    ContinueState.Unavailable(
      ContinueUnavailableReason.SavedTargetUnavailable(chapterKey, position)
    )

  private fun describe(outcome: ContinueSelectionResult): String =
    when (outcome) {
      is ContinueSelectionResult.Selected ->
        when (outcome.target.start) {
          ScreenReadingStart.Beginning -> "beginning"
          is ScreenReadingStart.Resume -> "resume"
        }

      is ContinueSelectionResult.Unavailable ->
        when (outcome.reason) {
          is ContinueUnavailableReason.SavedTargetUnavailable -> "saved"
          ContinueUnavailableReason.NoAvailableChapter -> "none"
        }

      ContinueSelectionResult.TitleNotFound -> "missing"
    }
}
