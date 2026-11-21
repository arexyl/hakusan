package app.hakusan.reader.san

import app.hakusan.reader.ReaderContentFailure
import app.hakusan.reader.ReaderContentTerminal
import app.hakusan.reader.ReaderContentWarning
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrowsExactly
import org.junit.jupiter.api.Test

/**
 * Checks the mapping of native statuses onto reader outcomes for every status
 * of the declarations and one unknown status on each side of them.
 */
class SanStatusTest {
  @Test
  fun `a creation reports two failures and no refusal of its limits`() {
    assertEquals(
      expected(
        SanAbi.STATUS_OK to null,
        SanAbi.STATUS_ALLOCATION_FAILED to
          ReaderContentFailure.ALLOCATION_FAILED,
        SanAbi.STATUS_ENGINE_FAILURE to ReaderContentFailure.ENGINE_FAILURE,
      ),
      outcomes(::engineCreateFailure),
    )
  }

  @Test
  fun `an open reports its three failures and no other status`() {
    assertEquals(
      expected(
        SanAbi.STATUS_OK to null,
        SanAbi.STATUS_BUDGET_EXCEEDED to ReaderContentFailure.BUDGET_EXCEEDED,
        SanAbi.STATUS_ALLOCATION_FAILED to
          ReaderContentFailure.ALLOCATION_FAILED,
        SanAbi.STATUS_ENGINE_FAILURE to ReaderContentFailure.ENGINE_FAILURE,
      ),
      outcomes(::contentOpenFailure),
    )
  }

  @Test
  fun `a request also reports an unsupported operation`() {
    assertEquals(
      expected(
        SanAbi.STATUS_OK to null,
        SanAbi.STATUS_UNSUPPORTED_OPERATION to
          ReaderContentFailure.UNSUPPORTED_OPERATION,
        SanAbi.STATUS_BUDGET_EXCEEDED to ReaderContentFailure.BUDGET_EXCEEDED,
        SanAbi.STATUS_ALLOCATION_FAILED to
          ReaderContentFailure.ALLOCATION_FAILED,
        SanAbi.STATUS_ENGINE_FAILURE to ReaderContentFailure.ENGINE_FAILURE,
      ),
      outcomes(::jobRequestFailure),
    )
  }

  @Test
  fun `a feed reports only a failed allocation`() {
    assertEquals(
      expected(
        SanAbi.STATUS_OK to null,
        SanAbi.STATUS_ALLOCATION_FAILED to
          ReaderContentFailure.ALLOCATION_FAILED,
      ),
      outcomes(::contentFeedFailure),
    )
  }

  @Test
  fun `a terminal step maps every outcome of a job and no other status`() {
    assertEquals(
      expected(
        SanAbi.STATUS_OK to ReaderContentTerminal.Completed.of(),
        SanAbi.STATUS_CANCELED to ReaderContentTerminal.Canceled,
        SanAbi.STATUS_UNSUPPORTED_FORMAT to
          failed(ReaderContentFailure.UNSUPPORTED_FORMAT),
        SanAbi.STATUS_UNSUPPORTED_OPERATION to
          failed(ReaderContentFailure.UNSUPPORTED_OPERATION),
        SanAbi.STATUS_INVALID_CONTENT to
          failed(ReaderContentFailure.INVALID_CONTENT),
        SanAbi.STATUS_TRUNCATED_CONTENT to
          failed(ReaderContentFailure.TRUNCATED_CONTENT),
        SanAbi.STATUS_BUDGET_EXCEEDED to
          failed(ReaderContentFailure.BUDGET_EXCEEDED),
        SanAbi.STATUS_ALLOCATION_FAILED to
          failed(ReaderContentFailure.ALLOCATION_FAILED),
        SanAbi.STATUS_ENGINE_FAILURE to
          failed(ReaderContentFailure.ENGINE_FAILURE),
      ),
      outcomes { jobTerminal(it, warnings = 0L, cancelCause = null) },
    )
  }

  @Test
  fun `a cancellation ends as the failure it was requested for`() {
    val cause = ReaderContentFailure.INPUT_FAILED

    assertEquals(
      failed(cause),
      jobTerminal(SanAbi.STATUS_CANCELED, warnings = 0L, cancelCause = cause),
    )
  }

  @Test
  fun `an outcome that San decided ignores the reason of a cancellation`() {
    val cause = ReaderContentFailure.INPUT_FAILED

    assertEquals(
      ReaderContentTerminal.Completed.of(),
      jobTerminal(SanAbi.STATUS_OK, warnings = 0L, cancelCause = cause),
    )

    assertEquals(
      failed(ReaderContentFailure.INVALID_CONTENT),
      jobTerminal(
        SanAbi.STATUS_INVALID_CONTENT,
        warnings = 0L,
        cancelCause = cause,
      ),
    )
  }

  @Test
  fun `the fallback bit becomes the warning of a completion alone`() {
    val fallback = SanAbi.WARNING_SRGB_FALLBACK.toLong()

    assertEquals(
      ReaderContentTerminal.Completed.of(
        setOf(ReaderContentWarning.SRGB_FALLBACK)
      ),
      jobTerminal(SanAbi.STATUS_OK, fallback, cancelCause = null),
    )

    assertEquals(
      failed(ReaderContentFailure.TRUNCATED_CONTENT),
      jobTerminal(
        SanAbi.STATUS_TRUNCATED_CONTENT,
        fallback,
        cancelCause = null,
      ),
    )

    assertEquals(
      ReaderContentTerminal.Canceled,
      jobTerminal(SanAbi.STATUS_CANCELED, fallback, cancelCause = null),
    )
  }

  @Test
  fun `a completion with an unknown warning bit is a defect`() {
    for (bits in listOf(2L, 3L, Long.MIN_VALUE)) {
      assertThrowsExactly(IllegalStateException::class.java) {
        jobTerminal(SanAbi.STATUS_OK, bits, cancelCause = null)
      }
    }
  }

  @Test
  fun `a settling call admits success alone`() {
    assertDoesNotThrow { checkSettled("jobRelease", SanAbi.STATUS_OK) }

    for (status in STATUSES - SanAbi.STATUS_OK) {
      assertThrowsExactly(IllegalStateException::class.java) {
        checkSettled("jobRelease", status)
      }
    }
  }

  /** The mapped value of every status, or [Defect] where the mapping throws. */
  private fun outcomes(map: (Int) -> Any?): Map<Int, Any?> =
    STATUSES.associateWith { status ->
      try {
        map(status)
      } catch (_: IllegalStateException) {
        Defect
      }
    }

  private fun expected(vararg mapped: Pair<Int, Any?>): Map<Int, Any?> =
    STATUSES.associateWith { Defect } + mapped

  private fun failed(failure: ReaderContentFailure): ReaderContentTerminal =
    ReaderContentTerminal.Failed(failure)

  private data object Defect

  private companion object {
    val STATUSES =
      (SanAbi.STATUS_ENGINE_FAILURE - 1)..(SanAbi.STATUS_PROGRESS + 1)
  }
}
