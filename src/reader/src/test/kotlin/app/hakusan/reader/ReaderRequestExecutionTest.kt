package app.hakusan.reader

import app.hakusan.reader.ReaderContentTerminal.Canceled
import app.hakusan.reader.san.SanAbi
import app.hakusan.reader.san.SanBinding
import app.hakusan.reader.san.ScriptedSan
import app.hakusan.reader.san.ScriptedStep
import app.hakusan.reader.san.rowsOfFourBytes
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Executes content requests through the owner against the scripted engine, with
 * the real dispatchers. The engine fails a call that breaks a rule of the
 * header, so every test also checks the calls that it answered.
 */
class ReaderRequestExecutionTest {
  private val book = ReaderTestBook()
  private val san = ScriptedSan()
  private val source = TestSource()

  private val owner =
    ReaderResourceOwner(
      ReaderEngineSettings.FIRST_ITERATION,
      callsOn = { san },
      bind = { SanBinding.Established },
    )

  @Test
  fun `a request decodes to its completion and leaves nothing behind`() =
    readerTest {
      val requests = contentOwner()
      val input = complete()
      val acknowledged = CopyOnWriteArrayList<Boolean>()

      source.supply(input)
      san.script(rowsOfFourBytes(width = 4, height = 4))

      val request = submit(requests)

      san.onAnswer = { call ->
        if (call.name in SETTLEMENTS) {
          acknowledged += request.terminal != null
        }
      }

      assertSame(request, owner.execute(requests, source))
      assertEquals(completed(), request.terminal)
      assertArrayEquals(counting(16), san.contents.single().bytes())
      assertEquals(1, input.closeCount)
      assertEquals(SETTLEMENTS, san.calls.takeLast(3).map { it.name })
      assertEquals(listOf(false, false, false), acknowledged)
      assertEquals(null, owner.execute(requests, source))
      assertSettled()
    }

  @Test
  fun `input that arrives in stages is fed in order and never polled for`() =
    readerTest {
      val requests = contentOwner()
      val input = live()

      source.supply(input)
      san.script(rowsOfFourBytes(width = 4, height = 4))

      val request = submit(requests)
      val running = executing(requests)

      san.await { count("jobStep") >= 1 }
      input.receiveThrough(5)

      san.await {
        contents.single().receivedBytes == 5L && calls.last().name == "jobStep"
      }

      input.receiveThrough(16)
      input.end()

      assertSame(request, running.request())
      assertEquals(completed(), request.terminal)
      assertArrayEquals(counting(16), san.contents.single().bytes())
      assertSettled()
    }

  @Test
  fun `a read that arrives while the job progresses waits for its need`() =
    readerTest {
      val requests = contentOwner()
      val rows = rowsOfFourBytes(width = 4, height = 4)
      var progressed = false

      source.supply(complete())

      // Every portion takes two steps, and the next read is ready meanwhile.
      san.script {
        progressed = content.staged > 0 && !progressed

        if (progressed) SanAbi.STATUS_PROGRESS else rows()
      }

      val request = submit(requests)

      owner.execute(requests, source)

      assertEquals(completed(), request.terminal)
      assertTrue(san.calls.any { it.status == SanAbi.STATUS_PROGRESS })
      assertSettled()
    }

  @Test
  fun `a cancellation while the source opens ends before any engine call`() =
    readerTest {
      val requests = contentOwner()

      source.hold = CompletableDeferred()

      val request = submit(requests)
      val running = executing(requests)

      source.entered.receive()
      submit(requests, unit = 1)

      assertSame(request, running.request())
      assertEquals(Canceled, request.terminal)
      assertEquals(emptyList<Any>(), san.calls)
      assertFalse(owner.gate.isLocked)
    }

  @Test
  fun `a cancellation at the gate ends without an acquisition`() = readerTest {
    val requests = contentOwner()

    owner.gate.lock()

    val request = submit(requests)

    // It runs on this thread up to its first suspension, behind the take.
    val running =
      async(start = CoroutineStart.UNDISPATCHED) {
        owner.execute(requests, source)
      }

    requests.beginClose()

    assertSame(request, running.await())
    assertEquals(Canceled, request.terminal)
    assertEquals(0, source.opens.get())
    assertTrue(owner.gate.isLocked)
    owner.gate.unlock()
  }

