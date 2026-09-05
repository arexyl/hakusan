package app.hakusan

import app.hakusan.extensions.ChapterSnapshot
import app.hakusan.extensions.SourceChapterKey
import app.hakusan.extensions.SourceIdentity
import app.hakusan.extensions.SourceTitleDetails
import app.hakusan.extensions.SourceTitleKey
import app.hakusan.sdk.ContinueSelectionResult
import app.hakusan.sdk.ContinueState
import app.hakusan.sdk.ContinueTarget
import app.hakusan.sdk.ContinueUnavailableReason
import app.hakusan.sdk.DetailsChapterItem
import app.hakusan.sdk.LibraryResumeState
import app.hakusan.sdk.LibraryScreen
import app.hakusan.sdk.LibraryShelfItem
import app.hakusan.sdk.LibraryTitleItem
import app.hakusan.sdk.LibraryTitleProgress
import app.hakusan.sdk.ScreenChapterId
import app.hakusan.sdk.ScreenChapterKey
import app.hakusan.sdk.ScreenContentUnitKind
import app.hakusan.sdk.ScreenReadingPosition
import app.hakusan.sdk.ScreenReadingStart
import app.hakusan.sdk.ScreenShelfId
import app.hakusan.sdk.ScreenSourceId
import app.hakusan.sdk.ScreenTitleId
import app.hakusan.sdk.ScreenTitleKey
import app.hakusan.sdk.TitleDetailsScreen
import app.hakusan.titles.LibraryShelfState
import app.hakusan.titles.LibraryShelf
import app.hakusan.titles.LibraryTitle
import app.hakusan.titles.ReconcileChapterSnapshot
import app.hakusan.titles.ReconcileSourceChapter
import app.hakusan.titles.ReconcileSourceTitle
import app.hakusan.titles.ReadingContentUnitKind
import app.hakusan.titles.SourceChapterAlias
import app.hakusan.titles.SourceTitleAlias
import app.hakusan.titles.TitleId
import app.hakusan.titles.TitleReadingProgress
import java.util.LinkedHashMap

internal fun SourceTitleKey.toScreenKey(): ScreenTitleKey = ScreenTitleKey(
  sourceId = ScreenSourceId(source.value),
  sourceTitleKey = key,
)

internal fun ScreenTitleKey.toSourceKey(): SourceTitleKey = SourceTitleKey(
  source = SourceIdentity(sourceId.value),
  key = sourceTitleKey,
)

private fun SourceChapterKey.toScreenKey(): ScreenChapterKey =
  ScreenChapterKey(
    titleKey = title.toScreenKey(),
    sourceChapterKey = key,
  )

private fun SourceChapterAlias.toScreenKey(): ScreenChapterKey =
  ScreenChapterKey(
    titleKey = titleAlias.toScreenKey(),
    sourceChapterKey = sourceChapterKey,
  )

private fun SourceTitleAlias.toScreenKey(): ScreenTitleKey = ScreenTitleKey(
  sourceId = ScreenSourceId(sourceIdentity),
  sourceTitleKey = sourceTitleKey,
)

internal fun SourceTitleDetails.toReconcileTitle(): ReconcileSourceTitle =
  ReconcileSourceTitle(
    alias = SourceTitleAlias(
      sourceIdentity = title.key.source.value,
      sourceTitleKey = title.key.key,
    ),
    displayName = title.displayName,
    description = description,
  )

