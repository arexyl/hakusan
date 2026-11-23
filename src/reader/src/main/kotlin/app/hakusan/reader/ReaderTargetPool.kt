package app.hakusan.reader

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Every target that the adapter created and has not recycled, across all
 * content owners, under the ceilings of the Android backing.
 *
 * A target serves one output at a time. It is free for a copy while no display
 * lease of it is outstanding. Once its output needs it no more and presentation
 * has returned it, whichever comes later, the pool keeps it for a later output
 * of the same geometry or recycles it. A new target writes each of its pages
 * for the first time, and a recycled one makes the platform purge the
 * allocator, so outputs of one geometry in a row reuse their targets.
 *
 * The pool keeps at most [KEPT] targets, all of the geometry that was reserved
 * last, and none after [drain]. A kept target counts under the ceilings, and
 * [create] recycles the kept ones before a ceiling refuses a target. A return
 * can arrive long after its request ended, so the pool belongs to the owner.
 *
 * Confined to the worker, except [post].
 */
internal class ReaderTargetPool(
  private val platform: ReaderPlatform,
  private val maxBytes: Long,
  private val maxCount: Int,
) {
  /** One target with what the worker tracks of it. */
  class Target(
    val backing: ReaderTarget,
    val width: Int,
    val height: Int,
    val opaque: Boolean,
  ) {
    val bytes: Long = width.toLong() * height * BYTES_PER_PIXEL

    /**
     * The lease rows `[0, rows)` of its output that the target holds. Below
     * them, a reused target still holds rows of an earlier output.
     */
    var rows: Int = 0

    /** The ordinal of the outstanding or of the last display lease. */
    var ordinal: Int = 0

    /** Whether a display lease of the target is outstanding. */
    var held: Boolean = false

    /** Whether an output copies into the target. */
    var needed: Boolean = true

    /** Whether a copy into the target failed, after which no output gets it. */
    var failed: Boolean = false

    private var issued = 0

    /** Whether the pool keeps the target for a later output. */
    val kept: Boolean
      get() = !held && !needed

    /**
     * Returns the ordinal of a new display lease. A target never issues one
     * twice, also across its outputs: the return of a lease that was never
     * handed over can arrive after a later output has handed the target over.
     */
    fun issue(): Int {
      issued += 1

      return issued
    }

    /**
     * Records that presentation holds the display lease [ordinal], which is the
     * one that the target issued last.
     */
    fun hand(ordinal: Int) {
      check(ordinal == issued) { "The lease $ordinal is not the last issued." }

      this.ordinal = ordinal
      held = true
    }

    fun has(width: Int, height: Int, opaque: Boolean): Boolean =
      this.width == width && this.height == height && this.opaque == opaque
  }

  private val targets = mutableListOf<Target>()
  private val posted = ConcurrentLinkedQueue<Return>()
  private var bytes = 0L
  private var keeping = true

  // The geometry that was reserved last, which every kept target has.
  private var width = 0
  private var height = 0
  private var opaque = false

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

  /**
   * Returns a target of this geometry for a new output, a kept one before a new
   * one, or null when a ceiling admits none.
   */
  fun create(width: Int, height: Int, opaque: Boolean): Target? {
    val kept = targets.firstOrNull { it.kept && it.has(width, height, opaque) }

    if (kept != null) {
      kept.needed = true
      kept.rows = 0

      return kept
    }

    // No kept target serves another geometry, and each holds room.
    this.width = width
    this.height = height
    this.opaque = opaque
    targets.filter { it.kept }.forEach(::recycle)

    val size = width.toLong() * height * BYTES_PER_PIXEL

    if (targets.size >= maxCount || bytes + size > maxBytes) {
      return null
    }

    val target =
      Target(
        platform.createTarget(width, height, opaque),
        width,
        height,
        opaque,
      )

    targets += target
    bytes += target.bytes

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
          settle(target)
        }
      }
    }
  }

  /** Ends the use of [target] by its output, after the last copy into it. */
  fun release(target: Target) {
    target.needed = false

    if (!target.held) {
      settle(target)
    }
  }

  /**
   * Recycles every kept target and keeps none from now on, for the close of the
   * owner.
   */
  fun drain() {
    keeping = false
    targets.filter { it.kept }.forEach(::recycle)
  }

  /** Keeps or recycles [target], which no output needs and no lease holds. */
  private fun settle(target: Target) {
    val others = targets.count { it !== target && it.kept }

    val reusable =
      keeping &&
        !target.failed &&
        others < KEPT &&
        target.has(width, height, opaque)

    if (!reusable) {
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

    /** The targets of one progressive output. */
    const val KEPT = 2
  }
}