  @Test
  fun `a cancellation in an input wait is collected by an immediate step`() =
    readerTest {
      val requests = contentOwner()
      val input = live()

      source.supply(input)
      san.script(rowsOfFourBytes(width = 4, height = 4))

      val request = submit(requests)
      val running = executing(requests)

      san.await { count("jobStep") >= 1 }
      requests.beginClose()

      assertSame(request, running.request())
      assertEquals(Canceled, request.terminal)
      assertEquals(1, input.closeCount)

      val worker = san.calls.first().thread
      val last = san.calls.takeLast(3)

      assertEquals(
        listOf("jobStep", "contentClose", "jobRelease"),
        last.map { it.name },
      )

      assertEquals(SanAbi.STATUS_CANCELED, last.first().status)
      assertTrue(
        san.calls.any { it.name == "jobCancel" && it.thread != worker }
      )
      assertSettled()
    }

  @Test
  fun `a cancellation during a step reaches the step from another thread`() =
    readerTest {
      val request = cancelDuringStep {
        if (canceled) SanAbi.STATUS_CANCELED else SanAbi.STATUS_INPUT_NEEDED
      }

      assertEquals(Canceled, request.terminal)
      assertEquals(1, san.count("jobStep"))
      assertSettled()
    }

  @Test
  fun `a cancellation that its step missed ends the job with no new input`() =
    readerTest {
      val request = cancelDuringStep { SanAbi.STATUS_INPUT_NEEDED }

      assertEquals(Canceled, request.terminal)
      assertEquals(0L, san.contents.single().receivedBytes)
      assertSettled()
    }

  @Test
  fun `a completion that its step accepted stands against a cancellation`() =
    readerTest {
      val request = cancelDuringStep {
        describe(4, 4)
        admit(4, 4)
        cover(4)

        SanAbi.STATUS_OK
      }

      assertTrue(request.cancellationRequested)
      assertEquals(completed(), request.terminal)
      assertSettled()
    }

  @Test
  fun `a cancellation between the acquisition and the job is not lost`() =
    readerTest {
      val requests = contentOwner()

      source.supply(live())
      san.script(rowsOfFourBytes(width = 4, height = 4))

      val request = submit(requests)

      san.onAnswer = { call ->
        if (call.name == "contentOpen") {
          requests.beginClose()
        }
      }

      owner.execute(requests, source)

      assertEquals(Canceled, request.terminal)
      assertSettled()
    }

  @Test
  fun `canceled requests leave the next one only its own bytes`() = readerTest {
    val requests = contentOwner()
    val full = complete()

    repeat(3) { source.supply(live()) }
    source.supply(full)
    repeat(4) { san.script(rowsOfFourBytes(width = 4, height = 4)) }

    var request = submit(requests)

    repeat(3) { index ->
      val running = executing(requests)

      san.await {
        calls.count { it.status == SanAbi.STATUS_INPUT_NEEDED } > index
      }

      val next = submit(requests, unit = (index + 1) % 3)

      assertSame(request, running.request())
      assertEquals(Canceled, request.terminal)
      request = next
    }

    assertSame(request, owner.execute(requests, source))
    assertEquals(completed(), request.terminal)

    assertEquals(
      listOf(0L, 0L, 0L, 16L),
      san.contents.map { it.receivedBytes },
    )

    assertArrayEquals(counting(16), san.contents.last().bytes())
    assertSettled()
  }

  @Test
  fun `a producer failure after a prefix ends as that failure`() = readerTest {
    val requests = contentOwner()
    val input = live()

    input.receiveThrough(6)
    input.fail(ReaderAcquisitionFailure.FAILED)
    source.supply(input)
    san.script(rowsOfFourBytes(width = 4, height = 4))

    val request = submit(requests)

    owner.execute(requests, source)

    assertEquals(failed(ReaderContentFailure.INPUT_FAILED), request.terminal)
    assertArrayEquals(counting(6), san.contents.single().bytes())
    assertEquals(
      SanAbi.STATUS_CANCELED,
      san.calls.last { it.name == "jobStep" }.status,
    )
    assertSettled()
  }

