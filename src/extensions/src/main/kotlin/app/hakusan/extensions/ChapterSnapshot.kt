package app.hakusan.extensions

/**
 * One chapter observation.
 *
 * [displayName] is unvalidated display metadata. It never defines identity or
 * canonical order.
 */
public data class SourceChapter(
  public val key: SourceChapterKey,
  public val displayName: String,
)

/** The backend's assertion about one observed chapter sequence. */
public enum class ChapterSequenceStatus {
  /** The backend cannot establish one first-to-final order. */
  AMBIGUOUS,

  /** The sequence is complete and ordered from first to final. */
  COMPLETE,

  /** The sequence omits chapters of the title. */
  PARTIAL,
}

/**
 * A complete, unambiguous, first-to-final chapter sequence of one title.
 *
 * The order of [chapters] is canonical. Display metadata and completion timing
 * never define it. Every chapter belongs to [title] and occurs once. An empty
 * snapshot is a valid observation of a title without chapters.
 */
@ConsistentCopyVisibility
public data class ChapterSnapshot
private constructor(
  public val title: SourceTitleKey,
  public val chapters: List<SourceChapter>,
) {
  public companion object {
    /**
     * Validates an owned, ordered copy of [chapters] as the snapshot of [title]
     * that the backend asserts with [status].
     */
    public fun create(
      title: SourceTitleKey,
      status: ChapterSequenceStatus,
      chapters: Iterable<SourceChapter>,
    ): SourceResult<ChapterSnapshot, ChapterRefreshFailure> {
      when (status) {
        ChapterSequenceStatus.AMBIGUOUS ->
          return invalidSnapshot(ChapterSnapshotRejection.AMBIGUOUS_ORDER)

        ChapterSequenceStatus.PARTIAL ->
          return invalidSnapshot(ChapterSnapshotRejection.PARTIAL_SEQUENCE)

        ChapterSequenceStatus.COMPLETE -> Unit
      }

      val ownedChapters = chapters.toOwnedList()

      if (ownedChapters.any { it.key.title != title }) {
        return invalidSnapshot(ChapterSnapshotRejection.FOREIGN_TITLE)
      }

      val seen = HashSet<SourceChapterKey>(ownedChapters.size)

      if (ownedChapters.any { !seen.add(it.key) }) {
        return invalidSnapshot(ChapterSnapshotRejection.DUPLICATE_CHAPTER)
      }

      return SourceResult.Success(ChapterSnapshot(title, ownedChapters))
    }

    private fun invalidSnapshot(
      reason: ChapterSnapshotRejection
    ): SourceResult.Failure<SourceFailure.InvalidChapterSnapshot> =
      SourceResult.Failure(SourceFailure.InvalidChapterSnapshot(reason))
  }
}

/** The reason a chapter-sequence observation is invalid. */
public enum class ChapterSnapshotRejection {
  /** The backend asserted an ambiguous order. */
  AMBIGUOUS_ORDER,

  /** The same chapter identity occurs more than once. */
  DUPLICATE_CHAPTER,

  /** The completion is bound to a request other than the answered one. */
  FOREIGN_REQUEST,

  /** A chapter belongs to a title other than the requested one. */
  FOREIGN_TITLE,

  /** The backend asserted a partial sequence. */
  PARTIAL_SEQUENCE,
}
