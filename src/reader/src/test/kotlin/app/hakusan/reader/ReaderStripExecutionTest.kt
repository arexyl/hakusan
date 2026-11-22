package app.hakusan.reader

import app.hakusan.reader.ReaderContentTerminal.Canceled
import app.hakusan.reader.san.SanAbi
import app.hakusan.reader.san.ScriptedSan
import app.hakusan.reader.san.ScriptedStep
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * Checks the retained strips of the resource owner against the scripted engine:
 * which lease stays after its job, how a strip answers a band without a job,
 * and when a strip leaves.
 */
class ReaderStripExecutionTest {
  private val book = ReaderTestBook()
  private val san = ScriptedSan()
  private val source = TestSource()
  private val platform = ScriptedPlatform(san)
  private val owner = ReaderResourceOwner(RETAINING, platform)

  /** The bytes of the segment, which every input of a test delivers. */
  private val bytes = ReaderRepresentation()

  @Test
  fun `a completed band stays as a strip that answers it without a job`() =
    readerTest {
      val requests = contentOwner()
      val published = requests.record()

      assertEquals(completed(), decode(requests, band(2)).terminal)
      assertEquals(1, san.heldLeaseCount)
      assertEquals(1, owner.onWorker { strips.count })

      // The same band again: no input, no content, and no job.
      val again = submit(requests, band(2))

      owner.execute(requests, source)

      assertEquals(completed(), again.terminal)
      assertEquals(1, source.opens.get())
      assertEquals(1, san.count("contentOpen"))
      assertEquals(1, san.count("jobRequest"))
      assertEquals(2, san.count("outputCopy"))

      // Both outputs hold the rows of the band, at its place in the grid.
      assertEquals(listOf(region(2), region(2)), published.map { it.region })
      assertEquals(1, owner.onWorker { strips.count })
      assertClosed(requests)
    }

  @Test
  fun `only a completed band at a scale of 1 to 1 stays`() = readerTest {
    val requests = contentOwner()

    requests.record()

    // A whole image, a scaled band, and a band whose input ends early.
    val whole = submit(requests, ReaderOutputRequest.Whole())

    source.supply(input())

    san.script {
      if (content.ended) {
        describe(SEGMENT.width, SEGMENT.height)
        admit(SEGMENT.width, SEGMENT.height, earlyPublication = false)
        cover(SEGMENT.height)

        SanAbi.STATUS_OK
      } else {
        SanAbi.STATUS_INPUT_NEEDED
      }
    }

    owner.execute(requests, source)

    assertEquals(completed(), whole.terminal)

    val scaled = ReaderOutputRequest.Band(SEGMENT, 0, 8, maxOutputWidth = 2)
    val grid = SEGMENT.grid(2)

    source.supply(input())

    san.script {
      if (content.ended) {
        describe(SEGMENT.width, SEGMENT.height, grid.width, grid.height)
        admit(grid.width, 4, earlyPublication = false)
        cover(4)

        SanAbi.STATUS_OK
      } else {
        SanAbi.STATUS_INPUT_NEEDED
      }
    }

    val half = submit(requests, scaled)

    owner.execute(requests, source)

    assertEquals(completed(), half.terminal)

    source.supply(input())

    san.script {
      if (content.ended) {
        SanAbi.STATUS_TRUNCATED_CONTENT
      } else {
        SanAbi.STATUS_INPUT_NEEDED
      }
    }

    val truncated = submit(requests, band(0))

    owner.execute(requests, source)

    assertEquals(
      failed(ReaderContentFailure.TRUNCATED_CONTENT),
      truncated.terminal,
    )

    assertEquals(0, san.heldLeaseCount)
    assertEquals(0, owner.onWorker { strips.count })
    assertClosed(requests)
  }

  @Test
  fun `a job finds its lease after the least recently used strip left`() =
    readerTest {
      val requests = contentOwner()

      requests.record()

      // Four leases: three strips stay beside the band that decodes.
      for (index in 0..3) {
        assertEquals(completed(), decode(requests, band(index)).terminal)
      }

      assertEquals(4, san.heldLeaseCount)

      // A hit makes the first band the most recently used strip.
      assertEquals(completed(), hit(requests, band(0)).terminal)
      assertEquals(completed(), decode(requests, band(4)).terminal)
      assertEquals(4, san.heldLeaseCount)

      // The second band left for the fifth, and the first one stayed.
      assertEquals(completed(), hit(requests, band(0)).terminal)
      assertEquals(completed(), decode(requests, band(1)).terminal)

      // No request met the count of the leases.
      assertEquals(
        emptyList<Int>(),
        san.calls
          .filter { it.name == "jobRequest" }
          .map { it.status }
          .filter { it != SanAbi.STATUS_OK },
      )

      assertClosed(requests)
    }

