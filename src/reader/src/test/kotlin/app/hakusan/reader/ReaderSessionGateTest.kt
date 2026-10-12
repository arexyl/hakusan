package app.hakusan.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderSessionGateTest {
  @Test
  fun `a new session rejects the previous session and its events`() =
    readerTest {
      val gate = ReaderSessionGate()

      val first = gate.start()

      val firstEvent = gate.issue(first).accepted()

      assertEquals(CURRENT, gate.withCurrent(firstEvent) { it })

      val second = gate.start()

      var staleBlockRan = false

      assertSame(
        ReaderSessionAcceptance.RejectedNotCurrent,
        gate.withCurrent(firstEvent) { staleBlockRan = true },
      )

      assertSame(ReaderSessionAcceptance.RejectedNotCurrent, gate.issue(first))
      assertFalse(staleBlockRan)
      gate.close(first)

      val secondEvent = gate.issue(second).accepted()

      assertEquals(0L, secondEvent.ordinal)
      assertEquals(CURRENT, gate.withCurrent(secondEvent) { it })
    }

  @Test
  fun `a session or event of another gate is rejected`() = readerTest {
    val firstGate = ReaderSessionGate()

    val secondGate = ReaderSessionGate()

    val foreign = firstGate.start()

    val foreignEvent = firstGate.issue(foreign).accepted()

    val session = secondGate.start()

    assertSame(
      ReaderSessionAcceptance.RejectedNotCurrent,
      secondGate.withCurrent(foreignEvent) { "unexpected" },
    )

    assertSame(
      ReaderSessionAcceptance.RejectedNotCurrent,
      secondGate.issue(foreign),
    )

    secondGate.close(foreign)

    val event = secondGate.issue(session).accepted()

    assertEquals(CURRENT, secondGate.withCurrent(event) { it })
    assertEquals(CURRENT, firstGate.withCurrent(foreignEvent) { it })
  }

  @Test
  fun `events delivered in issue order are each current`() = readerTest {
    val gate = ReaderSessionGate()

    val session = gate.start()

    val first = gate.issue(session).accepted()

    val second = gate.issue(session).accepted()

    assertEquals(CURRENT, gate.withCurrent(first) { it })
    assertEquals(CURRENT, gate.withCurrent(second) { it })
  }

  @Test
  fun `replacement waits for accepted work to finish`() = readerTest {
    val gate = ReaderSessionGate()

    val first = gate.start()

    val event = gate.issue(first).accepted()

    val entered = CompletableDeferred<Unit>()

    val release = CompletableDeferred<Unit>()

    var ended = false

    first.invokeOnEnd { ended = true }

    val work =
      async(start = CoroutineStart.UNDISPATCHED) {
        gate.withCurrent(event) {
          entered.complete(Unit)
          release.await()

          "committed"
        }
      }

    entered.await()

    val replacement =
      async(start = CoroutineStart.UNDISPATCHED) { gate.start() }

    assertFalse(replacement.isCompleted)
    assertFalse(ended)
    release.complete(Unit)
    assertEquals(ReaderSessionAcceptance.Accepted("committed"), work.await())
    replacement.await()
    assertTrue(ended)

    assertSame(
      ReaderSessionAcceptance.RejectedNotCurrent,
      gate.withCurrent(event) { "stale" },
    )
  }

  @Test
  fun `waiting deliveries and a queued ending run in arrival order`() =
    readerTest {
      val gate = ReaderSessionGate()

      val endings: List<suspend (ReaderSession) -> Unit> =
        listOf({ gate.close(it) }, { gate.start() })

      for (end in endings) {
        val session = gate.start()

        val holder = gate.issue(session).accepted()

        val older = gate.issue(session).accepted()

        val newer = gate.issue(session).accepted()

        val order = mutableListOf<String>()

        val entered = CompletableDeferred<Unit>()

        val release = CompletableDeferred<Unit>()

        session.invokeOnEnd { order += "end" }

        val held =
          async(start = CoroutineStart.UNDISPATCHED) {
            gate.withCurrent(holder) {
              entered.complete(Unit)
              release.await()
            }
          }

        entered.await()

        val waiting =
          listOf(newer, older).map { event ->
            async(start = CoroutineStart.UNDISPATCHED) {
              gate.withCurrent(event) { recency ->
                order += "ordinal ${event.ordinal}"

                recency
              }
            }
          }

        val ending =
          launch(start = CoroutineStart.UNDISPATCHED) { end(session) }

        assertFalse(ending.isCompleted)
        release.complete(Unit)
        held.await()
        ending.join()
        assertEquals(listOf(CURRENT, REORDERED), waiting.awaitAll())
        assertEquals(listOf("ordinal 2", "ordinal 1", "end"), order)
      }
    }

  @Test
  fun `close waits for accepted work before rejecting its event`() =
    readerTest {
      val gate = ReaderSessionGate()

      val session = gate.start()

      val event = gate.issue(session).accepted()

      val entered = CompletableDeferred<Unit>()

      val release = CompletableDeferred<Unit>()

      val work =
        async(start = CoroutineStart.UNDISPATCHED) {
          gate.withCurrent(event) {
            entered.complete(Unit)
            release.await()

            "committed"
          }
        }

      entered.await()

      val close =
        async(start = CoroutineStart.UNDISPATCHED) { gate.close(session) }

      assertFalse(close.isCompleted)
      release.complete(Unit)
      assertEquals(ReaderSessionAcceptance.Accepted("committed"), work.await())
      close.await()

      var closedBlockRan = false

      assertSame(
        ReaderSessionAcceptance.RejectedNotCurrent,
        gate.withCurrent(event) { closedBlockRan = true },
      )

      assertFalse(closedBlockRan)
    }

  @Test
  fun `close rejects the current session and repeats as a no-op`() =
    readerTest {
      val gate = ReaderSessionGate()

      val session = gate.start()

      val event = gate.issue(session).accepted()

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
  fun `a failure releases the lease without committing the event`() =
    readerTest {
      val gate = ReaderSessionGate()

      val session = gate.start()

      val event = gate.issue(session).accepted()

      assertFailsExactly<IllegalStateException> {
        gate.withCurrent(event) { error("injected failure") }
      }

      assertEquals(CURRENT, gate.withCurrent(event) { it })
      assertEquals(REORDERED, gate.withCurrent(event) { it })
    }

  @Test
  fun `a failed older event is reordered after a newer commit`() = readerTest {
    val gate = ReaderSessionGate()

    val session = gate.start()

    val older = gate.issue(session).accepted()

    val newer = gate.issue(session).accepted()

    assertFailsExactly<IllegalStateException> {
      gate.withCurrent(older) { error("injected failure") }
    }

    assertEquals(CURRENT, gate.withCurrent(newer) { it })
    assertEquals(REORDERED, gate.withCurrent(older) { it })
  }

  @Test
  fun `a cancellation releases the lease without committing the event`() =
    readerTest {
      val gate = ReaderSessionGate()

      val session = gate.start()

      val event = gate.issue(session).accepted()

      val entered = CompletableDeferred<Unit>()

      val work =
        async(start = CoroutineStart.UNDISPATCHED) {
          gate.withCurrent(event) {
            entered.complete(Unit)
            awaitCancellation()
          }
        }

      entered.await()
      work.cancelAndJoin()
      assertEquals(CURRENT, gate.withCurrent(event) { it })
    }

  @Test
  fun `a delivery canceled while it waits never runs its block`() = readerTest {
    val gate = ReaderSessionGate()

    val session = gate.start()

    val first = gate.issue(session).accepted()

    val waiting = gate.issue(session).accepted()

    val entered = CompletableDeferred<Unit>()

    val release = CompletableDeferred<Unit>()

    val accepted =
      async(start = CoroutineStart.UNDISPATCHED) {
        gate.withCurrent(first) {
          entered.complete(Unit)
          release.await()

          it
        }
      }

    entered.await()

    var waitingBlockRan = false

    val waitingWork =
      async(start = CoroutineStart.UNDISPATCHED) {
        gate.withCurrent(waiting) { waitingBlockRan = true }
      }

    assertFalse(waitingWork.isCompleted)
    waitingWork.cancelAndJoin()
    assertFalse(waitingBlockRan)
    release.complete(Unit)
    assertEquals(CURRENT, accepted.await())
    assertEquals(CURRENT, gate.withCurrent(waiting) { it })
  }

  @Test
  fun `a canceled older event is reordered after a newer commit`() =
    readerTest {
      val gate = ReaderSessionGate()

      val session = gate.start()

      val older = gate.issue(session).accepted()

      val newer = gate.issue(session).accepted()

      val entered = CompletableDeferred<Unit>()

      val work =
        async(start = CoroutineStart.UNDISPATCHED) {
          gate.withCurrent(older) {
            entered.complete(Unit)
            awaitCancellation()
          }
        }

      entered.await()
      work.cancelAndJoin()
      assertEquals(CURRENT, gate.withCurrent(newer) { it })
      assertEquals(REORDERED, gate.withCurrent(older) { it })
    }

  @Test
  fun `a cancellation after the block completed still commits the event`() =
    readerTest {
      val gate = ReaderSessionGate()

      val session = gate.start()

      val event = gate.issue(session).accepted()

      val outcome = CompletableDeferred<Throwable?>()

      val work =
        launch(start = CoroutineStart.UNDISPATCHED) {
          val delivery = coroutineContext.job

          val failure = runCatching {
            gate.withCurrent(event) { delivery.cancel() }
          }
            .exceptionOrNull()

          outcome.complete(failure)
        }

      work.join()
      assertInstanceOf(CancellationException::class.java, outcome.await())
      assertEquals(REORDERED, gate.withCurrent(event) { it })
    }

  @Test
  fun `reordered events keep the newer position and the older read effect`() =
    readerTest {
      val gate = ReaderSessionGate()

      val session = gate.start()

      val first = gate.issue(session).accepted()

      val second = gate.issue(session).accepted()

      val read = mutableSetOf<String>()

      var position = "A"

      val secondRecency =
        gate.withCurrent(second) { recency ->
          read += "B"

          if (recency == ReaderEventRecency.CURRENT) {
            position = "C"
          }

          recency
        }

      val firstRecency =
        gate.withCurrent(first) { recency ->
          read += "A"

          if (recency == ReaderEventRecency.CURRENT) {
            position = "B"
          }

          recency
        }

      assertEquals(CURRENT, secondRecency)
      assertEquals(REORDERED, firstRecency)
      assertEquals(setOf("A", "B"), read)
      assertEquals("C", position)
    }

  @Test
  fun `the gate ends each closed or replaced session once`() = readerTest {
    val gate = ReaderSessionGate()

    val ended = mutableListOf<String>()

    val first = gate.start()

    first.invokeOnEnd { ended += "first" }

    val second = gate.start()

    second.invokeOnEnd { ended += "second" }
    assertEquals(listOf("first"), ended)
    gate.close(first)
    ReaderSessionGate().close(second)
    assertEquals(listOf("first"), ended)
    gate.close(second)
    gate.close(second)
    gate.start()
    assertEquals(listOf("first", "second"), ended)
    first.invokeOnEnd { ended += "late" }
    assertEquals(listOf("first", "second", "late"), ended)
  }

  @Test
  fun `an accepted block cannot reenter its gate`() = readerTest {
    val gate = ReaderSessionGate()

    val session = gate.start()

    val event = gate.issue(session).accepted()

    val navigation = ReaderNavigation(gate, session, ReaderTestBook().input)

    val reentries: List<suspend () -> Unit> =
      listOf(
        { gate.start() },
        { gate.close(session) },
        { gate.issue(session) },
        { gate.withCurrent(event) {} },
        { navigation.request(navigation.input.chapters[0].key) },
      )

    for (reentry in reentries) {
      assertFailsExactly<IllegalStateException> {
        gate.withCurrent(event) { reentry() }
      }
    }

    assertEquals(CURRENT, gate.withCurrent(event) { it })
    assertEquals(1L, gate.issue(session).accepted().ordinal)
  }

  @Test
  fun `a coroutine started by an accepted block cannot reenter its gate`() =
    readerTest {
      val gate = ReaderSessionGate()

      val session = gate.start()

      val event = gate.issue(session).accepted()

      assertFailsExactly<IllegalStateException> {
        gate.withCurrent(event) {
          withContext(Dispatchers.Default) {
            coroutineScope {
              launch { gate.close(session) }
            }
          }
        }
      }

      assertEquals(CURRENT, gate.withCurrent(event) { it })
    }

  @Test
  fun `an accepted block may deliver through another gate`() = readerTest {
    val gate = ReaderSessionGate()

    val other = ReaderSessionGate()

    val event = gate.issue(gate.start()).accepted()

    val otherEvent = other.issue(other.start()).accepted()

    val nested =
      gate.withCurrent(event) { other.withCurrent(otherEvent) { it } }

    assertEquals(ReaderSessionAcceptance.Accepted(CURRENT), nested)

    assertFailsExactly<IllegalStateException> {
      gate.withCurrent(event) {
        other.withCurrent(otherEvent) { gate.start() }
      }
    }
  }

  private companion object {
    val CURRENT = ReaderSessionAcceptance.Accepted(ReaderEventRecency.CURRENT)
    val REORDERED =
      ReaderSessionAcceptance.Accepted(ReaderEventRecency.REORDERED)
  }
}
