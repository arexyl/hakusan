package app.hakusan.reader

import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * States how the platform decoder scales the large page of the corpus to the
 * width of the viewport, and records how far its image then is from the area
 * average of the page. A comparison of that decoder with one that averages
 * areas compares two different images, and these tests say by how much.
 *
 * The reference is the area average by choice: the page shrinks to about a
 * quarter of its width, so a pixel of the result covers some fourteen pixels of
 * the image, and the average counts each of them. It is the mean of the encoded
 * samples, as the engine computes it, and not of the light that they encode.
 */
@RunWith(AndroidJUnit4::class)
class PlatformScaleDeviceTest {
  @Test
  fun sampledDecodeKeepsOnePixelOfEachBlockOfThePage() {
    val page = DeviceCorpus.file(LARGE)
    val image = decodeWithPlatform(page.bytes)

    try {
      val sampled =
        decodeWithPlatform(page.bytes) { setTargetSampleSize(SAMPLE_SIZE) }

      try {
        assertEquals(page.width / SAMPLE_SIZE, sampled.width)
        assertEquals(page.height / SAMPLE_SIZE, sampled.height)

        // A decode that averaged its blocks would match at no offset, and a
        // page that is not flat throughout matches at one offset alone.
        val kept = keptOffsets(image, sampled)

        assertEquals(
          "the pixels of a block that the decode keeps",
          1,
          kept.size,
        )
        Log.i(
          TAG,
          "sample size $SAMPLE_SIZE keeps the pixel at ${kept.single()}",
        )
      } finally {
        sampled.recycle()
      }
    } finally {
      image.recycle()
    }
  }

  @Test
  fun decodeAtTheGridIsTheSampledPageFilteredAndNotItsAreaAverage() {
    val page = DeviceCorpus.file(LARGE)
    val grid = ReaderImageGeometry(page.width, page.height).grid(VIEWPORT_WIDTH)
    val image = decodeWithPlatform(page.bytes)

    val average =
      try {
        assertEquals(page.samplesSha256, rgbSha256(image))

        areaAverage(image, grid.width, grid.height)
      } finally {
        image.recycle()
      }

    val sampled =
      decodeWithPlatform(page.bytes) { setTargetSampleSize(SAMPLE_SIZE) }

    val filtered =
      try {
        Weights.entries.associateWith {
          filterLinearly(sampled, grid.width, grid.height, it)
        }
      } finally {
        sampled.recycle()
      }

    val target =
      decodeWithPlatform(page.bytes) { setTargetSize(grid.width, grid.height) }

    val decoded =
      try {
        assertEquals(grid.width, target.width)
        assertEquals(grid.height, target.height)

        target.pixels()
      } finally {
        target.recycle()
      }

    val whole = Area(0, 0, grid.width, grid.height)
    val fromFilters = filtered.mapValues { (_, pixels) ->
      ScaleError.of(pixels, decoded, grid.width, listOf(whole))
    }

    // The filter of the device is the one whose image the decode is nearest.
    val (weights, fromFiltered) = fromFilters.entries.minBy { it.value.mean }
    val fromAverage = ScaleError.of(average, decoded, grid.width, listOf(whole))

    val byTexture =
      page.regions
        .groupBy { it.texture }
        .mapValues { (_, regions) ->
          val areas = regions.map { it.within(page, grid) }

          ScaleError.of(average, decoded, grid.width, areas)
        }

    val record =
      JSONObject()
        .put("page", LARGE)
        .put("image", JSONArray(listOf(page.width, page.height)))
        .put("grid", JSONArray(listOf(grid.width, grid.height)))
        .put("sdk", Build.VERSION.SDK_INT)
        .put("release", Build.VERSION.RELEASE)
        .put("sample_size", SAMPLE_SIZE)
        .put("filter_weights", weights.name.lowercase())
        .put("from_filtered_sample", fromFiltered.json())
        .put("from_area_average", fromAverage.json())
        .put(
          "from_area_average_by_texture",
          JSONObject(byTexture.mapValues { it.value.json() }),
        )

    publish(
      record,
      grid,
      mapOf("area-average" to average, "decoded" to decoded),
    )

    assertTrue(
      "The decode is up to ${fromFiltered.maximum} levels from the sample" +
        " filtered with $weights weights, in ${fromFiltered.unequal} of the" +
        " samples.",
      fromFiltered.maximum <= weights.maximum &&
        fromFiltered.unequal < weights.unequal,
    )

    assertTrue(
      "The decode is ${fromFiltered.mean} from the filtered sample and" +
        " ${fromAverage.mean} from the area average.",
      fromFiltered.mean < fromAverage.mean,
    )
  }

