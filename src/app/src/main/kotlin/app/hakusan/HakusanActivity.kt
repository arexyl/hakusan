package app.hakusan

import android.os.Bundle
import app.hakusan.ui.BrowsingViewModel
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
        browsingModel = { browsingModel },
        onExit = ::finish,
      )
    }
  }

  private val graph: AppGraph
    get() = (application as HakusanApplication).graph

  private val browsingModel: BrowsingViewModel by lazy(
    LazyThreadSafetyMode.NONE,
  ) {
    ViewModelProvider(
      owner = this,
      factory = BrowsingViewModel.factory(
        browseService = {
          graph.browseService
        },
        detailsService = {
          graph.detailsService
        },
      ),
    )[BrowsingViewModel::class.java]
  }
}
