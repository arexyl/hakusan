package app.hakusan.reader

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.hakusan.reader.san.SanCalls
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs content requests through the worker against the San library, with real
 * targets and real frames. The pages come from the fixture corpus, whose
 * manifest states the samples of their decoded images.
 */
@RunWith(AndroidJUnit4::class)
class ReaderEngineDeviceTest {
  @Before
  fun requireLibrary() {
    DeviceCorpus.assumeSanLibrary()
  }

  @Test
  fun readyPageDecodesTwiceToTheSamplesOfTheManifest() = onStage {
    val page = DeviceCorpus.file(ORDINARY)

    // The second request finds the engine and the pool that the first left.
    repeat(2) {
      val request = submit()

      execute(PacedInput(page.bytes))

      assertEquals(ReaderContentTerminal.Completed.of(), request.terminal)
      assertShows(page)
      assertIdle()
      clearView()

      // No frame draws the targets now. The second request gets them back
      // and must write each of their rows again.
      onMain { created.forEach { it.eraseColor(Color.MAGENTA) } }
    }

    assertEquals(2, created.size)
  }

  @Test
  fun pacedPageGrowsThroughTwoTargetsThatNoCopyWritesWhileDrawn() = onStage {
    val page = DeviceCorpus.file(ORDINARY)
    val request = submit()

    // Twenty-four portions, each after more than one frame.
    execute(PacedInput(page.bytes, page.bytes.size / 24 + 1, 40.milliseconds))

    assertEquals(ReaderContentTerminal.Completed.of(), request.terminal)
    assertShows(page)
    assertIdle()
    assertEquals(2, created.size)
    assertTrue(view.revisions.size > 2)
    assertEquals(view.revisions.sorted(), view.revisions)
  }

  @Test
  fun pageAtHalfItsWidthIsTheAverageOfItsPixels() = onStage {
    val page = DeviceCorpus.file(ORDINARY)

    submit()
    execute(PacedInput(page.bytes))

    val whole = assertShows(page)

    clearView()

    val width = page.width / 2
    val height = page.height / 2
    val request = submit(ReaderOutputRequest.Whole(maxOutputWidth = width))

    execute(PacedInput(page.bytes))

    assertEquals(ReaderContentTerminal.Completed.of(), request.terminal)

    val scaled = assertShown(width, height)

    // Each pixel of the grid covers two by two pixels of the image.
    for (y in 0..<height) {
      for (x in 0..<width) {
        val first = 2 * y * page.width + 2 * x
        val below = first + page.width

        val covered =
          intArrayOf(
            whole[first],
            whole[first + 1],
            whole[below],
            whole[below + 1],
          )

        assertEquals(
          "the pixel at $x, $y",
          average(covered),
          scaled[y * width + x],
        )
      }
    }

    assertIdle()
  }

  @Test
  fun bandOfThePageHasTheRowsOfThePage() = onStage {
    val page = DeviceCorpus.file(ORDINARY)

    submit()
    execute(PacedInput(page.bytes))

    val whole = assertShows(page)

    clearView()

    val image = ReaderImageGeometry(page.width, page.height)
    val top = page.height / 4
    val bottom = page.height / 2
    val request = submit(ReaderOutputRequest.Band(image, top, bottom))

    execute(PacedInput(page.bytes))

    assertEquals(ReaderContentTerminal.Completed.of(), request.terminal)

    val band = assertShown(page.width, bottom - top)

    assertArrayEquals(
      whole.copyOfRange(top * page.width, bottom * page.width),
      band,
    )

    assertIdle()
  }

  @Test
  fun scaledBandHasTheRowsOfTheScaledPageInOneGrid() = onStage {
    val page = DeviceCorpus.file(ORDINARY)
    val image = ReaderImageGeometry(page.width, page.height)

    // A third of the width, and edges inside a grid row, so that the grid
    // and the rows of the lease come from divisions with a remainder.
    val width = page.width / 3
    val top = page.height / 4 + 1
    val bottom = page.height / 2 + 1
    val grid = image.grid(width)
    val rows = image.gridRows(top, bottom, grid)
    val whole = submit(ReaderOutputRequest.Whole(width))

    execute(PacedInput(page.bytes))

    assertEquals(ReaderContentTerminal.Completed.of(), whole.terminal)

    val scaled = assertShown(grid.width, grid.height)

    clearView()

    val band = submit(ReaderOutputRequest.Band(image, top, bottom, width))

    execute(PacedInput(page.bytes))

    assertEquals(ReaderContentTerminal.Completed.of(), band.terminal)

    assertArrayEquals(
      scaled.copyOfRange(rows.first * grid.width, (rows.last + 1) * grid.width),
      assertShown(grid.width, rows.count()),
    )

    assertIdle()
  }

