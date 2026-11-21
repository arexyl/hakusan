package app.hakusan.reader.san

/** The outcome of loading the San library and establishing its binding. */
internal sealed interface SanBinding {
  /** The library is loaded and has the ABI version [SanAbi.ABI_VERSION]. */
  data object Established : SanBinding

  /** The platform found or loaded no San library for this process. */
  data class LoadFailure(val cause: UnsatisfiedLinkError) : SanBinding

  /**
   * The loaded library is not the one of these declarations. [status] is the
   * status of the Establish binding call, or null when the library registered
   * no function, as after a missing class or a failed registration.
   */
  data class Mismatch(val status: Int?) : SanBinding
}

/** Loads the San library. Only this object names it. */
internal object SanLibrary {
  private const val NAME = "san"

  /**
   * Loads the library and establishes the binding of this process. A repeated
   * call loads nothing again and establishes the same binding. No other
   * function of [SanAbi] may run before this returns [SanBinding.Established].
   */
  fun bind(): SanBinding {
    try {
      System.loadLibrary(NAME)
    } catch (failure: UnsatisfiedLinkError) {
      return SanBinding.LoadFailure(failure)
    }

    val status =
      try {
        SanAbi.bindingEstablish(SanAbi.ABI_VERSION)
      } catch (_: UnsatisfiedLinkError) {
        return SanBinding.Mismatch(status = null)
      }

    return if (status == SanAbi.STATUS_OK) {
      SanBinding.Established
    } else {
      SanBinding.Mismatch(status)
    }
  }
}