  /**
   * Returns the offsets inside a block of [SAMPLE_SIZE] by [SAMPLE_SIZE] pixels
   * of [image] at which every pixel of [sampled] is the pixel of its block.
   */
  private fun keptOffsets(
    image: Bitmap,
    sampled: Bitmap,
  ): List<Pair<Int, Int>> {
    val row = IntArray(image.width)
    val kept = IntArray(sampled.width)

    return buildList {
      for (down in 0..<SAMPLE_SIZE) {
        val alive = BooleanArray(SAMPLE_SIZE) { true }

        for (y in 0..<sampled.height) {
          if (alive.none { it }) {
            break
          }

          image.getPixels(
            row,
            0,
            image.width,
            0,
            y * SAMPLE_SIZE + down,
            image.width,
            1,
          )
          sampled.getPixels(kept, 0, sampled.width, 0, y, sampled.width, 1)

          for (across in 0..<SAMPLE_SIZE) {
            alive[across] =
              alive[across] &&
                kept.indices.all { kept[it] == row[it * SAMPLE_SIZE + across] }
          }
        }

        for (across in 0..<SAMPLE_SIZE) {
          if (alive[across]) {
            add(across to down)
          }
        }
      }
    }
  }

  /**
   * Returns [image] at [width] by [height] pixels, each the linear
   * interpolation of the four pixels around its center in the arithmetic of
   * [weights], with the edge pixels repeated outward.
   */
  private fun filterLinearly(
    image: Bitmap,
    width: Int,
    height: Int,
    weights: Weights,
  ): IntArray {
    val pixels = image.pixels()
    val result = IntArray(width * height)
    val columns = List(width) { Tap.at(it, width, image.width, weights) }

    for (y in 0..<height) {
      val down = Tap.at(y, height, image.height, weights)
      val upper = down.first * image.width
      val lower = down.second * image.width

      for (x in 0..<width) {
        val across = columns[x]
        var pixel = 0xFF shl 24

        for (shift in intArrayOf(16, 8, 0)) {
          val above =
            across.between(
              (pixels[upper + across.first] ushr shift) and 0xFF,
              (pixels[upper + across.second] ushr shift) and 0xFF,
            )

          val below =
            across.between(
              (pixels[lower + across.first] ushr shift) and 0xFF,
              (pixels[lower + across.second] ushr shift) and 0xFF,
            )

          val value = above * (1 - down.weight) + below * down.weight

          val level =
            when (weights) {
              Weights.EXACT -> value.roundToInt()
              // Sixteenths make the sum a whole count of 256ths, which the
              // conversion cuts off.
              Weights.SIXTEENTHS -> value.toInt()
            }

          pixel = pixel or (level.coerceIn(0, 255) shl shift)
        }

        result[y * width + x] = pixel
      }
    }

    return result
  }

  /**
   * The two pixels of a row or a column of an image around the center of a
   * pixel of the result, with the [weight] of the [second].
   */
  private class Tap(val first: Int, val second: Int, val weight: Double) {
    /** The value at the center between [atFirst] and [atSecond]. */
    fun between(atFirst: Int, atSecond: Int): Double =
      atFirst * (1 - weight) + atSecond * weight

    companion object {
      /** The tap of pixel [index] of [count] over [source] pixels. */
      fun at(index: Int, count: Int, source: Int, weights: Weights): Tap {
        val center = (index + 0.5) * source / count - 0.5
        val first = floor(center).toInt()
        val weight = center - first

        return Tap(
          first = first.coerceIn(0, source - 1),
          second = (first + 1).coerceIn(0, source - 1),
          weight =
            when (weights) {
              Weights.EXACT -> weight
              Weights.SIXTEENTHS -> floor(weight * 16) / 16
            },
        )
      }
    }
  }

  /**
   * How a linear filter holds the weight of a tap and rounds its sum, with how
   * far a decode may lie from the model: by at most [maximum] levels, in less
   * than the share [unequal] of its samples.
   */
  private enum class Weights(val maximum: Int, val unequal: Double) {
    /**
     * The weight as it is, and the sum rounded to the nearest level. The
     * arithmetic of the platform may round a sample to the level beside it.
     */
    EXACT(maximum = 1, unequal = 0.01),