  @Test
  fun `the end of the session ends its executing request as canceled`() =
    readerTest {
      val gate = ReaderSessionGate()
      val session = gate.start()
      val requests = ReaderContentRequests(session, LIMITS)

      source.supply(live())
      san.script(rowsOfFourBytes(width = 4, height = 4))

      val request = submit(requests)
      val running = executing(requests)

      san.await { count("jobStep") >= 1 }
      gate.close(session)

      assertSame(request, running.request())
      assertEquals(Canceled, request.terminal)
      requests.awaitClosed()
      assertSettled()
    }

  @Test
  fun `a feed without memory ends as that failure with the content closed`() =
    readerTest {
      val requests = contentOwner()

      source.supply(complete())
      san.script(rowsOfFourBytes(width = 4, height = 4))
      san.failNext("contentFeed", SanAbi.STATUS_ALLOCATION_FAILED)

      val request = submit(requests)

      owner.execute(requests, source)

      assertEquals(
        failed(ReaderContentFailure.ALLOCATION_FAILED),
        request.terminal,
      )

      assertEquals(0L, san.contents.single().receivedBytes)
      assertSettled()
    }

  @Test
  fun `every terminal status of a job becomes its outcome`() = readerTest {
    val requests = contentOwner()
    val rows = rowsOfFourBytes(width = 4, height = 4)

    for ((status, failure) in FAILURES) {
      source.supply(complete())
      san.script { status }

      val request = submit(requests)

      owner.execute(requests, source)

      assertEquals(failed(failure), request.terminal)
    }

    source.supply(complete())

    san.script {
      warn(SanAbi.WARNING_SRGB_FALLBACK.toLong())
      rows()
    }

    val warned = submit(requests)

    owner.execute(requests, source)

    assertEquals(
      ReaderContentTerminal.Completed.of(
        setOf(ReaderContentWarning.SRGB_FALLBACK)
      ),
      warned.terminal,
    )

    assertSettled()
  }

  @Test
  fun `a refused content or job ends as its failure`() = readerTest {
    val requests = contentOwner()

    source.supply(complete())
    san.failNext("contentOpen", SanAbi.STATUS_BUDGET_EXCEEDED)

    val unopened = submit(requests)

    owner.execute(requests, source)

    assertEquals(
      failed(ReaderContentFailure.BUDGET_EXCEEDED),
      unopened.terminal,
    )
    assertEquals("contentOpen", san.calls.last().name)

    source.supply(complete())
    san.failNext("jobRequest", SanAbi.STATUS_UNSUPPORTED_OPERATION)

    val unrequested = submit(requests)

    owner.execute(requests, source)

    assertEquals(
      failed(ReaderContentFailure.UNSUPPORTED_OPERATION),
      unrequested.terminal,
    )

    assertEquals(
      listOf("jobRequest", "contentClose"),
      san.calls.takeLast(2).map { it.name },
    )

    assertSettled()
  }

  @Test
  fun `a job that completes before the end of its input closes the input`() =
    readerTest {
      val requests = contentOwner()
      val input = live()

      input.receiveThrough(16)
      source.supply(input)

      // A band of two rows of a four-row image.
      san.script {
        val rows = minOf((content.receivedBytes / 4).toInt(), 2)

        if (rows > 0 && lease == SanAbi.NO_HANDLE) {
          describe(4, 4)
          admit(4, 2)
        }

        if (rows > report[SanAbi.JOB_REPORT_COVERED_ROWS]) {
          cover(rows)
        }

        if (rows == 2) SanAbi.STATUS_OK else SanAbi.STATUS_INPUT_NEEDED
      }

      val request = submit(requests)

      owner.execute(requests, source)

      assertEquals(completed(), request.terminal)
      assertEquals(1, input.closeCount)
      assertTrue(san.contents.single().receivedBytes < 16L)
      assertFalse(san.contents.single().ended)
      assertSettled()
    }

  @Test
  fun `a status outside the contract throws after the cleanup`() = readerTest {
    val requests = contentOwner()

    source.supply(complete())
    san.script(rowsOfFourBytes(width = 4, height = 4))
    san.failNext("contentFeed", SanAbi.STATUS_BINDING_MISMATCH)

    val request = submit(requests)
    val failure = executing(requests).await().exceptionOrNull()

    assertInstanceOf(IllegalStateException::class.java, failure)
    assertEquals(failed(ReaderContentFailure.ENGINE_FAILURE), request.terminal)

    assertEquals(
      listOf("contentFeed", "contentClose", "jobRelease"),
      san.calls.takeLast(3).map { it.name },
    )

    assertSettled(defect = true)
  }