  @Test
  fun bandsOfTheSegmentStayAsStripsThatAnswerThemAgain() =
    onStage(RETAINING) {
      val segment = DeviceCorpus.file(LONG)
      val image = ReaderImageGeometry(segment.width, segment.height)
      val bytes = ReaderRepresentation()

      fun band(index: Int) =
        ReaderOutputRequest.Band(
          image,
          index * BAND_ROWS,
          (index + 1) * BAND_ROWS,
        )

      /** The hash of the samples of the band that the view shows. */
      suspend fun shown(): String =
        rgbSha256(assertShown(image.width, BAND_ROWS), image.width).also {
          clearView()
        }

      suspend fun decode(index: Int): String {
        val request = submit(band(index))

        execute(PacedInput(segment.bytes, representation = bytes))

        assertEquals(ReaderContentTerminal.Completed.of(), request.terminal)

        return shown()
      }

      suspend fun hit(index: Int): String {
        val request = submit(band(index))

        executeFromStrip()

        assertEquals(ReaderContentTerminal.Completed.of(), request.terminal)

        return shown()
      }

      // Four leases: the fourth band decodes beside three strips, and all
      // four stay while no job runs.
      val samples = List(4) { decode(it) }

      assertEquals(4, samples.toSet().size)
      assertEquals(4L, checkNotNull(owner.counters()).leaseCount)

      // The second band again, from its strip: the samples of its decode.
      assertEquals(samples[1], hit(1))

      // A fifth band takes the lease of the first one, which was used
      // longest ago, so the first band needs its input again, and takes the
      // lease of the third.
      decode(4)

      assertEquals(4L, checkNotNull(owner.counters()).leaseCount)
      assertEquals(samples[0], decode(0))
      assertEquals(samples[3], hit(3))
      assertEquals(samples[1], hit(1))
    }

  @Test
  fun longSegmentAtAWidthIsRefusedWithItsGeometry() = onStage {
    val segment = DeviceCorpus.file(LONG)
    val image = ReaderImageGeometry(segment.width, segment.height)

    // The grid of the whole segment at this width exceeds one lease.
    val request = submit(ReaderOutputRequest.Whole(maxOutputWidth = 1080))

    execute(PacedInput(segment.bytes))

    assertEquals(ReaderContentTerminal.Oversized(image), request.terminal)
    assertEquals(emptyList<Bitmap>(), created)
    assertIdle()
  }

  @Test
  fun largestBandOfTheSegmentIsAdmittedAndOneRowMoreIsNot() = onStage {
    val segment = DeviceCorpus.file(LONG)
    val image = ReaderImageGeometry(segment.width, segment.height)
    val settings = ReaderEngineSettings.FIRST_ITERATION

    // The rows whose pixels fill one lease up to the band margin.
    val rows =
      ((settings.outputLeaseBytes - settings.bandMarginBytes) /
          (image.width * 4))
        .toInt()

    val largest = submit(ReaderOutputRequest.Band(image, 0, rows))

    execute(PacedInput(segment.bytes))

    assertEquals(ReaderContentTerminal.Completed.of(), largest.terminal)
    assertShown(image.width, rows)
    assertIdle()
    clearView()

    val beyond = submit(ReaderOutputRequest.Band(image, 0, rows + 1))

    execute(PacedInput(segment.bytes))

    assertEquals(ReaderContentTerminal.Oversized(image), beyond.terminal)
    assertIdle()
  }

  @Test
  fun bandOfAnotherGeometryThanItsImageGetsNoTarget() = onStage {
    val page = DeviceCorpus.file(ORDINARY)

    // The band lies inside the page, so the engine admits its lease.
    val taller = ReaderImageGeometry(page.width, page.height + 1)
    val request = submit(ReaderOutputRequest.Band(taller, 0, 100))

    execute(PacedInput(page.bytes))

    assertEquals(
      ReaderContentTerminal.Failed(ReaderContentFailure.REPRESENTATION_CHANGED),
      request.terminal,
    )

    assertEquals(emptyList<Bitmap>(), created)
    assertIdle()
  }