    /**
     * The weight cut to sixteenths, and the sum cut to a whole level, which the
     * decode of Android 13 fits. A weight of the platform may fall into the
     * sixteenth beside the one of the model, which moves a sample by at most a
     * sixteenth of the range of a level.
     */
    SIXTEENTHS(maximum = 16, unequal = 0.05),
  }

  /** A rectangle of pixels of a grid, the far edges exclusive. */
  private class Area(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
  )

  /**
   * The pixels of [grid] whose area lies wholly inside this region of [page].
   * The filter of the platform reads a few pixels beyond that area, which the
   * distance of a region from the border and the label of its panel covers.
   */
  private fun CorpusRegion.within(page: CorpusFile, grid: ReaderImageGeometry) =
    Area(
      left = (left * grid.width + page.width - 1) / page.width,
      top = (top * grid.height + page.height - 1) / page.height,
      right = right * grid.width / page.width,
      bottom = bottom * grid.height / page.height,
    )

  /**
   * The absolute differences between the red, green, and blue samples of two
   * images over some areas: [counts] holds how many samples differ by each
   * value.
   */
  private class ScaleError(private val counts: LongArray) {
    private val samples = counts.sum()

    val mean: Double
      get() =
        counts.withIndex().sumOf { it.index * it.value.toDouble() } / samples

    /** The share of the samples that differ. */
    val unequal: Double
      get() = (samples - counts[0]).toDouble() / samples

    val maximum: Int
      get() = counts.indexOfLast { it != 0L }

    /** The nearest-rank percentile [share] of the differences. */
    fun percentile(share: Double): Int {
      val rank = maxOf(1L, ceil(share * samples).toLong())
      var below = 0L

      return counts.indices.first { difference ->
        below += counts[difference]

        below >= rank
      }
    }

    fun json(): JSONObject {
      val squares =
        counts.withIndex().sumOf { it.index.toDouble() * it.index * it.value }

      return JSONObject()
        .put("samples", samples)
        .put("unequal", unequal)
        .put("mean", mean)
        .put("p99", percentile(0.99))
        .put("maximum", maximum)
        .put(
          "psnr_db",
          if (squares == 0.0) {
            JSONObject.NULL
          } else {
            10 * log10(255.0 * 255.0 * samples / squares)
          },
        )
    }

    companion object {
      /** The differences of [actual] from [expected], rows of [width]. */
      fun of(
        expected: IntArray,
        actual: IntArray,
        width: Int,
        areas: List<Area>,
      ): ScaleError {
        val counts = LongArray(256)

        for (area in areas) {
          for (y in area.top..<area.bottom) {
            for (x in area.left..<area.right) {
              val wanted = expected[y * width + x]
              val found = actual[y * width + x]

              for (shift in intArrayOf(16, 8, 0)) {
                val difference =
                  ((wanted ushr shift) and 0xFF) - ((found ushr shift) and 0xFF)

                counts[abs(difference)]++
              }
            }
          }
        }

        return ScaleError(counts)
      }
    }
  }

  /**
   * Logs [record] and, where the run names a directory for the output of its
   * tests, writes it there with [images], rows of the width of [grid].
   */
  private fun publish(
    record: JSONObject,
    grid: ReaderImageGeometry,
    images: Map<String, IntArray>,
  ) {
    for (key in record.keys()) {
      Log.i(TAG, "$key: ${record.get(key)}")
    }

    val directory =
      InstrumentationRegistry.getArguments()
        .getString(OUTPUT_ARGUMENT)
        ?.let(::File) ?: return

    check(directory.isDirectory || directory.mkdirs()) {
      "The output directory of the tests cannot be created."
    }

    File(directory, "large-page-scale.json").writeText(record.toString(2))

    for ((name, pixels) in images) {
      val image =
        Bitmap.createBitmap(
          pixels,
          grid.width,
          grid.height,
          Bitmap.Config.ARGB_8888,
        )

      try {
        File(directory, "large-page-$name.png").outputStream().use {
          check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
      } finally {
        image.recycle()
      }
    }
  }

  private companion object {
    const val TAG = "PlatformScale"
    const val LARGE = "qualification/timed/large.png"

    /** The width of the viewport of the reference workload. */
    const val VIEWPORT_WIDTH = 1080

    /**
     * The largest whole step at which the page is still no narrower than the
     * viewport: 4096 columns give 1365.
     */
    const val SAMPLE_SIZE = 3

    /** The argument in which a run names the directory it collects. */
    const val OUTPUT_ARGUMENT = "additionalTestOutputDir"
  }
}
