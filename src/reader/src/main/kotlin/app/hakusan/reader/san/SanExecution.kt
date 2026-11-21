package app.hakusan.reader.san

import app.hakusan.reader.ReaderByteInput
import app.hakusan.reader.ReaderContentFailure
import app.hakusan.reader.ReaderContentRequest
import app.hakusan.reader.ReaderContentRequests
import app.hakusan.reader.ReaderContentTerminal
import app.hakusan.reader.ReaderEncodedSource
import app.hakusan.reader.ReaderEngineAccess
import app.hakusan.reader.ReaderInputOpenResult
import app.hakusan.reader.ReaderInputRead
import app.hakusan.reader.ReaderResourceOwner
import app.hakusan.reader.toContentFailure
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The execution of one content request through the native engine: one
 * acquisition, one content, and one job.
 *
 * **Acquisition.** A coroutine waits for the owner's gate, opens the input, and
 * binds its representation. A cancellation of the request cancels it.
 *
 * **Decoding.** The worker then steps the job until it ends, and it suspends
 * only for an event of its mailbox. A pump on the IO dispatcher reads one
 * portion into the transfer array and posts each read; it reads the next one
 * once the worker has fed the array. The worker feeds only after the job needs
 * input, so a read that arrives earlier waits in one slot.
 *
 * **Cancellation.** A watcher off the worker requests the native cancellation
 * and wakes the worker, which collects it with an immediate step. Nothing
 * cancels the coroutine that steps the job, except the caller.
 *
 * **Cleanup.** On every path, the worker settles the pump, releases its claim
 * on the lease, closes the content and the input, joins the watcher, and only
 * then releases the job: every cancellation has returned by then.
 *
 * It publishes no output yet.
 */
