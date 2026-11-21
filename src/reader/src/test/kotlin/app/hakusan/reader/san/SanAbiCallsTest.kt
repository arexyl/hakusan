package app.hakusan.reader.san

import app.hakusan.reader.ReaderTarget
import kotlin.concurrent.thread
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrowsExactly
import org.junit.jupiter.api.Test

/**
 * Checks where the calls on the San library may come from. The host has no San
 * library, so a call that passes its checks ends as an [UnsatisfiedLinkError].
 */
class SanAbiCallsTest {
  private val target = object : ReaderTarget {}

  private val calls: Map<String, (SanCalls) -> Int> =
    mapOf(
      "engineCreate" to
        {
          it.engineCreate(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, out())
        },
      "engineShutdown" to { it.engineShutdown(HANDLE) },
      "engineCounters" to { it.engineCounters(HANDLE, false, counters()) },
      "contentOpen" to { it.contentOpen(HANDLE, out()) },
      "contentFeed" to
        {
          it.contentFeed(HANDLE, 0L, ByteArray(1), 0, 1, false)
        },
      "contentClose" to { it.contentClose(HANDLE) },
      "jobRequest" to { it.jobRequest(HANDLE, 0, 0, 0, 0, 0, out()) },
      "jobStep" to { it.jobStep(HANDLE, report()) },
      "jobCancel" to { it.jobCancel(HANDLE) },
      "jobRelease" to { it.jobRelease(HANDLE) },
      "outputCopy" to { it.outputCopy(HANDLE, 0, 1, target, 0) },
      "outputRelease" to { it.outputRelease(HANDLE) },
    )

  @Test
  fun `the cases name every call of the seam`() {
    val declared = SanCalls::class.java.declaredMethods.map { it.name }

    assertEquals(declared.sorted(), calls.keys.sorted())
  }

  @Test
  fun `every call but the cancellation rejects a thread off the worker`() {
    val abi = SanAbiCalls(worker = Thread {})

    for ((name, call) in calls - "jobCancel") {
      assertThrowsExactly(
        IllegalStateException::class.java,
        { call(abi) },
        name,
      )
    }
  }

  @Test
  fun `a call on the worker reaches the library`() {
    val abi = SanAbiCalls(worker = Thread.currentThread())

    for ((name, call) in calls - "outputCopy") {
      assertThrowsExactly(UnsatisfiedLinkError::class.java, { call(abi) }, name)
    }
  }

  @Test
  fun `the cancellation reaches the library from any thread`() {
    val abi = SanAbiCalls(worker = Thread.currentThread())
    var failure: Throwable? = null

    thread { failure = runCatching { abi.jobCancel(HANDLE) }.exceptionOrNull() }
      .join()

    assertInstanceOf(UnsatisfiedLinkError::class.java, failure)
  }

  @Test
  fun `a copy on the worker rejects a destination that holds no Bitmap`() {
    val abi = SanAbiCalls(worker = Thread.currentThread())

    assertThrowsExactly(IllegalArgumentException::class.java) {
      abi.outputCopy(HANDLE, 0, 1, target, 0)
    }
  }

  @Test
  fun `the host loads no San library`() {
    assertInstanceOf(SanBinding.LoadFailure::class.java, SanLibrary.bind())
  }

  private fun out() = LongArray(1)

  private fun counters() = LongArray(SanAbi.ENGINE_COUNTERS_LENGTH)

  private fun report() = LongArray(SanAbi.JOB_REPORT_LENGTH)

  private companion object {
    const val HANDLE = 1L
  }
}
