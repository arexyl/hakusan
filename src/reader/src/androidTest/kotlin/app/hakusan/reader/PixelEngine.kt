package app.hakusan.reader

import android.graphics.Bitmap
import android.graphics.Color
import app.hakusan.reader.san.SanAbi
import app.hakusan.reader.san.SanCalls
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlinx.coroutines.delay

/**
 * An engine for device tests that decodes nothing and writes pixels from
 * Kotlin: every [bytesPerRow] bytes of input complete one row of an opaque
 * image of [width] by [height] pixels, and row `r` has the color [colorOf].
 *
 * It keeps none of the call rules; the scripted engine of the JVM tests does.
 * It records a copy into a target that [referenced] names, which a recorded
 * frame may still draw.
 */
internal class PixelEngine(
  private val width: Int,
  private val height: Int,
  private val bytesPerRow: Int,
  private val referenced: Set<Bitmap>,
) : SanCalls {
  private val canceled = AtomicBoolean()
  private var received = 0L
  private var ended = false
  private var covered = 0
  private var revision = 0L

  /** The copies that wrote a target while a frame could draw it. */
  val violations: MutableList<String> = CopyOnWriteArrayList()

  /** Every target that received a copy. */
  val written: MutableSet<Bitmap> = ConcurrentHashMap.newKeySet()

  override fun engineCreate(
    aggregateBudgetBytes: Long,
    contentCount: Long,
    jobCount: Long,
    contentLengthBytes: Long,
    inputPortionBytes: Long,
    imageAxisPixels: Long,
    imageAreaPixels: Long,
    metadataBytes: Long,
    metadataExpandedBytes: Long,
    metadataPayloadBytes: Long,
    heldChunkBytes: Long,
    iccTagCount: Long,
    exifEntryCount: Long,
    outputBytes: Long,
    outputLeaseBytes: Long,
    outputLeaseCount: Long,
    engineOut: LongArray,
  ): Int = handle(engineOut, ENGINE)

  override fun engineShutdown(engine: Long): Int = SanAbi.STATUS_OK

  override fun engineCounters(
    engine: Long,
    resetPeak: Boolean,
    countersOut: LongArray,
  ): Int = SanAbi.STATUS_OK

  override fun contentOpen(engine: Long, contentOut: LongArray): Int {
    canceled.set(false)
    received = 0L
    ended = false
    covered = 0
    revision = 0L

    return handle(contentOut, CONTENT)
  }

  override fun contentFeed(
    content: Long,
    position: Long,
    bytes: ByteArray,
    offset: Int,
    length: Int,
    endOfInput: Boolean,
  ): Int {
    received += length
    ended = endOfInput

    return SanAbi.STATUS_OK
  }

  override fun contentClose(content: Long): Int = SanAbi.STATUS_OK

  override fun jobRequest(
    content: Long,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    maxOutputWidth: Int,
    jobOut: LongArray,
  ): Int = handle(jobOut, JOB)

  override fun jobStep(job: Long, reportOut: LongArray): Int {
    val rows = minOf((received / bytesPerRow).toInt(), height)

    if (!canceled.get() && rows > covered) {
      covered = rows
      revision += 1L
    }

    reportOut[SanAbi.JOB_REPORT_LEASE] =
      if (covered > 0) LEASE else SanAbi.NO_HANDLE

    reportOut[SanAbi.JOB_REPORT_REVISION] = revision
    reportOut[SanAbi.JOB_REPORT_COVERED_ROWS] = covered.toLong()
    reportOut[SanAbi.JOB_REPORT_LEASE_WIDTH] = width.toLong()
    reportOut[SanAbi.JOB_REPORT_LEASE_HEIGHT] = height.toLong()
    reportOut[SanAbi.JOB_REPORT_ALPHA] = SanAbi.ALPHA_OPAQUE.toLong()
    reportOut[SanAbi.JOB_REPORT_EARLY_PUBLICATION] = 1L
    reportOut[SanAbi.JOB_REPORT_IMAGE_WIDTH] = width.toLong()
    reportOut[SanAbi.JOB_REPORT_IMAGE_HEIGHT] = height.toLong()
    reportOut[SanAbi.JOB_REPORT_OUTPUT_WIDTH] = width.toLong()
    reportOut[SanAbi.JOB_REPORT_OUTPUT_HEIGHT] = height.toLong()
    reportOut[SanAbi.JOB_REPORT_DECODED_ROWS] = covered.toLong()

    return when {
      canceled.get() -> SanAbi.STATUS_CANCELED
      covered == height -> SanAbi.STATUS_OK
      ended -> SanAbi.STATUS_TRUNCATED_CONTENT
      else -> SanAbi.STATUS_INPUT_NEEDED
    }
  }

  override fun jobCancel(job: Long): Int {
    canceled.set(true)

    return SanAbi.STATUS_OK
  }

  override fun jobRelease(job: Long): Int = SanAbi.STATUS_OK

  override fun outputCopy(
    lease: Long,
    firstRow: Int,
    rowCount: Int,
    destination: ReaderTarget,
    destinationFirstRow: Int,
  ): Int {
    val bitmap = (destination as ReaderBitmapTarget).bitmap

    if (bitmap in referenced) {
      violations +=
        "Rows $firstRow to ${firstRow + rowCount} wrote a target" +
          " that a frame could draw."
    }

    val pixels = IntArray(width * rowCount) { colorOf(firstRow + it / width) }

    bitmap.setPixels(pixels, 0, width, 0, destinationFirstRow, width, rowCount)
    written += bitmap

    return SanAbi.STATUS_OK
  }

  override fun outputRelease(lease: Long): Int = SanAbi.STATUS_OK

  private fun handle(out: LongArray, value: Long): Int {
    out[0] = value

    return SanAbi.STATUS_OK
  }

  companion object {
    private const val ENGINE = 1L
    private const val CONTENT = 2L
    private const val JOB = 3L
    private const val LEASE = 4L

    /** The opaque color of every pixel of the image row [row]. */
    fun colorOf(row: Int): Int =
      Color.rgb(row and 0xFF, 0x5A, 0xFF - (row and 0xFF))
  }
}

/**
 * An input of [length] zero bytes that delivers at most [portion] bytes per
 * read, each after [pause], so that frames fall between the reads.
 */
internal class PacedInput(
  private val length: Int,
  private val portion: Int,
  private val pause: Duration,
) : ReaderByteInput {
  override val representation = ReaderRepresentation()

  override val capabilities =
    ReaderInputCapabilities(length.toLong(), rewind = false, seek = false)

  @Volatile
  override var position = 0L
    private set

  override suspend fun read(
    destination: ByteArray,
    offset: Int,
    length: Int,
  ): ReaderInputRead {
    val left = (this.length - position).toInt()

    if (length == 0) {
      return ReaderInputRead.NoOp
    }

    if (left == 0) {
      return ReaderInputRead.EndOfInput
    }

    delay(pause)

    val count = minOf(length, portion, left)

    destination.fill(0, offset, offset + count)
    position += count

    return ReaderInputRead.Data(count)
  }

  override suspend fun awaitInput() {}

  override suspend fun seek(position: Long): ReaderInputSeek =
    ReaderInputSeek.Unsupported

  override fun close() {}
}
