package app.hakusan.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The browsing island: the primary destinations, Library and then Catalog, as
 * tabs of one selectable group.
 *
 * Each destination always shows its icon and keeps its accessible name. Only
 * the [selected] destination also shows its label. The island offers no action
 * that is not materialized yet.
 */
@Composable
internal fun BrowsingIsland(
  selected: Destination,
  onSelect: (Destination) -> Unit,
  modifier: Modifier = Modifier,
) {
  HorizontalFloatingToolbar(
    expanded = true,
    modifier = modifier.selectableGroup(),
  ) {
    DestinationItem(
      selected = selected == Destination.Library,
      label = stringResource(R.string.destination_library),
      icon = painterResource(R.drawable.ic_library),
      onClick = { onSelect(Destination.Library) },
    )

    DestinationItem(
      selected = selected == Destination.Catalog,
      label = stringResource(R.string.destination_catalog),
      icon = painterResource(R.drawable.ic_catalog),
      onClick = { onSelect(Destination.Catalog) },
    )
  }
}

@Composable
private fun DestinationItem(
  selected: Boolean,
  label: String,
  icon: Painter,
  onClick: () -> Unit,
) {
  val shape = MaterialTheme.shapes.extraLarge

  val colors = MaterialTheme.colorScheme

  Surface(
    modifier =
      Modifier.minimumInteractiveComponentSize()
        .clip(shape)
        .selectable(selected = selected, onClick = onClick, role = Role.Tab)
        .semantics { contentDescription = label },
    shape = shape,
    color =
      if (selected) colors.secondaryContainer else colors.surfaceContainer,
    contentColor =
      if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
  ) {
    Row(
      modifier =
        Modifier.animateContentSize()
          .padding(horizontal = 14.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(painter = icon, contentDescription = null)

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