internal class SanExecution(
  private val owner: ReaderResourceOwner,
  private val requests: ReaderContentRequests,
  private val source: ReaderEncodedSource,
  private val request: ReaderContentRequest,
) {
  private val transfer = ByteArray(owner.settings.inputPortionBytes)

  // Bounded by construction: one read of the pump at a time, and cancellations
  // that the worker collects once.
  private val mailbox = Channel<Event>(Channel.UNLIMITED)
  private val fedSignal = Channel<Unit>(Channel.CONFLATED)

  @Volatile private var gateHeld = false
  @Volatile private var input: ReaderByteInput? = null

  /** The job as the watcher needs it, from its request until its release. */
  @Volatile private var live: LiveJob? = null

  // Confined to the worker from here on.
  private val report = LongArray(SanAbi.JOB_REPORT_LENGTH)
  private var content = SanAbi.NO_HANDLE
  private var job = SanAbi.NO_HANDLE
  private var lease = SanAbi.NO_HANDLE
  private var fedBytes = 0L
  private var pendingInput: ReaderInputRead? = null
  private var collect = false
  private var cancelCause: ReaderContentFailure? = null
  private var jobSettled = false
  private var pumping: Job? = null

  /**
   * Executes the request to its one terminal outcome, with nothing of it left
   * running. The caller acknowledges the outcome and then calls [releaseGate].
   *
   * @throws CancellationException when the caller was canceled.
   */
  suspend fun run(): ReaderContentTerminal = coroutineScope {
    val acquisition = async { acquire() }
    val watcher = launch(Dispatchers.Default) { watch(acquisition) }

    try {
      val unacquired =
        try {
          acquisition.await()
        } catch (_: CancellationException) {
          // Without a cancellation of the caller, the watcher canceled it.
          ensureActive()

          ReaderContentTerminal.Canceled
        }

      unacquired ?: decode(watcher)
    } finally {
      // A canceled caller leaves the await before the acquisition has ended,
      // and an acquisition that still runs may yet store its input.
      withContext(NonCancellable) {
        acquisition.cancelAndJoin()
        watcher.cancelAndJoin()
        closeInput()
      }
    }
  }

  /** Unlocks the owner's gate if this execution holds it. */
  fun releaseGate() {
    if (gateHeld) {
      gateHeld = false
      owner.gate.unlock()
    }
  }

  /** Returns null once the input is open and bound, or the early outcome. */
  private suspend fun acquire(): ReaderContentTerminal? {
    owner.gate.lock()
    gateHeld = true
    owner.checkOpen()

    // The field holds the input from here on, so no cancellation loses it.
    val opened =
      when (val opened = foreign { source.open(request.descriptor) }) {
        is ReaderInputOpenResult.Failed ->
          return ReaderContentTerminal.Failed(opened.failure.toContentFailure())

        is ReaderInputOpenResult.Opened -> opened.input.also { input = it }
      }

    return if (requests.bindRepresentation(request, opened.representation)) {
      null
    } else {
      ReaderContentTerminal.Canceled
    }
  }

  private suspend fun watch(acquisition: Job) {
    request.awaitCancellationRequested()

    val job = live

    if (job == null) {
      acquisition.cancel()
    } else {
      checkSettled("jobCancel", job.san.jobCancel(job.handle))
      mailbox.trySend(Event.Cancel)
    }
  }

  private suspend fun decode(watcher: Job): ReaderContentTerminal =
    withContext(owner.worker) {
      when (val access = owner.engine()) {
        is ReaderEngineAccess.Failed ->
          ReaderContentTerminal.Failed(access.failure)

        is ReaderEngineAccess.Live -> decodeOn(access, watcher)
      }
    }

  private suspend fun CoroutineScope.decodeOn(
    engine: ReaderEngineAccess.Live,
    watcher: Job,
  ): ReaderContentTerminal {
    val san = engine.san
    val handle = LongArray(1)

    contentOpenFailure(san.contentOpen(engine.engine, handle))?.let {
      return ReaderContentTerminal.Failed(it)
    }

    content = handle[0]

    try {
      val region = request.region

      val requested =
        san.jobRequest(
          content,
          left = region?.left ?: 0,
          top = region?.top ?: 0,
          right = region?.right ?: 0,
          bottom = region?.bottom ?: 0,
          maxOutputWidth = 0,
          handle,
        )

      jobRequestFailure(requested)?.let {
        return ReaderContentTerminal.Failed(it)
      }

      job = handle[0]
      live = LiveJob(san, job)

      // The watcher reads the job after the intent, and this read follows the
      // job, so one of the two sees a cancellation at any moment.
      if (request.cancellationRequested) {
        cancel(san)
      }

      val open = checkNotNull(input) { "The acquisition left no input." }

      pumping = launch(Dispatchers.IO) { pump(open) }

      val status = steps(san)

      val outcome =
        jobTerminal(status, report[SanAbi.JOB_REPORT_WARNINGS], cancelCause)

      jobSettled = true

      return outcome
    } finally {
      withContext(NonCancellable) { cleanUp(san, watcher) }
    }
  }

  /** Steps the job to its terminal status, which it returns unchecked. */
  private suspend fun steps(san: SanCalls): Int {
    var status = step(san)

    while (continues(status)) {
      while (true) {
        route(san, mailbox.tryReceive().getOrNull() ?: break)
      }

      status =
        when {
          collect -> collectingStep(san)
          status == SanAbi.STATUS_PROGRESS -> step(san)

          else ->
            when (val read = pendingInput.also { pendingInput = null }) {
              null -> {
                route(san, mailbox.receive())

                status
              }

              is ReaderInputRead.Data -> {
                if (feed(san, read.count, endOfInput = false)) {
                  fedSignal.trySend(Unit)
                }

                step(san)
              }

              ReaderInputRead.EndOfInput -> {
                feed(san, count = 0, endOfInput = true)

                step(san)
              }

              else -> error("The slot held a read that is no input.")
            }
        }
    }

    return status
  }

  /**
   * The one function that acts on an event, so that none meets a wrong state.
   */
  private fun route(san: SanCalls, event: Event) {
    when (event) {
      Event.Cancel -> cancel(san)

      is Event.Input ->
        when (val read = event.read) {
          is ReaderInputRead.Data,
          ReaderInputRead.EndOfInput -> pendingInput = read

          is ReaderInputRead.InputFailure ->
            cancel(san, read.failure.toContentFailure())

          ReaderInputRead.Canceled -> cancel(san)

          ReaderInputRead.NoOp,
          ReaderInputRead.AwaitInput ->
            error("The pump posted a read without an outcome.")
        }
    }
  }

  private fun step(san: SanCalls): Int {
    val status = san.jobStep(job, report)

    // The claim on a lease starts with the first report that names it. A
    // status that writes no report leaves the last one in place.
    if (lease == SanAbi.NO_HANDLE) {
      lease = report[SanAbi.JOB_REPORT_LEASE]
    }

    return status
  }

  /** The step after a cancellation, which ends the job before any byte. */
  private fun collectingStep(san: SanCalls): Int {
    val status = step(san)

    check(!continues(status)) {
      "A canceled job continued with the status $status."
    }

    return status
  }

  /** Feeds the transfer array and returns whether the content took it. */
  private fun feed(san: SanCalls, count: Int, endOfInput: Boolean): Boolean {
    val status =
      san.contentFeed(content, fedBytes, transfer, 0, count, endOfInput)

    val failure = contentFeedFailure(status)

    if (failure == null) {
      fedBytes += count
    } else {
      cancel(san, failure)
    }

    return failure == null
  }

  /**
   * Requests the native cancellation once, for [cause] or for a cancellation of
   * the request. The first reason stays.
   */
  private fun cancel(san: SanCalls, cause: ReaderContentFailure? = null) {
    if (collect) {
      return
    }

    checkSettled("jobCancel", san.jobCancel(job))

    cancelCause = cause
    collect = true
  }

  private suspend fun pump(input: ReaderByteInput) {
    while (true) {
      val read = foreign { input.read(transfer, 0, transfer.size) }

      if (read == ReaderInputRead.AwaitInput) {
        foreign { input.awaitInput() }

        continue
      }

      mailbox.trySend(Event.Input(read))

      if (read !is ReaderInputRead.Data) {
        return
      }

      // The array is the worker's until it has fed these bytes.
      fedSignal.receive()
    }
  }

  /**
   * Calls the source or the input. A cancellation that [call] throws while this
   * coroutine is active is its own, as from a timeout inside it. It would end
   * this coroutine in silence, with the worker left waiting, so it is a defect.
   */
  private suspend inline fun <T> foreign(call: () -> T): T =
    try {
      call()
    } catch (cancellation: CancellationException) {
      currentCoroutineContext().ensureActive()

      throw IllegalStateException(
        "The source or the input threw a cancellation of its own.",
        cancellation,
      )
    }

  /** Runs every step whatever an earlier one threw, in the contract's order. */
  private suspend fun cleanUp(san: SanCalls, watcher: Job) {
    val failures = mutableListOf<Throwable>()

    settle(failures) { pumping?.cancelAndJoin() }

    // A job that no step ended ends as canceled by the close of its content.
    settle(failures) {
      if (job != SanAbi.NO_HANDLE && !jobSettled) {
        closeContent(san)
      }
    }

    settle(failures) {
      if (lease != SanAbi.NO_HANDLE) {
        checkSettled("outputRelease", san.outputRelease(lease))
      }
    }

    settle(failures) { closeContent(san) }
    settle(failures) { closeInput() }
    settle(failures) { watcher.cancelAndJoin() }

    settle(failures) {
      if (job != SanAbi.NO_HANDLE) {
        checkSettled("jobRelease", san.jobRelease(job))
      }
    }

    mailbox.close()

    while (mailbox.tryReceive().isSuccess) {
      // Nothing of this request reaches the next one.
    }

    val first = failures.firstOrNull() ?: return

    failures.drop(1).forEach(first::addSuppressed)

    throw first
  }

  private fun closeContent(san: SanCalls) {
    val open = content

    if (open != SanAbi.NO_HANDLE) {
      content = SanAbi.NO_HANDLE
      checkSettled("contentClose", san.contentClose(open))
    }
  }

  private fun closeInput() {
    val open = input

    input = null
    open?.close()
  }

  private inline fun settle(
    failures: MutableList<Throwable>,
    step: () -> Unit,
  ) {
    try {
      step()
    } catch (failure: Throwable) {
      failures += failure
    }
  }

  private fun continues(status: Int): Boolean =
    status == SanAbi.STATUS_INPUT_NEEDED || status == SanAbi.STATUS_PROGRESS

  private class LiveJob(val san: SanCalls, val handle: Long)

  private sealed interface Event {
    class Input(val read: ReaderInputRead) : Event

    data object Cancel : Event
  }
}
