package app.hakusan.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import app.hakusan.sdk.ScreenSourceId
import app.hakusan.sdk.ScreenTitleKey
import kotlinx.serialization.Serializable

@Serializable
internal sealed interface HakusanRoute : NavKey

@Serializable
internal data object LibraryRoute : HakusanRoute

@Serializable
internal data object CatalogRoute : HakusanRoute

@Serializable
internal data class SourceBrowseRoute(
  val sourceId: String,
) : HakusanRoute {
  init {
    ScreenSourceId(sourceId)
  }

  fun toScreenSourceId(): ScreenSourceId = ScreenSourceId(sourceId)
}

@Serializable
internal data class TitleDetailsRoute(
  val sourceId: String,
  val sourceTitleKey: String,
) : HakusanRoute {
  init {
    toScreenTitleKey()
  }

  fun toScreenTitleKey(): ScreenTitleKey = ScreenTitleKey(
    sourceId = ScreenSourceId(sourceId),
    sourceTitleKey = sourceTitleKey,
  )
}

internal enum class PrimaryDestination {
  LIBRARY,
  CATALOG,
}

internal class HakusanNavigationState(
  selectedDestination: MutableState<PrimaryDestination>,
  private val libraryBackStack: NavBackStack<NavKey>,
  private val catalogBackStack: NavBackStack<NavKey>,
) {
  var selectedDestination by selectedDestination
    private set

  val currentBackStack: NavBackStack<NavKey>
    get() = backStack(selectedDestination)

  val currentRoute: NavKey
    get() = currentBackStack.last()

  val showsBrowsingIsland: Boolean
    get() = currentRoute !is TitleDetailsRoute

  fun backStack(destination: PrimaryDestination): NavBackStack<NavKey> =
    when (destination) {
      PrimaryDestination.LIBRARY -> libraryBackStack
      PrimaryDestination.CATALOG -> catalogBackStack
    }

  fun select(destination: PrimaryDestination) {
    selectedDestination = destination
  }

  fun openCatalogSource(sourceId: ScreenSourceId) {
    if (
      selectedDestination == PrimaryDestination.CATALOG &&
      catalogBackStack.lastOrNull() == CatalogRoute
    ) {
      catalogBackStack.add(SourceBrowseRoute(sourceId.value))
    }
  }

  fun openCatalogTitle(titleKey: ScreenTitleKey) {
    val browseRoute = catalogBackStack.lastOrNull() as? SourceBrowseRoute
      ?: return
    require(titleKey.sourceId.value == browseRoute.sourceId) {
      "A Catalog title must belong to the open source."
    }
    if (selectedDestination == PrimaryDestination.CATALOG) {
      catalogBackStack.add(
        TitleDetailsRoute(
          sourceId = titleKey.sourceId.value,
          sourceTitleKey = titleKey.sourceTitleKey,
        ),
      )
    }
  }

  fun pop(): NavKey? {
    val backStack = currentBackStack
    if (backStack.size <= 1) {
      return null
    }
    return backStack.removeAt(backStack.lastIndex)
  }

  fun popCatalog(expectedRoute: NavKey): NavKey? {
    if (
      selectedDestination != PrimaryDestination.CATALOG ||
      catalogBackStack.size <= 1 ||
      catalogBackStack.lastOrNull() != expectedRoute
    ) {
      return null
    }
    return catalogBackStack.removeAt(catalogBackStack.lastIndex)
  }
}

@Composable
internal fun rememberHakusanNavigationState(
  initialDestination: PrimaryDestination = PrimaryDestination.LIBRARY,
): HakusanNavigationState {
  val selectedDestination = rememberSaveable(
    initialDestination,
    stateSaver = PrimaryDestinationSaver,
  ) {
    mutableStateOf(initialDestination)
  }
  val libraryBackStack = rememberNavBackStack(LibraryRoute)
  val catalogBackStack = rememberNavBackStack(CatalogRoute)
  return remember(
    selectedDestination,
    libraryBackStack,
    catalogBackStack,
  ) {
    HakusanNavigationState(
      selectedDestination = selectedDestination,
      libraryBackStack = libraryBackStack,
      catalogBackStack = catalogBackStack,
    )
  }
}

private val PrimaryDestinationSaver = Saver<PrimaryDestination, String>(
  save = { it.name },
  restore = PrimaryDestination::valueOf,
)
