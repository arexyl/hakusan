package app.hakusan.ui

import app.hakusan.sdk.AddToLibraryScreenResult
import app.hakusan.sdk.LibraryResumeState
import app.hakusan.sdk.LibraryScreen
import app.hakusan.sdk.LibraryScreenService
import app.hakusan.sdk.LibraryShelfMembers
import app.hakusan.sdk.LibraryTitleItem
import app.hakusan.sdk.LibraryTitleProgress
import app.hakusan.sdk.ScreenShelfId
import app.hakusan.sdk.ScreenSourceId
import app.hakusan.sdk.ScreenTitleId
import app.hakusan.sdk.ScreenTitleKey
import java.util.Locale
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals

/** The bound of every wait of the observation tests. */
internal val OBSERVATION_TIMEOUT: Duration = 5.seconds

/**
 * A Library service whose observation the test drives.
 *
 * It counts the collections of [observeLibrary] that started and those that
 * ended, so a test awaits a start or a stop of the upstream instead of
 * sleeping. A collection starts once it can receive [emit].
 */
internal class ScriptedLibraryService : LibraryScreenService {
  private val events = MutableSharedFlow<ObservationEvent>()
  private val started = MutableStateFlow(0)
  private val stopped = MutableStateFlow(0)

  /** The number of collections that started. */
  val collections: Int
    get() = started.value

  /** The number of collections that ended. */
  val stops: Int
    get() = stopped.value

  override fun observeLibrary(): Flow<LibraryScreen> =
    events
      .onSubscription { started.update { count -> count + 1 } }
      .onCompletion { stopped.update { count -> count + 1 } }
      .map { event ->
        when (event) {
          is ObservationEvent.Screen -> event.screen
          is ObservationEvent.Failure -> throw event.cause
        }
      }

  override fun observeLibraryTitleIds(): Flow<Set<ScreenTitleId>> =
    throw AssertionError("The Library owner observes no membership yet.")

  override suspend fun addToLibrary(
    titleId: ScreenTitleId
  ): AddToLibraryScreenResult =
    throw AssertionError("The Library owner adds no title yet.")

  /** Delivers [screen] to every active collection. */
  suspend fun emit(screen: LibraryScreen) {
    events.emit(ObservationEvent.Screen(screen))
  }

  /** Makes every active collection throw [cause]. */
  suspend fun fail(cause: Throwable) {
    events.emit(ObservationEvent.Failure(cause))
  }

  /** Waits until [count] collections have started. */
  suspend fun awaitCollections(count: Int) {
    withTimeout(OBSERVATION_TIMEOUT) { started.first { it >= count } }
  }

  /** Waits until [count] collections have ended. */
  suspend fun awaitStops(count: Int) {
    withTimeout(OBSERVATION_TIMEOUT) { stopped.first { it >= count } }
  }

  /**
   * Returns whether another collection ends within [window].
   *
   * The tests run on one event loop, which runs every coroutine that is ready
   * before a timer that is not due yet. A stop that the owner requests without
   * waiting for its own timer therefore ends a collection within the window.
   */
  suspend fun stopsWithin(window: Duration): Boolean {
    val before = stopped.value

    val stop = withTimeoutOrNull(window) { stopped.first { it > before } }

    return stop != null
  }

  private sealed interface ObservationEvent {
    data class Screen(val screen: LibraryScreen) : ObservationEvent

    data class Failure(val cause: Throwable) : ObservationEvent
  }
}

/**
 * Runs [block] with a Library owner of [service] in a supervisor scope of the
 * caller, and cancels that scope afterwards.
 *
 * A failure in the owner's scope reaches [handler]. Without a handler, such a
 * failure fails the test.
 */
internal suspend fun withLibraryModel(
  service: LibraryScreenService,
  stopTimeout: Duration = LIBRARY_STOP_TIMEOUT,
  handler: CoroutineExceptionHandler? = null,
  block: suspend CoroutineScope.(LibraryModel) -> Unit,
) {
  val unexpected = ArrayList<Throwable>()

  val failures =
    handler ?: CoroutineExceptionHandler { _, failure -> unexpected += failure }

  coroutineScope {
    val job = SupervisorJob(coroutineContext.job)

    val scope = CoroutineScope(coroutineContext + job + failures)

    val model = LibraryModel(service, scope, stopTimeout)

    try {
      block(model)
    } finally {
      scope.cancel()
    }
  }

  assertEquals(emptyList<Throwable>(), unexpected)
}

/** Waits until [state] holds [expected]. */
internal suspend fun <T> awaitValue(state: StateFlow<T>, expected: T) {
  withTimeout(OBSERVATION_TIMEOUT) { state.first { it == expected } }
}

/** Returns a Library screen with one title on each of the named shelves. */
internal fun libraryScreen(vararg shelves: Pair<Long, String>): LibraryScreen {
  val titles = shelves.mapIndexed { index, (_, name) ->
    libraryTitle(index + 1, name)
  }

  val members = shelves.mapIndexed { index, (id, name) ->
    LibraryShelfMembers(ScreenShelfId(id), name, setOf(titles[index].id))
  }

  return LibraryScreen.of(titles, members, Locale.ROOT)
}

private fun libraryTitle(suffix: Int, name: String): LibraryTitleItem {
  val id = ScreenTitleId(UUID(TITLE_ID_HIGH_BITS, TITLE_ID_LOW_BITS + suffix))

  val key = ScreenTitleKey(ScreenSourceId("app.hakusan.test.source"), name)

  val progress = LibraryTitleProgress(1, 0, LibraryResumeState.NONE)

  return LibraryTitleItem(id, key, name, null, "Source", progress)
}

/** The high bits of a test title UUID: version 7. */
private const val TITLE_ID_HIGH_BITS = 0x0192a6f07c3e7d4aL

/** The low bits of a test title UUID: the RFC 9562 variant. */
private const val TITLE_ID_LOW_BITS = Long.MIN_VALUE
