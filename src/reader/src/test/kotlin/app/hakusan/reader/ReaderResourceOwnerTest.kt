package app.hakusan.reader

import app.hakusan.reader.san.SanAbi
import app.hakusan.reader.san.SanBinding
import app.hakusan.reader.san.ScriptedSan
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderResourceOwnerTest {
  private val san = ScriptedSan()
  private var binding: SanBinding = SanBinding.Established
  private var binds = 0

  private val owner =
    ReaderResourceOwner(
      ReaderEngineSettings.FIRST_ITERATION,
      callsOn = { san },
      bind = {
        binds++

        binding
      },
    )

  @Test
  fun `the first use creates one engine under the profile on the worker`() =
    readerTest {
      val first = engine()

      assertInstanceOf(ReaderEngineAccess.Live::class.java, first)
      assertSame(first, engine())
      assertEquals(1, binds)

      // The first-iteration profile, in the order of the creation.
      assertEquals(
        listOf(
          134_217_728L,
          3L,
          1L,
          536_870_912L,
          65_536L,
          65_535L,
          64_000_000L,
          4_194_304L,
          2_097_152L,
          1_048_576L,
          1_049_600L,
          128L,
          1_024L,
          67_108_864L,
          16_777_216L,
          4L,
        ),
        san.limits,
      )

      val creation = san.calls.single()

      assertEquals("engineCreate", creation.name)
      assertEquals("hakusan-reader", creation.thread.name)
      assertTrue(creation.thread.isDaemon)
      owner.close()
    }

  @Test
  fun `a library that does not load or match stays unavailable`() {
    val bindings =
      listOf(
        SanBinding.LoadFailure(UnsatisfiedLinkError()),
        SanBinding.Mismatch(SanAbi.STATUS_BINDING_MISMATCH),
        SanBinding.Mismatch(status = null),
      )

    for (failed in bindings) {
      readerTest {
        val owner = ReaderResourceOwner(profile, { san }, { failed })

        val unavailable =
          ReaderEngineAccess.Failed(ReaderContentFailure.ENGINE_UNAVAILABLE)

        assertEquals(unavailable, withContext(owner.worker) { owner.engine() })
        assertNull(owner.counters())
        owner.close()
      }
    }

    assertEquals(emptyList<Any>(), san.calls)
  }

  @Test
  fun `the binding is established once for the process`() = readerTest {
    binding = SanBinding.Mismatch(status = null)

    engine()
    engine()

    assertEquals(1, binds)
    owner.close()
  }

  @Test
  fun `a creation without memory fails its use, and the next use creates`() =
    readerTest {
      san.failNext("engineCreate", SanAbi.STATUS_ALLOCATION_FAILED)
      san.failNext("engineCreate", SanAbi.STATUS_ENGINE_FAILURE)

      assertEquals(
        ReaderEngineAccess.Failed(ReaderContentFailure.ALLOCATION_FAILED),
        engine(),
      )

      assertEquals(
        ReaderEngineAccess.Failed(ReaderContentFailure.ENGINE_FAILURE),
        engine(),
      )

      assertInstanceOf(ReaderEngineAccess.Live::class.java, engine())
      owner.close()
    }

  @Test
  fun `a creation that the engine refuses is a defect`() = readerTest {
    san.failNext("engineCreate", SanAbi.STATUS_BUDGET_EXCEEDED)

    assertSuspendFails<IllegalStateException> { engine() }
    owner.close()
  }

  @Test
  fun `the counters read the ledger of the live engine`() = readerTest {
    assertNull(owner.counters())

    val live = engine() as ReaderEngineAccess.Live

    withContext(owner.worker) {
      live.san.contentOpen(live.engine, LongArray(1))
    }

    val counters = checkNotNull(owner.counters())

    assertEquals(1L, counters.contentCount)
    assertEquals(0L, counters.jobCount)
    assertEquals(san.calls.first().thread, san.calls.last().thread)
    assertFalse(san.calls.last().resetPeak)
    owner.close()
  }

  @Test
  fun `a peak reset waits for the gate, and a plain read does not`() =
    readerTest {
      engine()
      owner.gate.lock()

      val reset =
        async(start = CoroutineStart.UNDISPATCHED) {
          owner.counters(resetPeak = true)
        }

      checkNotNull(owner.counters())

      assertFalse(reset.isCompleted)
      assertFalse(san.calls.any { it.resetPeak })
      owner.gate.unlock()
      checkNotNull(reset.await())
      assertTrue(san.calls.last().resetPeak)
      owner.close()
    }

  @Test
  fun `closing shuts the engine down on the worker and stops it`() =
    readerTest {
      engine()

      val worker = san.calls.single().thread

      owner.close()
      owner.close()

      assertEquals(
        listOf("engineCreate", "engineShutdown"),
        san.calls.map { it.name },
      )

      assertSame(worker, san.calls.last().thread)
      assertFalse(san.engineLive)
      worker.join(TimeUnit.SECONDS.toMillis(BOUND_SECONDS))
      assertFalse(worker.isAlive)
      assertSuspendFails<IllegalStateException> { owner.counters() }
    }

  @Test
  fun `closing an owner that never used its engine calls nothing`() =
    readerTest {
      owner.close()

      assertEquals(0, binds)
      assertEquals(emptyList<Any>(), san.calls)
    }

  @Test
  fun `a shutdown that stays pending is a defect that stops the worker`() =
    readerTest {
      val live = engine() as ReaderEngineAccess.Live

      // A job that no one releases keeps the shutdown pending.
      withContext(owner.worker) {
        val content = LongArray(1)

        live.san.contentOpen(live.engine, content)
        san.script { SanAbi.STATUS_INPUT_NEEDED }
        live.san.jobRequest(content[0], 0, 0, 0, 0, 0, LongArray(1))
      }

      val worker = san.calls.last().thread

      assertSuspendFails<IllegalStateException> { owner.close() }
      assertTrue(san.engineLive)
      worker.join(TimeUnit.SECONDS.toMillis(BOUND_SECONDS))
      assertFalse(worker.isAlive)
      owner.close()
    }

  private suspend fun engine(): ReaderEngineAccess =
    withContext(owner.worker) { owner.engine() }

  private companion object {
    const val BOUND_SECONDS = 5L
    val profile = ReaderEngineSettings.FIRST_ITERATION
  }
}
