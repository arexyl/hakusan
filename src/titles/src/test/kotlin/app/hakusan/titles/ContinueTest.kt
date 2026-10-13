package app.hakusan.titles

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ContinueTest {
  @Test
  fun availablePositionResumesAtItsUnit() {
    val chapters = chapters("a", "b", "c")

    val target =
      ResumeTarget.Available(
        chapter = chapters[1],
        unitKind = ContentUnitKind.PROVIDER_SEGMENT,
        unitIndex = 12,
      )

    val snapshot = snapshot(states(chapters, read = setOf(0)), target)

    assertEquals(ContinueResolution.Resume(target), snapshot.resolveContinue())
  }

  @Test
  fun unavailableSavedPositionIsKeptWithoutAnotherChapter() {
    val chapters = chapters("a", "b")

    val omitted = chapter("gone", "Gone")

    val target =
      ResumeTarget.TemporarilyUnavailable(
        chapter = omitted,
        unitKind = ContentUnitKind.PAGE,
        unitIndex = 4,
      )

    val withUnread = snapshot(states(chapters), target)

    val withoutChapters = snapshot(emptyList(), target)

    assertEquals(
      ContinueResolution.SavedTargetUnavailable(target),
      withUnread.resolveContinue(),
    )

    assertEquals(
      ContinueResolution.SavedTargetUnavailable(target),
      withoutChapters.resolveContinue(),
    )
  }

  @Test
  fun withoutPositionTheFirstUnreadChapterOpensInCanonicalOrder() {
    val chapters =
      listOf(
        chapter("c10", "Chapter 10"),
        chapter("c2", "Chapter 2"),
        chapter("c1", "Chapter 1"),
        chapter("c3", "Chapter 3"),
      )

    val firstUnread = snapshot(states(chapters, read = setOf(0)))

    val gapUnread = snapshot(states(chapters, read = setOf(0, 2)))

    val noneRead = snapshot(states(chapters))

    assertEquals(
      ContinueResolution.FirstUnread(chapters[1]),
      firstUnread.resolveContinue(),
    )

    assertEquals(
      ContinueResolution.FirstUnread(chapters[1]),
      gapUnread.resolveContinue(),
    )

    assertEquals(
      ContinueResolution.FirstUnread(chapters[0]),
      noneRead.resolveContinue(),
    )
  }

  @Test
  fun everyChapterReadOpensTheFinalChapter() {
    val chapters =
      listOf(chapter("z", "Z"), chapter("b", "B"), chapter("a", "A"))

    val snapshot = snapshot(states(chapters, read = setOf(0, 1, 2)))

    assertEquals(
      ContinueResolution.AllRead(chapters[2]),
      snapshot.resolveContinue(),
    )
  }

  @Test
  fun withoutPositionOrChapterContinueIsUnavailable() {
    val snapshot = snapshot(emptyList())

    assertEquals(
      ContinueResolution.NoAvailableChapter,
      snapshot.resolveContinue(),
    )
  }

  private fun chapters(vararg keys: String): List<Chapter> = keys.map { key ->
    chapter(key, "Chapter $key")
  }

  private fun chapter(key: String, displayName: String): Chapter =
    Chapter(
      id = ChapterId.generate(),
      localKey = TitleLocalChapterKey.of(key),
      displayName = displayName,
    )

  private fun states(
    chapters: List<Chapter>,
    read: Set<Int> = emptySet(),
  ): List<ChapterReadingState> = chapters.mapIndexed { index, chapter ->
    ChapterReadingState(chapter, index in read)
  }

  private fun snapshot(
    states: List<ChapterReadingState>,
    target: ResumeTarget? = null,
  ): ProgressSnapshot =
    ProgressSnapshot.create(
      titleId = TITLE_ID,
      isInLibrary = target != null,
      chapters = states,
      resumeTarget = target,
    )

  private companion object {
    val TITLE_ID = TitleId.generate()
  }
}
