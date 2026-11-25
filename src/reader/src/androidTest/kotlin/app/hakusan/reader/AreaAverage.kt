package app.hakusan.reader

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.junit.Assert.fail

/**
 * Decodes [bytes] with the platform decoder into a software Bitmap in sRGB,
 * after [configure] has set what the decode differs in. The caller recycles it.
 */
internal fun decodeWithPlatform(
  bytes: ByteArray,
  configure: ImageDecoder.() -> Unit = {},
): Bitmap =
  ImageDecoder.decodeBitmap(
    ImageDecoder.createSource(ByteBuffer.wrap(bytes))
  ) { decoder, _, _ ->
    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
    decoder.configure()
  }

/**
 * Returns the SHA-256 of the opaque [image] as rows of RGB samples, read row by
 * row, so that an image of a hundred megabytes needs no second copy. A pixel
 * that is not opaque fails the test.
 */
internal fun rgbSha256(image: Bitmap): String {
  val digest = MessageDigest.getInstance("SHA-256")
  val pixels = IntArray(image.width)
  val row = ByteArray(image.width * 3)

  for (y in 0..<image.height) {
    image.getPixels(pixels, 0, image.width, 0, y, image.width, 1)

    for (x in pixels.indices) {
      val pixel = pixels[x]

      // No message is built for the millions of pixels that pass.
      if (pixel ushr 24 != 0xFF) {
        fail("alpha at $x, $y is ${pixel ushr 24}")
      }

      row[x * 3] = (pixel shr 16).toByte()
      row[x * 3 + 1] = (pixel shr 8).toByte()
      row[x * 3 + 2] = pixel.toByte()
    }

    digest.update(row)
  }

  return hex(digest.digest())
}

/**
 * Returns the opaque [image] at [width] by [height] pixels as its area average:
 * each sample is the mean of that sample over the area of the image that the
 * pixel covers, with a pixel of the image that the area cuts counted by the
 * part inside, rounded to the nearest value, halves up.
 *
 * The sums are whole numbers. Along a row, a column of the image is [width]
 * units wide and a column of the result as many units as the image has columns,
 * and likewise down a column, so every weight is a count of units and the
 * weights of one pixel add up to the pixel count of the image.
 */
internal fun areaAverage(image: Bitmap, width: Int, height: Int): IntArray {
  val area = image.width.toLong() * image.height
  val result = IntArray(width * height)
  val row = IntArray(image.width)
  val across = LongArray(width * 3)
  val sums = LongArray(width * 3)

  for (y in 0..<height) {
    val top = y.toLong() * image.height
    val bottom = top + image.height
    var source = (top / height).toInt()

    sums.fill(0)

    while (source.toLong() * height < bottom) {
      val first = maxOf(top, source.toLong() * height)
      val covered = minOf(bottom, (source + 1L) * height) - first

      image.getPixels(row, 0, image.width, 0, source, image.width, 1)
      sumAcross(row, across)

      for (index in sums.indices) {
        sums[index] += covered * across[index]
      }

      source++
    }

    for (x in 0..<width) {
      var pixel = 0xFF shl 24

      for (channel in 0..<3) {
        val sample = (2 * sums[x * 3 + channel] + area) / (2 * area)

        pixel = pixel or (sample.toInt() shl (16 - 8 * channel))
      }

      result[y * width + x] = pixel
    }
  }

  return result
}

/**
 * Writes into [sums], three per column of the result, the red, green, and blue
 * samples of [row] weighted by the units of each column of the result that they
 * cover.
 */
private fun sumAcross(row: IntArray, sums: LongArray) {
  val width = sums.size / 3

  sums.fill(0)

  for (x in 0..<width) {
    val left = x.toLong() * row.size
    val right = left + row.size
    var source = (left / width).toInt()

    while (source.toLong() * width < right) {
      val first = maxOf(left, source.toLong() * width)
      val covered = minOf(right, (source + 1L) * width) - first
      val pixel = row[source]

      sums[x * 3] += covered * ((pixel ushr 16) and 0xFF)
      sums[x * 3 + 1] += covered * ((pixel ushr 8) and 0xFF)
      sums[x * 3 + 2] += covered * (pixel and 0xFF)
      source++
    }
  }
}
