package app.hakusan.reader

/**
 * One session-bound reading event. Only [ReaderNavigation] creates it.
 *
 * Its recency is classified when [ReaderSessionGate.withCurrent] delivers
 * [identity], never stored here.
 */
@ConsistentCopyVisibility
public data class ReaderEvent
internal constructor(
  public val identity: ReaderSessionEvent,
  public val payload: ReaderEventPayload,
)

/** What an actually started or completed reading step reports. */
public sealed interface ReaderEventPayload {
  /** The unit at [position] actually began reading. */
  public data class ActualPosition(public val position: ReaderPosition) :
    ReaderEventPayload

  /**
   * The reader left the end of [completedChapter], and its canonical successor
   * actually began reading at [startedPosition].
   */
  public data class ChapterBoundary(
    public val completedChapter: ReaderChapterKey,
    public val startedPosition: ReaderPosition,
  ) : ReaderEventPayload

  /** The reader gestured forward from the final unit of [chapter]. */
  public data class FinalCompletion(public val chapter: ReaderChapterKey) :
    ReaderEventPayload
}

/**
 * One navigation intent. It is neither a content job nor a reading event.
 *
 * A boundary request also records the actual position it leaves, which its
 * start reports as the completed chapter.
 */
public class ReaderNavigationRequest
internal constructor(
  public val chapter: ReaderChapterKey,
  public val start: ReaderStart,
  internal val boundaryFrom: ReaderPosition?,
)

/** The separate actual, preview, and requested navigation targets. */
public data class ReaderNavigationState(
  public val actual: ReaderPosition?,
  public val preview: ReaderPosition?,
  public val requested: ReaderNavigationRequest?,
)

/** Why navigation rejected an operation. */
public enum class ReaderNavigationRejection {
  /** The navigation is closing, or its session has ended. */
  CLOSING,

  /** The requested or previewed chapter is not in the pinned sequence. */
  UNKNOWN_CHAPTER,

  /**
   * The operation targets navigation state that is not current: a superseded or
   * withdrawn request, a preview of a chapter other than the chapter of actual
   * reading, a release without a preview, a position other than the actual end,
   * or a final gesture after the completion it repeats.
   */
  STALE_REQUEST,

  /** The start or the preview target names no unit of the chapter content. */
  UNAVAILABLE_START,
}

/** The result of one navigation operation. */
public sealed interface ReaderNavigationResult<out Value> {
  /** The operation applied and produced [value]. */
  public data class Accepted<out Value>(public val value: Value) :
    ReaderNavigationResult<Value>

  /**
   * The operation was rejected for [reason]. Only an unavailable start changes
   * state: it withdraws its request.
   */
  public data class Rejected(public val reason: ReaderNavigationRejection) :
    ReaderNavigationResult<Nothing>
}

/**
 * Reader intent and explicit presentation acknowledgments, without UI or I/O.
 *
 * The requested, preview, and actual positions stay separate. Only
 * [actualStarted] and [completeFinalAfterEnd] emit reading events. Presentation
 * calls them for a real start and a real forward gesture. Image coverage, input
 * EOF, and decoded-region edges never call them.
 *
 * Every transition runs under the session lease of [gate], and then under this
 * navigation's monitor. When the session ends through its gate, the navigation
 * settles as [beginClose] does, without a paired call.
 *
 * A call that contradicts the pinned sequence or the resolved start is a caller
 * defect and throws [IllegalArgumentException]. The named rejections cover
 * requests whose validity depends on the navigation state.
 */
