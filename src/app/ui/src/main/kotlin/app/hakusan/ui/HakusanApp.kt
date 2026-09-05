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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay

@Composable
fun HakusanApp(
  onExit: () -> Unit,
  modifier: Modifier = Modifier,
) {
  HakusanTheme {
    HakusanShell(
      navigationState = rememberHakusanNavigationState(),
      onExit = onExit,
      modifier = modifier,
    )
  }
}

@Composable
internal fun HakusanShell(
  navigationState: HakusanNavigationState,
  onExit: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val libraryLabel = stringResource(R.string.destination_library)
  val catalogLabel = stringResource(R.string.destination_catalog)
  val librarySaveableStateDecorator =
    rememberSaveableStateHolderNavEntryDecorator<NavKey>()
  val catalogSaveableStateDecorator =
    rememberSaveableStateHolderNavEntryDecorator<NavKey>()
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
        onBack = {
          if (!navigationState.pop()) {
            onExit()
          }
        },
        entryDecorators = remember(
          destination,
          librarySaveableStateDecorator,
          catalogSaveableStateDecorator,
        ) {
          listOf(
            when (destination) {
              PrimaryDestination.LIBRARY ->
                librarySaveableStateDecorator

              PrimaryDestination.CATALOG ->
                catalogSaveableStateDecorator
            },
          )
        },
        entryProvider = { route ->
          when (route) {
            LibraryRoute -> NavEntry(route) {
              DestinationLanding(libraryLabel)
            }

            CatalogRoute -> NavEntry(route) {
              DestinationLanding(catalogLabel)
            }

            else -> error("Unknown Hakusan route: $route")
          }
        },
      )
    }

    BrowsingIsland(
      selectedDestination = navigationState.selectedDestination,
      onDestinationSelected = navigationState::select,
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .windowInsetsPadding(
          WindowInsets.safeDrawing.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
          ),
        )
        .padding(16.dp),
    )
  }
}

@Composable
private fun DestinationLanding(label: String) {
  Surface(
    modifier = Modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.background,
    contentColor = MaterialTheme.colorScheme.onBackground,
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .windowInsetsPadding(
          WindowInsets.safeDrawing.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
          ),
        )
        .padding(horizontal = 24.dp, vertical = 32.dp)
        .padding(bottom = 88.dp),
      contentAlignment = Alignment.TopStart,
    ) {
      Text(
        text = label,
        modifier = Modifier.semantics {
          heading()
          paneTitle = label
        },
        style = MaterialTheme.typography.headlineLarge,
      )
    }
  }
}

@Composable
private fun BrowsingIsland(
  selectedDestination: PrimaryDestination,
  onDestinationSelected: (PrimaryDestination) -> Unit,
  modifier: Modifier = Modifier,
) {
  HorizontalFloatingToolbar(
    expanded = true,
    modifier = modifier.selectableGroup(),
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
