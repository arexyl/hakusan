package app.hakusan.titles

/**
 * Domain operations and observations owned by the titles subsystem.
 *
 * Calls may suspend, but this contract creates no task and selects no caller
 * dispatcher or lifetime. Cancellation and unexpected persistence
 * failures propagate to the caller.
 */
interface Titles {
  /**
   * Reconciles [input] by its exact source alias and returns the stable title
   * identity. Repeated or concurrent reconciliation preserves that identity.
   */
  suspend fun reconcileSourceTitle(
    input: ReconcileSourceTitle,
  ): TitleId
}
