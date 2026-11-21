package app.hakusan.reader.san

import app.hakusan.reader.ReaderTarget
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrowsExactly
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Checks that the scripted engine answers as the header `san.h` states, because
 * the tests of the worker take its statuses for San's.
 */
class ScriptedSanTest {
  private val san = ScriptedSan()

  @Test
  fun `a job publishes, completes, and keeps shutdown pending until released`() {
    val engine = create()
    val content = open(engine)

    san.script(rowsOfFourBytes(width = 4, height = 4))

    val job = request(content)
    val report = report()
    val target = ScriptedTarget(width = 4, height = 4)

    assertEquals(listOf(1L, 1L, 1L), counts(engine))
    assertEquals(SanAbi.STATUS_INPUT_NEEDED, san.jobStep(job, report))
    assertEquals(SanAbi.NO_HANDLE, report[SanAbi.JOB_REPORT_LEASE])
    assertEquals(0L, report[SanAbi.JOB_REPORT_IMAGE_WIDTH])

    assertEquals(SanAbi.STATUS_OK, feed(content, 0L, bytes(0, 8)))
    assertEquals(SanAbi.STATUS_INPUT_NEEDED, san.jobStep(job, report))

    val lease = report[SanAbi.JOB_REPORT_LEASE]

    assertNotEquals(SanAbi.NO_HANDLE, lease)
    assertEquals(1L, report[SanAbi.JOB_REPORT_REVISION])
    assertEquals(2L, report[SanAbi.JOB_REPORT_COVERED_ROWS])
    assertEquals(4L, report[SanAbi.JOB_REPORT_LEASE_WIDTH])
    assertEquals(SanAbi.STATUS_OK, san.outputCopy(lease, 0, 2, target, 0))

    assertEquals(SanAbi.STATUS_OK, feed(content, 8L, bytes(8, 8), end = true))
    assertEquals(SanAbi.STATUS_OK, san.jobStep(job, report))
    assertEquals(lease, report[SanAbi.JOB_REPORT_LEASE])
    assertEquals(2L, report[SanAbi.JOB_REPORT_REVISION])
    assertEquals(4L, report[SanAbi.JOB_REPORT_COVERED_ROWS])
    assertArrayEquals(bytes(0, 16), san.contents.single().bytes())

    assertEquals(SanAbi.STATUS_PENDING, san.engineShutdown(engine))
    assertEquals(listOf(0L, 1L, 1L), counts(engine))
    assertEquals(SanAbi.STATUS_OK, san.jobRelease(job))
    assertEquals(SanAbi.STATUS_PENDING, san.engineShutdown(engine))
    assertEquals(SanAbi.STATUS_OK, san.outputCopy(lease, 2, 2, target, 2))
    assertEquals(SanAbi.STATUS_OK, san.outputRelease(lease))
    assertEquals(listOf(0L, 0L, 0L), counts(engine))
    assertEquals(SanAbi.STATUS_OK, san.engineShutdown(engine))

    assertFalse(san.engineLive)
    assertEquals(SanAbi.STATUS_INVALID_HANDLE, san.engineShutdown(engine))

    assertEquals(
      listOf(
        ScriptedTarget.Copy(lease, 0, 2, 0),
        ScriptedTarget.Copy(lease, 2, 2, 2),
      ),
      target.copies,
    )

    assertEquals(emptyList<String>(), san.violations)
  }

  @Test
  fun `a feed out of order is a binding mismatch that changes nothing`() {
    val engine = create(inputPortionBytes = 8)
    val content = open(engine)

    san.script { SanAbi.STATUS_INPUT_NEEDED }

    val job = request(content)

    assertEquals(SanAbi.STATUS_OK, feed(content, 0L, bytes(0, 8)))

    // The content holds bytes that no step has consumed.
    assertEquals(
      SanAbi.STATUS_BINDING_MISMATCH,
      feed(content, 8L, bytes(8, 8)),
    )

    assertEquals(SanAbi.STATUS_INPUT_NEEDED, san.jobStep(job, report()))

    // Another position, a portion above the limit, no byte without the end,
    // and two ranges outside their array.
    val rejected =
      listOf(
        feed(content, 7L, bytes(8, 8)),
        feed(content, 8L, bytes(8, 9)),
        feed(content, 8L, ByteArray(0)),
        san.contentFeed(content, 8L, ByteArray(4), 2, 3, false),
        san.contentFeed(content, 8L, ByteArray(4), -1, 2, false),
      )

    assertEquals(List(5) { SanAbi.STATUS_BINDING_MISMATCH }, rejected)
    assertArrayEquals(bytes(0, 8), san.contents.single().bytes())

    assertEquals(
      SanAbi.STATUS_OK,
      feed(content, 8L, ByteArray(0), end = true),
    )

    assertEquals(
      SanAbi.STATUS_BINDING_MISMATCH,
      feed(content, 8L, ByteArray(0), end = true),
    )
  }

