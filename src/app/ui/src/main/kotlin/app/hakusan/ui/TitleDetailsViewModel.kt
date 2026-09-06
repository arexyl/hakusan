package app.hakusan.ui

import app.hakusan.sdk.ContinueSelectionResult
import app.hakusan.sdk.ContinueSelectionService
import app.hakusan.sdk.ContinueState
import app.hakusan.sdk.ContinueTarget
import app.hakusan.sdk.ContinueUnavailableReason
import app.hakusan.sdk.DetailsScreenFailure
import app.hakusan.sdk.DetailsScreenResult
import app.hakusan.sdk.ScreenTitleKey
import app.hakusan.sdk.TitleDetailsScreen
import app.hakusan.sdk.TitleDetailsScreenService
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CoroutineScope

class TitleDetailsViewModel internal constructor(
  private val detailsService: TitleDetailsScreenService,
  private val continueService: ContinueSelectionService,
  taskScope: CoroutineScope? = null,
) : ViewModel() {
  private val scope = taskScope ?: viewModelScope

  private val entries =
    mutableMapOf<PresentationEntryId, TitleDetailsEntry>()

  internal fun state(
    entryId: PresentationEntryId,
    titleKey: ScreenTitleKey,
  ): State<TitleDetailsEntryState> = entry(entryId, titleKey).state

  internal fun ensureDetails(entryId: PresentationEntryId) {
    entries[entryId]?.ensure()
  }

  internal fun retryDetails(entryId: PresentationEntryId) {
    entries[entryId]?.retry()
  }

  internal fun selectContinue(entryId: PresentationEntryId) {
    entries[entryId]?.selectContinue()
  }

  internal fun discard(entryId: PresentationEntryId) {
    entries.remove(entryId)?.close()
  }

  private fun entry(
    entryId: PresentationEntryId,
    titleKey: ScreenTitleKey,
  ): TitleDetailsEntry {
    val current = entries[entryId]
    if (current != null) {
      require(current.titleKey == titleKey) {
        "A presentation entry cannot be rebound to another title."
      }
      return current
    }
    return TitleDetailsEntry(
      titleKey = titleKey,
      scope = scope,
      detailsService = detailsService,
      continueService = continueService,
    ).also { entries[entryId] = it }
  }

  companion object {
    fun factory(
      detailsService: () -> TitleDetailsScreenService,
      continueService: () -> ContinueSelectionService,
    ): ViewModelProvider.Factory = viewModelFactory {
      initializer {
        TitleDetailsViewModel(
          detailsService = detailsService(),
          continueService = continueService(),
        )
      }
    }
  }
}

internal sealed interface TitleDetailsEntryState {
  data object Loading : TitleDetailsEntryState

  data object Superseded : TitleDetailsEntryState

  data class Failed(
    val failure: DetailsScreenFailure,
  ) : TitleDetailsEntryState

  data class Content(
    val screen: TitleDetailsScreen,
    val continueAction: ContinueActionState = ContinueActionState.Idle,
  ) : TitleDetailsEntryState
}

internal sealed interface ContinueActionState {
  data object Idle : ContinueActionState

  data object Selecting : ContinueActionState

  data class Selected(
    val target: ContinueTarget,
  ) : ContinueActionState

  data class Unavailable(
    val reason: ContinueUnavailableReason,
  ) : ContinueActionState

  data object TitleNotFound : ContinueActionState
}

private class TitleDetailsEntry(
  val titleKey: ScreenTitleKey,
  private val scope: CoroutineScope,
  private val detailsService: TitleDetailsScreenService,
  private val continueService: ContinueSelectionService,
) {
  private val mutableState = mutableStateOf<TitleDetailsEntryState>(
    TitleDetailsEntryState.Loading,
  )
  val state: State<TitleDetailsEntryState> = mutableState

  private val detailsTask = TaskSlot()
  private val continueTask = TaskSlot()
  private var started = false

  fun ensure() {
    if (started) {
      return
    }
    started = true
    detailsTask.start(
      scope = scope,
      onStarted = {
        mutableState.value = TitleDetailsEntryState.Loading
      },
      request = ::loadDetails,
      accept = { mutableState.value = it },
    )
  }

  fun retry() {
    continueTask.cancel()
    started = true
    detailsTask.replace(
      scope = scope,
      onStarted = {
        mutableState.value = TitleDetailsEntryState.Loading
      },
      request = ::loadDetails,
      accept = { mutableState.value = it },
    )
  }

  fun selectContinue() {
    val content = mutableState.value as? TitleDetailsEntryState.Content
      ?: return
    if (content.screen.continueState !is ContinueState.Ready) {
      return
    }
    continueTask.start(
      scope = scope,
      onStarted = {
        mutableState.value = content.copy(
          continueAction = ContinueActionState.Selecting,
        )
      },
      request = {
        continueService.selectContinue(content.screen.id)
      },
      accept = { result ->
        mutableState.value = content.copy(
          continueAction = result.toActionState(),
        )
      },
    )
  }

  fun close() {
    continueTask.cancel()
    detailsTask.cancel()
  }

  private suspend fun loadDetails(): TitleDetailsEntryState =
    when (val result = detailsService.loadDetails(titleKey)) {
      is DetailsScreenResult.Success -> TitleDetailsEntryState.Content(
        screen = result.screen,
      )

      is DetailsScreenResult.Failure -> TitleDetailsEntryState.Failed(
        failure = result.error,
      )

      DetailsScreenResult.RejectedNotCurrent ->
        TitleDetailsEntryState.Superseded
    }

  private fun ContinueSelectionResult.toActionState(): ContinueActionState =
    when (this) {
      is ContinueSelectionResult.Selected ->
        ContinueActionState.Selected(target)

      is ContinueSelectionResult.Unavailable ->
        ContinueActionState.Unavailable(reason)

      ContinueSelectionResult.TitleNotFound ->
        ContinueActionState.TitleNotFound
    }
}
