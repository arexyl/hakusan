package app.hakusan.reader

/**
 * The opaque identity of one logical byte sequence or acquisition attempt.
 *
 * The source adapter issues it. Equality is identity, so a token never proves
 * that another fetch returns identical bytes. It is process-local and never
 * persisted.
 */
public class ReaderRepresentation

/**
 * The capabilities that one input declares, each independently.
 *
 * Sequential reading is the minimum and is always available. Positional reads
 * are not declared yet, and reopening belongs to the source, not the input.
 */
public data class ReaderInputCapabilities(
  /**
   * The exact logical length in bytes, or null when it is unknown. Unknown is
   * distinct from zero, and an input never drains its source to answer it.
   */
  public val length: Long?,
  /**
   * Whether the input repositions within the interval it currently retains. A
   * target before that interval is unavailable history.
   */
  public val rewind: Boolean,
  /** Whether the input repositions anywhere within the representation. */
  public val seek: Boolean,
) {
  init {
    require(length == null || length >= 0) { "Length must not be negative." }
  }
}

/**
 * The machine-readable acquisition categories that `:reader` owns.
 *
 * They are separate from diagnostic prose and from the native categories.
 */
public enum class ReaderAcquisitionFailure {
  /** The source cannot supply the content. */
  UNAVAILABLE,

  /**
   * Acquisition or consumption failed, or the input was truncated. A declared
   * length mismatch belongs here.
   */
  FAILED,

  /** The source can no longer establish the same logical byte sequence. */
  REPRESENTATION_CHANGED,
}

/** The outcome of one [ReaderByteInput.read]. */
public sealed interface ReaderInputRead {
  /**
   * The read obtained [count] valid bytes, with `0 < count <= capacity`. Short
   * reads are legal.
   */
  public data class Data(public val count: Int) : ReaderInputRead {
    init {
      require(count > 0) { "Data must contain at least one byte." }
    }
  }

  /**
   * The read requested no bytes. It performed no I/O and left the cursor
   * unchanged.
   */
  public data object NoOp : ReaderInputRead

  /**
   * No bytes are available yet, and the source is live. Wait with
   * [ReaderByteInput.awaitInput] before reading again.
   */
  public data object AwaitInput : ReaderInputRead

  /**
   * The producer reported successful completion, and the cursor is at the end.
   * This proves neither a valid image nor a finished unit.
   */
  public data object EndOfInput : ReaderInputRead

  /** Acquisition or consumption failed. The input is terminal. */
  public data class InputFailure(public val failure: ReaderAcquisitionFailure) :
    ReaderInputRead

  /**
   * An earlier operation on this input was cancelled. The input is terminal and
   * never reports a successful end.
   */
  public data object Canceled : ReaderInputRead
}

/** The outcome of one [ReaderByteInput.seek]. */
public sealed interface ReaderInputSeek {
  /** The cursor is at the target. */
  public data object Positioned : ReaderInputSeek

  /** No declared capability covers the target. The cursor is unchanged. */
  public data object Unsupported : ReaderInputSeek

  /**
   * The target lies before the retained interval of a rewind-only input. The
   * cursor is unchanged.
   */
  public data object HistoryUnavailable : ReaderInputSeek

  /**
   * The target lies past the bytes received so far, and the source is live. The
   * cursor is unchanged.
   */
  public data object NotYetAvailable : ReaderInputSeek

  /**
   * The target lies past the known length or the completed end. The cursor is
   * unchanged.
   */
  public data object BeyondEnd : ReaderInputSeek

  /**
   * Repositioning failed, and the input guarantees the previous cursor. The
   * input stays usable. A target past the received bytes of an input whose
   * producer failed reports that failure here; [ReaderByteInput.read] still
   * delivers the valid prefix first.
   */
  public data class Failed(public val failure: ReaderAcquisitionFailure) :
    ReaderInputSeek

  /**
   * Repositioning failed without a guaranteed cursor. The input is terminal,
   * and later reads report [failure].
   */
  public data class Invalidated(public val failure: ReaderAcquisitionFailure) :
    ReaderInputSeek

  /**
   * The input already holds a failure or a cancellation, which
   * [ReaderByteInput.read] reports. A successful end never causes this.
   */
  public data object Terminal : ReaderInputSeek
}

/**
 * An open input over one representation: a cursor, declared capabilities, a
 * terminal outcome, and the resources that its owner closes.
 *
 * This is a reader contract, not a transport abstraction or a native binding.
 * Its `ByteArray` shape is provisional: the first ABI may reshape it against
 * the Bindings declarations, while its outcome semantics stay.
 *
 * **Calls.** Callers serialize [read], [awaitInput], and [seek]. Before
 * [close], they cancel and join every pending operation. [close] is idempotent,
 * and any other operation after it is a caller defect.
 *
 * **Producer outcome.** The input has exactly one terminal producer outcome,
 * successful completion or failure. Once established, it never changes, and the
 * input accepts no more bytes. If bytes are accepted before an error is
 * discovered, [read] delivers that valid prefix once as [ReaderInputRead.Data]
 * and reports the failure on the next observation. A declared length mismatch
 * is [ReaderAcquisitionFailure.FAILED], never [ReaderInputRead.EndOfInput].
 *
 * **Cancellation.** Cancellation of an operation propagates to its caller and
 * leaves the input terminal with [ReaderInputRead.Canceled], even when the
 * producer had already reported a failure that no read had observed. Bytes that
 * the cancelled operation consumed are never delivered by a later read.
 *
 * Expected acquisition outcomes are values. Unexpected defects propagate.
 */
public interface ReaderByteInput : AutoCloseable {
  /** The representation whose bytes this input delivers. */
  public val representation: ReaderRepresentation

  /** The capabilities declared for the lifetime of this input. */
  public val capabilities: ReaderInputCapabilities

  /**
   * The logical cursor: the offset of the next byte that [read] delivers. Only
   * [ReaderInputRead.Data] and [ReaderInputSeek.Positioned] change it.
   */
  public val position: Long

  /**
   * Reads at most [length] bytes into [destination] at [offset] and advances
   * the cursor by the delivered count.
   *
   * The range must lie within [destination]; it is checked before any access. A
   * zero [length] returns [ReaderInputRead.NoOp] without I/O.
   */
  public suspend fun read(
    destination: ByteArray,
    offset: Int,
    length: Int,
  ): ReaderInputRead

  /**
   * Suspends until [read] can return an outcome other than
   * [ReaderInputRead.AwaitInput]. Returns at once when it already can.
   */
  public suspend fun awaitInput()

  /**
   * Moves the cursor to the nonnegative [position] if a declared capability
   * covers it.
   */
  public suspend fun seek(position: Long): ReaderInputSeek

  /** Releases the input and its resources. */
  override fun close()
}