  @Test
  fun `a throwing step ends the request as an engine failure`() = readerTest {
    val requests = contentOwner()

    source.supply(complete())

    // The engine fails a script that progresses past no byte.
    san.script { SanAbi.STATUS_PROGRESS }

    val request = submit(requests)
    val failure = executing(requests).await().exceptionOrNull()

    assertInstanceOf(IllegalStateException::class.java, failure)
    assertEquals(failed(ReaderContentFailure.ENGINE_FAILURE), request.terminal)
    assertSettled()
  }

  @Test
  fun `an unavailable engine fails the request and closes its input`() =
    readerTest {
      val unavailable =
        ReaderResourceOwner(
          ReaderEngineSettings.FIRST_ITERATION,
          callsOn = { san },
          bind = { SanBinding.Mismatch(status = null) },
        )

      val requests = contentOwner()
      val input = complete()

      source.supply(input)

      val request = submit(requests)

      unavailable.execute(requests, source)

      assertEquals(
        failed(ReaderContentFailure.ENGINE_UNAVAILABLE),
        request.terminal,
      )

      assertEquals(1, input.closeCount)
      assertEquals(emptyList<Any>(), san.calls)
      unavailable.close()
    }

  @Test
  fun `a source that opens nothing fails the request before the engine`() =
    readerTest {
      val requests = contentOwner()

      source.results +=
        ReaderInputOpenResult.Failed(ReaderAcquisitionFailure.UNAVAILABLE)

      val request = submit(requests)

      owner.execute(requests, source)

      assertEquals(
        failed(ReaderContentFailure.INPUT_UNAVAILABLE),
        request.terminal,
      )

      assertEquals(emptyList<Any>(), san.calls)
      assertFalse(owner.gate.isLocked)
    }

  @Test
  fun `the gate admits one request across content owners`() = readerTest {
    val first = contentOwner()
    val second = contentOwner()
    val other = TestSource()
    val hold = Hold()
    val acknowledgedAtOpen = CompletableDeferred<Boolean>()

    source.supply(live())
    other.supply(complete())

    san.script {
      hold.block()

      SanAbi.STATUS_INVALID_CONTENT
    }

    san.script(rowsOfFourBytes(width = 4, height = 4))

    val held = submit(first)
    val waiting = submit(second)

    other.onOpen = { acknowledgedAtOpen.complete(held.terminal != null) }

    val running = executing(first)

    hold.entered.await()

    val queued = executing(second, other)

    // A gate that admitted the second request would let its source open now.
    delay(50.milliseconds)
    hold.resume.countDown()

    assertSame(held, running.request())
    assertSame(waiting, queued.request())
    assertTrue(acknowledgedAtOpen.await())
    assertEquals(completed(), waiting.terminal)
    assertEquals(2, san.contents.size)
    assertSettled()
  }

  @Test
  fun `a canceled caller leaves the request acknowledged and settled`() =
    readerTest {
      val requests = contentOwner()
      val input = live()

      source.supply(input)
      san.script(rowsOfFourBytes(width = 4, height = 4))

      val request = submit(requests)

      val thrown = CompletableDeferred<Throwable?>()

      val caller =
        launch(Dispatchers.Default) {
          val execution = runCatching { owner.execute(requests, source) }

          thrown.complete(execution.exceptionOrNull())
        }

      san.await { count("jobStep") >= 1 }
      caller.cancelAndJoin()

      assertInstanceOf(CancellationException::class.java, thrown.await())
      assertEquals(Canceled, request.terminal)
      assertEquals(1, input.closeCount)

      // No step ended the job, so the close of its content did.
      assertEquals(
        listOf("contentClose", "jobRelease"),
        san.calls.takeLast(2).map { it.name },
      )

      assertSettled()
    }

