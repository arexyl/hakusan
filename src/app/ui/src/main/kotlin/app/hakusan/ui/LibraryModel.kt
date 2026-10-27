package app.hakusan.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.hakusan.sdk.LibraryScreen
import app.hakusan.sdk.LibraryScreenService
import app.hakusan.sdk.ScreenShelfId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/**
 * How long the Library observation stays active without a subscriber.
 *
 * About five seconds keep it alive across a configuration change, while a
 * Library that stays hidden stops observing soon after.
 */
internal val LIBRARY_STOP_TIMEOUT: Duration = 5.seconds

/** The presented state of the committed Library. */
internal sealed interface LibraryState {
  /** No Library snapshot has arrived yet. */
  data object Loading : LibraryState

  /** The latest committed Library [screen]. */
  data class Loaded(val screen: LibraryScreen) : LibraryState
}

/** A carousel position: the first visible item and its scroll offset. */
internal data class CarouselPosition(val index: Int, val offset: Int) {
  companion object {
    /** The position of a carousel that shows its first item. */
    val START: CarouselPosition = CarouselPosition(index = 0, offset = 0)
  }
}

/**
 * The presentation owner of the Library, one for each Activity scope.
 *
 * It shares the committed Library observation of [service] as [library] while a
 * screen collects it, and keeps the observation running for [stopTimeout] after
 * the last subscriber leaves. It keeps the last snapshot meanwhile, so a
 * returning subscriber sees it at once. A [StateFlow] conflates: a subscriber
 * that falls behind skips to the newest screen, while the observation itself
 * delivers every snapshot. A failure of the observation propagates in [scope]
 * and never becomes a state.
 *
 * It also keeps the carousel positions of the shelves in memory, keyed by
 * shelf. It keeps a position only for a shelf of the latest snapshot, and drops
 * the positions of shelves that a new snapshot no longer holds. A shelf key is
 * valid only for the current database, so the positions never enter saved
 * state, and process restoration starts every carousel at its first item.
 *
 * Use it on the main thread only. Clearing it cancels [scope].
 */
internal class LibraryModel(
  service: LibraryScreenService,
  scope: CoroutineScope,
  stopTimeout: Duration = LIBRARY_STOP_TIMEOUT,
) : ViewModel(scope) {
  private val carouselPositions = HashMap<ScreenShelfId, CarouselPosition>()

  /** The committed Library, [LibraryState.Loading] until the first snapshot. */
  val library: StateFlow<LibraryState> =
    service
      .observeLibrary()
      .onEach(::dropRemovedCarousels)
      .map<LibraryScreen, LibraryState>(LibraryState::Loaded)
      .stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(stopTimeout),
        initialValue = LibraryState.Loading,
      )

  /** Returns the kept position of the carousel of [shelf], or its start. */
  fun carouselPosition(shelf: ScreenShelfId): CarouselPosition =
    carouselPositions[shelf] ?: CarouselPosition.START

  /**
   * Keeps [position] as the position of the carousel of [shelf].
   *
   * It ignores a shelf that the latest snapshot does not hold. Such a report
   * comes from a carousel that leaves composition after a snapshot removed its
   * shelf.
   */
  fun keepCarouselPosition(shelf: ScreenShelfId, position: CarouselPosition) {
    val state = library.value

    if (state is LibraryState.Loaded && state.screen.holdsShelf(shelf)) {
      carouselPositions[shelf] = position
    }
  }

  private fun dropRemovedCarousels(screen: LibraryScreen) {
    val shelves = screen.shelves.mapTo(HashSet()) { shelf -> shelf.id }

    carouselPositions.keys.retainAll(shelves)
  }

  companion object {
    /**
     * Returns the factory of the Library owner.
     *
     * It calls [library] only when it creates an owner, and runs the owner on
     * the main thread.
     */
    fun factory(
      library: () -> LibraryScreenService
    ): ViewModelProvider.Factory = viewModelFactory {
      initializer {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        LibraryModel(library(), scope)
      }
    }
  }
}

private fun LibraryScreen.holdsShelf(shelf: ScreenShelfId): Boolean =
  shelves.any { item ->
    item.id == shelf
  }
