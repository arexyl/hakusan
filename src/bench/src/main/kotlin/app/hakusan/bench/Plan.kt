package app.hakusan.bench

import android.content.Intent
import androidx.compose.ui.unit.IntSize
import app.hakusan.reader.ReaderBackwardVariant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** A workload: one fixture and the requests of one attempt on it. */
internal enum class Cell(val id: String, val fixture: String) {
  /** The ordinary page at 1:1, which the viewport scales. */
  ORDINARY("ordinary", "journey/ordinary.png"),

  /**
   * The ordinary page from an input that withholds the bytes behind its hold
   * point until the display has shown the first useful output, and then
   * delivers them.
   */
  EARLY("early", "journey/ordinary.png"),

  /**
   * As [EARLY] up to the shown output, after which the input ends inside the
   * image data. The request fails, and the display keeps the output.
   */
  EARLY_TRUNCATED("early-truncated", "journey/ordinary.png"),

  /** As [EARLY_TRUNCATED], with an input whose next read fails. */
  EARLY_FAILED("early-failed", "journey/ordinary.png"),

  /**
   * As [EARLY] up to the shown output, after which the request is cancelled
   * while its input waits.
   */
  CANCEL_WAIT("cancel-wait", "journey/ordinary.png"),

  /** The ordinary page, cancelled while its decode runs. */
  CANCEL_DECODE("cancel-decode", "journey/ordinary.png"),

  /** The ordinary page, cancelled with its first output queued. */
  CANCEL_PUBLICATION("cancel-publication", "journey/ordinary.png"),

  /** The ordinary page from an input that delivers it in slow chunks. */
  THROTTLED("throttled", "journey/ordinary.png"),

  /** The large page, decoded at the width of the viewport. */
  LARGE("large", "qualification/timed/large.png"),

  /** The first band of the long segment, from a new input. */
  LONG_FIRST("long-first", "journey/long.png"),

  /**
   * A scroll over the long segment in one attempt: three bands forward from the
   * top, the two above the last one again in backward order, a seek to the
   * middle and one to the end, and the band above the end, which the attempt
   * has not decoded before.
   */
  LONG_SCROLL("long-scroll", "journey/long.png");

  /**
   * The requests of one attempt, in order.
   *
   * A band holds the image rows that fill the viewport at its width, so one
   * band is one screen of the segment. The first useful output holds the image
   * rows that fill the top [useful] rows of the viewport, rounded up.
   */
  fun steps(image: IntSize, viewport: IntSize, useful: Int): List<Step> {
    val band = (viewport.height.toLong() * image.width / viewport.width).toInt()
    val last = image.height - band

    val shown =
      ((useful.toLong() * image.width + viewport.width - 1) / viewport.width)
        .toInt()

    val tops =
      when (this) {
        ORDINARY -> return listOf(Whole(width = null))

        EARLY -> return listOf(Held(shown, Ending.RELEASE))
        EARLY_TRUNCATED -> return listOf(Held(shown, Ending.TRUNCATE))
        EARLY_FAILED -> return listOf(Held(shown, Ending.FAIL))
        CANCEL_WAIT -> return listOf(Held(shown, Ending.CANCEL))
        CANCEL_DECODE -> return listOf(Interrupted(Moment.DECODING))
        CANCEL_PUBLICATION -> return listOf(Interrupted(Moment.PUBLISHED))
        THROTTLED -> return listOf(Throttled)
        LARGE -> return listOf(Whole(width = viewport.width))
        LONG_FIRST -> listOf(0)
        LONG_SCROLL ->
          listOf(
            0,
            band,
            2 * band,
            band,
            0,
            last / 2,
            last,
            last - band,
          )
      }

    return tops.map { Band(top = it, bottom = it + band) }
  }
}

/** The decode backends of the harness. */
internal enum class BackendKind(val id: String) {
  IMAGE_DECODER("image-decoder"),
  REGION_DECODER("region-decoder"),

  /** The native path, which decodes a band again at every request of it. */
  SAN("san"),

  /** The native path with the decoded strips of its bands retained. */
  SAN_STRIPS("san-strips");

  /** Creates the backend of a block whose display has [viewport]. */
  fun create(viewport: IntSize): Backend =
    when (this) {
      IMAGE_DECODER -> ImageDecoderBackend()
      REGION_DECODER -> RegionDecoderBackend()
      SAN -> SanBackend(viewport, ReaderBackwardVariant.REDECODE)
      SAN_STRIPS -> SanBackend(viewport, ReaderBackwardVariant.RETAIN_STRIPS)
    }
}

/**
 * One block as the host requested it.
 *
 * The host passes every value as an extra of the launch intent, so a block
 * needs no input on the device.
 */
internal data class Plan(
  val cell: Cell,
  val backend: BackendKind,
  /** The attempts before the measured ones, which run the same sequence. */
  val warmups: Int,
  val attempts: Int,
  /** The wait between the close of an attempt and its settled mark. */
  val settle: Duration,
  /** The display frames that an output stays up after its first draw. */
  val hold: Int,
  /** The refresh rate of the display mode that the block requests, in Hz. */
  val refresh: Int,
  /** The file name of the record in the external files directory. */
  val record: String,
) {
  init {
    require(warmups >= 0 && attempts > 0) { "A block needs attempts." }
    require(hold > 0 && !settle.isNegative()) { "The waits must be valid." }
    require(refresh > 0) { "A block names its refresh rate." }

    require(RECORD_NAME.matches(record)) {
      "The record must be a plain file name."
    }
  }

  companion object {
    private val RECORD_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

    /**
     * Reads the plan of [intent], or returns null when it names no cell, as
     * when the launcher starts the harness.
     *
     * @throws IllegalArgumentException when an extra has no valid value.
     */
    fun of(intent: Intent): Plan? {
      val cell = intent.getStringExtra("cell") ?: return null
      val backend = intent.getStringExtra("backend")

      return Plan(
        cell =
          requireNotNull(Cell.entries.find { it.id == cell }) {
            "No such cell."
          },
        backend =
          requireNotNull(BackendKind.entries.find { it.id == backend }) {
            "No such backend."
          },
        warmups = intent.getIntExtra("warmups", 5),
        attempts = intent.getIntExtra("attempts", 20),
        settle = intent.getIntExtra("settle_ms", 500).milliseconds,
        hold = intent.getIntExtra("hold", 3),
        refresh = intent.getIntExtra("refresh_hz", 0),
        record = requireNotNull(intent.getStringExtra("record")),
      )
    }
  }
}
