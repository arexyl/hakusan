package app.hakusan.reader

import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Stress cases on the multi-threaded default dispatcher.
 *
 * Each session runs one executor, live input producers, and presentation
 * throughout. Submissions pass through four phases: burst submitters contend
 * with a submitter that waits for each request to resolve; that submitter then
 * continues alone, first while presentation is stalled; finally the session
 * ends while late requests are in flight. Interleavings vary between runs, and
 * every per-session assertion holds for each of them. A tally across sessions
 * checks that the run reached the paths it stresses.
 */
class ReaderContentConcurrencyTest {
  private val book = ReaderTestBook()

  @Test
  fun `concurrent requests settle every obligation once`() = readerStressTest {
    val tally = Tally()

    repeat(SESSIONS) { round ->
      Session(Random(round), tally, failingReleases = false).exercise()
    }

    tally.verifyReached(Path.entries - Path.OWNER_RELEASE_FAILURE)
  }

  @Test
  fun `concurrent release failures all reach one close-failure channel`() =
    readerStressTest {
      val tally = Tally()

      repeat(SESSIONS) { round ->
        val random = Random(SESSIONS + round)

        Session(random, tally, failingReleases = true).exercise()
      }

      tally.verifyReached(Path.entries)
    }

  /** One session with its content owner and the participants that use it. */
  private inner class Session(
    private val random: Random,
    private val tally: Tally,
    private val failingReleases: Boolean,
  ) {
    private val gate = ReaderSessionGate()
    private val limits = ReaderOutputLimits.FIRST_ITERATION
    private val submitted = ConcurrentLinkedQueue<ReaderContentRequest>()
    private val executed = ConcurrentLinkedQueue<ReaderContentRequest>()
    private val settlements =
      ConcurrentHashMap<Any, CompletableDeferred<Unit>>()
    private val pendingWork = Channel<Unit>(Channel.CONFLATED)
    private val publications = Channel<Unit>(Channel.CONFLATED)
    private val presenting = Mutex()

    suspend fun exercise() {
      val session = gate.start()

      val requests = ReaderContentRequests(session, limits)

      val closing = coroutineScope {
        val run = Run(this, random.nextInt(), tally, failingReleases)

        val execution = launch { execute(requests, run) }

        val presentation = launch {
          present(requests, Random(random.nextInt()))
        }

        contend(requests)
        presenting.withLock { settleAlone(requests, STALLED_SUBMISSIONS) }
        settleAlone(requests, ALONE_SUBMISSIONS)
        endInFlight(requests, session)
        pendingWork.close()
        execution.join()
        publications.close()
        presentation.join()

        val closing = runCatching { requests.awaitClosed() }.exceptionOrNull()

        run.verifyReleases(closing)
        run.verifyInputs()

        closing
      }

      tally.closed(closing)
      verifyTerminals()
    }

    /** Runs every waiting request as soon as work is signaled. */
    private suspend fun execute(requests: ReaderContentRequests, run: Run) {
      val executor = ControlledReaderExecutor(requests) { run.open(it) }

      while (pendingWork.receiveCatching().isSuccess) {
        while (true) {
          val request =
            executor.runNext { request, input ->
              run.decode(requests, request, input, publications)
            } ?: break

          executed += request
          settlement(request).complete(Unit)
        }
      }
    }

    /**
     * Takes publications as they arrive, at a varying pace, and keeps a random
     * number of them shown, releasing the oldest first. Releases every shown
     * output at the end.
     */
    private suspend fun present(
      requests: ReaderContentRequests,
      pace: Random,
    ) {
      val shown = ArrayDeque<ReaderOutputLease>()

      while (publications.receiveCatching().isSuccess) {
        presenting.withLock {
          while (true) {
            val publication = requests.takePublication() ?: break

            shown.addLast(publication.output)
            check(shown.size <= limits.liveOutputs)

            if (shown.size > pace.nextInt(1, limits.liveOutputs + 1)) {
              shown.removeFirst().close()
            }

            repeat(pace.nextInt(4)) { yield() }
          }
        }
      }

      shown.forEach { it.close() }
    }

    /** Burst submitters contend with a submitter that waits each time. */
    private suspend fun contend(requests: ReaderContentRequests) {
      coroutineScope {
        val bursts =
          List(BURSTS) {
            val submitting = Random(random.nextInt())

            launch {
              repeat(BURST_SUBMISSIONS) {
                submit(requests, submitting)
                repeat(submitting.nextInt(8)) { yield() }
              }
            }
          }

        val settler = launch { settleAlone(requests, CONTENDED_SUBMISSIONS) }

        (bursts + settler).joinAll()
      }
    }

    /** Submits [count] requests, waiting for each one to resolve. */
    private suspend fun settleAlone(
      requests: ReaderContentRequests,
      count: Int,
    ) {
      val submitting = Random(random.nextInt())

      repeat(count) {
        submit(requests, submitting)?.let { awaitResolution(it) }
      }
    }

    /** Ends the session while late requests are submitted and executing. */
    private suspend fun endInFlight(
      requests: ReaderContentRequests,
      session: ReaderSession,
    ) {
      coroutineScope {
        val submitting = Random(random.nextInt())

        val late = launch {
          repeat(BURST_SUBMISSIONS) {
            submit(requests, submitting)
            yield()
          }
        }

        repeat(random.nextInt(BURST_SUBMISSIONS)) { yield() }

        when (random.nextInt(3)) {
          0 -> gate.start()
          1 -> gate.close(session)
          else -> requests.beginClose()
        }

        late.join()
      }
    }

    private fun submit(
      requests: ReaderContentRequests,
      submitting: Random,
    ): ReaderContentRequest? {
      val unit = submitting.nextInt(3)

      val region = REGIONS.random(submitting)

      val request = requests.submit(book.descriptor(unit), region)

      if (request != null) {
        submitted += request
        pendingWork.trySend(Unit)
      }

      return request
    }

    /** Suspends until [request] has executed or its cancellation arrived. */
    private suspend fun awaitResolution(request: ReaderContentRequest) {
      val executed = settlement(request)

      coroutineScope {
        val canceled = async { request.awaitCancellationRequested() }

        select {
          executed.onAwait {}
          canceled.onAwait {}
        }

        canceled.cancel()
      }
    }

    private fun settlement(
      request: ReaderContentRequest
    ): CompletableDeferred<Unit> =
      settlements.computeIfAbsent(request) { CompletableDeferred() }

    /**
     * Checks that each executed request has exactly one terminal outcome and
     * that every other request received only its cancellation intent.
     */
    private fun verifyTerminals() {
      val executions =
        Collections.newSetFromMap(
          IdentityHashMap<ReaderContentRequest, Boolean>()
        )

      executions.addAll(executed)
      assertEquals(executed.size, executions.size)

      for (request in submitted) {
        if (request in executions) {
          tally.terminal(checkNotNull(request.terminal))
        } else {
          assertNull(request.terminal)
          assertTrue(request.cancellationRequested)
          tally.reached(Path.DROPPED)
        }
      }
    }
  }

