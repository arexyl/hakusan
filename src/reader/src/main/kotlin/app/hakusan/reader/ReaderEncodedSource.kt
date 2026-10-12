package app.hakusan.reader

/**
 * One provider unit of current chapter content, addressed for acquisition.
 *
 * It carries the unit's position and its opaque reference.
 */
public class ReaderContentDescriptor
private constructor(
  public val position: ReaderPosition,
  public val reference: String,
) {
  public companion object {
    /** Addresses the unit at [position], which [content] must contain. */
    public fun of(
      content: ReaderChapterContent,
      position: ReaderPosition,
    ): ReaderContentDescriptor {
      require(content.contains(position)) {
        "The position must name a unit of the content."
      }

      return ReaderContentDescriptor(
        position,
        content.units[position.unitIndex].reference,
      )
    }
  }
}

/** The outcome of [ReaderEncodedSource.open]. */
public sealed interface ReaderInputOpenResult {
  /** The caller now owns [input] and closes it. */
  public data class Opened(public val input: ReaderByteInput) :
    ReaderInputOpenResult

  /** No input was opened. */
  public data class Failed(public val failure: ReaderAcquisitionFailure) :
    ReaderInputOpenResult
}

/**
 * Acquires encoded input for provider units. The application implements it, and
 * the reader never learns where the content comes from.
 *
 * The caller owns the task. Cancellation propagates, and unexpected defects
 * propagate. Expected acquisition outcomes are values.
 *
 * **Ownership obligation.** An implementation never returns an owned input as
 * the result of a cancellable suspension, such as a `withContext` block that
 * opens the input. Cancellation delivered when that suspension resumes discards
 * its result, and the input would leak. The implementation keeps the acquired
 * input reachable outside the suspension instead, and it closes that input when
 * its caller is cancelled.
 */
public fun interface ReaderEncodedSource {
  /** Opens a new input for [descriptor]. Each call is a new acquisition. */
  public suspend fun open(
    descriptor: ReaderContentDescriptor
  ): ReaderInputOpenResult
}
