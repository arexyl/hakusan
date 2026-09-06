package app.hakusan.ui

import app.hakusan.sdk.BrowseScreen
import app.hakusan.sdk.BrowseScreenFailure
import app.hakusan.sdk.BrowseScreenResult
import app.hakusan.sdk.BrowseScreenService
import app.hakusan.sdk.CatalogScreen
import app.hakusan.sdk.ScreenSourceId
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CoroutineScope

class CatalogViewModel internal constructor(
  private val browseService: BrowseScreenService,
  taskScope: CoroutineScope? = null,
) : ViewModel() {
  private val scope = taskScope ?: viewModelScope

  internal val catalog: CatalogScreen = browseService.catalog()

  private val browseEntries =
    mutableMapOf<PresentationEntryId, SourceBrowseEntry>()

  internal fun browseState(
    entryId: PresentationEntryId,
    sourceId: ScreenSourceId,
  ): State<SourceBrowseState> =
    entry(entryId, sourceId).state

  internal fun ensureBrowse(entryId: PresentationEntryId) {
    browseEntries[entryId]?.ensure()
  }

  internal fun retryBrowse(entryId: PresentationEntryId) {
    browseEntries[entryId]?.retry()
  }

  internal fun discard(entryId: PresentationEntryId) {
    browseEntries.remove(entryId)?.close()
  }

  private fun entry(
    entryId: PresentationEntryId,
    sourceId: ScreenSourceId,
  ): SourceBrowseEntry {
    val current = browseEntries[entryId]
    if (current != null) {
      require(current.sourceId == sourceId) {
        "A presentation entry cannot be rebound to another source."
      }
      return current
    }
    return SourceBrowseEntry(
      sourceId = sourceId,
      scope = scope,
      browseService = browseService,
    ).also { browseEntries[entryId] = it }
  }

  companion object {
    fun factory(
      browseService: () -> BrowseScreenService,
    ): ViewModelProvider.Factory = viewModelFactory {
      initializer {
        CatalogViewModel(browseService = browseService())
      }
    }
  }
}

internal sealed interface SourceBrowseState {
  data object Loading : SourceBrowseState

  data class Content(
    val screen: BrowseScreen,
  ) : SourceBrowseState

  data class Failed(
    val failure: BrowseScreenFailure,
  ) : SourceBrowseState
}

private class SourceBrowseEntry(
  val sourceId: ScreenSourceId,
  private val scope: CoroutineScope,
  private val browseService: BrowseScreenService,
) {
  private val mutableState = mutableStateOf<SourceBrowseState>(
    SourceBrowseState.Loading,
  )
  val state: State<SourceBrowseState> = mutableState

  private var started = false
  private val task = TaskSlot()

  fun ensure() {
    if (started) {
      return
    }
    started = true
    task.start(
      scope = scope,
      onStarted = { mutableState.value = SourceBrowseState.Loading },
      request = ::load,
      accept = { mutableState.value = it },
    )
  }

  fun retry() {
    started = true
    task.replace(
      scope = scope,
      onStarted = { mutableState.value = SourceBrowseState.Loading },
      request = ::load,
      accept = { mutableState.value = it },
    )
  }

  fun close() {
    task.cancel()
  }

  private suspend fun load(): SourceBrowseState =
    when (val result = browseService.loadBrowse(sourceId)) {
      is BrowseScreenResult.Success -> SourceBrowseState.Content(result.screen)
      is BrowseScreenResult.Failure -> SourceBrowseState.Failed(result.error)
    }
}
