package app.hakusan.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay

@Composable
fun HakusanApp(
  browsingModel: () -> BrowsingViewModel,
  libraryModel: () -> LibraryViewModel,
  onExit: () -> Unit,
  modifier: Modifier = Modifier,
) {
  HakusanTheme {
    AppShell(
      navigationState = rememberNavigationState(),
      browsingModel = browsingModel,
      libraryModel = libraryModel,
      onExit = onExit,
      modifier = modifier,
    )
  }
}

@Composable
internal fun AppShell(
  navigationState: NavigationState,
  browsingModel: () -> BrowsingViewModel,
  libraryModel: () -> LibraryViewModel,
  onExit: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val libraryStateDecorator =
    rememberSaveableStateHolderNavEntryDecorator<NavKey>()
  val catalogStateDecorator =
    rememberSaveableStateHolderNavEntryDecorator<NavKey>()
  var islandHeightPx by remember { mutableIntStateOf(0) }
  val islandHeight = with(LocalDensity.current) {
    islandHeightPx.toDp()
  }
  val safeBottom = WindowInsets.safeDrawing
    .only(WindowInsetsSides.Bottom)
    .asPaddingValues()
    .calculateBottomPadding()
  val contentBottomPadding =
    safeBottom +
      FloatingIslandEdgeSpacing +
      islandHeight
  val contentBottomPaddingState = rememberUpdatedState(
    contentBottomPadding,
  )
  val navigateBack = {
    val destination = navigationState.selectedDestination
    val removedRoute = navigationState.pop()
    if (removedRoute == null) {
      onExit()
    } else {
      browsingModel().discard(destination, removedRoute)
    }
  }
  val navigateRouteBack: (PrimaryDestination, NavKey) -> Unit =
    { destination, expectedRoute ->
      navigationState.pop(destination, expectedRoute)?.let { removedRoute ->
        browsingModel().discard(destination, removedRoute)
      }
    }
  BackHandler(
    enabled = navigationState.currentBackStack.size <= 1,
    onBack = onExit,
  )
  Box(
    modifier = modifier.fillMaxSize(),
  ) {
    Crossfade(
      targetState = navigationState.selectedDestination,
      modifier = Modifier.fillMaxSize(),
      label = "Primary destination",
    ) { destination ->
      NavDisplay(
        backStack = navigationState.backStack(destination),
        modifier = Modifier.fillMaxSize(),
        onBack = navigateBack,
        entryDecorators = remember(
          destination,
          libraryStateDecorator,
          catalogStateDecorator,
        ) {
          listOf(
            when (destination) {
              PrimaryDestination.LIBRARY ->
                libraryStateDecorator

              PrimaryDestination.CATALOG ->
                catalogStateDecorator
            },
          )
        },
        entryProvider = { route ->
          when (route) {
            LibraryRoute -> NavEntry(route) {
              LibraryDestination(
                libraryModel = libraryModel,
                onTitleSelected = navigationState::openLibraryTitle,
                contentBottomPadding = contentBottomPaddingState.value,
              )
            }

            CatalogRoute -> NavEntry(route) {
              CatalogDestination(
                browsingModel = browsingModel,
                onSourceSelected = navigationState::openCatalogSource,
                contentBottomPadding = contentBottomPaddingState.value,
              )
            }

            is SourceBrowseRoute -> NavEntry(route) {
              SourceBrowseDestination(
                route = route,
                browsingModel = browsingModel,
                onTitleSelected = navigationState::openCatalogTitle,
                onBack = {
                  navigateRouteBack(PrimaryDestination.CATALOG, route)
                },
                contentBottomPadding = contentBottomPaddingState.value,
              )
            }

            is TitleDetailsRoute -> NavEntry(route) {
              TitleDetailsDestination(
                destination = destination,
                route = route,
                browsingModel = browsingModel,
                libraryModel = libraryModel,
                onBack = { navigateRouteBack(destination, route) },
              )
            }

            else -> error("Unknown navigation route: $route")
          }
        },
      )
    }

    if (navigationState.showsBrowsingIsland) {
      BrowsingIsland(
        selectedDestination = navigationState.selectedDestination,
        onDestinationSelected = navigationState::select,
        onHeightChanged = { height ->
          islandHeightPx = height
        },
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .windowInsetsPadding(
            WindowInsets.safeDrawing.only(
              WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
            ),
          )
          .padding(FloatingIslandEdgeSpacing),
      )
    }
  }
}

@Composable
private fun BrowsingIsland(
  selectedDestination: PrimaryDestination,
  onDestinationSelected: (PrimaryDestination) -> Unit,
  onHeightChanged: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier,
    contentAlignment = Alignment.Center,
  ) {
    HorizontalFloatingToolbar(
      expanded = true,
      modifier = Modifier
        .selectableGroup()
        .onSizeChanged { size -> onHeightChanged(size.height) },
    ) {
      DestinationItem(
        selected = selectedDestination == PrimaryDestination.LIBRARY,
        label = stringResource(R.string.destination_library),
        icon = R.drawable.ic_library,
        onClick = { onDestinationSelected(PrimaryDestination.LIBRARY) },
      )
      DestinationItem(
        selected = selectedDestination == PrimaryDestination.CATALOG,
        label = stringResource(R.string.destination_catalog),
        icon = R.drawable.ic_catalog,
        onClick = { onDestinationSelected(PrimaryDestination.CATALOG) },
      )
    }
  }
}

@Composable
private fun DestinationItem(
  selected: Boolean,
  label: String,
  @DrawableRes icon: Int,
  onClick: () -> Unit,
) {
  val shape: Shape = MaterialTheme.shapes.extraLarge
  Surface(
    modifier = Modifier
      .heightIn(min = 48.dp)
      .clip(shape)
      .selectable(
        selected = selected,
        onClick = onClick,
        role = Role.Tab,
      )
      .semantics {
        contentDescription = label
      },
    shape = shape,
    color = if (selected) {
      MaterialTheme.colorScheme.secondaryContainer
    } else {
      MaterialTheme.colorScheme.surfaceContainer
    },
    contentColor = if (selected) {
      MaterialTheme.colorScheme.onSecondaryContainer
    } else {
      MaterialTheme.colorScheme.onSurfaceVariant
    },
  ) {
    Row(
      modifier = Modifier
        .animateContentSize()
        .padding(horizontal = 14.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        painter = painterResource(icon),
        contentDescription = null,
      )
      if (selected) {
        Text(
          text = label,
          modifier = Modifier.padding(start = 8.dp),
          style = MaterialTheme.typography.labelLarge,
        )
      }
    }
  }
}
