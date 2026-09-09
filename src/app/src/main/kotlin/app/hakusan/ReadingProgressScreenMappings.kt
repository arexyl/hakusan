package app.hakusan

import app.hakusan.extensions.SourceTitleDetails
import app.hakusan.sdk.ContinueSelectionResult
import app.hakusan.sdk.ContinueState
import app.hakusan.sdk.ContinueTarget
import app.hakusan.sdk.ContinueUnavailableReason
import app.hakusan.sdk.DetailsChapterItem
import app.hakusan.sdk.ScreenChapterId
import app.hakusan.sdk.ScreenChapterKey
import app.hakusan.sdk.ScreenContentUnitKind
import app.hakusan.sdk.ScreenReadingPosition
import app.hakusan.sdk.ScreenReadingStart
import app.hakusan.sdk.ScreenTitleId
import app.hakusan.sdk.TitleDetailsScreen
import app.hakusan.titles.ReadingContentUnitKind
import app.hakusan.titles.SourceChapterAlias
import app.hakusan.titles.TitleReadingProgress

private fun SourceChapterAlias.toScreenKey(): ScreenChapterKey =
  ScreenChapterKey(
    titleKey = titleAlias.toScreenKey(),
    sourceChapterKey = sourceChapterKey,
  )

internal fun TitleReadingProgress.toDetailsScreen(
  sourceDisplayName: String,
  details: SourceTitleDetails,
): TitleDetailsScreen {
  check(
    titleAlias.sourceIdentity == details.title.key.source.value &&
      titleAlias.sourceTitleKey == details.title.key.key,
  ) {
    "Source details and stored reading progress must identify one title."
  }
  val screenTitleId = ScreenTitleId(titleId.value)
  val screenTitleKey = titleAlias.toScreenKey()
  return TitleDetailsScreen.of(
    id = screenTitleId,
    key = screenTitleKey,
    sourceDisplayName = sourceDisplayName,
    displayName = details.title.displayName,
    description = details.description,
    chapters = canonicalChapters.map { state ->
      DetailsChapterItem(
        id = ScreenChapterId(state.chapter.id.value),
        titleId = screenTitleId,
        key = ScreenChapterKey(
          titleKey = screenTitleKey,
          sourceChapterKey = state.chapter.alias.sourceChapterKey,
        ),
        displayName = state.chapter.displayName,
        isRead = state.isRead,
      )
    },
    continueState = toContinueState(),
  )
}

internal fun TitleReadingProgress.toContinueState(): ContinueState {
  val resume = libraryResumePosition
  if (resume != null) {
    val position = ScreenReadingPosition(
      titleId = ScreenTitleId(resume.position.titleId.value),
      chapterId = ScreenChapterId(resume.position.chapterId.value),
      unitKind = resume.position.unitKind.toScreenKind(),
      unitIndex = resume.position.unitIndex,
    )
    val key = resume.chapter.alias.toScreenKey()
    if (!resume.isCurrentlyAvailable) {
      return ContinueState.Unavailable(
        ContinueUnavailableReason.SavedTargetUnavailable(
          chapterKey = key,
          position = position,
        ),
      )
    }
    return ContinueState.Ready(
      ContinueTarget(
        titleId = ScreenTitleId(titleId.value),
        chapterId = ScreenChapterId(resume.chapter.id.value),
        chapterKey = key,
        start = ScreenReadingStart.Resume(position),
      ),
    )
  }

  val selected = canonicalChapters.firstOrNull { !it.isRead }
    ?: canonicalChapters.lastOrNull()
    ?: return ContinueState.Unavailable(
      ContinueUnavailableReason.NoAvailableChapter,
    )
  return ContinueState.Ready(
    ContinueTarget(
      titleId = ScreenTitleId(titleId.value),
      chapterId = ScreenChapterId(selected.chapter.id.value),
      chapterKey = selected.chapter.alias.toScreenKey(),
      start = ScreenReadingStart.Beginning,
    ),
  )
}

internal fun ContinueState.toSelectionResult(): ContinueSelectionResult =
  when (this) {
    is ContinueState.Ready -> ContinueSelectionResult.Selected(target)
    is ContinueState.Unavailable ->
      ContinueSelectionResult.Unavailable(reason)
  }

private fun ReadingContentUnitKind.toScreenKind(): ScreenContentUnitKind =
  when (this) {
    ReadingContentUnitKind.PAGE -> ScreenContentUnitKind.PAGE
    ReadingContentUnitKind.PROVIDER_SEGMENT ->
      ScreenContentUnitKind.PROVIDER_SEGMENT
  }
