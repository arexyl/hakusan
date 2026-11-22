package app.hakusan.reader

import android.graphics.Bitmap
import android.graphics.ColorSpace
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.hakusan.reader.san.SanBinding
import app.hakusan.reader.san.SanCalls
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checks on a device what the JVM tests cannot: the Bitmaps of the targets, and
 * the return of a target through real frames. A fake engine writes the pixels,
 * so the tests need no native library.
 */
@RunWith(AndroidJUnit4::class)
class ReaderTargetDeviceTest {
  @Test
  fun targetsHaveThePixelProfileOfTheCopy() {
    for (opaque in listOf(true, false)) {
      val target = AndroidReaderPlatform.createTarget(7, 5, opaque)
      val bitmap = (target as ReaderBitmapTarget).bitmap

      assertTrue(bitmap.isMutable)
      assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
      assertEquals(ColorSpace.get(ColorSpace.Named.SRGB), bitmap.colorSpace)
      assertEquals(!opaque, bitmap.hasAlpha())
      assertEquals(!opaque, bitmap.isPremultiplied)
      assertEquals(7, bitmap.width)
      assertEquals(5, bitmap.height)
      assertEquals(7 * 4, bitmap.rowBytes)

      AndroidReaderPlatform.recycle(target)

      assertTrue(bitmap.isRecycled)
    }
  }

  @Test
  fun traceSectionsOpenAndClose() {
    AndroidReaderPlatform.section("reader.step") {
      AndroidReaderPlatform.section("reader.copy 1 0 1") {}
    }
  }

  @Test
  fun framesAlternateTwoTargetsThatNoCopyWritesWhileDrawn(): Unit =
    runBlocking {
      ActivityScenario.launch(TargetActivity::class.java).use { scenario ->
        lateinit var view: TargetView

        scenario.onActivity { view = it.view }

        val engine = PixelEngine(WIDTH, HEIGHT, BYTES_PER_ROW, view.referenced)
        val created = CopyOnWriteArrayList<Bitmap>()

        val platform =
          object : ReaderPlatform by AndroidReaderPlatform {
            override fun bind(): SanBinding = SanBinding.Established

            override fun calls(worker: Thread): SanCalls = engine

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

        // Sixteen portions of four rows, each after more than one frame.
        val input =
          PacedInput(ByteArray(HEIGHT * BYTES_PER_ROW), 16, 40.milliseconds)

        view.present(requests)

        val request = checkNotNull(requests.submit(pageDescriptor()))

        withTimeout(TIMEOUT_MILLIS) {
          owner.execute(requests) { ReaderInputOpenResult.Opened(input) }
        }

        assertEquals(ReaderContentTerminal.Completed.of(), request.terminal)

        // The final copy is published by now, and a frame has yet to draw it.
        withTimeout(TIMEOUT_MILLIS) { view.drawn.first { it == HEIGHT } }

        assertEquals(emptyList<String>(), engine.violations)
        assertEquals(2, created.size)
        assertEquals(created.toSet(), engine.written)
        assertTrue(view.revisions.size > 2)
        assertEquals(view.revisions.sorted(), view.revisions)

        scenario.onActivity {
          val shown = checkNotNull(view.shownBitmap)

          // Whichever target shows the last revision holds every row.
          for (row in 0..<HEIGHT) {
            assertEquals(PixelEngine.colorOf(row), shown.getPixel(0, row))

            assertEquals(
              PixelEngine.colorOf(row),
              shown.getPixel(WIDTH - 1, row),
            )
          }

          // The ended output keeps only the target that presentation holds.
          assertFalse(shown.isRecycled)
          view.clear()
        }

        // The shutdown runs behind the returns that the view just posted.
        owner.close()
        gate.close(session)

        assertTrue(created.all { it.isRecycled })
      }
    }

  private companion object {
    const val WIDTH = 32
    const val HEIGHT = 64
    const val BYTES_PER_ROW = 4
    const val TIMEOUT_MILLIS = 20_000L
  }
}
