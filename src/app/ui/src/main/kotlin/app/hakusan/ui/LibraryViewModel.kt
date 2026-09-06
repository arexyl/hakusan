package app.hakusan.ui

import app.hakusan.sdk.AddToLibraryScreenResult
import app.hakusan.sdk.LibraryScreen
import app.hakusan.sdk.LibraryScreenService
import app.hakusan.sdk.ScreenTitleId
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class LibraryViewModel(
  private val libraryService: LibraryScreenService,
) : ViewModel() {
  internal var libraryState: LibraryLoadState by mutableStateOf(
    LibraryLoadState.Loading,
  )
    private set

  private val addOwners = mutableStateMapOf<ScreenTitleId, LibraryAddOwner>()
  private val addJobs = mutableMapOf<ScreenTitleId, Job>()

  // Retain positive membership confirmations for this model's lifetime.
  private val confirmedMemberships = mutableSetOf<ScreenTitleId>()

  init {
    viewModelScope.launch {
      libraryService.observeLibrary().collect { screen ->
        confirmedMemberships.addAll(screen.titlesById.keys)
        libraryState = LibraryLoadState.Loaded(screen)
      }
    }
  }

  internal fun addState(titleId: ScreenTitleId): LibraryAddState =
    addOwners[titleId]?.state ?: LibraryAddState.Idle

  internal fun isInLibrary(
    titleId: ScreenTitleId,
    snapshotMembership: Boolean,
  ): Boolean =
    snapshotMembership ||
      titleId in currentTitleIds() ||
      addState(titleId) == LibraryAddState.Committed ||
      titleId in confirmedMemberships

  internal fun confirmMembership(titleId: ScreenTitleId) {
    confirmedMemberships.add(titleId)
  }

  internal fun addToLibrary(titleId: ScreenTitleId) {
    val owner = addOwner(titleId)
    if (titleId in addJobs || !owner.begin()) {
      return
    }

    lateinit var job: Job
    job = viewModelScope.launch(start = CoroutineStart.LAZY) {
      try {
        when (
          val result = libraryService.addToLibrary(titleId)
        ) {
          AddToLibraryScreenResult.Success -> {
            confirmedMemberships.add(titleId)
            owner.commit()
          }

          AddToLibraryScreenResult.CategorySelectionRequired ->
            owner.requireCategorySelection()

          AddToLibraryScreenResult.TitleNotFound -> owner.titleNotFound()
        }
      } finally {
        addJobs.remove(titleId, job)
      }
    }
    addJobs[titleId] = job
    job.start()
  }

  private fun addOwner(titleId: ScreenTitleId): LibraryAddOwner =
    addOwners.getOrPut(titleId, ::LibraryAddOwner)

  private fun currentTitleIds(): Set<ScreenTitleId> =
    when (val state = libraryState) {
      LibraryLoadState.Loading -> emptySet()
      is LibraryLoadState.Loaded -> state.screen.titlesById.keys
    }

  companion object {
    fun factory(
      libraryService: () -> LibraryScreenService,
    ): ViewModelProvider.Factory = viewModelFactory {
      initializer {
        LibraryViewModel(
          libraryService = libraryService(),
        )
      }
    }
  }
}

internal sealed interface LibraryLoadState {
  data object Loading : LibraryLoadState

  data class Loaded(
    val screen: LibraryScreen,
  ) : LibraryLoadState
}

internal sealed interface LibraryAddState {
  data object Idle : LibraryAddState

  data object Adding : LibraryAddState

  data object Committed : LibraryAddState

  data object CategorySelectionRequired : LibraryAddState

  data object TitleNotFound : LibraryAddState
}

private class LibraryAddOwner {
  var state: LibraryAddState by mutableStateOf(LibraryAddState.Idle)
    private set

  fun begin(): Boolean {
    if (state == LibraryAddState.Adding) {
      return false
    }
    state = LibraryAddState.Adding
    return true
  }

  fun commit() {
    complete(LibraryAddState.Committed)
  }

  fun requireCategorySelection() {
    complete(LibraryAddState.CategorySelectionRequired)
  }

  fun titleNotFound() {
    complete(LibraryAddState.TitleNotFound)
  }

  private fun complete(completion: LibraryAddState) {
    check(state == LibraryAddState.Adding) {
      "A Library Add can complete only while it is pending."
    }
    state = completion
  }
}
