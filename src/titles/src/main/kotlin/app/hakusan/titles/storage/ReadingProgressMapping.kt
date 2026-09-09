package app.hakusan.titles.storage

import app.hakusan.titles.Chapter
import app.hakusan.titles.ChapterId
import app.hakusan.titles.ChapterReadingState
import app.hakusan.titles.LibraryResumePosition
import app.hakusan.titles.ReadingContentUnitKind
import app.hakusan.titles.ReadingPosition
import app.hakusan.titles.SourceChapterAlias
import app.hakusan.titles.SourceTitleAlias
import app.hakusan.titles.TitleId
import app.hakusan.titles.TitleReadingProgress
import java.util.ArrayList
import java.util.UUID

internal fun toReadingProgress(
  rows: List<ReadingProgressRow>,
): TitleReadingProgress? {
  if (rows.isEmpty()) {
    return null
  }

  val first = rows.first()
  // Preserve projection failure priority: title, canonical, then resume.
  check(rows.all { row -> row.hasSameTitleStateAs(first) }) {
    "One progress query returned conflicting title state."
  }
  val titleId = TitleId(first.titleId)
  val titleAlias = SourceTitleAlias(
    sourceIdentity = first.titleSourceIdentity,
    sourceTitleKey = first.titleSourceKey,
  )
  val canonical = ArrayList<ChapterReadingState>(rows.size)
  val resumeChapterId = first.resumeChapterId
  var canonicalResumeState: ChapterReadingState? = null
  var canonicalResumeIndex: Int? = null
  var canonicalIndexesAreDense = true
  rows.forEach { row ->
    val state = row.toCanonicalChapterState(titleId, titleAlias)
      ?: return@forEach
    if (row.chapterCanonicalIndex != canonical.size) {
      canonicalIndexesAreDense = false
    }
    if (
      canonicalResumeState == null && row.chapterId == resumeChapterId
    ) {
      canonicalResumeState = state
      canonicalResumeIndex = canonical.size
    }
    canonical += state
  }
  check(canonicalIndexesAreDense) {
    "Observed canonical chapter indexes must be dense."
  }

  val storedResume = first.toStoredResume()
  var resumeStateAgrees = true
  for (index in 1 until rows.size) {
    val row = rows[index]
    row.validateStoredResumeState()
    if (!row.hasSameResumeStateAs(first)) {
      resumeStateAgrees = false
    }
  }
  check(resumeStateAgrees) {
    "One progress query returned conflicting resume state."
  }
  val resume = storedResume?.toLibraryResumePosition(
    titleId = titleId,
    titleAlias = titleAlias,
    canonicalState = canonicalResumeState,
    canonicalStateIndex = canonicalResumeIndex,
  )
  return TitleReadingProgress.create(
    titleId = titleId,
    titleAlias = titleAlias,
    isInLibrary = first.isLibraryMember,
    canonicalChapters = canonical,
    libraryResumePosition = resume,
  )
}

private fun ReadingProgressRow.hasSameTitleStateAs(
  other: ReadingProgressRow,
): Boolean =
  titleId == other.titleId &&
    titleSourceIdentity == other.titleSourceIdentity &&
    titleSourceKey == other.titleSourceKey &&
    isLibraryMember == other.isLibraryMember

private fun ReadingProgressRow.hasSameResumeStateAs(
  other: ReadingProgressRow,
): Boolean =
  resumeChapterId == other.resumeChapterId &&
    resumeChapterSourceKey == other.resumeChapterSourceKey &&
    resumeChapterDisplayName == other.resumeChapterDisplayName &&
    resumeChapterCanonicalIndex == other.resumeChapterCanonicalIndex &&
    resumeChapterIsRead == other.resumeChapterIsRead &&
    resumeUnitKind == other.resumeUnitKind &&
    resumeUnitIndex == other.resumeUnitIndex

