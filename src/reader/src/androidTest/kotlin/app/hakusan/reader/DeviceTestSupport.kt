package app.hakusan.reader

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import kotlin.time.Duration
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue

/**
 * The files of the fixture corpus that the test package holds as assets, with
 * the manifest that states their hashes.
 */
internal object DeviceCorpus {
  private val assets
    get() = InstrumentationRegistry.getInstrumentation().context.assets

  private val artifacts: Map<String, JSONObject> by lazy {
    val manifest =
      assets.open("manifest.json").bufferedReader().use {
        JSONObject(it.readText())
      }

    val artifacts = manifest.getJSONArray("artifacts")

    (0..<artifacts.length()).map(artifacts::getJSONObject).associateBy {
      it.getString("path")
    }
  }

  /**
   * Whether the build packaged the San library. A build that packaged it states
   * so, and a failed load then fails its test.
   */
  val sanLibraryPackaged: Boolean
    get() =
      InstrumentationRegistry.getArguments()
        .getString("app.hakusan.reader.san") != null

  /** Skips the calling test unless the build packaged the San library. */
  fun assumeSanLibrary() {
    assumeTrue("The build packaged no San library.", sanLibraryPackaged)
  }

  /** Returns the file at [path] once its bytes have the stated hash. */
  fun file(path: String): CorpusFile {
    val stated = artifacts.getValue(path)
    val bytes = assets.open(path).use { it.readBytes() }
    val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)

    assertEquals(stated.getString("sha256"), hex(sha256))

    return CorpusFile(bytes, stated)
  }
}

/** A file of the corpus with what the manifest states about it. */
internal class CorpusFile(
  val bytes: ByteArray,
  private val stated: JSONObject,
) {
  val width: Int
    get() = stated.getInt("width")

  val height: Int
    get() = stated.getInt("height")

  /** The SHA-256 of the decoded image as rows of RGB samples. */
  val samplesSha256: String
    get() = stated.getJSONObject("master").getString("rgb_sha256")

  /**
   * The part of each panel of the page that its texture fills alone: below the
   * label and as far inside the panel as the label lies, which keeps the border
   * of the panel and the label at a distance of several pixels.
   */
  val regions: List<CorpusRegion>
    get() {
      val panels = stated.getJSONObject("master").getJSONArray("regions")

      return (0..<panels.length()).map(panels::getJSONObject).map { panel ->
        val label = panel.getJSONArray("label_box")
        val left = panel.getInt("x")
        val top = panel.getInt("y")
        val inset = label.getInt(0) - left

        CorpusRegion(
          texture = panel.getString("texture"),
          left = left + inset,
          top = label.getInt(1) + label.getInt(3) + inset,
          right = left + panel.getInt("width") - inset,
          bottom = top + panel.getInt("height") - inset,
        )
      }
    }
}

/**
 * An area of one texture of a page in pixels of its image, the far edges
 * exclusive.
 */
internal data class CorpusRegion(
  val texture: String,
  val left: Int,
  val top: Int,
  val right: Int,
  val bottom: Int,
)

internal fun hex(bytes: ByteArray): String =
  bytes.joinToString("") { "%02x".format(it) }

internal fun Bitmap.pixels(): IntArray =
  IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

/**
 * Returns the SHA-256 of [pixels], rows of [width] opaque pixels, as rows of
 * RGB samples. A pixel that is not opaque fails the test.
 */
internal fun rgbSha256(pixels: IntArray, width: Int): String {
  val digest = MessageDigest.getInstance("SHA-256")
  val row = ByteArray(width * 3)

  for (first in pixels.indices step width) {
    for (x in 0..<width) {
      val pixel = pixels[first + x]

      assertEquals("alpha at $x, ${first / width}", 0xFF, pixel ushr 24)

      row[x * 3] = (pixel shr 16).toByte()
      row[x * 3 + 1] = (pixel shr 8).toByte()
      row[x * 3 + 2] = pixel.toByte()
    }

    digest.update(row)
  }

  return hex(digest.digest())
}

/** The one page of a title with one chapter. */
internal fun pageDescriptor(): ReaderContentDescriptor {
  val title = ReaderTitleKey("source", "title")
  val chapter = ReaderChapter(ReaderChapterKey(title, "chapter"), "1")
  val page = ReaderContentUnit(ReaderUnitKind.PAGE, "page")
  val content = ReaderChapterContent.of(chapter, listOf(page))

  return ReaderContentDescriptor.of(content, content.positionAt(0))
}

/**
 * An input of [bytes] that delivers at most [portion] bytes per read, each
 * after [pause], so that frames fall between the reads.
 *
 * With [hold], it is a live input of unknown length that delivers that many
 * bytes and then waits for more without end.
 */
internal class PacedInput(
  private val bytes: ByteArray,
  private val portion: Int = Int.MAX_VALUE,
  private val pause: Duration = Duration.ZERO,
  private val hold: Int? = null,
  override val representation: ReaderRepresentation = ReaderRepresentation(),
) : ReaderByteInput {
  private val end = hold ?: bytes.size

  override val capabilities =
    ReaderInputCapabilities(
      length = bytes.size.toLong().takeIf { hold == null },
      rewind = false,
      seek = false,
    )

  @Volatile
  override var position = 0L
    private set

  override suspend fun read(
    destination: ByteArray,
    offset: Int,
    length: Int,
  ): ReaderInputRead {
    val first = position.toInt()
    val left = end - first

    if (length == 0) {
      return ReaderInputRead.NoOp
    }

    if (left == 0) {
      return if (hold == null) {
        ReaderInputRead.EndOfInput
      } else {
        ReaderInputRead.AwaitInput
      }
    }

    delay(pause)

    val count = minOf(length, portion, left)

    bytes.copyInto(destination, offset, first, first + count)
    position += count

    return ReaderInputRead.Data(count)
  }

  override suspend fun awaitInput() {
    if (hold != null && position.toInt() == end) {
      awaitCancellation()
    }
  }

  override suspend fun seek(position: Long): ReaderInputSeek =
    ReaderInputSeek.Unsupported

  override fun close() {}
}
