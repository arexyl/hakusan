package app.hakusan

import android.os.Bundle
import app.hakusan.ui.CatalogViewModel
import app.hakusan.ui.HakusanApp
import app.hakusan.ui.LibraryViewModel
import app.hakusan.ui.TitleDetailsViewModel
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
        catalogModel = { catalogModel },
        titleDetailsModel = { titleDetailsModel },
        libraryModel = { libraryModel },
        onExit = ::finish,
      )
    }
  }

  private val graph: AppGraph
    get() = (application as HakusanApplication).graph

  private val catalogModel: CatalogViewModel by lazy(
    LazyThreadSafetyMode.NONE,
  ) {
    ViewModelProvider(
      owner = this,
      factory = CatalogViewModel.factory(
        browseService = {
          graph.browseService
        },
      ),
    )[CatalogViewModel::class.java]
  }

  private val titleDetailsModel: TitleDetailsViewModel by lazy(
    LazyThreadSafetyMode.NONE,
  ) {
    ViewModelProvider(
      owner = this,
      factory = TitleDetailsViewModel.factory(
        detailsService = {
          graph.detailsService
        },
        continueService = {
          graph.continueService
        },
      ),
    )[TitleDetailsViewModel::class.java]
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
