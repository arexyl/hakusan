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
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
internal sealed interface HakusanRoute : NavKey

@Serializable
internal data object LibraryRoute : HakusanRoute

@Serializable
internal data object CatalogRoute : HakusanRoute

@Serializable
@ConsistentCopyVisibility
internal data class SourceBrowseRoute private constructor(
  val sourceId: String,
  private val presentationToken: String,
) : HakusanRoute {
  internal constructor(sourceId: String) : this(
    sourceId = sourceId,
    presentationToken = newPresentationToken(),
  )

  init {
    ScreenSourceId(sourceId)
  }

  fun toScreenSourceId(): ScreenSourceId = ScreenSourceId(sourceId)
}

@Serializable
@ConsistentCopyVisibility
internal data class TitleDetailsRoute private constructor(
  val sourceId: String,
  val sourceTitleKey: String,
  private val presentationToken: String,
) : HakusanRoute {
  internal constructor(
    sourceId: String,
    sourceTitleKey: String,
  ) : this(
    sourceId = sourceId,
    sourceTitleKey = sourceTitleKey,
    presentationToken = newPresentationToken(),
  )

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

/** Opaque identity of one retained presentation entry. */
internal class PresentationEntryId private constructor(
  private val destination: PrimaryDestination,
  private val route: HakusanRoute,
) {
  override fun equals(other: Any?): Boolean =
    other is PresentationEntryId &&
      destination == other.destination &&
      route == other.route

  override fun hashCode(): Int = 31 * destination.hashCode() + route.hashCode()

  companion object {
    fun create(
      destination: PrimaryDestination,
      route: HakusanRoute,
    ): PresentationEntryId = PresentationEntryId(destination, route)
  }
}

internal sealed class NavigationEntryHandle private constructor(
  private val destination: PrimaryDestination,
  route: HakusanRoute,
) {
  abstract val route: HakusanRoute

  val presentationId: PresentationEntryId =
    PresentationEntryId.create(destination, route)

  fun matches(
    destination: PrimaryDestination,
    route: NavKey?,
  ): Boolean =
    this.destination == destination && this.route == route

  class SourceBrowse internal constructor(
    destination: PrimaryDestination,
    override val route: SourceBrowseRoute,
  ) : NavigationEntryHandle(destination, route)

  class TitleDetails internal constructor(
    destination: PrimaryDestination,
    override val route: TitleDetailsRoute,
  ) : NavigationEntryHandle(destination, route)
}

internal sealed interface CurrentPopResult {
  data object AtRoot : CurrentPopResult

  data class Popped(
    val entry: NavigationEntryHandle,
  ) : CurrentPopResult
}

internal sealed interface ExpectedPopResult {
  data object Rejected : ExpectedPopResult

  data class Popped(
    val entry: NavigationEntryHandle,
  ) : ExpectedPopResult
}

internal class NavigationState(
  selectedDestination: MutableState<PrimaryDestination>,
  private val libraryBackStack: NavBackStack<NavKey>,
  private val catalogBackStack: NavBackStack<NavKey>,
) {
  var selectedDestination by selectedDestination
    private set

  val currentBackStack: NavBackStack<NavKey>
    get() = backStack(selectedDestination)

  val currentRoute: HakusanRoute
    get() = currentBackStack.last().toHakusanRoute()

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

  fun entry(
    destination: PrimaryDestination,
    route: SourceBrowseRoute,
  ): NavigationEntryHandle.SourceBrowse =
    NavigationEntryHandle.SourceBrowse(destination, route)

  fun entry(
    destination: PrimaryDestination,
    route: TitleDetailsRoute,
  ): NavigationEntryHandle.TitleDetails =
    NavigationEntryHandle.TitleDetails(destination, route)

  fun openCatalogSource(sourceId: ScreenSourceId) {
    if (
      selectedDestination == PrimaryDestination.CATALOG &&
      catalogBackStack.lastOrNull() == CatalogRoute
    ) {
      catalogBackStack.add(SourceBrowseRoute(sourceId.value))
    }
  }

  fun openLibraryTitle(titleKey: ScreenTitleKey) {
    if (
      selectedDestination == PrimaryDestination.LIBRARY &&
      libraryBackStack.lastOrNull() == LibraryRoute
    ) {
      libraryBackStack.add(
        TitleDetailsRoute(
          sourceId = titleKey.sourceId.value,
          sourceTitleKey = titleKey.sourceTitleKey,
        ),
      )
    }
  }

  fun openCatalogTitle(
    owner: NavigationEntryHandle.SourceBrowse,
    titleKey: ScreenTitleKey,
  ) {
    if (
      selectedDestination != PrimaryDestination.CATALOG ||
      !owner.matches(
        destination = PrimaryDestination.CATALOG,
        route = catalogBackStack.lastOrNull(),
      )
    ) {
      return
    }
    val browseRoute = owner.route
    require(titleKey.sourceId.value == browseRoute.sourceId) {
      "A Catalog title must belong to the open source."
    }
    catalogBackStack.add(
      TitleDetailsRoute(
        sourceId = titleKey.sourceId.value,
        sourceTitleKey = titleKey.sourceTitleKey,
      ),
    )
  }

  fun popCurrent(): CurrentPopResult {
    val destination = selectedDestination
    val backStack = currentBackStack
    if (backStack.size <= 1) {
      return CurrentPopResult.AtRoot
    }
    val entry = when (val route = backStack.last().toHakusanRoute()) {
      is SourceBrowseRoute -> entry(destination, route)
      is TitleDetailsRoute -> entry(destination, route)
      CatalogRoute,
      LibraryRoute,
      -> error("A root route cannot be nested in a back stack.")
    }
    backStack.removeAt(backStack.lastIndex)
    return CurrentPopResult.Popped(entry)
  }

  fun popExpected(
    expected: NavigationEntryHandle,
  ): ExpectedPopResult {
    val destination = selectedDestination
    val backStack = currentBackStack
    if (
      backStack.size <= 1 ||
      !expected.matches(destination, backStack.lastOrNull())
    ) {
      return ExpectedPopResult.Rejected
    }
    backStack.removeAt(backStack.lastIndex)
    return ExpectedPopResult.Popped(expected)
  }
}

@Composable
internal fun rememberNavigationState(
  initialDestination: PrimaryDestination = PrimaryDestination.LIBRARY,
): NavigationState {
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
    NavigationState(
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

/** Creates saveable identity for one nested push, not a domain identifier. */
private fun newPresentationToken(): String = UUID.randomUUID().toString()

private fun NavKey.toHakusanRoute(): HakusanRoute =
  checkNotNull(this as? HakusanRoute) {
    "Unknown navigation route: $this"
  }
