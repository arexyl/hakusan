package app.hakusan.reader.san

import app.hakusan.reader.ReaderContentFailure
import app.hakusan.reader.ReaderContentRequest
import app.hakusan.reader.ReaderContentRequests
import app.hakusan.reader.ReaderContentTerminal
import app.hakusan.reader.ReaderEngineSettings
import app.hakusan.reader.ReaderImageCoverage
import app.hakusan.reader.ReaderImageGeometry
import app.hakusan.reader.ReaderImageRegion
import app.hakusan.reader.ReaderOutputLease
import app.hakusan.reader.ReaderOutputRequest
import app.hakusan.reader.ReaderPublishResult
import app.hakusan.reader.ReaderRepresentation
import app.hakusan.reader.ReaderResourceOwner
import app.hakusan.reader.ReaderStripStore
import app.hakusan.reader.ReaderTargetPool
import app.hakusan.reader.section

/**
 * The output of one job: its claim on the native lease, its targets, and the
 * publications that it hands to presentation.
 *
 * **Targets.** When the report first names the lease geometry, the output
 * reserves two targets for a lease that publishes early and one otherwise. With
 * fewer than it wants, it publishes once, at the end of the job. With none, it
 * tries again while presentation holds a target of an ended output, whose
 * return frees room; otherwise the ceiling refuses the output.
 *
 * **Publications.** The output publishes when the coverage exceeds its last
 * published revision, a target is free, and no publication of the request
 * waits, so that at most one revision reaches presentation per frame. It copies
 * only the rows that the free target lacks: a later revision covers every row
 * of an earlier one. A target that presentation holds never receives a row.
 *
 * **Final copy.** When the job has ended with rows beyond a revision that still
 * waits, the output takes that revision back, and the last rows extend its
 * target. They can then reach presentation in the display frame that would have
 * taken the waiting revision, where they would otherwise wait for a target
 * until that frame had passed. A frame that comes during that copy shows no new
 * revision.
 *
 * Confined to the worker. [cancel] requests the cancellation of the job, for a
 * failure or for a stale request.
 */
