package app.hakusan

import app.hakusan.extensions.SourceIdentity
import app.hakusan.sdk.AddToLibraryScreenResult
import app.hakusan.sdk.LibraryResumeState
import app.hakusan.sdk.LibraryTitleProgress
import app.hakusan.sdk.ScreenShelfId
import app.hakusan.sdk.ScreenTitleId
import app.hakusan.sdk.ScreenTitleKey
import app.hakusan.titles.CategoryId
import app.hakusan.titles.CompactProgress
import app.hakusan.titles.LibraryAddResult
import app.hakusan.titles.ResumeAvailability
import app.hakusan.titles.SourceTitleAlias
import app.hakusan.titles.TitleId
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
