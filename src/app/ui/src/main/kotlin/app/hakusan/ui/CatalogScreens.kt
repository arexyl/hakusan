package app.hakusan.ui

import app.hakusan.sdk.BrowseScreenFailure
import app.hakusan.sdk.CatalogScreen
import app.hakusan.sdk.ScreenSourceId
import app.hakusan.sdk.ScreenTitleKey
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
internal fun CatalogDestination(
  model: CatalogViewModel,
  onSourceSelected: (ScreenSourceId) -> Unit,
  contentPadding: PaddingValues,
  modifier: Modifier = Modifier,
) {
  CatalogContent(
    catalog = model.catalog,
    onSourceSelected = onSourceSelected,
    contentPadding = contentPadding,
    modifier = modifier,
  )
}

@Composable
internal fun SourceBrowseDestination(
  entryId: PresentationEntryId,
  route: SourceBrowseRoute,
  model: CatalogViewModel,
  onTitleSelected: (ScreenTitleKey) -> Unit,
  onBack: () -> Unit,
  contentPadding: PaddingValues,
  modifier: Modifier = Modifier,
) {
  val sourceId = remember(route) {
    route.toScreenSourceId()
  }
  val stateHolder = remember(model, entryId, sourceId) {
    model.browseState(entryId, sourceId)
  }
  val state by stateHolder
  val catalogSourceName = remember(model, route) {
    model.catalog.sources
      .singleOrNull { it.id == sourceId }
      ?.displayName
  }
  LaunchedEffect(model, entryId) {
    model.ensureBrowse(entryId)
  }

  val sourceName = when (val current = state) {
    is SourceBrowseState.Content -> current.screen.source.displayName
    else -> catalogSourceName ?: stringResource(R.string.source_fallback)
  }
  SourceBrowseContent(
    sourceName = sourceName,
    state = state,
    onTitleSelected = onTitleSelected,
    onRetry = { model.retryBrowse(entryId) },
    onBack = onBack,
    contentPadding = contentPadding,
    modifier = modifier,
  )
}

@Composable
private fun CatalogContent(
  catalog: CatalogScreen,
  onSourceSelected: (ScreenSourceId) -> Unit,
  contentPadding: PaddingValues,
  modifier: Modifier = Modifier,
) {
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
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        item {
          SectionHeading(
            text = stringResource(R.string.catalog_sources),
          )
        }
        items(
          items = catalog.sources,
          key = { source -> source.id.value },
        ) { source ->
          CatalogListItem(
            displayName = source.displayName,
            fallback = R.string.source_name_fallback,
            onClick = { onSourceSelected(source.id) },
          )
        }
      }
    }
  }
}

@Composable
private fun SourceBrowseContent(
  sourceName: String,
  state: SourceBrowseState,
  onTitleSelected: (ScreenTitleKey) -> Unit,
  onRetry: () -> Unit,
  onBack: () -> Unit,
  contentPadding: PaddingValues,
  modifier: Modifier = Modifier,
) {
  ScreenFrame(
    title = displayName(
      value = sourceName,
      fallback = R.string.source_name_fallback,
    ),
    onBack = onBack,
    modifier = modifier,
  ) {
    when (state) {
      SourceBrowseState.Loading -> LoadingContent(
        message = stringResource(R.string.browse_loading),
        contentPadding = contentPadding,
      )

      is SourceBrowseState.Failed -> FailureContent(
        title = stringResource(R.string.browse_failure_title),
        body = state.failure.message(),
        onRetry = onRetry,
        contentPadding = contentPadding,
      )

      is SourceBrowseState.Content -> {
        val titles = state.screen.titles
        if (titles.isEmpty()) {
          EmptyContent(
            title = stringResource(R.string.browse_empty_title),
            body = stringResource(R.string.browse_empty_body),
            contentPadding = contentPadding,
          )
        } else {
          LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            items(
              items = titles,
              key = { title ->
                title.key.sourceId.value to title.key.sourceTitleKey
              },
            ) { title ->
              CatalogListItem(
                displayName = title.displayName,
                fallback = R.string.title_name_fallback,
                onClick = { onTitleSelected(title.key) },
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun CatalogListItem(
  displayName: String,
  @StringRes fallback: Int,
  onClick: () -> Unit,
) {
  Surface(
    onClick = onClick,
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 64.dp),
    shape = MaterialTheme.shapes.large,
    color = MaterialTheme.colorScheme.surfaceContainer,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = displayName(
          value = displayName,
          fallback = fallback,
        ),
        style = MaterialTheme.typography.titleMedium,
      )
    }
  }
}

@Composable
private fun BrowseScreenFailure.message(): String = when (this) {
  BrowseScreenFailure.SourceNotFound ->
    stringResource(R.string.browse_failure_source_not_found)

  BrowseScreenFailure.SourceUnavailable ->
    stringResource(R.string.browse_failure_source_unavailable)

  BrowseScreenFailure.InvalidObservation ->
    stringResource(R.string.browse_failure_invalid_observation)
}
