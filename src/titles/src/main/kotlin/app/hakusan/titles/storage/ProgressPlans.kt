package app.hakusan.titles.storage

import app.hakusan.titles.PositionNotPersistedReason
import app.hakusan.titles.ProgressEventRecency

// The pure progress policy. It decides a position command and a chapter
// boundary from facts that the transaction resolved and validated, and from
// the recency of the delivery. The transaction applies the decision; DAOs
// decide nothing.

/** The stored facts that decide whether a position may be recorded. */
internal data class PositionFacts(
  /** Whether the title has at least one category association. */
  val isLibraryMember: Boolean,
  /** Whether the chapter of the position is in the read set. */
  val isChapterRead: Boolean,
)

/** The decision on the latest position for one position command. */
internal sealed interface PositionDecision {
  /** Record the position as the latest persistent position. */
  data object Record : PositionDecision

  /** Leave the latest position unchanged for [reason]. */
  data class Skip(val reason: PositionNotPersistedReason) : PositionDecision
}

/** The plan of a chapter boundary. */
internal sealed interface BoundaryPlan {
  /**
   * The completed chapter no longer immediately precedes the started chapter.
   * Change nothing.
   */
  data object AdjacencyChanged : BoundaryPlan

  /**
   * Mark the completed chapter read and delete a position on it, then apply
   * [startedPosition] to the started position.
   */
  data class Apply(val startedPosition: PositionDecision) : BoundaryPlan
}

/**
 * Decides a position command of [recency] on a chapter with [facts].
 *
 * The reasons apply in order: a reordered command, a read chapter, and a title
 * outside the Library. A reordered command therefore never mutates, whatever
 * the stored facts are.
 */
internal fun decidePosition(
  recency: ProgressEventRecency,
  facts: PositionFacts,
): PositionDecision =
  when {
    recency == ProgressEventRecency.REORDERED ->
      PositionDecision.Skip(PositionNotPersistedReason.REORDERED_EVENT)

    facts.isChapterRead ->
      PositionDecision.Skip(PositionNotPersistedReason.CHAPTER_ALREADY_READ)

    !facts.isLibraryMember ->
      PositionDecision.Skip(PositionNotPersistedReason.TITLE_NOT_IN_LIBRARY)

    else -> PositionDecision.Record
  }

/**
 * Plans the boundary from the completed chapter at [completedIndex] to the
 * started chapter at [startedIndex], whose stored facts are [startedFacts].
 *
 * An index is the canonical index of a chapter, or null when the newest
 * snapshot omits the chapter. The boundary applies only when the completed
 * chapter immediately precedes the started chapter in the canonical order. It
 * then always marks the completed chapter read, and it records the started
 * position as [decidePosition] decides.
 */
internal fun planBoundary(
  completedIndex: Int?,
  startedIndex: Int?,
  recency: ProgressEventRecency,
  startedFacts: PositionFacts,
): BoundaryPlan {
  val isAdjacent =
    completedIndex != null &&
      startedIndex != null &&
      completedIndex.toLong() + 1 == startedIndex.toLong()

  return if (isAdjacent) {
    BoundaryPlan.Apply(decidePosition(recency, startedFacts))
  } else {
    BoundaryPlan.AdjacencyChanged
  }
}
