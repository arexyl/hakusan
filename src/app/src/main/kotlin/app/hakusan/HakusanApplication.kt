package app.hakusan

import android.app.Application
import app.hakusan.sdk.BrowseScreenService
import app.hakusan.sdk.LibraryScreenService
import app.hakusan.titles.TitlesStore
import dev.zacsweers.metro.createGraphFactory

/**
 * The application process.
 *
 * It owns the one titles store and the one composition graph of the process.
 * Both are created on first use, so a process that never reads the graph never
 * opens the database. The store lives until the process ends.
 */
internal class HakusanApplication : Application() {
  private val titlesStore: TitlesStore by lazy { TitlesStore.open(this) }

  /** The composition graph of this process. */
  val graph: AppGraph by lazy {
    createGraphFactory<AppGraph.Factory>()
      .create(
        registry = SourceRegistry.of(sourceBackends()),
        titles = titlesStore.titles,
      )
  }

  /**
   * The source of the screen services that each Activity passes to the user
   * interface.
   *
   * The production source reads each service from [graph] when the factory of a
   * presentation owner first calls its provider. A device test replaces the
   * source before it launches an Activity, and restores the previous source
   * afterwards. The replacement serves every Activity of the process until
   * then, across recreation.
   */
  @Volatile var screenServices: ScreenServiceSource = GraphScreenServices(this)
}

/**
 * The screen services that an Activity passes to the user interface, one
 * property per service that the user interface uses.
 *
 * The Activity reads a property only when an owner's factory calls the
 * service's provider.
 */
internal interface ScreenServiceSource {
  /** The Catalog and the source browse loads. */
  val browse: BrowseScreenService

  /** The committed Library, its membership, and the automatic Add. */
  val library: LibraryScreenService
}

private class GraphScreenServices(private val application: HakusanApplication) :
  ScreenServiceSource {
  override val browse: BrowseScreenService
    get() = application.graph.browseScreenService

  override val library: LibraryScreenService
    get() = application.graph.libraryScreenService
}
