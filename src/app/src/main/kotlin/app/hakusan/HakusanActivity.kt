package app.hakusan

import android.os.Bundle
import app.hakusan.ui.CatalogPresentationModel
import app.hakusan.ui.HakusanApp
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider

class HakusanActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      HakusanApp(
        catalogPresentationModel = { catalogPresentationModel },
        onExit = ::finish,
      )
    }
  }

  private val applicationGraph: ApplicationGraph
    get() = (application as HakusanApplication).applicationGraph

  private val catalogPresentationModel: CatalogPresentationModel by lazy(
    LazyThreadSafetyMode.NONE,
  ) {
    ViewModelProvider(
      owner = this,
      factory = CatalogPresentationModel.factory(
        browseScreenService = {
          applicationGraph.browseScreenService
        },
        titleDetailsScreenService = {
          applicationGraph.titleDetailsScreenService
        },
      ),
    )[CatalogPresentationModel::class.java]
  }
}
