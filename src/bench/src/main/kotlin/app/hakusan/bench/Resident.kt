package app.hakusan.bench

import java.io.File
import java.io.IOException
import org.json.JSONObject

/**
 * One read of the resident memory of this process from `/proc/self/status`, in
 * bytes.
 *
 * The harness reads it where the trace of a device holds no throttled event of
 * resident memory. The kernel counts each value in whole pages.
 */
internal data class Resident(
  /** All resident memory: anonymous, file, and shared. */
  val rss: Long,
  /** The highest [rss] since the peak was last reset. */
  val peak: Long,
  /** The anonymous part of [rss]. */
  val anon: Long,
  val swap: Long,
) {
  fun json(): JSONObject =
    JSONObject()
      .put("rss_bytes", rss)
      .put("peak_bytes", peak)
      .put("anon_bytes", anon)
      .put("swap_bytes", swap)

  companion object {
    private const val UNIT = " kB"

    /**
     * Reads the memory that is resident now.
     *
     * @throws IOException when the status cannot be read.
     * @throws IllegalArgumentException when it lacks a value.
     */
    fun read(): Resident = of(File("/proc/self/status").readText())

    /**
     * Makes the memory that is resident now the peak. It returns null, or the
     * failure when the kernel refused: a platform may deny the write, and an
     * old kernel does not know the value.
     */
    fun resetPeak(): String? =
      try {
        // The value that resets the peak and leaves the reference bits alone.
        File("/proc/self/clear_refs").writeText("5")

        null
      } catch (failure: IOException) {
        failure.toString()
      }

    /**
     * Reads the values of a [status] in the format of `/proc/<pid>/status`.
     *
     * @throws IllegalArgumentException when a value is missing or is not a
     *   count of kibibytes.
     */
    fun of(status: String): Resident {
      val fields =
        status
          .lineSequence()
          .filter { ':' in it }
          .associate {
            it.substringBefore(':') to it.substringAfter(':').trim()
          }

      fun bytes(name: String): Long {
        val value = requireNotNull(fields[name]) { "The status has no $name." }

        require(value.endsWith(UNIT)) { "$name is no count of kibibytes." }

        return value.removeSuffix(UNIT).trim().toLong() * 1024
      }

      return Resident(
        rss = bytes("VmRSS"),
        peak = bytes("VmHWM"),
        anon = bytes("RssAnon"),
        swap = bytes("VmSwap"),
      )
    }
  }
}
