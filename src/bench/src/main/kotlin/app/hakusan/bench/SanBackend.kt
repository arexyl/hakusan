package app.hakusan.bench

import android.os.SystemClock
import androidx.compose.ui.unit.IntSize
import app.hakusan.reader.ReaderAcquisitionFailure
import app.hakusan.reader.ReaderBackwardVariant
import app.hakusan.reader.ReaderByteInput
import app.hakusan.reader.ReaderChapter
import app.hakusan.reader.ReaderChapterContent
import app.hakusan.reader.ReaderChapterKey
import app.hakusan.reader.ReaderContentDescriptor
import app.hakusan.reader.ReaderContentFailure
import app.hakusan.reader.ReaderContentRequests
import app.hakusan.reader.ReaderContentTerminal
import app.hakusan.reader.ReaderContentUnit
import app.hakusan.reader.ReaderEngineSettings
import app.hakusan.reader.ReaderImageGeometry
import app.hakusan.reader.ReaderInputCapabilities
import app.hakusan.reader.ReaderInputOpenResult
import app.hakusan.reader.ReaderInputRead
import app.hakusan.reader.ReaderInputSeek
import app.hakusan.reader.ReaderOutputLease
import app.hakusan.reader.ReaderOutputLimits
import app.hakusan.reader.ReaderOutputRequest
import app.hakusan.reader.ReaderRepresentation
import app.hakusan.reader.ReaderResourceOwner
import app.hakusan.reader.ReaderSession
import app.hakusan.reader.ReaderSessionGate
import app.hakusan.reader.ReaderTitleKey
import app.hakusan.reader.ReaderUnitKind
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Objects
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * The native path: San through the reader, as the reader runs it.
 *
 * It decodes a whole image, at a scale of 1:1 or scaled down to the width of
 * its step, and a band of rows at a scale of 1:1. A band has a job of its own
 * that reads the file from its start, unless the block retains strips and the
 * attempt decoded the band before: the reader then copies it from its strip and
 * opens no input. One engine with the first-iteration profile and the
 * [backward] variant serves the block, and each attempt has a session and a
 * content owner of its own, so no attempt finds an output or a strip of an
 * earlier one. The worker of the reader decodes. The decode thread opens the
 * input and hands each publication to presentation, which the reader paces: it
 * copies the next revision only into a target that presentation has returned.
 * Where a step asks for it, that thread also watches for the output that ends
 * the hold of an input, and requests the cancellation of the request.
 *
 * The decode section of a request therefore spans waits for display frames
 * here. The sections of the reader on its worker hold the time of the engine.
 */
internal class SanBackend(
  private val viewport: IntSize,
  backward: ReaderBackwardVariant,
) : Backend {
  private val owner =
    ReaderResourceOwner(
      ReaderEngineSettings.FIRST_ITERATION.copy(backwardVariant = backward)
    )

  private val gate = ReaderSessionGate()

  override val worker: String
    get() = WORKER

  override suspend fun open(fixture: Fixture): Content =
    SanContent(owner, gate.start(), fixture, viewport.width)

  /** Reads the ledger of the engine, which exists from its first request. */
  override suspend fun ledger(reset: Boolean): JSONObject? {
    val counters = owner.counters(resetPeak = reset) ?: return null

    return JSONObject()
      .put("live_bytes", counters.liveBytes)
      .put("reserved_bytes", counters.reservedBytes)
      .put("peak_live_bytes", counters.peakLiveBytes)
      .put("peak_committed_bytes", counters.peakCommittedBytes)
      .put("input_bytes", counters.inputBytes)
      .put("metadata_bytes", counters.metadataBytes)
      .put("output_bytes", counters.outputBytes)
      .put("contents", counters.contentCount)
      .put("jobs", counters.jobCount)
      .put("leases", counters.leaseCount)
  }

  /** Shuts the engine down, which fails when an attempt left something. */
  override suspend fun close() {
    owner.close()
  }

  private companion object {
    /** The worker thread of the reader, by the name that the reader gives. */
    const val WORKER = "hakusan-reader"
  }
}

