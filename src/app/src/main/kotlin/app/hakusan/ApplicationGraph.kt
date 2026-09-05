package app.hakusan

import app.hakusan.sdk.BrowseScreenService
import app.hakusan.sdk.LibraryScreenService
import app.hakusan.sdk.TitleDetailsScreenService
import app.hakusan.titles.Titles
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createGraphFactory

internal abstract class ApplicationScope private constructor()

@DependencyGraph(ApplicationScope::class)
internal interface ApplicationGraph {
  val browseScreenService: BrowseScreenService
  val libraryScreenService: LibraryScreenService
  val titleDetailsScreenService: TitleDetailsScreenService

  @Provides
  fun provideBrowseScreenService(
    service: ApplicationBrowseScreenService,
  ): BrowseScreenService = service

  @Provides
  fun provideLibraryScreenService(
    service: ApplicationLibraryScreenService,
  ): LibraryScreenService = service

  @Provides
  fun provideTitleDetailsScreenService(
    service: ApplicationTitleDetailsScreenService,
  ): TitleDetailsScreenService = service

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(
      @Provides sourceRegistry: SourceRegistry,
      @Provides titles: Titles,
    ): ApplicationGraph
  }
}

internal fun createApplicationGraph(
  sourceRegistry: SourceRegistry,
  titles: Titles,
): ApplicationGraph = createGraphFactory<ApplicationGraph.Factory>().create(
  sourceRegistry = sourceRegistry,
  titles = titles,
)
