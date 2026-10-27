package app.hakusan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hakusan.sdk.LibraryResumeState
import app.hakusan.sdk.LibraryScreen
import app.hakusan.sdk.LibraryShelfItem
import app.hakusan.sdk.LibraryTitleItem
import app.hakusan.sdk.ScreenShelfId
import app.hakusan.sdk.ScreenTitleId

/**
 * The Library destination: the committed Library of [model].
 *
 * It collects the Library only while the screen is visible.
 */
@Composable
internal fun LibraryDestination(
  model: LibraryModel,
  contentPadding: PaddingValues,
  modifier: Modifier = Modifier,
) {
  val state by model.library.collectAsStateWithLifecycle()

  LibraryContent(
    state = state,
    contentPadding = contentPadding,
    carouselPosition = model::carouselPosition,
    onCarouselLeave = model::keepCarouselPosition,
    modifier = modifier,
  )
}

/**
 * The Library screen in [state].
 *
 * Shelves and the titles of each shelf appear in the order of the screen. A
 * shelf counts its own titles. A Library without shelves and an empty shelf are
 * distinct states. The primary list saves its position with the entry state.
 * Each carousel starts at [carouselPosition] when it enters composition and
 * reports its position to [onCarouselLeave] when it leaves, so no shelf key
 * enters saved state.
 */
@Composable
internal fun LibraryContent(
  state: LibraryState,
  contentPadding: PaddingValues,
  carouselPosition: (ScreenShelfId) -> CarouselPosition,
  onCarouselLeave: (ScreenShelfId, CarouselPosition) -> Unit,
  modifier: Modifier = Modifier,
) {
  val listState = rememberLazyListState()

  ScreenFrame(
    title = stringResource(R.string.destination_library),
    modifier = modifier,
  ) {
    when (state) {
      LibraryState.Loading ->
        LoadingContent(
          message = stringResource(R.string.library_loading),
          contentPadding = contentPadding,
        )

      is LibraryState.Loaded ->
        if (state.screen.shelves.isEmpty()) {
          EmptyContent(
            title = stringResource(R.string.library_empty_title),
            body = stringResource(R.string.library_empty_body),
            contentPadding = contentPadding,
          )
        } else {
          LibraryShelves(
            screen = state.screen,
            listState = listState,
            contentPadding = contentPadding,
            carouselPosition = carouselPosition,
            onCarouselLeave = onCarouselLeave,
          )
        }
    }
  }
}

@Composable
private fun LibraryShelves(
  screen: LibraryScreen,
  listState: LazyListState,
  contentPadding: PaddingValues,
  carouselPosition: (ScreenShelfId) -> CarouselPosition,
  onCarouselLeave: (ScreenShelfId, CarouselPosition) -> Unit,
) {
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    state = listState,
    contentPadding = contentPadding,
    verticalArrangement = Arrangement.spacedBy(24.dp),
  ) {
    items(screen.shelves, key = { shelf -> shelf.id.value }) { shelf ->
      LibraryShelf(
        shelf = shelf,
        titles = screen.titlesById,
        carouselPosition = carouselPosition,
        onCarouselLeave = onCarouselLeave,
      )
    }
  }
}

@Composable
private fun LibraryShelf(
  shelf: LibraryShelfItem,
  titles: Map<ScreenTitleId, LibraryTitleItem>,
  carouselPosition: (ScreenShelfId) -> CarouselPosition,
  onCarouselLeave: (ScreenShelfId, CarouselPosition) -> Unit,
) {
  val nameFallback = stringResource(R.string.library_shelf_name_fallback)

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
        text = displayName(shelf.name, nameFallback),
        modifier = Modifier.weight(1f).semantics { heading() },
        style = MaterialTheme.typography.titleLarge,
      )

      Text(
        text =
          pluralStringResource(
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
      ShelfCarousel(shelf, titles, carouselPosition, onCarouselLeave)
    }
  }
}

@Composable
private fun ShelfCarousel(
  shelf: LibraryShelfItem,
  titles: Map<ScreenTitleId, LibraryTitleItem>,
  carouselPosition: (ScreenShelfId) -> CarouselPosition,
  onCarouselLeave: (ScreenShelfId, CarouselPosition) -> Unit,
) {
  val state = rememberCarouselState(shelf.id, carouselPosition, onCarouselLeave)

  LazyRow(
    state = state,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    items(shelf.titleIds, key = { titleId -> titleId.value }) { titleId ->
      LibraryTitleCard(titles.getValue(titleId))
    }
  }
}

/**
 * Remembers the scroll state of the carousel of [shelf] in memory only.
 *
 * It starts at the kept position and reports the position when the carousel
 * leaves composition: on a destination switch, when the shelf scrolls away, and
 * on recreation.
 */
@Composable
private fun rememberCarouselState(
  shelf: ScreenShelfId,
  carouselPosition: (ScreenShelfId) -> CarouselPosition,
  onCarouselLeave: (ScreenShelfId, CarouselPosition) -> Unit,
): LazyListState {
  val state =
    remember(shelf) {
      val position = carouselPosition(shelf)

      LazyListState(position.index, position.offset)
    }

  val onLeave by rememberUpdatedState(onCarouselLeave)

  DisposableEffect(shelf, state) {
    onDispose {
      val position =
        CarouselPosition(
          state.firstVisibleItemIndex,
          state.firstVisibleItemScrollOffset,
        )

      onLeave(shelf, position)
    }
  }

  return state
}

/**
 * The text card of one Library title: its name, its source, and its reading
 * progress. It opens nothing until title details exist.
 */
@Composable
private fun LibraryTitleCard(title: LibraryTitleItem) {
  val progress = title.progress

  val nameFallback = stringResource(R.string.title_name_fallback)

  val sourceFallback = stringResource(R.string.library_source_fallback)

  Surface(
    modifier =
      Modifier.widthIn(min = 220.dp, max = 320.dp)
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
        text = displayName(title.displayName, nameFallback),
        style = MaterialTheme.typography.titleMedium,
      )

      Text(
        text = displayName(title.sourceDisplayName, sourceFallback),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
      )

      Text(
        text =
          pluralStringResource(
            R.plurals.library_read_progress,
            progress.chapterCount,
            progress.readChapterCount,
            progress.chapterCount,
          ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
      )

      Text(
        text = resumeMessage(progress.resumeState),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
      )
    }
  }
}

@Composable
private fun resumeMessage(state: LibraryResumeState): String =
  when (state) {
    LibraryResumeState.NONE -> stringResource(R.string.library_resume_none)

    LibraryResumeState.AVAILABLE ->
      stringResource(R.string.library_resume_available)

    LibraryResumeState.TEMPORARILY_UNAVAILABLE ->
      stringResource(R.string.library_resume_temporarily_unavailable)
  }
