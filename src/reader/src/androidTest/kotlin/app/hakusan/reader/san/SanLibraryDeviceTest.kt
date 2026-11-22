package app.hakusan.reader.san

import android.graphics.Bitmap
import android.graphics.ColorSpace
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.hakusan.reader.DeviceCorpus
import app.hakusan.reader.pixels
import app.hakusan.reader.rgbSha256
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the San library through the raw declarations: the load, the version
 * check, the registration, and the copy of a decoded lease into real Bitmaps
 * with every rejection of the copy.
 *
 * The class decodes the ordinary page of the corpus once and holds its lease
 * for every test. A rejection before the lock leaves the generation of its
 * Bitmap alone, and one inside the lock changes it, because the unlock marks
 * the Bitmap changed. Every rejection leaves the pixels alone.
 */
@RunWith(AndroidJUnit4::class)
class SanLibraryDeviceTest {
  @Before
  fun requireLibrary() {
    DeviceCorpus.assumeSanLibrary()
  }

  @Test
  fun bindingRefusesAnotherVersionAndStaysEstablished() {
    for (version in listOf(SanAbi.ABI_VERSION + 1, SanAbi.ABI_VERSION - 1)) {
      assertEquals(
        SanAbi.STATUS_BINDING_MISMATCH,
        SanAbi.bindingEstablish(version),
      )
    }

    assertEquals(SanBinding.Established, SanLibrary.bind())

    // The engine of the class still answers.
    val counters = LongArray(SanAbi.ENGINE_COUNTERS_LENGTH)

    expectOk(SanAbi.engineCounters(engine, false, counters))
    assertEquals(1L, counters[SanAbi.ENGINE_COUNTERS_LEASE_COUNT])
  }

  @Test
  fun wholeLeaseCopiesToTheSamplesOfTheManifest() {
    assertEquals(samplesSha256, rgbSha256(image, imageWidth))
  }

  @Test
  fun copyWritesItsRowsAndNoOther() {
    val band = destination(rows = 8)
    val generation = band.generationId

    expectOk(SanAbi.outputCopy(lease, 100, 3, band, 2))
    assertTrue(band.generationId != generation)

    val written = band.pixels()
    val expected = IntArray(imageWidth * 8) { SENTINEL }

    image.copyInto(
      expected,
      destinationOffset = 2 * imageWidth,
      startIndex = 100 * imageWidth,
      endIndex = 103 * imageWidth,
    )

    assertArrayEquals(expected, written)
  }

  @Test
  fun opaqueBitmapAdmitsALeaseWithoutAlpha() {
    val opaque = destination(rows = 4).apply { setHasAlpha(false) }

    expectOk(SanAbi.outputCopy(lease, 600, 2, opaque, 1))

    val expected = IntArray(imageWidth * 4) { SENTINEL }

    image.copyInto(
      expected,
      destinationOffset = imageWidth,
      startIndex = 600 * imageWidth,
      endIndex = 602 * imageWidth,
    )

    assertArrayEquals(expected, opaque.pixels())
  }

  @Test
  fun bitmapOutsideTheProfileFailsBeforeTheLock() {
    val outside =
      mapOf(
        "RGB_565" to destination(config = Bitmap.Config.RGB_565),
        "ALPHA_8" to destination(config = Bitmap.Config.ALPHA_8),
        "RGBA_F16" to destination(config = Bitmap.Config.RGBA_F16),
        "RGBA_1010102" to destination(config = Bitmap.Config.RGBA_1010102),
        "unpremultiplied" to destination().apply { isPremultiplied = false },
        "Display P3" to
          Bitmap.createBitmap(
              imageWidth,
              4,
              Bitmap.Config.ARGB_8888,
              true,
              ColorSpace.get(ColorSpace.Named.DISPLAY_P3),
            )
            .apply { eraseColor(SENTINEL) },
      )

    for ((name, bitmap) in outside) {
      // An invalid handle would fail the same copy inside the lock.
      for (handle in listOf(lease, SanAbi.NO_HANDLE)) {
        val changed =
          rejected(bitmap, SanAbi.STATUS_BINDING_MISMATCH, handle = handle)

        assertFalse("$name changed its generation", changed)
      }
    }
  }

  @Test
  fun recycledBitmapFailsBeforeTheLock() {
    val recycled = destination().apply { recycle() }

    for (handle in listOf(lease, SanAbi.NO_HANDLE)) {
      assertEquals(
        SanAbi.STATUS_BINDING_MISMATCH,
        SanAbi.outputCopy(handle, 0, 1, recycled, 0),
      )
    }
  }

