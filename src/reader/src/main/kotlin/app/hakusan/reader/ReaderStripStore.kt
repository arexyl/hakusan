package app.hakusan.reader

import app.hakusan.reader.san.SanCalls
import app.hakusan.reader.san.checkSettled

/**
 * The decoded strips of the reader resource owner: leases of completed bands
 * that outlive their jobs, so that a later request of the same band is served
 * by a copy and decodes nothing.
 *
 * A strip holds a lease slot and output bytes of the engine until it is
 * released, so the store gives both back before a job needs them, the least
 * recently used strip first. A strip belongs to the content requests of one
 * session and leaves when they close.
 *
 * Only the worker calls the store.
 */
internal class ReaderStripStore(private val settings: ReaderEngineSettings) {
  /** What a strip answers: one band of one unit, in one session. */
  data class Key(
    val scope: ReaderContentRequests,
    val position: ReaderPosition,
    val reference: String,
    val band: ReaderOutputRequest.Band,
  )

  /** A lease that holds every row of a completed band. */
  class Strip(
    val key: Key,
    val san: SanCalls,
    val lease: Long,
    val representation: ReaderRepresentation,
    val coverage: ReaderImageCoverage,
    val opaque: Boolean,
    /** The outcome of the job that decoded the band. */
    val completed: ReaderContentTerminal.Completed,
  ) {
    /** The bytes of the pixels, without what the engine counts beside them. */
    val pixelBytes: Long
      get() {
        val region = coverage.region
        val rows = region.bottom - region.top

        return rows.toLong() * (region.right - region.left) * PIXEL_BYTES
      }
  }

  // The least recently used strip first.
  private val strips = ArrayDeque<Strip>()

  // The sessions whose close drops their strips.
  private val scopes = HashSet<ReaderContentRequests>()

  val count: Int
    get() = strips.size

  /**
   * Takes the strip of [key] out of the store for one request, or returns null
   * when the store holds none. The request gives it back with [adopt].
   */
  fun take(key: Key): Strip? {
    val strip = strips.firstOrNull { it.key == key } ?: return null

    strips.remove(strip)

    return strip
  }

  /**
   * Keeps [strip] as the most recently used one, or releases it when its
   * session is closing. When the session closes later, [closed] runs with it on
   * the thread that closes it, and brings [drop] to the worker.
   */
  fun adopt(strip: Strip, closed: (ReaderContentRequests) -> Unit) {
    val scope = strip.key.scope

    // A close before the registration never reaches the store.
    if (scope !in scopes && !scope.invokeOnClose { closed(scope) }) {
      release(strip)

      return
    }

    scopes += scope
    strips.addLast(strip)
  }

  /**
   * Releases strips until a job finds a lease slot and the bytes of one lease
   * free, so that the engine never refuses a request for a strip.
   */
  fun makeRoom() {
    val slots = settings.outputLeaseCount - settings.jobCount
    val bytes = settings.outputBytes - settings.outputLeaseBytes

    // The band margin stands for what the engine counts beside the pixels.
    fun held(): Long = strips.sumOf { it.pixelBytes + settings.bandMarginBytes }

    while (strips.size > slots || held() > bytes) {
      release(strips.removeFirst())
    }
  }

  /**
   * Releases the strips of the unit of [key] that hold the bytes of another
   * representation than [representation], which an acquisition of that unit
   * just bound: they are pixels of bytes that the source no longer delivers.
   */
  fun dropOthers(
    scope: ReaderContentRequests,
    descriptor: ReaderContentDescriptor,
    representation: ReaderRepresentation,
  ) {
    releaseIf {
      it.key.scope === scope &&
        it.key.position == descriptor.position &&
        it.key.reference == descriptor.reference &&
        it.representation !== representation
    }
  }

  /** Releases every strip of [scope], whose session closed. */
  fun drop(scope: ReaderContentRequests) {
    scopes -= scope
    releaseIf { it.key.scope === scope }
  }

  /** Releases every strip, before the engine shuts down. */
  fun clear() {
    scopes.clear()
    releaseIf { true }
  }

  /** Releases [strip], which the store does not hold. */
  fun release(strip: Strip) {
    checkSettled("outputRelease", strip.san.outputRelease(strip.lease))
  }

  /**
   * Releases every strip that [leaves] names, whatever the release of another
   * one threw, and then throws the first failure with the later ones attached.
   */
  private inline fun releaseIf(leaves: (Strip) -> Boolean) {
    val leaving = strips.filter(leaves)
    val failures = mutableListOf<Throwable>()

    strips.removeAll(leaving.toSet())

    for (strip in leaving) {
      try {
        release(strip)
      } catch (failure: Throwable) {
        failures += failure
      }
    }

    val first = failures.firstOrNull() ?: return

    failures.drop(1).forEach(first::addSuppressed)

    throw first
  }

  private companion object {
    const val PIXEL_BYTES = 4
  }
}
