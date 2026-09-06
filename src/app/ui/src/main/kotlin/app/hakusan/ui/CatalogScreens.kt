package app.hakusan.ui

import app.hakusan.sdk.BrowseScreen
import app.hakusan.sdk.BrowseScreenFailure
import app.hakusan.sdk.CatalogScreen
import app.hakusan.sdk.CatalogSourceItem
import app.hakusan.sdk.DetailsChapterItem
import app.hakusan.sdk.DetailsScreenFailure
import app.hakusan.sdk.ScreenSourceId
import app.hakusan.sdk.ScreenTitleKey
import app.hakusan.sdk.TitleDetailsScreen
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
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
internal fun TitleDetailsDestination(
  route: TitleDetailsRoute,
  catalogPresentationModel: () -> CatalogPresentationModel,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val model = remember { catalogPresentationModel() }
  val owner = remember(route, model) {
    model.details(route)
  }
  val state = owner.state
  LaunchedEffect(model, route) {
    model.ensureDetails(route)
  }

  val contentBottomPadding = WindowInsets.safeDrawing
    .only(WindowInsetsSides.Bottom)
    .asPaddingValues()
    .calculateBottomPadding()

  TitleDetailsContent(
    state = state,
    onRetry = { model.retryDetails(route) },
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
private fun TitleDetailsContent(
  state: ScreenLoadState<TitleDetailsScreen, DetailsScreenFailure>,
  onRetry: () -> Unit,
  onBack: () -> Unit,
  contentBottomPadding: Dp,
  modifier: Modifier = Modifier,
) {
  val title = when (state) {
    is ScreenLoadState.Loaded -> displayName(
      value = state.content.displayName,
      fallback = R.string.title_name_fallback,
    )
    else -> stringResource(R.string.details_title_fallback)
  }
  ScreenFrame(
    title = title,
    onBack = onBack,
    modifier = modifier,
  ) {
    when (state) {
      ScreenLoadState.Loading -> LoadingContent(
        message = stringResource(R.string.details_loading),
        contentBottomPadding = contentBottomPadding,
      )

      ScreenLoadState.Superseded -> SupersededContent(
        onRetry = onRetry,
        contentBottomPadding = contentBottomPadding,
      )

      is ScreenLoadState.Failed -> FailureContent(
        title = stringResource(R.string.details_failure_title),
        body = state.failure.message(),
        onRetry = onRetry,
        contentBottomPadding = contentBottomPadding,
      )

      is ScreenLoadState.Loaded -> DetailsBody(
        screen = state.content,
        contentBottomPadding = contentBottomPadding,
      )
    }
  }
}

@Composable
private fun DetailsBody(
  screen: TitleDetailsScreen,
  contentBottomPadding: Dp,
  modifier: Modifier = Modifier,
) {
  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = screenContentPadding(contentBottomPadding),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      Text(
        text = displayName(
          value = screen.sourceDisplayName,
          fallback = R.string.source_name_fallback,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
      )
    }
    screen.description?.let { description ->
      item {
        Text(
          text = description,
          style = MaterialTheme.typography.bodyLarge,
        )
      }
    }
    item {
      SectionHeading(
        text = stringResource(R.string.details_chapters),
      )
    }
    if (screen.chapters.isEmpty()) {
      item {
        EmptyChapterContent()
      }
    } else {
      items(
        items = screen.chapters,
        key = { chapter -> chapter.id.value.toString() },
      ) { chapter ->
        ChapterRow(chapter)
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
private fun ChapterRow(chapter: DetailsChapterItem) {
  val readLabel = stringResource(R.string.details_chapter_read)
  val chapterName = displayName(
    value = chapter.displayName,
    fallback = R.string.chapter_name_fallback,
  )
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.large,
    color = MaterialTheme.colorScheme.surfaceContainerLow,
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 56.dp)
        .semantics(mergeDescendants = true) {
          contentDescription = chapterName
          if (chapter.isRead) {
            stateDescription = readLabel
          }
        }
        .padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = chapterName,
        modifier = Modifier
          .weight(1f)
          .clearAndSetSemantics {},
        style = MaterialTheme.typography.bodyLarge,
      )
      if (chapter.isRead) {
        Spacer(Modifier.width(12.dp))
        Text(
          text = readLabel,
          modifier = Modifier.clearAndSetSemantics {},
          color = MaterialTheme.colorScheme.primary,
          style = MaterialTheme.typography.labelLarge,
        )
      }
    }
  }
}

@Composable
private fun ScreenFrame(
  title: String,
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  content: @Composable BoxScope.() -> Unit,
) {
  Surface(
    modifier = modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.background,
    contentColor = MaterialTheme.colorScheme.onBackground,
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .windowInsetsPadding(
          WindowInsets.safeDrawing.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
          ),
        ),
    ) {
      ScreenHeader(
        title = title,
        onBack = onBack,
      )
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f),
        content = content,
      )
    }
  }
}

