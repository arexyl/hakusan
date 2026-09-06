package app.hakusan

import android.app.Application
import app.hakusan.titles.TitlesStore
import app.hakusan.titles.openTitlesStore

class HakusanApplication : Application() {
  internal val graph: AppGraph by lazy {
    createAppGraph(
      sourceRegistry = SourceRegistry.of(sourceBackends()),
      titles = titlesStore.titles,
    )
  }

  private val titlesStore: TitlesStore by lazy {
    openTitlesStore(this)
  }
}
