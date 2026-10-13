package app.hakusan.titles

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * The values of one collection of [flow], started in [scope] at construction.
 *
 * Each [next] and [failure] waits a bounded time for the next event of the
 * collection. The absence of a value is proven only by a later event that must
 * come next, never by a sleep. A collection that fails with a stored-state
 * violation ends, and its violation is the event after its last value. Any
 * other failure of the collection fails [scope].
 */
internal class ObservedValues<T : Any>(scope: CoroutineScope, flow: Flow<T>) {
  private val events = Channel<T>(Channel.UNLIMITED)

  private val collection: Job = scope.launch {
    try {
      flow.collect(events::send)
      events.close()
    } catch (violation: StoredStateViolationException) {
      events.close(violation)
    }
  }

  /** Returns the next value, or fails when the collection ended instead. */
  suspend fun next(): T = withTimeout(TIMEOUT_MILLIS) { events.receive() }

  /**
   * Returns the violation that ended the collection, after checking that the
   * collection emitted no value since the last one that [next] returned.
   */
  suspend fun failure(): StoredStateViolationException {
    val event = withTimeout(TIMEOUT_MILLIS) { events.receiveCatching() }

    if (event.isSuccess) {
      throw AssertionError(
        "The observation emitted a value instead of failing."
      )
    }

    val cause = event.exceptionOrNull()

    return cause as? StoredStateViolationException
      ?: throw AssertionError("The observation ended without a violation.")
  }

  /** Cancels the collection and waits until it has stopped. */
  suspend fun cancel() {
    collection.cancelAndJoin()
  }

  private companion object {
    const val TIMEOUT_MILLIS = 10_000L
  }
}
