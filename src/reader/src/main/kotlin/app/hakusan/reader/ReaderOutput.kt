package app.hakusan.reader

import android.graphics.Bitmap

/**
 * A half-open rectangle `[left, right) × [top, bottom)` in image coordinates.
 *
 * A region is a bounded request or result. It never establishes a provider
 * unit, a reading position, or a chapter boundary.
 */
public data class ReaderImageRegion(
  public val left: Int,
  public val top: Int,
  public val right: Int,
  public val bottom: Int,
) {
  init {
    require(left >= 0 && top >= 0) { "A region origin must not be negative." }
    require(right > left && bottom > top) { "A region must have an area." }
  }

  /** Whether [other] lies entirely inside this region. */
  internal fun contains(other: ReaderImageRegion): Boolean =
    left <= other.left &&
      top <= other.top &&
      right >= other.right &&
      bottom >= other.bottom
}

/**
 * The valid content of one published output.
 *
 * Coverage and its quality are separate from the completion of the input and of
 * the request.
 */
public data class ReaderImageCoverage(
  /** The image width in pixels. */
  public val imageWidth: Int,
  /** The image height in pixels. */
  public val imageHeight: Int,
  /** The covered region, which lies inside the image. */
  public val region: ReaderImageRegion,
  /** Whether the region has its final quality, so no refinement follows. */
  public val finalQuality: Boolean,
) {
  init {
    require(imageWidth > 0 && imageHeight > 0) { "An image must have pixels." }

    require(region.right <= imageWidth && region.bottom <= imageHeight) {
      "The covered region must lie inside the image."
    }
  }
}

/**
 * Ownership of one publication of an output and of the obligation to release
 * it: the display lease of one target.
 *
 * Presentation draws [bitmap] up to the covered rows of [coverage], and it
 * closes the lease once it has stopped drawing the target and a frame recorded
 * without it has synced to the render thread. Closing only returns the target
 * to the adapter that created it, which may then copy into it again.
 *
 * **Claims.** A lease has at most one claimant, which receives the outcome of
 * its release. A closing or released lease cannot be claimed.
 *
 * **Release.** The first [close] runs [release] exactly once. A failed release
 * is never retried and never reports success. With a claimant, the failure
 * travels to the claimant and [close] returns normally, except that a fatal
 * [VirtualMachineError] also propagates. Without one, [close] rethrows the
 * failure. A concurrent or later [close] returns at once, possibly before the
 * first one has finished releasing, so the claimant observes completion, not
 * the return of [close].
 *
 * The [release] action must be short and must not block. It can run inside any
 * call of the owner, and inside the end handler of a session.
 */
public class ReaderOutputLease
internal constructor(
  /** The representation whose content the output shows. */
  public val representation: ReaderRepresentation,
  /** The valid content of the output. */
  public val coverage: ReaderImageCoverage,
  /**
   * The ordinal of this publication of its output, from 1. A later revision
   * covers every row of an earlier one.
   */
  public val revision: Int,
  internal val target: ReaderTarget,
  release: () -> Unit,
) : AutoCloseable {
  private val monitor = Any()
  private var release: (() -> Unit)? = release
  private var claimant: ((Throwable?) -> Unit)? = null

  init {
    require(revision >= 1) { "A publication has a revision from 1." }
  }

  /**
   * The pixels of the target, of which only the covered rows are valid. Never
   * draw them after [close].
   *
   * @throws IllegalStateException if the target holds no Bitmap, as in a JVM
   *   test.
   */
  public val bitmap: Bitmap
    get() =
      (target as? ReaderBitmapTarget)?.bitmap
        ?: error("The target of this lease holds no Bitmap.")

  /**
   * Makes [claimant] the receiver of the release outcome if the lease is open
   * and unclaimed. The claimant runs once, on the thread that releases, with
   * the release failure or null. It must not throw.
   */
  internal fun claim(claimant: (Throwable?) -> Unit): Boolean =
    synchronized(monitor) {
      val claimable = release != null && this.claimant == null

      if (claimable) {
        this.claimant = claimant
      }

      claimable
    }

  /** Whether the lease can still be claimed. */
  internal fun claimable(): Boolean =
    synchronized(monitor) { release != null && claimant == null }

  /**
   * Releases the output if no earlier call has.
   *
   * Any throwable from the release is its failure, so it is never lost. A
   * claimant receives every failure, including a fatal [VirtualMachineError],
   * so its accounting settles. A fatal error then also propagates to this call,
   * because the process cannot continue from it as from a release failure.
   *
   * @throws Throwable the release failure of an unclaimed lease, or a
   *   [VirtualMachineError] of the release.
   */
  override fun close() {
    val action =
      synchronized(monitor) { release.also { release = null } } ?: return

    val failure =
      try {
        action()

        null
      } catch (failure: Throwable) {
        failure
      }

    val receiver = synchronized(monitor) { claimant }

    receiver?.invoke(failure)

    val propagates = receiver == null || failure is VirtualMachineError

    if (failure != null && propagates) {
      throw failure
    }
  }
}
