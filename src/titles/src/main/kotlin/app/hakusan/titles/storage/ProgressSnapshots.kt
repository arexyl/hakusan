package app.hakusan.titles.storage

import app.hakusan.titles.Chapter
import app.hakusan.titles.ChapterReadingState
import app.hakusan.titles.ContentUnitKind
import app.hakusan.titles.ProgressSnapshot
import app.hakusan.titles.ResumeTarget
import app.hakusan.titles.StoredStateViolationException
import app.hakusan.titles.StoredStateViolationKind.IDENTITY
import app.hakusan.titles.StoredStateViolationKind.OWNERSHIP
import app.hakusan.titles.StoredStateViolationKind.RESUME_STATE
import app.hakusan.titles.TitleId

/**
 * Reads the progress snapshot of the title [titleStorageId], whose identity is
 * [titleId]. Call it inside a transaction, so the rows form one state.
 *
 * Library membership is the existence of a category association, read without
 * its rows. The Library read validates the association rows themselves.
 *
 * @throws StoredStateViolationException if the stored progress of the title
 *   violates an invariant.
 */
internal suspend fun TitlesDao.readProgressSnapshot(
  titleStorageId: Long,
  titleId: TitleId,
): ProgressSnapshot {
  val chapters = loadChapters(titleStorageId)

  val readChapters = loadReadChapters(titleStorageId)

  val isLibraryMember = isLibraryMember(titleStorageId)

  val resumePositions = loadResumePositions(titleStorageId)

  return mapProgressSnapshot(
    titleId = titleId,
    titleStorageId = titleStorageId,
    chapterRows = chapters,
    readRows = readChapters,
    isLibraryMember = isLibraryMember,
    resumeRows = resumePositions,
  )
}

/**
 * Validates the stored progress rows of one title and returns its snapshot.
 *
 * The title row has already passed its checks. The checks then run in order:
 * the known chapters (see [KnownChapters.of]), the read rows, and the resume
 * rows. A later malformed row therefore never hides an earlier violation.
 *
 * @throws StoredStateViolationException if a row or the set of rows violates an
 *   invariant.
 */
internal fun mapProgressSnapshot(
  titleId: TitleId,
  titleStorageId: Long,
  chapterRows: List<ChapterEntity>,
  readRows: List<ReadChapterEntity>,
  isLibraryMember: Boolean,
  resumeRows: List<LibraryResumePositionEntity>,
): ProgressSnapshot {
  val chapters = KnownChapters.of(titleStorageId, chapterRows)

  val readKeys = readChapterKeys(chapters, readRows)

  val resumeTarget =
    resumeTarget(
      titleStorageId = titleStorageId,
      chapters = chapters,
      readKeys = readKeys,
      isLibraryMember = isLibraryMember,
      rows = resumeRows,
    )

  val states =
    chapters.canonical.map { chapter ->
      ChapterReadingState(chapter.toChapter(), chapter.storageId in readKeys)
    }

  return ProgressSnapshot.create(
    titleId = titleId,
    isInLibrary = isLibraryMember,
    chapters = states,
    resumeTarget = resumeTarget,
  )
}

/** Validates the read rows and returns the storage keys of read chapters. */
private fun readChapterKeys(
  chapters: KnownChapters,
  rows: List<ReadChapterEntity>,
): Set<Long> {
  val readChapters = rows.map(StoredReadChapter::of)

  val isOwned = readChapters.all { read ->
    chapters.withStorageId(read.chapterStorageId) != null
  }

  if (!isOwned) {
    violated(OWNERSHIP, "A stored read_chapters row names a foreign chapter.")
  }

  val keys = readChapters.mapTo(HashSet(), StoredReadChapter::chapterStorageId)

  if (keys.size != readChapters.size) {
    violated(IDENTITY, "Stored read_chapters rows repeat a chapter.")
  }

  return keys
}

/**
 * Validates the resume rows of a title and returns its resume target.
 *
 * This is the one validator of persistent resume state at the read boundary. It
 * runs the row-local checks of every row, then title ownership, then the
 * one-row rule, then chapter ownership, then read-state consistency, then
 * Library membership. On the mutation side, `RoomTitles.enterLibrary` checks
 * the Library rule before a title becomes a member,
 * `RoomTitles.writeLibraryPosition` keeps the Library and read rules for every
 * position it writes, and `RoomTitles.markChapterRead` deletes a position on
 * the chapter it marks read.
 */
private fun resumeTarget(
  titleStorageId: Long,
  chapters: KnownChapters,
  readKeys: Set<Long>,
  isLibraryMember: Boolean,
  rows: List<LibraryResumePositionEntity>,
): ResumeTarget? {
  val positions = rows.map(StoredResumePosition::of)

  if (positions.any { position -> position.titleStorageId != titleStorageId }) {
    violated(OWNERSHIP, "A stored resume position belongs to another title.")
  }

  if (positions.size > 1) {
    violated(RESUME_STATE, "A title has several stored resume positions.")
  }

  val position = positions.singleOrNull() ?: return null

  val chapter =
    chapters.withStorageId(position.chapterStorageId)
      ?: violated(
        OWNERSHIP,
        "A stored resume position names a chapter of another title.",
      )

  if (chapter.storageId in readKeys) {
    violated(RESUME_STATE, "A stored resume position is on a read chapter.")
  }

  if (!isLibraryMember) {
    violated(
      RESUME_STATE,
      "A title outside the Library has a persistent resume position.",
    )
  }

  val unitKind = position.unitKind.toContentUnitKind()

  return if (chapter.canonicalIndex == null) {
    ResumeTarget.TemporarilyUnavailable(
      chapter = chapter.toChapter(),
      unitKind = unitKind,
      unitIndex = position.unitIndex,
    )
  } else {
    ResumeTarget.Available(
      chapter = chapter.toChapter(),
      unitKind = unitKind,
      unitIndex = position.unitIndex,
    )
  }
}

private fun StoredChapter.toChapter(): Chapter =
  Chapter(id = id, localKey = localKey, displayName = displayName)

private fun StoredContentUnitKind.toContentUnitKind(): ContentUnitKind =
  when (this) {
    StoredContentUnitKind.PAGE -> ContentUnitKind.PAGE
    StoredContentUnitKind.PROVIDER_SEGMENT -> ContentUnitKind.PROVIDER_SEGMENT
  }
