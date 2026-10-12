package app.hakusan.reader

import app.hakusan.reader.ReaderContentTerminal.Canceled
import app.hakusan.reader.ReaderPublishResult.BUSY
import app.hakusan.reader.ReaderPublishResult.PUBLISHED
import app.hakusan.reader.ReaderPublishResult.STALE
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrowsExactly
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderContentRequestsTest {
  private val book = ReaderTestBook()

  @Test
  fun `one request executes while the latest request waits`() = readerTest {
    val owner = TestOwner.start()

    val requests = owner.requests

    val first = checkNotNull(requests.submit(book.descriptor(0)))

    assertSame(first, requests.takeNext())

    val second = checkNotNull(requests.submit(book.descriptor(1)))

    assertTrue(first.cancellationRequested)
    first.awaitCancellationRequested()
    assertNull(requests.takeNext())

    val third = checkNotNull(requests.submit(book.descriptor(2)))

    assertTrue(second.cancellationRequested)
    second.awaitCancellationRequested()
    assertFalse(third.cancellationRequested)
    requests.acknowledgeTerminal(first, Canceled)
    assertSame(third, requests.takeNext())
    assertSame(Canceled, first.terminal)
    assertNull(second.terminal)
    assertNull(requests.takeNext())
  }

  @Test
  fun `the end of the session settles requests without a paired call`() =
    readerTest {
      for (end in SessionEnd.entries) {
        val owner = TestOwner.start()

        val requests = owner.requests

        val executing = checkNotNull(requests.submit(book.descriptor(0)))

        assertSame(executing, requests.takeNext())

        val waiting = checkNotNull(requests.submit(book.descriptor(1)))

        end.apply(owner)
        executing.awaitCancellationRequested()
        waiting.awaitCancellationRequested()
        assertNull(requests.submit(book.descriptor(2)))
        assertNull(requests.takeNext())

        val closing =
          async(start = CoroutineStart.UNDISPATCHED) { requests.awaitClosed() }

        assertFalse(closing.isCompleted)
        requests.acknowledgeTerminal(executing, Canceled)
        closing.await()
        assertNull(waiting.terminal)
        assertSame(Canceled, executing.terminal)
      }
    }

  @Test
  fun `an owner of an ended session admits nothing`() = readerTest {
    val gate = ReaderSessionGate()

    val ended = gate.start()

    gate.start()

    val requests =
      ReaderContentRequests(ended, ReaderOutputLimits.FIRST_ITERATION)

    assertNull(requests.submit(book.descriptor(0)))
    requests.awaitClosed()
  }

  @Test
  fun `a stale publication is released and ends publishing for its request`() =
    readerTest {
      for (stale in Staleness.entries) {
        val owner = TestOwner.start()

        val requests = owner.requests

        val bound = owner.execute(book.descriptor(0), REGION)

        var releases = 0

        val output = stale.output(owner, bound) { releases++ }

        assertEquals(STALE, requests.publish(bound.request, output))
        assertEquals(1, releases)

        if (stale.leavesRequestCurrent) {
          val valid = testLease(bound.representation) { releases++ }

          assertEquals(STALE, requests.publish(bound.request, valid))
          assertEquals(2, releases)
        }

        requests.acknowledgeTerminal(bound.request, Canceled)
        assertNull(requests.takePublication())
        requests.beginClose()
        requests.awaitClosed()
      }
    }

  @Test
  fun `a cancelled request binds no representation`() = readerTest {
    val owner = TestOwner.start()

    val requests = owner.requests

    val request = checkNotNull(requests.submit(book.descriptor(0)))

    assertSame(request, requests.takeNext())
    requests.submit(book.descriptor(1))
    assertFalse(requests.bindRepresentation(request, ReaderRepresentation()))

    var releases = 0

    val output = testLease { releases++ }

    assertEquals(STALE, requests.publish(request, output))
    assertEquals(1, releases)
  }

  @Test
  fun `a busy publication leaves the output with its producer`() = readerTest {
    val limits = ReaderOutputLimits(liveOutputs = 2, awaitingPublications = 1)

    val owner = TestOwner.start(limits)

    val requests = owner.requests

    val bound = owner.execute(book.descriptor(0))

    val releases = IntArray(3)

    val outputs =
      List(3) { index -> testLease(bound.representation) { releases[index]++ } }

    assertEquals(PUBLISHED, requests.publish(bound.request, outputs[0]))
    assertEquals(BUSY, requests.publish(bound.request, outputs[1]))
    assertTrue(outputs[1].claimable())

    val shown = checkNotNull(requests.takePublication())

    assertSame(outputs[0], shown.output)
    assertSame(bound.request, shown.request)
    assertEquals(PUBLISHED, requests.publish(bound.request, outputs[1]))
    assertSame(outputs[1], requests.takePublication()?.output)
    assertEquals(BUSY, requests.publish(bound.request, outputs[2]))
    outputs[0].close()
    assertEquals(PUBLISHED, requests.publish(bound.request, outputs[2]))
    assertEquals(listOf(1, 0, 0), releases.toList())
    requests.acknowledgeTerminal(bound.request, completed())
    requests.beginClose()
    outputs[1].close()
    requests.awaitClosed()
    assertEquals(listOf(1, 1, 1), releases.toList())
  }

  @Test
  fun `a failed release keeps counting against the live outputs`() =
    readerTest {
      val limits = ReaderOutputLimits(liveOutputs = 1, awaitingPublications = 1)

      val owner = TestOwner.start(limits)

      val requests = owner.requests

      val bound = owner.execute(book.descriptor(0))

      val failure = IllegalStateException("Injected release failure.")

      val failing = testLease(bound.representation) { throw failure }

      assertEquals(PUBLISHED, requests.publish(bound.request, failing))
      checkNotNull(requests.takePublication()).output.close()

      var releases = 0

      val next = testLease(bound.representation) { releases++ }

      assertEquals(BUSY, requests.publish(bound.request, next))
      requests.discard(bound.request, next)
      assertEquals(1, releases)
      requests.acknowledgeTerminal(bound.request, completed())
      requests.beginClose()

      val thrown =
        assertSuspendFails<ReaderOutputReleaseException> {
          requests.awaitClosed()
        }

      assertSame(failure, thrown.cause)
      assertEquals(emptyList<Throwable>(), thrown.suppressed.toList())
    }

  @Test
  fun `a discarded output reaches the close-failure channel`() = readerTest {
    val limits = ReaderOutputLimits(liveOutputs = 1, awaitingPublications = 1)

    val owner = TestOwner.start(limits)

    val requests = owner.requests

    val bound = owner.execute(book.descriptor(0))

    val shown = testLease(bound.representation) {}

    assertEquals(PUBLISHED, requests.publish(bound.request, shown))

    val failure = IllegalStateException("Injected release failure.")

    var releases = 0

    val busy =
      testLease(bound.representation) {
        releases++

        throw failure
      }

    assertEquals(BUSY, requests.publish(bound.request, busy))
    requests.discard(bound.request, busy)
    assertEquals(1, releases)
    assertFalse(busy.claimable())

    assertThrowsExactly(IllegalArgumentException::class.java) {
      requests.discard(bound.request, busy)
    }

    val unpublished = testLease(bound.representation) { releases++ }

    requests.discard(bound.request, unpublished)
    assertEquals(2, releases)
    requests.acknowledgeTerminal(bound.request, completed())
    checkNotNull(requests.takePublication()).output.close()
    requests.beginClose()

    val thrown =
      assertSuspendFails<ReaderOutputReleaseException> {
        requests.awaitClosed()
      }

    assertSame(failure, thrown.cause)
    assertEquals(2, releases)
  }

  @Test
  fun `a taken lease survives its request and holds closing open`() =
    readerTest {
      val owner = TestOwner.start()

      val requests = owner.requests

      val bound = owner.execute(book.descriptor(0))

      var releases = 0

      val output = testLease(bound.representation) { releases++ }

      assertEquals(PUBLISHED, requests.publish(bound.request, output))
      requests.acknowledgeTerminal(bound.request, completed())
      assertSame(output, requests.takePublication()?.output)
      requests.beginClose()
      owner.gate.close(owner.session)

      val closing =
        async(start = CoroutineStart.UNDISPATCHED) { requests.awaitClosed() }

      assertFalse(closing.isCompleted)
      assertEquals(0, releases)
      output.close()
      output.close()
      closing.await()
      assertEquals(1, releases)
    }

  @Test
  fun `release completion is observed through the owner`() = readerTest {
    val owner = TestOwner.start()

    val requests = owner.requests

    val bound = owner.execute(book.descriptor(0))

    val releasing = CountDownLatch(1)

    val proceed = CountDownLatch(1)

    val output =
      testLease(bound.representation) {
        releasing.countDown()
        check(proceed.await(BOUND_SECONDS, TimeUnit.SECONDS))
      }

    assertEquals(PUBLISHED, requests.publish(bound.request, output))
    assertSame(output, requests.takePublication()?.output)
    requests.acknowledgeTerminal(bound.request, completed())
    requests.beginClose()

    val first = thread { output.close() }

    assertTrue(releasing.await(BOUND_SECONDS, TimeUnit.SECONDS))
    output.close()

    val closing =
      async(start = CoroutineStart.UNDISPATCHED) { requests.awaitClosed() }

    assertFalse(closing.isCompleted)
    proceed.countDown()
    closing.await()
    first.join(TimeUnit.SECONDS.toMillis(BOUND_SECONDS))
    assertFalse(first.isAlive)
  }

  @Test
  fun `partial output and a failure coexist`() = readerTest {
    val owner = TestOwner.start()

    val requests = owner.requests

    val bound = owner.execute(book.descriptor(0))

    var releases = 0

    val partial =
      ReaderOutputLease(
        bound.representation,
        ReaderImageCoverage(100, 4000, REGION, finalQuality = false),
      ) {
        releases++
      }

    assertEquals(PUBLISHED, requests.publish(bound.request, partial))

    val failed =
      ReaderContentTerminal.Failed(ReaderContentFailure.TRUNCATED_CONTENT)

    requests.acknowledgeTerminal(bound.request, failed)
    assertEquals(failed, bound.request.terminal)
    assertSame(partial, requests.takePublication()?.output)
    assertEquals(0, releases)
    requests.beginClose()
    partial.close()
    requests.awaitClosed()
  }

  @Test
  fun `a failed release completes closing with its failure`() = readerTest {
    for (path in ReleasePath.entries) {
      val owner = TestOwner.start()

      val requests = owner.requests

      val bound = owner.execute(book.descriptor(0))

      val failure = IllegalStateException("Injected release failure.")

      var releases = 0

      val output =
        testLease(bound.representation) {
          releases++

          throw failure
        }

      assertEquals(PUBLISHED, requests.publish(bound.request, output))
      path.release(owner, output)
      assertEquals(1, releases)
      output.close()
      requests.beginClose()
      requests.beginClose()
      assertNull(requests.takePublication())
      requests.acknowledgeTerminal(bound.request, Canceled)

      val thrown =
        assertSuspendFails<ReaderOutputReleaseException> {
          requests.awaitClosed()
        }

      assertSame(failure, thrown.cause)
      assertEquals(1, releases)

      val repeated =
        assertSuspendFails<ReaderOutputReleaseException> {
          requests.awaitClosed()
        }

      assertSame(thrown, repeated)
    }
  }

  @Test
  fun `later release failures are suppressed into the first`() = readerTest {
    val owner = TestOwner.start()

    val requests = owner.requests

    val bound = owner.execute(book.descriptor(0))

    val failures = List(2) { IllegalStateException("Injected failure $it.") }

    val outputs = failures.map { testLease(bound.representation) { throw it } }

    for (output in outputs) {
      assertEquals(PUBLISHED, requests.publish(bound.request, output))
      checkNotNull(requests.takePublication()).output.close()
    }

    requests.acknowledgeTerminal(bound.request, completed())
    requests.beginClose()

    val thrown =
      assertSuspendFails<ReaderOutputReleaseException> {
        requests.awaitClosed()
      }

    assertSame(failures[0], thrown.cause)
    assertEquals(listOf<Throwable>(failures[1]), thrown.suppressed.toList())
  }

  @Test
  fun `observers are notified outside the monitor and before disposal`() =
    readerTest {
      for (path in WithdrawalPath.entries) {
        val owner = TestOwner.start()

        val requests = owner.requests

        val bound = owner.execute(book.descriptor(0))

        var releases = 0

        val output = testLease(bound.representation) { releases++ }

        assertEquals(PUBLISHED, requests.publish(bound.request, output))

        val observed = CompletableDeferred<Observation>()

        launch(Dispatchers.Unconfined) {
          bound.request.awaitCancellationRequested()

          val probe = thread { requests.takePublication() }

          probe.join(TimeUnit.SECONDS.toMillis(BOUND_SECONDS))

          val observation =
            Observation(releases, probe.isAlive, bound.request.terminal)

          observed.complete(observation)
        }

        path.apply(owner)
        assertEquals(Observation(0, false, null), observed.await())
        assertEquals(1, releases)
        requests.acknowledgeTerminal(bound.request, Canceled)
        requests.beginClose()
        requests.awaitClosed()
      }
    }

  @Test
  fun `protocol violations of the executor throw`() = readerTest {
    val owner = TestOwner.start()

    val requests = owner.requests

    val waiting = checkNotNull(requests.submit(book.descriptor(0)))

    assertThrowsExactly(IllegalArgumentException::class.java) {
      requests.acknowledgeTerminal(waiting, completed())
    }

    assertSame(waiting, requests.takeNext())

    val representation = ReaderRepresentation()

    assertTrue(requests.bindRepresentation(waiting, representation))

    assertThrowsExactly(IllegalArgumentException::class.java) {
      requests.bindRepresentation(waiting, representation)
    }

    val released = testLease {}

    released.close()

    assertThrowsExactly(IllegalArgumentException::class.java) {
      requests.publish(waiting, released)
    }

    val output = testLease(representation) {}

    assertEquals(PUBLISHED, requests.publish(waiting, output))

    assertThrowsExactly(IllegalArgumentException::class.java) {
      requests.publish(waiting, output)
    }

    assertThrowsExactly(IllegalArgumentException::class.java) {
      requests.discard(waiting, output)
    }

    requests.acknowledgeTerminal(waiting, completed())

    assertThrowsExactly(IllegalArgumentException::class.java) {
      requests.acknowledgeTerminal(waiting, completed())
    }

    var releases = 0

    val late = testLease(representation) { releases++ }

    assertThrowsExactly(IllegalArgumentException::class.java) {
      requests.publish(waiting, late)
    }

    assertThrowsExactly(IllegalArgumentException::class.java) {
      requests.discard(waiting, late)
    }

    assertEquals(0, releases)
    assertTrue(late.claimable())

    val other = TestOwner.start().requests

    assertThrowsExactly(IllegalArgumentException::class.java) {
      other.publish(waiting, late)
    }
  }

  @Test
  fun `output limits reject impossible settings`() {
    assertThrowsExactly(IllegalArgumentException::class.java) {
      ReaderOutputLimits(liveOutputs = 0, awaitingPublications = 1)
    }

    assertThrowsExactly(IllegalArgumentException::class.java) {
      ReaderOutputLimits(liveOutputs = 1, awaitingPublications = 2)
    }

    assertEquals(
      ReaderOutputLimits(liveOutputs = 4, awaitingPublications = 1),
      ReaderOutputLimits.FIRST_ITERATION,
    )
  }

  /** A gate, its session, and the content owner of that session. */
  private class TestOwner(
    val gate: ReaderSessionGate,
    val session: ReaderSession,
    val requests: ReaderContentRequests,
  ) {
    /** Submits, takes, and binds a request to a new representation. */
    fun execute(
      descriptor: ReaderContentDescriptor,
      region: ReaderImageRegion? = null,
    ): Bound {
      val request = checkNotNull(requests.submit(descriptor, region))

      check(requests.takeNext() === request)

      val representation = ReaderRepresentation()

      check(requests.bindRepresentation(request, representation))

      return Bound(request, representation)
    }

    companion object {
      suspend fun start(
        limits: ReaderOutputLimits = ReaderOutputLimits.FIRST_ITERATION
      ): TestOwner {
        val gate = ReaderSessionGate()

        val session = gate.start()

        return TestOwner(gate, session, ReaderContentRequests(session, limits))
      }
    }
  }

  /** An executing request and its bound representation. */
  private class Bound(
    val request: ReaderContentRequest,
    val representation: ReaderRepresentation,
  )

  /** What a cancellation observer saw when it was notified. */
  private data class Observation(
    val releases: Int,
    val monitorHeld: Boolean,
    val terminal: ReaderContentTerminal?,
  )

  /** The ways a session ends or its owner closes. */
  private enum class SessionEnd {
    REPLACED,
    CLOSED,
    OWNER_CLOSED;

    suspend fun apply(owner: TestOwner) {
      when (this) {
        REPLACED -> owner.gate.start()
        CLOSED -> owner.gate.close(owner.session)
        OWNER_CLOSED -> owner.requests.beginClose()
      }
    }
  }

  /** The ways a publication is stale. */
  private enum class Staleness(val leavesRequestCurrent: Boolean) {
    SUPERSEDED(false),
    SESSION_REPLACED(false),
    CLOSING(false),
    REPRESENTATION(true),
    REGION(true);

    suspend fun output(
      owner: TestOwner,
      bound: Bound,
      release: () -> Unit,
    ): ReaderOutputLease =
      when (this) {
        SUPERSEDED -> {
          owner.requests.submit(ReaderTestBook().descriptor(1))

          testLease(bound.representation, release = release)
        }

        SESSION_REPLACED -> {
          owner.gate.start()

          testLease(bound.representation, release = release)
        }

        CLOSING -> {
          owner.requests.beginClose()

          testLease(bound.representation, release = release)
        }

        REPRESENTATION -> testLease(release = release)

        REGION -> testLease(bound.representation, OUTSIDE, release)
      }
  }

  /** The paths through which a held lease is released. */
  private enum class ReleasePath {
    SUPERSESSION,
    OWNER_CLOSE,
    SESSION_END,
    PRESENTATION;

    suspend fun release(owner: TestOwner, output: ReaderOutputLease) {
      when (this) {
        SUPERSESSION ->
          checkNotNull(owner.requests.submit(ReaderTestBook().descriptor(1)))

        OWNER_CLOSE -> owner.requests.beginClose()

        SESSION_END -> owner.gate.start()

        PRESENTATION -> {
          check(owner.requests.takePublication()?.output === output)
          output.close()
        }
      }
    }
  }

  /** The paths that notify cancellation and then release queued outputs. */
  private enum class WithdrawalPath {
    SUPERSESSION,
    OWNER_CLOSE,
    SESSION_END;

    suspend fun apply(owner: TestOwner) {
      when (this) {
        SUPERSESSION ->
          checkNotNull(owner.requests.submit(ReaderTestBook().descriptor(1)))

        OWNER_CLOSE -> owner.requests.beginClose()

        SESSION_END -> owner.gate.close(owner.session)
      }
    }
  }

  private companion object {
    const val BOUND_SECONDS = 5L
    val REGION = ReaderImageRegion(0, 0, 100, 100)
    val OUTSIDE = ReaderImageRegion(0, 0, 100, 101)
  }
}
