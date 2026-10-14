package app.hakusan.titles

import app.hakusan.titles.storage.ChapterEntity
import app.hakusan.titles.storage.LibraryResumePositionEntity
import app.hakusan.titles.storage.ReadChapterEntity
import app.hakusan.titles.storage.mapProgressSnapshot
import app.hakusan.titles.storage.toStoredBytes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows

class ProgressSnapshotFixturesTest {
  @Test
  fun snapshotWithoutTargetMatchesTheReadBoundary() {
    val outside = progressSnapshot(TITLE_ID, ALIAS, listOf(A, B, C), setOf(B))

    val member =
      progressSnapshot(
        TITLE_ID,
        ALIAS,
        listOf(A, B, C),
        setOf(B),
        isInLibrary = true,
      )

    val rows = listOf(row(A, 0), row(B, 1), row(C, 2))

    assertAll(
      { assertEquals(ALIAS, outside.alias) },
      {
        assertEquals(
          read(rows, reads = listOf(B), isMember = false),
          outside,
        )
      },
      {
        assertEquals(
          read(rows, reads = listOf(B), isMember = true),
          member,
        )
      },
      {
        assertEquals(
          read(emptyList(), isMember = false),
          progressSnapshot(TITLE_ID, ALIAS, emptyList()),
        )
      },
    )
  }

  @Test
  fun targetInTheCanonicalSequenceIsAvailableAsTheReadBoundaryReadsIt() {
    val snapshot =
      progressSnapshot(
        titleId = TITLE_ID,
        alias = ALIAS,
        chapters = listOf(A, B),
        readChapters = setOf(A),
        resumeChapter = B,
        unitKind = ContentUnitKind.PROVIDER_SEGMENT,
        unitIndex = 12,
      )

    val expected =
      read(
        chapters = listOf(row(A, 0), row(B, 1)),
        reads = listOf(A),
        isMember = true,
        resume = position(B, "PROVIDER_SEGMENT", 12),
      )

    assertInstanceOf(ResumeTarget.Available::class.java, snapshot.resumeTarget)
    assertEquals(expected, snapshot)
  }

  @Test
  fun targetOutsideTheCanonicalSequenceIsTemporarilyUnavailable() {
    val snapshot =
      progressSnapshot(
        titleId = TITLE_ID,
        alias = ALIAS,
        chapters = listOf(A),
        readChapters = setOf(A),
        resumeChapter = C,
        unitKind = ContentUnitKind.PAGE,
        unitIndex = 0,
      )

    val expected =
      read(
        chapters = listOf(row(A, 0), row(C, null)),
        reads = listOf(A),
        isMember = true,
        resume = position(C, "PAGE", 0),
      )

    assertInstanceOf(
      ResumeTarget.TemporarilyUnavailable::class.java,
      snapshot.resumeTarget,
    )

    assertEquals(expected, snapshot)
  }

  @Test
  fun impossibleProgressIsRejected() {
    val sameId = chapter(A.id, "other", "Other")

    val sameKey = chapter(chapterId(9), A.localKey.text, "Other")

    assertAll(
      rejects { progressSnapshot(TITLE_ID, ALIAS, listOf(A, sameId)) },
      rejects { progressSnapshot(TITLE_ID, ALIAS, listOf(A, sameKey)) },
      rejects { resumeOn(sameId, chapters = listOf(A)) },
      rejects { resumeOn(sameKey, chapters = listOf(A)) },
      rejects {
        progressSnapshot(TITLE_ID, ALIAS, listOf(A), readChapters = setOf(B))
      },
      rejects { resumeOn(A, chapters = listOf(A), read = setOf(A)) },
      rejects { resumeOn(A, chapters = listOf(A), unitIndex = -1) },
      rejects { chapter(chapterId(1), " ", "Blank") },
    )
  }

  private fun resumeOn(
    chapter: Chapter,
    chapters: List<Chapter>,
    read: Set<Chapter> = emptySet(),
    unitIndex: Int = 0,
  ): ProgressSnapshot =
    progressSnapshot(
      titleId = TITLE_ID,
      alias = ALIAS,
      chapters = chapters,
      readChapters = read,
      resumeChapter = chapter,
      unitKind = ContentUnitKind.PAGE,
      unitIndex = unitIndex,
    )

  private fun rejects(build: () -> Unit): () -> Unit = {
    assertThrows<IllegalArgumentException> { build() }
  }

  /** Reads the stored state of the title through the read boundary. */
  private fun read(
    chapters: List<ChapterEntity>,
    reads: List<Chapter> = emptyList(),
    isMember: Boolean,
    resume: LibraryResumePositionEntity? = null,
  ): ProgressSnapshot =
    mapProgressSnapshot(
      titleId = TITLE_ID,
      alias = ALIAS,
      titleStorageId = TITLE_KEY,
      chapterRows = chapters,
      readRows = reads.map { chapter -> ReadChapterEntity(storageId(chapter)) },
      isLibraryMember = isMember,
      resumeRows = listOfNotNull(resume),
    )

  /** The stored row of [chapter] at the canonical [index]. */
  private fun row(chapter: Chapter, index: Int?): ChapterEntity =
    ChapterEntity(
      storageId = storageId(chapter),
      id = chapter.id.toStoredBytes(),
      titleStorageId = TITLE_KEY,
      sourceChapterKey = chapter.localKey.text,
      displayName = chapter.displayName,
      canonicalIndex = index,
    )

  private fun position(
    chapter: Chapter,
    kind: String,
    index: Int,
  ): LibraryResumePositionEntity =
    LibraryResumePositionEntity(
      titleStorageId = TITLE_KEY,
      chapterStorageId = storageId(chapter),
      unitKind = kind,
      unitIndex = index,
      updatedAt = 0,
    )

  private fun storageId(chapter: Chapter): Long = STORAGE_IDS.getValue(chapter)

  private companion object {
    const val TITLE_KEY = 31L

    val TITLE_ID = TitleId.generate()

    val ALIAS = SourceTitleAlias.of(" Source ", "Cafe\u0301 Title")

    val A = chapter(chapterId(1), "a", "Chapter A")
    val B = chapter(chapterId(2), "b", "Chapter B")
    val C = chapter(chapterId(3), "c", "Chapter C")

    val STORAGE_IDS = mapOf(A to 1L, B to 2L, C to 3L)
  }
}
