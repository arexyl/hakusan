package app.hakusan

import android.os.Bundle
import app.hakusan.ui.BrowsingViewModel
import app.hakusan.ui.HakusanApp
import app.hakusan.ui.LibraryViewModel
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
        libraryModel = { libraryModel },
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
        continueService = {
          graph.continueService
        },
      ),
    )[BrowsingViewModel::class.java]
  }

  private val libraryModel: LibraryViewModel by lazy(
    LazyThreadSafetyMode.NONE,
  ) {
    ViewModelProvider(
      owner = this,
      factory = LibraryViewModel.factory(
        libraryService = {
          graph.libraryService
        },
      ),
    )[LibraryViewModel::class.java]
  }
}
