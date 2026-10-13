package app.hakusan.titles

/**
 * One actual reading position: a title, a chapter, and a unit in it.
 *
 * The unit has page or provider-segment granularity. A position stores no
 * pixel, viewport, or within-segment offset, so a long segment resumes at its
 * start.
 */
@ConsistentCopyVisibility
public data class ReadingPosition
private constructor(
  /** The identity of the title. */
  public val titleId: TitleId,
  /** The identity of the chapter. */
  public val chapterId: ChapterId,
  /** The provider granularity of the unit. */
  public val unitKind: ContentUnitKind,
  /** The zero-based index of the unit in the provider unit sequence. */
  public val unitIndex: Int,
) {
  public companion object {
    /**
     * Returns the position of the unit [unitIndex] of kind [unitKind] in the
     * chapter [chapterId] of the title [titleId].
     *
     * @throws IllegalArgumentException if [unitIndex] is negative.
     */
    public fun of(
      titleId: TitleId,
      chapterId: ChapterId,
      unitKind: ContentUnitKind,
      unitIndex: Int,
    ): ReadingPosition {
      require(unitIndex >= 0) { "A content unit index must not be negative." }

      return ReadingPosition(titleId, chapterId, unitKind, unitIndex)
    }
  }
}

/**
 * The recency of one progress command relative to the latest accepted delivery
 * of its reader session.
 *
 * The caller classifies each accepted delivery anew and never stores the
 * recency for reuse. `:titles` knows nothing about reader sessions or event
 * ordinals.
 */
public enum class ProgressEventRecency {
  /** The delivery is the latest one, so it may set the latest position. */
  CURRENT,

  /**
   * A newer delivery was already accepted, so the command never replaces the
   * latest position. Effects that do not depend on order still apply.
   */
  REORDERED,
}

/** An actual position that the reader reached in one delivery. */
public data class ActualPositionUpdate(
  /** The reached position. */
  public val position: ReadingPosition,
  /** The recency of the delivery. */
  public val recency: ProgressEventRecency,
)

/**
 * A chapter boundary: reading reached the end of one chapter, and the next
 * chapter actually began.
 *
 * The title is the title of [startedPosition]. The two chapters are distinct.
 */
@ConsistentCopyVisibility
public data class ChapterBoundaryCompletion
private constructor(
  /** The identity of the completed chapter. */
  public val completedChapterId: ChapterId,
  /** The unit that actually began in the next chapter. */
  public val startedPosition: ReadingPosition,
  /** The recency of the delivery. */
  public val recency: ProgressEventRecency,
) {
  public companion object {
    /**
     * Returns the boundary from the chapter [completedChapterId] to
     * [startedPosition].
     *
     * @throws IllegalArgumentException if [startedPosition] is in the chapter
     *   [completedChapterId].
     */
    public fun of(
      completedChapterId: ChapterId,
      startedPosition: ReadingPosition,
      recency: ProgressEventRecency,
    ): ChapterBoundaryCompletion {
      require(completedChapterId != startedPosition.chapterId) {
        "A chapter boundary must cross two distinct chapters."
      }

      return ChapterBoundaryCompletion(
        completedChapterId,
        startedPosition,
        recency,
      )
    }
  }
}

/**
 * The completion of the final chapter: the reader made the completion gesture
 * after the final unit of the chapter [chapterId] of the title [titleId].
 *
 * It carries no recency, because its effects do not depend on delivery order.
 */
public data class FinalChapterCompletion(
  /** The identity of the title. */
  public val titleId: TitleId,
  /** The identity of the completed chapter. */
  public val chapterId: ChapterId,
)

/** Why a valid position command left the latest position unchanged. */
public enum class PositionNotPersistedReason {
  /** The command was [ProgressEventRecency.REORDERED]. */
  REORDERED_EVENT,

  /** The chapter of the position is read, and a read chapter has none. */
  CHAPTER_ALREADY_READ,

  /**
   * The title is outside the Library. Its position belongs to the temporary
   * store, which does not exist yet.
   */
  TITLE_NOT_IN_LIBRARY,
}

/** The outcome of an actual-position update. */
public sealed interface ActualPositionResult {
  /**
   * The position is the latest persistent position of the title. An update
   * equal to the stored position leaves the stored row unchanged.
   */
  public data object Persisted : ActualPositionResult

  /** The update was valid, and nothing changed, for [reason]. */
  @ConsistentCopyVisibility
  public data class NotPersisted
  internal constructor(
    /** Why the latest position stayed unchanged. */
    public val reason: PositionNotPersistedReason
  ) : ActualPositionResult

  /** No stored title has the identity. Nothing changed. */
  public data object TitleNotFound : ActualPositionResult

  /** No chapter of the title has the identity. Nothing changed. */
  public data object ChapterNotFound : ActualPositionResult
}

/** The outcome of a chapter boundary. */
public sealed interface ChapterBoundaryResult {
  /**
   * The completed chapter is read, its position is deleted, and the started
   * position is the latest persistent position of the title.
   */
  public data object Completed : ChapterBoundaryResult

  /**
   * The completed chapter is read and its position is deleted, but the started
   * position was not recorded for [reason]. A reordered boundary keeps the
   * newer latest position.
   */
  @ConsistentCopyVisibility
  public data class CompletedWithoutStartedPosition
  internal constructor(
    /** Why the started position was not recorded. */
    public val reason: PositionNotPersistedReason
  ) : ChapterBoundaryResult

  /** No stored title has the identity. Nothing changed. */
  public data object TitleNotFound : ChapterBoundaryResult

  /**
   * The completed or the started chapter is not a chapter of the title. Nothing
   * changed.
   */
  public data object ChapterNotFound : ChapterBoundaryResult

  /**
   * The completed chapter no longer immediately precedes the started chapter in
   * the current canonical order. Nothing changed.
   */
  public data object CanonicalAdjacencyChanged : ChapterBoundaryResult
}

/** The outcome of a final-chapter completion. */
public sealed interface FinalCompletionResult {
  /**
   * The chapter is read, and a position on it is deleted. A position on another
   * chapter stays.
   */
  public data object Completed : FinalCompletionResult

  /** No stored title has the identity. Nothing changed. */
  public data object TitleNotFound : FinalCompletionResult

  /** No chapter of the title has the identity. Nothing changed. */
  public data object ChapterNotFound : FinalCompletionResult

  /**
   * The chapter is no longer the final chapter of the current canonical order.
   * Nothing changed.
   */
  public data object CanonicalFinalityChanged : FinalCompletionResult
}
