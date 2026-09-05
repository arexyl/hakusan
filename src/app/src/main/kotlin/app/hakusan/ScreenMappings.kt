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
import app.hakusan.titles.LibraryResumeAvailability
import app.hakusan.titles.LibraryShelf
import app.hakusan.titles.LibraryShelfState
import app.hakusan.titles.LibrarySummaryState
import app.hakusan.titles.LibraryTitle
import app.hakusan.titles.ReconcileChapterSnapshot
import app.hakusan.titles.ReconcileSourceChapter
import app.hakusan.titles.ReconcileSourceTitle
import app.hakusan.titles.ReadingContentUnitKind
import app.hakusan.titles.SourceChapterAlias
import app.hakusan.titles.SourceTitleAlias
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

internal fun LibrarySummaryState.toLibraryScreen(): LibraryScreen {
  val order = CheckpointLibraryOrder.create(shelfState)
  val screenTitles = LinkedHashMap<ScreenTitleId, LibraryTitleItem>(
    order.titles.size,
  )
  order.titles.forEach { title ->
    val progress = progressByTitleId.getValue(title.id)
    val screenId = ScreenTitleId(title.id.value)
    screenTitles[screenId] = LibraryTitleItem(
      id = screenId,
      key = title.alias.toScreenKey(),
      displayName = title.displayName,
      description = title.description,
      progress = LibraryTitleProgress(
        chapterCount = progress.chapterCount,
        readChapterCount = progress.readChapterCount,
        resumeState = when (progress.resumeAvailability) {
          LibraryResumeAvailability.NONE -> LibraryResumeState.NONE
          LibraryResumeAvailability.AVAILABLE -> LibraryResumeState.AVAILABLE
          LibraryResumeAvailability.TEMPORARILY_UNAVAILABLE ->
            LibraryResumeState.TEMPORARILY_UNAVAILABLE
        },
      ),
    )
  }
  val screenShelves = order.shelves.map { orderedShelf ->
    val shelf = orderedShelf.shelf
    LibraryShelfItem.of(
      id = ScreenShelfId(shelf.category.id.value),
      name = shelf.category.name,
      titleIds = orderedShelf.titles.map { ScreenTitleId(it.id.value) },
    )
  }
  return LibraryScreen.of(screenTitles, screenShelves)
}

/**
 * Temporary Checkpoint 1 ordering based only on visible title metadata.
 * Durable user-selected shelf order remains owned by the later settings slice.
 */
private class CheckpointLibraryOrder private constructor(
  val titles: List<LibraryTitle>,
  val shelves: List<OrderedLibraryShelf>,
) {
  companion object {
    private val titleComparator =
      compareBy<LibraryTitle> { it.displayName }
        .thenComparator { first, second ->
          compareValues(first.description, second.description)
        }

    fun create(state: LibraryShelfState): CheckpointLibraryOrder {
      val titles = state.titlesById.values.sortedWith(titleComparator)
      val shelves = state.shelves
        .map { shelf ->
          OrderedLibraryShelf(
            shelf = shelf,
            titles = shelf.titleIds
              .map { state.titlesById.getValue(it) }
              .sortedWith(titleComparator),
          )
        }
        .sortedWith { first, second ->
          val nameOrder = first.shelf.category.name.compareTo(
            second.shelf.category.name,
          )
          if (nameOrder != 0) {
            nameOrder
          } else {
            compareTitleLists(first.titles, second.titles)
          }
        }
      return CheckpointLibraryOrder(titles, shelves)
    }

    private fun compareTitleLists(
      first: List<LibraryTitle>,
      second: List<LibraryTitle>,
    ): Int {
      repeat(minOf(first.size, second.size)) { index ->
        val itemOrder = titleComparator.compare(first[index], second[index])
        if (itemOrder != 0) {
          return itemOrder
        }
      }
      return first.size.compareTo(second.size)
    }
  }
}

private data class OrderedLibraryShelf(
  val shelf: LibraryShelf,
  val titles: List<LibraryTitle>,
)

private fun ReadingContentUnitKind.toScreenKind(): ScreenContentUnitKind =
  when (this) {
    ReadingContentUnitKind.PAGE -> ScreenContentUnitKind.PAGE
    ReadingContentUnitKind.PROVIDER_SEGMENT ->
      ScreenContentUnitKind.PROVIDER_SEGMENT
  }
