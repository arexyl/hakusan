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
import androidx.savedstate.serialization.SavedStateConfiguration

/** A primary destination of the shell, with its own retained back stack. */
internal enum class Destination {
  Library,
  Catalog,
}

/**
 * The two retained back stacks of the shell and the selected destination.
 *
 * Library is selected initially, and switching destinations keeps both stacks.
 * Each stack holds its own root first and each nested route once, so every
 * entry of a stack has its own content key. Each change that an entry requests
 * binds to that exact entry: a nested route equals only its own push, so a
 * callback of an outgoing entry never acts on a reopened equal route. No change
 * removes a root.
 *
 * Read and change the state on the main thread only.
 *
 * @throws IllegalArgumentException if a stack does not start with its own root,
 *   holds another root, or holds a nested route twice.
 */
internal class NavigationState(
  selected: MutableState<Destination>,
  private val library: NavBackStack<NavKey>,
  private val catalog: NavBackStack<NavKey>,
) {
  /** The destination whose stack the shell shows and Back acts on. */
  var selected: Destination by selected
    private set

  init {
    for (destination in Destination.entries) {
      val stack = stack(destination)

      val nested = stack.drop(1)

      require(
        stack.firstOrNull() == destination.root &&
          nested.none(::isRoot) &&
          nested.toSet().size == nested.size
      ) {
        "A stack must hold its own root first and each nested route once."
      }
    }
  }

  /** Returns the routes of the stack of [destination], root first. */
  fun backStack(destination: Destination): List<NavKey> = stack(destination)

  /** Selects [destination] and shows its retained stack. */
  fun select(destination: Destination) {
    selected = destination
  }

  /**
   * Pushes [route] onto the selected stack when [owner] is its top entry.
   *
   * Returns whether it pushed. A stale owner changes nothing. A root is never a
   * [NestedRoute], so no push can repeat a root.
   *
   * @throws IllegalArgumentException if [route] is already in the selected
   *   stack.
   */
  fun open(owner: NavKey, route: NestedRoute): Boolean {
    val stack = stack(selected)

    require(route !in stack) { "Only a new nested route can be pushed." }

    if (stack.last() != owner) {
      return false
    }

    stack.add(route)

    return true
  }

  /**
   * Pops the top entry of the selected stack, which system Back requests.
   *
   * Returns whether it popped. A root stays.
   */
  fun pop(): Boolean {
    val stack = stack(selected)

    if (stack.size <= 1) {
      return false
    }

    stack.removeAt(stack.lastIndex)

    return true
  }

  private fun stack(destination: Destination): NavBackStack<NavKey> =
    when (destination) {
      Destination.Library -> library
      Destination.Catalog -> catalog
    }
}

/**
 * Remembers the navigation state of the shell across recreation and process
 * restoration.
 *
 * The stacks save their routes through [configuration], which must register
 * every route that a stack can hold.
 */
@Composable
internal fun rememberNavigationState(
  configuration: SavedStateConfiguration = routeConfiguration
): NavigationState {
  val selected =
    rememberSaveable(stateSaver = DestinationSaver) {
      mutableStateOf(Destination.Library)
    }

  val library = rememberNavBackStack(configuration, Destination.Library.root)

  val catalog = rememberNavBackStack(configuration, Destination.Catalog.root)

  return remember(selected, library, catalog) {
    NavigationState(selected, library, catalog)
  }
}

/** The root route of the stack of this destination. */
private val Destination.root: NavKey
  get() =
    when (this) {
      Destination.Library -> LibraryRoute
      Destination.Catalog -> CatalogRoute
    }

private fun isRoot(route: NavKey): Boolean =
  Destination.entries.any { destination -> destination.root == route }

private val DestinationSaver =
  Saver<Destination, String>(
    save = { destination -> destination.name },
    restore = { name -> enumValueOf<Destination>(name) },
  )