  @Test
  fun `the strips of a session leave when it closes`() = readerTest {
    val first = contentOwner()
    val second = contentOwner()

    first.record()
    second.record()

    assertEquals(completed(), decode(first, band(0)).terminal)
    assertEquals(completed(), decode(second, band(0)).terminal)
    assertEquals(2, san.heldLeaseCount)

    first.beginClose()
    san.await { heldLeaseCount == 1 }

    // The strip of the other session still answers its band.
    assertEquals(completed(), hit(second, band(0)).terminal)
    first.awaitClosed()
    assertClosed(second)
  }

  @Test
  fun `closing the owner releases the strips of a session that is open`() =
    readerTest {
      val requests = contentOwner()

      requests.record()

      assertEquals(completed(), decode(requests, band(0)).terminal)
      assertEquals(completed(), decode(requests, band(1)).terminal)
      assertEquals(2, san.heldLeaseCount)

      // The shutdown is not left pending, and the later close of the session
      // finds no worker and no strip.
      assertClosed(null)
      requests.beginClose()
      requests.awaitClosed()
    }

  @Test
  fun `a band never answers another session or another unit`() = readerTest {
    val first = contentOwner()
    val second = contentOwner()

    first.record()
    second.record()

    assertEquals(completed(), decode(first, band(0)).terminal)

    // Each of these decodes: the session, the unit, or the rows differ.
    assertEquals(completed(), decode(second, band(0)).terminal)
    assertEquals(completed(), decode(first, band(0), unit = 1).terminal)
    assertEquals(completed(), decode(first, band(1)).terminal)
    assertEquals(4, san.count("jobRequest"))

    first.beginClose()
    first.awaitClosed()
    assertClosed(second)
  }

  @Test
  fun `other bytes of a unit drop the strips of its earlier bytes`() =
    readerTest {
      val requests = contentOwner()

      requests.record()

      assertEquals(completed(), decode(requests, band(0)).terminal)
      assertEquals(completed(), decode(requests, band(1)).terminal)
      assertEquals(2, san.heldLeaseCount)

      // An acquisition of the unit that binds another representation.
      val other = ReaderRepresentation()

      assertEquals(completed(), decode(requests, band(2), other).terminal)
      assertEquals(1, san.heldLeaseCount)

      // The earlier bands decode again, and each of them drops the strip of
      // the other bytes in turn.
      assertEquals(completed(), decode(requests, band(0)).terminal)
      assertEquals(1, san.heldLeaseCount)

      // A request of the whole image with other bytes drops the strip too,
      // also when its job fails.
      val whole = submit(requests, ReaderOutputRequest.Whole())

      source.supply(input(ReaderRepresentation()))
      san.script { SanAbi.STATUS_INVALID_CONTENT }
      owner.execute(requests, source)

      assertEquals(failed(ReaderContentFailure.INVALID_CONTENT), whole.terminal)
      assertEquals(0, san.heldLeaseCount)
      assertClosed(requests)
    }

  @Test
  fun `a canceled hit leaves its strip, which then goes with its session`() =
    readerTest {
      // One live output: while presentation holds it, a hit cannot publish.
      val limits = ReaderOutputLimits(liveOutputs = 1, awaitingPublications = 1)
      val requests = contentOwner(limits)
      val presenter = TestPresenter(requests)

      source.supply(input())
      san.script(script(band(0)))

      val decoded = submit(requests, band(0))
      val running =
        async(start = CoroutineStart.UNDISPATCHED) {
          owner.execute(requests, source)
        }

      val shown = presenter.take()

      assertSame(decoded, running.await())
      assertEquals(completed(), decoded.terminal)

      val waiting = submit(requests, band(0))
      val serving =
        async(start = CoroutineStart.UNDISPATCHED) {
          owner.execute(requests, source)
        }

      // The hit copied its rows and found the owner busy.
      san.await { count("outputCopy") == 2 }
      assertFalse(serving.isCompleted)

      requests.beginClose()

      assertSame(waiting, serving.await())
      assertEquals(Canceled, waiting.terminal)
      shown.close()
      san.await { heldLeaseCount == 0 }
      requests.awaitClosed()
      assertClosed(null)
    }

  /** The four rows of the band [index] of the segment, at a scale of 1:1. */
  private fun band(index: Int): ReaderOutputRequest.Band =
    ReaderOutputRequest.Band(SEGMENT, index * 4, index * 4 + 4)

