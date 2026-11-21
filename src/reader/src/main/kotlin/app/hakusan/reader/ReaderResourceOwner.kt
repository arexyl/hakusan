package app.hakusan.reader

import app.hakusan.reader.san.SanAbi
import app.hakusan.reader.san.SanAbiCalls
import app.hakusan.reader.san.SanBinding
import app.hakusan.reader.san.SanCalls
import app.hakusan.reader.san.SanLibrary
import app.hakusan.reader.san.checkSettled
import app.hakusan.reader.san.engineCreateFailure
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
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
 * **Closing.** [close] waits for the executing request, shuts the engine down,
 * and stops the worker. The sessions end first: a request after it is a defect
 * of the caller.
 */
public class ReaderResourceOwner
internal constructor(
  private val settings: ReaderEngineSettings,
  private val callsOn: (worker: Thread) -> SanCalls,
  private val bind: () -> SanBinding,
) {
  private val executor = Executors.newSingleThreadExecutor { task ->
    Thread(task, WORKER_NAME).apply { isDaemon = true }
  }

  /** The worker. Every use of [engine] runs on it. */
  internal val worker: CoroutineDispatcher = executor.asCoroutineDispatcher()

  /** Held by the executing request, and by a closing or a peak reset. */
  internal val gate: Mutex = Mutex()

  @Volatile private var closed = false

  // Confined to the worker.
  private var state: State = State.Absent

  /** An owner of the San library that the application packages. */
  public constructor(
    settings: ReaderEngineSettings
  ) : this(settings, ::SanAbiCalls, SanLibrary::bind)

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
   * Shuts the engine down and stops the worker. It waits for the executing
   * request, and a repeated call returns at once. It completes also when its
   * caller is canceled meanwhile.
   *
   * @throws IllegalStateException if the engine reports its shutdown pending:
   *   every job and lease is released by then, so the engine holds something
   *   that no one will release. The engine then stays closing.
   */
  public suspend fun close() {
    gate.withLock {
      if (closed) {
        return
      }

      closed = true

      try {
        withContext(NonCancellable + worker) { shutDown() }
      } finally {
        executor.shutdown()
      }
    }
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
    if (bind() != SanBinding.Established) {
      state = State.Unavailable

      return ReaderEngineAccess.Failed(ReaderContentFailure.ENGINE_UNAVAILABLE)
    }

    val san = callsOn(Thread.currentThread())
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

    return withContext(worker) {
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
  }

  /** Runs on the worker, with the gate held. */
  private fun shutDown() {
    val live = (state as? State.Live)?.access

    state = State.Closed

    if (live != null) {
      val status = live.san.engineShutdown(live.engine)

      check(status != SanAbi.STATUS_PENDING) {
        "The engine shutdown stays pending while the owner holds nothing."
      }

      checkSettled("engineShutdown", status)
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
