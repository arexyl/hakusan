package app.hakusan.titles

import java.util.Collections

/**
 * The coherent reading progress of one title, read in one transaction.
 *
 * The snapshot is owned: later changes to storage never change it. Read status,
 * the resume target, and Library membership are independent facts. A chapter
 * without a read flag may still have been read before the newest snapshot
 * omitted it, and the absence of a resume target does not mean that a chapter
 * is unread.
 */
@ConsistentCopyVisibility
public data class ProgressSnapshot
private constructor(
  /** The identity of the title. */
  public val titleId: TitleId,
  /** Whether the title belongs to at least one Library category. */
  public val isInLibrary: Boolean,
  /**
   * The canonical first-to-final chapter sequence of the newest accepted
   * snapshot, each chapter with its independent read flag.
   */
  public val chapters: List<ChapterReadingState>,
  /** The latest actual position of the title, or null when it has none. */
  public val resumeTarget: ResumeTarget?,
) {
  internal companion object {
    /**
     * Returns the snapshot of already validated progress. The read boundary is
     * the only caller and has established every invariant of the snapshot.
     */
    fun create(
      titleId: TitleId,
      isInLibrary: Boolean,
      chapters: List<ChapterReadingState>,
      resumeTarget: ResumeTarget?,
    ): ProgressSnapshot =
      ProgressSnapshot(
        titleId = titleId,
        isInLibrary = isInLibrary,
        chapters = Collections.unmodifiableList(ArrayList(chapters)),
        resumeTarget = resumeTarget,
      )
  }
}

/**
 * One known chapter of a title, with its latest stored metadata.
 *
 * The enclosing snapshot owns the title identity. Only the read boundary of
 * `:titles` creates a chapter, from a validated stored row.
 */
@ConsistentCopyVisibility
public data class Chapter
internal constructor(
  /** The identity of the chapter. */
  public val id: ChapterId,
  /** The exact title-local key of the chapter. */
  public val localKey: TitleLocalChapterKey,
  /** The display name of the newest accepted snapshot that listed it. */
  public val displayName: String,
)

/**
 * One canonical chapter and its read status. Only the read boundary of
 * `:titles` creates it.
 */
@ConsistentCopyVisibility
public data class ChapterReadingState
internal constructor(
  /** The chapter. */
  public val chapter: Chapter,
  /** Whether the chapter is in the read set of the title. */
  public val isRead: Boolean,
)

/** The provider granularity of the content unit of a position. */
public enum class ContentUnitKind {
  /** One page. */
  PAGE,

  /** One provider-defined segment, such as a long webtoon strip. */
  PROVIDER_SEGMENT,
}

/**
 * The latest actual position of a title: a chapter and a unit in it.
 *
 * The chapter is never read. A target is [Available] while the newest accepted
 * snapshot lists its chapter, and [TemporarilyUnavailable] while that snapshot
 * omits it. An unavailable target is kept, never replaced by another chapter.
 *
 * Only the read boundary of `:titles` creates a target, after it validated the
 * unit index, the read status, and the availability against the canonical
 * order. The constructors therefore stay internal.
 */
public sealed interface ResumeTarget {
  /** The chapter of the position. */
  public val chapter: Chapter

  /** The provider granularity of the unit. */
  public val unitKind: ContentUnitKind

  /** The zero-based index of the unit in the provider unit sequence. */
  public val unitIndex: Int

  /** A position in a chapter of the canonical sequence. */
  @ConsistentCopyVisibility
  public data class Available
  internal constructor(
    override val chapter: Chapter,
    override val unitKind: ContentUnitKind,
    override val unitIndex: Int,
  ) : ResumeTarget

  /** A position in a known chapter that the newest snapshot omits. */
  @ConsistentCopyVisibility
  public data class TemporarilyUnavailable
  internal constructor(
    override val chapter: Chapter,
    override val unitKind: ContentUnitKind,
    override val unitIndex: Int,
  ) : ResumeTarget
}