  @Test
  fun hardwareBitmapFailsBeforeTheLock() {
    val hardware =
      try {
        destination().copy(Bitmap.Config.HARDWARE, false)
      } catch (_: RuntimeException) {
        null
      }

    assumeTrue("The device allocates no hardware Bitmap", hardware != null)

    checkNotNull(hardware)

    val generation = hardware.generationId

    for (handle in listOf(lease, SanAbi.NO_HANDLE)) {
      assertEquals(
        SanAbi.STATUS_BINDING_MISMATCH,
        SanAbi.outputCopy(handle, 0, 1, hardware, 0),
      )
    }

    assertEquals(generation, hardware.generationId)
  }

  @Test
  fun nullBitmapFailsBeforeTheHandle() {
    // The declaration takes no null, so the call goes through reflection.
    val copy =
      SanAbi::class
        .java
        .getMethod(
          "outputCopy",
          Long::class.javaPrimitiveType,
          Int::class.javaPrimitiveType,
          Int::class.javaPrimitiveType,
          Bitmap::class.java,
          Int::class.javaPrimitiveType,
        )

    for (handle in listOf(lease, SanAbi.NO_HANDLE)) {
      assertEquals(
        SanAbi.STATUS_BINDING_MISMATCH,
        copy.invoke(null, handle, 0, 1, null, 0),
      )
    }
  }

  @Test
  fun rejectionInsideTheLockKeepsThePixelsAndMarksTheBitmapChanged() {
    val mismatch = SanAbi.STATUS_BINDING_MISMATCH

    // A width other than the width of the lease.
    for (columns in listOf(imageWidth + 1, imageWidth - 1)) {
      assertTrue(rejected(destination(columns = columns), mismatch))
    }

    val bitmap = destination()

    // No row, and rows outside the coverage.
    assertTrue(rejected(bitmap, mismatch, rowCount = 0))
    assertTrue(rejected(bitmap, mismatch, rowCount = -1))
    assertTrue(rejected(bitmap, mismatch, firstRow = -1))
    assertTrue(rejected(bitmap, mismatch, firstRow = imageHeight))
    assertTrue(
      rejected(bitmap, mismatch, firstRow = imageHeight - 1, rowCount = 2)
    )

    // Rows outside the Bitmap.
    assertTrue(rejected(bitmap, mismatch, destinationFirstRow = -1))
    assertTrue(rejected(bitmap, mismatch, destinationFirstRow = 4))
    assertTrue(
      rejected(bitmap, mismatch, rowCount = 2, destinationFirstRow = 3)
    )
  }

  @Test
  fun staleAndReleasedLeasesFailInsideTheLock() {
    val invalid = SanAbi.STATUS_INVALID_HANDLE
    val bitmap = destination()

    for (handle in listOf(SanAbi.NO_HANDLE, STALE, lease + 1)) {
      assertTrue(rejected(bitmap, invalid, handle = handle))
    }

    val released = decode()

    expectOk(SanAbi.outputCopy(released, 0, 1, destination(), 0))
    expectOk(SanAbi.outputRelease(released))
    held -= released
    assertEquals(invalid, SanAbi.outputRelease(released))
    assertTrue(rejected(bitmap, invalid, handle = released))
  }