public class ReaderNavigation(
  private val gate: ReaderSessionGate,
  private val session: ReaderSession,
  public val input: ReaderInput,
) {
  private val monitor = Any()
  private var closing = false
  private var content: ReaderChapterContent? = null
  private var actual: ReaderPosition? = null
  private var preview: ReaderPosition? = null
  private var requested: ReaderNavigationRequest? = null
  private var finalCompleted = false

  init {
    session.invokeOnEnd(::beginClose)
  }

  /** Returns the current navigation targets. */
  public fun snapshot(): ReaderNavigationState =
    synchronized(monitor) { ReaderNavigationState(actual, preview, requested) }

  /**
   * Requests [chapter] at [start] and clears the preview. The request replaces
   * a pending one.
   *
   * @throws IllegalStateException if an accepted block of [gate] calls it.
   */
  public suspend fun request(
    chapter: ReaderChapterKey,
    start: ReaderStart = ReaderStart.Beginning,
  ): ReaderNavigationResult<ReaderNavigationRequest> = transition {
    if (input.find(chapter) == null) {
      return@transition rejected(ReaderNavigationRejection.UNKNOWN_CHAPTER)
    }

    replaceRequest(ReaderNavigationRequest(chapter, start, null))
  }

  /**
   * Moves the preview target to the unit at [unitIndex] of [chapter]. The
   * actual position and the pending request stay unchanged.
   *
   * A chapter outside the pinned sequence is unknown. A preview of another
   * chapter of the sequence than the chapter of actual reading is a stale
   * request, for example a slider value from before a chapter boundary.
   *
   * @throws IllegalStateException if an accepted block of [gate] calls it.
   */
  public suspend fun preview(
    chapter: ReaderChapterKey,
    unitIndex: Int,
  ): ReaderNavigationResult<ReaderPosition> = transition {
    if (input.find(chapter) == null) {
      return@transition rejected(ReaderNavigationRejection.UNKNOWN_CHAPTER)
    }

    val loaded = content

    if (loaded == null || loaded.chapter.key != chapter) {
      return@transition rejected(ReaderNavigationRejection.STALE_REQUEST)
    }

    if (unitIndex !in loaded.units.indices) {
      return@transition rejected(ReaderNavigationRejection.UNAVAILABLE_START)
    }

    val target = loaded.positionAt(unitIndex)

    preview = target

    ReaderNavigationResult.Accepted(target)
  }

  /**
   * Clears the preview target without requesting a seek.
   *
   * @throws IllegalStateException if an accepted block of [gate] calls it.
   */
  public suspend fun cancelPreview() {
    transition {
      preview = null

      ReaderNavigationResult.Accepted(Unit)
    }
  }

  /**
   * Clears the preview target and requests a seek to it in the previewed
   * chapter.
   *
   * @throws IllegalStateException if an accepted block of [gate] calls it.
   */
  public suspend fun releasePreview():
    ReaderNavigationResult<ReaderNavigationRequest> = transition {
    val target =
      preview
        ?: return@transition rejected(ReaderNavigationRejection.STALE_REQUEST)

    val start = ReaderStart.Resume(target.unitKind, target.unitIndex)

    replaceRequest(ReaderNavigationRequest(target.chapter, start, null))
  }

  /**
   * Withdraws [request] if it is pending, for example after its start failed.
   * No event is emitted, and the actual position stays unchanged.
   *
   * @throws IllegalStateException if an accepted block of [gate] calls it.
   */
  public suspend fun cancelRequest(
    request: ReaderNavigationRequest
  ): ReaderNavigationResult<Unit> = transition {
    if (requested !== request) {
      return@transition rejected(ReaderNavigationRejection.STALE_REQUEST)
    }

    requested = null

    ReaderNavigationResult.Accepted(Unit)
  }

  /**
   * Acknowledges that [request] actually began reading at [position] of
   * [loaded], and emits its event.
   *
   * A boundary request emits a chapter boundary; any other request emits the
   * actual position. A start that names no unit of [loaded] fails: the request
   * is withdrawn, and no event is emitted.
   *
   * @throws IllegalArgumentException if [request] is pending and [loaded] is
   *   content of another chapter, or if its start resolves in [loaded] to a
   *   unit other than [position].
   * @throws IllegalStateException if an accepted block of [gate] calls it, or
   *   if the event order of the session is exhausted.
   */
  public suspend fun actualStarted(
    request: ReaderNavigationRequest,
    loaded: ReaderChapterContent,
    position: ReaderPosition,
  ): ReaderNavigationResult<ReaderEvent> = transition { issue ->
    if (requested !== request) {
      return@transition rejected(ReaderNavigationRejection.STALE_REQUEST)
    }

    require(loaded.chapter.key == request.chapter) {
      "The content belongs to another chapter than the request."
    }

    val target = loaded.resolve(request.start)

    if (target !is ReaderStartTarget.Available) {
      requested = null

      return@transition rejected(ReaderNavigationRejection.UNAVAILABLE_START)
    }

    require(target.position == position) {
      "The started unit is not the unit that the request selects."
    }

    val event = ReaderEvent(issue(), startedPayload(request, position))

    content = loaded
    actual = position
    preview = null
    requested = null
    finalCompleted = false

    ReaderNavigationResult.Accepted(event)
  }

  /**
   * Requests the canonical successor after a forward gesture at the end of
   * [position], which must be the final unit of the actual chapter.
   *
   * The boundary event follows only when the successor actually starts.
   *
   * @throws IllegalArgumentException if [position] is the actual end of the
   *   final chapter, which completes through [completeFinalAfterEnd] instead.
   * @throws IllegalStateException if an accepted block of [gate] calls it.
   */
  public suspend fun nextChapterAfterEnd(
    position: ReaderPosition
  ): ReaderNavigationResult<ReaderNavigationRequest> = transition {
    if (!atActualEnd(position)) {
      return@transition rejected(ReaderNavigationRejection.STALE_REQUEST)
    }

    val successor =
      requireNotNull(input.next(position.chapter)) {
        "The final chapter has no successor."
      }

    val start = ReaderStart.Beginning

    replaceRequest(ReaderNavigationRequest(successor.key, start, position))
  }

  /**
   * Emits the final completion after the additional forward gesture at
   * [position], which must be the final unit of the actual chapter. A repeated
   * gesture for the same start is stale.
   *
   * @throws IllegalArgumentException if [position] is the actual end of a
   *   chapter with a successor, which advances through [nextChapterAfterEnd]
   *   instead.
   * @throws IllegalStateException if an accepted block of [gate] calls it, or
   *   if the event order of the session is exhausted.
   */
  public suspend fun completeFinalAfterEnd(
    position: ReaderPosition
  ): ReaderNavigationResult<ReaderEvent> = transition { issue ->
    if (!atActualEnd(position)) {
      return@transition rejected(ReaderNavigationRejection.STALE_REQUEST)
    }

    require(input.next(position.chapter) == null) {
      "Only the final chapter completes after its end."
    }

    if (finalCompleted) {
      return@transition rejected(ReaderNavigationRejection.STALE_REQUEST)
    }

    val payload = ReaderEventPayload.FinalCompletion(position.chapter)

    val event = ReaderEvent(issue(), payload)

    finalCompleted = true

    ReaderNavigationResult.Accepted(event)
  }

  /**
   * Rejects every later operation, and clears the preview and the pending
   * request. The actual position stays. Repeating it is a no-op.
   *
   * The owner calls it first when the reader closes, then closes the session
   * through the gate. The end of the session has the same effect.
   */
  public fun beginClose() {
    synchronized(monitor) {
      closing = true
      preview = null
      requested = null
    }
  }

  private fun replaceRequest(
    request: ReaderNavigationRequest
  ): ReaderNavigationResult<ReaderNavigationRequest> {
    preview = null
    requested = request

    return ReaderNavigationResult.Accepted(request)
  }

  private fun startedPayload(
    request: ReaderNavigationRequest,
    position: ReaderPosition,
  ): ReaderEventPayload {
    val previous = request.boundaryFrom

    return if (previous == null) {
      ReaderEventPayload.ActualPosition(position)
    } else {
      ReaderEventPayload.ChapterBoundary(previous.chapter, position)
    }
  }

  private fun atActualEnd(position: ReaderPosition): Boolean =
    actual == position && content?.units?.lastIndex == position.unitIndex

  /**
   * Runs [block] under the session lease and the monitor. A closing navigation
   * and an ended session both reject with [ReaderNavigationRejection.CLOSING].
   */
  private suspend fun <Value> transition(
    block: (issue: () -> ReaderSessionEvent) -> ReaderNavigationResult<Value>
  ): ReaderNavigationResult<Value> {
    val acceptance =
      gate.withCurrentSession(session) { issue ->
        synchronized(monitor) { if (closing) CLOSED else block(issue) }
      }

    return when (acceptance) {
      is ReaderSessionAcceptance.Accepted -> acceptance.value
      ReaderSessionAcceptance.RejectedNotCurrent -> CLOSED
    }
  }

  private fun rejected(
    reason: ReaderNavigationRejection
  ): ReaderNavigationResult<Nothing> = ReaderNavigationResult.Rejected(reason)

  private companion object {
    val CLOSED =
      ReaderNavigationResult.Rejected(ReaderNavigationRejection.CLOSING)
  }
}
