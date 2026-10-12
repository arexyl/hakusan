package app.hakusan.reader

import java.util.Objects
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * A scripted input for the byte-input contract, not a transport.
 *
 * The test plays the producer: it makes a prefix of [bytes] available, then
 * ends or fails the input. The input declares rewind when [retainedFrom] is
 * set, retaining every received byte from that offset on. Each transfer
 * delivers at most [maxTransfer] bytes, so short reads are the norm.
 */
internal class ControlledReaderInput(
  bytes: ByteArray,
  length: Long? = bytes.size.toLong(),
  private val retainedFrom: Long? = null,
  seek: Boolean = false,
  private val maxTransfer: Int = 3,
  override val representation: ReaderRepresentation = ReaderRepresentation(),
) : ReaderByteInput {
  override val capabilities =
    ReaderInputCapabilities(length, rewind = retainedFrom != null, seek)

  private val bytes = bytes.copyOf()
  private val monitor = Any()
  private var changed = CompletableDeferred<Unit>()
  private var received = 0
  private var producer: Producer = Producer.Live
  private var terminal: ReaderInputRead? = null
  private var cursor = 0L
  private var operating = false
  private var closed = false
  private var heldTransfer: CompletableDeferred<Unit>? = null
  private var positioningFault: ReaderInputSeek? = null

  var closeCount = 0
    private set

  override val position: Long
    get() = synchronized(monitor) { cursor }

  /** Makes the first [count] scripted bytes available. */
  fun receiveThrough(count: Int) {
    produce {
      require(count in received..bytes.size) { "Bytes arrive in order." }

      received = count
    }
  }

  /** Reports successful completion of the producer. */
  fun end() {
    produce { producer = Producer.Ended }
  }

  /** Reports a producer failure after the bytes received so far. */
  fun fail(failure: ReaderAcquisitionFailure) {
    produce { producer = Producer.Failed(failure) }
  }

  /**
   * Suspends the next data transfer after it has consumed its bytes, like a
   * blocking read inside `withContext`. Completing the returned signal lets the
   * transfer return.
   */
  fun holdNextTransfer(): CompletableDeferred<Unit> =
    synchronized(monitor) {
      CompletableDeferred<Unit>().also { heldTransfer = it }
    }

  /**
   * Makes the next attempted repositioning fail with [failure]. The input keeps
   * its cursor unless [invalidates] is set.
   */
  fun failNextPositioning(
    failure: ReaderAcquisitionFailure,
    invalidates: Boolean,
  ) {
    synchronized(monitor) {
      positioningFault =
        if (invalidates) {
          ReaderInputSeek.Invalidated(failure)
        } else {
          ReaderInputSeek.Failed(failure)
        }
    }
  }

  override suspend fun read(
    destination: ByteArray,
    offset: Int,
    length: Int,
  ): ReaderInputRead {
    Objects.checkFromIndexSize(offset, length, destination.size)

    return operation {
      if (length == 0) return@operation ReaderInputRead.NoOp

      val (outcome, hold) =
        synchronized(monitor) {
          val outcome = transfer(destination, offset, length)

          val hold = heldTransfer.takeIf { outcome is ReaderInputRead.Data }

          if (hold != null) heldTransfer = null

          outcome to hold
        }

      hold?.await()

      outcome
    }
  }

  override suspend fun awaitInput() {
    operation {
      while (true) {
        val signal =
          synchronized(monitor) { changed.takeIf { awaiting() } } ?: break

        signal.await()
      }
    }
  }

  override suspend fun seek(position: Long): ReaderInputSeek {
    require(position >= 0) { "The target must not be negative." }

    return operation { synchronized(monitor) { reposition(position) } }
  }

  override fun close() {
    synchronized(monitor) {
      check(!operating) { "Settle every operation before closing." }

      if (!closed) {
        closed = true
        closeCount++
      }
    }
  }

  private suspend inline fun <T> operation(block: () -> T): T {
    synchronized(monitor) {
      check(!closed) { "The input is closed." }
      check(!operating) { "Input operations must be serialized." }

      operating = true
    }

    try {
      return block()
    } catch (cancellation: CancellationException) {
      synchronized(monitor) {
        if (terminal == null) terminal = ReaderInputRead.Canceled
      }

      throw cancellation
    } finally {
      synchronized(monitor) { operating = false }
    }
  }

  private fun transfer(
    destination: ByteArray,
    offset: Int,
    length: Int,
  ): ReaderInputRead {
    terminal?.let {
      return it
    }

    val declared = capabilities.length

    val valid = minOf(received.toLong(), declared ?: Long.MAX_VALUE)

    if (cursor < valid) {
      val count = minOf(length, maxTransfer, (valid - cursor).toInt())

      val start = cursor.toInt()

      bytes.copyInto(destination, offset, start, start + count)
      cursor += count

      return ReaderInputRead.Data(count)
    }

    val state = producer

    val failure =
      when {
        state is Producer.Failed -> state.failure

        declared != null && received > declared ->
          ReaderAcquisitionFailure.FAILED

        state == Producer.Ended && declared != null && valid != declared ->
          ReaderAcquisitionFailure.FAILED

        state == Producer.Ended -> return ReaderInputRead.EndOfInput

        else -> return ReaderInputRead.AwaitInput
      }

    return ReaderInputRead.InputFailure(failure).also { terminal = it }
  }

  private fun reposition(target: Long): ReaderInputSeek {
    if (terminal != null) return ReaderInputSeek.Terminal

    val history = retainedFrom
    val declared = capabilities.length
    val fault = positioningFault
    val state = producer

    val outcome =
      when {
        history == null && !capabilities.seek -> ReaderInputSeek.Unsupported

        declared != null && target > declared -> ReaderInputSeek.BeyondEnd

        target > received && state == Producer.Live ->
          ReaderInputSeek.NotYetAvailable

        target > received && state is Producer.Failed ->
          ReaderInputSeek.Failed(state.failure)

        target > received -> ReaderInputSeek.BeyondEnd

        !capabilities.seek && history != null && target < history ->
          ReaderInputSeek.HistoryUnavailable

        else -> fault ?: ReaderInputSeek.Positioned
      }

    when (outcome) {
      ReaderInputSeek.Positioned -> cursor = target

      is ReaderInputSeek.Invalidated ->
        terminal = ReaderInputRead.InputFailure(outcome.failure)

      else -> {}
    }

    if (outcome === fault) positioningFault = null

    return outcome
  }

  private fun awaiting(): Boolean =
    terminal == null && producer == Producer.Live && cursor >= received

  private inline fun produce(change: () -> Unit) {
    val signal =
      synchronized(monitor) {
        check(producer == Producer.Live) {
          "The producer outcome is already established."
        }

        change()

        changed.also { changed = CompletableDeferred() }
      }

    signal.complete(Unit)
  }

  private sealed interface Producer {
    data object Live : Producer

    data object Ended : Producer

    data class Failed(val failure: ReaderAcquisitionFailure) : Producer
  }
}
