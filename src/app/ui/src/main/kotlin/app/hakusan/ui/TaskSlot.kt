package app.hakusan.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * Main-confined ownership of at most one current coroutine task.
 *
 * Callers own semantic state and invoke every method from the same UI domain.
 * Cancellation and unexpected failures are not converted into result values.
 */
internal class TaskSlot {
  private var currentJob: Job? = null

  fun <Result> start(
    scope: CoroutineScope,
    onStarted: () -> Unit,
    request: suspend () -> Result,
    accept: (Result) -> Unit,
  ) {
    if (currentJob != null) {
      return
    }
    launch(
      scope = scope,
      previous = null,
      onStarted = onStarted,
      request = request,
      accept = accept,
    )
  }

  fun <Result> replace(
    scope: CoroutineScope,
    onStarted: () -> Unit,
    request: suspend () -> Result,
    accept: (Result) -> Unit,
  ) {
    launch(
      scope = scope,
      previous = currentJob,
      onStarted = onStarted,
      request = request,
      accept = accept,
    )
  }

  fun cancel() {
    val canceled = currentJob
    currentJob = null
    canceled?.cancel()
  }

  private fun <Result> launch(
    scope: CoroutineScope,
    previous: Job?,
    onStarted: () -> Unit,
    request: suspend () -> Result,
    accept: (Result) -> Unit,
  ) {
    val launched = scope.launch(start = CoroutineStart.LAZY) {
      val ownerJob = currentCoroutineContext().job
      try {
        val result = request()
        if (currentJob === ownerJob && ownerJob.isActive) {
          accept(result)
        }
      } finally {
        if (currentJob === ownerJob) {
          currentJob = null
        }
      }
    }

    currentJob = launched
    try {
      onStarted()
    } catch (failure: Throwable) {
      if (currentJob === launched) {
        currentJob = previous
      }
      launched.cancel()
      throw failure
    }
    previous?.cancel()
    if (!launched.start() && currentJob === launched) {
      currentJob = null
    }
  }
}
