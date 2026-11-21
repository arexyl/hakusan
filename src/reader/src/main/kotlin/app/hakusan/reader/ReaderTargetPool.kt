package app.hakusan.reader

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Every target that the adapter created and has not recycled, across all
 * content owners, under the ceilings of the Android backing.
 *
 * A target belongs to one output. It is free for a copy while no display lease
 * of it is outstanding, and the pool recycles it once its output needs it no
 * more and presentation has returned it, whichever comes later. A return can
 * arrive long after its request ended, so the pool belongs to the owner.
 *
 * Confined to the worker, except [post].
 */
internal class ReaderTargetPool(
  private val platform: ReaderPlatform,
  private val maxBytes: Long,
  private val maxCount: Int,
) {
  /** One target with what the worker tracks of it. */
  class Target(val backing: ReaderTarget, val bytes: Long) {
    /** The lease rows `[0, rows)` that the target holds. */
    var rows: Int = 0

    /** The ordinal of the outstanding or of the last display lease. */
    var ordinal: Int = 0

    /** Whether a display lease of the target is outstanding. */
    var held: Boolean = false

    /** Whether the output of the target still copies into it. */
    var needed: Boolean = true
  }

  private val targets = mutableListOf<Target>()
  private val posted = ConcurrentLinkedQueue<Return>()
  private var bytes = 0L

  /** Runs when the pool recycled its last target. */
  var onEmpty: (() -> Unit)? = null

  val isEmpty: Boolean
    get() = targets.isEmpty()

  /**
   * Whether presentation holds a target of an ended output, whose return frees
   * its room.
   */
  val awaitsRoom: Boolean
    get() = targets.any { it.held && !it.needed }

  /** Creates a target, or returns null when a ceiling admits none. */
  fun create(width: Int, height: Int, opaque: Boolean): Target? {
    val size = width.toLong() * height * BYTES_PER_PIXEL

    if (targets.size >= maxCount || bytes + size > maxBytes) {
      return null
    }

    val target = Target(platform.createTarget(width, height, opaque), size)

    targets += target
    bytes += size

    return target
  }

  /**
   * Records the return of the display lease [ordinal] of [target], from any
   * thread. The worker applies it with [applyReturns].
   */
  fun post(target: Target, ordinal: Int) {
    posted += Return(target, ordinal)
  }

  /**
   * Applies every posted return. Only the return of a target's outstanding
   * display lease frees it. Any other one changes nothing: it belongs to a
   * lease that was never handed over.
   */
  fun applyReturns() {
    while (true) {
      val returned = posted.poll() ?: break
      val target = returned.target

      if (target.held && target.ordinal == returned.ordinal) {
        target.held = false

        if (!target.needed) {
          recycle(target)
        }
      }
    }
  }

  /** Ends the use of [target] by its output, after the last copy into it. */
  fun release(target: Target) {
    target.needed = false

    if (!target.held) {
      recycle(target)
    }
  }

  private fun recycle(target: Target) {
    targets -= target
    bytes -= target.bytes
    platform.recycle(target.backing)

    if (targets.isEmpty()) {
      onEmpty?.invoke()
    }
  }

  private class Return(val target: Target, val ordinal: Int)

  private companion object {
    const val BYTES_PER_PIXEL = 4L
  }
}
