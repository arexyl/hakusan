package app.hakusan.titles

/**
 * The domain operations of `:titles`.
 *
 * Operations suspend. They own no scope and start no work that outlives the
 * call. The storage runs their statements on its own context, so the context of
 * the caller does not confine them. Each operation runs in one transaction. A
 * failure or a cancellation before the transaction commits persists nothing.
 * Cancellation and unexpected storage failures propagate to the caller.
 *
 * A cancellation can also surface after the transaction committed, while the
 * result returns to the context of the caller. A `CancellationException`
 * therefore does not prove that nothing persisted.
 */
public interface Titles {
  /**
   * Reconciles one accepted [observation] of a title in one write transaction
   * and returns the progress of the title read in the same transaction.
   *
   * The exact alias of the observation resolves the title. A known title keeps
   * its identity, Library membership, and reading state, and its display name
   * and description become the observed ones. An unknown alias receives a new
   * identity, so a title enters storage only together with a complete accepted
   * observation. Chapters reconcile by exact alias: a known chapter keeps its
   * identity, the observed order becomes the canonical order, and a known
   * chapter that the observation omits keeps its identity, read status, and
   * resume position without a canonical place. No title or chapter is deleted.
   *
   * This operation does not arbitrate refresh generations. Per title, the
   * caller completes each accept-to-reconcile handoff before it accepts a later
   * snapshot, so an older accepted snapshot never overwrites a newer one.
   *
   * A planned chapter write that conflicts with a unique index is a defect of
   * the planner. An insert reports it as [IllegalStateException], and an update
   * as the constraint exception of the database driver. Nothing persists.
   *
   * @throws StoredStateViolationException if the stored state of the title
   *   violates an invariant of `:titles`. Nothing persists.
   * @throws IllegalStateException if no generated identity of a new title or
   *   chapter could be stored, or if a planned chapter insert conflicts with a
   *   stored chapter. Either is a defect, and nothing persists.
   */
  public suspend fun reconcileTitle(
    observation: AcceptedTitleObservation
  ): ProgressSnapshot
}