  @Test
  fun closingDuringADecodeCancelsItAndFreesTheEngine() = onStage {
    val page = DeviceCorpus.file(ORDINARY)
    val request = submit()
    val input = PacedInput(page.bytes, hold = page.bytes.size / 2)

    coroutineScope {
      val execution = async { execute(input) }

      // Rows of the first half are on the screen, and the input never ends.
      withTimeout(TIMEOUT_MILLIS) { view.drawn.first { it > 0 } }

      requests.beginClose()
      execution.await()
    }

    assertEquals(ReaderContentTerminal.Canceled, request.terminal)
    assertTrue(view.drawn.value < page.height)
    assertIdle()
    clearView()

    withTimeout(TIMEOUT_MILLIS) { requests.awaitClosed() }
  }

  @Test
  fun pageThatEndsEarlyFailsAsTruncatedContentAfterItsRows() = onStage {
    val page = DeviceCorpus.file(ORDINARY)
    val prefix = DeviceCorpus.file(TRUNCATED)

    submit()
    execute(PacedInput(page.bytes))

    val whole = assertShows(page)
    val request = submit()

    execute(PacedInput(prefix.bytes))

    assertEquals(
      ReaderContentTerminal.Failed(ReaderContentFailure.TRUNCATED_CONTENT),
      request.terminal,
    )

    assertIdle()
    awaitFrame()

    // The rows that the prefix completed are those of the whole page.
    val rows = view.drawn.value
    val shown = onMain { checkNotNull(view.shownBitmap) }
    val partial = IntArray(page.width * rows)

    shown.getPixels(partial, 0, page.width, 0, 0, page.width, rows)

    assertTrue("$rows rows", rows in 1..<page.height)
    assertArrayEquals(whole.copyOf(partial.size), partial)
  }

  @Test
  fun bytesWithoutASignatureFailAsInvalidContent() = onStage {
    val request = submit()

    execute(PacedInput(ByteArray(4096)))

    assertEquals(
      ReaderContentTerminal.Failed(ReaderContentFailure.INVALID_CONTENT),
      request.terminal,
    )

    assertIdle()
    assertEquals(emptyList<Bitmap>(), created)
    assertEquals(emptyList<Int>(), view.revisions)
  }

  /** One owner with its requests, presented by the view of a test Activity. */
  private class Stage(
    private val scenario: ActivityScenario<TargetActivity>,
    val view: TargetView,
    val owner: ReaderResourceOwner,
    val requests: ReaderContentRequests,
    val created: List<Bitmap>,
  ) {
    fun submit(
      output: ReaderOutputRequest = ReaderOutputRequest.Whole()
    ): ReaderContentRequest =
      checkNotNull(requests.submit(pageDescriptor(), output))

    suspend fun execute(input: ReaderByteInput) {
      withTimeout(TIMEOUT_MILLIS) {
        owner.execute(requests) { ReaderInputOpenResult.Opened(input) }
      }
    }

    /** Executes a request that a strip answers, which opens no input. */
    suspend fun executeFromStrip() {
      withTimeout(TIMEOUT_MILLIS) {
        owner.execute(requests) { error("A strip needs no input.") }
      }
    }

    /**
     * Waits for the frame of the whole [page], checks its samples against the
     * manifest, and returns its pixels.
     */
    suspend fun assertShows(page: CorpusFile): IntArray {
      withTimeout(TIMEOUT_MILLIS) { view.drawn.first { it == page.height } }

      val shown = onMain { checkNotNull(view.shownBitmap) }
      val pixels = shown.pixels()

      assertEquals(page.width, shown.width)
      assertEquals(page.height, shown.height)
      assertEquals(page.samplesSha256, rgbSha256(pixels, shown.width))

      return pixels
    }

    /**
     * Waits for the frame that draws [rows] rows, checks that its Bitmap has
     * [width] columns and exactly those rows, and returns its pixels.
     */
    suspend fun assertShown(width: Int, rows: Int): IntArray {
      withTimeout(TIMEOUT_MILLIS) { view.drawn.first { it == rows } }

      val shown = onMain { checkNotNull(view.shownBitmap) }

      assertEquals(width, shown.width)
      assertEquals(rows, shown.height)

      return shown.pixels()
    }

    /**
     * Waits until a frame has drawn every publication that was queued at the
     * call: its wakeup has posted the take already, which runs before the
     * callback of this function in the same frame, and the message behind that
     * callback runs after the traversal.
     */
    suspend fun awaitFrame() {
      val drawn = CompletableDeferred<Unit>()

      view.postOnAnimation { view.post { drawn.complete(Unit) } }
      withTimeout(TIMEOUT_MILLIS) { drawn.await() }
    }

    /** Checks that the engine holds nothing of a request. */
    suspend fun assertIdle() {
      val counters = checkNotNull(owner.counters())

      assertEquals(0L, counters.contentCount)
      assertEquals(0L, counters.jobCount)
      assertEquals(0L, counters.leaseCount)
      assertEquals(0L, counters.inputBytes)
      assertEquals(0L, counters.outputBytes)

      // What stays is the engine's own bookkeeping and the block that it
      // keeps for its next lease, which no handle reaches.
      assertEquals(
        counters.metadataBytes + counters.keptBytes,
        counters.liveBytes,
      )
    }

    /** Releases every lease of the view and waits for the frame without one. */
    suspend fun clearView() {
      onMain { view.clear() }
      withTimeout(TIMEOUT_MILLIS) { view.drawn.first { it == 0 } }
    }

    /** Runs [block] on the main thread and fails on the calling one. */
    fun <T> onMain(block: () -> T): T {
      var result: Result<T>? = null

      scenario.onActivity { result = runCatching(block) }

      return checkNotNull(result).getOrThrow()
    }
  }