  @Test
  fun `a set cancellation flag ends the job before its script runs`() {
    val engine = create()
    val content = open(engine)

    san.script(rowsOfFourBytes(width = 4, height = 4))

    val job = request(content)
    val published = report()
    val canceled = report()

    feed(content, 0L, bytes(0, 4))
    san.jobStep(job, published)

    assertEquals(SanAbi.STATUS_OK, feed(content, 4L, bytes(4, 4)))
    assertEquals(SanAbi.STATUS_OK, san.jobCancel(job))
    assertEquals(SanAbi.STATUS_CANCELED, san.jobStep(job, canceled))
    assertArrayEquals(published, canceled)

    assertEquals(SanAbi.STATUS_CLOSED, san.jobStep(job, report()))
    assertEquals(SanAbi.STATUS_CLOSED, feed(content, 8L, bytes(8, 4)))
    assertEquals(SanAbi.STATUS_OK, san.jobCancel(job))
    assertEquals(SanAbi.STATUS_OK, san.jobRelease(job))
    assertEquals(SanAbi.STATUS_INVALID_HANDLE, san.jobCancel(job))
    assertEquals(SanAbi.STATUS_INVALID_HANDLE, san.jobRelease(job))
  }

  @Test
  fun `a cancellation after the terminal outcome changes nothing`() {
    val engine = create()
    val content = open(engine)

    san.script { SanAbi.STATUS_INVALID_CONTENT }

    val job = request(content)

    assertEquals(SanAbi.STATUS_INVALID_CONTENT, san.jobStep(job, report()))
    assertEquals(SanAbi.STATUS_OK, san.jobCancel(job))
    assertFalse(checkNotNull(san.contents.single().job).canceled)
  }

  @Test
  fun `a cancellation from another thread reaches a step that runs`() {
    val engine = create()
    val content = open(engine)
    val entered = CountDownLatch(1)
    val resume = CountDownLatch(1)

    san.script {
      entered.countDown()
      resume.await(WAIT_SECONDS, TimeUnit.SECONDS)

      if (canceled) SanAbi.STATUS_CANCELED else SanAbi.STATUS_INPUT_NEEDED
    }

    val job = request(content)
    var cancellation = SanAbi.STATUS_INVALID_HANDLE

    val canceler = thread {
      entered.await(WAIT_SECONDS, TimeUnit.SECONDS)
      cancellation = san.jobCancel(job)
      resume.countDown()
    }

    assertEquals(SanAbi.STATUS_CANCELED, san.jobStep(job, report()))
    canceler.join()

    assertEquals(SanAbi.STATUS_OK, cancellation)
    assertEquals(emptyList<String>(), san.violations)

    assertEquals(
      listOf("jobCancel" to canceler, "jobStep" to Thread.currentThread()),
      san.calls.takeLast(2).map { it.name to it.thread },
    )
  }

  @Test
  fun `a call off the thread of the first call is a violation`() {
    val engine = create()
    val content = open(engine)
    var failure: Throwable? = null

    thread {
      failure = runCatching { san.contentClose(content) }.exceptionOrNull()
    }
      .join()

    assertTrue(failure is IllegalStateException)

    assertEquals(
      listOf("contentClose ran off the thread of the first call."),
      san.violations,
    )

    assertEquals(1, san.liveContentCount)
    assertEquals(SanAbi.STATUS_OK, san.contentClose(content))
    assertEquals(SanAbi.STATUS_OK, san.engineShutdown(engine))
  }

  @Test
  fun `a call beside another call is a violation`() {
    val engine = create()
    val content = open(engine)

    san.script {
      san.engineCounters(engine, false, counters())

      SanAbi.STATUS_INPUT_NEEDED
    }

    val job = request(content)

    assertThrowsExactly(IllegalStateException::class.java) {
      san.jobStep(job, report())
    }

    assertEquals(
      listOf("engineCounters ran beside another call."),
      san.violations,
    )
    assertEquals(SanAbi.STATUS_OK, san.contentClose(content))
  }

