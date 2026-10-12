package app.hakusan.reader

import java.util.Collections

/**
 * The machine-readable reader-level failure categories of a content request.
 *
 * They combine the acquisition categories that `:reader` owns with the native
 * categories that the Bindings declare. Each category tells the caller whether
 * a retry, a supported fallback, or neither can help. Diagnostic prose stays
 * separate.
 *
 * Native cancellation is the [ReaderContentTerminal.Canceled] outcome, not a
 * failure. A native invalid-handle or closed report is an ownership defect of
 * the caller, not an outcome.
 */
public enum class ReaderContentFailure {
  /**
   * The source cannot supply the content, from
   * [ReaderAcquisitionFailure.UNAVAILABLE]. A retry needs a change at the
   * source.
   */
  INPUT_UNAVAILABLE,

  /**
   * Acquisition or consumption failed, or the input was truncated against its
   * declared length, from [ReaderAcquisitionFailure.FAILED]. A new acquisition
   * may succeed.
   */
  INPUT_FAILED,

  /**
   * The source no longer establishes the same byte sequence, from
   * [ReaderAcquisitionFailure.REPRESENTATION_CHANGED]. A new request against a
   * new representation may succeed.
   */
  REPRESENTATION_CHANGED,

  /**
   * The input completed successfully, but the encoded image ended before the
   * data it requires. This is never padded into a complete image. A new
   * acquisition may obtain the complete content.
   *
   * It comes from the native truncated-content category, which San reports when
   * the input completed while the encoded image still needs data. Truncation
   * against the declared length is [INPUT_FAILED].
   */
  TRUNCATED_CONTENT,

  /**
   * Metadata or encoded structure violates the format or the profile, from the
   * native invalid-content category. No retry or fallback helps.
   */
  INVALID_CONTENT,

  /**
   * The profile cannot decode this content at all: its format, variant, color
   * model, or dynamic range is unsupported. This comes from the native
   * unsupported-format category, and no other request for the content helps.
   */
  UNSUPPORTED_FORMAT,

  /**
   * The profile cannot satisfy the requested access or output, from the native
   * unsupported-operation category. A request for another region or scale may
   * succeed.
   */
  UNSUPPORTED_OPERATION,

  /**
   * The work cannot be admitted within the configured limits, from the native
   * budget-exceeded category. A smaller request, or one after other holders
   * release capacity, may succeed.
   */
  BUDGET_EXCEEDED,

  /**
   * A required allocation failed despite admission, from the native
   * allocation-failed category. A later retry may succeed.
   */
  ALLOCATION_FAILED,

  /**
   * An unexpected recoverable engine failure ended the operation, from the
   * native engine-failure category. A new request may succeed.
   */
  ENGINE_FAILURE,

  /**
   * The native library is incompatible or unavailable, from the native binding
   * mismatch and load failure categories. No retry helps in this process.
   */
  ENGINE_UNAVAILABLE,
}

/**
 * A condition that a successful result reports without becoming a failure.
 *
 * A warning never hides a failure. Content that a warning cannot describe ends
 * as [ReaderContentTerminal.Failed].
 */
public enum class ReaderContentWarning {
  /**
   * Safely decoded, supported SDR content was displayed as sRGB, because its
   * color description was malformed or unsupported. Invalid pixel data, HDR
   * signaling, an unsupported color model, and an exceeded limit stay failures.
   */
  SRGB_FALLBACK
}

/**
 * The one terminal outcome of an executed content request.
 *
 * It completes the requested operation. It is not a reading event, and it is
 * not the end of the input. Output published before a failure stays valid while
 * its lease is held, so partial output and a failure coexist.
 */
public sealed interface ReaderContentTerminal {
  /** The operation completed and reports [warnings], which may be empty. */
  @ConsistentCopyVisibility
  public data class Completed
  private constructor(public val warnings: Set<ReaderContentWarning>) :
    ReaderContentTerminal {
    public companion object {
      /** Completes with an owned copy of [warnings]. */
      public fun of(
        warnings: Iterable<ReaderContentWarning> = emptyList()
      ): Completed = Completed(Collections.unmodifiableSet(warnings.toSet()))
    }
  }

  /**
   * The operation stopped because its request was cancelled, or because its
   * executing task was cancelled.
   */
  public data object Canceled : ReaderContentTerminal

  /** The operation ended with [failure]. */
  public data class Failed(public val failure: ReaderContentFailure) :
    ReaderContentTerminal
}

/** Maps an acquisition category onto its reader-level failure. */
internal fun ReaderAcquisitionFailure.toContentFailure(): ReaderContentFailure =
  when (this) {
    ReaderAcquisitionFailure.UNAVAILABLE ->
      ReaderContentFailure.INPUT_UNAVAILABLE

    ReaderAcquisitionFailure.FAILED -> ReaderContentFailure.INPUT_FAILED

    ReaderAcquisitionFailure.REPRESENTATION_CHANGED ->
      ReaderContentFailure.REPRESENTATION_CHANGED
  }
