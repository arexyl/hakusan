package app.hakusan

import android.app.Application
import app.hakusan.titles.TitlesStore
import app.hakusan.titles.openTitlesStore

class HakusanApplication : Application() {
  internal val applicationGraph: ApplicationGraph by lazy {
    createApplicationGraph(
      sourceRegistry = SourceRegistry.of(applicationSourceBackends()),
      titles = titlesStore.titles,
    )
  }

  private val titlesStore: TitlesStore by lazy {
    openTitlesStore(this)
  }
}