@Composable
private fun ScreenHeader(
  title: String,
  onBack: (() -> Unit)?,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 72.dp)
      .padding(horizontal = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (onBack != null) {
      TextButton(
        onClick = onBack,
        modifier = Modifier.heightIn(min = 48.dp),
      ) {
        Text(stringResource(R.string.navigate_back))
      }
      Spacer(Modifier.width(4.dp))
    } else {
      Spacer(Modifier.width(12.dp))
    }
    Text(
      text = title,
      modifier = Modifier
        .weight(1f)
        .semantics {
          heading()
          paneTitle = title
        },
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      style = MaterialTheme.typography.headlineMedium,
    )
  }
}

@Composable
private fun SectionHeading(text: String) {
  Text(
    text = text,
    modifier = Modifier.semantics { heading() },
    style = MaterialTheme.typography.titleLarge,
  )
}

@Composable
private fun LoadingContent(
  message: String,
  contentBottomPadding: Dp,
  modifier: Modifier = Modifier,
) {
  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = screenContentPadding(contentBottomPadding),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    item {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(Modifier.size(40.dp))
        Text(
          text = message,
          modifier = Modifier.padding(top = 16.dp),
          style = MaterialTheme.typography.bodyLarge,
        )
      }
    }
  }
}

@Composable
private fun FailureContent(
  title: String,
  body: String,
  onRetry: () -> Unit,
  contentBottomPadding: Dp,
  modifier: Modifier = Modifier,
) {
  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = screenContentPadding(contentBottomPadding),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    item {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
          text = title,
          modifier = Modifier.semantics { heading() },
          style = MaterialTheme.typography.titleLarge,
        )
        Text(
          text = body,
          modifier = Modifier.padding(top = 8.dp),
          style = MaterialTheme.typography.bodyLarge,
        )
        Button(
          onClick = onRetry,
          modifier = Modifier
            .padding(top = 20.dp)
            .heightIn(min = 48.dp),
        ) {
          Text(stringResource(R.string.retry))
        }
      }
    }
  }
}

@Composable
private fun SupersededContent(
  onRetry: () -> Unit,
  contentBottomPadding: Dp,
  modifier: Modifier = Modifier,
) {
  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = screenContentPadding(contentBottomPadding),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    item {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
          text = stringResource(R.string.load_superseded_title),
          modifier = Modifier.semantics { heading() },
          style = MaterialTheme.typography.titleLarge,
        )
        Text(
          text = stringResource(R.string.load_superseded_body),
          modifier = Modifier.padding(top = 8.dp),
          style = MaterialTheme.typography.bodyLarge,
        )
        Button(
          onClick = onRetry,
          modifier = Modifier
            .padding(top = 20.dp)
            .heightIn(min = 48.dp),
        ) {
          Text(stringResource(R.string.retry))
        }
      }
    }
  }
}

@Composable
private fun EmptyContent(
  title: String,
  body: String,
  contentBottomPadding: Dp,
  modifier: Modifier = Modifier,
) {
  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = screenContentPadding(contentBottomPadding),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    item {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
          text = title,
          modifier = Modifier.semantics { heading() },
          style = MaterialTheme.typography.titleLarge,
        )
        Text(
          text = body,
          modifier = Modifier.padding(top = 8.dp),
          style = MaterialTheme.typography.bodyLarge,
        )
      }
    }
  }
}

@Composable
private fun EmptyChapterContent() {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
      text = stringResource(R.string.details_empty_chapters_title),
      modifier = Modifier.semantics { heading() },
      style = MaterialTheme.typography.titleMedium,
    )
    Text(
      text = stringResource(R.string.details_empty_chapters_body),
      modifier = Modifier.padding(top = 8.dp),
      style = MaterialTheme.typography.bodyMedium,
    )
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

@Composable
private fun DetailsScreenFailure.message(): String = when (this) {
  DetailsScreenFailure.SourceNotFound ->
    stringResource(R.string.details_failure_source_not_found)

  DetailsScreenFailure.DetailsUnavailable ->
    stringResource(R.string.details_failure_details_unavailable)

  DetailsScreenFailure.ChaptersUnavailable ->
    stringResource(R.string.details_failure_chapters_unavailable)

  DetailsScreenFailure.InvalidTitleObservation ->
    stringResource(R.string.details_failure_invalid_title)

  DetailsScreenFailure.InvalidChapterSnapshot ->
    stringResource(R.string.details_failure_invalid_chapters)

  DetailsScreenFailure.LocalTitleNotFound ->
    stringResource(R.string.details_failure_local_title_not_found)
}

private fun screenContentPadding(
  bottomContentPadding: Dp,
): PaddingValues = PaddingValues(
  start = 20.dp,
  top = 20.dp,
  end = 20.dp,
  bottom = 20.dp + bottomContentPadding,
)

@Composable
private fun displayName(
  value: String,
  fallback: Int,
): String = if (value.isBlank()) {
  stringResource(fallback)
} else {
  value
}
