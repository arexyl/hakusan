package app.hakusan.titles.storage

import app.hakusan.titles.ChapterId
import app.hakusan.titles.ContentUnitKind
import app.hakusan.titles.ResumeTarget
import app.hakusan.titles.StoredStateViolationException
import app.hakusan.titles.StoredStateViolationKind
import app.hakusan.titles.StoredStateViolationKind.ALIAS
import app.hakusan.titles.StoredStateViolationKind.CANONICAL_ORDER
import app.hakusan.titles.StoredStateViolationKind.IDENTITY
import app.hakusan.titles.StoredStateViolationKind.OWNERSHIP
import app.hakusan.titles.StoredStateViolationKind.RESUME_STATE
import app.hakusan.titles.StoredStateViolationKind.STORAGE_KEY
import app.hakusan.titles.TitleId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows

class ProgressSnapshotsTest {
  @Test
  fun snapshotKeepsCanonicalOrderReadFlagsAndMembership() {
    val rows =
      listOf(chapter(3, 2), chapter(1, 0), chapter(2, 1), chapter(4, null))

    val snapshot =
      map(chapters = rows, read = listOf(read(2), read(4)), isMember = false)

    assertEquals(TITLE_ID, snapshot.titleId)
    assertFalse(snapshot.isInLibrary)
    assertNull(snapshot.resumeTarget)

    assertEquals(
      listOf("key-1", "key-2", "key-3"),
      snapshot.chapters.map { state -> state.chapter.localKey.text },
    )

    assertEquals(
      listOf(false, true, false),
      snapshot.chapters.map { state -> state.isRead },
    )

    assertEquals(
      rows[1].id.toChapterIdOrNull(),
      snapshot.chapters.first().chapter.id,
    )
  }

  @Test
  fun resumeOnACanonicalChapterIsAvailable() {
    val snapshot =
      map(
        chapters = listOf(chapter(1, 0), chapter(2, 1)),
        isMember = true,
        resume = listOf(resume(2, kind = "PROVIDER_SEGMENT", index = 3)),
      )

    val target =
      assertInstanceOf(
        ResumeTarget.Available::class.java,
        snapshot.resumeTarget,
      )

    assertTrue(snapshot.isInLibrary)
    assertEquals("key-2", target.chapter.localKey.text)
    assertEquals(ContentUnitKind.PROVIDER_SEGMENT, target.unitKind)
    assertEquals(3, target.unitIndex)
  }

  @Test
  fun resumeOnAnOmittedChapterIsTemporarilyUnavailable() {
    val snapshot =
      map(
        chapters = listOf(chapter(1, 0), chapter(2, null)),
        read = listOf(read(1)),
        isMember = true,
        resume = listOf(resume(2)),
      )

    val target =
      assertInstanceOf(
        ResumeTarget.TemporarilyUnavailable::class.java,
        snapshot.resumeTarget,
      )

    assertEquals("key-2", target.chapter.localKey.text)
    assertEquals(ContentUnitKind.PAGE, target.unitKind)

    assertEquals(
      listOf("key-1"),
      snapshot.chapters.map { it.chapter.localKey.text },
    )
  }

  @Test
  fun snapshotChaptersAreOwned() {
    val snapshot = map(chapters = listOf(chapter(1, 0)))

    assertThrows<UnsupportedOperationException> {
      @Suppress("UNCHECKED_CAST")
      (snapshot.chapters as MutableList<Any>).clear()
    }
  }

  @Test
  fun contradictoryChapterRowsRaiseTheirKind() {
    val duplicateId = ChapterId.generate().toStoredBytes()

    assertAll(
      violates(OWNERSHIP) {
        map(
          chapters = listOf(chapter(1, 0), chapter(2, 1, title = OTHER_TITLE))
        )
      },
      violates(IDENTITY) {
        map(
          chapters =
            listOf(
              chapter(1, 0, id = duplicateId),
              chapter(2, 1, id = duplicateId),
            )
        )
      },
      violates(ALIAS) {
        map(chapters = listOf(chapter(1, 0), chapter(2, 1, key = "key-1")))
      },
      violates(CANONICAL_ORDER) { map(chapters = listOf(chapter(1, 1))) },
      violates(CANONICAL_ORDER) {
        map(chapters = listOf(chapter(1, 0), chapter(2, 2)))
      },
      violates(CANONICAL_ORDER) {
        map(chapters = listOf(chapter(1, 0), chapter(2, 0)))
      },
    )
  }

