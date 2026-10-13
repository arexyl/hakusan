package app.hakusan.titles

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ReadingProgressTest {
  @Test
  fun positionKeepsPageOrSegmentGranularity() {
    val page = position(CHAPTER_ID, ContentUnitKind.PAGE, 0)

    val segment = position(CHAPTER_ID, ContentUnitKind.PROVIDER_SEGMENT, 41)

    assertEquals(ContentUnitKind.PAGE, page.unitKind)
    assertEquals(0, page.unitIndex)
    assertEquals(ContentUnitKind.PROVIDER_SEGMENT, segment.unitKind)
    assertEquals(41, segment.unitIndex)
  }

  @Test
  fun negativeUnitIndexIsRejected() {
    val error =
      assertThrows<IllegalArgumentException> {
        position(CHAPTER_ID, ContentUnitKind.PAGE, -1)
      }

    assertEquals("A content unit index must not be negative.", error.message)
  }

  @Test
  fun boundaryCrossesTwoDistinctChapters() {
    val started = position(OTHER_CHAPTER_ID, ContentUnitKind.PAGE, 0)

    val boundary =
      ChapterBoundaryCompletion.of(
        CHAPTER_ID,
        started,
        ProgressEventRecency.REORDERED,
      )

    assertEquals(CHAPTER_ID, boundary.completedChapterId)
    assertEquals(started, boundary.startedPosition)
    assertEquals(ProgressEventRecency.REORDERED, boundary.recency)

    assertThrows<IllegalArgumentException> {
      ChapterBoundaryCompletion.of(
        CHAPTER_ID,
        position(CHAPTER_ID, ContentUnitKind.PAGE, 3),
        ProgressEventRecency.CURRENT,
      )
    }
  }

  private fun position(
    chapterId: ChapterId,
    kind: ContentUnitKind,
    index: Int,
  ): ReadingPosition = ReadingPosition.of(TITLE_ID, chapterId, kind, index)

  private companion object {
    val TITLE_ID = TitleId.generate()
    val CHAPTER_ID = ChapterId.generate()
    val OTHER_CHAPTER_ID = ChapterId.generate()
  }
}