private class SanContent(
  private val owner: ReaderResourceOwner,
  session: ReaderSession,
  private val fixture: Fixture,
  private val viewportWidth: Int,
) : Content {
  private val requests =
    ReaderContentRequests(session, ReaderOutputLimits.FIRST_ITERATION)

  private val published = Channel<Unit>(Channel.CONFLATED)

  /** The bytes of the fixture, which every input of the attempt delivers. */
  private val representation = ReaderRepresentation()

  /** The geometry that the first band of the attempt learned. */
  private var image: ReaderImageGeometry? = null

  /** The bytes that the inputs of the running request delivered so far. */
  private var acquired = Acquired(bytes = 0, again = 0)

  /** The end of the longest prefix that an input of the attempt delivered. */
  private var reach = 0L

  /** Whether the input of the running request delivers in slow chunks. */
  private var throttled = false

  /** The cancellation of the running request, if the backend requests one. */
  private var cancellation = Cancellation(request = 0)

  /** The times of one cancellation, on the clock of the record. */
  private class Cancellation(val request: Int) {
    var requested: Long? = null
    var returned: Long? = null
  }

  init {
    // The reader runs the wakeup on its worker.
    requests.setPresentationWakeup { published.trySend(Unit) }
  }

  override suspend fun decode(
    request: Int,
    step: Step,
    display: Display,
  ): Report {
    val wanted: ReaderOutputRequest
    val size: IntSize
    val top: Int
    val hold: Withheld?
    var resumed: Long? = null
    var taken = 0

    // Before the first execution of the request, which reads all three.
    acquired = Acquired(bytes = 0, again = 0)
    cancellation = Cancellation(request)
    throttled = step == Throttled

    when (step) {
      is Whole -> {
        wanted = ReaderOutputRequest.Whole(maxOutputWidth = step.width)
        size = step.grid(fixture.image)
        top = 0
        hold = null
      }

      is Band -> {
        wanted = ReaderOutputRequest.Band(discovered(), step.top, step.bottom)
        size = IntSize(fixture.image.width, step.bottom - step.top)
        top = step.top
        hold = null
      }

      is Held -> {
        wanted = ReaderOutputRequest.Whole()
        size = fixture.image
        top = 0
        hold = withheld(step)
      }

      is Interrupted,
      Throttled -> {
        wanted = ReaderOutputRequest.Whole()
        size = fixture.image
        top = 0
        hold = null
      }
    }

    val moment = (step as? Interrupted)?.moment

    val watch: (suspend () -> Unit)? = hold?.let {
      { resumed = resume(request, it, display) }
    }

    // The first publication is queued and not taken when this thread
    // learns of it.
    val arrived = if (moment == Moment.PUBLISHED) ::cancelOnce else null

    val terminal =
      execute(wanted, hold, watch, arrived) { lease ->
        val output = publication(lease, request, size, top)
        val held = hold?.waits == true

        taken += 1

        // Before the output goes on: this thread still holds its target.
        if (moment == Moment.DECODING && taken == 2) {
          cancelOnce()
        }

        display.publish(output)

        // More rows than the prefix holds would need a withheld byte.
        check(!held || output.rows <= checkNotNull(hold).rows) {
          "An output has ${output.rows} rows before the hold point ended."
        }
      }

    val ended =
      when (terminal) {
        is ReaderContentTerminal.Completed -> Terminal.COMPLETED
        is ReaderContentTerminal.Canceled -> Terminal.CANCELED

        is ReaderContentTerminal.Failed -> failed(terminal.failure)

        is ReaderContentTerminal.Oversized,
        null -> error("The request ended as $terminal.")
      }

    check(ended in expected(step)) { "The request ended as $terminal." }

    return Report(
      terminal = ended,
      input = acquired,
      resumed = resumed,
      canceled = cancellation.requested,
      returned = cancellation.returned,
    )
  }

  /** The terminal outcomes that [step] may end as. */
  private fun expected(step: Step): Set<Terminal> =
    when (step) {
      is Whole,
      is Band,
      Throttled -> setOf(Terminal.COMPLETED)

      // A decode that ended before the cancellation reached it completed.
      is Interrupted -> setOf(Terminal.CANCELED, Terminal.COMPLETED)

      is Held ->
        when (step.ending) {
          Ending.RELEASE -> setOf(Terminal.COMPLETED)

          // The stream ends inside the image data, and the decoder says so.
          Ending.TRUNCATE ->
            setOf(failed(ReaderContentFailure.TRUNCATED_CONTENT))

          Ending.FAIL -> setOf(failed(ReaderContentFailure.INPUT_FAILED))
          Ending.CANCEL -> setOf(Terminal.CANCELED)
        }
    }

  private fun failed(failure: ReaderContentFailure): Terminal =
    Terminal.failed(failure.name.lowercase())

  /**
   * Cancels the executing request once, as a controller does that moves on: it
   * submits a replacement, and the content owner notifies the cancellation of
   * the executing request. The replacement never executes, and the close of the
   * attempt drops it.
   */
  private fun cancelOnce() {
    if (cancellation.requested != null) {
      return
    }

    cancellation.requested = SystemClock.elapsedRealtimeNanos()
    Marks.instant(Marks.cancel(cancellation.request))

    checkNotNull(requests.submit(descriptor(fixture))) {
      "The content is closed."
    }
  }

  /**
   * The hold of the input of [step] at the hold point of the fixture.
   *
   * @throws IllegalStateException when the fixture has no hold point, or one
   *   whose prefix decodes less than the useful output.
   */
  private fun withheld(step: Held): Withheld {
    val point = checkNotNull(fixture.hold) { "The fixture has no hold point." }

    check(point.rows >= step.rows) {
      "The hold point leaves ${point.rows} rows, and ${step.rows} are useful."
    }

    return Withheld(point.offset, point.rows, step.ending)
  }

  /**
   * Waits until the display has shown the output with every row of the prefix
   * of [hold], and then lets the input go on to its ending and returns the time
   * of that, or cancels the request and returns null. No copy and no
   * publication of the request is on its way by then, because the adapter has
   * no row left to copy.
   *
   * @throws IllegalStateException when the output takes longer than its limit
   *   after the input delivered the prefix.
   */
  private suspend fun resume(
    request: Int,
    hold: Withheld,
    display: Display,
  ): Long? {
    hold.awaitPrefix()

    val shown =
      withTimeoutOrNull(Held.LIMIT) { display.shown(request, hold.rows) }

    checkNotNull(shown) {
      "No output with the ${hold.rows} rows of the prefix in ${Held.LIMIT}."
    }

    // A cancellation finds the input waiting, so the hold stays.
    if (hold.ending == Ending.CANCEL) {
      cancelOnce()

      return null
    }

    val resumed = SystemClock.elapsedRealtimeNanos()

    Marks.instant(Marks.resume(request))
    hold.release()

    return resumed
  }

  /**
   * The geometry of the image as the engine reports it. The first band of an
   * attempt learns it as a reader does: it asks for the whole image at the
   * width of the viewport, and the engine refuses that lease with the geometry.
   */
  private suspend fun discovered(): ReaderImageGeometry {
    image?.let {
      return it
    }

    val whole = ReaderOutputRequest.Whole(maxOutputWidth = viewportWidth)

    val terminal =
      execute(whole) { lease ->
        lease.close()

        error("The whole image published an output.")
      }

    check(terminal is ReaderContentTerminal.Oversized) {
      "The whole image ended as $terminal."
    }

    val stated = ReaderImageGeometry(fixture.image.width, fixture.image.height)

    check(terminal.image == stated) {
      "The engine reports ${terminal.image}, and the manifest $stated."
    }

    image = stated

    return stated
  }

  /**
   * Runs one request to its terminal outcome, which it returns, and hands every
   * publication to [take]. Its input withholds what [hold] names, if anything,
   * and [watch] runs beside the takes until the request ends, where a failure
   * of it ends the request. [arrived] runs when this thread learns of a queued
   * publication, before it takes it. It counts what the input of the request
   * delivered, if the reader opened one.
   */
  private suspend fun execute(
    wanted: ReaderOutputRequest,
    hold: Withheld? = null,
    watch: (suspend () -> Unit)? = null,
    arrived: (() -> Unit)? = null,
    take: suspend (ReaderOutputLease) -> Unit,
  ): ReaderContentTerminal? {
    // The wakeup of the last publication of the request before may still be
    // here, when the end of that execution was seen first.
    published.tryReceive()

    val submitted =
      checkNotNull(requests.submit(descriptor(fixture), wanted)) {
        "The content is closed."
      }

    // The reader opens one input at most, and it has closed the input when
    // its execution ends.
    val input = AtomicReference<FileInput>()

    try {
      coroutineScope {
        val execution = async {
          owner.execute(requests) {
            val opened = open(fixture.file, representation, hold, throttled)

            if (opened != null) {
              input.set(opened)
              ReaderInputOpenResult.Opened(opened)
            } else {
              ReaderInputOpenResult.Failed(ReaderAcquisitionFailure.UNAVAILABLE)
            }
          }

          // The request has settled here, back on this thread.
          if (cancellation.requested != null) {
            cancellation.returned = SystemClock.elapsedRealtimeNanos()
            Marks.instant(Marks.returned(cancellation.request))
          }
        }

        val watching = watch?.let { launch { it() } }

        try {
          // The reader publishes the last copy before the execution ends, and
          // one publication at most waits for its take.
          do {
            val ended = select {
              execution.onJoin { true }
              published.onReceive { false }
            }

            if (!ended) {
              arrived?.invoke()
            }

            requests.takePublication()?.let { take(it.output) }
          } while (!ended)

          execution.await()
        } finally {
          watching?.cancel()
        }
      }
    } finally {
      input.get()?.let { count(it.position) }
    }

    return submitted.terminal
  }

  /**
   * Counts an input that delivered the bytes before [end]. Every input of the
   * attempt reads the same file from its start, so the bytes that the attempt
   * acquires again are the ones below [reach].
   */
  private fun count(end: Long) {
    acquired =
      Acquired(
        bytes = acquired.bytes + end,
        again = acquired.again + minOf(end, reach),
      )

    reach = maxOf(reach, end)
  }

  /**
   * Stops the content owner and waits until it holds nothing.
   *
   * @throws IllegalStateException when an output of the attempt stays
   *   unreleased, which would keep its target from every later attempt.
   */
  override suspend fun close() {
    requests.beginClose()

    val closed = withTimeoutOrNull(CLOSE) { requests.awaitClosed() }

    checkNotNull(closed) { "An output of the attempt was never released." }
  }

  /**
   * Wraps [lease] once its target has the pixel profile at [size], which the
   * harness computes for the step by its own rule, and starts at the grid row
   * [top].
   */
  private fun publication(
    lease: ReaderOutputLease,
    request: Int,
    size: IntSize,
    top: Int,
  ): Publication {
    val region = lease.coverage.region

    try {
      lease.bitmap.requireProfile(size)
      check(region.top == top) { "The output starts at row ${region.top}." }
    } catch (failure: IllegalStateException) {
      lease.close()

      throw failure
    }

    return Publication(
      bitmap = lease.bitmap,
      request = request,
      revision = lease.revision,
      rows = region.bottom - region.top,
      release = lease::close,
    )
  }

  private companion object {
    /** The longest wait for the releases that the close of a display posts. */
    val CLOSE = 2.seconds

    /**
     * Opens [file] as an input of [representation] with the length that the
     * file has now, or returns null when no such file exists. The input
     * withholds what [hold] names, if anything, and delivers in slow chunks
     * when it is [throttled].
     */
    fun open(
      file: File,
      representation: ReaderRepresentation,
      hold: Withheld?,
      throttled: Boolean,
    ): FileInput? {
      val stream =
        try {
          FileInputStream(file)
        } catch (_: FileNotFoundException) {
          return null
        }

      val length =
        try {
          stream.channel.size()
        } catch (failure: IOException) {
          stream.close()

          throw failure
        }

      return FileInput(stream, length, representation, hold, throttled)
    }

    /** The fixture as the one page of a title with one chapter. */
    fun descriptor(fixture: Fixture): ReaderContentDescriptor {
      val title = ReaderTitleKey("bench", "corpus")
      val chapter = ReaderChapter(ReaderChapterKey(title, "fixtures"), "1")
      val page = ReaderContentUnit(ReaderUnitKind.PAGE, fixture.path)
      val content = ReaderChapterContent.of(chapter, listOf(page))

      return ReaderContentDescriptor.of(content, content.positionAt(0))
    }
  }
}

