package app.hakusan.bench

import android.graphics.Bitmap
import android.graphics.ColorSpace
import androidx.compose.ui.unit.IntSize
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.json.JSONObject

/** One request of a workload. */
internal sealed interface Step

/**
 * The whole image at 1:1, or scaled down to [width] columns when the image is
 * wider.
 */
internal data class Whole(val width: Int?) : Step {
  /**
   * The output grid of an image: the smaller of [width] and the image width,
   * with the height rounded to the nearest row, halves up, and at least one.
   */
  fun grid(image: IntSize): IntSize {
    val columns = minOf(width ?: image.width, image.width)
    val scaled =
      (2L * image.height * columns + image.width) / (2L * image.width)

    return IntSize(columns, maxOf(scaled, 1L).toInt())
  }
}

/** The image rows `[top, bottom)` at every column, at 1:1. */
internal data class Band(val top: Int, val bottom: Int) : Step {
  init {
    require(top in 0..<bottom) { "A band must have rows." }
  }
}

/** How a held step goes on once the display has shown its useful output. */
internal enum class Ending(val id: String) {
  /** The input delivers the rest, and the request completes. */
  RELEASE("release"),

  /** The input ends at the hold point, inside the image data. */
  TRUNCATE("truncate"),

  /** The next read of the input fails. */
  FAIL("fail"),

  /** The request is cancelled while its input waits. */
  CANCEL("cancel"),
}

/** The state of a request with a local input in which it is cancelled. */
internal enum class Moment(val id: String) {
  /**
   * Right after the second publication was taken. The display holds the first
   * output and the backend the second, so none can be queued, and the decoder
   * is inside the image.
   */
  DECODING("decoding"),

  /** With the first publication queued and not taken. */
  PUBLISHED("published"),
}

/**
 * The whole image at 1:1 from the local file, and a cancellation of the request
 * at [moment]. A decode that ends before the cancellation reaches it completes,
 * so the step ends as canceled or as completed.
 */
internal data class Interrupted(val moment: Moment) : Step

/**
 * The whole image at 1:1 from an input that withholds every byte behind the
 * hold point of the fixture until the display has shown the output with every
 * row that the bytes before it decode, and then goes on as [ending] says. The
 * top [rows] of the image are its first useful output, which that output must
 * contain.
 */
internal data class Held(val rows: Int, val ending: Ending) : Step {
  init {
    require(rows > 0) { "A useful output must have rows." }
  }

  companion object {
    /**
     * The longest time from the last byte before the hold point to the shown
     * output. A longer one fails the request.
     */
    val LIMIT = 10.seconds
  }
}

/**
 * The whole image at 1:1 from an input that delivers the file in chunks of at
 * most [CHUNK] bytes, none sooner than [INTERVAL] after the one before. It is a
 * diagnostic of output under a slow input, and no latency target applies to it.
 */
internal data object Throttled : Step {
  const val CHUNK = 16 shl 10

  val INTERVAL = 8.milliseconds

  /** The shortest time in which the input delivers [bytes]. */
  fun time(bytes: Long): Duration =
    INTERVAL * ((bytes + CHUNK - 1) / CHUNK).toInt()
}

/** Presentation as a backend sees it. */
internal interface Display {
  /**
   * Queues [output] for presentation and returns once it is queued, which waits
   * for the display frame that promotes an output queued before it.
   */
  suspend fun publish(output: Publication)

  /**
   * Suspends until a display frame has drawn an output of [request] with at
   * least [rows] rows, and the draw has stayed for the hold frames of the
   * block.
   */
  suspend fun shown(request: Int, rows: Int)
}

/**
 * How a request ended: as `completed`, as `canceled`, or as `failed` with the
 * category of the [failure]. Its step names the one that it must end as, and a
 * backend fails a request that ends otherwise.
 */
internal data class Terminal(val outcome: String, val failure: String? = null) {
  companion object {
    val COMPLETED = Terminal("completed")
    val CANCELED = Terminal("canceled")

    fun failed(category: String): Terminal = Terminal("failed", category)
  }
}

/** What a backend reports of one request beside its outputs. */
internal data class Report(
  val terminal: Terminal = Terminal.COMPLETED,
  /** The bytes of its inputs, or null when the platform reads the file. */
  val input: Acquired? = null,
  /** When a held input went on, on the clock of the record. */
  val resumed: Long? = null,
  /** When the backend requested the cancellation, on that clock. */
  val canceled: Long? = null,
  /**
   * When the cancelled request had settled: its execution had returned, with
   * its job, its input, and its content ended.
   */
  val returned: Long? = null,
)

/**
 * A decode backend behind the display consumer.
 *
 * It supplies decoded output only. Every call but [close] runs on the decode
 * thread, which a backend leaves only for a thread that it names as its
 * [worker].
 */
internal interface Backend {
  /**
   * The name of the thread that decodes in place of the decode thread, or null
   * when the decode thread itself decodes. The record states its priority.
   */
  val worker: String?
    get() = null

  /** Opens [fixture] as the input and the decoder state of one attempt. */
  suspend fun open(fixture: Fixture): Content

  /**
   * Reads the counters of the memory that the backend accounts itself, or
   * returns null when it accounts none or holds nothing to count yet. With
   * [reset], the read also restarts the peaks. No decode of the backend runs
   * during the call.
   */
  suspend fun ledger(reset: Boolean): JSONObject? = null

  /**
   * Ends the backend after the last attempt of its block, on any thread, once
   * no decode of the block runs any longer.
   */
  suspend fun close() {}
}

/**
 * What the inputs of one request delivered: [bytes] in all, and [again] of them
 * that an input of the same attempt had delivered before. They are the bytes
 * read from the file, so each input may count one portion that the decoder no
 * longer took.
 */
internal data class Acquired(val bytes: Long, val again: Long)

/** The input and the decoder state of one attempt. */
internal interface Content {
  /**
   * Decodes [step] and publishes each output on [display] before it returns.
   * The last one has every row of the step when the request completes and the
   * backend requested no cancellation of it.
   *
   * @throws IllegalArgumentException when the backend has no such step.
   * @throws IllegalStateException when an output differs from the request.
   */
  suspend fun decode(request: Int, step: Step, display: Display): Report

  /**
   * Ends the attempt. Presentation holds none of its outputs by then, unless
   * the block ended without display frames.
   */
  suspend fun close()
}

/**
 * Checks that the platform honored the request for a software Bitmap of [size]
 * in the pixel profile that every backend supplies.
 */
internal fun Bitmap.requireProfile(size: IntSize) {
  check(config == Bitmap.Config.ARGB_8888) { "The output is $config." }

  check(colorSpace == ColorSpace.get(ColorSpace.Named.SRGB)) {
    "The output is in ${colorSpace?.name}."
  }

  check(width == size.width && height == size.height) {
    "The output is $width by $height, not $size."
  }
}
