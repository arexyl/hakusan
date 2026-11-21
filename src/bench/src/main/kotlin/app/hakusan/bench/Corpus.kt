package app.hakusan.bench

import android.content.res.AssetManager
import androidx.compose.ui.unit.IntSize
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/** One fixture as a local file whose bytes the manifest confirmed. */
internal class Fixture(
  /** The path of the fixture in the corpus, which the record names. */
  val path: String,
  val file: File,
  /** The dimensions of the encoded image. */
  val image: IntSize,
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
    )
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

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(1 shl 16)

    file.inputStream().use { input ->
      while (true) {
        val read = input.read(buffer)

        if (read < 0) break

        digest.update(buffer, 0, read)
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
