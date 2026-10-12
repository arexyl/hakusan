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
  fun `regions and coverage reject impossible geometry`() {
    assertThrowsExactly(IllegalArgumentException::class.java) {
      ReaderImageRegion(-1, 0, 1, 1)
    }

    assertThrowsExactly(IllegalArgumentException::class.java) {
      ReaderImageRegion(0, 0, 0, 1)
    }

    assertThrowsExactly(IllegalArgumentException::class.java) {
      ReaderImageCoverage(10, 10, ReaderImageRegion(0, 0, 11, 10), true)
    }

    val outer = ReaderImageRegion(0, 0, 10, 10)

    assertTrue(outer.contains(ReaderImageRegion(0, 5, 10, 10)))
    assertFalse(outer.contains(ReaderImageRegion(0, 5, 10, 11)))
  }

  private companion object {
    const val BOUND_SECONDS = 5L
    const val ROUNDS = 2_000
  }
}
