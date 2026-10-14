package app.hakusan

import app.hakusan.extensions.SourceIdentity
import app.hakusan.sdk.AddToLibraryScreenResult
import app.hakusan.sdk.ContinueSelectionResult
import app.hakusan.sdk.ContinueTarget
import app.hakusan.sdk.ContinueUnavailableReason
import app.hakusan.sdk.LibraryResumeState
import app.hakusan.sdk.LibraryTitleProgress
import app.hakusan.sdk.ScreenChapterId
import app.hakusan.sdk.ScreenChapterKey
import app.hakusan.sdk.ScreenContentUnitKind
import app.hakusan.sdk.ScreenReadingPosition
import app.hakusan.sdk.ScreenReadingStart
import app.hakusan.sdk.ScreenShelfId
import app.hakusan.sdk.ScreenTitleId
import app.hakusan.sdk.ScreenTitleKey
import app.hakusan.titles.CategoryId
import app.hakusan.titles.Chapter
import app.hakusan.titles.ChapterId
import app.hakusan.titles.CompactProgress
import app.hakusan.titles.ContentUnitKind
import app.hakusan.titles.ContinueResolution
import app.hakusan.titles.LibraryAddResult
import app.hakusan.titles.ProgressSnapshot
import app.hakusan.titles.ResumeAvailability
import app.hakusan.titles.ResumeTarget
import app.hakusan.titles.SourceTitleAlias
import app.hakusan.titles.TitleId
import app.hakusan.titles.resolveContinue
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

/** Returns the screen identity of this title, with the same UUID. */
internal fun TitleId.toScreenTitleId(): ScreenTitleId =
  ScreenTitleId(uuid.toJavaUuid())

/**
 * Returns the title identity with the UUID of this screen identity.
 *
 * The screen identity already carries a validated UUIDv7, so the format check
 * of [TitleId.ofOrNull] cannot reject it. The identity may still name no stored
 * title.
 *
 * @throws IllegalStateException if the format check rejects the UUID, which is
 *   a defect.
 */
internal fun ScreenTitleId.toTitleId(): TitleId =
  checkNotNull(TitleId.ofOrNull(value.toKotlinUuid())) {
    "A screen title id must carry a valid title identity."
  }

/** Returns the identity of the source of this title alias, exactly. */
internal fun SourceTitleAlias.toSourceIdentity(): SourceIdentity =
  SourceIdentity(sourceIdentity)

/** Returns the screen key of this title alias, with the exact components. */
internal fun SourceTitleAlias.toScreenTitleKey(): ScreenTitleKey =
  ScreenTitleKey(toSourceIdentity().toScreenSourceId(), sourceTitleKey)

/**
 * Returns the screen key of the Library shelf of this category.
 *
 * The key is valid only for the current database, like the category identity.
 */
internal fun CategoryId.toScreenShelfId(): ScreenShelfId = ScreenShelfId(key)

/** Returns this compact progress as the progress of a Library title. */
internal fun CompactProgress.toLibraryTitleProgress(): LibraryTitleProgress =
  LibraryTitleProgress(
    chapterCount = chapterCount,
    readChapterCount = readChapterCount,
    resumeState = resumeAvailability.toLibraryResumeState(),
  )

/** Returns the screen state of this resume availability. */
internal fun ResumeAvailability.toLibraryResumeState(): LibraryResumeState =
  when (this) {
    ResumeAvailability.NONE -> LibraryResumeState.NONE
    ResumeAvailability.AVAILABLE -> LibraryResumeState.AVAILABLE
    ResumeAvailability.TEMPORARILY_UNAVAILABLE ->
      LibraryResumeState.TEMPORARILY_UNAVAILABLE
  }

/** Returns the screen outcome of this automatic Add. */
internal fun LibraryAddResult.toAddToLibraryScreenResult():
  AddToLibraryScreenResult =
  when (this) {
    is LibraryAddResult.Success -> AddToLibraryScreenResult.Success
    is LibraryAddResult.CategorySelectionRequired ->
      AddToLibraryScreenResult.CategorySelectionRequired
    LibraryAddResult.TitleNotFound -> AddToLibraryScreenResult.TitleNotFound
  }

