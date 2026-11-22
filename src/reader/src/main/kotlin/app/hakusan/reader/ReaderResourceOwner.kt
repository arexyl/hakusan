package app.hakusan.reader

import app.hakusan.reader.san.SanAbi
import app.hakusan.reader.san.SanBinding
import app.hakusan.reader.san.SanCalls
import app.hakusan.reader.san.SanExecution
import app.hakusan.reader.san.checkSettled
import app.hakusan.reader.san.engineCreateFailure
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The ledger of the native engine at one read, without any image content. */
public data class ReaderEngineCounters(
  /** The bytes of live blocks. */
  public val liveBytes: Long,
  /** The bytes that reservations hold and no block uses yet. */
  public val reservedBytes: Long,
  /** The highest live bytes since creation or the last reset. */
  public val peakLiveBytes: Long,
  /** The highest live plus reserved bytes since creation or the last reset. */
  public val peakCommittedBytes: Long,
  /** The live bytes of the input staging. */
  public val inputBytes: Long,
  /** The live bytes of native metadata and bookkeeping. */
  public val metadataBytes: Long,
  /** The live bytes of output leases and intermediates. */
  public val outputBytes: Long,
  public val contentCount: Long,
  public val jobCount: Long,
  public val leaseCount: Long,
)

/** The live engine as the worker uses it, or the reason why there is none. */
internal sealed interface ReaderEngineAccess {
  class Live(val san: SanCalls, val engine: Long) : ReaderEngineAccess

  data class Failed(val failure: ReaderContentFailure) : ReaderEngineAccess
}

/**
 * The process-local owner of the native engine and of its worker.
 *
 * **Worker.** One dedicated thread makes every engine call except a
 * cancellation request, because San takes no lock. Everything that the owner
 * knows about the engine is confined to that thread.
 *
 * **Engine.** The first use loads the library, establishes the binding, and
 * creates the one engine of the process under [settings]. A library that does
 * not load or does not match stays unavailable for the process. A creation that
 * the engine could not allocate fails its use alone, and the next use creates
 * again.
 *
 * **Gate.** The owner admits one executing request at a time across all content
 * owners, from taking the request until its acknowledgment, which keeps one
 * acquisition and one native job.
 *
 * **Targets.** The owner holds every target of the output copies until
 * presentation has returned it, also after the request that created it.
 *
 * **Strips.** Under [ReaderBackwardVariant.RETAIN_STRIPS], the owner keeps the
 * lease of a completed band after its job, until a job needs its room, its
 * session closes, or the owner closes.
 *
 * **Closing.** [close] waits for the executing request and shuts the engine
 * down. The worker stops once presentation has returned the last target. The
 * sessions end first: a request after it is a defect of the caller.
 */
