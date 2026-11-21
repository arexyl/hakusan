package app.hakusan.reader

/**
 * The limits of the native engine and the ceilings of the adapter around it, as
 * named, replaceable settings.
 *
 * Engine creation passes the first sixteen as they stand. The engine checks
 * each limit alone and no relation between two of them, so the settings check
 * their consistency here, once: a combination that could admit nothing, or that
 * contradicts a containment between two ceilings, is rejected at construction.
 * Every ceiling applies together with the aggregate budget, which counts a
 * block once however many limits count it.
 */
public data class ReaderEngineSettings(
  /** Every native resource of the engine together, in bytes. */
  public val aggregateBudgetBytes: Long,
  /** Live content contexts, opening and closing ones included. */
  public val contentCount: Int,
  /** Owned native jobs, each from its request until its release. */
  public val jobCount: Int,
  /** The logical length of one representation, in bytes. */
  public val contentLengthBytes: Long,
  /** One feed, and the staging of each live content context, in bytes. */
  public val inputPortionBytes: Int,
  /** Each encoded image axis, in pixels. */
  public val imageAxisPixels: Int,
  /** The encoded width times the height, in pixels. */
  public val imageAreaPixels: Long,
  /** Native metadata and bookkeeping, expanded metadata included, in bytes. */
  public val metadataBytes: Long,
  /** Expanded ICC and EXIF data live at once, in bytes. */
  public val metadataExpandedBytes: Long,
  /** One expanded ICC profile or EXIF payload, in bytes. */
  public val metadataPayloadBytes: Long,
  /** One held container chunk outside the image data, in declared bytes. */
  public val heldChunkBytes: Long,
  /** ICC tag-table entries. */
  public val iccTagCount: Int,
  /** EXIF IFD0 entries examined. */
  public val exifEntryCount: Int,
  /** Native output leases and conversion intermediates together, in bytes. */
  public val outputBytes: Long,
  /** One native output lease, its size header included, in bytes. */
  public val outputLeaseBytes: Long,
  /** Live native output leases. */
  public val outputLeaseCount: Int,
  /**
   * The portion staging of every live content context together, in bytes. The
   * engine takes no such limit: it bounds the two limits that multiply to it.
   */
  public val transferStagingBytes: Long,
  /** Every target of the output copies together, in bytes of pixels. */
  public val targetBytes: Long,
  /** The targets of the output copies, across all content owners. */
  public val targetCount: Int,
) {
  init {
    val limits =
      listOf(
        aggregateBudgetBytes,
        contentCount.toLong(),
        jobCount.toLong(),
        contentLengthBytes,
        inputPortionBytes.toLong(),
        imageAxisPixels.toLong(),
        imageAreaPixels,
        metadataBytes,
        metadataExpandedBytes,
        metadataPayloadBytes,
        heldChunkBytes,
        iccTagCount.toLong(),
        exifEntryCount.toLong(),
        outputBytes,
        outputLeaseBytes,
        outputLeaseCount.toLong(),
        transferStagingBytes,
        targetBytes,
        targetCount.toLong(),
      )

    require(limits.all { it >= 1 }) { "Every limit must be at least 1." }

    require(maxOf(contentCount, jobCount, outputLeaseCount) <= MAX_COUNT) {
      "A handle names at most $MAX_COUNT contents, jobs, or leases."
    }

    // A content admits one job, and a job holds a lease slot from its request.
    require(jobCount <= contentCount && jobCount <= outputLeaseCount) {
      "Every job needs a content and a lease slot."
    }

    require(contentCount.toLong() * inputPortionBytes <= transferStagingBytes) {
      "The portion staging of the contents exceeds the transfer staging."
    }

    require(
      metadataPayloadBytes <= metadataExpandedBytes &&
        metadataExpandedBytes <= metadataBytes
    ) {
      "A payload lies in the expanded metadata, and that in the metadata."
    }

    require(outputLeaseBytes <= outputBytes) {
      "One output lease exceeds the output ceiling."
    }

    require(
      maxOf(metadataBytes, outputBytes, transferStagingBytes) <=
        aggregateBudgetBytes
    ) {
      "A ceiling exceeds the aggregate budget."
    }
  }

  public companion object {
    private const val MAX_COUNT = 65_535

    /**
     * The first-iteration qualification profile: a budget of 128 MiB with one
     * job, three contents, and four leases of at most 16 MiB, and four targets
     * within 64 MiB.
     */
    public val FIRST_ITERATION: ReaderEngineSettings =
      ReaderEngineSettings(
        aggregateBudgetBytes = 128L shl 20,
        contentCount = 3,
        jobCount = 1,
        contentLengthBytes = 512L shl 20,
        inputPortionBytes = 64 shl 10,
        imageAxisPixels = 65_535,
        imageAreaPixels = 64_000_000,
        metadataBytes = 4L shl 20,
        metadataExpandedBytes = 2L shl 20,
        metadataPayloadBytes = 1L shl 20,
        heldChunkBytes = (1L shl 20) + (1L shl 10),
        iccTagCount = 128,
        exifEntryCount = 1_024,
        outputBytes = 64L shl 20,
        outputLeaseBytes = 16L shl 20,
        outputLeaseCount = 4,
        transferStagingBytes = 2L shl 20,
        targetBytes = 64L shl 20,
        targetCount = 4,
      )
  }
}
