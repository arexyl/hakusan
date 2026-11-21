package app.hakusan.bench

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/** What one frame of the viewport shows for a request. */
internal sealed interface Frame {
  /** The number of the request within its block, which names no content. */
  val request: Int
}

/** A frame without any output, which lets the renderer drop the last one. */
internal data class Blank(override val request: Int) : Frame

/**
 * One decoded output that a backend hands to presentation.
 *
 * The backend never changes [bitmap] while presentation holds the output.
 * Presentation only draws it, and it runs [release] once, on the main thread,
 * when no recorded frame draws it any longer.
 */
internal class Publication(
  val bitmap: Bitmap,
  override val request: Int,
  /** Increases with each publication of one output. */
  val revision: Int,
  /** The valid rows of [bitmap], from its top. */
  val rows: Int,
  val release: () -> Unit,
) : Frame {
  val image: ImageBitmap = bitmap.asImageBitmap()

  init {
    require(rows in 1..bitmap.height) { "The rows must lie in the Bitmap." }
  }
}

/**
 * The one display consumer of the harness, behind every backend.
 *
 * It holds at most one queued frame. A queued frame requests a display frame,
 * and each display frame promotes at most one, so every promoted revision is
 * drawn in a display frame of its own.
 *
 * Everything but [queue] runs on the main thread.
 */
internal class DisplayConsumer {
  private val main = Handler(Looper.getMainLooper())
  private val queued = AtomicReference<Frame?>(null)
  private val wake = Channel<Unit>(Channel.CONFLATED)
  private val superseded = mutableListOf<Publication>()
  private val lastDrawn = MutableStateFlow<Frame?>(null)
  private val unreleased = MutableStateFlow(0)

  /** The frame that the next draw shows. */
  var shown: Frame by mutableStateOf(Blank(request = 0))
    private set

  /** The frame of the last draw, which is not yet its presentation. */
  val drawn: StateFlow<Frame?>
    get() = lastDrawn

  /** The outputs that presentation took and has not released yet. */
  val held: StateFlow<Int>
    get() = unreleased

  /**
   * Queues [frame] for the next display frame, from any thread.
   *
   * @throws IllegalStateException when a frame is already queued.
   */
  fun queue(frame: Frame) {
    check(queued.compareAndSet(null, frame)) { "A frame is already queued." }

    wake.trySend(Unit)
  }

  /** Promotes queued frames, one per display frame, until cancelled. */
  suspend fun present(): Nothing {
    while (true) {
      wake.receive()
      withFrameNanos { promote() }
    }
  }

  /**
   * Shows [blank] behind every frame queued before it, and returns once its
   * draw has run and every output has been released.
   *
   * It needs display frames, so a caller bounds the wait and falls back to
   * [abandon].
   */
  suspend fun clear(blank: Blank) {
    // The next display frame promotes a frame that is still queued.
    while (!queued.compareAndSet(null, blank)) {
      withFrameNanos {}
    }

    wake.trySend(Unit)
    lastDrawn.first { it == blank }
    unreleased.first { it == 0 }
  }

  /**
   * Releases every output that presentation still holds, without a display
   * frame, and shows nothing from then on.
   *
   * Only a block that ends without display frames calls it: an output is
   * released while the renderer may still hold its pixels, which keeps them
   * alive until its next frame.
   */
  fun abandon() {
    val pending = queued.getAndSet(null)
    val outputs = superseded + listOfNotNull(shown as? Publication)

    superseded.clear()
    shown = Blank(request = 0)
    release(outputs)

    // A frame that was never promoted was never counted as held.
    if (pending is Publication) {
      pending.release()
    }
  }

  /**
   * Records that the draw of this display frame showed [frame].
   *
   * The draw runs inside a drawing traversal, and the main thread leaves that
   * traversal only after the render thread has synchronized the frame. The
   * outputs that this draw left out are therefore released in a message behind
   * the traversal, which is the first point where the renderer holds none of
   * them.
   */
  fun onDrawn(frame: Frame) {
    val absent = superseded.toList()

    superseded.clear()
    lastDrawn.value = frame

    if (absent.isNotEmpty()) {
      main.post { release(absent) }
    }
  }

  private fun promote() {
    val next = queued.getAndSet(null) ?: return
    val previous = shown

    if (previous is Publication) {
      superseded += previous
    }

    if (next is Publication) {
      unreleased.value += 1
    }

    shown = next
  }

  private fun release(outputs: List<Publication>) {
    for (output in outputs) {
      Marks.instant(Marks.release(output))
      output.release()
    }

    unreleased.value -= outputs.size
  }
}
