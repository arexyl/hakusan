package app.hakusan.ui

import app.hakusan.sdk.DetailsChapterItem
import app.hakusan.sdk.DetailsScreenFailure
import app.hakusan.sdk.TitleDetailsScreen
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun TitleDetailsDestination(
  destination: PrimaryDestination,
  route: TitleDetailsRoute,
  browsingModel: () -> BrowsingViewModel,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val model = remember { browsingModel() }
  val ownerKey = remember(destination, route) {
    DetailsOwnerKey(destination, route)
  }
  val owner = remember(ownerKey, model) {
    model.details(ownerKey)
  }
  val state = owner.state
  LaunchedEffect(model, ownerKey) {
    model.ensureDetails(ownerKey)
  }

  val contentBottomPadding = WindowInsets.safeDrawing
    .only(WindowInsetsSides.Bottom)
    .asPaddingValues()
    .calculateBottomPadding()

  TitleDetailsContent(
    state = state,
    onRetry = { model.retryDetails(ownerKey) },
    onBack = onBack,
    contentBottomPadding = contentBottomPadding,
    modifier = modifier,
  )
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