/**
 * What an input withholds: every byte from [offset] on, until [release], after
 * which it goes on as [ending] says. The bytes before the offset, the prefix,
 * decode the top [rows] of the image. Every call is safe from any thread.
 */
private class Withheld(val offset: Long, val rows: Int, val ending: Ending) {
  private val delivered = CompletableDeferred<Unit>()
  private val released = CompletableDeferred<Unit>()

  /** Whether the input still withholds its bytes. */
  val waits: Boolean
    get() = !released.isCompleted

  /** The input has delivered the prefix and has nothing to deliver now. */
  fun prefixDelivered() {
    delivered.complete(Unit)
  }

  suspend fun awaitPrefix() {
    delivered.await()
  }

  /** Lets the input go on to its ending. */
  fun release() {
    released.complete(Unit)
  }

  suspend fun awaitRelease() {
    released.await()
  }
}

/**
 * The local file of a fixture as a sequential input. Its reads block, and the
 * reader runs them off its worker.
 *
 * Without a [hold] it declares the [size] of the file as its length. With one
 * it declares no length, as a stream does, and while the hold withholds bytes
 * it has none behind the offset of the hold and waits there. A [throttled]
 * input delivers the chunks of the throttled step at its interval.
 */
private class FileInput(
  private val stream: FileInputStream,
  private val size: Long,
  override val representation: ReaderRepresentation,
  private val hold: Withheld?,
  private val throttled: Boolean,
) : ReaderByteInput {
  /** The failure or the cancellation that every read after it repeats. */
  private var terminal: ReaderInputRead? = null

  /** The earliest time of the next chunk of a throttled input. */
  private var next = 0L

  override val capabilities =
    ReaderInputCapabilities(
      length = size.takeIf { hold == null },
      rewind = false,
      seek = false,
    )

  @Volatile
  override var position = 0L
    private set

  override suspend fun read(
    destination: ByteArray,
    offset: Int,
    length: Int,
  ): ReaderInputRead {
    Objects.checkFromIndexSize(offset, length, destination.size)

    // Only a released hold that delivers the rest reads behind its offset.
    val whole = hold == null || (!hold.waits && hold.ending == Ending.RELEASE)
    val end = if (whole) size else minOf(checkNotNull(hold).offset, size)
    val left = end - position

    terminal?.let {
      return it
    }

    when {
      length == 0 -> return ReaderInputRead.NoOp
      left == 0L && whole -> return ReaderInputRead.EndOfInput
    }

    if (left == 0L) {
      return behind(checkNotNull(hold))
    }

    var wanted = minOf(length.toLong(), left).toInt()

    if (throttled) {
      wanted = minOf(wanted, Throttled.CHUNK)
      pace()
    }

    // A file that ends before its size is not the file of the fixture.
    val count =
      try {
        stream.read(destination, offset, wanted)
      } catch (_: IOException) {
        -1
      }

    if (count < 0) {
      return ReaderInputRead.InputFailure(ReaderAcquisitionFailure.FAILED)
        .also { terminal = it }
    }

    position += count

    return ReaderInputRead.Data(count)
  }

  /**
   * Waits until the interval of the throttled step has passed since the chunk
   * before, and leaves the input terminal when the wait is cancelled.
   */
  private suspend fun pace() {
    val now = SystemClock.elapsedRealtimeNanos()

    try {
      delay((next - now).nanoseconds)
    } catch (failure: CancellationException) {
      terminal = ReaderInputRead.Canceled

      throw failure
    }

    next = maxOf(now, next) + Throttled.INTERVAL.inWholeNanoseconds
  }

  /** The read at the offset of [hold], which delivers no byte. */
  private fun behind(hold: Withheld): ReaderInputRead {
    if (hold.waits) {
      hold.prefixDelivered()

      return ReaderInputRead.AwaitInput
    }

    return when (hold.ending) {
      Ending.RELEASE -> error("A released hold delivers the rest.")
      Ending.CANCEL -> error("A cancelled request never releases its hold.")
      Ending.TRUNCATE -> ReaderInputRead.EndOfInput

      Ending.FAIL ->
        ReaderInputRead.InputFailure(ReaderAcquisitionFailure.FAILED).also {
          terminal = it
        }
    }
  }

  override suspend fun awaitInput() {
    if (hold == null || position != hold.offset) {
      return
    }

    try {
      hold.awaitRelease()
    } catch (failure: CancellationException) {
      terminal = ReaderInputRead.Canceled

      throw failure
    }
  }

  override suspend fun seek(position: Long): ReaderInputSeek =
    ReaderInputSeek.Unsupported

  override fun close() {
    stream.close()
  }
}
