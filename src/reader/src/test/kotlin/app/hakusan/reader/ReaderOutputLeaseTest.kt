package app.hakusan.reader

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrowsExactly
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderOutputLeaseTest {
  @Test
  fun `a lease releases exactly once`() {
    var releases = 0

    val lease = testLease { releases++ }

    lease.close()
    lease.close()
    assertEquals(1, releases)
  }

  @Test
  fun `a lease has one claimant and a closed lease has none`() {
    val outcomes = ArrayList<Throwable?>()

    val lease = testLease {}

    assertTrue(lease.claim { outcomes += it })
    assertFalse(lease.claim { error("A second claimant must not run.") })
    lease.close()
    assertEquals(listOf<Throwable?>(null), outcomes)

    val closed = testLease {}

    closed.close()
    assertFalse(closed.claimable())
    assertFalse(closed.claim { error("A released lease has no claimant.") })
  }

  @Test
  fun `an unclaimed lease rethrows its release failure and never retries`() {
    val failure = IllegalStateException("Injected release failure.")

    var releases = 0

    val lease = testLease {
      releases++

      throw failure
    }

    val thrown =
      assertThrowsExactly(IllegalStateException::class.java) {
        lease.close()
      }

    assertSame(failure, thrown)
    lease.close()
    assertEquals(1, releases)
    assertFalse(lease.claimable())
  }

  @Test
  fun `a claimed lease sends its release failure to the claimant`() {
    val failure = IllegalStateException("Injected release failure.")

    val received = AtomicReference<Throwable?>()

    val lease = testLease { throw failure }

    assertTrue(lease.claim { received.set(it) })
    lease.close()
    assertSame(failure, received.get())
  }

  @Test
  fun `a fatal release error reaches the claimant and propagates`() {
    val fatal = OutOfMemoryError("Injected fatal release error.")

    val received = AtomicReference<Throwable?>()

    var releases = 0

    val lease = testLease {
      releases++

      throw fatal
    }

    assertTrue(lease.claim { received.set(it) })

    val thrown =
      assertThrowsExactly(OutOfMemoryError::class.java) { lease.close() }

    assertSame(fatal, thrown)
    assertSame(fatal, received.get())
    lease.close()
    assertEquals(1, releases)
  }

  @Test
  fun `a concurrent close returns before the release completes`() {
    val releasing = CountDownLatch(1)

    val proceed = CountDownLatch(1)

    val settled = CountDownLatch(1)

    val lease = testLease {
      releasing.countDown()
      check(proceed.await(BOUND_SECONDS, TimeUnit.SECONDS))
    }

    assertTrue(lease.claim { settled.countDown() })

    val first = thread { lease.close() }

    assertTrue(releasing.await(BOUND_SECONDS, TimeUnit.SECONDS))
    lease.close()
    assertEquals(1L, settled.count)
    proceed.countDown()
    assertTrue(settled.await(BOUND_SECONDS, TimeUnit.SECONDS))
    first.join(TimeUnit.SECONDS.toMillis(BOUND_SECONDS))
    assertFalse(first.isAlive)
  }

  @Test
  fun `claims racing closes release once and notify only a claimant`() =
    readerStressTest {
      repeat(ROUNDS) {
        val releases = AtomicInteger()

        val notices = AtomicInteger()

        val lease = testLease { releases.incrementAndGet() }

        val claimed =
          listOf(
              async { lease.claim { notices.incrementAndGet() } },
              async { lease.claim { notices.incrementAndGet() } },
              async {
                lease.close()

                false
              },
            )
            .awaitAll()
            .count { it }

        assertTrue(claimed <= 1)
        assertEquals(1, releases.get())
        assertEquals(claimed, notices.get())
      }
    }

  @Test
  fun `a lease has a revision from one and pixels only with a Bitmap`() {
    val representation = ReaderRepresentation()
    val square = ReaderImageGeometry(10, 10)
    val region = ReaderImageRegion(0, 0, 10, 5)

    val coverage =
      ReaderImageCoverage(square, square, region, finalQuality = false)

    assertThrowsExactly(IllegalArgumentException::class.java) {
      ReaderOutputLease(representation, coverage, revision = 0, TestTarget()) {}
    }

    val lease =
      ReaderOutputLease(representation, coverage, revision = 3, TestTarget()) {}

    assertEquals(3, lease.revision)
    assertThrowsExactly(IllegalStateException::class.java) { lease.bitmap }
  }

  @Test
  fun `regions and coverage reject impossible geometry`() {
    assertThrowsExactly(IllegalArgumentException::class.java) {
      ReaderImageRegion(-1, 0, 1, 1)
    }

    assertThrowsExactly(IllegalArgumentException::class.java) {
      ReaderImageRegion(0, 0, 0, 1)
    }

    assertThrowsExactly(IllegalArgumentException::class.java) {
      ReaderImageGeometry(0, 1)
    }

    val square = ReaderImageGeometry(10, 10)
    val half = ReaderImageGeometry(5, 5)

    // A region outside its grid, and a grid above its image.
    assertThrowsExactly(IllegalArgumentException::class.java) {
      ReaderImageCoverage(square, half, ReaderImageRegion(0, 0, 6, 5), true)
    }

    assertThrowsExactly(IllegalArgumentException::class.java) {
      ReaderImageCoverage(half, square, ReaderImageRegion(0, 0, 5, 5), true)
    }
  }

  @Test
  fun `the grid of an image never scales up and rounds its height`() {
    val page = ReaderImageGeometry(4320, 6480)
    val segment = ReaderImageGeometry(1440, 40_000)

    assertEquals(page, page.grid(null))
    assertEquals(page, page.grid(4320))
    assertEquals(page, page.grid(10_000))
    assertEquals(ReaderImageGeometry(1080, 1620), page.grid(1080))

    // A height of one and a half rows rounds up, and no grid has less than
    // one row.
    assertEquals(ReaderImageGeometry(1080, 30_000), segment.grid(1080))
    assertEquals(ReaderImageGeometry(3, 2), ReaderImageGeometry(4, 2).grid(3))
    assertEquals(
      ReaderImageGeometry(10, 1),
      ReaderImageGeometry(1000, 1).grid(10),
    )
  }

  @Test
  fun `a band takes the grid rows that its image rows intersect`() {
    val segment = ReaderImageGeometry(1440, 40_000)
    val scaled = segment.grid(1080)

    assertEquals(2560..<5120, segment.gridRows(2560, 5120, segment))
    assertEquals(14_040..<15_960, segment.gridRows(18_720, 21_280, scaled))

    // An image row inside a grid row takes that grid row on both sides.
    assertEquals(0..<2, segment.gridRows(1, 2, scaled))
    assertEquals(29_999..<30_000, segment.gridRows(39_999, 40_000, scaled))
  }

  @Test
  fun `a request admits only the coverage of its grid and of its rows`() {
    val page = ReaderImageGeometry(4320, 6480)
    val grid = ReaderImageGeometry(1080, 1620)

    fun covered(top: Int, bottom: Int, right: Int = grid.width) =
      ReaderImageCoverage(
        page,
        grid,
        ReaderImageRegion(0, top, right, bottom),
        true,
      )

    val whole = ReaderOutputRequest.Whole(1080)

    assertTrue(whole.admits(covered(0, 1620)))
    assertFalse(ReaderOutputRequest.Whole().admits(covered(0, 1620)))
    assertFalse(ReaderOutputRequest.Whole(540).admits(covered(0, 1620)))

    // The image rows 400 to 800 are the grid rows 100 to 200.
    val band = ReaderOutputRequest.Band(page, 400, 800, 1080)

    assertTrue(band.admits(covered(100, 200)))
    assertTrue(band.admits(covered(100, 101)))
    assertFalse(band.admits(covered(99, 200)))
    assertFalse(band.admits(covered(100, 201)))
    assertFalse(band.admits(covered(100, 200, right = 1079)))

    val other =
      ReaderOutputRequest.Band(ReaderImageGeometry(4320, 6481), 400, 800, 1080)

    assertFalse(other.admits(covered(100, 200)))
  }

  @Test
  fun `a request rejects rows outside its image and an empty width`() {
    val page = ReaderImageGeometry(4, 4)

    assertThrowsExactly(IllegalArgumentException::class.java) {
      ReaderOutputRequest.Whole(0)
    }

    assertThrowsExactly(IllegalArgumentException::class.java) {
      ReaderOutputRequest.Band(page, 2, 2)
    }

    assertThrowsExactly(IllegalArgumentException::class.java) {
      ReaderOutputRequest.Band(page, 0, 5)
    }

    assertThrowsExactly(IllegalArgumentException::class.java) {
      ReaderOutputRequest.Band(page, 0, 4, maxOutputWidth = 0)
    }
  }

  private companion object {
    const val BOUND_SECONDS = 5L
    const val ROUNDS = 2_000
  }
}