/**
 * Returns the outcome of one Continue invocation on this progress.
 *
 * [resolveContinue] decides the target. This function only maps each case to
 * its screen outcome, with the exact identities and keys of this progress.
 */
internal fun ProgressSnapshot.toContinueSelectionResult():
  ContinueSelectionResult =
  when (val resolution = resolveContinue()) {
    is ContinueResolution.Resume ->
      ContinueSelectionResult.Selected(resumedTarget(resolution.target))

    is ContinueResolution.SavedTargetUnavailable ->
      ContinueSelectionResult.Unavailable(
        savedTargetUnavailable(resolution.target)
      )

    is ContinueResolution.FirstUnread ->
      ContinueSelectionResult.Selected(beginningTarget(resolution.chapter))

    is ContinueResolution.AllRead ->
      ContinueSelectionResult.Selected(beginningTarget(resolution.chapter))

    ContinueResolution.NoAvailableChapter ->
      ContinueSelectionResult.Unavailable(
        ContinueUnavailableReason.NoAvailableChapter
      )
  }

/** Returns the Continue target that resumes at the saved [target]. */
private fun ProgressSnapshot.resumedTarget(
  target: ResumeTarget.Available
): ContinueTarget {
  val start = ScreenReadingStart.Resume(readingPosition(target))

  return continueTarget(target.chapter, start)
}

/** Returns the Continue target that opens [chapter] at its beginning. */
private fun ProgressSnapshot.beginningTarget(chapter: Chapter): ContinueTarget =
  continueTarget(chapter, ScreenReadingStart.Beginning)

/** Returns the Continue target that opens [chapter] at [start]. */
private fun ProgressSnapshot.continueTarget(
  chapter: Chapter,
  start: ScreenReadingStart,
): ContinueTarget =
  ContinueTarget(
    titleId = titleId.toScreenTitleId(),
    chapterId = chapter.id.toScreenChapterId(),
    chapterKey = chapterKey(chapter),
    start = start,
  )

/**
 * Returns the reason that keeps the saved [target] in an omitted chapter
 * without opening another chapter.
 */
private fun ProgressSnapshot.savedTargetUnavailable(
  target: ResumeTarget.TemporarilyUnavailable
): ContinueUnavailableReason =
  ContinueUnavailableReason.SavedTargetUnavailable(
    chapterKey = chapterKey(target.chapter),
    position = readingPosition(target),
  )

/** Returns the saved [target] of this title as a screen reading position. */
private fun ProgressSnapshot.readingPosition(
  target: ResumeTarget
): ScreenReadingPosition =
  ScreenReadingPosition(
    titleId = titleId.toScreenTitleId(),
    chapterId = target.chapter.id.toScreenChapterId(),
    unitKind = target.unitKind.toScreenContentUnitKind(),
    unitIndex = target.unitIndex,
  )

/**
 * Returns the screen key of [chapter], qualified by the exact alias of this
 * title.
 */
private fun ProgressSnapshot.chapterKey(chapter: Chapter): ScreenChapterKey =
  ScreenChapterKey(alias.toScreenTitleKey(), chapter.localKey.text)

/** Returns the screen identity of this chapter, with the same UUID. */
private fun ChapterId.toScreenChapterId(): ScreenChapterId =
  ScreenChapterId(uuid.toJavaUuid())

/** Returns the screen kind of this unit kind. */
private fun ContentUnitKind.toScreenContentUnitKind(): ScreenContentUnitKind =
  when (this) {
    ContentUnitKind.PAGE -> ScreenContentUnitKind.PAGE
    ContentUnitKind.PROVIDER_SEGMENT -> ScreenContentUnitKind.PROVIDER_SEGMENT
  }
