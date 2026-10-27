package app.hakusan.ui

import app.hakusan.sdk.ScreenShelfId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LibraryModelTest {
  private val service = ScriptedLibraryService()

  private val first = libraryScreen(1L to "First")

  private val second = libraryScreen(1L to "First", 2L to "Second")

  @Test
  fun `the Library is loading until the first screen arrives`(): Unit =
    runBlocking {
      withLibraryModel(service) { model ->
        assertEquals(LibraryState.Loading, model.library.value)

        val collector = launch { model.library.collect {} }

        service.awaitCollections(1)
        assertEquals(LibraryState.Loading, model.library.value)
        service.emit(first)
        awaitValue(model.library, LibraryState.Loaded(first))
        collector.cancel()
      }
    }

  @Test
  fun `a subscriber returning within the timeout keeps the observation`():
    Unit = runBlocking {
    withLibraryModel(service, stopTimeout = 1.minutes) { model ->
      val leaving = launch { model.library.collect {} }

      service.awaitCollections(1)
      leaving.cancelAndJoin()
      assertFalse(service.stopsWithin(AWAY))

      val returning = launch { model.library.collect {} }

      service.emit(first)
      awaitValue(model.library, LibraryState.Loaded(first))
      assertEquals(1, service.collections)
      assertEquals(0, service.stops)
      returning.cancel()
    }
  }

  @Test
  fun `the observation stops after the timeout and keeps the last screen`():
    Unit = runBlocking {
    withLibraryModel(service, stopTimeout = 10.milliseconds) { model ->
      val leaving = launch { model.library.collect {} }

      service.awaitCollections(1)
      service.emit(first)
      awaitValue(model.library, LibraryState.Loaded(first))
      leaving.cancel()
      service.awaitStops(1)
      assertEquals(LibraryState.Loaded(first), model.library.value)

      val returning = launch { model.library.collect {} }

      service.awaitCollections(2)
      assertEquals(LibraryState.Loaded(first), model.library.value)
      service.emit(second)
      awaitValue(model.library, LibraryState.Loaded(second))
      returning.cancel()
    }
  }

  @Test
  fun `a failing observation becomes no state`(): Unit = runBlocking {
    val failure = IllegalStateException("A defect of the observation.")

    val reported = CompletableDeferred<Throwable>()

    val handler = CoroutineExceptionHandler { _, error ->
      reported.complete(error)
    }

    withLibraryModel(service, handler = handler) { model ->
      val collector = launch { model.library.collect {} }

      service.awaitCollections(1)
      service.emit(first)
      awaitValue(model.library, LibraryState.Loaded(first))
      service.fail(failure)

      val propagated = withTimeout(OBSERVATION_TIMEOUT) { reported.await() }

      assertPropagated(failure, propagated)
      assertEquals(LibraryState.Loaded(first), model.library.value)
      collector.cancel()
    }
  }

  @Test
  fun `carousel positions of removed shelves are dropped`(): Unit =
    runBlocking {
      withLibraryModel(service) { model ->
        val kept = ScreenShelfId(1L)

        val removed = ScreenShelfId(2L)

        val position = CarouselPosition(index = 7, offset = 12)

        val collector = launch { model.library.collect {} }

        service.awaitCollections(1)
        service.emit(second)
        awaitValue(model.library, LibraryState.Loaded(second))
        model.keepCarouselPosition(kept, position)
        model.keepCarouselPosition(removed, position)
        service.emit(first)
        awaitValue(model.library, LibraryState.Loaded(first))
        assertEquals(position, model.carouselPosition(kept))
        assertEquals(CarouselPosition.START, model.carouselPosition(removed))
        collector.cancel()
      }
    }

  @Test
  fun `a carousel leaving after its shelf was removed keeps no position`():
    Unit = runBlocking {
    withLibraryModel(service) { model ->
      val removed = ScreenShelfId(2L)

      val position = CarouselPosition(index = 7, offset = 12)

      val collector = launch { model.library.collect {} }

      service.awaitCollections(1)
      service.emit(second)
      awaitValue(model.library, LibraryState.Loaded(second))
      service.emit(first)
      awaitValue(model.library, LibraryState.Loaded(first))
      model.keepCarouselPosition(removed, position)
      assertEquals(CarouselPosition.START, model.carouselPosition(removed))
      service.emit(second)
      awaitValue(model.library, LibraryState.Loaded(second))
      assertEquals(CarouselPosition.START, model.carouselPosition(removed))
      collector.cancel()
    }
  }
}

/**
 * Asserts that [propagated] is [thrown] itself, or the copy of it that
 * kotlinx.coroutines stack trace recovery makes when [thrown] crosses a
 * coroutine boundary. Recovery runs in kotlinx.coroutines debug mode, which
 * host tests turn on through assertions. The copy keeps the class and the
 * message, has the original as its cause, and carries the coroutine boundary
 * frame in its stack trace. Any other failure, such as a wrapper, fails.
 */
private fun assertPropagated(thrown: Throwable, propagated: Throwable) {
  if (propagated === thrown) {
    return
  }

  assertSame(thrown.javaClass, propagated.javaClass)
  assertEquals(thrown.message, propagated.message)
  assertSame(thrown, propagated.cause)

  assertTrue(
    propagated.stackTrace.any { it.className == COROUTINE_BOUNDARY_FRAME }
  )
}

/** The class of the frame that marks a recovered coroutine boundary. */
private const val COROUTINE_BOUNDARY_FRAME = "_COROUTINE._BOUNDARY"

/**
 * How long a subscriber stays away before it returns: far below the stop
 * timeout of the owner under test, and far above the time that a stop without a
 * timer takes.
 */
private val AWAY: Duration = 100.milliseconds
