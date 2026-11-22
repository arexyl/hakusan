package app.hakusan.reader

import app.hakusan.reader.ReaderContentTerminal.Canceled
import app.hakusan.reader.san.SanAbi
import app.hakusan.reader.san.ScriptedRequest
import app.hakusan.reader.san.ScriptedSan
import app.hakusan.reader.san.ScriptedTarget
import app.hakusan.reader.san.rowsOfFourBytes
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Checks what an executed request publishes, against the scripted engine and
 * targets without pixels. The test plays presentation: it takes each
 * publication and returns its target when the case says so.
 */
class ReaderOutputExecutionTest {
  private val book = ReaderTestBook()
  private val san = ScriptedSan()
  private val source = TestSource()
  private val platform = ScriptedPlatform(san)
  private var owner = ReaderResourceOwner(PROFILE, platform)

  @Test
  fun `revisions alternate two targets and never write one that is held`() =
    readerTest {
      val requests = contentOwner()
      val presenter = TestPresenter(requests)
      val input = liveInput()

      source.supply(input)
      san.script(rowsOfFourBytes(width = 4, height = 4))

      val request = submit(requests)
      val running = executing(requests)

      input.receiveThrough(4)

      val first = presenter.take()

      input.receiveThrough(8)

      val second = presenter.take()

      assertNotSame(first.scripted, second.scripted)
      assertEquals(listOf(1, 2), listOf(first.rows, second.rows))

      // Both targets are with presentation, so the third row is not copied.
      input.receiveThrough(12)
      san.await { steppedAt(12L) }

      assertEquals(listOf(copy(0, 1)), first.scripted.rowCopies())
      assertEquals(listOf(copy(0, 2)), second.scripted.rowCopies())
      first.close()

      val third = presenter.take()

      assertSame(first.scripted, third.scripted)
      assertEquals(3, third.rows)
      assertEquals(listOf(copy(0, 1), copy(1, 2)), third.scripted.rowCopies())

      // The job ends while presentation holds both targets.
      input.receiveThrough(16)
      input.end()
      san.await { calls.any { it.status == SanAbi.STATUS_OK && it.isStep } }

      assertNull(request.terminal)
      assertEquals(listOf(copy(0, 2)), second.scripted.rowCopies())
      second.close()

      val last = presenter.take()

      assertSame(second.scripted, last.scripted)
      assertEquals(4, last.rows)
      assertEquals(listOf(copy(0, 2), copy(2, 2)), last.scripted.rowCopies())
      assertSame(request, running.request())
      assertEquals(completed(), request.terminal)
      assertEquals(listOf(1, 2, 3, 4), presenter.taken.map { it.revision })

      assertEquals(
        listOf(
          "reader.copy 1 0 1",
          "reader.copy 2 0 2",
          "reader.copy 3 1 2",
          "reader.copy 4 2 2",
        ),
        platform.sections.filter { it != "reader.step" },
      )

      assertEquals(
        san.count("jobStep"),
        platform.sections.count { it == "reader.step" },
      )

      third.close()
      last.close()
      assertReleased()
    }

  @Test
  fun `a stalled presentation holds the final copy only until a cancellation`() =
    readerTest {
      val requests = contentOwner()

      source.supply(completeInput())
      san.script(rowsOfFourBytes(width = 4, height = 4))

      val request = submit(requests)
      val running = executing(requests)

      san.await { calls.any { it.status == SanAbi.STATUS_OK && it.isStep } }

      assertNull(request.terminal)
      requests.beginClose()

      assertSame(request, running.request())
      assertEquals(completed(), request.terminal)
      assertTrue(copiedRows() < 4)
      assertReleased()
    }

