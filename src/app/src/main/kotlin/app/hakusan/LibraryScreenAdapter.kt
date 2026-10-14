package app.hakusan

import app.hakusan.sdk.AddToLibraryScreenResult
import app.hakusan.sdk.LibraryScreen
import app.hakusan.sdk.LibraryScreenService
import app.hakusan.sdk.LibraryShelfMembers
import app.hakusan.sdk.LibraryTitleItem
import app.hakusan.sdk.ScreenTitleId
import app.hakusan.titles.LibraryShelf
import app.hakusan.titles.LibrarySnapshot
import app.hakusan.titles.LibraryTitle
import app.hakusan.titles.SourceTitleAlias
import app.hakusan.titles.TitleId
import app.hakusan.titles.Titles
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.util.Collections
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Serves the committed Library and the automatic Add from [titles].
 *
 * Each Library snapshot becomes one screen, ordered by [LibraryScreen.of] under
 * the process default locale at that emission. Every stored category becomes a
 * shelf, including an empty one. The source name of a title is the Catalog name
 * that the [registry] captured, or empty text when its source is not
 * registered. The flows of [titles] already run on their own worker, so the
 * adapter adds no thread hop.
 */
@Inject
@SingleIn(AppScope::class)
internal class LibraryScreenAdapter(
  private val registry: SourceRegistry,
  private val titles: Titles,
) : LibraryScreenService {
  override fun observeLibrary(): Flow<LibraryScreen> =
    titles.observeLibrary().map(::toLibraryScreen)

  override fun observeLibraryTitleIds(): Flow<Set<ScreenTitleId>> =
    titles.observeLibraryTitleIds().map(::toScreenTitleIds)

  override suspend fun addToLibrary(
    titleId: ScreenTitleId
  ): AddToLibraryScreenResult =
    titles.addToLibrary(titleId.toTitleId()).toAddToLibraryScreenResult()

  private fun toLibraryScreen(snapshot: LibrarySnapshot): LibraryScreen {
    val items = snapshot.titles.map { (id, title) -> toTitleItem(id, title) }

    val shelves = snapshot.shelves.map(LibraryShelf::toShelfMembers)

    return LibraryScreen.of(items, shelves, Locale.getDefault())
  }

  private fun toTitleItem(id: TitleId, title: LibraryTitle): LibraryTitleItem =
    LibraryTitleItem(
      id = id.toScreenTitleId(),
      key = title.alias.toScreenTitleKey(),
      displayName = title.displayName,
      description = title.description,
      sourceDisplayName = sourceDisplayName(title.alias),
      progress = title.progress.toLibraryTitleProgress(),
    )

  private fun sourceDisplayName(alias: SourceTitleAlias): String {
    val registration = registry.find(alias.toSourceIdentity())

    return registration?.catalogItem?.displayName ?: ""
  }
}

private fun LibraryShelf.toShelfMembers(): LibraryShelfMembers =
  LibraryShelfMembers(
    id = category.id.toScreenShelfId(),
    name = category.name,
    titleIds = titleIds.mapTo(HashSet()) { it.toScreenTitleId() },
  )

private fun toScreenTitleIds(titleIds: Set<TitleId>): Set<ScreenTitleId> {
  val screenIds = titleIds.mapTo(HashSet()) { it.toScreenTitleId() }

  return Collections.unmodifiableSet(screenIds)
}
