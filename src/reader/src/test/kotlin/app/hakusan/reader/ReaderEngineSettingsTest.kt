package app.hakusan.reader

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrowsExactly
import org.junit.jupiter.api.Test

/**
 * Checks each consistency rule of the engine settings at its boundary: the last
 * value that the rule admits, and the first one that it rejects.
 */
class ReaderEngineSettingsTest {
  private val profile = ReaderEngineSettings.FIRST_ITERATION

  @Test
  fun `every limit is at least one`() {
    admits { copy(exifEntryCount = 1) }
    rejects { copy(exifEntryCount = 0) }
    rejects { copy(aggregateBudgetBytes = 0) }
    rejects { copy(transferStagingBytes = 0) }
    rejects { copy(inputPortionBytes = -1) }
  }

  @Test
  fun `a count stays within what a handle names`() {
    admits { copy(contentCount = 65_535, inputPortionBytes = 1) }
    rejects { copy(contentCount = 65_536, inputPortionBytes = 1) }
  }

  @Test
  fun `every job has a content and a lease slot`() {
    admits { copy(jobCount = 3) }
    rejects { copy(jobCount = 4) }
    admits { copy(jobCount = 3, outputLeaseCount = 3) }
    rejects { copy(jobCount = 3, outputLeaseCount = 2) }
  }

  @Test
  fun `the portion staging of the contents fits the transfer staging`() {
    admits { copy(contentCount = 32) }
    rejects { copy(contentCount = 33) }
  }

  @Test
  fun `the metadata ceilings contain each other`() {
    admits { copy(metadataPayloadBytes = 2L shl 20) }
    rejects { copy(metadataPayloadBytes = (2L shl 20) + 1) }
    admits { copy(metadataExpandedBytes = 4L shl 20) }
    rejects { copy(metadataExpandedBytes = (4L shl 20) + 1) }
  }

  @Test
  fun `the output ceilings contain each other and fit the budget`() {
    admits { copy(outputLeaseBytes = 64L shl 20) }
    rejects { copy(outputLeaseBytes = (64L shl 20) + 1) }
    admits { copy(aggregateBudgetBytes = 64L shl 20) }
    rejects { copy(aggregateBudgetBytes = (64L shl 20) - 1) }
  }

  private fun admits(change: ReaderEngineSettings.() -> ReaderEngineSettings) {
    assertDoesNotThrow { profile.change() }
  }

  private fun rejects(change: ReaderEngineSettings.() -> ReaderEngineSettings) {
    assertThrowsExactly(IllegalArgumentException::class.java) {
      profile.change()
    }
  }
}