  @Test
  fun `a failure of the content still publishes every covered row`() =
    readerTest {
      val requests = contentOwner()
      val published = requests.recordRows()

      source.supply(completeInput())

      // The third row arrives in the step that finds the content invalid.
      san.script {
        val rows = (content.receivedBytes / 4).toInt()

        if (rows > 0 && lease == SanAbi.NO_HANDLE) {
          describe(4, 4)
          admit(4, 4)
        }

        if (rows > report[SanAbi.JOB_REPORT_COVERED_ROWS]) {
          cover(rows)
        }

        if (rows == 3) {
          SanAbi.STATUS_INVALID_CONTENT
        } else {
          SanAbi.STATUS_INPUT_NEEDED
        }
      }

      val request = submit(requests)

      owner.execute(requests, source)

      assertEquals(
        failed(ReaderContentFailure.INVALID_CONTENT),
        request.terminal,
      )

      assertEquals(3, published.last())
      assertReleased()
    }

  @Test
  fun `a producer failure still gets its final copy`() = readerTest {
    val requests = contentOwner()
    val presenter = TestPresenter(requests)
    val stalled = stallAtThreeRows(requests, presenter)

    stalled.input.fail(ReaderAcquisitionFailure.FAILED)
    san.await { calls.any { it.status == SanAbi.STATUS_CANCELED } }

    val second = presenter.take()

    // Presentation holds both targets, and the third row waits for one.
    assertEquals(2, second.rows)
    assertNull(stalled.request.terminal)
    stalled.first.close()

    val last = presenter.take()

    assertEquals(3, last.rows)
    assertSame(stalled.first.scripted, last.scripted)
    assertSame(stalled.request, stalled.running.request())

    assertEquals(
      failed(ReaderContentFailure.INPUT_FAILED),
      stalled.request.terminal,
    )

    second.close()
    last.close()
    assertReleased()
  }

  @Test
  fun `a canceled request gets no final copy`() = readerTest {
    val requests = contentOwner()
    val presenter = TestPresenter(requests)
    val stalled = stallAtThreeRows(requests, presenter)

    // The newer request also releases the publication that still waits.
    submit(requests, unit = 1)

    assertSame(stalled.request, stalled.running.request())
    assertEquals(Canceled, stalled.request.terminal)
    assertEquals(2, copiedRows())
    assertEquals(1, presenter.taken.size)
    stalled.first.close()
    assertReleased()
  }

  @Test
  fun `a busy owner keeps the output with its producer until room returns`() =
    readerTest {
      val limits = ReaderOutputLimits(liveOutputs = 1, awaitingPublications = 1)
      val requests = contentOwner(limits)
      val presenter = TestPresenter(requests)
      val input = liveInput()

      source.supply(input)
      san.script(rowsOfFourBytes(width = 4, height = 4))

      val request = submit(requests)
      val running = executing(requests)

      input.receiveThrough(4)

      val first = presenter.take()

      // Every further attempt is busy, because the owner holds one output.
      input.receiveThrough(16)
      input.end()
      san.await { calls.any { it.status == SanAbi.STATUS_OK && it.isStep } }

      assertNull(request.terminal)
      assertNull(requests.takePublication())

      // A producer that its own discard woke would spin and never park.
      awaitParked(san.calls.first().thread)
      first.close()

      val last = presenter.take()

      assertEquals(4, last.rows)
      assertSame(request, running.request())
      assertEquals(completed(), request.terminal)
      assertEquals(2, presenter.taken.size)
      last.close()
      assertReleased()
    }

  @Test
  fun `a lease that publishes only at its end takes one target`() = readerTest {
    val requests = contentOwner()
    val published = requests.recordRows()

    source.supply(completeInput())

    san.script {
      if (content.ended) {
        describe(4, 4)
        admit(4, 4, earlyPublication = false)
        cover(4)

        SanAbi.STATUS_OK
      } else {
        SanAbi.STATUS_INPUT_NEEDED
      }
    }

    val request = submit(requests)

    owner.execute(requests, source)

    assertEquals(completed(), request.terminal)
    assertEquals(listOf(4), published)
    assertEquals(1, platform.created.size)
    assertReleased()
  }