internal fun ChapterSnapshot.toReconcileSnapshot(): ReconcileChapterSnapshot {
  val screenTitleAlias = SourceTitleAlias(
    sourceIdentity = title.source.value,
    sourceTitleKey = title.key,
  )
  return ReconcileChapterSnapshot.of(
    titleAlias = screenTitleAlias,
    chapters = chapters.map { chapter ->
      ReconcileSourceChapter(
        alias = SourceChapterAlias(
          titleAlias = screenTitleAlias,
          sourceChapterKey = chapter.key.key,
        ),
        displayName = chapter.displayName,
      )
    },
  )
}

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
  return TitleDetailsScreen.of(
    id = ScreenTitleId(titleId.value),
    key = titleAlias.toScreenKey(),
    sourceDisplayName = sourceDisplayName,
    displayName = details.title.displayName,
    description = details.description,
    chapters = canonicalChapters.map { state ->
      DetailsChapterItem(
        id = ScreenChapterId(state.chapter.id.value),
        titleId = ScreenTitleId(state.chapter.titleId.value),
        key = state.chapter.alias.toScreenKey(),
        displayName = state.chapter.displayName,
        isRead = state.isRead,
      )
    },
    isInLibrary = isInLibrary,
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

internal fun LibraryShelfState.toLibraryScreen(
  progressByTitleId: Map<TitleId, TitleReadingProgress>,
): LibraryScreen {
  check(progressByTitleId.keys == titlesById.keys) {
    "Library title and progress identities must agree."
  }
  val orderedTitles = CheckpointLibraryOrder.titles(titlesById.values)
  val screenTitles = LinkedHashMap<ScreenTitleId, LibraryTitleItem>(
    orderedTitles.size,
  )
  orderedTitles.forEach { title ->
    val progress = progressByTitleId.getValue(title.id)
    check(progress.isInLibrary && progress.titleAlias == title.alias) {
      "Library metadata and reading progress must describe one member."
    }
    val screenId = ScreenTitleId(title.id.value)
    val resume = progress.libraryResumePosition
    screenTitles[screenId] = LibraryTitleItem(
      id = screenId,
      key = title.alias.toScreenKey(),
      displayName = title.displayName,
      description = title.description,
      progress = LibraryTitleProgress(
        chapterCount = progress.canonicalChapters.size,
        readChapterCount = progress.canonicalChapters.count { it.isRead },
        resumeState = when {
          resume == null -> LibraryResumeState.NONE
          resume.isCurrentlyAvailable ->
            LibraryResumeState.AVAILABLE

          else -> LibraryResumeState.TEMPORARILY_UNAVAILABLE
        },
      ),
    )
  }
  val screenShelves = CheckpointLibraryOrder.shelves(this)
    .map { shelf ->
      LibraryShelfItem.of(
        id = ScreenShelfId(shelf.category.id.value),
        name = shelf.category.name,
        titleIds = CheckpointLibraryOrder.titles(
          shelf.titleIds.map { titlesById.getValue(it) },
        )
          .map { it.id }
          .map { ScreenTitleId(it.value) },
      )
    }
  return LibraryScreen.of(screenTitles, screenShelves)
}

/**
 * Temporary Checkpoint 1 ordering based only on visible title metadata.
 * Durable user-selected shelf order remains owned by the later settings slice.
 */
internal object CheckpointLibraryOrder {
  private val titleComparator =
    compareBy<LibraryTitle> { it.displayName }
      .thenComparator { first, second ->
        compareValues(first.description, second.description)
      }

  fun titles(titles: Iterable<LibraryTitle>): List<LibraryTitle> =
    titles.sortedWith(titleComparator)

  fun shelves(state: LibraryShelfState): List<LibraryShelf> =
    state.shelves.sortedWith { first, second ->
      val nameOrder = first.category.name.compareTo(second.category.name)
      if (nameOrder != 0) {
        nameOrder
      } else {
        compareTitleLists(
          first.titleIds.map { state.titlesById.getValue(it) },
          second.titleIds.map { state.titlesById.getValue(it) },
        )
      }
    }

  private fun compareTitleLists(
    first: List<LibraryTitle>,
    second: List<LibraryTitle>,
  ): Int {
    val firstOrdered = titles(first)
    val secondOrdered = titles(second)
    repeat(minOf(firstOrdered.size, secondOrdered.size)) { index ->
      val itemOrder = titleComparator.compare(
        firstOrdered[index],
        secondOrdered[index],
      )
      if (itemOrder != 0) {
        return itemOrder
      }
    }
    return firstOrdered.size.compareTo(secondOrdered.size)
  }
}

private fun ReadingContentUnitKind.toScreenKind(): ScreenContentUnitKind =
  when (this) {
    ReadingContentUnitKind.PAGE -> ScreenContentUnitKind.PAGE
    ReadingContentUnitKind.PROVIDER_SEGMENT ->
      ScreenContentUnitKind.PROVIDER_SEGMENT
  }
