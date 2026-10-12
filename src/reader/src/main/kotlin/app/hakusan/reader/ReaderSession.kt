package app.hakusan.reader

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One process-local reader session issued by a [ReaderSessionGate].
 *
 * The value is opaque and is never persisted. Its lifetime ends once, when its
 * gate closes or replaces it. Reader input, positions, content, and task
 * lifetime remain separate state.
 */
public class ReaderSession internal constructor() {
  private val lifetime = Job()

  /**
   * Runs [handler] once when this session ends, or at once if it has ended.
   *
   * The handler runs on the thread that ends the session, after the gate has
   * released its lease. It must be short, must not block or throw, and must not
   * enter the gate.
   */
  internal fun invokeOnEnd(handler: () -> Unit) {
    lifetime.invokeOnCompletion { handler() }
  }

  /** Ends the lifetime. Only the gate calls it, outside its lease. */
  internal fun end() {
    lifetime.complete()
  }
}

/**
 * One process-local event identity, issued in its session's event order.
 *
 * The value is opaque and is never persisted.
 */
public class ReaderSessionEvent
internal constructor(
  internal val session: ReaderSession,
  internal val ordinal: Long,
)

/** The relationship of a delivered event to the committed session events. */
public enum class ReaderEventRecency {
  /** No newer event of the session has committed. */
  CURRENT,

  /**
   * A newer event of the session has committed, or this event has committed
   * before. Latest-position effects must not apply.
   */
  REORDERED,
}

/** The result of work that belongs only to the current session. */
public sealed interface ReaderSessionAcceptance<out Value> {
  /** The session was current, and the work produced [value]. */
  public data class Accepted<out Value>(public val value: Value) :
    ReaderSessionAcceptance<Value>

  /**
   * The session was closed, superseded, or issued by another gate. No work ran.
   */
  public data object RejectedNotCurrent : ReaderSessionAcceptance<Nothing>
}

/**
 * Linearizes reader-session replacement and the delivery of session events.
 *
 * One FIFO lease serializes every gate operation. [withCurrent] holds it until
 * its block completes, fails, or is canceled, so a later [start] or [close]
 * returns only after already accepted work released it. Once [start] or [close]
 * returns, work of the ended session is rejected without running.
 *
 * Event order is assigned when the reader issues an event. When a newer event
 * has committed before an older one arrives, the older one is delivered as
 * [ReaderEventRecency.REORDERED], so monotonic effects still converge while
 * latest-position effects stay unchanged.
 *
 * The caller owns every task. An accepted block never reenters the gate,
 * directly or through a coroutine it starts in its scope; the gate refuses such
 * a call instead of waiting for its own lease.
 *
 * Use one gate per logical reader owner. Independent gates do not arbitrate
 * each other's sessions.
 */
public class ReaderSessionGate {
  private val mutex = Mutex()
  private val leaseKey = object : CoroutineContext.Key<Lease> {}
  private val lease = Lease(leaseKey)
  private var current: ReaderSession? = null
  private var nextOrdinal = 0L
  private var lastCommittedOrdinal = -1L

  /**
   * Starts a new current session and ends the previous one.
   *
   * @throws IllegalStateException if an accepted block of this gate calls it.
   */
  public suspend fun start(): ReaderSession {
    refuseReentry()

    val session = ReaderSession()

    val previous = mutex.withLock {
      val replaced = current

      current = session
      nextOrdinal = 0L
      lastCommittedOrdinal = -1L

      replaced
    }

    previous?.end()

    return session
  }

  /**
   * Closes [session] if it is current. Otherwise nothing changes, so a
   * repeated, superseded, or foreign close is a no-op.
   *
   * @throws IllegalStateException if an accepted block of this gate calls it.
   */
  public suspend fun close(session: ReaderSession) {
    refuseReentry()

    val closed = mutex.withLock {
      val ended = current === session

      if (ended) {
        current = null
      }

      ended
    }

    if (closed) {
      session.end()
    }
  }

  /**
   * Runs [block] only while [event] belongs to the current session.
   *
   * The block receives the recency of the event and holds the lease until it
   * returns. Its normal return commits the event. A failure or cancellation of
   * the block propagates without committing, and a retry is classified again
   * from the gate state at that time.
   *
   * A cancellation of the caller that the block did not observe propagates once
   * the block has returned. The event stays committed, so a repeated delivery
   * is [ReaderEventRecency.REORDERED].
   *
   * @throws IllegalStateException if an accepted block of this gate calls it.
   */
  public suspend fun <Value> withCurrent(
    event: ReaderSessionEvent,
    block: suspend (ReaderEventRecency) -> Value,
  ): ReaderSessionAcceptance<Value> {
    refuseReentry()

    return mutex.withLock {
      if (current !== event.session) {
        return@withLock ReaderSessionAcceptance.RejectedNotCurrent
      }

      val recency =
        if (event.ordinal > lastCommittedOrdinal) {
          ReaderEventRecency.CURRENT
        } else {
          ReaderEventRecency.REORDERED
        }

      val value = withContext(lease) { deliver(event, recency, block) }

      ReaderSessionAcceptance.Accepted(value)
    }
  }

  /**
   * Runs a short state transition under the lease if [session] is current.
   *
   * The [block] may call its argument to issue the next event of the session,
   * but only before it returns. It must not suspend, block, perform I/O, or
   * call back into the gate.
   *
   * @throws IllegalStateException if an accepted block of this gate calls it,
   *   or if the event order of the session is exhausted.
   */
  internal suspend fun <Value> withCurrentSession(
    session: ReaderSession,
    block: (issue: () -> ReaderSessionEvent) -> Value,
  ): ReaderSessionAcceptance<Value> {
    refuseReentry()

    return mutex.withLock {
      if (current !== session) {
        return@withLock ReaderSessionAcceptance.RejectedNotCurrent
      }

      var open = true

      val issue = {
        check(open) { "An event was issued outside its transition." }

        nextEvent(session)
      }

      try {
        ReaderSessionAcceptance.Accepted(block(issue))
      } finally {
        open = false
      }
    }
  }

  private suspend fun <Value> deliver(
    event: ReaderSessionEvent,
    recency: ReaderEventRecency,
    block: suspend (ReaderEventRecency) -> Value,
  ): Value {
    val value = block(recency)

    if (recency == ReaderEventRecency.CURRENT) {
      lastCommittedOrdinal = event.ordinal
    }

    return value
  }

  private fun nextEvent(session: ReaderSession): ReaderSessionEvent {
    check(nextOrdinal < Long.MAX_VALUE) {
      "Reader session event order is exhausted."
    }

    val event = ReaderSessionEvent(session, nextOrdinal)

    nextOrdinal += 1L

    return event
  }

  /**
   * Fails instead of waiting for a lease that the calling task already holds.
   *
   * The lease marker is a coroutine context element, because the lease belongs
   * to a task rather than to a thread: the block may resume on any thread, and
   * its child coroutines inherit the marker. The key is per gate, so a block
   * may still use another gate.
   */
  private suspend fun refuseReentry() {
    check(currentCoroutineContext()[leaseKey] == null) {
      "An accepted delivery reentered its reader session gate."
    }
  }

  /** Marks the coroutine context of a block that holds the lease. */
  private class Lease(override val key: CoroutineContext.Key<Lease>) :
    CoroutineContext.Element
}
