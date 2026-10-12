package app.hakusan.extensions

/** The identity and monitor of one [ChapterRefreshGate]. */
internal class ChapterRefreshOwner

/**
 * One chapter-refresh request that a [ChapterRefreshGate] issued for [title].
 *
 * Equality is structural within the issuing gate: a copy with the same complete
 * value is the same request. Requests of different gates never compare equal.
 */
@ConsistentCopyVisibility
public data class ChapterRefreshRequest
internal constructor(
  public val title: SourceTitleKey,
  internal val ordinal: Long,
  private val owner: ChapterRefreshOwner,
)

/**
 * The arbiter of chapter-refresh completions for one title's active loads.
 *
 * Each issued request is unique and newer than every earlier one, and it
 * supersedes the previous request. Issuance and acceptance are atomic. The gate
 * accepts the current completion once. A gate lives only as long as its title's
 * active loads, so a cancelled refresh needs no explicit release.
 */
public class ChapterRefreshGate(public val title: SourceTitleKey) {
  private val owner = ChapterRefreshOwner()
  private var currentRequest: ChapterRefreshRequest? = null
  private var lastOrdinal = -1L

  /** Issues a new current request that supersedes the previous one. */
  public fun issue(): ChapterRefreshRequest =
    synchronized(owner) {
      check(lastOrdinal < Long.MAX_VALUE) { "Refresh requests are exhausted." }

      lastOrdinal += 1L

      val request = ChapterRefreshRequest(title, lastOrdinal, owner)

      currentRequest = request

      request
    }

  /**
   * Accepts [completion] when it answers the current request.
   *
   * Older, future, cross-title, cross-gate, and repeated completions are
   * [ChapterRefreshAcceptance.RejectedNotCurrent].
   */
  public fun accept(
    completion: ChapterRefreshCompletion
  ): ChapterRefreshAcceptance =
    synchronized(owner) {
      if (completion.request != currentRequest) {
        return ChapterRefreshAcceptance.RejectedNotCurrent
      }

      currentRequest = null

      ChapterRefreshAcceptance.Accepted(completion.result)
    }
}

/**
 * The arbitration of one chapter-refresh completion.
 *
 * [RejectedNotCurrent] is arbitration, not a source failure. It never replaces
 * current state.
 */
public sealed interface ChapterRefreshAcceptance {
  /**
   * The completion answered the current request and carries [result].
   *
   * Only [ChapterRefreshGate.accept] creates this value.
   */
  @ConsistentCopyVisibility
  public data class Accepted
  internal constructor(
    public val result: SourceResult<ChapterSnapshot, ChapterRefreshFailure>
  ) : ChapterRefreshAcceptance

  /** The completion did not answer the current request. */
  public data object RejectedNotCurrent : ChapterRefreshAcceptance
}
