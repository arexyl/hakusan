package app.hakusan.reader

import android.graphics.Bitmap
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
    }
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
    fun submit(): ReaderContentRequest =
      checkNotNull(requests.submit(pageDescriptor()))

    suspend fun execute(input: ReaderByteInput) {
      withTimeout(TIMEOUT_MILLIS) {
        owner.execute(requests) { ReaderInputOpenResult.Opened(input) }
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

  private fun onStage(block: suspend Stage.() -> Unit): Unit = runBlocking {
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

      val owner =
        ReaderResourceOwner(ReaderEngineSettings.FIRST_ITERATION, platform)

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

  private companion object {
    const val ORDINARY = "journey/ordinary.png"
    const val TRUNCATED = "qualification/fault/png-truncated-prefix.png"
    const val TIMEOUT_MILLIS = 30_000L
  }
}