  @Test
  fun `a cancellation that overlaps the release of its job is a violation`() {
    val engine = create()
    val content = open(engine)
    val entered = CountDownLatch(1)
    val resume = CountDownLatch(1)

    san.script { SanAbi.STATUS_INPUT_NEEDED }

    val job = request(content)

    san.contentClose(content)

    san.duringCancel = {
      entered.countDown()
      resume.await(WAIT_SECONDS, TimeUnit.SECONDS)
    }

    val canceler = thread { san.jobCancel(job) }

    assertTrue(entered.await(WAIT_SECONDS, TimeUnit.SECONDS))
    assertEquals(SanAbi.STATUS_OK, san.jobRelease(job))

    resume.countDown()
    canceler.join()

    assertEquals(
      listOf(
        "jobRelease ran beside jobCancel.",
        "jobCancel ran beside jobRelease.",
      ),
      san.violations,
    )

    assertEquals(SanAbi.STATUS_OK, san.engineShutdown(engine))
  }

  @Test
  fun `closing a content ends its live job as canceled`() {
    val engine = create()
    val content = open(engine)

    san.script { SanAbi.STATUS_INPUT_NEEDED }

    val job = request(content)

    assertEquals(SanAbi.STATUS_BINDING_MISMATCH, san.jobRelease(job))
    assertEquals(SanAbi.STATUS_OK, san.contentClose(content))
    assertEquals(SanAbi.STATUS_CLOSED, san.jobStep(job, report()))
    assertEquals(SanAbi.STATUS_INVALID_HANDLE, feed(content, 0L, bytes(0, 4)))
    assertEquals(SanAbi.STATUS_INVALID_HANDLE, san.contentClose(content))
    assertEquals(SanAbi.STATUS_OK, san.jobRelease(job))
    assertEquals(SanAbi.STATUS_OK, san.engineShutdown(engine))
  }

  @Test
  fun `shutdown ends live jobs and closes the contents itself`() {
    val engine = create()
    val content = open(engine)
    val idle = open(engine)

    san.script { SanAbi.STATUS_INPUT_NEEDED }

    val job = request(content)

    assertEquals(SanAbi.STATUS_PENDING, san.engineShutdown(engine))

    val closed =
      listOf(
        san.contentClose(content),
        san.contentOpen(engine, LongArray(1)),
        feed(idle, 0L, bytes(0, 4)),
        san.jobRequest(idle, 0, 0, 0, 0, 0, LongArray(1)),
        san.jobStep(job, report()),
      )

    assertEquals(List(5) { SanAbi.STATUS_CLOSED }, closed)
    assertEquals(SanAbi.STATUS_OK, san.engineCounters(engine, true, counters()))
    assertEquals(SanAbi.STATUS_OK, san.jobRelease(job))
    assertEquals(SanAbi.STATUS_OK, san.engineShutdown(engine))
    assertEquals(SanAbi.STATUS_INVALID_HANDLE, san.contentClose(content))

    assertNotEquals(engine, create())
  }

  @Test
  fun `a released lease admits no copy and no second release`() {
    val engine = create()
    val content = open(engine)

    san.script(rowsOfFourBytes(width = 4, height = 2))

    val job = request(content)
    val report = report()

    feed(content, 0L, bytes(0, 4))

    assertEquals(SanAbi.STATUS_INPUT_NEEDED, san.jobStep(job, report))

    val lease = report[SanAbi.JOB_REPORT_LEASE]
    val target = ScriptedTarget(width = 4, height = 2)

    assertEquals(1, san.heldLeaseCount)
    assertEquals(SanAbi.STATUS_OK, san.outputRelease(lease))

    assertEquals(
      SanAbi.STATUS_INVALID_HANDLE,
      san.outputCopy(lease, 0, 1, target, 0),
    )

    assertEquals(SanAbi.STATUS_INVALID_HANDLE, san.outputRelease(lease))
    assertEquals(emptyList<ScriptedTarget.Copy>(), target.copies)

    // The job keeps its own claim and publishes on, without a new claim of
    // the caller.
    feed(content, 4L, bytes(4, 4), end = true)

    assertEquals(SanAbi.STATUS_OK, san.jobStep(job, report))
    assertEquals(lease, report[SanAbi.JOB_REPORT_LEASE])
    assertEquals(0, san.heldLeaseCount)
    assertEquals(SanAbi.STATUS_OK, san.jobRelease(job))
    assertEquals(SanAbi.STATUS_OK, san.engineShutdown(engine))
  }

