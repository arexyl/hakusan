package app.hakusan.reader.san

import app.hakusan.reader.ReaderContentFailure
import app.hakusan.reader.ReaderContentRequest
import app.hakusan.reader.ReaderContentRequests
import app.hakusan.reader.ReaderImageCoverage
import app.hakusan.reader.ReaderImageGeometry
import app.hakusan.reader.ReaderImageRegion
import app.hakusan.reader.ReaderOutputLease
import app.hakusan.reader.ReaderPublishResult
import app.hakusan.reader.ReaderRepresentation
import app.hakusan.reader.ReaderResourceOwner
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
  private var busy = false
  private var ended = false
  private var stale = false

  /** Whether the ceiling of the targets refused this output. */
  var refused: Boolean = false
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

    reserve()
    publish()
  }

  /**
   * Makes the final copy after the job ended, and returns whether it still
   * waits: for a target that presentation will return, or for presentation to
   * take or settle a publication. A cancellation of the request ends the wait,
   * and so does a state in which nothing would end it.
   */
  fun finish(): Boolean {
    ended = true
    update()

    val pending =
      lease != SanAbi.NO_HANDLE &&
        covered() > publishedRows &&
        !request.cancellationRequested &&
        !stale &&
        !refused

    return pending &&
      (busy ||
        targets.any { it.held } ||
        requests.publicationWaits(request) ||
        (targets.isEmpty() && pool.awaitsRoom))
  }

  /** Releases the claim on the lease, after the last copy. */
  fun releaseLease() {
    val claimed = lease

    if (claimed != SanAbi.NO_HANDLE) {
      lease = SanAbi.NO_HANDLE
      checkSettled("outputRelease", san.outputRelease(claimed))
    }
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

    repeat(wanted) {
      pool.create(width, height, opaque)?.let(targets::add)
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

    val target = targets.firstOrNull { !it.held } ?: return

    copy(target, covered)

    // The ordinal counts only once presentation holds the lease, so that the
    // return of a lease that was never handed over changes nothing.
    val ordinal = target.ordinal + 1

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
        target.ordinal = ordinal
        target.held = true
        publishedRows = covered
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

    owner.platform.section("reader.copy $revision $first $count") {
      checkSettled(
        "outputCopy",
        san.outputCopy(lease, first, count, target.backing, first),
      )
    }

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
      image =
        ReaderImageGeometry(
          element(SanAbi.JOB_REPORT_IMAGE_WIDTH),
          element(SanAbi.JOB_REPORT_IMAGE_HEIGHT),
        ),
      grid =
        ReaderImageGeometry(
          element(SanAbi.JOB_REPORT_OUTPUT_WIDTH),
          element(SanAbi.JOB_REPORT_OUTPUT_HEIGHT),
        ),
      region = ReaderImageRegion(left, top, right, top + covered),
      finalQuality = true,
    )
  }

  private fun covered(): Int = element(SanAbi.JOB_REPORT_COVERED_ROWS)

  private fun element(index: Int): Int = Math.toIntExact(report[index])
}
