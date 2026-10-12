package app.hakusan.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class ReaderContentTerminalTest {
  @Test
  fun `a completion owns a copy of its warnings`() {
    val warnings = mutableSetOf(ReaderContentWarning.SRGB_FALLBACK)

    val warned = ReaderContentTerminal.Completed.of(warnings)

    warnings.clear()
    assertEquals(setOf(ReaderContentWarning.SRGB_FALLBACK), warned.warnings)
    assertNotEquals(completed(), warned)

    val plain = ReaderContentTerminal.Completed.of()

    assertEquals(emptySet<ReaderContentWarning>(), plain.warnings)

    val repeated =
      ReaderContentTerminal.Completed.of(
        List(2) { ReaderContentWarning.SRGB_FALLBACK }
      )

    assertEquals(warned, repeated)
  }

  @Test
  fun `every acquisition category maps onto its own reader failure`() {
    val mapped = ReaderAcquisitionFailure.entries.map { it.toContentFailure() }

    assertEquals(
      listOf(
        ReaderContentFailure.INPUT_UNAVAILABLE,
        ReaderContentFailure.INPUT_FAILED,
        ReaderContentFailure.REPRESENTATION_CHANGED,
      ),
      mapped,
    )
  }
}