  @Test
  fun `a copy checks its destination and its rows`() {
    val engine = create()
    val content = open(engine)

    san.script {
      describe(4, 4)
      admit(4, 4, alpha = SanAbi.ALPHA_PREMULTIPLIED)
      cover(2)

      SanAbi.STATUS_INPUT_NEEDED
    }

    val job = request(content)
    val report = report()

    san.jobStep(job, report)

    val lease = report[SanAbi.JOB_REPORT_LEASE]
    val premultiplied = SanAbi.ALPHA_PREMULTIPLIED
    val target = ScriptedTarget(width = 4, height = 4, premultiplied)

    val rejected =
      listOf(
        san.outputCopy(lease, 0, 2, object : ReaderTarget {}, 0),
        san.outputCopy(lease, 0, 2, ScriptedTarget(5, 4, premultiplied), 0),
        san.outputCopy(lease, 0, 2, ScriptedTarget(4, 4), 0),
        san.outputCopy(lease, 0, 0, target, 0),
        san.outputCopy(lease, 1, 2, target, 0),
        san.outputCopy(lease, -1, 2, target, 0),
        san.outputCopy(lease, 0, 2, target, 3),
        san.outputCopy(lease, 0, 2, target, -1),
      )

    assertEquals(List(8) { SanAbi.STATUS_BINDING_MISMATCH }, rejected)
    assertEquals(emptyList<ScriptedTarget.Copy>(), target.copies)
    assertEquals(SanAbi.STATUS_OK, san.outputCopy(lease, 1, 1, target, 3))
  }

  @Test
  fun `the limits refuse a creation, a content, and a job`() {
    val out = LongArray(1) { STALE }

    assertEquals(SanAbi.STATUS_BUDGET_EXCEEDED, create(out, contentCount = 0))
    assertEquals(SanAbi.NO_HANDLE, out[0])
    assertEquals(SanAbi.STATUS_BUDGET_EXCEEDED, create(out, jobCount = 65_536))
    assertEquals(SanAbi.STATUS_OK, create(out, contentCount = 2, jobCount = 1))
    assertEquals(SanAbi.STATUS_BUDGET_EXCEEDED, create(LongArray(1)))

    val engine = out[0]
    val first = open(engine)
    val second = open(engine)

    out[0] = STALE

    assertEquals(SanAbi.STATUS_BUDGET_EXCEEDED, san.contentOpen(engine, out))
    assertEquals(SanAbi.NO_HANDLE, out[0])

    san.script { SanAbi.STATUS_INPUT_NEEDED }
    request(first)

    out[0] = STALE

    assertEquals(
      SanAbi.STATUS_BUDGET_EXCEEDED,
      san.jobRequest(second, 0, 0, 0, 0, 0, out),
    )

    assertEquals(SanAbi.NO_HANDLE, out[0])

    assertEquals(
      SanAbi.STATUS_BINDING_MISMATCH,
      san.jobRequest(first, 0, 0, 0, 0, 0, out),
    )

    assertEquals(
      SanAbi.STATUS_BINDING_MISMATCH,
      san.jobRequest(second, 4, 0, 4, 8, 0, out),
    )
  }

  @Test
  fun `a lease slot counts from the request until San reclaims it`() {
    val engine = create(outputLeaseCount = 1)
    val first = open(engine)
    val second = open(engine)
    val out = LongArray(1)

    san.script { SanAbi.STATUS_INPUT_NEEDED }
    san.script { SanAbi.STATUS_INPUT_NEEDED }
    request(first)

    assertEquals(
      SanAbi.STATUS_BUDGET_EXCEEDED,
      san.jobRequest(second, 0, 0, 0, 0, 0, out),
    )

    // The job never published, so its end reclaims the slot.
    san.contentClose(first)

    assertEquals(SanAbi.STATUS_OK, san.jobRequest(second, 0, 0, 0, 0, 0, out))
  }

  @Test
  fun `an array of another length changes nothing`() {
    val engine = create()
    val wide = LongArray(2) { STALE }

    assertEquals(SanAbi.STATUS_BINDING_MISMATCH, san.contentOpen(engine, wide))
    assertEquals(listOf(STALE, STALE), wide.toList())
    assertEquals(0, san.liveContentCount)
  }