  private fun region(index: Int): ReaderImageRegion =
    ReaderImageRegion(0, index * 4, SEGMENT.width, index * 4 + 4)

  /** A job that completes [band] at the end of its input, in one revision. */
  private fun script(band: ReaderOutputRequest.Band): ScriptedStep = {
    if (content.ended) {
      describe(SEGMENT.width, SEGMENT.height)

      admit(
        SEGMENT.width,
        band.bottom - band.top,
        top = band.top,
        earlyPublication = false,
      )

      decoded(band.bottom)
      cover(band.bottom - band.top)

      SanAbi.STATUS_OK
    } else {
      SanAbi.STATUS_INPUT_NEEDED
    }
  }

  private fun input(
    representation: ReaderRepresentation = bytes
  ): ControlledReaderInput =
    ControlledReaderInput(
        countingBytes(16),
        maxTransfer = 5,
        representation = representation,
      )
      .apply {
        receiveThrough(16)
        end()
      }

  private fun submit(
    requests: ReaderContentRequests,
    output: ReaderOutputRequest,
    unit: Int = 0,
  ): ReaderContentRequest =
    checkNotNull(requests.submit(book.descriptor(unit), output))

  /** Executes [band] through a job, which opens one input. */
  private suspend fun decode(
    requests: ReaderContentRequests,
    band: ReaderOutputRequest.Band,
    representation: ReaderRepresentation = bytes,
    unit: Int = 0,
  ): ReaderContentRequest {
    val opens = source.opens.get()
    val request = submit(requests, band, unit)

    source.supply(input(representation))
    san.script(script(band))
    owner.execute(requests, source)

    assertEquals(opens + 1, source.opens.get())

    return request
  }

  /** Executes [band] from a strip, which opens no input and runs no job. */
  private suspend fun hit(
    requests: ReaderContentRequests,
    band: ReaderOutputRequest.Band,
  ): ReaderContentRequest {
    val opens = source.opens.get()
    val jobs = san.count("jobRequest")
    val request = submit(requests, band)

    owner.execute(requests, source)

    assertEquals(opens, source.opens.get())
    assertEquals(jobs, san.count("jobRequest"))

    return request
  }

  /**
   * Makes presentation return each target at once and records the coverage of
   * every output.
   */
  private fun ReaderContentRequests.record(): List<ReaderImageCoverage> {
    val outputs = CopyOnWriteArrayList<ReaderImageCoverage>()

    setPresentationWakeup {
      takePublication()?.output?.let { output ->
        outputs += output.coverage
        output.close()
      }
    }

    return outputs
  }

  private suspend fun contentOwner(
    limits: ReaderOutputLimits = ReaderOutputLimits.FIRST_ITERATION
  ): ReaderContentRequests =
    ReaderContentRequests(ReaderSessionGate().start(), limits)

  /** Reads state that only the worker may touch. */
  private suspend fun <T> ReaderResourceOwner.onWorker(
    read: ReaderResourceOwner.() -> T
  ): T = withContext(worker) { read() }

  /**
   * Closes [requests] and the owner, and checks that the engine then holds
   * nothing, that its shutdown was not left pending, and that no call broke a
   * rule.
   */
  private suspend fun assertClosed(requests: ReaderContentRequests?) {
    requests?.beginClose()
    requests?.awaitClosed()
    owner.close()

    assertEquals(emptyList<String>(), san.violations)

    assertEquals(
      emptyList<Any>(),
      san.calls.filter { it.status in CALLER_DEFECTS },
    )

    assertEquals(0, san.liveContentCount)
    assertEquals(0, san.ownedJobCount)
    assertEquals(0, san.heldLeaseCount)
    assertEquals("engineShutdown", san.calls.last().name)
    assertEquals(SanAbi.STATUS_OK, san.calls.last().status)
    assertEquals(platform.created.toSet(), platform.recycled.toSet())
    assertEquals(emptyList<String>(), platform.violations)
  }

  private companion object {
    /** A segment of four columns and forty rows, in bands of four rows. */
    val SEGMENT = ReaderImageGeometry(4, 40)

    val RETAINING =
      ReaderEngineSettings.FIRST_ITERATION.copy(
        backwardVariant = ReaderBackwardVariant.RETAIN_STRIPS
      )

    val CALLER_DEFECTS =
      setOf(
        SanAbi.STATUS_INVALID_HANDLE,
        SanAbi.STATUS_BINDING_MISMATCH,
        SanAbi.STATUS_CLOSED,
      )
  }
}
