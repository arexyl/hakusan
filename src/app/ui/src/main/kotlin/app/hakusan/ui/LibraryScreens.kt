package app.hakusan.ui

import app.hakusan.sdk.LibraryResumeState
import app.hakusan.sdk.LibraryScreen
import app.hakusan.sdk.LibraryShelfItem
import app.hakusan.sdk.LibraryTitleItem
import app.hakusan.sdk.ScreenTitleKey
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun LibraryDestination(
  libraryPresentationModel: () -> LibraryPresentationModel,
  onTitleSelected: (ScreenTitleKey) -> Unit,
  contentBottomPadding: Dp,
  modifier: Modifier = Modifier,
) {
  val model = remember { libraryPresentationModel() }
  LibraryContent(
    state = model.libraryState,
    onTitleSelected = onTitleSelected,
    contentBottomPadding = contentBottomPadding,
    modifier = modifier,
  )
}

@Composable
private fun LibraryContent(
  state: LibraryLoadState,
  onTitleSelected: (ScreenTitleKey) -> Unit,
  contentBottomPadding: Dp,
  modifier: Modifier = Modifier,
) {
  ScreenFrame(
    title = stringResource(R.string.destination_library),
    modifier = modifier,
  ) {
    when (state) {
      LibraryLoadState.Loading -> LoadingContent(
        message = stringResource(R.string.library_loading),
        contentBottomPadding = contentBottomPadding,
      )

      is LibraryLoadState.Loaded -> LibrarySnapshotContent(
        screen = state.screen,
        onTitleSelected = onTitleSelected,
        contentBottomPadding = contentBottomPadding,
      )
    }
  }
}

@Composable
private fun LibrarySnapshotContent(
  screen: LibraryScreen,
  onTitleSelected: (ScreenTitleKey) -> Unit,
  contentBottomPadding: Dp,
) {
  if (screen.shelves.isEmpty()) {
    EmptyContent(
      title = stringResource(R.string.library_empty_title),
      body = stringResource(R.string.library_empty_body),
      contentBottomPadding = contentBottomPadding,
    )
    return
  }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = screenContentPadding(contentBottomPadding),
    verticalArrangement = Arrangement.spacedBy(24.dp),
  ) {
    items(
      items = screen.shelves,
      key = { shelf -> shelf.id.value.toString() },
    ) { shelf ->
      LibraryShelf(
        shelf = shelf,
        screen = screen,
        onTitleSelected = onTitleSelected,
      )
    }
  }
}

@Composable
private fun LibraryShelf(
  shelf: LibraryShelfItem,
  screen: LibraryScreen,
  onTitleSelected: (ScreenTitleKey) -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = displayName(
          value = shelf.name,
          fallback = R.string.library_shelf_name_fallback,
        ),
        modifier = Modifier
          .weight(1f)
          .semantics { heading() },
        style = MaterialTheme.typography.titleLarge,
      )
      Text(
        text = pluralStringResource(
          R.plurals.library_title_count,
          shelf.titleCount,
          shelf.titleCount,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
      )
    }

    if (shelf.titleIds.isEmpty()) {
      Text(
        text = stringResource(R.string.library_shelf_empty),
        modifier = Modifier.padding(vertical = 16.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyLarge,
      )
    } else {
      LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        items(
          items = shelf.titleIds,
          key = { titleId -> titleId.value.toString() },
        ) { titleId ->
          val title = screen.titlesById.getValue(titleId)
          LibraryTitleCard(
            title = title,
            onClick = { onTitleSelected(title.key) },
          )
        }
      }
    }
  }
}

@Composable
private fun LibraryTitleCard(
  title: LibraryTitleItem,
  onClick: () -> Unit,
) {
  val progress = title.progress
  Surface(
    onClick = onClick,
    modifier = Modifier
      .widthIn(min = 220.dp, max = 320.dp)
      .heightIn(min = 120.dp)
      .semantics(mergeDescendants = true) {},
    shape = MaterialTheme.shapes.large,
    color = MaterialTheme.colorScheme.surfaceContainer,
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        text = displayName(
          value = title.displayName,
          fallback = R.string.title_name_fallback,
        ),
        style = MaterialTheme.typography.titleMedium,
      )
      Text(
        text = pluralStringResource(
          R.plurals.library_read_progress,
          progress.chapterCount,
          progress.readChapterCount,
          progress.chapterCount,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
      )
      Text(
        text = progress.resumeState.message(),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
      )
    }
  }
}

@Composable
private fun LibraryResumeState.message(): String = when (this) {
  LibraryResumeState.NONE ->
    stringResource(R.string.library_resume_none)

  LibraryResumeState.AVAILABLE ->
    stringResource(R.string.library_resume_available)

  LibraryResumeState.TEMPORARILY_UNAVAILABLE ->
    stringResource(R.string.library_resume_temporarily_unavailable)
}
