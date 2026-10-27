package app.hakusan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.hakusan.sdk.CatalogScreen

/**
 * The Catalog overview of [catalog].
 *
 * It lists the sources in the order of the Catalog, keyed by their exact source
 * identities, or the empty state when no source is available. Sources open
 * nothing until source browse exists. The primary list saves its position with
 * the entry state.
 */
@Composable
internal fun CatalogContent(
  catalog: CatalogScreen,
  contentPadding: PaddingValues,
  modifier: Modifier = Modifier,
) {
  val listState = rememberLazyListState()

  val nameFallback = stringResource(R.string.source_name_fallback)

  ScreenFrame(
    title = stringResource(R.string.destination_catalog),
    modifier = modifier,
  ) {
    if (catalog.sources.isEmpty()) {
      EmptyContent(
        title = stringResource(R.string.catalog_empty_title),
        body = stringResource(R.string.catalog_empty_body),
        contentPadding = contentPadding,
      )
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        item { SectionHeading(stringResource(R.string.catalog_sources)) }

        items(catalog.sources, key = { source -> source.id.value }) { source ->
          CatalogSourceRow(displayName(source.displayName, nameFallback))
        }
      }
    }
  }
}

@Composable
private fun CatalogSourceRow(name: String) {
  Surface(
    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
    shape = MaterialTheme.shapes.large,
    color = MaterialTheme.colorScheme.surfaceContainer,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(text = name, style = MaterialTheme.typography.titleMedium)
    }
  }
}
