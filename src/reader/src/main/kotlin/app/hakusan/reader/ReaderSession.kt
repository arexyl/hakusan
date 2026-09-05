package app.hakusan.reader

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One process-local reader session issued by a [ReaderSessionGate].
 *
 * The value is opaque and cannot be reconstructed or persisted. Reader input,
 * position, content, and task lifetime remain separate state.
 */
class ReaderSession internal constructor()

/**
 * One process-local event identity issued in its session's actual event order.
 */
class ReaderSessionEvent internal constructor(
  internal val session: ReaderSession,
  internal val ordinal: Long,
)

/** Relationship of an accepted event to earlier committed session events. */
enum class ReaderEventRecency {
  CURRENT,
  REORDERED,
}

/** Result of attempting work that belongs only to the current session. */
sealed interface ReaderSessionAcceptance<out Value> {
  data class Accepted<Value>(
    val value: Value,
  ) : ReaderSessionAcceptance<Value>

  /** The session was closed, superseded, or issued by another gate. */
  data object RejectedNotCurrent : ReaderSessionAcceptance<Nothing>
}

/**
 * Linearizes reader-session replacement and persistence-event delivery.
 *
 * The caller owns every task. [withCurrent] holds the session lease until its
 * block completes, fails, or is canceled. A later [start] cannot return until
 * an already accepted block releases that lease; after [start] returns, work
 * from the previous session is rejected without invoking its block.
 *
 * [issue] assigns event order at the reader-owned boundary. If a newer event
 * commits before an older one arrives, the older event is delivered as
 * [ReaderEventRecency.REORDERED] so monotonic effects can still converge while
 * latest-position effects remain unchanged.
 *
 * Use one gate for one logical reader owner. Independently created gates do
 * not arbitrate each other's sessions.
 */
class ReaderSessionGate {
  private val mutex = Mutex()
  private var current: ReaderSession? = null
  private var nextOrdinal = 0L
  private var lastCommittedOrdinal = -1L

  /** Starts a new current session and supersedes the previous one. */
  suspend fun start(): ReaderSession = mutex.withLock {
    val session = ReaderSession()
    current = session
    nextOrdinal = 0L
    lastCommittedOrdinal = -1L
    session
  }

  /** Issues the next event token if [session] is still current. */
  suspend fun issue(
    session: ReaderSession,
  ): ReaderSessionAcceptance<ReaderSessionEvent> = mutex.withLock {
    if (current !== session) {
      return@withLock ReaderSessionAcceptance.RejectedNotCurrent
    }
    check(nextOrdinal < Long.MAX_VALUE) {
      "Reader session event order is exhausted."
    }
    val event = ReaderSessionEvent(
      session = session,
      ordinal = nextOrdinal,
    )
    nextOrdinal += 1L
    ReaderSessionAcceptance.Accepted(event)
  }

  /** Closes [session] if it is still current. Repeating close is a no-op. */
  suspend fun close(session: ReaderSession) {
    mutex.withLock {
      if (current === session) {
        current = null
      }
    }
  }

  /**
   * Runs [block] only while [event] belongs to the current session.
   *
   * Cancellation and failures from [block] propagate without committing the
   * event ordinal. The block must not reenter this gate.
   */
  suspend fun <Value> withCurrent(
    event: ReaderSessionEvent,
    block: suspend (ReaderEventRecency) -> Value,
  ): ReaderSessionAcceptance<Value> {
    mutex.lock()
    try {
      if (current !== event.session) {
        return ReaderSessionAcceptance.RejectedNotCurrent
      }
      val recency = if (event.ordinal > lastCommittedOrdinal) {
        ReaderEventRecency.CURRENT
      } else {
        ReaderEventRecency.REORDERED
      }
      val value = block(recency)
      if (recency == ReaderEventRecency.CURRENT) {
        lastCommittedOrdinal = event.ordinal
      }
      return ReaderSessionAcceptance.Accepted(value)
    } finally {
      mutex.unlock()
    }
  }
}
