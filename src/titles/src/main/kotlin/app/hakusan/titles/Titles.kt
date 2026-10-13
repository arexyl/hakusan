package app.hakusan.titles

import kotlinx.coroutines.flow.Flow

/**
 * The domain operations and observations of `:titles`.
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
 *
 * Observations return cold flows. Each collection owns its observation: it
 * reads the current state first, then reads again after each committed write to
 * a table that the observation reads, and stops when the collector is canceled,
 * without changing stored state. Every reading builds its value on the
 * observation worker, a background dispatcher, so a collector on another
 * dispatcher, such as the main thread, never builds it. Equal consecutive
 * values are suppressed after mapping, so the collector receives only finished,
 * changed values. A stored-state violation fails the collection with
 * [StoredStateViolationException], never with an empty or partial value.
 * Cancellation and unexpected storage failures propagate to the collector.
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

  /**
   * Adds the title [titleId] to the Library automatically, in one write
   * transaction.
   *
   * A title that already belongs to a category stays unchanged, and the Add
   * succeeds with its current membership. Otherwise the categories that are
   * current inside the transaction decide, never a category name:
   * - without a category, the Add creates an ordinary category named Default
   *   and assigns the title to it in the same commit;
   * - with one category, the Add assigns the title to it, whatever its name;
   * - with several categories, the Add changes nothing and returns them, so the
   *   caller can choose a nonempty selection for the explicit Add.
   *
   * Write transactions are serialized, so concurrent first Adds share one
   * Default. Default is created only while no category exists, never recreated
   * by name. Several categories share one title and one reading-progress
   * record.
   *
   * The Add writes no resume position. It does not yet promote a temporary
   * position to the persistent one, because no temporary store exists.
   *
   * @throws StoredStateViolationException if the stored state of the title or
   *   of the categories violates an invariant of `:titles`, such as a
   *   persistent resume position of a title outside the Library. Nothing
   *   persists.
   * @throws IllegalStateException if the database generated an identity for
   *   Default that is not positive. It is a defect, and nothing persists.
   */
  public suspend fun addToLibrary(titleId: TitleId): LibraryAddResult

  /**
   * Adds the title [titleId] to the Library in the selected categories, in one
   * write transaction.
   *
   * A title that already belongs to a category stays unchanged, and the Add
   * succeeds with its current membership. The selection is then neither
   * validated nor applied, so it never reassigns a member. Otherwise the
   * selection is validated against the categories that are current inside the
   * transaction. When every selected category exists, the title is assigned to
   * all of them in one commit. When any selected category has disappeared, the
   * whole Add is rejected with the missing identities, and nothing changes.
   * Several categories share one title and one reading-progress record.
   *
   * The Add writes no resume position. It does not yet promote a temporary
   * position to the persistent one, because no temporary store exists.
   *
   * @throws StoredStateViolationException if the stored state of the title or
   *   of the categories violates an invariant of `:titles`, such as a
   *   persistent resume position of a title outside the Library. Nothing
   *   persists.
   */
  public suspend fun addToLibrary(
    titleId: TitleId,
    selection: LibraryCategorySelection,
  ): ExplicitLibraryAddResult

  /**
   * Observes the Library: every stored category with its titles, and each
   * Library title once with its compact progress.
   *
   * Each snapshot comes from one statement, so its categories, membership,
   * titles, canonical chapter counts, read counts, and resume availability are
   * coherent. Progress covers only Library members, and a title in several
   * categories counts its chapters and reads once. The snapshot carries no
   * product order.
   *
   * Every committed write to a table that the snapshot reads runs the whole
   * aggregation again, including every resume-position write, even when the
   * resulting snapshot is equal and is not emitted.
   *
   * A collection fails with [StoredStateViolationException] when the stored
   * Library state violates an invariant of `:titles`.
   */
  public fun observeLibrary(): Flow<LibrarySnapshot>

  /**
   * Observes the identities of the Library titles. The set is owned and carries
   * no order.
   *
   * The observation reads only titles and their category associations, so
   * chapter, read-status, and resume-position writes never make it emit. A
   * change of title details reads the set again but emits nothing, because the
   * set stays equal.
   *
   * A collection fails with [StoredStateViolationException] when a stored
   * category association or Library title violates an invariant of `:titles`.
   */
  public fun observeLibraryTitleIds(): Flow<Set<TitleId>>

  /**
   * Reads the current progress of the title [titleId] in one read transaction,
   * or returns null when no stored title has the identity.
   *
   * This is the explicit read for one-shot consumers, such as Continue. It
   * validates every stored row it reads, so it also detects an existing
   * violation that no mutation touched.
   *
   * @throws StoredStateViolationException if the stored state of the title
   *   violates an invariant of `:titles`.
   */
  public suspend fun readProgress(titleId: TitleId): ProgressSnapshot?

  /**
   * Records the actual position of [update] in one write transaction.
   *
   * The checks run in order, and the first one that fails decides the result: a
   * missing title, a chapter that is missing or belongs to another title, a
   * reordered update, which never mutates, and a read chapter. A valid position
   * of a Library title becomes its latest persistent position. A position of a
   * title outside the Library is not persisted, because the temporary store
   * does not exist yet. A chapter that the newest snapshot omits still takes a
   * position, which then reads back as temporarily unavailable.
   *
   * A position equal in chapter, unit kind, and unit index to the stored one
   * changes nothing, including the time of its last change.
   *
   * @throws StoredStateViolationException if a stored title or chapter row that
   *   the update resolves violates an invariant of `:titles`. Nothing persists.
   */
  public suspend fun recordActualPosition(
    update: ActualPositionUpdate
  ): ActualPositionResult

  /**
   * Applies the chapter boundary [completion] in one idempotent write
   * transaction.
   *
   * The title must exist, both chapters must belong to it, and the completed
   * chapter must immediately precede the started chapter in the current
   * canonical order; otherwise nothing changes. The boundary then marks the
   * completed chapter read and deletes a position on it. It records the started
   * position as the latest position under the rules of [recordActualPosition],
   * so a reordered boundary keeps the newer latest position, and a read started
   * chapter or a title outside the Library records none. Read status applies
   * whether or not the title is in the Library. Repeating a boundary changes
   * nothing, and it still attempts the read insertion.
   *
   * @throws StoredStateViolationException if a stored title or chapter row that
   *   the boundary resolves violates an invariant of `:titles`. Nothing
   *   persists.
   */
  public suspend fun completeChapterBoundary(
    completion: ChapterBoundaryCompletion
  ): ChapterBoundaryResult

  /**
   * Applies the final-chapter [completion] in one idempotent write transaction.
   *
   * The title must exist, the chapter must belong to it, and the chapter must
   * be the final chapter of the current canonical order; otherwise nothing
   * changes. The completion then marks the chapter read and deletes only a
   * position on that chapter. It writes no position, so delivery order does not
   * matter. Read status applies whether or not the title is in the Library.
   * Repeating a completion changes nothing, and it still attempts the read
   * insertion.
   *
   * @throws StoredStateViolationException if a stored title or chapter row that
   *   the completion resolves violates an invariant of `:titles`. Nothing
   *   persists.
   */
  public suspend fun completeFinalChapter(
    completion: FinalChapterCompletion
  ): FinalCompletionResult
}
