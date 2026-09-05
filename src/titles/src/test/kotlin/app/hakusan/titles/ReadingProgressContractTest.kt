package app.hakusan.titles

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReadingProgressContractTest {
  @Test
  fun `chapter identities and aliases retain exact validated values`() {
    val id = ChapterId(CHAPTER_ID)
    val alias = SourceChapterAlias(
      titleAlias = TITLE_ALIAS,
      sourceChapterKey = " chapter/é ",
    )

    assertEquals(CHAPTER_ID, id.value)
    assertEquals(" chapter/é ", alias.sourceChapterKey)
    assertFalse(
      alias == SourceChapterAlias(TITLE_ALIAS, " chapter/e\u0301 "),
    )
    assertThrows(IllegalArgumentException::class.java) {
      ChapterId(UUID.fromString("00000000-0000-4000-8000-000000000001"))
    }
    assertThrows(IllegalArgumentException::class.java) {
      SourceChapterAlias(TITLE_ALIAS, " ")
    }
  }

  @Test
  fun `chapter snapshot owns canonical order and accepts empty`() {
    val chapters = mutableListOf(
      observed("opening", "Chapter 10"),
      observed("middle", "Chapter 2"),
      observed("final", "Chapter 1"),
    )
    val snapshot = ReconcileChapterSnapshot.of(TITLE_ALIAS, chapters)

    chapters.reverse()

    assertEquals(
      listOf("opening", "middle", "final"),
      snapshot.chapters.map { it.alias.sourceChapterKey },
    )
    assertTrue(
      ReconcileChapterSnapshot.of(TITLE_ALIAS, emptyList()).chapters.isEmpty(),
    )
  }

  @Test
  fun `chapter snapshot rejects foreign and duplicate aliases`() {
    val foreignTitle = SourceTitleAlias("other-source", "title")

    assertThrows(IllegalArgumentException::class.java) {
      ReconcileChapterSnapshot.of(
        TITLE_ALIAS,
        listOf(
          ReconcileSourceChapter(
            SourceChapterAlias(foreignTitle, "chapter"),
            "Chapter",
          ),
        ),
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      ReconcileChapterSnapshot.of(
        TITLE_ALIAS,
        listOf(observed("same", "One"), observed("same", "Two")),
      )
    }
  }

  @Test
  fun `positions and boundaries validate local indices and identities`() {
    val position = ReadingPosition(
      titleId = TITLE_ID,
      chapterId = ChapterId(CHAPTER_ID),
      unitKind = ReadingContentUnitKind.PROVIDER_SEGMENT,
      unitIndex = 3,
    )

    assertEquals(3, position.unitIndex)
    assertEquals(
      position,
      ActualPositionUpdate(
        position = position,
        recency = ProgressEventRecency.CURRENT,
      ).position,
    )
    assertThrows(IllegalArgumentException::class.java) {
      position.copy(unitIndex = -1)
    }
    assertThrows(IllegalArgumentException::class.java) {
      ChapterBoundaryCompletion(
        completedChapterId = position.chapterId,
        startedPosition = position,
        recency = ProgressEventRecency.CURRENT,
      )
    }
  }

  @Test
  fun `reading progress owns state and resume availability`() {
    val opening = chapter(CHAPTER_ID, "opening")
    val final = chapter(OTHER_CHAPTER_ID, "final")
    val canonical = mutableListOf(
      ChapterReadingState(opening, isRead = true),
      ChapterReadingState(final, isRead = false),
    )
    val resume = LibraryResumePosition(
      chapter = final,
      position = ReadingPosition(
        titleId = TITLE_ID,
        chapterId = final.id,
        unitKind = ReadingContentUnitKind.PAGE,
        unitIndex = 1,
      ),
      isCurrentlyAvailable = true,
    )
    val progress = TitleReadingProgress.create(
      titleId = TITLE_ID,
      titleAlias = TITLE_ALIAS,
      isInLibrary = true,
      canonicalChapters = canonical,
      libraryResumePosition = resume,
    )

    canonical.clear()

    assertEquals(listOf(opening.id, final.id), progress.canonicalChapters
      .map { it.chapter.id })
    assertEquals(resume, progress.libraryResumePosition)

    val unavailable = LibraryResumePosition(
      chapter = final,
      position = resume.position,
      isCurrentlyAvailable = false,
    )
    assertTrue(
      TitleReadingProgress.create(
        titleId = TITLE_ID,
        titleAlias = TITLE_ALIAS,
        isInLibrary = true,
        canonicalChapters = emptyList(),
        libraryResumePosition = unavailable,
      ).canonicalChapters.isEmpty(),
    )
  }

  @Test
  fun `reading progress rejects contradictory resume state`() {
    val current = chapter(CHAPTER_ID, "chapter")
    val position = ReadingPosition(
      titleId = TITLE_ID,
      chapterId = current.id,
      unitKind = ReadingContentUnitKind.PAGE,
      unitIndex = 0,
    )

    assertThrows(IllegalArgumentException::class.java) {
      TitleReadingProgress.create(
        titleId = TITLE_ID,
        titleAlias = TITLE_ALIAS,
        isInLibrary = false,
        canonicalChapters = emptyList(),
        libraryResumePosition = LibraryResumePosition(
          chapter = current,
          position = position,
          isCurrentlyAvailable = false,
        ),
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      TitleReadingProgress.create(
        titleId = TITLE_ID,
        titleAlias = TITLE_ALIAS,
        isInLibrary = true,
        canonicalChapters = listOf(
          ChapterReadingState(current, isRead = false),
        ),
        libraryResumePosition = LibraryResumePosition(
          chapter = current,
          position = position,
          isCurrentlyAvailable = false,
        ),
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      TitleReadingProgress.create(
        titleId = TITLE_ID,
        titleAlias = TITLE_ALIAS,
        isInLibrary = true,
        canonicalChapters = listOf(
          ChapterReadingState(current, isRead = true),
        ),
        libraryResumePosition = LibraryResumePosition(
          chapter = current,
          position = position,
          isCurrentlyAvailable = true,
        ),
      )
    }
  }

  private fun observed(
    key: String,
    displayName: String,
  ): ReconcileSourceChapter = ReconcileSourceChapter(
    alias = SourceChapterAlias(TITLE_ALIAS, key),
    displayName = displayName,
  )

  private fun chapter(
    id: UUID,
    sourceKey: String,
  ): Chapter = Chapter(
    id = ChapterId(id),
    titleId = TITLE_ID,
    alias = SourceChapterAlias(TITLE_ALIAS, sourceKey),
    displayName = sourceKey,
  )

  private companion object {
    val TITLE_ALIAS = SourceTitleAlias("source", "title")
    val TITLE_ID = TitleId(
      UUID.fromString("00000000-0000-7000-8000-000000000001"),
    )
    val CHAPTER_ID: UUID =
      UUID.fromString("00000000-0000-7000-8000-000000000002")
    val OTHER_CHAPTER_ID: UUID =
      UUID.fromString("00000000-0000-7000-8000-000000000003")
  }
}
