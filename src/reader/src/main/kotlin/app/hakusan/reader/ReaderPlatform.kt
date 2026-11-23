package app.hakusan.reader

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.os.Trace
import app.hakusan.reader.san.SanAbiCalls
import app.hakusan.reader.san.SanBinding
import app.hakusan.reader.san.SanCalls
import app.hakusan.reader.san.SanLibrary

/**
 * What the adapter takes from the device: the San library, the Bitmaps of its
 * targets, and the system trace. JVM tests replace all of it, because none of
 * it exists off a device.
 */
internal interface ReaderPlatform {
  /** Loads the San library and establishes its binding. */
  fun bind(): SanBinding

  /** The calls on the loaded library, which [worker] serializes. */
  fun calls(worker: Thread): SanCalls

  /**
   * Creates a target of [width] by [height] pixels in the pixel profile of the
   * output copy, without alpha when [opaque].
   */
  fun createTarget(width: Int, height: Int, opaque: Boolean): ReaderTarget

  /** Frees the pixels of [target], which nothing draws or copies any longer. */
  fun recycle(target: ReaderTarget)

  /** Opens a trace section of the calling thread. [name] names no content. */
  fun beginSection(name: String)

  /** Closes the innermost trace section of the calling thread. */
  fun endSection()
}

/** Runs [block] inside the trace section [name]. */
internal inline fun <T> ReaderPlatform.section(
  name: String,
  block: () -> T,
): T {
  beginSection(name)

  try {
    return block()
  } finally {
    endSection()
  }
}

/** The platform of a device with the San library that the application packs. */
internal object AndroidReaderPlatform : ReaderPlatform {
  override fun bind(): SanBinding = SanLibrary.bind()

  override fun calls(worker: Thread): SanCalls = SanAbiCalls(worker)

  // A mutable, premultiplied Bitmap, as the copy requires of its destination.
  // The platform erases an ARGB_8888 Bitmap that it creates without alpha,
  // which writes every page of it before the first copy, so an opaque target
  // is created with alpha and loses the flag before anything else sees it.
  override fun createTarget(
    width: Int,
    height: Int,
    opaque: Boolean,
  ): ReaderTarget {
    val bitmap =
      Bitmap.createBitmap(
        width,
        height,
        Bitmap.Config.ARGB_8888,
        true,
        ColorSpace.get(ColorSpace.Named.SRGB),
      )

    if (opaque) {
      bitmap.setHasAlpha(false)
    }

    return ReaderBitmapTarget(bitmap)
  }

  override fun recycle(target: ReaderTarget) {
    check(target is ReaderBitmapTarget) { "The target holds no Bitmap." }

    target.bitmap.recycle()
  }

  override fun beginSection(name: String) {
    Trace.beginSection(name)
  }

  override fun endSection() {
    Trace.endSection()
  }
}
