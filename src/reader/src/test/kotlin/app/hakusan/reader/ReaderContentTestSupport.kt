package app.hakusan.reader

import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertInstanceOf

/** Addresses the unit at [unitIndex] of the first chapter. */
internal fun ReaderTestBook.descriptor(
  unitIndex: Int
): ReaderContentDescriptor =
  ReaderContentDescriptor.of(content[0], content[0].positionAt(unitIndex))

/** A completion without warnings. */
internal fun completed(): ReaderContentTerminal =
  ReaderContentTerminal.Completed.of()

/** A lease of one 100 × 4000 segment whose top 100 rows are final. */
internal fun testLease(
  representation: ReaderRepresentation = ReaderRepresentation(),
  region: ReaderImageRegion = ReaderImageRegion(0, 0, 100, 100),
  release: () -> Unit,
): ReaderOutputLease =
  ReaderOutputLease(
    representation,
    ReaderImageCoverage(100, 4000, region, finalQuality = true),
    release,
  )

/** Asserts that the suspending [block] fails with [Failure] and returns it. */
internal suspend inline fun <reified Failure : Throwable> assertSuspendFails(
  block: suspend () -> Unit
): Failure {
  val failure = runCatching { block() }.exceptionOrNull()

  return assertInstanceOf(Failure::class.java, failure)
}

/**
 * Reads [input] into [buffer] until a terminal read, waiting for live input,
 * and maps that read onto a terminal outcome.
 */
internal suspend fun readToEnd(
  input: ReaderByteInput,
  buffer: ByteArray,
): ReaderContentTerminal {
  while (true) {
    when (val read = input.read(buffer, 0, buffer.size)) {
      is ReaderInputRead.Data,
      ReaderInputRead.NoOp -> {}

      ReaderInputRead.AwaitInput -> input.awaitInput()

      ReaderInputRead.EndOfInput -> return completed()

      is ReaderInputRead.InputFailure ->
        return ReaderContentTerminal.Failed(read.failure.toContentFailure())

      ReaderInputRead.Canceled -> return ReaderContentTerminal.Canceled
    }
  }
}

/** Waits on [input] for bytes that never arrive, until cancellation. */
internal suspend fun awaitWithheld(input: ReaderByteInput): Nothing {
  check(input.read(ByteArray(1), 0, 1) == ReaderInputRead.AwaitInput)
  input.awaitInput()

  error("The withheld input arrived.")
}

/**
 * An input that counts every close call, separately from the idempotent close
 * effect of [input], and runs [onClose] before each call reaches [input].
 */
internal class TrackedReaderInput(
  val input: ControlledReaderInput,
  private val onClose: () -> Unit = {},
) : ReaderByteInput by input {
  private val calls = AtomicInteger()

  /** The number of close calls so far. */
  val closeCalls: Int
    get() = calls.get()

  override fun close() {
    calls.incrementAndGet()
    onClose()
    input.close()
  }
}
