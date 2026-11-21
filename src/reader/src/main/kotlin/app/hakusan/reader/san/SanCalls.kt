package app.hakusan.reader.san

import app.hakusan.reader.ReaderBitmapTarget
import app.hakusan.reader.ReaderTarget

/**
 * The calls on one engine, as an interface so that JVM tests script the engine.
 *
 * Each function has the contract of the [SanAbi] function of its name, and
 * [outputCopy] takes its destination as a [ReaderTarget]. Establish binding is
 * no call on an engine: [SanLibrary] makes it.
 */
internal interface SanCalls {
  fun engineCreate(
    aggregateBudgetBytes: Long,
    contentCount: Long,
    jobCount: Long,
    contentLengthBytes: Long,
    inputPortionBytes: Long,
    imageAxisPixels: Long,
    imageAreaPixels: Long,
    metadataBytes: Long,
    metadataExpandedBytes: Long,
    metadataPayloadBytes: Long,
    heldChunkBytes: Long,
    iccTagCount: Long,
    exifEntryCount: Long,
    outputBytes: Long,
    outputLeaseBytes: Long,
    outputLeaseCount: Long,
    engineOut: LongArray,
  ): Int

  fun engineShutdown(engine: Long): Int

  fun engineCounters(
    engine: Long,
    resetPeak: Boolean,
    countersOut: LongArray,
  ): Int

  fun contentOpen(engine: Long, contentOut: LongArray): Int

  fun contentFeed(
    content: Long,
    position: Long,
    bytes: ByteArray,
    offset: Int,
    length: Int,
    endOfInput: Boolean,
  ): Int

  fun contentClose(content: Long): Int

  fun jobRequest(
    content: Long,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    maxOutputWidth: Int,
    jobOut: LongArray,
  ): Int

  fun jobStep(job: Long, reportOut: LongArray): Int

  /** The one call that any thread may make, while the caller owns [job]. */
  fun jobCancel(job: Long): Int

  fun jobRelease(job: Long): Int

  fun outputCopy(
    lease: Long,
    firstRow: Int,
    rowCount: Int,
    destination: ReaderTarget,
    destinationFirstRow: Int,
  ): Int

  fun outputRelease(lease: Long): Int
}

/**
 * The calls on the loaded San library.
 *
 * San takes no lock, so the caller serializes the calls on an engine: every
 * call except [jobCancel] must come from [worker].
 */
internal class SanAbiCalls(private val worker: Thread) : SanCalls {
  override fun engineCreate(
    aggregateBudgetBytes: Long,
    contentCount: Long,
    jobCount: Long,
    contentLengthBytes: Long,
    inputPortionBytes: Long,
    imageAxisPixels: Long,
    imageAreaPixels: Long,
    metadataBytes: Long,
    metadataExpandedBytes: Long,
    metadataPayloadBytes: Long,
    heldChunkBytes: Long,
    iccTagCount: Long,
    exifEntryCount: Long,
    outputBytes: Long,
    outputLeaseBytes: Long,
    outputLeaseCount: Long,
    engineOut: LongArray,
  ): Int = onWorker {
    SanAbi.engineCreate(
      aggregateBudgetBytes = aggregateBudgetBytes,
      contentCount = contentCount,
      jobCount = jobCount,
      contentLengthBytes = contentLengthBytes,
      inputPortionBytes = inputPortionBytes,
      imageAxisPixels = imageAxisPixels,
      imageAreaPixels = imageAreaPixels,
      metadataBytes = metadataBytes,
      metadataExpandedBytes = metadataExpandedBytes,
      metadataPayloadBytes = metadataPayloadBytes,
      heldChunkBytes = heldChunkBytes,
      iccTagCount = iccTagCount,
      exifEntryCount = exifEntryCount,
      outputBytes = outputBytes,
      outputLeaseBytes = outputLeaseBytes,
      outputLeaseCount = outputLeaseCount,
      engineOut = engineOut,
    )
  }

  override fun engineShutdown(engine: Long): Int = onWorker {
    SanAbi.engineShutdown(engine)
  }

  override fun engineCounters(
    engine: Long,
    resetPeak: Boolean,
    countersOut: LongArray,
  ): Int = onWorker { SanAbi.engineCounters(engine, resetPeak, countersOut) }

  override fun contentOpen(engine: Long, contentOut: LongArray): Int =
    onWorker {
      SanAbi.contentOpen(engine, contentOut)
    }

  override fun contentFeed(
    content: Long,
    position: Long,
    bytes: ByteArray,
    offset: Int,
    length: Int,
    endOfInput: Boolean,
  ): Int = onWorker {
    SanAbi.contentFeed(content, position, bytes, offset, length, endOfInput)
  }

  override fun contentClose(content: Long): Int = onWorker {
    SanAbi.contentClose(content)
  }

  override fun jobRequest(
    content: Long,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    maxOutputWidth: Int,
    jobOut: LongArray,
  ): Int = onWorker {
    SanAbi.jobRequest(content, left, top, right, bottom, maxOutputWidth, jobOut)
  }

  override fun jobStep(job: Long, reportOut: LongArray): Int = onWorker {
    SanAbi.jobStep(job, reportOut)
  }

  override fun jobCancel(job: Long): Int = SanAbi.jobCancel(job)

  override fun jobRelease(job: Long): Int = onWorker { SanAbi.jobRelease(job) }

  override fun outputCopy(
    lease: Long,
    firstRow: Int,
    rowCount: Int,
    destination: ReaderTarget,
    destinationFirstRow: Int,
  ): Int = onWorker {
    require(destination is ReaderBitmapTarget) {
      "The San library copies into a Bitmap only."
    }

    SanAbi.outputCopy(
      lease,
      firstRow,
      rowCount,
      destination.bitmap,
      destinationFirstRow,
    )
  }

  override fun outputRelease(lease: Long): Int = onWorker {
    SanAbi.outputRelease(lease)
  }

  private inline fun onWorker(call: () -> Int): Int {
    check(Thread.currentThread() === worker) {
      "An engine call left the reader worker."
    }

    return call()
  }
}
