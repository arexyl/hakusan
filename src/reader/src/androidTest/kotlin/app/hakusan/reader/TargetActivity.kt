package app.hakusan.reader

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The Activity whose window draws the targets of a test. */
class TargetActivity : Activity() {
  internal lateinit var view: TargetView
    private set

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    view = TargetView(this)
    setContentView(view)
  }
}

/**
 * Presentation as the adapter expects it, with real frames.
 *
 * It takes at most one publication per frame and draws the covered rows of its
 * target. It returns a target by closing its lease in a message behind the
 * first drawing traversal that left the target out: the main thread leaves a
 * traversal only once the render thread has synced that frame, so no recorded
 * frame draws the target any longer.
 *
 * Everything runs on the main thread, except [present] and the reads of
 * [referenced].
 */
internal class TargetView(context: Context) : View(context) {
  private val superseded = mutableListOf<ReaderOutputLease>()

  /**
   * The leases that a posted message releases, behind the draw without them.
   */
  private val returning = mutableListOf<ReaderOutputLease>()

  private val drawnRows = MutableStateFlow(0)
  private var shown: ReaderOutputLease? = null

  /** The targets that a recorded frame may still draw. */
  val referenced: MutableSet<Bitmap> = ConcurrentHashMap.newKeySet()

  /** The revision of every lease taken, in order. */
  val revisions: MutableList<Int> = CopyOnWriteArrayList()

  /** The covered rows that the last draw showed. */
  val drawn: StateFlow<Int>
    get() = drawnRows

  /** The target that the next draw shows. */
  val shownBitmap: Bitmap?
    get() = shown?.bitmap

  /** Takes the publications of [requests], one per frame, from now on. */
  fun present(requests: ReaderContentRequests) {
    requests.setPresentationWakeup {
      postOnAnimation { requests.takePublication()?.let { show(it.output) } }
    }
  }

  /**
   * Releases every lease without waiting for a frame, at the end of a test. The
   * renderer keeps the pixels of its last frame alive until the next one.
   */
  fun clear() {
    val held = returning + superseded + listOfNotNull(shown)

    returning.clear()
    superseded.clear()
    shown = null
    held.forEach(::release)
    invalidate()
  }

  override fun onDraw(canvas: Canvas) {
    val lease = shown
    val rows = lease?.let { it.coverage.region.bottom - it.coverage.region.top }

    if (lease != null && rows != null) {
      val covered = Rect(0, 0, lease.bitmap.width, rows)

      canvas.drawBitmap(lease.bitmap, covered, covered, null)
    }

    // The message finds nothing when [clear] ran before it.
    if (superseded.isNotEmpty()) {
      returning += superseded
      superseded.clear()
      post(::releaseReturning)
    }

    drawnRows.value = rows ?: 0
  }

  private fun releaseReturning() {
    val absent = returning.toList()

    returning.clear()
    absent.forEach(::release)
  }

  private fun show(lease: ReaderOutputLease) {
    shown?.let(superseded::add)
    shown = lease
    referenced += lease.bitmap
    revisions += lease.revision
    invalidate()
  }

  private fun release(lease: ReaderOutputLease) {
    referenced -= lease.bitmap
    lease.close()
  }
}
