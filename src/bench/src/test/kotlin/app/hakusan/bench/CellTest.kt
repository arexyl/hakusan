package app.hakusan.bench

import androidx.compose.ui.unit.IntSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class CellTest {
  private val viewport = IntSize(1080, 1920)
  private val long = IntSize(1440, 40000)

  @Test
  fun `the grid of a wider image keeps the aspect with a rounded height`() {
    assertEquals(
      IntSize(1080, 1620),
      Whole(width = 1080).grid(IntSize(4096, 6144)),
    )

    // 3 rows at a third of the width are 1 row, and 5 rows round 1.67 up.
    assertEquals(IntSize(1, 1), Whole(width = 1).grid(IntSize(3, 3)))
    assertEquals(IntSize(1, 2), Whole(width = 1).grid(IntSize(3, 5)))
  }

  @Test
  fun `the grid never scales up and never loses its last row`() {
    assertEquals(
      IntSize(1600, 2400),
      Whole(width = null).grid(IntSize(1600, 2400)),
    )

    assertEquals(IntSize(600, 900), Whole(width = 1080).grid(IntSize(600, 900)))
    assertEquals(IntSize(1, 1), Whole(width = 1).grid(IntSize(4000, 1)))
  }

  @Test
  fun `a band is one viewport of the long segment`() {
    assertEquals(
      listOf(Band(top = 0, bottom = 2560)),
      Cell.LONG_FIRST.steps(long, viewport),
    )
  }

  @Test
  fun `the scroll moves forward, seeks, moves backward, and returns`() {
    val tops = Cell.LONG_SCROLL.steps(long, viewport).map { (it as Band).top }

    assertEquals(listOf(0, 2560, 5120, 18720, 37440, 34880, 32320, 0), tops)
  }

  @Test
  fun `every band lies in the image and fits one pixel backing`() {
    val backing = 16L * 1024 * 1024

    for (step in Cell.LONG_SCROLL.steps(long, viewport)) {
      val band = step as Band

      assertTrue(band.bottom <= long.height)
      assertTrue(4L * long.width * (band.bottom - band.top) <= backing)
    }
  }
}
