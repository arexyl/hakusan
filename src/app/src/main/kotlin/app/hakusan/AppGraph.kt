package app.hakusan

import app.hakusan.sdk.BrowseScreenService
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
  val libraryService: LibraryScreenService
  val detailsService: TitleDetailsScreenService

  @Provides
  fun bindBrowse(
    services: ApplicationScreenServices,
  ): BrowseScreenService = services

  @Provides
  fun bindLibrary(
    services: ApplicationScreenServices,
  ): LibraryScreenService = services

  @Provides
  fun bindTitleDetails(
    services: ApplicationScreenServices,
  ): TitleDetailsScreenService = services

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
