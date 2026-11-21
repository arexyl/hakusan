package app.hakusan.reader.san

import app.hakusan.reader.ReaderContentFailure
import app.hakusan.reader.ReaderContentTerminal
import app.hakusan.reader.ReaderContentWarning

// A native status becomes a reader outcome by the call that returned it: the
// same status is an outcome from one call and a broken contract from another.
// A status that no rule of its call admits is a defect of this adapter or of
// the library, and it throws instead of ending a request as if San had decided.

/**
 * The failure that `contentOpen` reported with [status], or null after success.
 *
 * @throws IllegalStateException for any other status.
 */
internal fun contentOpenFailure(status: Int): ReaderContentFailure? =
  admissionFailure("contentOpen", status, unsupportedOperation = false)

/**
 * The failure that `jobRequest` reported with [status], or null after success.
 *
 * @throws IllegalStateException for any other status.
 */
internal fun jobRequestFailure(status: Int): ReaderContentFailure? =
  admissionFailure("jobRequest", status, unsupportedOperation = true)

/**
 * The failure that `contentFeed` reported with [status], or null after success.
 * A feed fails only when the platform provides no array elements, which changes
 * nothing, so a later request may succeed.
 *
 * @throws IllegalStateException for any other status.
 */
internal fun contentFeedFailure(status: Int): ReaderContentFailure? =
  when (status) {
    SanAbi.STATUS_OK -> null
    SanAbi.STATUS_ALLOCATION_FAILED -> ReaderContentFailure.ALLOCATION_FAILED
    else -> defect("contentFeed", status)
  }

/**
 * The outcome of a job whose step returned the terminal [status] with the
 * [warnings] of its report.
 *
 * San knows no reason for a cancellation, so [cancelCause] names the failure
 * for which the adapter canceled, or null when the request was canceled. Every
 * other status decides the outcome alone, whatever the adapter asked for.
 *
 * @throws IllegalStateException for a status that ends no job, and for a
 *   warning bit that the declarations do not name.
 */
internal fun jobTerminal(
  status: Int,
  warnings: Long,
  cancelCause: ReaderContentFailure?,
): ReaderContentTerminal =
  when (status) {
    SanAbi.STATUS_OK -> ReaderContentTerminal.Completed.of(warningsOf(warnings))

    SanAbi.STATUS_CANCELED ->
      cancelCause?.let(ReaderContentTerminal::Failed)
        ?: ReaderContentTerminal.Canceled

    else ->
      ReaderContentTerminal.Failed(
        failureCategory(status) ?: defect("jobStep", status)
      )
  }

/**
 * Checks a call that only succeeds under the contract: a close, a release, a
 * cancellation, a copy, or a counters read.
 *
 * @throws IllegalStateException unless [status] is `OK`.
 */
internal fun checkSettled(call: String, status: Int) {
  if (status != SanAbi.STATUS_OK) {
    defect(call, status)
  }
}

private fun admissionFailure(
  call: String,
  status: Int,
  unsupportedOperation: Boolean,
): ReaderContentFailure? =
  when (status) {
    SanAbi.STATUS_OK -> null

    SanAbi.STATUS_BUDGET_EXCEEDED,
    SanAbi.STATUS_ALLOCATION_FAILED,
    SanAbi.STATUS_ENGINE_FAILURE -> failureCategory(status)

    SanAbi.STATUS_UNSUPPORTED_OPERATION if unsupportedOperation ->
      failureCategory(status)

    else -> defect(call, status)
  }

/** The reader-level failure of a native failure category, or null for none. */
private fun failureCategory(status: Int): ReaderContentFailure? =
  when (status) {
    SanAbi.STATUS_UNSUPPORTED_FORMAT -> ReaderContentFailure.UNSUPPORTED_FORMAT

    SanAbi.STATUS_UNSUPPORTED_OPERATION ->
      ReaderContentFailure.UNSUPPORTED_OPERATION

    SanAbi.STATUS_INVALID_CONTENT -> ReaderContentFailure.INVALID_CONTENT
    SanAbi.STATUS_TRUNCATED_CONTENT -> ReaderContentFailure.TRUNCATED_CONTENT
    SanAbi.STATUS_BUDGET_EXCEEDED -> ReaderContentFailure.BUDGET_EXCEEDED
    SanAbi.STATUS_ALLOCATION_FAILED -> ReaderContentFailure.ALLOCATION_FAILED
    SanAbi.STATUS_ENGINE_FAILURE -> ReaderContentFailure.ENGINE_FAILURE
    else -> null
  }

private fun warningsOf(bits: Long): Set<ReaderContentWarning> {
  val fallback = SanAbi.WARNING_SRGB_FALLBACK.toLong()

  check(bits and fallback.inv() == 0L) {
    "A job reported the unknown warning bits $bits."
  }

  return if (bits == fallback) {
    setOf(ReaderContentWarning.SRGB_FALLBACK)
  } else {
    emptySet()
  }
}

private fun defect(call: String, status: Int): Nothing =
  error("$call returned the status $status, which its contract excludes.")
