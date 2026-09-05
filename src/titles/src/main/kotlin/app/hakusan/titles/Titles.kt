package app.hakusan.titles

import kotlinx.coroutines.flow.Flow

/**
 * Domain operations and observations owned by the titles subsystem.
 *
 * Calls may suspend, but this contract creates no task and selects no caller
 * dispatcher or lifetime. Expected Library Add rejections use
 * [LibraryAddResult]. Cancellation and unexpected persistence failures
 * propagate to the caller or collector.
 */
interface Titles {
  /**
   * Reconciles [input] by its exact source alias and returns the stable title
   * identity. Repeated or concurrent reconciliation preserves that identity.
   */
  suspend fun reconcileSourceTitle(
    input: ReconcileSourceTitle,
  ): TitleId

  /**
   * Reconciles one accepted complete source chapter snapshot atomically.
   * Per title, the caller completes each accept-to-reconcile handoff before
   * accepting a later snapshot. This subsystem does not arbitrate source
   * refresh generations.
   */
  suspend fun reconcileChapterSnapshot(
    input: ReconcileChapterSnapshot,
  ): ChapterReconciliationResult

  /**
   * Adds a known title atomically, including initial category assignment.
   * Repeating the operation for an existing member is a successful no-op.
   */
  suspend fun addToLibrary(
    titleId: TitleId,
    selection: LibraryCategorySelection =
      LibraryCategorySelection.Automatic,
  ): LibraryAddResult

  /**
   * Observes a current snapshot followed by committed relevant changes.
   * Collection owns the observation lifetime; canceling it stops that
   * collection without changing stored state.
   */
  fun observeLibraryShelves(): Flow<LibraryShelfState>

  /** Observes current canonical chapters, read status, and Library resume. */
  fun observeReadingProgress(
    titleId: TitleId,
  ): Flow<TitleReadingProgress?>

  /**
   * Persists one current actual position only for a Library title. Reordered
   * events return without mutation. Preview, prefetch, loading, and canceled
   * navigation are not actual input.
   */
  suspend fun recordActualPosition(
    update: ActualPositionUpdate,
  ): ActualPositionResult

  /**
   * Applies one accepted actual chapter-boundary transition atomically.
   * The caller maps its reader-gate classification into the completion.
   */
  suspend fun completeChapterBoundary(
    completion: ChapterBoundaryCompletion,
  ): CompletionResult

  /**
   * Applies completion intent for the current canonical final chapter.
   * The caller supplies this only after the final forward reading gesture.
   * Completion is order-independent because it writes no successor position.
   */
  suspend fun completeFinalChapter(
    completion: FinalChapterCompletion,
  ): CompletionResult
}
