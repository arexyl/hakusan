package app.hakusan.reader

import android.graphics.Bitmap

/**
 * The pixel backing of one output, which a copy fills and presentation draws.
 * The Android output adapter's targets hold a Bitmap, and a JVM test supplies
 * its own kind, because no Bitmap exists off a device.
 */
internal interface ReaderTarget

/** A mutable `ARGB_8888` sRGB [bitmap] that the adapter created as a target. */
internal class ReaderBitmapTarget(val bitmap: Bitmap) : ReaderTarget