  @Test
  fun `the release of a job waits for a cancellation that still runs`() =
    readerTest {
      val requests = contentOwner()
      val input = live()
      val entered = CompletableDeferred<Unit>()
      val resume = CountDownLatch(1)

      source.supply(input)
      san.script(rowsOfFourBytes(width = 4, height = 4))

      val request = submit(requests)
      val running = executing(requests)

      san.await { count("jobStep") >= 1 }

      // The watcher's cancellation stays inside the engine, before its flag.
      san.duringCancel = {
        entered.complete(Unit)
        check(resume.await(BOUND_SECONDS, TimeUnit.SECONDS))
      }

      requests.beginClose()
      entered.await()
      input.receiveThrough(16)
      input.end()
      san.await { count("contentClose") >= 1 }

      // A release that did not wait would run now and break the call rule.
      delay(50.milliseconds)

      assertEquals(0, san.count("jobRelease"))
      resume.countDown()

      assertSame(request, running.request())
      assertEquals(completed(), request.terminal)
      assertSettled()
    }

  @Test
  fun `a cancellation that the input or the source throws is a defect`() =
    readerTest {
      val requests = contentOwner()
      val waiting = live()

      val input =
        object : ReaderByteInput by waiting {
          override suspend fun awaitInput() {
            throw CancellationException("A timeout inside the input.")
          }
        }

      source.supply(input)
      san.script(rowsOfFourBytes(width = 4, height = 4))

      val unread = submit(requests)
      val reading = executing(requests).await().exceptionOrNull()

      assertInstanceOf(IllegalStateException::class.java, reading)
      assertEquals(failed(ReaderContentFailure.ENGINE_FAILURE), unread.terminal)
      assertEquals(1, waiting.closeCount)

      val unopened = submit(requests)

      val opening = runCatching {
        owner.execute(requests) {
          throw CancellationException("A timeout inside the source.")
        }
      }
        .exceptionOrNull()

      assertInstanceOf(IllegalStateException::class.java, opening)

      assertEquals(
        failed(ReaderContentFailure.ENGINE_FAILURE),
        unopened.terminal,
      )

      assertSettled()
    }

  @Test
  fun `a request after the close of the owner is a defect`() = readerTest {
    val requests = contentOwner()

    owner.close()

    val request = submit(requests)
    val failure = executing(requests).await().exceptionOrNull()

    assertInstanceOf(IllegalStateException::class.java, failure)
    assertEquals(failed(ReaderContentFailure.ENGINE_FAILURE), request.terminal)
    assertEquals(0, source.opens.get())
    assertFalse(owner.gate.isLocked)
  }

  /**
   * Cancels a request while the first step of its job runs, lets the step
   * return [result] once the cancellation has reached the engine, and returns
   * the executed request.
   */
  private suspend fun CoroutineScope.cancelDuringStep(
    result: ScriptedStep
  ): ReaderContentRequest {
    val requests = contentOwner()
    val hold = Hold()

    source.supply(live())

    san.script {
      hold.block()
      result()
    }

    val request = submit(requests)
    val running = executing(requests)

    hold.entered.await()
    requests.beginClose()
    san.await { count("jobCancel") >= 1 }
    hold.resume.countDown()

    assertSame(request, running.request())

    return request
  }

  /**
   * Checks what every execution leaves: no broken call rule, no status that
   * reports a defect of the caller unless the test injected one, input fed only
   * after a need and never polled for, contiguous feeds, nothing live in the
   * engine, a free gate, and a shutdown that completes at once.
   */
  private suspend fun assertSettled(defect: Boolean = false) {
    assertEquals(emptyList<String>(), san.violations)

    if (!defect) {
      assertEquals(
        emptyList<Any>(),
        san.calls.filter { it.status in CALLER_DEFECTS },
      )
    }

    val serialized = san.calls.filter { it.name != "jobCancel" }

    for ((previous, call) in serialized.zipWithNext()) {
      val needed =
        previous.name == "jobStep" &&
          previous.status == SanAbi.STATUS_INPUT_NEEDED

      val collected = call.status == SanAbi.STATUS_CANCELED

      when (call.name) {
        "contentFeed" -> assertTrue(needed) { "A feed followed $previous." }

        "jobStep" ->
          assertTrue(!needed || collected) { "A step followed $previous." }
      }
    }

    for (content in san.contents) {
      val positions =
        content.feeds.runningFold(0L) { fed, feed ->
          fed + feed.length
        }

      assertEquals(positions.dropLast(1), content.feeds.map { it.position })
    }

    assertEquals(0, san.liveContentCount)
    assertEquals(0, san.ownedJobCount)
    assertEquals(0, san.heldLeaseCount)
    assertFalse(owner.gate.isLocked)

    owner.close()

    val last = san.calls.last()

    assertEquals("engineShutdown", last.name)
    assertEquals(SanAbi.STATUS_OK, last.status)
  }