  companion object {
    private const val FIXTURE = "journey/ordinary.png"

    /** An opaque color, which premultiplication keeps as it is. */
    private const val SENTINEL = 0xFF336699.toInt()

    /** A value that names no lease. */
    private const val STALE = 0x5A5A_5A5A_5A5A_5A5AL

    private const val PORTION = 64 shl 10

    private lateinit var fixture: ByteArray
    private lateinit var samplesSha256: String
    private var imageWidth = 0
    private var imageHeight = 0
    private var engine = SanAbi.NO_HANDLE
    private var lease = SanAbi.NO_HANDLE

    /** Every lease that a decode of the class obtained and no test released. */
    private val held = mutableSetOf<Long>()

    /** The pixels of the whole lease, copied into one Bitmap. */
    private val image: IntArray by lazy {
      val whole = destination(rows = imageHeight)
      val generation = whole.generationId

      expectOk(SanAbi.outputCopy(lease, 0, imageHeight, whole, 0))
      assertTrue(whole.generationId != generation)

      whole.pixels().also { whole.recycle() }
    }

    @JvmStatic
    @BeforeClass
    fun bindAndDecode() {
      // Each test skips itself then: the runner would count a class that an
      // assumption skips here as a run that lost its tests.
      if (!DeviceCorpus.sanLibraryPackaged) {
        return
      }

      assertEquals(SanBinding.Established, SanLibrary.bind())

      val page = DeviceCorpus.file(FIXTURE)

      fixture = page.bytes
      imageWidth = page.width
      imageHeight = page.height
      samplesSha256 = page.samplesSha256

      val out = LongArray(1)

      expectOk(
        SanAbi.engineCreate(
          aggregateBudgetBytes = 96L shl 20,
          contentCount = 2,
          jobCount = 2,
          contentLengthBytes = 16L shl 20,
          inputPortionBytes = PORTION.toLong(),
          imageAxisPixels = 1L shl 16,
          imageAreaPixels = 1L shl 26,
          metadataBytes = 1L shl 20,
          metadataExpandedBytes = 1L shl 20,
          metadataPayloadBytes = 1L shl 20,
          heldChunkBytes = 1L shl 20,
          iccTagCount = 64,
          exifEntryCount = 64,
          outputBytes = 48L shl 20,
          outputLeaseBytes = 16L shl 20,
          outputLeaseCount = 2,
          engineOut = out,
        )
      )

      engine = out[0]
      lease = decode()
    }

    @JvmStatic
    @AfterClass
    fun releaseAndShutDown() {
      // A skipped class created no engine.
      if (engine == SanAbi.NO_HANDLE) {
        return
      }

      val pending = SanAbi.engineShutdown(engine)

      // Every lease, also one that a failed test left: a later class creates
      // the one engine of the process again.
      val released = held.map(SanAbi::outputRelease)

      expectOk(SanAbi.engineShutdown(engine))
      assertEquals(SanAbi.STATUS_PENDING, pending)
      assertEquals(listOf(SanAbi.STATUS_OK), released)
    }

    private fun expectOk(status: Int) {
      assertEquals(SanAbi.STATUS_OK, status)
    }

    /**
     * Decodes the fixture whole in a new content, ends its job and its content,
     * and returns the lease, which the caller then holds.
     */
    private fun decode(): Long {
      val out = LongArray(1)

      expectOk(SanAbi.contentOpen(engine, out))

      val content = out[0]
      val report = LongArray(SanAbi.JOB_REPORT_LENGTH)

      expectOk(SanAbi.jobRequest(content, 0, 0, 0, 0, 0, out))

      val job = out[0]
      val closed: Int
      val released: Int

      try {
        step(content, job, report)
      } finally {
        // Also after a failure, so that nothing keeps the shutdown of the
        // class pending. The close ends a job that has no outcome yet, and
        // only then may the job be released.
        closed = SanAbi.contentClose(content)
        released = SanAbi.jobRelease(job)

        if (report[SanAbi.JOB_REPORT_LEASE] != SanAbi.NO_HANDLE) {
          held += report[SanAbi.JOB_REPORT_LEASE]
        }
      }

      expectOk(closed)
      expectOk(released)

      return report[SanAbi.JOB_REPORT_LEASE]
    }

    /** Steps [job] over the whole fixture and checks its last [report]. */
    private fun step(content: Long, job: Long, report: LongArray) {
      var position = 0
      var status = SanAbi.STATUS_INPUT_NEEDED

      while (position < fixture.size) {
        val length = minOf(PORTION, fixture.size - position)
        val last = position + length == fixture.size

        expectOk(
          SanAbi.contentFeed(
            content,
            position.toLong(),
            fixture,
            position,
            length,
            last,
          )
        )

        position += length
        status = SanAbi.jobStep(job, report)

        if (!last) assertEquals(SanAbi.STATUS_INPUT_NEEDED, status)
      }

      expectOk(status)

      val whole = imageHeight.toLong()

      assertEquals(whole, report[SanAbi.JOB_REPORT_COVERED_ROWS])
      assertEquals(whole, report[SanAbi.JOB_REPORT_LEASE_HEIGHT])
      assertEquals(imageWidth.toLong(), report[SanAbi.JOB_REPORT_LEASE_WIDTH])

      assertEquals(
        SanAbi.ALPHA_OPAQUE.toLong(),
        report[SanAbi.JOB_REPORT_ALPHA],
      )
    }

    /** Returns a mutable sRGB Bitmap of [config] that holds the sentinel. */
    private fun destination(
      rows: Int = 4,
      columns: Int = imageWidth,
      config: Bitmap.Config = Bitmap.Config.ARGB_8888,
    ): Bitmap =
      Bitmap.createBitmap(columns, rows, config).apply { eraseColor(SENTINEL) }

    /**
     * Copies into [bitmap], checks that the copy fails as [status] and leaves
     * the pixels alone, and returns whether the generation changed.
     */
    private fun rejected(
      bitmap: Bitmap,
      status: Int,
      handle: Long = lease,
      firstRow: Int = 0,
      rowCount: Int = 1,
      destinationFirstRow: Int = 0,
    ): Boolean {
      val before = bitmap.copy(checkNotNull(bitmap.config), false)
      val generation = bitmap.generationId

      assertEquals(
        status,
        SanAbi.outputCopy(
          handle,
          firstRow,
          rowCount,
          bitmap,
          destinationFirstRow,
        ),
      )

      assertTrue(bitmap.sameAs(before))

      return bitmap.generationId != generation
    }
  }
}
