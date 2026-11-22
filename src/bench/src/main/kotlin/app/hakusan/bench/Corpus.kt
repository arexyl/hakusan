package app.hakusan.bench

import android.content.res.AssetManager
import androidx.compose.ui.unit.IntSize
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/**
 * The frozen hold point of a fixture: the bytes before [offset] decode the top
 * [rows] of the image, and every later row needs a byte behind them.
 */
internal data class Hold(val offset: Long, val rows: Int)

/** One fixture as a local file whose bytes the manifest confirmed. */
internal class Fixture(
  /** The path of the fixture in the corpus, which the record names. */
  val path: String,
  val file: File,
  /** The dimensions of the encoded image. */
  val image: IntSize,
  /** The hold point, when the manifest freezes one for the fixture. */
  val hold: Hold?,
)

/**
 * The packaged part of the fixture corpus: its manifest and the fixtures that
 * the harness decodes.
 *
 * The manifest is the one source of the viewport and of every fixture's size,
 * dimensions, and hash, so the harness holds no copy of them.
 */
internal class Corpus
private constructor(
  private val assets: AssetManager,
  private val manifest: JSONObject,
  private val directory: File,
) {
  /** The viewport of the reference workload, in physical pixels. */
  val viewport: IntSize
    get() {
      val viewport = manifest.getJSONObject("display").getJSONArray("viewport")

      return IntSize(viewport.getInt(0), viewport.getInt(1))
    }

  /**
   * The rows of the viewport, from its top and at its full width, that the
   * first useful output of an image fills.
   */
  val usefulRows: Int
    get() {
      val region =
        manifest.getJSONObject("display").getJSONArray("first_useful_region")

      val edges = List(region.length(), region::getInt)

      check(edges.take(3) == listOf(0, 0, viewport.width)) {
        "The first useful region is not the top of the viewport."
      }

      return edges[3]
    }

  /**
   * Returns the fixture at [path] as a file under the corpus directory.
   *
   * It copies the packaged bytes when the file is missing or differs from the
   * manifest, and reads the whole file to check it. Call it outside every timed
   * interval, off the main thread.
   *
   * @throws IllegalStateException when the manifest lacks [path], or when the
   *   packaged bytes differ from the manifest.
   */
  fun fixture(path: String): Fixture {
    val artifact = artifact(path)
    val file = File(directory, path)

    if (!matches(file, artifact)) {
      checkNotNull(file.parentFile).mkdirs()

      assets.open(path).use { packaged ->
        file.outputStream().use { local ->
          packaged.copyTo(local)

          // The write-back of the copy must not share the storage with the
          // first attempts.
          local.fd.sync()
        }
      }

      check(matches(file, artifact)) {
        "The packaged fixture $path differs from the manifest."
      }
    }

    return Fixture(
      path = path,
      file = file,
      image = IntSize(artifact.getInt("width"), artifact.getInt("height")),
      hold = hold(file, artifact),
    )
  }

  /**
   * Reads the hold point of [artifact], or returns null when it has none.
   *
   * @throws IllegalStateException when the bytes of [file] before the hold
   *   point differ from the manifest.
   */
  private fun hold(file: File, artifact: JSONObject): Hold? {
    val stated = artifact.optJSONObject("hold") ?: return null
    val hold = Hold(stated.getLong("offset"), stated.getInt("source_rows"))

    // A hold at an end of the file or of the image would withhold nothing,
    // or everything.
    check(
      hold.offset in 1..<artifact.getLong("bytes") &&
        hold.rows in 1..<artifact.getInt("height")
    ) {
      "The hold point of ${artifact.getString("path")} lies outside it."
    }

    check(sha256(file, hold.offset) == stated.getString("prefix_sha256")) {
      "The prefix of ${artifact.getString("path")} differs from the manifest."
    }

    return hold
  }

  private fun artifact(path: String): JSONObject {
    val artifacts = manifest.getJSONArray("artifacts")

    for (index in 0..<artifacts.length()) {
      val artifact = artifacts.getJSONObject(index)

      if (artifact.getString("path") == path) {
        return artifact
      }
    }

    error("The manifest names no fixture $path.")
  }

  private fun matches(file: File, artifact: JSONObject): Boolean =
    file.isFile &&
      file.length() == artifact.getLong("bytes") &&
      sha256(file) == artifact.getString("sha256")

  /** Hashes the first [limit] bytes of [file], or all that it has of them. */
  private fun sha256(file: File, limit: Long = Long.MAX_VALUE): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(1 shl 16)
    var left = limit

    file.inputStream().use { input ->
      while (left > 0) {
        val wanted = minOf(left, buffer.size.toLong()).toInt()
        val read = input.read(buffer, 0, wanted)

        if (read < 0) break

        digest.update(buffer, 0, read)
        left -= read
      }
    }

    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  companion object {
    private const val MANIFEST = "manifest.json"
    private const val SCHEMA_VERSION = 1

    /**
     * Reads the packaged manifest. Fixtures are copied below [directory].
     *
     * @throws IllegalStateException when the manifest has another schema.
     */
    fun open(assets: AssetManager, directory: File): Corpus {
      val text = assets.open(MANIFEST).use { it.readBytes().decodeToString() }
      val manifest = JSONObject(text)

      check(manifest.getInt("schema_version") == SCHEMA_VERSION) {
        "The packaged manifest has an unknown schema."
      }

      return Corpus(assets, manifest, directory)
    }
  }
}
