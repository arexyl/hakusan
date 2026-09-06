package app.hakusan.ui

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TaskSlotTest {
  @Test
  fun `replacement rejects a non-cooperative late completion`(): Unit =
    runBlocking {
      withTimeout(TEST_TIMEOUT_MILLIS) {
        val slot = TaskSlot()
        val oldStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val oldReturned = CompletableDeferred<Unit>()
        val accepted = ArrayList<String>()

        slot.start(
          scope = this,
          onStarted = {},
          request = {
            oldStarted.complete(Unit)
            try {
              awaitCancellation()
            } catch (_: CancellationException) {
              withContext(NonCancellable) {
                releaseOld.await()
              }
              oldReturned.complete(Unit)
              "old"
            }
          },
          accept = accepted::add,
        )
        oldStarted.await()

        slot.replace(
          scope = this,
          onStarted = {},
          request = { "new" },
          accept = accepted::add,
        )
        awaitCondition { accepted == listOf("new") }
        slot.start(
          scope = this,
          onStarted = {},
          request = { "after" },
          accept = accepted::add,
        )
        awaitCondition { accepted == listOf("new", "after") }

        releaseOld.complete(Unit)
        oldReturned.await()
        yield()

        assertEquals(listOf("new", "after"), accepted)
      }
    }

  @Test
  fun `cancel revokes publication before requesting cancellation`(): Unit =
    runBlocking {
      withTimeout(TEST_TIMEOUT_MILLIS) {
        val slot = TaskSlot()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        val accepted = ArrayList<String>()

        slot.start(
          scope = this,
          onStarted = {},
          request = {
            started.complete(Unit)
            try {
              awaitCancellation()
            } catch (_: CancellationException) {
              withContext(NonCancellable) {
                release.await()
              }
              returned.complete(Unit)
              "late"
            }
          },
          accept = accepted::add,
        )
        started.await()

        slot.cancel()
        slot.start(
          scope = this,
          onStarted = {},
          request = { "current" },
          accept = accepted::add,
        )
        awaitCondition { accepted == listOf("current") }
        release.complete(Unit)
        returned.await()
        yield()
        assertEquals(listOf("current"), accepted)
      }
    }

  @Test
  fun `failed start callback restores the previous task`(): Unit = runBlocking {
    withTimeout(TEST_TIMEOUT_MILLIS) {
      val slot = TaskSlot()
      val previous = CompletableDeferred<String>()
      val accepted = CompletableDeferred<String>()
      slot.start(
        scope = this,
        onStarted = {},
        request = previous::await,
        accept = { result -> accepted.complete(result) },
      )

      assertThrows(IllegalStateException::class.java) {
        slot.replace(
          scope = this,
          onStarted = { error("injected state failure") },
          request = { "replacement" },
          accept = { result -> accepted.complete(result) },
        )
      }

      previous.complete("previous")

      assertEquals("previous", accepted.await())
      yield()
      val next = CompletableDeferred<String>()
      slot.start(
        scope = this,
        onStarted = {},
        request = { "next" },
        accept = { result -> next.complete(result) },
      )
      assertEquals("next", next.await())
    }
  }

  @Test
  fun `unexpected task failure reaches scope and releases the slot`(): Unit =
    runBlocking {
      withTimeout(TEST_TIMEOUT_MILLIS) {
        val reported = CompletableDeferred<Throwable>()
        val owner = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(
          coroutineContext + owner + CoroutineExceptionHandler { _, failure ->
            reported.complete(failure)
          },
        )
        val slot = TaskSlot()
        val failure = IllegalStateException("injected task failure")
        try {
          slot.start(
            scope = scope,
            onStarted = {},
            request = { throw failure },
            accept = { error("A failed task must not publish a result.") },
          )

          assertSame(failure, reported.await())
          val accepted = CompletableDeferred<String>()
          slot.start(
            scope = scope,
            onStarted = {},
            request = { "recovered" },
            accept = { result -> accepted.complete(result) },
          )
          assertEquals("recovered", accepted.await())
        } finally {
          scope.cancel()
        }
      }
    }

  private companion object {
    const val TEST_TIMEOUT_MILLIS = 5_000L
  }
}
