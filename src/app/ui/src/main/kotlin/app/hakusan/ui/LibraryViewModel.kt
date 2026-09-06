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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel internal constructor(
  private val libraryService: LibraryScreenService,
  taskScope: CoroutineScope? = null,
) : ViewModel() {
  private val scope = taskScope ?: viewModelScope

  internal val libraryState: StateFlow<LibraryLoadState> =
    libraryService.observeLibrary()
      .map<LibraryScreen, LibraryLoadState> { screen ->
        LibraryLoadState.Loaded(screen)
      }
      .stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(),
        initialValue = LibraryLoadState.Loading,
      )

  private var membershipState: LibraryMembershipState by mutableStateOf(
    LibraryMembershipState.Loading,
  )
  private val additions =
    mutableStateMapOf<ScreenTitleId, LibraryAddition>()
  private val membershipBridges =
    mutableStateMapOf<ScreenTitleId, Unit>()
  private var lastAddFeedback: LibraryAddFeedback? by mutableStateOf(null)

  init {
    scope.launch {
      libraryService.observeLibraryTitleIds().collect { titleIds ->
        membershipState = LibraryMembershipState.Loaded(titleIds)
        membershipBridges.clear()
        val feedback = lastAddFeedback
        if (
          feedback != null &&
          feedback.isContradictedBy(titleIds)
        ) {
          lastAddFeedback = null
        }
      }
    }
  }

  internal fun membership(titleId: ScreenTitleId): LibraryMembership {
    val committed = membershipState
    return when {
      titleId in membershipBridges ->
        LibraryMembership.Member

      committed is LibraryMembershipState.Loading ->
        LibraryMembership.Loading

      committed is LibraryMembershipState.Loaded &&
        titleId in committed.titleIds ->
        LibraryMembership.Member

      else -> LibraryMembership.NotMember
    }
  }

  internal fun addState(titleId: ScreenTitleId): LibraryAddState {
    val feedback = lastAddFeedback
    return when {
      titleId in additions &&
        currentTitleIds()?.contains(titleId) != true ->
        LibraryAddState.Adding

      feedback?.titleId == titleId -> feedback.outcome.toAddState()
      else -> LibraryAddState.Idle
    }
  }

  internal fun addToLibrary(titleId: ScreenTitleId) {
    if (
      membership(titleId) != LibraryMembership.NotMember ||
      titleId in additions
    ) {
      return
    }

    lastAddFeedback = null
    val addition = LibraryAddition(
      titleId = titleId,
      scope = scope,
      libraryService = libraryService,
      onCompleted = { completed, result ->
        acceptCompletion(titleId, completed, result)
      },
    )
    additions[titleId] = addition
    addition.start()
  }

  private fun acceptCompletion(
    titleId: ScreenTitleId,
    addition: LibraryAddition,
    result: AddToLibraryScreenResult,
  ) {
    if (additions[titleId] !== addition) {
      return
    }
    additions.remove(titleId)
    val outcome = result.toFeedbackOutcome()
    val isMember = currentTitleIds()?.contains(titleId) == true
    if (outcome != LibraryAddFeedbackOutcome.COMMITTED && isMember) {
      return
    }
    if (outcome == LibraryAddFeedbackOutcome.COMMITTED && !isMember) {
      membershipBridges[titleId] = Unit
    }
    lastAddFeedback = LibraryAddFeedback(
      titleId = titleId,
      outcome = outcome,
    )
  }

  private fun currentTitleIds(): Set<ScreenTitleId>? =
    (membershipState as? LibraryMembershipState.Loaded)?.titleIds

  companion object {
    fun factory(
      libraryService: () -> LibraryScreenService,
    ): ViewModelProvider.Factory = viewModelFactory {
      initializer {
        LibraryViewModel(libraryService = libraryService())
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

internal sealed interface LibraryMembership {
  data object Loading : LibraryMembership

  data object NotMember : LibraryMembership

  data object Member : LibraryMembership
}

internal sealed interface LibraryAddState {
  data object Idle : LibraryAddState

  data object Adding : LibraryAddState

  data object Committed : LibraryAddState

  data object CategorySelectionRequired : LibraryAddState

  data object TitleNotFound : LibraryAddState
}

private sealed interface LibraryMembershipState {
  data object Loading : LibraryMembershipState

  data class Loaded(
    val titleIds: Set<ScreenTitleId>,
  ) : LibraryMembershipState
}

private class LibraryAddition(
  private val titleId: ScreenTitleId,
  private val scope: CoroutineScope,
  private val libraryService: LibraryScreenService,
  private val onCompleted: (
    LibraryAddition,
    AddToLibraryScreenResult,
  ) -> Unit,
) {
  private val task = TaskSlot()

  fun start() {
    task.start(
      scope = scope,
      onStarted = {},
      request = { libraryService.addToLibrary(titleId) },
      accept = { result -> onCompleted(this, result) },
    )
  }
}

private data class LibraryAddFeedback(
  val titleId: ScreenTitleId,
  val outcome: LibraryAddFeedbackOutcome,
) {
  fun isContradictedBy(titleIds: Set<ScreenTitleId>): Boolean =
    when (outcome) {
      LibraryAddFeedbackOutcome.COMMITTED -> titleId !in titleIds
      LibraryAddFeedbackOutcome.CATEGORY_SELECTION_REQUIRED,
      LibraryAddFeedbackOutcome.TITLE_NOT_FOUND,
      -> titleId in titleIds
    }
}

private enum class LibraryAddFeedbackOutcome {
  COMMITTED,
  CATEGORY_SELECTION_REQUIRED,
  TITLE_NOT_FOUND,
}

private fun LibraryAddFeedbackOutcome.toAddState(): LibraryAddState =
  when (this) {
    LibraryAddFeedbackOutcome.COMMITTED -> LibraryAddState.Committed
    LibraryAddFeedbackOutcome.CATEGORY_SELECTION_REQUIRED ->
      LibraryAddState.CategorySelectionRequired

    LibraryAddFeedbackOutcome.TITLE_NOT_FOUND -> LibraryAddState.TitleNotFound
  }

private fun AddToLibraryScreenResult.toFeedbackOutcome():
  LibraryAddFeedbackOutcome =
  when (this) {
    AddToLibraryScreenResult.Success -> LibraryAddFeedbackOutcome.COMMITTED
    AddToLibraryScreenResult.CategorySelectionRequired ->
      LibraryAddFeedbackOutcome.CATEGORY_SELECTION_REQUIRED

    AddToLibraryScreenResult.TitleNotFound ->
      LibraryAddFeedbackOutcome.TITLE_NOT_FOUND
  }
