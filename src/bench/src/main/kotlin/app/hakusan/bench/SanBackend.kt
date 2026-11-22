package app.hakusan.bench

import androidx.compose.ui.unit.IntSize
import app.hakusan.reader.ReaderAcquisitionFailure
import app.hakusan.reader.ReaderByteInput
import app.hakusan.reader.ReaderChapter
import app.hakusan.reader.ReaderChapterContent
import app.hakusan.reader.ReaderChapterKey
import app.hakusan.reader.ReaderContentDescriptor
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
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * The native path: San through the reader, as the reader runs it.
 *
 * It decodes a whole image, at a scale of 1:1 or scaled down to the width of
 * its step, and a band of rows at a scale of 1:1, each band in a job of its own
 * that reads the file from its start. One engine with the first-iteration
 * profile serves the block, and each attempt has a session and a content owner
 * of its own, so no attempt finds an output of an earlier one. The worker of
 * the reader decodes. The decode thread only opens the input and hands each
 * publication to presentation, which the reader paces: it copies the next
 * revision only into a target that presentation has returned.
 *
 * The decode section of a request therefore spans waits for display frames
 * here. The sections of the reader on its worker hold the time of the engine.
 */
internal class SanBackend(private val viewport: IntSize) : Backend {
  private val owner = ReaderResourceOwner(ReaderEngineSettings.FIRST_ITERATION)
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

  init {
    // The reader runs the wakeup on its worker.
    requests.setPresentationWakeup { published.trySend(Unit) }
  }

  override suspend fun decode(
    request: Int,
    step: Step,
    publish: suspend (Publication) -> Unit,
  ) {
    val wanted: ReaderOutputRequest
    val size: IntSize
    val top: Int

    when (step) {
      is Whole -> {
        wanted = ReaderOutputRequest.Whole(maxOutputWidth = step.width)
        size = step.grid(fixture.image)
        top = 0
      }

      is Band -> {
        wanted = ReaderOutputRequest.Band(discovered(), step.top, step.bottom)
        size = IntSize(fixture.image.width, step.bottom - step.top)
        top = step.top
      }
    }

    val terminal =
      execute(wanted) { publish(publication(it, request, size, top)) }

    check(terminal is ReaderContentTerminal.Completed) {
      "The request ended as $terminal."
    }
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
   * publication to [take].
   */
  private suspend fun execute(
    wanted: ReaderOutputRequest,
    take: suspend (ReaderOutputLease) -> Unit,
  ): ReaderContentTerminal? {
    val submitted =
      checkNotNull(requests.submit(descriptor(fixture), wanted)) {
        "The content is closed."
      }

    coroutineScope {
      val execution = async {
        owner.execute(requests) { open(fixture.file, representation) }
      }

      // The reader publishes the last copy before the execution ends, and
      // one publication at most waits for its take.
      do {
        val ended = select {
          execution.onJoin { true }
          published.onReceive { false }
        }

        requests.takePublication()?.let { take(it.output) }
      } while (!ended)

      execution.await()
    }

    return submitted.terminal
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
     * file has now.
     */
    fun open(
      file: File,
      representation: ReaderRepresentation,
    ): ReaderInputOpenResult {
      val stream =
        try {
          FileInputStream(file)
        } catch (_: FileNotFoundException) {
          return ReaderInputOpenResult.Failed(
            ReaderAcquisitionFailure.UNAVAILABLE
          )
        }

      return ReaderInputOpenResult.Opened(
        FileInput(stream, stream.channel.size(), representation)
      )
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
 * The local file of a fixture as a sequential input of known length. Its reads
 * block, and the reader runs them off its worker.
 */
private class FileInput(
  private val stream: FileInputStream,
  private val declared: Long,
  override val representation: ReaderRepresentation,
) : ReaderByteInput {
  /** The failure that every read after it repeats. */
  private var failure: ReaderInputRead.InputFailure? = null

  override val capabilities =
    ReaderInputCapabilities(length = declared, rewind = false, seek = false)

  @Volatile
  override var position = 0L
    private set

  override suspend fun read(
    destination: ByteArray,
    offset: Int,
    length: Int,
  ): ReaderInputRead {
    Objects.checkFromIndexSize(offset, length, destination.size)

    val left = declared - position

    when {
      length == 0 -> return ReaderInputRead.NoOp
      failure != null -> return checkNotNull(failure)
      left == 0L -> return ReaderInputRead.EndOfInput
    }

    // A file that ends before its length did not deliver what it declared.
    val count =
      try {
        stream.read(destination, offset, minOf(length.toLong(), left).toInt())
      } catch (_: IOException) {
        -1
      }

    if (count < 0) {
      return ReaderInputRead.InputFailure(ReaderAcquisitionFailure.FAILED)
        .also { failure = it }
    }

    position += count

    return ReaderInputRead.Data(count)
  }

  override suspend fun awaitInput() {}

  override suspend fun seek(position: Long): ReaderInputSeek =
    ReaderInputSeek.Unsupported

  override fun close() {
    stream.close()
  }
}
