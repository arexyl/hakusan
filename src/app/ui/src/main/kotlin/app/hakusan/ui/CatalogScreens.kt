package app.hakusan.ui

import app.hakusan.sdk.BrowseScreen
import app.hakusan.sdk.BrowseScreenFailure
import app.hakusan.sdk.CatalogScreen
import app.hakusan.sdk.CatalogSourceItem
import app.hakusan.sdk.ScreenSourceId
import app.hakusan.sdk.ScreenTitleKey
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun CatalogDestination(
  catalogPresentationModel: () -> CatalogPresentationModel,
  onSourceSelected: (ScreenSourceId) -> Unit,
  contentBottomPadding: Dp,
  modifier: Modifier = Modifier,
) {
  val model = remember { catalogPresentationModel() }
  CatalogContent(
    catalog = model.catalog,
    onSourceSelected = onSourceSelected,
    contentBottomPadding = contentBottomPadding,
    modifier = modifier,
  )
}

@Composable
internal fun SourceBrowseDestination(
  route: SourceBrowseRoute,
  catalogPresentationModel: () -> CatalogPresentationModel,
  onTitleSelected: (ScreenTitleKey) -> Unit,
  onBack: () -> Unit,
  contentBottomPadding: Dp,
  modifier: Modifier = Modifier,
) {
  val model = remember { catalogPresentationModel() }
  val owner = remember(route, model) {
    model.browse(route)
  }
  val state = owner.state
  val catalogSourceName = remember(model, route) {
    val sourceId = route.toScreenSourceId()
    model.catalog.sources
      .singleOrNull { it.id == sourceId }
      ?.displayName
  }
  LaunchedEffect(model, route) {
    model.ensureBrowse(route)
  }

  val sourceName = when (state) {
    is ScreenLoadState.Loaded -> state.content.source.displayName
    else -> catalogSourceName ?: stringResource(R.string.source_fallback)
  }
  SourceBrowseContent(
    sourceName = sourceName,
    state = state,
    onTitleSelected = onTitleSelected,
    onRetry = { model.retryBrowse(route) },
    onBack = onBack,
    contentBottomPadding = contentBottomPadding,
    modifier = modifier,
  )
}

@Composable
private fun CatalogContent(
  catalog: CatalogScreen,
  onSourceSelected: (ScreenSourceId) -> Unit,
  contentBottomPadding: Dp,
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
        contentBottomPadding = contentBottomPadding,
      )
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = screenContentPadding(contentBottomPadding),
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
          SourceRow(
            source = source,
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
  state: ScreenLoadState<BrowseScreen, BrowseScreenFailure>,
  onTitleSelected: (ScreenTitleKey) -> Unit,
  onRetry: () -> Unit,
  onBack: () -> Unit,
  contentBottomPadding: Dp,
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
      ScreenLoadState.Loading -> LoadingContent(
        message = stringResource(R.string.browse_loading),
        contentBottomPadding = contentBottomPadding,
      )

      ScreenLoadState.Superseded -> SupersededContent(
        onRetry = onRetry,
        contentBottomPadding = contentBottomPadding,
      )

      is ScreenLoadState.Failed -> FailureContent(
        title = stringResource(R.string.browse_failure_title),
        body = state.failure.message(),
        onRetry = onRetry,
        contentBottomPadding = contentBottomPadding,
      )

      is ScreenLoadState.Loaded -> {
        val titles = state.content.titles
        if (titles.isEmpty()) {
          EmptyContent(
            title = stringResource(R.string.browse_empty_title),
            body = stringResource(R.string.browse_empty_body),
            contentBottomPadding = contentBottomPadding,
          )
        } else {
          LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = screenContentPadding(contentBottomPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            items(
              items = titles,
              key = { title ->
                title.key.sourceId.value to title.key.sourceTitleKey
              },
            ) { title ->
              TitleRow(
                displayName = title.displayName,
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
private fun SourceRow(
  source: CatalogSourceItem,
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
          value = source.displayName,
          fallback = R.string.source_name_fallback,
        ),
        style = MaterialTheme.typography.titleMedium,
      )
    }
  }
}

@Composable
private fun TitleRow(
  displayName: String,
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
          fallback = R.string.title_name_fallback,
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
