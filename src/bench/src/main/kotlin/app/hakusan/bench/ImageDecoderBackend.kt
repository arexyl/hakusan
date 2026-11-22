package app.hakusan.bench

import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import androidx.compose.ui.unit.IntSize

/**
 * The bounded final-only reference for a whole image: `ImageDecoder` with
 * software allocation and sRGB output.
 *
 * It decodes at the output grid of the step, so the platform scales while it
 * decodes. Each decode reads the file again, and the backend keeps no state
 * between two decodes.
 */
internal class ImageDecoderBackend : Backend {
  override suspend fun open(fixture: Fixture): Content {
    val source = ImageDecoder.createSource(fixture.file)

    return object : Content {
      override suspend fun decode(
        request: Int,
        step: Step,
        display: Display,
      ): Report {
        require(step is Whole) { "ImageDecoder decodes whole images only." }

        var grid = IntSize.Zero

        val bitmap =
          ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val image = IntSize(info.size.width, info.size.height)

            grid = step.grid(image)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))

            if (grid != image) {
              decoder.setTargetSize(grid.width, grid.height)
            }
          }

        try {
          bitmap.requireProfile(grid)
        } catch (failure: IllegalStateException) {
          bitmap.recycle()

          throw failure
        }

        display.publish(
          Publication(
            bitmap = bitmap,
            request = request,
            revision = 1,
            rows = bitmap.height,
            release = bitmap::recycle,
          )
        )

        return Report()
      }

      override suspend fun close() {}
    }
  }
}