  private suspend fun contentOwner(): ReaderContentRequests =
    ReaderContentRequests(ReaderSessionGate().start(), LIMITS)

  private fun submit(
    requests: ReaderContentRequests,
    unit: Int = 0,
  ): ReaderContentRequest = checkNotNull(requests.submit(book.descriptor(unit)))

  /** Executes off the test thread, and keeps a failure for the test. */
  private fun CoroutineScope.executing(
    requests: ReaderContentRequests,
    from: ReaderEncodedSource = source,
  ): Deferred<Result<ReaderContentRequest?>> =
    async(Dispatchers.Default) { runCatching { owner.execute(requests, from) } }

  private suspend fun Deferred<Result<ReaderContentRequest?>>.request():
    ReaderContentRequest? = await().getOrThrow()

  private fun failed(failure: ReaderContentFailure): ReaderContentTerminal =
    ReaderContentTerminal.Failed(failure)

  private fun ScriptedSan.count(name: String): Int = calls.count {
    it.name == name
  }

  /** An input whose [count] bytes arrived and whose producer completed. */
  private fun complete(count: Int = 16): ControlledReaderInput =
    live(count).apply {
      receiveThrough(count)
      end()
    }

  /** An input of [count] bytes that has received none and stays live. */
  private fun live(count: Int = 16): ControlledReaderInput =
    ControlledReaderInput(counting(count), maxTransfer = 5)

  private fun counting(count: Int): ByteArray = ByteArray(count) { it.toByte() }

  /** Blocks the step that calls [block] until the test counts [resume] down. */
  private class Hold {
    val entered = CompletableDeferred<Unit>()
    val resume = CountDownLatch(1)

    fun block() {
      entered.complete(Unit)
      check(resume.await(BOUND_SECONDS, TimeUnit.SECONDS))
    }
  }

  /** A source that hands out the queued results, one per opening. */
  private class TestSource : ReaderEncodedSource {
    val results = ConcurrentLinkedQueue<ReaderInputOpenResult>()
    val entered = Channel<Unit>(Channel.UNLIMITED)
    val opens = AtomicInteger()

    /** Suspends every opening until it completes, when set. */
    @Volatile var hold: CompletableDeferred<Unit>? = null

    @Volatile var onOpen: () -> Unit = {}

    fun supply(input: ReaderByteInput) {
      results += ReaderInputOpenResult.Opened(input)
    }

    override suspend fun open(
      descriptor: ReaderContentDescriptor
    ): ReaderInputOpenResult {
      opens.incrementAndGet()
      onOpen()
      entered.trySend(Unit)
      hold?.await()

      return checkNotNull(results.poll()) { "No result for an opening." }
    }
  }

  private companion object {
    const val BOUND_SECONDS = 5L
    val LIMITS = ReaderOutputLimits.FIRST_ITERATION
    val SETTLEMENTS = listOf("outputRelease", "contentClose", "jobRelease")

    val CALLER_DEFECTS =
      setOf(
        SanAbi.STATUS_INVALID_HANDLE,
        SanAbi.STATUS_CLOSED,
        SanAbi.STATUS_BINDING_MISMATCH,
      )

    val FAILURES =
      listOf(
        SanAbi.STATUS_UNSUPPORTED_FORMAT to
          ReaderContentFailure.UNSUPPORTED_FORMAT,
        SanAbi.STATUS_UNSUPPORTED_OPERATION to
          ReaderContentFailure.UNSUPPORTED_OPERATION,
        SanAbi.STATUS_INVALID_CONTENT to ReaderContentFailure.INVALID_CONTENT,
        SanAbi.STATUS_TRUNCATED_CONTENT to
          ReaderContentFailure.TRUNCATED_CONTENT,
        SanAbi.STATUS_BUDGET_EXCEEDED to ReaderContentFailure.BUDGET_EXCEEDED,
        SanAbi.STATUS_ALLOCATION_FAILED to
          ReaderContentFailure.ALLOCATION_FAILED,
        SanAbi.STATUS_ENGINE_FAILURE to ReaderContentFailure.ENGINE_FAILURE,
      )
  }
}
