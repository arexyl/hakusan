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
import kotlinx.serialization.Serializable

@Serializable
internal sealed interface HakusanRoute : NavKey

@Serializable
internal data object LibraryRoute : HakusanRoute

@Serializable
internal data object CatalogRoute : HakusanRoute

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

  fun backStack(destination: PrimaryDestination): NavBackStack<NavKey> =
    when (destination) {
      PrimaryDestination.LIBRARY -> libraryBackStack
      PrimaryDestination.CATALOG -> catalogBackStack
    }

  fun select(destination: PrimaryDestination) {
    selectedDestination = destination
  }

  fun pop(): Boolean {
    val backStack = currentBackStack
    if (backStack.size <= 1) {
      return false
    }
    backStack.removeAt(backStack.lastIndex)
    return true
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
