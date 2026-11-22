package app.hakusan.bench

import androidx.compose.ui.unit.IntSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class CellTest {
  private val viewport = IntSize(1080, 1920)
  private val long = IntSize(1440, 40000)
  private val useful = 960

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
      Cell.LONG_FIRST.steps(long, viewport, useful),
    )
  }

  @Test
  fun `the scroll moves forward, revisits two bands, and seeks twice`() {
    val tops =
      Cell.LONG_SCROLL.steps(long, viewport, useful).map { (it as Band).top }

    assertEquals(listOf(0, 2560, 5120, 2560, 0, 18720, 37440, 34880), tops)
  }

  @Test
  fun `the useful output is the image rows of the top of the viewport`() {
    // 960 viewport rows are 1422.2 rows of an image 1600 columns wide.
    assertEquals(
      listOf(Held(rows = 1423, Ending.RELEASE)),
      Cell.EARLY.steps(IntSize(1600, 2400), viewport, useful),
    )

    assertEquals(
      listOf(Held(rows = 960, Ending.RELEASE)),
      Cell.EARLY.steps(IntSize(1080, 1620), viewport, useful),
    )
  }

  @Test
  fun `the cells of an ended early output differ only in the ending`() {
    val ordinary = IntSize(1600, 2400)

    assertEquals(
      listOf(Held(rows = 1423, Ending.TRUNCATE)),
      Cell.EARLY_TRUNCATED.steps(ordinary, viewport, useful),
    )

    assertEquals(
      listOf(Held(rows = 1423, Ending.FAIL)),
      Cell.EARLY_FAILED.steps(ordinary, viewport, useful),
    )
  }

  @Test
  fun `every band lies in the image and fits one pixel backing`() {
    val backing = 16L * 1024 * 1024

    for (step in Cell.LONG_SCROLL.steps(long, viewport, useful)) {
      val band = step as Band

      assertTrue(band.bottom <= long.height)
      assertTrue(4L * long.width * (band.bottom - band.top) <= backing)
    }
  }
}
