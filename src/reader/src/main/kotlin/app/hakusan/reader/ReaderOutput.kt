package app.hakusan.reader

import android.graphics.Bitmap

/** The width and the height of an image or of an output grid, in pixels. */
public data class ReaderImageGeometry(
  public val width: Int,
  public val height: Int,
) {
  init {
    require(width > 0 && height > 0) { "A geometry must have pixels." }
  }

  /**
   * The output grid of an image of this geometry for [maxOutputWidth], or the
   * image itself for null: the whole image scaled uniformly to that width,
   * never up, with the height rounded to the nearest row, halves up.
   */
  internal fun grid(maxOutputWidth: Int?): ReaderImageGeometry {
    val columns = minOf(maxOutputWidth ?: width, width)
    val rows = (2L * height * columns + width) / (2L * width)

    return ReaderImageGeometry(columns, maxOf(rows, 1L).toInt())
  }

  /**
   * The rows of [grid], an output grid of an image of this geometry, that the
   * image rows `[top, bottom)` intersect. Adjacent bands share one grid, and a
   * grid row that straddles their boundary belongs to both.
   */
  internal fun gridRows(
    top: Int,
    bottom: Int,
    grid: ReaderImageGeometry,
  ): IntRange {
    val first = top.toLong() * grid.height / height
    val end = (bottom.toLong() * grid.height + height - 1) / height

    return first.toInt()..<end.toInt()
  }
}

/**
 * What a content request asks of its image: the whole image or a band of its
 * rows, at a scale of 1:1 or scaled down to a width.
 *
 * A request never establishes a provider unit, a reading position, or a chapter
 * boundary.
 */
public sealed interface ReaderOutputRequest {
  /**
   * The largest width of the output grid in pixels, or null for a scale of 1:1.
   * The grid is the whole image scaled uniformly to that width, and an image
   * that is narrower keeps its size.
   */
  public val maxOutputWidth: Int?

  /** The whole image. */
  public data class Whole(override val maxOutputWidth: Int? = null) :
    ReaderOutputRequest {
    init {
      require(maxOutputWidth == null || maxOutputWidth > 0) {
        "An output width must have pixels."
      }
    }
  }

  /**
   * The image rows `[top, bottom)` at every column of an image of [geometry],
   * which the caller learned from an earlier request of that image.
   */
  public data class Band(
    public val geometry: ReaderImageGeometry,
    public val top: Int,
    public val bottom: Int,
    override val maxOutputWidth: Int? = null,
  ) : ReaderOutputRequest {
    init {
      require(top in 0..<bottom && bottom <= geometry.height) {
        "A band must have rows inside its image."
      }

      require(maxOutputWidth == null || maxOutputWidth > 0) {
        "An output width must have pixels."
      }
    }
  }
}

/**
 * Whether [coverage] is an output of this request: its grid is the grid of the
 * request, and for a band its image is the image of the band and its region
 * spans every column and lies within the grid rows that the band intersects.
 */
internal fun ReaderOutputRequest.admits(
  coverage: ReaderImageCoverage
): Boolean {
  val grid = coverage.image.grid(maxOutputWidth)

  if (coverage.grid != grid) {
    return false
  }

  return when (this) {
    is ReaderOutputRequest.Whole -> true
    is ReaderOutputRequest.Band -> {
      val rows = geometry.gridRows(top, bottom, grid)
      val region = coverage.region

      coverage.image == geometry &&
        region.left == 0 &&
        region.right == grid.width &&
        region.top >= rows.first &&
        region.bottom <= rows.last + 1
    }
  }
}

/**
 * A half-open rectangle `[left, right) × [top, bottom)` in the coordinates of
 * an output grid, which are those of the image at a scale of 1:1.
 *
 * A region is a bounded result. It never establishes a provider unit, a reading
 * position, or a chapter boundary.
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
}

/**
 * The valid content of one published output.
 *
 * The pixels of the output are those of [region], so its first row is the row
 * `region.top` of [grid], and its first column the column `region.left`.
 *
 * Coverage and its quality are separate from the completion of the input and of
 * the request.
 */
public data class ReaderImageCoverage(
  /** The encoded image. */
  public val image: ReaderImageGeometry,
  /** The output grid: the whole image at the scale of the output. */
  public val grid: ReaderImageGeometry,
  /** The covered region, which lies inside the grid. */
  public val region: ReaderImageRegion,
  /** Whether the region has its final quality, so no refinement follows. */
  public val finalQuality: Boolean,
) {
  init {
    require(grid.width <= image.width && grid.height <= image.height) {
      "An output grid must not exceed its image."
    }

    require(region.right <= grid.width && region.bottom <= grid.height) {
      "The covered region must lie inside the grid."
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