  @Test
  fun `a ceiling that admits one target publishes once, at the end`() =
    readerTest {
      owner = ReaderResourceOwner(PROFILE.copy(targetCount = 1), platform)

      val requests = contentOwner()
      val published = requests.recordRows()

      source.supply(completeInput())
      san.script(rowsOfFourBytes(width = 4, height = 4))

      val request = submit(requests)

      owner.execute(requests, source)

      assertEquals(completed(), request.terminal)
      assertEquals(listOf(4), published)
      assertEquals(1, platform.created.size)
      assertReleased()
    }

  @Test
  fun `a ceiling that admits no target ends the request as a budget failure`() =
    readerTest {
      owner = ReaderResourceOwner(PROFILE.copy(targetBytes = 63), platform)

      val requests = contentOwner()
      val published = requests.recordRows()

      source.supply(completeInput())
      san.script(rowsOfFourBytes(width = 4, height = 4))

      val request = submit(requests)

      owner.execute(requests, source)

      assertEquals(
        failed(ReaderContentFailure.BUDGET_EXCEEDED),
        request.terminal,
      )

      assertEquals(emptyList<Int>(), published)
      assertEquals(emptyList<ScriptedTarget>(), platform.created)
      assertTrue(san.calls.any { it.status == SanAbi.STATUS_CANCELED })
      assertReleased()
    }

  @Test
  fun `a job that the engine refuses for its lease creates no target`() =
    readerTest {
      val requests = contentOwner()

      source.supply(completeInput())

      // The image and the grid are reported, and no lease is admitted.
      san.script {
        describe(4, 40_000)

        SanAbi.STATUS_BUDGET_EXCEEDED
      }

      val request = submit(requests)

      owner.execute(requests, source)

      assertEquals(
        failed(ReaderContentFailure.BUDGET_EXCEEDED),
        request.terminal,
      )

      assertEquals(emptyList<ScriptedTarget>(), platform.created)
      assertReleased()
    }

  @Test
  fun `room that an ended output still holds is awaited and not refused`() =
    readerTest {
      owner = ReaderResourceOwner(PROFILE.copy(targetCount = 2), platform)

      val requests = contentOwner()
      val presenter = TestPresenter(requests)
      val input = liveInput(count = 8)

      source.supply(input)
      source.supply(completeInput(count = 8))
      repeat(2) { san.script(rowsOfFourBytes(width = 4, height = 2)) }

      val ended = submit(requests)
      val running = executing(requests)

      input.receiveThrough(4)

      val first = presenter.take()

      input.receiveThrough(8)
      input.end()

      val second = presenter.take()

      assertSame(ended, running.request())
      assertEquals(completed(), ended.terminal)

      // Presentation holds both targets of the ended output.
      val waiting = submit(requests, unit = 1)
      val next = executing(requests)

      san.await { count { it.status == SanAbi.STATUS_OK && it.isStep } == 2 }

      assertNull(waiting.terminal)
      assertEquals(2, platform.created.size)
      first.close()

      val last = presenter.take()

      assertEquals(2, last.rows)
      assertEquals(3, platform.created.size)
      assertSame(waiting, next.request())
      assertEquals(completed(), waiting.terminal)
      second.close()
      last.close()
      assertReleased()
    }

  @Test
  fun `a publication outside the requested band ends the job as stale`() =
    readerTest {
      val requests = contentOwner()
      val presenter = TestPresenter(requests)
      val band = ReaderOutputRequest.Band(ReaderImageGeometry(4, 4), 0, 2)
      val input = liveInput()

      source.supply(input)

      // The script ignores the band and covers the whole image.
      san.script(rowsOfFourBytes(width = 4, height = 4))

      val request = checkNotNull(requests.submit(book.descriptor(0), band))

      val running = executing(requests)

      input.receiveThrough(4)
      presenter.take().close()
      input.receiveThrough(8)
      presenter.take().close()

      // The third row lies outside the request, while the job still runs.
      input.receiveThrough(12)

      assertSame(request, running.request())
      assertEquals(Canceled, request.terminal)
      assertEquals(listOf(1, 2), presenter.taken.map { it.rows })
      assertNull(requests.takePublication())
      assertReleased()
    }