  @Test
  fun `an injected failure answers one call, which keeps nothing`() {
    val engine = create()
    val out = LongArray(1) { STALE }

    san.failNext("contentOpen", SanAbi.STATUS_ALLOCATION_FAILED)

    assertEquals(SanAbi.STATUS_ALLOCATION_FAILED, san.contentOpen(engine, out))
    assertEquals(SanAbi.NO_HANDLE, out[0])
    assertEquals(0, san.liveContentCount)
    assertEquals(SanAbi.STATUS_OK, san.contentOpen(engine, out))
    assertEquals(1, san.liveContentCount)

    assertThrowsExactly(IllegalArgumentException::class.java) {
      san.failNext("jobStep", SanAbi.STATUS_ENGINE_FAILURE)
    }

    assertThrowsExactly(IllegalArgumentException::class.java) {
      san.failNext("contentOpen", SanAbi.STATUS_OK)
    }
  }

  @Test
  fun `a script that breaks a rule of the step fails`() {
    val broken =
      listOf<ScriptedStep>(
        { SanAbi.STATUS_PROGRESS },
        { SanAbi.STATUS_CANCELED },
        { SanAbi.STATUS_OK },
        { SanAbi.STATUS_PENDING },
        {
          describe(4, 4)
          admit(4, 4)
          cover(2)

          SanAbi.STATUS_OK
        },
        {
          describe(4, 4)
          describe(4, 4)

          SanAbi.STATUS_INPUT_NEEDED
        },
      )

    val engine = create(contentCount = broken.size.toLong())

    for (script in broken) {
      val content = open(engine)

      san.script(script)

      val job = request(content)

      assertThrowsExactly(IllegalStateException::class.java) {
        san.jobStep(job, report())
      }

      san.contentClose(content)
      san.jobRelease(job)
    }

    assertEquals(SanAbi.STATUS_OK, san.engineShutdown(engine))
  }

  private fun create(
    contentCount: Long = 4,
    jobCount: Long = 4,
    inputPortionBytes: Long = 64,
    outputLeaseCount: Long = 4,
  ): Long {
    val out = LongArray(1)

    assertEquals(
      SanAbi.STATUS_OK,
      create(out, contentCount, jobCount, inputPortionBytes, outputLeaseCount),
    )

    return out[0]
  }

  private fun create(
    out: LongArray,
    contentCount: Long = 4,
    jobCount: Long = 4,
    inputPortionBytes: Long = 64,
    outputLeaseCount: Long = 4,
  ): Int =
    san.engineCreate(
      aggregateBudgetBytes = LARGE,
      contentCount = contentCount,
      jobCount = jobCount,
      contentLengthBytes = LARGE,
      inputPortionBytes = inputPortionBytes,
      imageAxisPixels = LARGE,
      imageAreaPixels = LARGE,
      metadataBytes = LARGE,
      metadataExpandedBytes = LARGE,
      metadataPayloadBytes = LARGE,
      heldChunkBytes = LARGE,
      iccTagCount = LARGE,
      exifEntryCount = LARGE,
      outputBytes = LARGE,
      outputLeaseBytes = LARGE,
      outputLeaseCount = outputLeaseCount,
      engineOut = out,
    )

  private fun open(engine: Long): Long {
    val out = LongArray(1)

    assertEquals(SanAbi.STATUS_OK, san.contentOpen(engine, out))

    return out[0]
  }

  private fun request(content: Long): Long {
    val out = LongArray(1)

    assertEquals(SanAbi.STATUS_OK, san.jobRequest(content, 0, 0, 0, 0, 0, out))

    return out[0]
  }

  private fun feed(
    content: Long,
    position: Long,
    bytes: ByteArray,
    end: Boolean = false,
  ): Int = san.contentFeed(content, position, bytes, 0, bytes.size, end)

  /** The counts of live contents, owned jobs, and live leases. */
  private fun counts(engine: Long): List<Long> {
    val counters = counters()

    assertEquals(SanAbi.STATUS_OK, san.engineCounters(engine, false, counters))

    return listOf(
      counters[SanAbi.ENGINE_COUNTERS_CONTENT_COUNT],
      counters[SanAbi.ENGINE_COUNTERS_JOB_COUNT],
      counters[SanAbi.ENGINE_COUNTERS_LEASE_COUNT],
    )
  }

  /** The [count] bytes of a counting sequence from [first] on. */
  private fun bytes(first: Int, count: Int): ByteArray =
    ByteArray(count) { (first + it).toByte() }

  private fun report() = LongArray(SanAbi.JOB_REPORT_LENGTH)

  private fun counters() = LongArray(SanAbi.ENGINE_COUNTERS_LENGTH)

  private companion object {
    const val LARGE = 1L shl 30
    const val STALE = 77L
    const val WAIT_SECONDS = 10L
  }
}
