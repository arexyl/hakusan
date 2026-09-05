package app.hakusan.titles

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
   * Adds a known title atomically, including initial category assignment.
   * Repeating the operation for an existing member is a successful no-op.
   */
  suspend fun addToLibrary(
    titleId: TitleId,
    selection: LibraryCategorySelection =
      LibraryCategorySelection.Automatic,
  ): LibraryAddResult
}