  @Test
  fun `a whole page at a width publishes the rows of its grid`() = readerTest {
    val requests = contentOwner()
    val presenter = TestPresenter(requests)
    val whole = ReaderOutputRequest.Whole(maxOutputWidth = 4)

    source.supply(completeInput())

    // An image of eight by six pixels in a grid of four by three.
    san.script {
      if (content.ended) {
        describe(8, 6, outputWidth = 4, outputHeight = 3)
        admit(4, 3, earlyPublication = false)
        cover(3)

        SanAbi.STATUS_OK
      } else {
        SanAbi.STATUS_INPUT_NEEDED
      }
    }

    val request = checkNotNull(requests.submit(book.descriptor(0), whole))
    val running = executing(requests)
    val output = presenter.take()

    assertEquals(listOf(ScriptedRequest(0, 0, 0, 0, 4)), san.requested)

    assertEquals(
      ReaderImageCoverage(
        image = ReaderImageGeometry(8, 6),
        grid = ReaderImageGeometry(4, 3),
        region = ReaderImageRegion(0, 0, 4, 3),
        finalQuality = true,
      ),
      output.coverage,
    )

    // The target has the size of the lease, which is the grid here.
    assertEquals(listOf(4 to 3), platform.created.map { it.width to it.height })
    output.close()

    assertSame(request, running.request())
    assertEquals(completed(), request.terminal)
    assertReleased()
  }

  @Test
  fun `an output in another grid than the requested width is stale`() =
    readerTest {
      val requests = contentOwner()
      val whole = ReaderOutputRequest.Whole(maxOutputWidth = 2)
      val input = liveInput()

      source.supply(input)

      // The script ignores the width and keeps the image at its size.
      san.script(rowsOfFourBytes(width = 4, height = 4))

      val request = checkNotNull(requests.submit(book.descriptor(0), whole))
      val running = executing(requests)

      input.receiveThrough(4)

      assertSame(request, running.request())
      assertEquals(Canceled, request.terminal)
      assertEquals(listOf(ScriptedRequest(0, 0, 0, 0, 2)), san.requested)
      assertNull(requests.takePublication())
      assertReleased()
    }

  @Test
  fun `a band requests its rows at every column and publishes them`() =
    readerTest {
      val requests = contentOwner()
      val presenter = TestPresenter(requests)
      val image = ReaderImageGeometry(4, 8)
      val band = ReaderOutputRequest.Band(image, top = 2, bottom = 6)

      source.supply(completeInput())

      // The lease holds the four rows of the band, from the grid row 2 on.
      san.script {
        if (content.ended) {
          describe(4, 8)
          admit(4, 4, top = 2, earlyPublication = false)
          decoded(6)
          cover(4)

          SanAbi.STATUS_OK
        } else {
          SanAbi.STATUS_INPUT_NEEDED
        }
      }

      val request = checkNotNull(requests.submit(book.descriptor(0), band))
      val running = executing(requests)
      val output = presenter.take()

      assertEquals(listOf(ScriptedRequest(0, 2, 4, 6, 0)), san.requested)

      assertEquals(
        ReaderImageCoverage(image, image, ReaderImageRegion(0, 2, 4, 6), true),
        output.coverage,
      )

      output.close()

      assertSame(request, running.request())
      assertEquals(completed(), request.terminal)
      assertReleased()
    }

  @Test
  fun `the worker outlives a closed owner until the last target returns`() =
    readerTest {
      val requests = contentOwner()
      val presenter = TestPresenter(requests)
      val input = liveInput(count = 4)

      source.supply(input)
      san.script(rowsOfFourBytes(width = 4, height = 1))

      val request = submit(requests)
      val running = executing(requests)

      input.receiveThrough(4)

      val held = presenter.take()

      input.end()

      assertSame(request, running.request())
      assertEquals(completed(), request.terminal)

      val worker = san.calls.first().thread

      owner.close()

      // The target that presentation never held is recycled, the other waits.
      assertFalse(san.engineLive)
      assertTrue(worker.isAlive)
      assertEquals(1, platform.created.size - platform.recycled.size)
      held.close()
      worker.join(TimeUnit.SECONDS.toMillis(BOUND_SECONDS))

      assertFalse(worker.isAlive)
      assertEquals(platform.created.toSet(), platform.recycled.toSet())
      assertEquals(emptyList<String>(), platform.violations)
    }

