package app.hakusan.extensions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ChapterSnapshotTest {
  private val source = SourceIdentity("source")
  private val title = SourceTitleKey(source, "title")

  @Test
  fun `complete snapshots keep an owned copy in canonical order`() {
    val first = chapter("first", "Chapter 10")

    val second = chapter("second", "Chapter 2")

    val final = chapter("final", "Chapter 1")

    val input = mutableListOf(first, second, final)

    val snapshot = complete(input).successValue()

    input.reverse()
    assertEquals(title, snapshot.title)
    assertEquals(listOf(first, second, final), snapshot.chapters)

    assertNotEquals(
      snapshot.chapters.sortedBy(SourceChapter::displayName),
      snapshot.chapters,
    )

    assertThrows(UnsupportedOperationException::class.java) {
      (snapshot.chapters as MutableList).clear()
    }
  }

  @Test
  fun `an empty complete snapshot is valid`() {
    val snapshot = complete(emptyList()).successValue()

    assertEquals(title, snapshot.title)
    assertEquals(emptyList<SourceChapter>(), snapshot.chapters)
  }

  @Test
  fun `partial and ambiguous sequences are rejected`() {
    val chapters = listOf(chapter("chapter", "Chapter"))

    val partial =
      ChapterSnapshot.create(title, ChapterSequenceStatus.PARTIAL, chapters)

    val ambiguous =
      ChapterSnapshot.create(title, ChapterSequenceStatus.AMBIGUOUS, chapters)

    assertEquals(
      SourceFailure.InvalidChapterSnapshot(
        ChapterSnapshotRejection.PARTIAL_SEQUENCE
      ),
      partial.failureValue(),
    )

    assertEquals(
      SourceFailure.InvalidChapterSnapshot(
        ChapterSnapshotRejection.AMBIGUOUS_ORDER
      ),
      ambiguous.failureValue(),
    )
  }

  @Test
  fun `repeated chapter identities are rejected`() {
    val first = chapter("chapter", "First")

    val conflicting = chapter("chapter", "Conflicting")

    val result = complete(listOf(first, conflicting))

    assertEquals(
      SourceFailure.InvalidChapterSnapshot(
        ChapterSnapshotRejection.DUPLICATE_CHAPTER
      ),
      result.failureValue(),
    )
  }

  @Test
  fun `chapters of another title are rejected`() {
    val otherTitle = SourceTitleKey(source, "other-title")

    val foreign = SourceChapter(SourceChapterKey(otherTitle, "c"), "Foreign")

    val result = complete(listOf(chapter("chapter", "Own"), foreign))

    assertEquals(
      SourceFailure.InvalidChapterSnapshot(
        ChapterSnapshotRejection.FOREIGN_TITLE
      ),
      result.failureValue(),
    )
  }

  @Test
  fun `later snapshots may change display metadata of one identity`() {
    val earlier = complete(listOf(chapter("chapter", "Earlier"))).successValue()

    val later = complete(listOf(chapter("chapter", "Later"))).successValue()

    assertEquals(earlier.chapters.single().key, later.chapters.single().key)
    assertNotEquals(earlier, later)
  }

  private fun chapter(key: String, displayName: String): SourceChapter =
    SourceChapter(SourceChapterKey(title, key), displayName)

  private fun complete(
    chapters: Iterable<SourceChapter>
  ): SourceResult<ChapterSnapshot, ChapterRefreshFailure> =
    ChapterSnapshot.create(title, ChapterSequenceStatus.COMPLETE, chapters)
}