internal class SanOutput(
  private val owner: ReaderResourceOwner,
  private val requests: ReaderContentRequests,
  private val request: ReaderContentRequest,
  private val representation: ReaderRepresentation,
  private val san: SanCalls,
  private val report: LongArray,
  private val cancel: (cause: ReaderContentFailure?) -> Unit,
) {
  private val pool = owner.targets
  private val targets = mutableListOf<ReaderTargetPool.Target>()
  private var lease = SanAbi.NO_HANDLE
  private var wanted = 0
  private var publishedRows = 0
  private var publishedRevision = 0
  private var busy = false
  private var ended = false
  private var stale = false

  private var described = false

  /** Whether the ceiling of the targets refused this output. */
  var refused: Boolean = false
    private set

  /** Whether the report described another image or grid than a band names. */
  var changed: Boolean = false
    private set

  /** Whether the request was canceled before the report showed [changed]. */
  var canceledBefore: Boolean = false
    private set

  /**
   * Acts on the report of the last step and on what presentation returned or
   * took since: adopts the lease, reserves the targets, and publishes.
   */
  fun update() {
    // The claim starts with the first report that names the lease. A status
    // that writes no report leaves the last one in place.
    if (lease == SanAbi.NO_HANDLE) {
      lease = report[SanAbi.JOB_REPORT_LEASE]
    }

    if (!described && report[SanAbi.JOB_REPORT_IMAGE_WIDTH] != 0L) {
      described = true
      check()
    }

    // The bytes of another image than the band names give no output.
    if (changed) {
      return
    }

    reserve()
    publish()
  }

  /**
   * Compares the image and the grid of the first report that describes them
   * with what a band names, before any target or copy. A difference means that
   * the bytes are another image than the caller learned.
   */
  private fun check() {
    val band = request.output as? ReaderOutputRequest.Band ?: return
    val grid = band.geometry.grid(band.maxOutputWidth)

    if (image() != band.geometry || grid() != grid) {
      changed = true

      // A request that was canceled first stays canceled.
      canceledBefore = request.cancellationRequested

      cancel(
        ReaderContentFailure.REPRESENTATION_CHANGED.takeUnless {
          canceledBefore
        }
      )
    }
  }

  /**
   * The image whose requested output the engine refused because its pixels
   * exceed the ceiling of one lease less the band margin of [settings], or null
   * when the last report shows no such refusal: no image, an admitted lease, or
   * a lease that fits, which another limit refused.
   */
  fun oversized(settings: ReaderEngineSettings): ReaderImageGeometry? {
    val described = report[SanAbi.JOB_REPORT_IMAGE_WIDTH] != 0L
    val admitted = report[SanAbi.JOB_REPORT_LEASE_WIDTH] != 0L

    if (!described || admitted || changed) {
      return null
    }

    val image = image()
    val grid = grid()

    val rows =
      when (val wanted = request.output) {
        is ReaderOutputRequest.Whole -> grid.height
        is ReaderOutputRequest.Band ->
          image.gridRows(wanted.top, wanted.bottom, grid).let {
            it.last - it.first + 1
          }
      }

    val pixels = rows.toLong() * grid.width * PIXEL_BYTES
    val room = settings.outputLeaseBytes - settings.bandMarginBytes

    return image.takeIf { pixels > room }
  }

  /**
   * Makes the final copy after the job ended, and returns whether it still
   * waits: for a target that presentation will return, or for a held output to
   * settle after a busy publication. A revision that waits for presentation
   * holds nothing back, because the output takes it back first. A cancellation
   * of the request ends the wait, and so does a state in which nothing would
   * end it.
   */
  fun finish(): Boolean {
    ended = true
    update()

    if (withdraw()) {
      // The release has posted the return of its target, and the last copy
      // extends that target now.
      pool.applyReturns()
      publish()
    }

    val pending =
      lease != SanAbi.NO_HANDLE &&
        covered() > publishedRows &&
        !request.cancellationRequested &&
        !stale &&
        !refused &&
        !changed

    return pending &&
      (busy ||
        targets.any { it.held } ||
        (targets.isEmpty() && pool.awaitsRoom))
  }

  /**
   * Takes back the revision that still waits for presentation, once the job has
   * ended with rows beyond it, and returns whether one waited. That revision
   * would hold one target until a display frame took it, while the drawn one
   * holds the other, so the last rows would reach presentation a display frame
   * later. A request that can publish nothing more has no revision that waits:
   * what cancels it releases the queue of its owner.
   */
  private fun withdraw(): Boolean {
    val unpublished = lease != SanAbi.NO_HANDLE && covered() > publishedRows

    if (!unpublished || !requests.withdrawPublication(request)) {
      return false
    }

    owner.platform.section("$WITHDRAW $publishedRevision") {}

    return true
  }

  /** Releases the claim on the lease, after the last copy. */
  fun releaseLease() {
    val claimed = lease

    if (claimed != SanAbi.NO_HANDLE) {
      lease = SanAbi.NO_HANDLE
      checkSettled("outputRelease", san.outputRelease(claimed))
    }
  }

  /** Whether presentation received every covered row of the lease. */
  val published: Boolean
    get() = publishedRows > 0 && publishedRows == covered()

  /**
   * Gives the claim on the lease to a strip of [key] in place of its release,
   * after the last copy of a job that ended as [completed]. Returns null, with
   * the claim kept, unless the lease holds every one of its rows.
   */
  fun toStrip(
    key: ReaderStripStore.Key,
    completed: ReaderContentTerminal.Completed,
  ): ReaderStripStore.Strip? {
    val claimed = lease
    val covered = covered()

    if (
      claimed == SanAbi.NO_HANDLE ||
        covered != element(SanAbi.JOB_REPORT_LEASE_HEIGHT)
    ) {
      return null
    }

    lease = SanAbi.NO_HANDLE

    return ReaderStripStore.Strip(
      key = key,
      san = san,
      lease = claimed,
      representation = representation,
      coverage = coverage(covered),
      opaque = element(SanAbi.JOB_REPORT_ALPHA) == SanAbi.ALPHA_OPAQUE,
      completed = completed,
    )
  }

  /** Ends the claim on a lease that a strip owns, without a release. */
  fun surrender() {
    lease = SanAbi.NO_HANDLE
  }

  /** Ends the use of the targets, which presentation may still draw. */
  fun releaseTargets() {
    targets.forEach(pool::release)
    targets.clear()
  }

  private fun reserve() {
    if (report[SanAbi.JOB_REPORT_LEASE_WIDTH] == 0L) {
      return
    }

    if (wanted == 0) {
      val early = report[SanAbi.JOB_REPORT_EARLY_PUBLICATION] != 0L

      wanted = if (early) 2 else 1
    }

    // A canceled request publishes nothing, so it needs no target, and a
    // ceiling must not turn its cancellation into a budget failure.
    if (targets.isNotEmpty() || refused || request.cancellationRequested) {
      return
    }

    val width = element(SanAbi.JOB_REPORT_LEASE_WIDTH)
    val height = element(SanAbi.JOB_REPORT_LEASE_HEIGHT)
    val opaque = element(SanAbi.JOB_REPORT_ALPHA) == SanAbi.ALPHA_OPAQUE

    // The creation runs on the worker between two steps, and a trace would
    // otherwise show its time as time of no section.
    owner.platform.section(TARGETS) {
      repeat(wanted) { pool.create(width, height, opaque)?.let(targets::add) }
    }

    if (targets.isEmpty() && !pool.awaitsRoom) {
      refused = true

      if (!ended) {
        cancel(ReaderContentFailure.BUDGET_EXCEEDED)
      }
    }
  }

  private fun publish() {
    val covered = covered()

    val blocked =
      lease == SanAbi.NO_HANDLE ||
        covered <= publishedRows ||
        request.cancellationRequested ||
        stale ||
        // With fewer targets than wanted, once, at the end of the job.
        (!ended && targets.size < wanted) ||
        requests.publicationWaits(request)

    if (blocked) {
      return
    }

    // The free target that lacks the fewest rows: after a withdrawal, that is
    // the target of the withdrawn revision, also when the other one is free.
    val target = targets.filter { !it.held }.maxByOrNull { it.rows } ?: return

    copy(target, covered)

    // The ordinal counts only once presentation holds the lease, so that the
    // return of a lease that was never handed over changes nothing.
    val ordinal = target.issue()

    // A lease that the worker discards itself returns nothing: its return
    // would wake the worker, which would publish and discard again.
    var discarded = false

    val output =
      ReaderOutputLease(
        representation,
        coverage(covered),
        element(SanAbi.JOB_REPORT_REVISION),
        target.backing,
      ) {
        if (!discarded) {
          owner.returnTarget(target, ordinal)
        }
      }

    when (requests.publish(request, output)) {
      ReaderPublishResult.PUBLISHED -> {
        target.hand(ordinal)
        publishedRows = covered
        publishedRevision = output.revision
        busy = false
      }

      ReaderPublishResult.BUSY -> {
        busy = true
        discarded = true
        requests.discard(request, output)
      }

      ReaderPublishResult.STALE -> {
        stale = true
        cancel(null)
      }
    }
  }

  /** Copies the covered rows that [target] lacks into it. */
  private fun copy(target: ReaderTargetPool.Target, covered: Int) {
    val first = target.rows
    val count = covered - first

    if (count <= 0) {
      return
    }

    val revision = report[SanAbi.JOB_REPORT_REVISION]

    // A copy that fails leaves the target in no known state, and a later
    // output would fail on it again.
    target.failed = true

    owner.platform.section("reader.copy $revision $first $count") {
      checkSettled(
        "outputCopy",
        san.outputCopy(lease, first, count, target.backing, first),
      )
    }

    target.failed = false
    target.rows = covered
  }

  /**
   * The coverage of the lease rows `[0, covered)`, which lie in the output grid
   * from the origin of the lease on.
   */
  private fun coverage(covered: Int): ReaderImageCoverage {
    val left = element(SanAbi.JOB_REPORT_LEASE_LEFT)
    val top = element(SanAbi.JOB_REPORT_LEASE_TOP)
    val right = left + element(SanAbi.JOB_REPORT_LEASE_WIDTH)

    return ReaderImageCoverage(
      image = image(),
      grid = grid(),
      region = ReaderImageRegion(left, top, right, top + covered),
      finalQuality = true,
    )
  }

  /** The image of a report that describes one. */
  private fun image(): ReaderImageGeometry =
    ReaderImageGeometry(
      element(SanAbi.JOB_REPORT_IMAGE_WIDTH),
      element(SanAbi.JOB_REPORT_IMAGE_HEIGHT),
    )

  /** The output grid of a report that describes an image. */
  private fun grid(): ReaderImageGeometry =
    ReaderImageGeometry(
      element(SanAbi.JOB_REPORT_OUTPUT_WIDTH),
      element(SanAbi.JOB_REPORT_OUTPUT_HEIGHT),
    )

  private fun covered(): Int = element(SanAbi.JOB_REPORT_COVERED_ROWS)

  private fun element(index: Int): Int = Math.toIntExact(report[index])

  companion object {
    /** The bytes of one pixel of a lease. */
    private const val PIXEL_BYTES = 4

    /** The trace section of the creation of the targets of an output. */
    private const val TARGETS = "reader.targets"

    /**
     * The trace section of a withdrawal, without duration, whose name ends with
     * the revision that was taken back.
     */
    private const val WITHDRAW = "reader.withdraw"

    /**
     * The report of a job that would have just completed the band of [strip]:
     * its lease with every row covered, as one revision that publishes only at
     * its end. With it, an output serves a strip as it serves a final copy.
     */
    fun reportOf(strip: ReaderStripStore.Strip): LongArray {
      val coverage = strip.coverage
      val region = coverage.region
      val rows = region.bottom - region.top
      val alpha =
        if (strip.opaque) SanAbi.ALPHA_OPAQUE else SanAbi.ALPHA_PREMULTIPLIED
      val report = LongArray(SanAbi.JOB_REPORT_LENGTH)

      report[SanAbi.JOB_REPORT_LEASE] = strip.lease
      report[SanAbi.JOB_REPORT_REVISION] = 1L
      report[SanAbi.JOB_REPORT_COVERED_ROWS] = rows.toLong()
      report[SanAbi.JOB_REPORT_LEASE_WIDTH] =
        (region.right - region.left).toLong()
      report[SanAbi.JOB_REPORT_LEASE_HEIGHT] = rows.toLong()
      report[SanAbi.JOB_REPORT_LEASE_LEFT] = region.left.toLong()
      report[SanAbi.JOB_REPORT_LEASE_TOP] = region.top.toLong()
      report[SanAbi.JOB_REPORT_ALPHA] = alpha.toLong()
      report[SanAbi.JOB_REPORT_IMAGE_WIDTH] = coverage.image.width.toLong()
      report[SanAbi.JOB_REPORT_IMAGE_HEIGHT] = coverage.image.height.toLong()
      report[SanAbi.JOB_REPORT_OUTPUT_WIDTH] = coverage.grid.width.toLong()
      report[SanAbi.JOB_REPORT_OUTPUT_HEIGHT] = coverage.grid.height.toLong()

      return report
    }
  }
}
