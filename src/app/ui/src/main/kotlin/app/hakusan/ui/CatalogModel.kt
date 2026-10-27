package app.hakusan.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.hakusan.sdk.BrowseScreenService
import app.hakusan.sdk.CatalogScreen

/**
 * The presentation owner of the Catalog overview, scoped to the entry of the
 * Catalog root.
 *
 * It reads the Catalog from [browse] once, when it is created, and keeps that
 * static snapshot. The overview gains loading and failure states only when the
 * Catalog becomes observable.
 */
internal class CatalogModel(browse: BrowseScreenService) : ViewModel() {
  /** The locally available sources, in the order of the Catalog. */
  val catalog: CatalogScreen = browse.catalog()

  companion object {
    /**
     * Returns the factory of the Catalog owner.
     *
     * It calls [browse] only when it creates an owner, and reads no creation
     * extras.
     */
    fun factory(browse: () -> BrowseScreenService): ViewModelProvider.Factory =
      viewModelFactory {
        initializer { CatalogModel(browse()) }
      }
  }
}