  /**
   * Decodes three rows of four while presentation holds the first revision and
   * leaves the second one untaken, so that the third row is copied nowhere.
   */
  private suspend fun CoroutineScope.stallAtThreeRows(
    requests: ReaderContentRequests,
    presenter: TestPresenter,
  ): Stalled {
    val input = liveInput()

    source.supply(input)
    san.script(rowsOfFourBytes(width = 4, height = 4))

    val request = submit(requests)
    val running = executing(requests)

    input.receiveThrough(4)

    val first = presenter.take()

    input.receiveThrough(12)
    san.await { steppedAt(12L) }

    assertEquals(2, copiedRows())

    return Stalled(input, request, running, first)
  }

  /**
   * Checks that the engine holds nothing of the executed requests, that no call
   * broke a rule, and that every target is recycled once the owner is closed.
   * The test has closed every lease that it took.
   */
  private suspend fun assertReleased() {
    assertEquals(emptyList<String>(), san.violations)

    assertEquals(
      emptyList<Any>(),
      san.calls.filter { it.status in CALLER_DEFECTS },
    )

    assertEquals(0, san.liveContentCount)
    assertEquals(0, san.ownedJobCount)
    assertEquals(0, san.heldLeaseCount)
    assertFalse(owner.gate.isLocked)

    // The shutdown runs behind every return that was posted before it.
    owner.close()

    assertEquals(SanAbi.STATUS_OK, san.calls.last().status)
    assertEquals(platform.created.toSet(), platform.recycled.toSet())
    assertEquals(emptyList<String>(), platform.violations)
  }

  /** Makes presentation return each target at once and records its rows. */
  private fun ReaderContentRequests.recordRows(): List<Int> {
    val rows = CopyOnWriteArrayList<Int>()

    setPresentationWakeup {
      takePublication()?.output?.let { output ->
        rows += output.rows
        output.close()
      }
    }

    return rows
  }

  private suspend fun contentOwner(
    limits: ReaderOutputLimits = ReaderOutputLimits.FIRST_ITERATION
  ): ReaderContentRequests =
    ReaderContentRequests(ReaderSessionGate().start(), limits)

  private fun submit(
    requests: ReaderContentRequests,
    unit: Int = 0,
  ): ReaderContentRequest = checkNotNull(requests.submit(book.descriptor(unit)))

  private fun CoroutineScope.executing(
    requests: ReaderContentRequests
  ): Deferred<ReaderContentRequest?> =
    async(Dispatchers.Default) { owner.execute(requests, source) }

  private suspend fun Deferred<ReaderContentRequest?>.request():
    ReaderContentRequest? = await()

  /** The highest lease row that any copy reached. */
  private fun copiedRows(): Int =
    platform.created
      .flatMap { it.copies }
      .maxOfOrNull {
        it.firstRow + it.rowCount
      } ?: 0

  /** The copies into a target, as its first row and its row count. */
  private fun ScriptedTarget.rowCopies(): List<Pair<Int, Int>> =
    copies.map { made ->
      assertEquals(made.firstRow, made.destinationFirstRow)

      made.firstRow to made.rowCount
    }

  private fun copy(firstRow: Int, rowCount: Int): Pair<Int, Int> =
    firstRow to rowCount

  private class Stalled(
    val input: ControlledReaderInput,
    val request: ReaderContentRequest,
    val running: Deferred<ReaderContentRequest?>,
    val first: ReaderOutputLease,
  )

  private companion object {
    const val BOUND_SECONDS = 5L
    val PROFILE = ReaderEngineSettings.FIRST_ITERATION

    val CALLER_DEFECTS =
      setOf(
        SanAbi.STATUS_INVALID_HANDLE,
        SanAbi.STATUS_CLOSED,
        SanAbi.STATUS_BINDING_MISMATCH,
      )
  }
}
