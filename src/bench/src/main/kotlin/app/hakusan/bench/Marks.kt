package app.hakusan.bench

import android.os.Trace

/**
 * The trace sections of the harness, which the analysis of a system trace reads
 * by name.
 *
 * A name carries only the number of a request within its block, a revision, and
 * a row count, so a trace names no content.
 */
internal object Marks {
  /** The controller received the request. Display timing starts here. */
  fun request(request: Int): String = "bench.request $request"

  /** The decode of a request, on the decode thread. */
  fun decode(request: Int): String = "bench.decode $request"

  /** The held input of a request goes on, after its useful output. */
  fun resume(request: Int): String = "bench.resume $request"

  /** The backend requests the cancellation of a request. */
  fun cancel(request: Int): String = "bench.cancel $request"

  /** The execution of a cancelled request has returned. */
  fun returned(request: Int): String = "bench.returned $request"

  /** The backend of the attempt that ends with [request] has closed. */
  fun close(request: Int): String = "bench.close $request"

  /** The settle interval after [close] has ended. */
  fun settled(request: Int): String = "bench.settled $request"

  /** The draw of [frame], inside the display frame that first shows it. */
  fun draw(frame: Frame): String =
    when (frame) {
      is Blank -> "bench.blank ${frame.request}"
      is Publication ->
        "bench.present ${frame.request} ${frame.revision} ${frame.rows}"
    }

  /** Presentation returned [output] after a frame without it synchronized. */
  fun release(output: Publication): String =
    "bench.release ${output.request} ${output.revision}"

  /** Runs [block] inside the section [name] of the calling thread. */
  inline fun <T> section(name: String, block: () -> T): T {
    try {
      Trace.beginSection(name)

      return block()
    } finally {
      Trace.endSection()
    }
  }

  /** Records [name] as a section without duration, at this instant. */
  fun instant(name: String) {
    section(name) {}
  }
}
