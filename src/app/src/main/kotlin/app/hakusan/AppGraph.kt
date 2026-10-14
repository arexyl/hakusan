package app.hakusan

import app.hakusan.sdk.BrowseScreenService
import app.hakusan.titles.Titles
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides

/**
 * The application composition.
 *
 * Its inputs are one validated source registry and the [Titles] surface of one
 * titles store. It keeps one scoped instance of each screen service and exposes
 * only the screen-facing services.
 */
@DependencyGraph(
  AppScope::class,
  bindingContainers = [ScreenServiceBindings::class],
)
internal interface AppGraph {
  /** The Catalog and the source browse loads. */
  val browseScreenService: BrowseScreenService

  /** Creates a graph from its inputs. */
  @DependencyGraph.Factory
  fun interface Factory {
    fun create(
      @Provides registry: SourceRegistry,
      @Provides titles: Titles,
    ): AppGraph
  }
}

/**
 * Binds each screen-service adapter to the service that it implements.
 *
 * The bindings stay outside [AppGraph]. With Kotlin 2.4.21, Metro 1.4.5
 * compiles a binding member of the graph into an abstract method of the
 * concrete generated graph class.
 */
@BindingContainer
internal interface ScreenServiceBindings {
  @Binds val BrowseScreenAdapter.bind: BrowseScreenService
}
