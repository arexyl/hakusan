package app.hakusan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.hakusan.sdk.DetailsChapterItem
import app.hakusan.sdk.DetailsScreenFailure
import app.hakusan.sdk.TitleDetailsScreen

@Composable
internal fun TitleDetailsDestination(
  entryId: PresentationEntryId,
  route: TitleDetailsRoute,
  model: TitleDetailsViewModel,
  onBack: () -> Unit,
  contentPadding: PaddingValues,
  modifier: Modifier = Modifier,
) {
  val titleKey = remember(route) {
    route.toScreenTitleKey()
  }
  val stateHolder = remember(model, entryId, titleKey) {
    model.state(entryId, titleKey)
  }
  val state by stateHolder
  LaunchedEffect(model, entryId) {
    model.ensureDetails(entryId)
  }

  TitleDetailsContent(
    state = state,
    onRetry = { model.retryDetails(entryId) },
    onBack = onBack,
    contentPadding = contentPadding,
    modifier = modifier,
  )
}

@Composable
private fun TitleDetailsContent(
  state: TitleDetailsEntryState,
  onRetry: () -> Unit,
  onBack: () -> Unit,
  contentPadding: PaddingValues,
  modifier: Modifier = Modifier,
) {
  val title = when (state) {
    is TitleDetailsEntryState.Content -> displayName(
      value = state.screen.displayName,
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
      TitleDetailsEntryState.Loading -> LoadingContent(
        message = stringResource(R.string.details_loading),
        contentPadding = contentPadding,
      )

      TitleDetailsEntryState.Superseded -> FailureContent(
        title = stringResource(R.string.load_superseded_title),
        body = stringResource(R.string.load_superseded_body),
        onRetry = onRetry,
        contentPadding = contentPadding,
      )

      is TitleDetailsEntryState.Failed -> FailureContent(
        title = stringResource(R.string.details_failure_title),
        body = state.failure.message(),
        onRetry = onRetry,
        contentPadding = contentPadding,
      )

      is TitleDetailsEntryState.Content -> DetailsBody(
        screen = state.screen,
        contentPadding = contentPadding,
      )
    }
  }
}

@Composable
private fun DetailsBody(
  screen: TitleDetailsScreen,
  contentPadding: PaddingValues,
  modifier: Modifier = Modifier,
) {
  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = contentPadding,
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
}
