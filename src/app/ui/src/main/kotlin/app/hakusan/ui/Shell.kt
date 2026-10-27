package app.hakusan.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.ViewModelStoreProvider
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.NavMetadataKey
import androidx.navigation3.runtime.get
import androidx.navigation3.runtime.metadata
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.ui.defaultTransitionSpec
import kotlin.reflect.KClass

/**
 * The application shell: one display over the selected stack, and the overlay
 * above it.
 *
 * Each stack keeps its saveable state holder, its ViewModel store provider, and
 * its decorated entries at shell level. Therefore an entry of the non-selected
 * stack keeps its state and its ViewModels, and a pop from either stack clears
 * the popped entry once its content leaves composition, at the latest when its
 * exit transition ends. The display shows the selected stack only, so Back acts
 * on that stack even during a destination crossfade, and a root registers no
 * Back callback.
 *
 * Keep the shell at one position of the composition. Moving it within a live
 * composition clears the ViewModels of both stacks. After recreation each stack
 * finds its ViewModels again under a fixed key, even if the shell moved, but
 * the saveable state of its entries is restored only at the same position.
 *
 * @param entryProvider returns the entry of a route. The entry's content key
 *   must be the content key of its route. Its content reads the padding that
 *   keeps it reachable above the overlay from the given function, while it
 *   composes.
 * @param overlay draws the overlay for the top entry of the selected stack.
 */
@Composable
internal fun Shell(
  navigationState: NavigationState,
  entryProvider:
    (route: NavKey, contentPadding: () -> PaddingValues) -> NavEntry<NavKey>,
  overlay: @Composable (TopEntry) -> Unit,
  modifier: Modifier = Modifier,
) {
  val overlayState = rememberFloatingOverlayState()

  val contentPadding = rememberUpdatedState(overlayState.contentPadding())

  val routeEntry = { route: NavKey ->
    entryProvider(route) { contentPadding.value }
  }

  val library =
    rememberStackEntries(Destination.Library, navigationState, routeEntry)

  val catalog =
    rememberStackEntries(Destination.Catalog, navigationState, routeEntry)

  val selected =
    when (navigationState.selected) {
      Destination.Library -> library
      Destination.Catalog -> catalog
    }

  FloatingOverlayHost(
    state = overlayState,
    overlay = { overlay(selected.top) },
    modifier = modifier,
  ) {
    NavDisplay(
      entries = selected.entries,
      modifier = Modifier.fillMaxSize(),
      transitionSpec = shellTransition,
      onBack = { navigationState.pop() },
    )
  }
}

/**
 * Returns the entry of [route] with its presentation owner of [modelClass],
 * which [content] receives.
 *
 * The entry creates the owner in its own ViewModel store through [factory]. It
 * also carries that factory instance, so [TopEntry.model] reads the same owner
 * through the same factory whichever side creates it first. The overlay creates
 * without creation extras, so [factory] must read none.
 */
internal fun <M : ViewModel> ownedEntry(
  route: HakusanRoute,
  modelClass: KClass<M>,
  factory: ViewModelProvider.Factory,
  content: @Composable (M) -> Unit,
): NavEntry<NavKey> {
  val ownerMetadata = metadata { put(OwnerFactory, factory) }

  return NavEntry<NavKey>(route, route.contentKey, ownerMetadata) {
    content(viewModel(modelClass, factory = factory))
  }
}

/**
 * The top entry of a stack, the only entry whose state the overlay may read.
 *
 * Only the shell creates it, once for the live top of each stack, and it passes
 * the top of the selected stack to the overlay. Therefore a read never reaches
 * a popped entry and never recreates its store. The ViewModel decorator scopes
 * an entry's ViewModel store to the entry's content key. The overlay reads the
 * same store, so it reads the same ViewModel instance as the entry.
 */
internal sealed interface TopEntry {
  /** The route of the entry. */
  val route: NavKey

  /**
   * Returns the entry's presentation owner of [modelClass], created through the
   * factory of the entry if the entry has none yet.
   *
   * The read holds the entry's store while it stays composed for this entry.
   *
   * @throws IllegalStateException if the entry has no presentation owner.
   */
  @Composable fun <M : ViewModel> model(modelClass: KClass<M>): M
}

/**
 * The metadata key of the factory of an entry's presentation owner.
 *
 * Navigation 3 stores metadata under the string form of its key, so the string
 * form names this key in full.
 */
private object OwnerFactory : NavMetadataKey<ViewModelProvider.Factory> {
  override fun toString(): String = "app.hakusan.ui.OwnerFactory"
}

/** The live top of one stack, which only [rememberStackEntries] creates. */
private class StackTopEntry(
  override val route: NavKey,
  private val contentKey: Any,
  private val ownerFactory: ViewModelProvider.Factory?,
  private val stores: ViewModelStoreProvider,
) : TopEntry {
  @Composable
  override fun <M : ViewModel> model(modelClass: KClass<M>): M {
    val factory =
      checkNotNull(ownerFactory) { "The top entry has no presentation owner." }

    DisposableEffect(stores, contentKey) {
      val token = stores.acquireToken(contentKey)

      onDispose { token.close() }
    }

    return remember(stores, contentKey, modelClass) {
      val store = stores.getOrCreate(contentKey)

      ViewModelProvider.create(store, factory)[modelClass]
    }
  }
}

/** The decorated entries of one stack and its top entry. */
private class StackEntries(
  val entries: List<NavEntry<NavKey>>,
  val top: TopEntry,
)

/**
 * The key of a stack's ViewModel store provider in the Activity's store.
 *
 * A fixed key, unlike the default key of the call site, finds the same provider
 * after recreation even if the composition above the shell changed its shape.
 */
private data class StackStoreKey(val destination: Destination)

@Composable
private fun rememberStackEntries(
  destination: Destination,
  navigationState: NavigationState,
  entryProvider: (NavKey) -> NavEntry<NavKey>,
): StackEntries {
  val saveableState = rememberSaveableStateHolderNavEntryDecorator<NavKey>()

  val stores = rememberViewModelStoreProvider(key = StackStoreKey(destination))

  val entryModels = rememberViewModelStoreNavEntryDecorator<NavKey>(stores)

  val decorators =
    remember(saveableState, entryModels) { listOf(saveableState, entryModels) }

  val backStack = navigationState.backStack(destination)

  val entries =
    rememberDecoratedNavEntries(backStack, decorators, entryProvider)

  val route = backStack.last()

  val contentKey = entries.last().contentKey

  val ownerFactory = entries.last().metadata[OwnerFactory]

  val top =
    remember(stores, route, contentKey, ownerFactory) {
      StackTopEntry(route, contentKey, ownerFactory, stores)
    }

  return StackEntries(entries, top)
}

private val defaultTransition = defaultTransitionSpec<NavKey>()

/**
 * Crossfades a destination switch with the default animation of the Crossfade
 * composable, and keeps the display's default transition within one stack.
 */
private val shellTransition:
  AnimatedContentTransitionScope<Scene<NavKey>>.() -> ContentTransform =
  {
    if (initialState.rootContentKey == targetState.rootContentKey) {
      defaultTransition()
    } else {
      fadeIn(tween()) togetherWith fadeOut(tween())
    }
  }

private val Scene<NavKey>.rootContentKey: Any
  get() = (previousEntries.firstOrNull() ?: entries.first()).contentKey
