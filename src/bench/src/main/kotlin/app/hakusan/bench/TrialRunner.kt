package app.hakusan.bench

import android.os.Process
import android.os.SystemClock
import androidx.compose.runtime.withFrameNanos
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runs one block: the warmups and the measured attempts of one cell on one
 * backend, each attempt cold.
 *
 * The runner is the controller of every request. It marks the request before
 * the backend opens its input, so acquisition lies inside the timed interval.
 * Its clock values only cross-check the trace: the display times come from the
 * presentation of the frames that the trace records.
 *
 * [run] executes on the main thread in a context with a frame clock.
 */
internal class TrialRunner(
  private val plan: Plan,
  private val corpus: Corpus,
  private val consumer: DisplayConsumer,
  private val conditions: Conditions,
) {
  private val executor = Executors.newSingleThreadExecutor {
    Thread(it, Conditions.DECODE_THREAD)
  }

  private val decode = executor.asCoroutineDispatcher()

  // A decode cannot be interrupted, so it runs outside the request that
  // waits for it, and a request can end while its decode still runs.
  private val decodes = CoroutineScope(SupervisorJob() + decode)

  private val requests = JSONArray()

  // The name of the record seeds the offsets, so a block repeats its own.
  private val offsets = Random(plan.record.hashCode())

  // Whether the kernel resets the peak of the resident memory for this
  // process, which the first reset of a block that reads it shows.
  private var resets = false

  /**
   * The record of the block. It is complete once [run] has returned or thrown,
   * and it then states how the block ended.
   */
  val record: JSONObject =
    JSONObject()
      .put("schema", SCHEMA)
      .put("cell", plan.cell.id)
      .put("backend", plan.backend.id)
      .put("fixture", plan.cell.fixture)
      .put("warmups", plan.warmups)
      .put("attempts", plan.attempts)
      .put("settle_ms", plan.settle.inWholeMilliseconds)
      .put("hold", plan.hold)
      .put("refresh_hz", plan.refresh)
      .put("requests", requests)

  /** The input of the running attempt and the decode of its last request. */
  private class Session {
    @Volatile var content: Content? = null
    var decoding: Deferred<Decoded>? = null

    /** The entry of the last request, which takes the readings of the close. */
    var entry: JSONObject? = null
  }

  /**
   * The last output of a request, with the counters of its backend, where it
   * has them, and with its report.
   */
  private class Decoded(
    val output: Publication?,
    val ledger: JSONObject?,
    val report: Report,
  )

  /**
   * Runs the block. A failed request does not end it, and a failure of the
   * block itself ends it with the outcome `failed`.
   *
   * @throws CancellationException when the caller is cancelled, after the
   *   attempt was torn down and the outcome `aborted` was recorded.
   */
  suspend fun run() {
    try {
      val nice =
        withContext(decode) { Process.getThreadPriority(Process.myTid()) }

      val mode = conditions.requestDisplayMode(plan.refresh)

      // A block whose display stays in another mode still runs: the record
      // states the mode that the display reached, and the frames show it.
      withTimeoutOrNull(MODE_SWITCH) {
        while (!conditions.displays(mode)) {
          withFrameNanos {}
        }
      }

      record.put("conditions", conditions.block(mode, nice))

      if (plan.proc) {
        val refusal = Resident.resetPeak()

        resets = refusal == null

        record.put(
          "memory",
          JSONObject()
            .put("source", "proc")
            .put("peak_reset", resets)
            .put("reset_failure", refusal ?: JSONObject.NULL),
        )
      }

      record.put("outcome", block())
    } catch (failure: CancellationException) {
      record.put("outcome", "aborted")

      throw failure
    } catch (failure: Exception) {
      record.put("outcome", "failed").put("failure", failure.toString())
    } finally {
      decodes.cancel()
      executor.shutdown()
    }
  }

  private suspend fun block(): String {
    val fixture =
      withContext(Dispatchers.IO) { corpus.fixture(plan.cell.fixture) }

    val steps =
      plan.cell.steps(fixture.image, corpus.viewport, corpus.usefulRows)

    val backend = plan.backend.create(corpus.viewport)

    conditions.requireWindow(corpus.viewport)

    try {
      return attempts(steps, backend, fixture)
    } finally {
      withContext(NonCancellable) {
        // The thread of a backend exists only once it has decoded. A failed
        // read leaves the record without it and still ends the backend.
        runCatching {
          backend.worker?.let { record.put("worker", conditions.thread(it)) }
        }

        // A cancelled decode ends soon unless it cannot be interrupted. Its
        // backend then stays open, because the close would wait for it.
        val ended =
          withTimeoutOrNull(TEARDOWN) {
            decodes.coroutineContext.job.children.forEach { it.join() }
          }

        if (ended != null) {
          val closing = SystemClock.elapsedRealtimeNanos()

          backend.close()

          // The end of the backend with nothing of an attempt left in it.
          record.put(
            "backend_close_us",
            (SystemClock.elapsedRealtimeNanos() - closing) / 1000,
          )
        }
      }
    }
  }

  private suspend fun attempts(
    steps: List<Step>,
    backend: Backend,
    fixture: Fixture,
  ): String {
    var request = 0

    for (attempt in 1..plan.warmups + plan.attempts) {
      val session = Session()
      var timedOut = false
      var closed = false

      try {
        for ((index, step) in steps.withIndex()) {
          request += 1

          val entry = request(request, step, session, backend, fixture)

          session.entry = entry

          requests.put(
            entry
              .put("attempt", attempt)
              .put("step", index)
              .put("wanted", describe(step))
              .put("warmup", attempt <= plan.warmups)
          )

          if (entry.getString("outcome") != "ok") {
            timedOut = entry.getString("outcome") == "timeout"

            break
          }
        }
      } finally {
        closed =
          withContext(NonCancellable) { close(request, session, backend) }
      }

      if (timedOut) {
        return "stopped"
      }

      if (!closed) {
        return "aborted"
      }
    }

    return "completed"
  }

  /** Runs one request up to the end of its hold, and returns its entry. */
  private suspend fun request(
    request: Int,
    step: Step,
    session: Session,
    backend: Backend,
    fixture: Fixture,
  ): JSONObject {
    // A request at a fixed time after the last frame would keep one phase to
    // the frames of the display. Its draw waits for the next frame, so a
    // millisecond of decode time would then move every request of a block
    // by a whole frame period.
    val offset = offsets.nextLong(OFFSET.inWholeMicroseconds).microseconds

    delay(offset)

    val entry =
      JSONObject()
        .put("request", request)
        .put("offset_us", offset.inWholeMicroseconds)
        .put("conditions", conditions.request())

    if (plan.proc) {
      // The peak of the request before this one ends where the next one
      // starts, as it ends at the next request mark in a trace.
      session.entry?.getJSONObject("memory")?.put("end", Resident.read().json())

      if (resets) {
        Resident.resetPeak()?.let { error("A later reset failed: $it") }
      }

      entry.put("memory", JSONObject().put("request", Resident.read().json()))
    }

    val marked = SystemClock.elapsedRealtimeNanos()
    val clock = JSONObject().put("request", marked)

    Marks.instant(Marks.request(request))

    // The decode thread waits for display frames on the clock of this context.
    val frames = currentCoroutineContext().minusKey(Job)

    val decoding = decodes.async {
      decode(request, marked, step, session, backend, fixture, frames)
    }

    session.decoding = decoding

    // A held step may wait its own limit for its useful output, and that
    // limit fails the request before the watchdog stops the block. A
    // throttled step has twice the time that its input needs.
    val watchdog =
      when (step) {
        is Held -> WATCHDOG + Held.LIMIT
        Throttled -> WATCHDOG + Throttled.time(fixture.file.length()) * 2
        else -> WATCHDOG
      }

    try {
      val finished =
        withTimeoutOrNull(watchdog) {
          val decoded = decoding.await()

          clock.put("decoded", SystemClock.elapsedRealtimeNanos())

          // A request without an output still stays for the hold frames.
          if (decoded.output != null) {
            consumer.drawn.first { it === decoded.output }
            clock.put("drawn", SystemClock.elapsedRealtimeNanos())
          }

          repeat(plan.hold) { withFrameNanos {} }

          decoded
        }

      if (finished == null) {
        entry.put("outcome", "timeout")
      } else {
        entry.put("outcome", "ok")
        record(finished, entry, clock)
      }
    } catch (failure: CancellationException) {
      throw failure
    } catch (failure: Exception) {
      entry.put("outcome", "failed").put("failure", failure.toString())
    }

    return entry.put("clock", clock)
  }

  /** Adds what [finished] reports to the [entry] of its request. */
  private fun record(finished: Decoded, entry: JSONObject, clock: JSONObject) {
    val report = finished.report

    finished.output?.let { entry.put("output", describe(it)) }
    finished.ledger?.let { entry.put("ledger", it) }

    report.input?.let { input ->
      entry.put(
        "input",
        JSONObject()
          .put("acquired_bytes", input.bytes)
          .put("reacquired_bytes", input.again),
      )
    }

    entry.put(
      "terminal",
      JSONObject()
        .put("outcome", report.terminal.outcome)
        .put("failure", report.terminal.failure ?: JSONObject.NULL),
    )

    report.resumed?.let { clock.put("resumed", it) }
    report.canceled?.let { clock.put("canceled", it) }
    report.returned?.let { clock.put("returned", it) }
  }

  /**
   * Decodes one step on the decode thread and returns its last output, if it
   * has one, with the counters of the backend before the decode and at its end,
   * and with the report of the backend. [frames] is a context with the frame
   * clock of the display.
   */
  private suspend fun decode(
    request: Int,
    marked: Long,
    step: Step,
    session: Session,
    backend: Backend,
    fixture: Fixture,
    frames: CoroutineContext,
  ): Decoded =
    Marks.section(Marks.decode(request)) {
      val content =
        session.content ?: backend.open(fixture).also { session.content = it }

      // The peaks of the request start here, right before it executes.
      val reset = backend.ledger(reset = true)
      val offset = SystemClock.elapsedRealtimeNanos() - marked
      var last: Publication? = null

      val display =
        object : Display {
          override suspend fun publish(output: Publication) {
            try {
              // The one output of a reference never waits here.
              consumer.room()
              consumer.queue(output)
            } catch (failure: Throwable) {
              output.release()

              throw failure
            }

            last = output
          }

          override suspend fun present(
            source: () -> Publication?
          ): Publication? {
            consumer.room()

            return consumer.take(source)?.also { last = it }
          }

          override suspend fun shown(request: Int, rows: Int) {
            consumer.drawn.first { drawn ->
              drawn is Publication &&
                drawn.request == request &&
                drawn.rows >= rows
            }

            withContext(frames) { repeat(plan.hold) { withFrameNanos {} } }
          }
        }

      val report = content.decode(request, step, display)
      val output = last

      // A request that did not complete may end with a partial output, and
      // one cancelled before its first take with none. So may one whose
      // decode ended before its cancellation reached it: the reader makes no
      // final copy for a cancelled request, and it releases a queued output.
      if (report.terminal == Terminal.COMPLETED && report.canceled == null) {
        checkNotNull(output) { "The backend published no output." }

        check(output.rows == output.bitmap.height) {
          "The last output of the request lacks rows."
        }
      }

      check(output == null || output.request == request) {
        "The last output belongs to another request."
      }

      val ledger =
        backend.ledger(reset = false)?.let { terminal ->
          JSONObject()
            .put("reset_offset_us", offset / 1000)
            .put("reset", reset ?: JSONObject.NULL)
            .put("terminal", terminal)
        }

      Decoded(output, ledger, report)
    }

  /**
   * Ends an attempt: the outputs leave the display, one frame without them
   * synchronizes, the backend closes, and the settle interval passes.
   *
   * It returns false when the attempt did not end that way: no display frame
   * came, as after the activity stopped, or the decode still runs after its
   * request timed out. The outputs are then released without a frame, and a
   * running decode is cancelled and keeps its input.
   */
  private suspend fun close(
    request: Int,
    session: Session,
    backend: Backend,
  ): Boolean {
    val idle = session.decoding?.isActive != true

    // Before the display empties: the cancellation ends a decode that waits
    // for room, which would otherwise queue an output behind the blank frame,
    // and it keeps the output of one that cannot be interrupted off the
    // display.
    if (!idle) {
      session.decoding?.cancel()
    }

    val cleared =
      withTimeoutOrNull(TEARDOWN) { consumer.clear(Blank(request)) } != null

    if (!cleared) {
      consumer.abandon()
    }

    if (idle) {
      val entry = session.entry
      val closing = SystemClock.elapsedRealtimeNanos()
      var ended = closing

      val closed =
        withContext(decode) {
          session.content?.close()
          ended = SystemClock.elapsedRealtimeNanos()
          backend.ledger(reset = false)
        }

      // The close of the attempt, after presentation released its outputs.
      entry
        ?.optJSONObject("clock")
        ?.put("closing", closing)
        ?.put("closed", ended)

      // What the backend still holds once the attempt has ended.
      if (closed != null) {
        val ledger = entry?.optJSONObject("ledger") ?: JSONObject()

        entry?.put("ledger", ledger.put("close", closed))
      }
    }

    // An attempt that ended inside a request has no entry of that request.
    // The reads of the one before it stay as they are, because the reset of
    // the lost request already ended its peak.
    val memory =
      session.entry?.optJSONObject("memory")?.takeUnless { it.has("end") }

    Marks.instant(Marks.close(request))
    memory?.put("end", closingRead())
    delay(plan.settle)
    Marks.instant(Marks.settled(request))
    memory?.put("settled", closingRead())

    return cleared && idle
  }

  /**
   * Reads the resident memory at the close of an attempt, or returns null when
   * the read fails: the close also ends an attempt that another failure ended,
   * which a second one would hide, and the analysis reports a missing read.
   */
  private fun closingRead(): JSONObject? =
    try {
      Resident.read().json()
    } catch (_: IOException) {
      null
    }

  private fun describe(step: Step): JSONObject =
    when (step) {
      is Whole -> JSONObject().put("width", step.width ?: JSONObject.NULL)
      is Band -> JSONObject().put("top", step.top).put("bottom", step.bottom)

      is Held ->
        JSONObject().put("useful_rows", step.rows).put("ending", step.ending.id)

      is Interrupted -> JSONObject().put("canceled", step.moment.id)

      Throttled ->
        JSONObject()
          .put("chunk_bytes", Throttled.CHUNK)
          .put("interval_us", Throttled.INTERVAL.inWholeMicroseconds)
    }

  private fun describe(output: Publication): JSONObject =
    JSONObject()
      .put("width", output.bitmap.width)
      .put("height", output.bitmap.height)
      .put("rows", output.rows)
      .put("revision", output.revision)
      .put("mutable", output.bitmap.isMutable)
      .put("alpha", output.bitmap.hasAlpha())

  private companion object {
    /**
     * The version of the record. It rises with every change of a field or of
     * the steps of a cell, so the analysis never pools blocks that differ.
     */
    const val SCHEMA = 8

    /** A request that takes longer is a timeout, which stops its block. */
    val WATCHDOG = 10.seconds

    /**
     * The bound of the pseudo-random wait before a request. It is a whole
     * number of frame periods at 60, 90, and 120 Hz, so the requests of a block
     * spread evenly over the phases of a frame.
     */
    val OFFSET = 100.milliseconds

    /** The longest wait for the display to reach the requested mode. */
    val MODE_SWITCH = 2.seconds

    /** The longest wait for the display frames that end an attempt. */
    val TEARDOWN = 2.seconds
  }
}