public class ReaderResourceOwner
internal constructor(
  internal val settings: ReaderEngineSettings,
  internal val platform: ReaderPlatform,
) {
  private val executor = Executors.newSingleThreadExecutor { task ->
    Thread(task, WORKER_NAME).apply { isDaemon = true }
  }

  /** The worker. Every use of [engine] and of [targets] runs on it. */
  internal val worker: CoroutineDispatcher = executor.asCoroutineDispatcher()

  /** Held by the executing request, and by a closing or a peak reset. */
  internal val gate: Mutex = Mutex()

  internal val targets: ReaderTargetPool =
    ReaderTargetPool(platform, settings.targetBytes, settings.targetCount)

  /** The decoded strips, which only the worker uses. */
  internal val strips: ReaderStripStore = ReaderStripStore(settings)

  /**
   * Runs on the thread that returns a target, for the executing request. It
   * must be short, and it must neither block nor throw.
   */
  @Volatile internal var returnListener: (() -> Unit)? = null

  @Volatile private var closed = false

  // Confined to the worker.
  private var state: State = State.Absent
  private var stopping = false

  init {
    targets.onEmpty = {
      if (stopping) {
        executor.shutdown()
      }
    }
  }

  /** An owner of the San library that the application packages. */
  public constructor(
    settings: ReaderEngineSettings
  ) : this(settings, AndroidReaderPlatform)

  /**
   * Reads the ledger of the engine, or returns null while none is live. With
   * [resetPeak], the read then sets both peaks to their current values; it
   * waits for the gate first, so that a reset never falls into a request.
   *
   * @throws IllegalStateException after [close].
   */
  public suspend fun counters(
    resetPeak: Boolean = false
  ): ReaderEngineCounters? =
    if (resetPeak) {
      gate.withLock { readCounters(resetPeak = true) }
    } else {
      readCounters(resetPeak = false)
    }

  /**
   * Executes the waiting request of [requests] with an input of [source] and
   * returns it after its terminal acknowledgment, or returns null when
   * [ReaderContentRequests.takeNext] supplies none.
   *
   * It waits for the gate, so one request executes at a time across all content
   * owners. A cancellation of the request ends it as canceled. A cancellation
   * of the caller propagates after the request has been acknowledged as
   * canceled, and any other failure of the work propagates after it has been
   * acknowledged as an engine failure, both with nothing of the request left
   * running.
   */
  public suspend fun execute(
    requests: ReaderContentRequests,
    source: ReaderEncodedSource,
  ): ReaderContentRequest? {
    val request = requests.takeNext() ?: return null
    var execution: SanExecution? = null

    try {
      // From the take on, every failure acknowledges, also one of the
      // allocations of the execution.
      val outcome =
        try {
          SanExecution(this, requests, source, request)
            .also { execution = it }
            .run()
        } catch (failure: Throwable) {
          requests.acknowledgeTerminal(request, abandoned(failure))

          throw failure
        }

      requests.acknowledgeTerminal(request, outcome)
    } finally {
      execution?.releaseGate()
    }

    return request
  }

  /**
   * Shuts the engine down. It waits for the executing request, and a repeated
   * call returns at once. It completes also when its caller is canceled
   * meanwhile. The worker stops with the return of the last target, at once
   * when presentation holds none.
   *
   * @throws IllegalStateException if the engine reports its shutdown pending:
   *   every job and lease is released by then, so the engine holds something
   *   that no one will release. The engine then stays closing.
   */
  public suspend fun close() {
    // The wait for the gate ends with the executing request, which the end of
    // its session cancels, so a canceled caller still closes.
    withContext(NonCancellable) {
      gate.withLock {
        if (!closed) {
          closed = true

          withContext(worker) {
            try {
              shutDown()
            } finally {
              stopping = true

              if (targets.isEmpty) {
                executor.shutdown()
              }
            }
          }
        }
      }
    }
  }

  /**
   * Returns the display lease [ordinal] of [target] to the worker. Any thread
   * calls it, from the release of that lease.
   *
   * An executing request hears of it between two steps, through
   * [returnListener]. A task of the worker applies it in any case, because the
   * request may have ended meanwhile, and then tells the request that executes
   * by then: a request that began between the two would otherwise wait for room
   * that is already free.
   */
  internal fun returnTarget(target: ReaderTargetPool.Target, ordinal: Int) {
    targets.post(target, ordinal)
    returnListener?.invoke()

    try {
      executor.execute {
        targets.applyReturns()
        returnListener?.invoke()
      }
    } catch (_: RejectedExecutionException) {
      // The worker stops only with an empty pool, so this lease was never
      // handed over, and its return would change nothing.
    }
  }

  /**
   * Brings the release of the strips of [scope] to the worker. The thread that
   * closes the session of [scope] calls it.
   */
  internal fun dropStrips(scope: ReaderContentRequests) {
    try {
      executor.execute { strips.drop(scope) }
    } catch (_: RejectedExecutionException) {
      // The shutdown released every strip before the worker stopped.
    }
  }

  /** Fails a use of the owner after [close]. The caller holds the gate. */
  internal fun checkOpen() {
    check(!closed) { CLOSED }
  }

  /**
   * The live engine, which the first call creates. Only the worker calls it.
   *
   * @throws IllegalStateException after [close], and for a defect of creation.
   */
  internal fun engine(): ReaderEngineAccess =
    when (val current = state) {
      State.Absent -> create()
      is State.Live -> current.access

      State.Unavailable ->
        ReaderEngineAccess.Failed(ReaderContentFailure.ENGINE_UNAVAILABLE)

      State.Closed -> error(CLOSED)
    }

  private fun create(): ReaderEngineAccess {
    if (platform.bind() != SanBinding.Established) {
      state = State.Unavailable

      return ReaderEngineAccess.Failed(ReaderContentFailure.ENGINE_UNAVAILABLE)
    }

    val san = platform.calls(Thread.currentThread())
    val engine = LongArray(1)

    val status =
      san.engineCreate(
        aggregateBudgetBytes = settings.aggregateBudgetBytes,
        contentCount = settings.contentCount.toLong(),
        jobCount = settings.jobCount.toLong(),
        contentLengthBytes = settings.contentLengthBytes,
        inputPortionBytes = settings.inputPortionBytes.toLong(),
        imageAxisPixels = settings.imageAxisPixels.toLong(),
        imageAreaPixels = settings.imageAreaPixels,
        metadataBytes = settings.metadataBytes,
        metadataExpandedBytes = settings.metadataExpandedBytes,
        metadataPayloadBytes = settings.metadataPayloadBytes,
        heldChunkBytes = settings.heldChunkBytes,
        iccTagCount = settings.iccTagCount.toLong(),
        exifEntryCount = settings.exifEntryCount.toLong(),
        outputBytes = settings.outputBytes,
        outputLeaseBytes = settings.outputLeaseBytes,
        outputLeaseCount = settings.outputLeaseCount.toLong(),
        engineOut = engine,
      )

    engineCreateFailure(status)?.let {
      return ReaderEngineAccess.Failed(it)
    }

    val access = ReaderEngineAccess.Live(san, engine[0])

    state = State.Live(access)

    return access
  }

  private suspend fun readCounters(resetPeak: Boolean): ReaderEngineCounters? {
    check(!closed) { CLOSED }

    return try {
      readOnWorker(resetPeak)
    } catch (cancellation: CancellationException) {
      // A close that stopped the worker meanwhile refuses the dispatch as a
      // cancellation, which this caller did not request.
      currentCoroutineContext().ensureActive()

      throw IllegalStateException(CLOSED, cancellation)
    }
  }

  private suspend fun readOnWorker(resetPeak: Boolean): ReaderEngineCounters? =
    withContext(worker) {
      val live = (state as? State.Live)?.access ?: return@withContext null
      val counters = LongArray(SanAbi.ENGINE_COUNTERS_LENGTH)

      checkSettled(
        "engineCounters",
        live.san.engineCounters(live.engine, resetPeak, counters),
      )

      ReaderEngineCounters(
        liveBytes = counters[SanAbi.ENGINE_COUNTERS_LIVE_BYTES],
        reservedBytes = counters[SanAbi.ENGINE_COUNTERS_RESERVED_BYTES],
        peakLiveBytes = counters[SanAbi.ENGINE_COUNTERS_PEAK_LIVE_BYTES],
        peakCommittedBytes =
          counters[SanAbi.ENGINE_COUNTERS_PEAK_COMMITTED_BYTES],
        inputBytes = counters[SanAbi.ENGINE_COUNTERS_INPUT_BYTES],
        metadataBytes = counters[SanAbi.ENGINE_COUNTERS_METADATA_BYTES],
        outputBytes = counters[SanAbi.ENGINE_COUNTERS_OUTPUT_BYTES],
        contentCount = counters[SanAbi.ENGINE_COUNTERS_CONTENT_COUNT],
        jobCount = counters[SanAbi.ENGINE_COUNTERS_JOB_COUNT],
        leaseCount = counters[SanAbi.ENGINE_COUNTERS_LEASE_COUNT],
      )
    }

  /** The outcome of a request whose execution threw [failure]. */
  private fun abandoned(failure: Throwable): ReaderContentTerminal =
    if (failure is CancellationException) {
      ReaderContentTerminal.Canceled
    } else {
      ReaderContentTerminal.Failed(ReaderContentFailure.ENGINE_FAILURE)
    }

  /** Runs on the worker, with the gate held. */
  private fun shutDown() {
    val live = (state as? State.Live)?.access

    state = State.Closed

    if (live != null) {
      // The strips are the leases that the owner itself still holds. The
      // engine shuts down also after a strip that failed its release.
      try {
        strips.clear()
      } finally {
        val status = live.san.engineShutdown(live.engine)

        check(status != SanAbi.STATUS_PENDING) {
          "The engine shutdown stays pending while the owner holds nothing."
        }

        checkSettled("engineShutdown", status)
      }
    }
  }

  private sealed interface State {
    data object Absent : State

    class Live(val access: ReaderEngineAccess.Live) : State

    data object Unavailable : State

    data object Closed : State
  }

  private companion object {
    const val WORKER_NAME = "hakusan-reader"
    const val CLOSED = "The reader resource owner is closed."
  }
}
