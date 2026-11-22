package app.hakusan.bench

import android.graphics.Bitmap
import android.graphics.ColorSpace
import androidx.compose.ui.unit.IntSize
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

/** The input and the decoder state of one attempt. */
internal interface Content {
  /**
   * Decodes [step] and hands each output to [publish], the last one with every
   * row of the step, before it returns. [publish] returns once the output is
   * queued for presentation, which waits for the display frame that promotes an
   * output queued before it.
   *
   * @throws IllegalArgumentException when the backend has no such step.
   * @throws IllegalStateException when an output differs from the request.
   */
  suspend fun decode(
    request: Int,
    step: Step,
    publish: suspend (Publication) -> Unit,
  )

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
