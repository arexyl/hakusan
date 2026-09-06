package app.hakusan.ui

import app.hakusan.sdk.ContinueState
import app.hakusan.sdk.ContinueUnavailableReason
import app.hakusan.sdk.DetailsChapterItem
import app.hakusan.sdk.DetailsScreenFailure
import app.hakusan.sdk.TitleDetailsScreen
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

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
private fun ActionMessage(message: String) {
  Text(
    text = message,
    modifier = Modifier.semantics {
      liveRegion = LiveRegionMode.Polite
    },
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.bodyMedium,
  )
}

@Composable
internal fun TitleActionsOverlay(
  screen: TitleDetailsScreen,
  membership: LibraryMembership,
  addState: LibraryAddState,
  continueActionState: ContinueActionState,
  onLike: () -> Unit,
  onContinue: () -> Unit,
  onRetryDetails: () -> Unit,
) {
  val addMessage = when (membership) {
    LibraryMembership.Loading -> stringResource(R.string.library_loading)
    LibraryMembership.NotMember,
    LibraryMembership.Member,
    -> addState.message()
  }
  val selectedContinueMessage = continueMessage(
    screen = screen,
    actionState = continueActionState,
  )
  val showDetailsRetry = needsDetailsRetry(screen, continueActionState)
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    if (addMessage != null || selectedContinueMessage != null) {
      Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
      ) {
        Column(
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          addMessage?.let { message ->
            ActionMessage(message)
          }
          selectedContinueMessage?.let { message ->
            ActionMessage(message)
          }
          if (showDetailsRetry) {
            TextButton(
              onClick = onRetryDetails,
            ) {
              Text(stringResource(R.string.retry_title_details))
            }
          }
        }
      }
    }
    HorizontalFloatingToolbar(expanded = true) {
      LikeAction(
        membership = membership,
        addState = addState,
        onClick = onLike,
      )
      ContinueAction(
        screenState = screen.continueState,
        actionState = continueActionState,
        onClick = onContinue,
      )
    }
  }
}

@Composable
private fun LikeAction(
  membership: LibraryMembership,
  addState: LibraryAddState,
  onClick: () -> Unit,
) {
  val label = stringResource(R.string.like)
  if (membership == LibraryMembership.Member) {
    val membershipState = stringResource(R.string.library_membership_selected)
    Surface(
      modifier = Modifier
        .heightIn(min = 48.dp)
        .semantics(mergeDescendants = true) {
          contentDescription = label
          selected = true
          stateDescription = membershipState
        },
      shape = MaterialTheme.shapes.extraLarge,
      color = MaterialTheme.colorScheme.primaryContainer,
      contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
      Box(
        modifier = Modifier.padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
      ) {
        Text(label)
      }
    }
    return
  }

  val adding = addState == LibraryAddState.Adding
  val addDescription = stringResource(R.string.add_to_library)
  val addStateDescription = if (membership == LibraryMembership.Loading) {
    stringResource(R.string.library_loading)
  } else {
    addState.message()
  }
  Button(
    onClick = onClick,
    enabled = membership == LibraryMembership.NotMember && !adding,
    modifier = Modifier.semantics {
      contentDescription = addDescription
      addStateDescription?.let { stateDescription = it }
    },
  ) {
    Text(label)
  }
}

@Composable
private fun ContinueAction(
  screenState: ContinueState,
  actionState: ContinueActionState,
  onClick: () -> Unit,
) {
  val enabled = when (actionState) {
    ContinueActionState.Idle,
    is ContinueActionState.Selected
    -> screenState is ContinueState.Ready

    ContinueActionState.Selecting,
    is ContinueActionState.Unavailable,
    ContinueActionState.TitleNotFound
    -> false
  }
  val stateDescription = when (actionState) {
    ContinueActionState.Idle -> when (screenState) {
      is ContinueState.Ready -> null
      is ContinueState.Unavailable -> screenState.reason.message()
    }

    ContinueActionState.Selecting ->
      stringResource(R.string.continue_selecting)

    is ContinueActionState.Selected ->
      stringResource(R.string.continue_target_selected_state)

    is ContinueActionState.Unavailable -> actionState.reason.message()

    ContinueActionState.TitleNotFound ->
      stringResource(R.string.continue_title_not_found)
  }
  Button(
    onClick = onClick,
    enabled = enabled,
    modifier = Modifier.semantics {
      stateDescription?.let { this.stateDescription = it }
    },
  ) {
    Text(stringResource(R.string.continue_action))
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
private fun LibraryAddState.message(): String? = when (this) {
  LibraryAddState.Idle -> null
  LibraryAddState.Adding -> stringResource(R.string.library_add_adding)
  LibraryAddState.Committed ->
    stringResource(R.string.library_add_committed)

  LibraryAddState.CategorySelectionRequired ->
    stringResource(R.string.library_add_category_required)

  LibraryAddState.TitleNotFound ->
    stringResource(R.string.library_add_title_not_found)
}

@Composable
private fun continueMessage(
  screen: TitleDetailsScreen,
  actionState: ContinueActionState,
): String? = when (actionState) {
  ContinueActionState.Idle -> when (val state = screen.continueState) {
    is ContinueState.Ready -> null
    is ContinueState.Unavailable -> state.reason.message()
  }

  ContinueActionState.Selecting ->
    stringResource(R.string.continue_selecting)

  is ContinueActionState.Selected -> {
    val chapter = screen.chapters.singleOrNull { item ->
      item.id == actionState.target.chapterId &&
        item.key == actionState.target.chapterKey
    }
    if (chapter == null) {
      stringResource(R.string.continue_target_selected_unknown_chapter)
    } else {
      val chapterName = displayName(
        value = chapter.displayName,
        fallback = R.string.chapter_name_fallback,
      )
      stringResource(R.string.continue_target_selected, chapterName)
    }
  }

  is ContinueActionState.Unavailable -> actionState.reason.message()

  ContinueActionState.TitleNotFound ->
    stringResource(R.string.continue_title_not_found)
}

private fun needsDetailsRetry(
  screen: TitleDetailsScreen,
  actionState: ContinueActionState,
): Boolean = when (actionState) {
  is ContinueActionState.Unavailable ->
    actionState.reason is ContinueUnavailableReason.SavedTargetUnavailable

  ContinueActionState.TitleNotFound -> true
  else -> screen.continueState.let { state ->
    state is ContinueState.Unavailable &&
      state.reason is ContinueUnavailableReason.SavedTargetUnavailable
  }
}

@Composable
private fun ContinueUnavailableReason.message(): String = when (this) {
  ContinueUnavailableReason.NoAvailableChapter ->
    stringResource(R.string.continue_no_chapter)

  is ContinueUnavailableReason.SavedTargetUnavailable ->
    stringResource(R.string.continue_saved_target_unavailable)
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
