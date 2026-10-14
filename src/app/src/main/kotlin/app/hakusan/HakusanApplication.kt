package app.hakusan

import android.app.Application
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
}
