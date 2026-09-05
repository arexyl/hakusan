package app.hakusan.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ReaderSessionGateTest {
  @Test
  fun `new session rejects events from the previous session`() = runBlocking {
    val gate = ReaderSessionGate()
    val first = gate.start()
    val firstEvent = issue(gate, first)
    assertEquals(
      ReaderSessionAcceptance.Accepted(ReaderEventRecency.CURRENT),
      gate.withCurrent(firstEvent) { it },
    )
    val second = gate.start()
    var staleBlockInvoked = false

    assertSame(
      ReaderSessionAcceptance.RejectedNotCurrent,
      gate.withCurrent(firstEvent) {
        staleBlockInvoked = true
      },
    )
    assertSame(
      ReaderSessionAcceptance.RejectedNotCurrent,
      gate.issue(first),
    )
    assertFalse(staleBlockInvoked)
    gate.close(first)
    val secondEvent = issue(gate, second)
    assertEquals(0L, secondEvent.ordinal)
    assertEquals(
      ReaderSessionAcceptance.Accepted(ReaderEventRecency.CURRENT),
      gate.withCurrent(secondEvent) { it },
    )
  }

  @Test
  fun `event from another gate is rejected`() = runBlocking {
    val firstGate = ReaderSessionGate()
    val secondGate = ReaderSessionGate()
    val foreignSession = firstGate.start()
    val foreignEvent = issue(firstGate, foreignSession)
    val currentSession = secondGate.start()

    assertSame(
      ReaderSessionAcceptance.RejectedNotCurrent,
      secondGate.withCurrent(foreignEvent) { "unexpected" },
    )
    assertSame(
      ReaderSessionAcceptance.RejectedNotCurrent,
      secondGate.issue(foreignSession),
    )
    secondGate.close(foreignSession)
    assertEquals(
      ReaderSessionAcceptance.Accepted(ReaderEventRecency.CURRENT),
      secondGate.withCurrent(issue(secondGate, currentSession)) { it },
    )
  }

  @Test
  fun `different events delivered in order are each current`() = runBlocking {
    val gate = ReaderSessionGate()
    val session = gate.start()
    val first = issue(gate, session)
    val second = issue(gate, session)

    assertEquals(
      ReaderSessionAcceptance.Accepted(ReaderEventRecency.CURRENT),
      gate.withCurrent(first) { it },
    )
    assertEquals(
      ReaderSessionAcceptance.Accepted(ReaderEventRecency.CURRENT),
      gate.withCurrent(second) { it },
    )
  }

  @Test
  fun `session replacement waits for accepted work to finish`() = runBlocking {
    withTimeout(TEST_TIMEOUT_MILLIS) {
      val gate = ReaderSessionGate()
      val first = gate.start()
      val event = issue(gate, first)
      val entered = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      val work = async(start = CoroutineStart.UNDISPATCHED) {
        gate.withCurrent(event) {
          entered.complete(Unit)
          release.await()
          "committed"
        }
      }
      entered.await()
      val replacement = async(start = CoroutineStart.UNDISPATCHED) {
        gate.start()
      }

      assertFalse(replacement.isCompleted)
      release.complete(Unit)
      assertEquals(
        ReaderSessionAcceptance.Accepted("committed"),
        work.await(),
      )
      replacement.await()
      assertSame(
        ReaderSessionAcceptance.RejectedNotCurrent,
        gate.withCurrent(event) { "stale" },
      )
    }
  }

  @Test
  fun `failure keeps an event retryable and releases the lease`() {
    val gate = ReaderSessionGate()
    val session = runBlocking { gate.start() }
    val event = runBlocking { issue(gate, session) }

    assertThrows(IllegalStateException::class.java) {
      runBlocking {
        gate.withCurrent(event) {
          error("injected failure")
        }
      }
    }
    assertEquals(
      ReaderSessionAcceptance.Accepted(ReaderEventRecency.CURRENT),
      runBlocking { gate.withCurrent(event) { it } },
    )
    assertEquals(
      ReaderSessionAcceptance.Accepted(ReaderEventRecency.REORDERED),
      runBlocking { gate.withCurrent(event) { it } },
    )
  }

  @Test
  fun `failed older event becomes reordered after a newer commit`() {
    val gate = ReaderSessionGate()
    val session = runBlocking { gate.start() }
    val older = runBlocking { issue(gate, session) }
    val newer = runBlocking { issue(gate, session) }

    assertThrows(IllegalStateException::class.java) {
      runBlocking {
        gate.withCurrent(older) {
          error("injected failure")
        }
      }
    }
    assertEquals(
      ReaderSessionAcceptance.Accepted(ReaderEventRecency.CURRENT),
      runBlocking { gate.withCurrent(newer) { it } },
    )
    assertEquals(
      ReaderSessionAcceptance.Accepted(ReaderEventRecency.REORDERED),
      runBlocking { gate.withCurrent(older) { it } },
    )
  }

  @Test
  fun `close rejects the current session and its issued events`() =
    runBlocking {
      val gate = ReaderSessionGate()
      val session = gate.start()
      val event = issue(gate, session)

      gate.close(session)

      assertSame(
        ReaderSessionAcceptance.RejectedNotCurrent,
        gate.withCurrent(event) { "closed" },
      )
      assertSame(
        ReaderSessionAcceptance.RejectedNotCurrent,
        gate.issue(session),
      )
      gate.close(session)
    }

  @Test
  fun `cancellation keeps an event retryable and releases the lease`() =
    runBlocking {
      withTimeout(TEST_TIMEOUT_MILLIS) {
        val gate = ReaderSessionGate()
        val session = gate.start()
        val event = issue(gate, session)
        val entered = CompletableDeferred<Unit>()
        val work = async(start = CoroutineStart.UNDISPATCHED) {
          gate.withCurrent(event) {
            entered.complete(Unit)
            awaitCancellation()
          }
        }
        entered.await()

        work.cancelAndJoin()
        assertEquals(
          ReaderSessionAcceptance.Accepted(ReaderEventRecency.CURRENT),
          gate.withCurrent(event) { it },
        )
      }
    }

  @Test
  fun `canceled older event becomes reordered after a newer commit`() =
    runBlocking {
      withTimeout(TEST_TIMEOUT_MILLIS) {
        val gate = ReaderSessionGate()
        val session = gate.start()
        val older = issue(gate, session)
        val newer = issue(gate, session)
        val entered = CompletableDeferred<Unit>()
        val work = async(start = CoroutineStart.UNDISPATCHED) {
          gate.withCurrent(older) {
            entered.complete(Unit)
            awaitCancellation()
          }
        }
        entered.await()
        work.cancelAndJoin()

        assertEquals(
          ReaderSessionAcceptance.Accepted(ReaderEventRecency.CURRENT),
          gate.withCurrent(newer) { it },
        )
        assertEquals(
          ReaderSessionAcceptance.Accepted(ReaderEventRecency.REORDERED),
          gate.withCurrent(older) { it },
        )
      }
    }

  @Test
  fun `reordered events preserve newer position and older read effects`() =
    runBlocking {
      val gate = ReaderSessionGate()
      val session = gate.start()
      val first = issue(gate, session)
      val second = issue(gate, session)
      val readChapters = mutableSetOf<String>()
      var position = "A"

      val secondRecency = gate.withCurrent(second) { recency ->
        readChapters += "B"
        if (recency == ReaderEventRecency.CURRENT) {
          position = "C"
        }
        recency
      }
      val firstRecency = gate.withCurrent(first) { recency ->
        readChapters += "A"
        if (recency == ReaderEventRecency.CURRENT) {
          position = "B"
        }
        recency
      }

      assertEquals(
        ReaderSessionAcceptance.Accepted(ReaderEventRecency.CURRENT),
        secondRecency,
      )
      assertEquals(
        ReaderSessionAcceptance.Accepted(ReaderEventRecency.REORDERED),
        firstRecency,
      )
      assertEquals(setOf("A", "B"), readChapters)
      assertEquals("C", position)
    }

  private suspend fun issue(
    gate: ReaderSessionGate,
    session: ReaderSession,
  ): ReaderSessionEvent =
    (gate.issue(session) as ReaderSessionAcceptance.Accepted).value

  private companion object {
    const val TEST_TIMEOUT_MILLIS = 5_000L
  }
}