private fun ReadingProgressRow.validateStoredResumeState() {
  if (resumeChapterId == null) {
    check(
      resumeChapterSourceKey == null &&
        resumeChapterDisplayName == null &&
        resumeChapterCanonicalIndex == null &&
        resumeUnitKind == null &&
        resumeUnitIndex == null &&
        !resumeChapterIsRead
    ) {
      "An empty resume row contained partial position state."
    }
    return
  }
  check(!resumeChapterIsRead) {
    "A read chapter retained a persistent resume position."
  }
  checkNotNull(resumeChapterSourceKey)
  checkNotNull(resumeChapterDisplayName)
  checkNotNull(resumeUnitKind)
  checkNotNull(resumeUnitIndex)
}

private fun ReadingProgressRow.toCanonicalChapterState(
  titleId: TitleId,
  titleAlias: SourceTitleAlias,
): ChapterReadingState? {
  val id = chapterId
  if (id == null) {
    check(
      chapterSourceKey == null &&
        chapterDisplayName == null &&
        chapterCanonicalIndex == null &&
        !chapterIsRead
    ) {
      "An empty canonical row contained partial chapter state."
    }
    return null
  }
  checkNotNull(chapterCanonicalIndex)
  return ChapterReadingState(
    chapter = Chapter(
      id = ChapterId(id),
      titleId = titleId,
      alias = SourceChapterAlias(
        titleAlias = titleAlias,
        sourceChapterKey = checkNotNull(chapterSourceKey),
      ),
      displayName = checkNotNull(chapterDisplayName),
    ),
    isRead = chapterIsRead,
  )
}

private fun ReadingProgressRow.toStoredResume(): StoredResume? {
  val id = resumeChapterId
  if (id == null) {
    check(
      resumeChapterSourceKey == null &&
        resumeChapterDisplayName == null &&
        resumeChapterCanonicalIndex == null &&
        resumeUnitKind == null &&
        resumeUnitIndex == null &&
        !resumeChapterIsRead
    ) {
      "An empty resume row contained partial position state."
    }
    return null
  }
  check(!resumeChapterIsRead) {
    "A read chapter retained a persistent resume position."
  }
  return StoredResume(
    chapterId = id,
    sourceChapterKey = checkNotNull(resumeChapterSourceKey),
    displayName = checkNotNull(resumeChapterDisplayName),
    canonicalIndex = resumeChapterCanonicalIndex,
    unitKind = checkNotNull(resumeUnitKind),
    unitIndex = checkNotNull(resumeUnitIndex),
  )
}

private fun StoredResume.toLibraryResumePosition(
  titleId: TitleId,
  titleAlias: SourceTitleAlias,
  canonicalState: ChapterReadingState?,
  canonicalStateIndex: Int?,
): LibraryResumePosition {
  val domainChapterId = ChapterId(chapterId)
  check((canonicalState != null) == (canonicalIndex != null)) {
    "Resume availability disagreed with its canonical index."
  }
  if (canonicalState != null) {
    check(canonicalStateIndex == canonicalIndex) {
      "Resume canonical index disagreed with the current sequence."
    }
    check(
      canonicalState.chapter.alias.sourceChapterKey == sourceChapterKey &&
        canonicalState.chapter.displayName == displayName
    ) {
      "Resume and canonical chapter metadata disagreed."
    }
  }
  val chapter = canonicalState?.chapter ?: Chapter(
    id = domainChapterId,
    titleId = titleId,
    alias = SourceChapterAlias(titleAlias, sourceChapterKey),
    displayName = displayName,
  )
  return LibraryResumePosition(
    chapter = chapter,
    position = ReadingPosition(
      titleId = titleId,
      chapterId = domainChapterId,
      unitKind = unitKind.toDomainKind(),
      unitIndex = unitIndex,
    ),
    isCurrentlyAvailable = canonicalState != null,
  )
}

private fun StoredContentUnitKind.toDomainKind(): ReadingContentUnitKind =
  when (this) {
    StoredContentUnitKind.PAGE -> ReadingContentUnitKind.PAGE
    StoredContentUnitKind.PROVIDER_SEGMENT ->
      ReadingContentUnitKind.PROVIDER_SEGMENT
  }

private data class StoredResume(
  val chapterId: UUID,
  val sourceChapterKey: String,
  val displayName: String,
  val canonicalIndex: Int?,
  val unitKind: StoredContentUnitKind,
  val unitIndex: Int,
)
