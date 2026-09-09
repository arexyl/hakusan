package app.hakusan

import app.hakusan.sdk.AddToLibraryScreenResult
import app.hakusan.sdk.LibraryResumeState
import app.hakusan.sdk.LibraryScreen
import app.hakusan.sdk.LibraryScreenService
import app.hakusan.sdk.LibraryShelfItem
import app.hakusan.sdk.LibraryTitleItem
import app.hakusan.sdk.LibraryTitleProgress
import app.hakusan.sdk.ScreenShelfId
import app.hakusan.sdk.ScreenTitleId
import app.hakusan.titles.LibraryAddResult
import app.hakusan.titles.LibraryResumeAvailability
import app.hakusan.titles.LibraryShelf
import app.hakusan.titles.LibraryState
import app.hakusan.titles.LibraryTitle
import app.hakusan.titles.TitleId
import app.hakusan.titles.Titles
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Adapts title persistence to the screen-facing Library contract. */
@Inject
@SingleIn(AppScope::class)
internal class LibraryScreenAdapter(
  private val titles: Titles,
) : LibraryScreenService {
  override fun observeLibrary(): Flow<LibraryScreen> =
    titles.observeLibrary()
      .map { state -> state.toLibraryScreen() }

  override fun observeLibraryTitleIds(): Flow<Set<ScreenTitleId>> =
    titles.observeLibraryTitleIds()
      .map(::toOwnedScreenTitleIds)

  override suspend fun addToLibrary(
    titleId: ScreenTitleId,
  ): AddToLibraryScreenResult = when (
    val result = titles.addToLibrary(TitleId(titleId.value))
  ) {
    is LibraryAddResult.Success -> AddToLibraryScreenResult.Success
    is LibraryAddResult.CategorySelectionRequired ->
      AddToLibraryScreenResult.CategorySelectionRequired

    LibraryAddResult.TitleNotFound -> AddToLibraryScreenResult.TitleNotFound
  }

  private fun toOwnedScreenTitleIds(
    titleIds: Set<TitleId>,
  ): Set<ScreenTitleId> {
    val result = LinkedHashSet<ScreenTitleId>(titleIds.size)
    titleIds.forEach { titleId ->
      result += ScreenTitleId(titleId.value)
    }
    return Collections.unmodifiableSet(result)
  }
}

internal fun LibraryState.toLibraryScreen(): LibraryScreen {
  val screenOrder = LibraryOrder.create(this)
  val screenTitles = LinkedHashMap<ScreenTitleId, LibraryTitleItem>(
    titlesById.size,
  )
  titlesById.values.forEach { title ->
    val progress = title.progress
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
  val screenShelves = screenOrder.shelves.map { orderedShelf ->
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
 * Orders Library content by visible category and title metadata.
 * Values with identical presentation metadata compare as equivalent.
 */
private class LibraryOrder private constructor(
  val shelves: List<OrderedShelf>,
) {
  companion object {
    private val titleComparator =
      compareBy<LibraryTitle> { it.displayName }
        .thenComparator { first, second ->
          compareValues(first.description, second.description)
        }

    fun create(state: LibraryState): LibraryOrder {
      val shelves = state.shelves
        .map { shelf ->
          OrderedShelf(
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
            compareTitles(first.titles, second.titles)
          }
        }
      return LibraryOrder(shelves)
    }

    private fun compareTitles(
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

private data class OrderedShelf(
  val shelf: LibraryShelf,
  val titles: List<LibraryTitle>,
)
