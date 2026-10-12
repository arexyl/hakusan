package app.hakusan.reader

import app.hakusan.reader.ReaderContentTerminal.Canceled
import app.hakusan.reader.ReaderContentTerminal.Failed
import java.util.concurrent.Executor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderExecutionTest {
  private val book = ReaderTestBook()

  @Test
  fun `only the latest waiting input opens after the executing one closes`() =
    readerTest {
      val requests = owner()

      val first = ControlledReaderInput(byteArrayOf(1))

      val next = ControlledReaderInput(byteArrayOf(2))

      next.receiveThrough(1)
      next.end()

      val opened = ArrayList<Int>()

      val source = ReaderEncodedSource { descriptor ->
        val index = descriptor.position.unitIndex

        opened += index

        if (index == 0) {
          ReaderInputOpenResult.Opened(first)
        } else {
          check(first.closeCount == 1) { "The previous input is open." }

          ReaderInputOpenResult.Opened(next)
        }
      }

      val executor = ControlledReaderExecutor(requests, source)

      requests.submit(book.descriptor(0))

      val running =
        async(start = CoroutineStart.UNDISPATCHED) {
          executor.runNext { _, input -> awaitWithheld(input) }
        }

      val skipped = checkNotNull(requests.submit(book.descriptor(1)))

      requests.submit(book.descriptor(2))
      assertNull(executor.runNext { _, _ -> error("Work still executes.") })
      running.await()
      assertEquals(listOf(0), opened)
      assertNull(skipped.terminal)
      assertTrue(skipped.cancellationRequested)

      val completed = executor.runNext { _, input ->
        readToEnd(input, ByteArray(1))
      }

      assertEquals(completed(), checkNotNull(completed).terminal)
      assertEquals(listOf(0, 2), opened)
      assertEquals(1, next.closeCount)
      requests.beginClose()
      requests.awaitClosed()
    }

  @Test
  fun `cancellation settles acquisition before the terminal acknowledgment`() =
    readerTest {
      val requests = owner()

      val request = checkNotNull(requests.submit(book.descriptor(0)))

      val entered = CompletableDeferred<Unit>()

      val settledWith = ArrayList<ReaderContentTerminal?>()

      val source = ReaderEncodedSource {
        entered.complete(Unit)

        try {
          CompletableDeferred<ReaderInputOpenResult>().await()
        } finally {
          settledWith += request.terminal
        }
      }

      val executor = ControlledReaderExecutor(requests, source)

      val running =
        async(start = CoroutineStart.UNDISPATCHED) {
          executor.runNext { _, _ -> error("Acquisition never completes.") }
        }

      entered.await()
      requests.beginClose()
      running.await()
      assertEquals(listOf<ReaderContentTerminal?>(null), settledWith)
      assertSame(Canceled, request.terminal)
      requests.awaitClosed()
    }

  @Test
  fun `the input closes before the terminal acknowledgment on every path`() =
    readerTest {
      for (end in ExecutionEnd.entries) {
        val requests = owner()

        val request = checkNotNull(requests.submit(book.descriptor(0)))

        val controlled = ControlledReaderInput(byteArrayOf(1))

        val closedWith = ArrayList<ReaderContentTerminal?>()

        val input =
          TrackedReaderInput(controlled) {
            closedWith += request.terminal
          }

        end.prepare(controlled)

        val executor =
          ControlledReaderExecutor(requests) {
            ReaderInputOpenResult.Opened(input)
          }

        val started = CompletableDeferred<Unit>()

        val running =
          async(start = CoroutineStart.UNDISPATCHED) {
            try {
              executor.runNext { _, bytes ->
                started.complete(Unit)

                end.operate(bytes)
              }
            } catch (failure: InjectedFailure) {
              null
            }
          }

        started.await()
        end.interrupt(requests, running)
        running.join()
        assertEquals(listOf<ReaderContentTerminal?>(null), closedWith)
        assertEquals(1, input.closeCalls)
        assertEquals(end.terminal, request.terminal)
        requests.beginClose()
        requests.awaitClosed()
      }
    }

  @Test
  fun `caller cancellation propagates after owned input cleanup`() =
    readerTest {
      val requests = owner()

      val input = ControlledReaderInput(byteArrayOf(1))

      val executor =
        ControlledReaderExecutor(requests) {
          ReaderInputOpenResult.Opened(input)
        }

      val request = checkNotNull(requests.submit(book.descriptor(0)))

      val waiting = CompletableDeferred<Unit>()

      val running =
        async(start = CoroutineStart.UNDISPATCHED) {
          executor.runNext { _, bytes ->
            waiting.complete(Unit)

            awaitWithheld(bytes)
          }
        }

      waiting.await()
      running.cancelAndJoin()
      assertTrue(running.isCancelled)
      assertEquals(1, input.closeCount)
      assertSame(Canceled, request.terminal)
      requests.beginClose()
      requests.awaitClosed()
    }

  @Test
  fun `opening against cancellation intent closes the input exactly once`() =
    readerTest {
      val tasks = ArrayDeque<Runnable>()

      val requests = owner()

      val first = checkNotNull(requests.submit(book.descriptor(0)))

      val closedWith = ArrayList<ReaderContentTerminal?>()

      val source =
        WorkerSource(Executor(tasks::addLast).asCoroutineDispatcher()) {
          closedWith += first.terminal
        }

      val executor = ControlledReaderExecutor(requests, source)

      val running =
        async(start = CoroutineStart.UNDISPATCHED) {
          executor.runNext { _, _ -> error("The cancelled request ran.") }
        }

      yield()
      assertEquals(1, tasks.size)

      val second = checkNotNull(requests.submit(book.descriptor(1)))

      tasks.removeFirst().run()
      assertSame(first, running.await())
      assertSame(Canceled, first.terminal)
      assertEquals(listOf<ReaderContentTerminal?>(null), closedWith)
      assertEquals(1, source.acquired.single().closeCalls)
      assertEquals(1, source.closedBySource)
      assertFalse(second.cancellationRequested)
      requests.beginClose()
      requests.awaitClosed()
    }

  @Test
  fun `opening against caller cancellation closes the input exactly once`() =
    readerTest {
      val tasks = ArrayDeque<Runnable>()

      val requests = owner()

      val request = checkNotNull(requests.submit(book.descriptor(0)))

      val closedWith = ArrayList<ReaderContentTerminal?>()

      val source =
        WorkerSource(Executor(tasks::addLast).asCoroutineDispatcher()) {
          closedWith += request.terminal
        }

      val executor = ControlledReaderExecutor(requests, source)

      val running =
        async(start = CoroutineStart.UNDISPATCHED) {
          executor.runNext { _, _ -> error("The cancelled request ran.") }
        }

      yield()
      tasks.removeFirst().run()
      running.cancelAndJoin()
      assertTrue(running.isCancelled)
      assertTrue(tasks.isEmpty())
      assertSame(Canceled, request.terminal)
      assertEquals(listOf<ReaderContentTerminal?>(null), closedWith)
      assertEquals(1, source.acquired.single().closeCalls)
      assertEquals(1, source.closedBySource)
      requests.beginClose()
      requests.awaitClosed()
    }

  @Test
  fun `a replaced session settles executing work without a close call`() =
    readerTest {
      val gate = ReaderSessionGate()

      val requests =
        ReaderContentRequests(gate.start(), ReaderOutputLimits.FIRST_ITERATION)

      val input = ControlledReaderInput(byteArrayOf(1, 2))

      input.receiveThrough(1)

      val executor =
        ControlledReaderExecutor(requests) {
          ReaderInputOpenResult.Opened(input)
        }

      val request = checkNotNull(requests.submit(book.descriptor(0)))

      val waiting = CompletableDeferred<Unit>()

      val running =
        async(start = CoroutineStart.UNDISPATCHED) {
          executor.runNext { _, bytes ->
            assertEquals(
              ReaderInputRead.Data(1),
              bytes.read(ByteArray(1), 0, 1),
            )

            waiting.complete(Unit)

            awaitWithheld(bytes)
          }
        }

      waiting.await()
      gate.start()
      assertSame(request, running.await())
      assertSame(Canceled, request.terminal)
      assertEquals(1, input.closeCount)
      requests.awaitClosed()
    }

  @Test
  fun `a closed session settles an opening without a close call`() =
    readerTest {
      val gate = ReaderSessionGate()

      val session = gate.start()

      val requests =
        ReaderContentRequests(session, ReaderOutputLimits.FIRST_ITERATION)

      val entered = CompletableDeferred<Unit>()

      val withheld = CompletableDeferred<ReaderInputOpenResult>()

      val executor =
        ControlledReaderExecutor(requests) {
          entered.complete(Unit)
          withheld.await()
        }

      val request = checkNotNull(requests.submit(book.descriptor(0)))

      val running =
        async(start = CoroutineStart.UNDISPATCHED) {
          executor.runNext { _, _ -> error("Acquisition never completes.") }
        }

      entered.await()
      gate.close(session)
      running.await()
      assertSame(Canceled, request.terminal)
      requests.awaitClosed()
    }

  @Test
  fun `acquisition failures and warnings reach the terminal outcome`() =
    readerTest {
      val requests = owner()

      val failing = ControlledReaderInput(byteArrayOf(1))

      failing.fail(ReaderAcquisitionFailure.FAILED)

      val complete = ControlledReaderInput(byteArrayOf(1))

      complete.receiveThrough(1)
      complete.end()

      val inputs = ArrayDeque(listOf(failing, complete))

      val source = ReaderEncodedSource { descriptor ->
        if (descriptor.reference == "last") {
          ReaderInputOpenResult.Failed(ReaderAcquisitionFailure.UNAVAILABLE)
        } else {
          ReaderInputOpenResult.Opened(inputs.removeFirst())
        }
      }

      val executor = ControlledReaderExecutor(requests, source)

      val warned =
        ReaderContentTerminal.Completed.of(
          listOf(ReaderContentWarning.SRGB_FALLBACK)
        )

      val expected =
        listOf(
          ReaderContentTerminal.Failed(ReaderContentFailure.INPUT_UNAVAILABLE),
          ReaderContentTerminal.Failed(ReaderContentFailure.INPUT_FAILED),
          warned,
        )

      val terminals =
        listOf(2, 0, 1).map { unit ->
          requests.submit(book.descriptor(unit))

          val request = executor.runNext { _, input ->
            val end = readToEnd(input, ByteArray(1))

            if (end == completed()) warned else end
          }

          checkNotNull(request).terminal
        }

      assertEquals(expected, terminals)
      assertEquals(1, failing.closeCount)
      assertEquals(1, complete.closeCount)
      requests.beginClose()
      requests.awaitClosed()
    }

  @Test
  fun `supersession wakes executing work when a queued release fails`() =
    readerTest {
      val requests = owner()

      val input = ControlledReaderInput(byteArrayOf(1, 2))

      input.receiveThrough(1)

      var opens = 0

      val executor =
        ControlledReaderExecutor(requests) {
          opens++

          ReaderInputOpenResult.Opened(input)
        }

      val failure = IllegalStateException("Injected release failure.")

      var releases = 0

      val published = CompletableDeferred<ReaderContentRequest>()

      val stopping = CompletableDeferred<Unit>()

      val cleanup = CompletableDeferred<Unit>()

      requests.submit(book.descriptor(0))

      val running =
        async(start = CoroutineStart.UNDISPATCHED) {
          executor.runNext { request, bytes ->
            assertEquals(
              ReaderInputRead.Data(1),
              bytes.read(ByteArray(1), 0, 1),
            )

            val lease =
              testLease(bytes.representation) {
                releases++

                throw failure
              }

            assertEquals(
              ReaderPublishResult.PUBLISHED,
              requests.publish(request, lease),
            )

            published.complete(request)

            try {
              awaitWithheld(bytes)
            } finally {
              withContext(NonCancellable) {
                stopping.complete(Unit)
                cleanup.await()
              }
            }
          }
        }

      val request = published.await()

      checkNotNull(requests.submit(book.descriptor(1)))
      stopping.await()
      assertEquals(1, releases)
      assertTrue(request.cancellationRequested)
      assertNull(request.terminal)
      assertEquals(0, input.closeCount)
      assertNull(executor.runNext { _, _ -> error("Premature replacement.") })
      assertEquals(1, opens)
      cleanup.complete(Unit)
      assertSame(request, running.await())
      assertSame(Canceled, request.terminal)
      assertEquals(1, input.closeCount)
      request.awaitCancellationRequested()
      requests.beginClose()

      val thrown =
        assertSuspendFails<ReaderOutputReleaseException> {
          requests.awaitClosed()
        }

      assertSame(failure, thrown.cause)
      assertEquals(1, releases)
    }

  /** The ways an executed request ends. */
  private enum class ExecutionEnd(val terminal: ReaderContentTerminal) {
    COMPLETED(completed()),
    CANCELLATION_INTENT(Canceled),
    CALLER_CANCELLATION(Canceled),
    OPERATION_FAILURE(Failed(ReaderContentFailure.ENGINE_FAILURE));

    /** Supplies the input of a request that reads to its end. */
    fun prepare(input: ControlledReaderInput) {
      if (this == COMPLETED) {
        input.receiveThrough(1)
        input.end()
      }
    }

    suspend fun operate(input: ReaderByteInput): ReaderContentTerminal =
      when (this) {
        COMPLETED -> readToEnd(input, ByteArray(1))
        OPERATION_FAILURE -> throw InjectedFailure()
        CANCELLATION_INTENT,
        CALLER_CANCELLATION -> awaitWithheld(input)
      }

    /** Ends an operation that has started and waits. */
    suspend fun interrupt(requests: ReaderContentRequests, running: Job) {
      when (this) {
        CANCELLATION_INTENT ->
          checkNotNull(requests.submit(ReaderTestBook().descriptor(1)))

        CALLER_CANCELLATION -> running.cancelAndJoin()

        COMPLETED,
        OPERATION_FAILURE -> {}
      }
    }
  }

  private class InjectedFailure : RuntimeException("Injected failure.")

  private suspend fun owner(): ReaderContentRequests =
    ReaderContentRequests(
      ReaderSessionGate().start(),
      ReaderOutputLimits.FIRST_ITERATION,
    )

  /**
   * Acquires on a worker, as an I/O adapter does, and keeps the acquired input
   * reachable outside the cancellable suspension. It closes that input when
   * cancellation discards the result of the suspension. Each close call of an
   * acquired input runs [onClose] first.
   */
  private class WorkerSource(
    private val worker: CoroutineContext,
    private val onClose: () -> Unit,
  ) : ReaderEncodedSource {
    val acquired = ArrayList<TrackedReaderInput>()

    var closedBySource = 0
      private set

    override suspend fun open(
      descriptor: ReaderContentDescriptor
    ): ReaderInputOpenResult {
      var input: TrackedReaderInput? = null

      try {
        withContext(worker) {
          val acquisition =
            TrackedReaderInput(ControlledReaderInput(byteArrayOf(1)), onClose)

          acquired += acquisition
          input = acquisition
        }
      } catch (cancellation: CancellationException) {
        input?.let {
          it.close()
          closedBySource++
        }

        throw cancellation
      }

      return ReaderInputOpenResult.Opened(checkNotNull(input))
    }
  }
}
