package app.hakusan.sdk

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TitleDetailsScreenContractTest {
  private val sourceId = ScreenSourceId("source")
  private val titleKey = ScreenTitleKey(sourceId, "title")
  private val titleId = ScreenTitleId(TITLE_ID)
  private val opening = chapter(
    id = CHAPTER_ID_1,
    key = "opening",
    displayName = "Chapter 10",
  )
  private val middle = chapter(
    id = CHAPTER_ID_2,
    key = "middle",
    displayName = "Chapter 2",
  )
  private val final = chapter(
    id = CHAPTER_ID_3,
    key = "final",
    displayName = "Chapter 1",
  )

  @Test
  fun `details preserve canonical order metadata and collection ownership`() {
    val mutableChapters = mutableListOf(opening, middle, final)
    val screen = details(
      chapters = mutableChapters,
      continueState = ContinueState.Ready(beginningTarget(opening)),
    )

    mutableChapters.reverse()

    assertEquals(listOf(opening, middle, final), screen.chapters)
    assertEquals("  Source  ", screen.sourceDisplayName)
    assertEquals("", screen.displayName)
    assertEquals("  Description  ", screen.description)
    assertThrows(UnsupportedOperationException::class.java) {
      (screen.chapters as MutableList).clear()
    }
  }

  @Test
  fun `details reject foreign duplicate and conflicting chapters`() {
    assertThrows(IllegalArgumentException::class.java) {
      details(
        chapters = listOf(
          opening,
          opening.copy(key = ScreenChapterKey(titleKey, "other")),
        ),
        continueState = ContinueState.Ready(beginningTarget(opening)),
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      details(
        chapters = listOf(
          opening,
          middle.copy(key = opening.key),
        ),
        continueState = ContinueState.Ready(beginningTarget(opening)),
      )
    }
    val foreignTitle = ScreenTitleKey(sourceId, "other")
    val foreignChapter = opening.copy(
      key = ScreenChapterKey(foreignTitle, "opening"),
    )
    assertThrows(IllegalArgumentException::class.java) {
      details(
        chapters = listOf(foreignChapter),
        continueState = ContinueState.Ready(
          beginningTarget(foreignChapter),
        ),
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      details(
        chapters = listOf(
          opening.copy(
            titleId = ScreenTitleId(OTHER_TITLE_ID),
          ),
        ),
        continueState = ContinueState.Ready(beginningTarget(opening)),
      )
    }
  }

  @Test
  fun `empty details require an unavailable Continue state`() {
    val screen = details(
      chapters = emptyList(),
      continueState = ContinueState.Unavailable(
        ContinueUnavailableReason.NoAvailableChapter,
      ),
    )

    assertEquals(emptyList<DetailsChapterItem>(), screen.chapters)
    assertThrows(IllegalArgumentException::class.java) {
      details(
        chapters = listOf(opening),
        continueState = ContinueState.Unavailable(
          ContinueUnavailableReason.NoAvailableChapter,
        ),
      )
    }
  }

  @Test
  fun `available resume is current and unread regardless of membership`() {
    val position = ScreenReadingPosition(
      titleId = titleId,
      chapterId = middle.id,
      unitKind = ScreenContentUnitKind.PROVIDER_SEGMENT,
      unitIndex = 2,
    )
    val target = ContinueTarget(
      titleId = titleId,
      chapterId = middle.id,
      chapterKey = middle.key,
      start = ScreenReadingStart.Resume(position),
    )
    val libraryScreen = details(
      chapters = listOf(opening, middle, final),
      isInLibrary = true,
      continueState = ContinueState.Ready(target),
    )
    val transientScreen = details(
      chapters = listOf(opening, middle, final),
      isInLibrary = false,
      continueState = ContinueState.Ready(target),
    )

    assertEquals(ContinueState.Ready(target), libraryScreen.continueState)
    assertEquals(ContinueState.Ready(target), transientScreen.continueState)
    assertThrows(IllegalArgumentException::class.java) {
      details(
        chapters = listOf(opening, middle.copy(isRead = true), final),
        isInLibrary = true,
        continueState = ContinueState.Ready(target),
      )
    }
  }

  @Test
  fun `beginning Continue selects first unread then final reread`() {
    val partlyRead = listOf(
      opening.copy(isRead = true),
      middle,
      final,
    )
    val unreadScreen = details(
      chapters = partlyRead,
      continueState = ContinueState.Ready(beginningTarget(middle)),
    )
    val allRead = partlyRead.map { it.copy(isRead = true) }
    val rereadScreen = details(
      chapters = allRead,
      continueState = ContinueState.Ready(beginningTarget(final)),
    )

    assertEquals(
      ContinueState.Ready(beginningTarget(middle)),
      unreadScreen.continueState,
    )
    assertEquals(
      ContinueState.Ready(beginningTarget(final)),
      rereadScreen.continueState,
    )
    assertThrows(IllegalArgumentException::class.java) {
      details(
        chapters = partlyRead,
        continueState = ContinueState.Ready(beginningTarget(final)),
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      details(
        chapters = allRead,
        continueState = ContinueState.Ready(beginningTarget(middle)),
      )
    }
  }

  @Test
  fun `unavailable saved target cannot silently select another chapter`() {
    val omittedKey = ScreenChapterKey(titleKey, "omitted")
    val position = ScreenReadingPosition(
      titleId = titleId,
      chapterId = ScreenChapterId(OMITTED_CHAPTER_ID),
      unitKind = ScreenContentUnitKind.PAGE,
      unitIndex = 4,
    )
    val reason = ContinueUnavailableReason.SavedTargetUnavailable(
      chapterKey = omittedKey,
      position = position,
    )
    val screen = details(
      chapters = listOf(opening, middle, final),
      isInLibrary = false,
      continueState = ContinueState.Unavailable(reason),
    )

    assertEquals(ContinueState.Unavailable(reason), screen.continueState)
    assertThrows(IllegalArgumentException::class.java) {
      details(
        chapters = listOf(
          opening,
          middle,
          final,
          chapter(OMITTED_CHAPTER_ID, "omitted", "Omitted"),
        ),
        isInLibrary = true,
        continueState = ContinueState.Unavailable(reason),
      )
    }
  }

  @Test
  fun `Continue target rejects a position for another chapter`() {
    val position = ScreenReadingPosition(
      titleId = titleId,
      chapterId = middle.id,
      unitKind = ScreenContentUnitKind.PAGE,
      unitIndex = 0,
    )

    assertThrows(IllegalArgumentException::class.java) {
      ContinueTarget(
        titleId = titleId,
        chapterId = opening.id,
        chapterKey = opening.key,
        start = ScreenReadingStart.Resume(position),
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      ScreenReadingPosition(
        titleId = titleId,
        chapterId = opening.id,
        unitKind = ScreenContentUnitKind.PAGE,
        unitIndex = -1,
      )
    }
  }

  private fun details(
    chapters: Iterable<DetailsChapterItem>,
    continueState: ContinueState,
    isInLibrary: Boolean = false,
  ): TitleDetailsScreen = TitleDetailsScreen.of(
    id = titleId,
    key = titleKey,
    sourceDisplayName = "  Source  ",
    displayName = "",
    description = "  Description  ",
    chapters = chapters,
    isInLibrary = isInLibrary,
    continueState = continueState,
  )

  private fun chapter(
    id: UUID,
    key: String,
    displayName: String,
  ): DetailsChapterItem = DetailsChapterItem(
    id = ScreenChapterId(id),
    titleId = titleId,
    key = ScreenChapterKey(titleKey, key),
    displayName = displayName,
    isRead = false,
  )

  private fun beginningTarget(
    chapter: DetailsChapterItem,
  ): ContinueTarget = ContinueTarget(
    titleId = titleId,
    chapterId = chapter.id,
    chapterKey = chapter.key,
    start = ScreenReadingStart.Beginning,
  )

  private companion object {
    val TITLE_ID: UUID =
      UUID.fromString("00000000-0000-7000-8000-000000000001")
    val CHAPTER_ID_1: UUID =
      UUID.fromString("00000000-0000-7000-8000-000000000011")
    val CHAPTER_ID_2: UUID =
      UUID.fromString("00000000-0000-7000-8000-000000000012")
    val CHAPTER_ID_3: UUID =
      UUID.fromString("00000000-0000-7000-8000-000000000013")
    val OMITTED_CHAPTER_ID: UUID =
      UUID.fromString("00000000-0000-7000-8000-000000000014")
    val OTHER_TITLE_ID: UUID =
      UUID.fromString("00000000-0000-7000-8000-000000000002")
  }
}
