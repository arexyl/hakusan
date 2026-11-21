package app.hakusan.bench

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ColorSpace
import android.graphics.Rect
import androidx.compose.ui.unit.IntSize

/**
 * The final-only reference for the bands of a long image: `BitmapRegionDecoder`
 * with software allocation and sRGB output.
 *
 * It opens the fixture as a stream, as a reader receives its input, and the
 * platform copies the whole stream before the first decode. One decoder serves
 * every band of an attempt. A band is decoded at 1:1, because the platform
 * samples only by whole factors, so the viewport scales it.
 */
internal class RegionDecoderBackend : Backend {
  override fun open(fixture: Fixture): Content {
    val decoder =
      fixture.file.inputStream().use {
        checkNotNull(BitmapRegionDecoder.newInstance(it)) { "No decoder." }
      }

    return object : Content {
      override fun decode(
        request: Int,
        step: Step,
        publish: (Publication) -> Unit,
      ) {
        require(step is Band) { "BitmapRegionDecoder decodes bands only." }

        val options =
          BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
          }

        val region = Rect(0, step.top, decoder.width, step.bottom)

        val bitmap =
          checkNotNull(decoder.decodeRegion(region, options)) { "No output." }

        try {
          bitmap.requireProfile(IntSize(region.width(), region.height()))
        } catch (failure: IllegalStateException) {
          bitmap.recycle()

          throw failure
        }

        publish(
          Publication(
            bitmap = bitmap,
            request = request,
            revision = 1,
            rows = bitmap.height,
            release = bitmap::recycle,
          )
        )
      }

      override fun close() {
        decoder.recycle()
      }
    }
  }
}