  private fun onStage(
    settings: ReaderEngineSettings = ReaderEngineSettings.FIRST_ITERATION,
    block: suspend Stage.() -> Unit,
  ): Unit = runBlocking {
    ActivityScenario.launch(TargetActivity::class.java).use { scenario ->
      lateinit var view: TargetView

      scenario.onActivity { view = it.view }

      val created = CopyOnWriteArrayList<Bitmap>()
      val violations = CopyOnWriteArrayList<String>()

      val platform =
        object : ReaderPlatform by AndroidReaderPlatform {
          override fun calls(worker: Thread): SanCalls =
            WatchedCalls(
              AndroidReaderPlatform.calls(worker),
              view.referenced,
              violations,
            )

          override fun createTarget(
            width: Int,
            height: Int,
            opaque: Boolean,
          ): ReaderTarget {
            val target =
              AndroidReaderPlatform.createTarget(width, height, opaque)

            created += (target as ReaderBitmapTarget).bitmap

            return target
          }
        }

      val owner = ReaderResourceOwner(settings, platform)

      val gate = ReaderSessionGate()
      val session = gate.start()
      val limits = ReaderOutputLimits.FIRST_ITERATION
      val requests = ReaderContentRequests(session, limits)

      view.present(requests)

      try {
        Stage(scenario, view, owner, requests, created).block()

        assertEquals(emptyList<String>(), violations)
      } finally {
        // A later test creates the one engine of the process again, so this
        // one shuts down also after a failure. The close releases what no
        // frame took yet, so the view then holds every lease that is left.
        requests.beginClose()
        scenario.onActivity { view.clear() }
        owner.close()
        gate.close(session)
      }

      withTimeout(TIMEOUT_MILLIS) { requests.awaitClosed() }
      assertTrue(created.all { it.isRecycled })
    }
  }

  /**
   * The calls of the library, with a record of every copy into a target that
   * [referenced] names, which a recorded frame may still draw.
   */
  private class WatchedCalls(
    private val san: SanCalls,
    private val referenced: Set<Bitmap>,
    private val violations: MutableList<String>,
  ) : SanCalls by san {
    override fun outputCopy(
      lease: Long,
      firstRow: Int,
      rowCount: Int,
      destination: ReaderTarget,
      destinationFirstRow: Int,
    ): Int {
      if ((destination as ReaderBitmapTarget).bitmap in referenced) {
        violations +=
          "Rows $firstRow to ${firstRow + rowCount} wrote a target" +
            " that a frame could draw."
      }

      return san.outputCopy(
        lease,
        firstRow,
        rowCount,
        destination,
        destinationFirstRow,
      )
    }
  }

  /**
   * The opaque pixel whose every sample is the mean of that sample over
   * [pixels], rounded to the nearest value, halves up.
   */
  private fun average(pixels: IntArray): Int {
    var result = 0xFF shl 24

    for (shift in intArrayOf(16, 8, 0)) {
      val sum = pixels.sumOf { (it ushr shift) and 0xFF }

      result = result or ((sum + pixels.size / 2) / pixels.size shl shift)
    }

    return result
  }

  private companion object {
    const val ORDINARY = "journey/ordinary.png"
    const val LONG = "journey/long.png"

    /** The rows of one band of the long segment: one viewport at 1:1. */
    const val BAND_ROWS = 2560

    val RETAINING =
      ReaderEngineSettings.FIRST_ITERATION.copy(
        backwardVariant = ReaderBackwardVariant.RETAIN_STRIPS
      )
    const val TRUNCATED = "qualification/fault/png-truncated-prefix.png"
    const val TIMEOUT_MILLIS = 30_000L
  }
}