  @Test
  fun contradictoryReadRowsRaiseTheirKind() {
    val chapters = listOf(chapter(1, 0), chapter(2, 1))

    assertAll(
      violates(STORAGE_KEY) {
        map(chapters = chapters, read = listOf(read(0)))
      },
      violates(OWNERSHIP) { map(chapters = chapters, read = listOf(read(9))) },
      violates(IDENTITY) {
        map(chapters = chapters, read = listOf(read(1), read(1)))
      },
    )
  }

  @Test
  fun contradictoryResumeRowsRaiseResumeOrOwnershipKinds() {
    val chapters = listOf(chapter(1, 0), chapter(2, 1))

    assertAll(
      violates(RESUME_STATE) {
        map(
          chapters = chapters,
          isMember = true,
          resume = listOf(resume(1, kind = "?")),
        )
      },
      violates(OWNERSHIP) {
        map(
          chapters = chapters,
          isMember = true,
          resume = listOf(resume(1, title = OTHER_TITLE)),
        )
      },
      violates(RESUME_STATE) {
        map(
          chapters = chapters,
          isMember = true,
          resume = listOf(resume(1), resume(2)),
        )
      },
      violates(OWNERSHIP) {
        map(chapters = chapters, isMember = true, resume = listOf(resume(9)))
      },
      violates(RESUME_STATE) {
        map(
          chapters = chapters,
          read = listOf(read(1)),
          isMember = true,
          resume = listOf(resume(1)),
        )
      },
      violates(RESUME_STATE) {
        map(chapters = chapters, isMember = false, resume = listOf(resume(1)))
      },
    )
  }

  @Test
  fun validationReportsTheEarliestViolationInOrder() {
    val malformedResume = listOf(resume(1, kind = "?"))

    assertAll(
      violates(STORAGE_KEY) {
        map(
          chapters = listOf(chapter(1, 1), chapter(0, 0)),
          read = listOf(read(9)),
          resume = malformedResume,
        )
      },
      violates(OWNERSHIP) {
        map(
          chapters = listOf(chapter(1, 5), chapter(2, 1, title = OTHER_TITLE)),
          resume = malformedResume,
        )
      },
      violates(CANONICAL_ORDER) {
        map(
          chapters = listOf(chapter(1, 1)),
          read = listOf(read(9)),
          resume = malformedResume,
        )
      },
      violates(OWNERSHIP) {
        map(
          chapters = listOf(chapter(1, 0)),
          read = listOf(read(9)),
          resume = malformedResume,
        )
      },
    )
  }

  @Test
  fun violationMessagesCarryNoStoredValue() {
    val error =
      assertThrows<StoredStateViolationException> {
        map(chapters = listOf(chapter(1, 0), chapter(2, 1, key = "key-1")))
      }

    assertFalse(error.message.orEmpty().contains("key-1"))
    assertFalse(error.message.orEmpty().contains(TITLE_KEY.toString()))
  }

  private fun violates(
    kind: StoredStateViolationKind,
    read: () -> Unit,
  ): () -> Unit = {
    val error = assertThrows<StoredStateViolationException> { read() }

    assertEquals(kind, error.kind)
  }

  private fun map(
    chapters: List<ChapterEntity>,
    read: List<ReadChapterEntity> = emptyList(),
    isMember: Boolean = false,
    resume: List<LibraryResumePositionEntity> = emptyList(),
  ) =
    mapProgressSnapshot(
      titleId = TITLE_ID,
      titleStorageId = TITLE_KEY,
      chapterRows = chapters,
      readRows = read,
      isLibraryMember = isMember,
      resumeRows = resume,
    )

  private fun chapter(
    storageId: Long,
    index: Int?,
    title: Long = TITLE_KEY,
    key: String = "key-$storageId",
    id: ByteArray = ChapterId.generate().toStoredBytes(),
  ): ChapterEntity =
    ChapterEntity(
      storageId = storageId,
      id = id,
      titleStorageId = title,
      sourceChapterKey = key,
      displayName = "Chapter $storageId",
      canonicalIndex = index,
    )

  private fun read(chapter: Long): ReadChapterEntity =
    ReadChapterEntity(chapter)

  private fun resume(
    chapter: Long,
    title: Long = TITLE_KEY,
    kind: String = "PAGE",
    index: Int = 0,
  ): LibraryResumePositionEntity =
    LibraryResumePositionEntity(
      titleStorageId = title,
      chapterStorageId = chapter,
      unitKind = kind,
      unitIndex = index,
      updatedAt = 0,
    )

  private companion object {
    const val TITLE_KEY = 31L
    const val OTHER_TITLE = 32L

    val TITLE_ID = TitleId.generate()
  }
}
