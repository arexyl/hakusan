package app.hakusan.extensions

/** An opaque chapter-refresh generation issued by [ChapterRefreshGate]. */
@ConsistentCopyVisibility
data class ChapterRefreshGeneration internal constructor(
  internal val ordinal: Long,
)

/** Private per-gate request identity and synchronization monitor. */
internal class ChapterRefreshOwner

/**
 * One chapter refresh request issued by a [ChapterRefreshGate].
 */
@ConsistentCopyVisibility
data class ChapterRefreshRequest internal constructor(
  val title: SourceTitleKey,
  val generation: ChapterRefreshGeneration,
  private val owner: ChapterRefreshOwner,
)

/**
 * The single generation owner and completion arbiter for one title.
 *
 * Issuing or accepting a request is atomic. Issuing supersedes the previous
 * request. A current completion is accepted at most once.
 */
class ChapterRefreshGate(
  val title: SourceTitleKey,
) {
  private val owner = ChapterRefreshOwner()
  private var currentRequest: ChapterRefreshRequest? = null
  private var lastOrdinal = -1L

  fun issue(): ChapterRefreshRequest = synchronized(owner) {
    check(lastOrdinal < Long.MAX_VALUE) {
      "Chapter refresh generation is exhausted."
    }
    lastOrdinal += 1L
    ChapterRefreshRequest(
      title = title,
      generation = ChapterRefreshGeneration(lastOrdinal),
      owner = owner,
    ).also { currentRequest = it }
  }

  fun accept(
    completion: ChapterRefreshCompletion,
  ): ChapterRefreshAcceptance = synchronized(owner) {
    if (completion.request != currentRequest) {
      return@synchronized ChapterRefreshAcceptance.RejectedNotCurrent
    }

    currentRequest = null
    ChapterRefreshAcceptance.Accepted(completion.result)
  }
}

/** A source completion paired with the request generation that produced it. */
@ConsistentCopyVisibility
data class ChapterRefreshCompletion private constructor(
  val request: ChapterRefreshRequest,
  val result: SourceResult<ChapterSnapshot, ChapterRefreshFailure>,
) {
  companion object {
    fun completed(
      request: ChapterRefreshRequest,
      status: ChapterSequenceStatus,
      chapters: Iterable<SourceChapter>,
    ): ChapterRefreshCompletion = ChapterRefreshCompletion(
      request = request,
      result = ChapterSnapshot.create(
        title = request.title,
        status = status,
        chapters = chapters,
      ),
    )

    fun failed(
      request: ChapterRefreshRequest,
      error: ChapterRefreshFailure,
    ): ChapterRefreshCompletion = ChapterRefreshCompletion(
      request = request,
      result = SourceResult.Failure(error),
    )
  }
}

/**
 * The current-request arbitration result.
 *
 * [RejectedNotCurrent] is not a source failure and must not replace current
 * state.
 */
sealed interface ChapterRefreshAcceptance {
  data class Accepted(
    val result: SourceResult<ChapterSnapshot, ChapterRefreshFailure>,
  ) : ChapterRefreshAcceptance

  data object RejectedNotCurrent : ChapterRefreshAcceptance
}
