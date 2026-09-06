package app.hakusan

import app.hakusan.sdk.BrowseScreenService
import app.hakusan.sdk.ContinueSelectionService
import app.hakusan.sdk.LibraryScreenService
import app.hakusan.sdk.TitleDetailsScreenService
import app.hakusan.titles.Titles
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createGraphFactory

internal abstract class AppScope private constructor()

@DependencyGraph(AppScope::class)
internal interface AppGraph {
  val browseService: BrowseScreenService
  val detailsService: TitleDetailsScreenService
  val libraryService: LibraryScreenService
  val continueService: ContinueSelectionService

  @Provides
  fun bindBrowse(
    adapter: BrowseScreenAdapter,
  ): BrowseScreenService = adapter

  @Provides
  fun bindTitleDetails(
    adapter: TitleDetailsScreenAdapter,
  ): TitleDetailsScreenService = adapter

  @Provides
  fun bindLibrary(
    adapter: LibraryScreenAdapter,
  ): LibraryScreenService = adapter

  @Provides
  fun bindContinueSelection(
    adapter: ContinueSelectionAdapter,
  ): ContinueSelectionService = adapter

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(
      @Provides sourceRegistry: SourceRegistry,
      @Provides titles: Titles,
    ): AppGraph
  }
}

internal fun createAppGraph(
  sourceRegistry: SourceRegistry,
  titles: Titles,
): AppGraph = createGraphFactory<AppGraph.Factory>().create(
  sourceRegistry = sourceRegistry,
  titles = titles,
)
