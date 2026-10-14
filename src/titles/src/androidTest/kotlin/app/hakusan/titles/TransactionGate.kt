package app.hakusan.titles

import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeout

/**
 * The query dispatcher of a database, which runs its work on [Dispatchers.IO],
 * can hold one write transaction before it starts, and can wait until the
 * coroutines that Room started on it have completed.
 *
 * Room hands each transaction to this dispatcher as a runnable through an
 * executor view, and uses that view for nothing else. The view dispatches with
 * an empty context, so a dispatch without a [Job] is a transaction, while the
 * dispatch of a coroutine always carries one.
 */
internal class TransactionGate : CoroutineDispatcher() {
  private val hold = AtomicReference<CompletableDeferred<Runnable>?>()

  private val coroutines = Collections.synchronizedSet(HashSet<Job>())

  /**
   * Holds the next transaction instead of running it, and returns the deferred
   * that receives it. The caller then runs the transaction.
   */
  fun holdNextTransaction(): Deferred<Runnable> {
    val transaction = CompletableDeferred<Runnable>()

    check(hold.compareAndSet(null, transaction))

    return transaction
  }

  /**
   * Waits a bounded time until every coroutine that Room dispatched here has
   * completed.
   *
   * Room runs each invalidation refresh in its own coroutine on this
   * dispatcher, and a write that requests one dispatches it before the write
   * completes. After this call, no refresh of an earlier write is pending, so
   * none can read the changes of a later write.
   */
  suspend fun awaitIdle() {
    withTimeout(TIMEOUT_MILLIS) {
      do {
        val running = runningCoroutines()

        running.joinAll()
      } while (running.isNotEmpty())
    }
  }

  private fun runningCoroutines(): List<Job> =
    synchronized(coroutines) {
      coroutines.removeAll(Job::isCompleted)

      coroutines.toList()
    }

  override fun dispatch(context: CoroutineContext, block: Runnable) {
    val job = context[Job]

    if (job != null) {
      coroutines += job
    }

    val transaction = if (job == null) hold.getAndSet(null) else null

    if (transaction == null) {
      Dispatchers.IO.dispatch(context, block)
    } else {
      transaction.complete(block)
    }
  }

  private companion object {
    const val TIMEOUT_MILLIS = 10_000L
  }
}
