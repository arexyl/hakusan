package app.hakusan.bench

import android.os.Process
import android.os.SystemClock
import android.view.Display
import androidx.compose.runtime.withFrameNanos
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
  private val pinned: Display.Mode,
) {
  private val executor = Executors.newSingleThreadExecutor {
    Thread(it, Conditions.DECODE_THREAD)
  }

  private val decode = executor.asCoroutineDispatcher()

  // A decode cannot be interrupted, so it runs outside the request that
  // waits for it, and a request can end while its decode still runs.
  private val decodes = CoroutineScope(SupervisorJob() + decode)

  private val requests = JSONArray()

  /**
   * The record of the block. It is complete once [run] has returned or thrown,
   * and it then states how the block ended.
   */
  val record: JSONObject =
    JSONObject()
      .put("schema", 1)
      .put("cell", plan.cell.id)
      .put("backend", plan.backend.id)
      .put("fixture", plan.cell.fixture)
      .put("warmups", plan.warmups)
      .put("attempts", plan.attempts)
      .put("settle_ms", plan.settle.inWholeMilliseconds)
      .put("hold", plan.hold)
      .put("requests", requests)

  /** The input of the running attempt and the decode of its last request. */
  private class Session {
    @Volatile var content: Content? = null
    var decoding: Deferred<Publication>? = null
  }

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

      record.put("conditions", conditions.block(pinned, nice))
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

    val steps = plan.cell.steps(fixture.image, corpus.viewport)
    val backend = plan.backend.create()
    var request = 0

    conditions.requireWindow(corpus.viewport)

    for (attempt in 1..plan.warmups + plan.attempts) {
      val session = Session()
      var timedOut = false
      var closed = false

      try {
        for ((index, step) in steps.withIndex()) {
          request += 1

          val entry = request(request, step, session, backend, fixture)

          requests.put(
            entry
              .put("attempt", attempt)
              .put("step", index)
              .put("warmup", attempt <= plan.warmups)
          )

          if (entry.getString("outcome") != "ok") {
            timedOut = entry.getString("outcome") == "timeout"

            break
          }
        }
      } finally {
        closed = withContext(NonCancellable) { close(request, session) }
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
    val entry =
      JSONObject()
        .put("request", request)
        .put("conditions", conditions.request())

    val clock = JSONObject().put("request", SystemClock.elapsedRealtimeNanos())

    Marks.instant(Marks.request(request))

    val decoding = decodes.async {
      decode(request, step, session, backend, fixture)
    }

    session.decoding = decoding

    try {
      val finished =
        withTimeoutOrNull(WATCHDOG) {
          val output = decoding.await()

          clock.put("decoded", SystemClock.elapsedRealtimeNanos())
          consumer.drawn.first { it === output }
          clock.put("drawn", SystemClock.elapsedRealtimeNanos())
          repeat(plan.hold) { withFrameNanos {} }

          output
        }

      if (finished == null) {
        entry.put("outcome", "timeout")
      } else {
        entry.put("outcome", "ok").put("output", describe(finished))
      }
    } catch (failure: CancellationException) {
      throw failure
    } catch (failure: Exception) {
      entry.put("outcome", "failed").put("failure", failure.toString())
    }

    return entry.put("clock", clock)
  }

  /** Decodes one step on the decode thread and returns its last output. */
  private fun decode(
    request: Int,
    step: Step,
    session: Session,
    backend: Backend,
    fixture: Fixture,
  ): Publication =
    Marks.section(Marks.decode(request)) {
      val content =
        session.content ?: backend.open(fixture).also { session.content = it }

      var last: Publication? = null

      content.decode(request, step) { output ->
        try {
          consumer.queue(output)
        } catch (failure: IllegalStateException) {
          output.release()

          throw failure
        }

        last = output
      }

      val output = checkNotNull(last) { "The backend published no output." }

      check(output.request == request && output.rows == output.bitmap.height) {
        "The last output of the request lacks rows."
      }

      output
    }

  /**
   * Ends an attempt: the outputs leave the display, one frame without them
   * synchronizes, the backend closes, and the settle interval passes.
   *
   * It returns false when the attempt did not end that way: no display frame
   * came, as after the activity stopped, or the decode still runs after its
   * request timed out. The outputs are then released without a frame, and a
   * running decode keeps its input.
   */
  private suspend fun close(request: Int, session: Session): Boolean {
    val cleared =
      withTimeoutOrNull(TEARDOWN) { consumer.clear(Blank(request)) } != null

    if (!cleared) {
      consumer.abandon()
    }

    val idle = session.decoding?.isActive != true

    if (idle) {
      withContext(decode) { session.content?.close() }
    }

    Marks.instant(Marks.close(request))
    delay(plan.settle)
    Marks.instant(Marks.settled(request))

    return cleared && idle
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
    /** A request that takes longer is a timeout, which stops its block. */
    val WATCHDOG = 10.seconds

    /** The longest wait for the display frames that end an attempt. */
    val TEARDOWN = 2.seconds
  }
}
