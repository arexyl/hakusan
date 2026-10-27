package app.hakusan.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import app.hakusan.sdk.BrowseScreenService
import app.hakusan.sdk.LibraryScreenService

/**
 * The user interface of the application: the Library and Catalog destinations
 * in the shell, with the browsing island.
 *
 * Each parameter provides one screen service. A presentation owner calls a
 * provider only in its factory, so a provider runs at most once per owner. The
 * shell creates the Library owner when it first composes, so [library] runs at
 * launch. That owner lives as long as the Activity scope of the composition,
 * across recreation. The Catalog owner lives with the entry of the Catalog
 * root, so [browse] runs when that entry first composes.
 */
@Composable
public fun HakusanApp(
  browse: () -> BrowseScreenService,
  library: () -> LibraryScreenService,
  modifier: Modifier = Modifier,
) {
  HakusanTheme {
    Surface(
      modifier = modifier.fillMaxSize(),
      color = MaterialTheme.colorScheme.background,
    ) {
      val navigationState = rememberNavigationState()

      val libraryFactory = remember(library) { LibraryModel.factory(library) }

      val libraryModel: LibraryModel = viewModel(factory = libraryFactory)

      val catalogFactory = remember(browse) { CatalogModel.factory(browse) }

      Shell(
        navigationState = navigationState,
        entryProvider = { route, contentPadding ->
          destinationEntry(route, contentPadding, libraryModel, catalogFactory)
        },
        overlay = {
          BrowsingIsland(navigationState.selected, navigationState::select)
        },
      )
    }
  }
}

private fun destinationEntry(
  key: NavKey,
  contentPadding: () -> PaddingValues,
  library: LibraryModel,
  catalog: ViewModelProvider.Factory,
): NavEntry<NavKey> {
  // The route configuration registers only application routes.
  val route = key as HakusanRoute

  return when (route) {
    LibraryRoute ->
      NavEntry<NavKey>(route, route.contentKey) {
        LibraryDestination(library, contentPadding())
      }

    CatalogRoute ->
      ownedEntry(route, CatalogModel::class, catalog) { model ->
        CatalogContent(model.catalog, contentPadding())
      }
  }
}
