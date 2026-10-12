package app.hakusan.reader

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * The executor of content requests in tests, not a production orchestration.
 *
 * The supplied operation stands for decoding. The executor opens one input per
 * executed request, cancels the work when the request's cancellation intent
 * arrives, and acknowledges the terminal outcome only after the acquisition,
 * the operation, and the input are settled. It proves no native bound.
 */
internal class ControlledReaderExecutor(
  private val requests: ReaderContentRequests,
  private val source: ReaderEncodedSource,
) {
  /**
   * Executes the next waiting request with [operation] and returns it, or
   * returns null when [ReaderContentRequests.takeNext] supplies none.
   *
   * A cancellation of the caller propagates after the request has been
   * acknowledged as cancelled. Any other failure of the work is acknowledged as
   * an engine failure and propagates.
   */
  suspend fun runNext(
    operation: ControlledReaderOperation
  ): ReaderContentRequest? {
    val request = requests.takeNext() ?: return null

    val outcome =
      try {
        execute(request, operation)
      } catch (failure: Throwable) {
        requests.acknowledgeTerminal(request, abandoned(failure))

        throw failure
      }

    requests.acknowledgeTerminal(request, outcome)

    return request
  }

  /**
   * Runs the work in a child that the cancellation intent cancels. The scope
   * returns only after both children complete, so nothing of the request runs
   * once it returns.
   */
  private suspend fun execute(
    request: ReaderContentRequest,
    operation: ControlledReaderOperation,
  ): ReaderContentTerminal = coroutineScope {
    val work = async { acquire(request, operation) }

    val watcher =
      launch(start = CoroutineStart.UNDISPATCHED) {
        request.awaitCancellationRequested()
        work.cancel()
      }

    try {
      work.await()
    } catch (cancellation: CancellationException) {
      currentCoroutineContext().ensureActive()

      ReaderContentTerminal.Canceled
    } finally {
      watcher.cancel()
    }
  }

  private suspend fun acquire(
    request: ReaderContentRequest,
    operation: ControlledReaderOperation,
  ): ReaderContentTerminal =
    when (val opened = source.open(request.descriptor)) {
      is ReaderInputOpenResult.Failed ->
        ReaderContentTerminal.Failed(opened.failure.toContentFailure())

      is ReaderInputOpenResult.Opened ->
        opened.input.use { input ->
          if (requests.bindRepresentation(request, input.representation)) {
            operation.run(request, input)
          } else {
            ReaderContentTerminal.Canceled
          }
        }
    }

  private fun abandoned(failure: Throwable): ReaderContentTerminal =
    if (failure is CancellationException) {
      ReaderContentTerminal.Canceled
    } else {
      ReaderContentTerminal.Failed(ReaderContentFailure.ENGINE_FAILURE)
    }
}

/** The work that stands for decoding one executed request. */
internal fun interface ControlledReaderOperation {
  suspend fun run(
    request: ReaderContentRequest,
    input: ReaderByteInput,
  ): ReaderContentTerminal
}