  /** The paths whose coverage the tally checks. */
  private enum class Path {
    PUBLISHED,
    BUSY,
    STALE,
    DROPPED,
    COMPLETED,
    CANCELED,
    FAILED,
    OWNER_RELEASE_FAILURE,
  }

  /** How often the stress run reached each path, across sessions. */
  private class Tally {
    private val counts = ConcurrentHashMap<Path, AtomicInteger>()

    fun reached(path: Path) {
      counts.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
    }

    fun terminal(terminal: ReaderContentTerminal) {
      when (terminal) {
        is ReaderContentTerminal.Completed -> reached(Path.COMPLETED)
        ReaderContentTerminal.Canceled -> reached(Path.CANCELED)
        is ReaderContentTerminal.Failed -> reached(Path.FAILED)
      }
    }

    fun closed(failure: Throwable?) {
      if (failure != null) reached(Path.OWNER_RELEASE_FAILURE)
    }

    fun verifyReached(paths: Collection<Path>) {
      val missed = paths.filter { (counts[it]?.get() ?: 0) == 0 }

      assertEquals(emptyList<Path>(), missed)
    }
  }

  /** The scripted inputs and outputs of one session. */
  private class Run(
    private val scope: CoroutineScope,
    seed: Int,
    private val tally: Tally,
    private val failingReleases: Boolean,
  ) {
    private val random = Random(seed)
    private val monitor = Any()
    private val inputs = ArrayList<TrackedReaderInput>()
    private val releases = ArrayList<AtomicInteger>()
    private val failures = ArrayList<Throwable>()

    /**
     * Opens a new input after the previous one has closed. The input is either
     * complete at once or live, with a separate producer that supplies its
     * remaining bytes and its producer outcome later.
     */
    fun open(descriptor: ReaderContentDescriptor): ReaderInputOpenResult =
      synchronized(monitor) {
        check(inputs.all { it.closeCalls == 1 }) { "Acquisitions overlap." }

        if (descriptor.reference == "last" && random.nextInt(4) == 0) {
          return ReaderInputOpenResult.Failed(
            ReaderAcquisitionFailure.UNAVAILABLE
          )
        }

        val input =
          ControlledReaderInput(ByteArray(INPUT_BYTES) { it.toByte() })

        val tracked = TrackedReaderInput(input)

        val delay = random.nextInt(0, 8)

        val fails = random.nextInt(4) == 0

        inputs += tracked

        if (delay == 0) {
          input.receiveThrough(INPUT_BYTES)
          input.end()
        } else {
          input.receiveThrough(INPUT_BYTES / 2)
          scope.launch { produce(input, delay, fails) }
        }

        ReaderInputOpenResult.Opened(tracked)
      }

    /**
     * Reads [input] and publishes an output after each transfer. Some outputs
     * cover the whole image, which lies outside a bounded request region. A
     * busy output is discarded through the owner, and a stale one ends the
     * request.
     */
    suspend fun decode(
      requests: ReaderContentRequests,
      request: ReaderContentRequest,
      input: ReaderByteInput,
      publications: Channel<Unit>,
    ): ReaderContentTerminal {
      val buffer = ByteArray(2)

      while (true) {
        when (val read = input.read(buffer, 0, buffer.size)) {
          is ReaderInputRead.Data -> {}

          ReaderInputRead.AwaitInput -> {
            input.awaitInput()

            continue
          }

          else -> return outcome(read)
        }

        val output = lease(input.representation, request.region)

        when (requests.publish(request, output)) {
          ReaderPublishResult.PUBLISHED -> {
            tally.reached(Path.PUBLISHED)
            publications.trySend(Unit)
          }

          ReaderPublishResult.BUSY -> {
            tally.reached(Path.BUSY)
            requests.discard(request, output)
          }

          ReaderPublishResult.STALE -> {
            tally.reached(Path.STALE)

            return ReaderContentTerminal.Canceled
          }
        }

        yield()
      }
    }

    /**
     * Checks that every lease was released once and that the close-failure
     * channel carries exactly every injected release failure.
     */
    fun verifyReleases(closing: Throwable?) {
      val (counts, injected) =
        synchronized(monitor) { releases.toList() to failures.toList() }

      assertTrue(counts.all { it.get() == 1 })

      if (injected.isEmpty()) {
        assertNull(closing)
      } else {
        val failure =
          assertInstanceOf(ReaderOutputReleaseException::class.java, closing)

        val carried = listOf(failure.cause) + failure.suppressed

        assertEquals(injected.toSet(), carried.toSet())
        assertEquals(injected.size, carried.size)
      }
    }

    /** Checks that every opened input received exactly one close call. */
    fun verifyInputs() {
      synchronized(monitor) { assertTrue(inputs.all { it.closeCalls == 1 }) }
    }

    private suspend fun produce(
      input: ControlledReaderInput,
      delay: Int,
      fails: Boolean,
    ) {
      repeat(delay) { yield() }

      if (fails) {
        input.fail(ReaderAcquisitionFailure.FAILED)
      } else {
        input.receiveThrough(INPUT_BYTES)
        input.end()
      }
    }

    private fun lease(
      representation: ReaderRepresentation,
      region: ReaderImageRegion?,
    ): ReaderOutputLease {
      val count = AtomicInteger()

      val (covered, failure) =
        synchronized(monitor) {
          releases += count

          val covered =
            if (random.nextInt(16) == 0) FULL_REGION else region ?: WHOLE_REGION

          val failure =
            if (failingReleases && random.nextInt(4) == 0) {
              IllegalStateException("Injected release failure.")
            } else {
              null
            }

          failure?.let { failures += it }

          covered to failure
        }

      return testLease(representation, covered) {
        count.incrementAndGet()

        if (failure != null) throw failure
      }
    }

    private fun outcome(read: ReaderInputRead): ReaderContentTerminal =
      when (read) {
        ReaderInputRead.EndOfInput -> completed()

        is ReaderInputRead.InputFailure ->
          ReaderContentTerminal.Failed(read.failure.toContentFailure())

        else -> ReaderContentTerminal.Canceled
      }
  }

  private companion object {
    const val SESSIONS = 64
    const val BURSTS = 2
    const val BURST_SUBMISSIONS = 40
    const val CONTENDED_SUBMISSIONS = 30
    const val STALLED_SUBMISSIONS = 6
    const val ALONE_SUBMISSIONS = 12
    const val INPUT_BYTES = 8

    /** The coverage of an output for a request of the whole image. */
    val WHOLE_REGION = ReaderImageRegion(0, 0, 100, 400)

    /** A coverage outside the bounded request region of 400 rows. */
    val FULL_REGION = ReaderImageRegion(0, 0, 100, 4000)

    /** The request regions: the whole image and two bounded regions. */
    val REGIONS =
      listOf(
        null,
        ReaderImageRegion(0, 0, 100, 400),
        ReaderImageRegion(0, 0, 100, 4000),
      )
  }
}
