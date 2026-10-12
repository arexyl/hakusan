package app.hakusan.reader

import kotlinx.coroutines.CompletableDeferred

/**
 * One technical content request: a provider unit and an optional region.
 *
 * Its identity is unrelated to reading-event order. Only
 * [ReaderContentRequests.submit] creates it.
 */
public class ReaderContentRequest
internal constructor(
  /** The provider unit whose content is requested. */
  public val descriptor: ReaderContentDescriptor,
  /** The requested region, or null for the whole image. */
  public val region: ReaderImageRegion?,
) {
  private val cancellation = CompletableDeferred<Unit>()

  @Volatile private var canceled = false

  @Volatile private var outcome: ReaderContentTerminal? = null

  /** The bound representation. The owner guards it. */
  internal var representation: ReaderRepresentation? = null

  /** Whether a publication was stale. The owner guards it. */
  internal var stale = false

  /** Whether cancellation of this request has been requested. */
  public val cancellationRequested: Boolean
    get() = canceled

  /**
   * The terminal outcome, or null while the request has not executed to its
   * end. A request dropped before execution never receives one.
   */
  public val terminal: ReaderContentTerminal?
    get() = outcome

  /**
   * Suspends until the owner has notified the cancellation of this request, and
   * returns at once afterwards. The intent stays observable regardless of how
   * submission or closing completed.
   */
  public suspend fun awaitCancellationRequested() {
    cancellation.await()
  }

  /** Records the intent. The owner calls it under its monitor. */
  internal fun requestCancellation() {
    canceled = true
  }

  /** Resumes observers. The owner calls it outside its monitor. */
  internal fun notifyCancellation() {
    cancellation.complete(Unit)
  }

  /** Records the outcome. The owner calls it under its monitor. */
  internal fun settle(terminal: ReaderContentTerminal) {
    outcome = terminal
  }
}

/** A published output of [request], handed to presentation once. */
@ConsistentCopyVisibility
public data class ReaderPublication
internal constructor(
  public val request: ReaderContentRequest,
  public val output: ReaderOutputLease,
)

/** The outcome of [ReaderContentRequests.publish]. */
public enum class ReaderPublishResult {
  /** The output is queued for presentation, and the owner holds its lease. */
  PUBLISHED,

  /**
   * The request can no longer publish: it was cancelled or superseded, its
   * owner is closing, or the output belongs to another representation or lies
   * outside the requested region. The owner has released the output. This is
   * terminal for the request, so every later publication of it is stale too.
   */
  STALE,

  /**
   * The owner is at an output limit. The output stays with its producer, which
   * may publish it again later or hand it to [ReaderContentRequests.discard].
   */
  BUSY,
}

/**
 * The output limits of one content owner, as named, replaceable settings.
 *
 * Every queued, displayed, or superseded output counts against [liveOutputs]
 * until its release, and so does an output whose release failed.
 */
public data class ReaderOutputLimits(
  /** The most outputs whose leases the owner holds at once. */
  public val liveOutputs: Int,
  /** The most published outputs that wait for presentation at once. */
  public val awaitingPublications: Int,
) {
  init {
    require(liveOutputs > 0) { "At least one live output is required." }

    require(awaitingPublications in 1..liveOutputs) {
      "Awaiting publications must be live outputs."
    }
  }

  public companion object {
    /**
     * The first-iteration qualification profile: four live outputs, with at
     * most one publication awaiting its consumer.
     */
    public val FIRST_ITERATION: ReaderOutputLimits =
      ReaderOutputLimits(liveOutputs = 4, awaitingPublications = 1)
  }
}

/**
 * The failure with which [ReaderContentRequests.awaitClosed] completes after an
 * output release failed.
 *
 * The [cause] is the first release failure, and later ones are suppressed. The
 * failed outputs were never reported as released.
 */
public class ReaderOutputReleaseException
internal constructor(cause: Throwable) :
  RuntimeException("An output release failed.", cause)

/**
 * Admission, publication, and closing of the content requests of one session.
 *
 * **Admission.** One request executes, and one lightweight request waits. A new
 * request replaces the waiting one and requests cancellation of both. A request
 * dropped before execution receives only its cancellation intent. An executor
 * takes the waiting request with [takeNext] only after the executing one has
 * acknowledged its terminal outcome, so replacements never overlap.
 *
 * **Publication.** The executor binds the representation of its input and
 * publishes outputs of that representation inside the requested region. The
 * owner holds every published lease until presentation or the owner releases
 * it, so a lease taken by presentation survives the end of its request. The
 * executor hands every output that it does not publish to [discard].
 *
 * **Closing.** The end of the session closes the owner as [beginClose] does,
 * without a paired call. Closing stops admission, cancels both requests, and
 * releases queued outputs. [awaitClosed] completes once the executing request
 * has acknowledged its terminal outcome and every held lease has settled.
 *
 * **Failures.** A release failure never escapes [submit], [publish], [discard],
 * [takePublication], or [beginClose]. It travels through one close-failure
 * channel, and [awaitClosed] completes with it. A failed release is never
 * retried. Only a fatal [VirtualMachineError] of a release also propagates
 * where it happens.
 *
 * **Execution.** The owner runs no task, acquisition, or decoder. Its calls are
 * short and safe from any thread. It notifies cancellation observers after its
 * admission state changes, outside its monitor, and before it releases outputs.
 * A notification is not a terminal acknowledgment.
 *
 * Calls that break the executor protocol are caller defects and throw
 * [IllegalArgumentException].
 */
public class ReaderContentRequests(
  session: ReaderSession,
  private val limits: ReaderOutputLimits,
) {
  private val monitor = Any()
  private val closed = CompletableDeferred<Unit>()
  private val queued = ArrayDeque<ReaderPublication>()
  private val held = HashSet<ReaderOutputLease>()
  private var closing = false
  private var finished = false
  private var executing: ReaderContentRequest? = null
  private var pending: ReaderContentRequest? = null
  private var failedReleases = 0
  private var closeFailure: ReaderOutputReleaseException? = null

  init {
    session.invokeOnEnd(::beginClose)
  }

  /**
   * Submits a request for [descriptor] and [region], where null requests the
   * whole image. It replaces the waiting request and requests cancellation of
   * the executing one. Queued outputs that presentation has not taken are
   * released.
   *
   * Returns the new request, or null once closing has begun.
   */
  public fun submit(
    descriptor: ReaderContentDescriptor,
    region: ReaderImageRegion? = null,
  ): ReaderContentRequest? {
    val request = ReaderContentRequest(descriptor, region)

    val superseded =
      synchronized(monitor) {
        if (closing) {
          return null
        }

        val settlement = withdraw(executing, pending)

        pending = request

        settlement
      }

    superseded.complete()

    return request
  }

  /**
   * Moves the waiting request to execution and returns it. Returns null while
   * another request executes, while none waits, and once closing has begun.
   */
  public fun takeNext(): ReaderContentRequest? =
    synchronized(monitor) {
      if (closing || executing != null) {
        return null
      }

      val next = pending

      executing = next
      pending = null

      next
    }

  /**
   * Binds the input [representation] to the executing [request]. An executed
   * request has one acquisition, so it binds at most once.
   *
   * Returns false when the request is cancelled or the owner is closing. The
   * executor then closes its input and acknowledges the request.
   *
   * @throws IllegalArgumentException if [request] is not executing in this
   *   owner, or if it already has a representation.
   */
  public fun bindRepresentation(
    request: ReaderContentRequest,
    representation: ReaderRepresentation,
  ): Boolean =
    synchronized(monitor) {
      requireExecuting(request)

      require(request.representation == null) {
        "The request already has a representation."
      }

      val current = !closing && !request.cancellationRequested && !request.stale

      if (current) {
        request.representation = representation
      }

      current
    }

  /**
   * Publishes [output] of the executing [request].
   *
   * A stale publication passes the output to the owner, which releases it. A
   * busy one leaves the output with its producer.
   *
   * @throws IllegalArgumentException if [request] is not executing in this
   *   owner, or if [output] is claimed, closing, or released. The owner state
   *   is then unchanged.
   */
  public fun publish(
    request: ReaderContentRequest,
    output: ReaderOutputLease,
  ): ReaderPublishResult {
    val result =
      synchronized(monitor) {
        requireExecuting(request)

        val result =
          when {
            !publishable(request, output) -> ReaderPublishResult.STALE
            atLimit() -> ReaderPublishResult.BUSY
            else -> ReaderPublishResult.PUBLISHED
          }

        val accepted =
          if (result == ReaderPublishResult.BUSY) {
            output.claimable()
          } else {
            hold(output)
          }

        require(accepted) { UNCLAIMABLE }

        when (result) {
          ReaderPublishResult.STALE -> request.stale = true

          ReaderPublishResult.PUBLISHED ->
            queued.addLast(ReaderPublication(request, output))

          ReaderPublishResult.BUSY -> {}
        }

        result
      }

    if (result == ReaderPublishResult.STALE) {
      output.close()
    }

    return result
  }

  /**
   * Releases [output], which the executing [request] produced and did not
   * publish, for example after a busy publication. A release failure travels
   * through the close-failure channel.
   *
   * The producer discards every output it still holds before
   * [acknowledgeTerminal], also on a cancellation path. Afterward the request
   * no longer executes, so only a direct close of the lease remains, and that
   * close rethrows a release failure to the producer instead of the channel.
   *
   * @throws IllegalArgumentException if [request] is not executing in this
   *   owner, or if [output] is claimed, closing, or released.
   */
  public fun discard(request: ReaderContentRequest, output: ReaderOutputLease) {
    synchronized(monitor) {
      requireExecuting(request)
      require(hold(output)) { UNCLAIMABLE }
    }

    output.close()
  }

  /**
   * Hands the oldest queued publication to presentation, which must close its
   * lease. Returns null when none is queued.
   */
  public fun takePublication(): ReaderPublication? =
    synchronized(monitor) { queued.removeFirstOrNull() }

  /**
   * Records [outcome] as the one terminal outcome of the executing [request].
   *
   * The executor calls it only after it has settled the acquisition, the
   * callbacks, and the borrows of the request, and after it has discarded every
   * output it still holds. Every later output handoff of the request is a
   * defect.
   *
   * @throws IllegalArgumentException if [request] is not executing in this
   *   owner, for example because it was already acknowledged.
   */
  public fun acknowledgeTerminal(
    request: ReaderContentRequest,
    outcome: ReaderContentTerminal,
  ) {
    synchronized(monitor) {
      requireExecuting(request)
      request.settle(outcome)
      executing = null
    }

    completeIfClosed()
  }

  /**
   * Stops admission, requests cancellation of the executing and the waiting
   * request, and releases the queued outputs. The waiting request is dropped.
   * Repeating it is a no-op. It throws nothing but a fatal
   * [VirtualMachineError] of a release.
   */
  public fun beginClose() {
    val withdrawn =
      synchronized(monitor) {
        if (closing) {
          return
        }

        closing = true

        val settlement = withdraw(executing, pending)

        pending = null

        settlement
      }

    withdrawn.complete()
    completeIfClosed()
  }

  /**
   * Suspends until closing has begun, the executing request has acknowledged
   * its terminal outcome, and every held lease has settled.
   *
   * @throws ReaderOutputReleaseException if an output release failed.
   */
  public suspend fun awaitClosed() {
    closed.await()

    synchronized(monitor) { closeFailure }?.let { throw it }
  }

  private fun requireExecuting(request: ReaderContentRequest) {
    require(executing === request) {
      "The request is not executing in this owner."
    }
  }

  private fun publishable(
    request: ReaderContentRequest,
    output: ReaderOutputLease,
  ): Boolean {
    val within = request.region?.contains(output.coverage.region) ?: true

    return !closing &&
      !request.cancellationRequested &&
      !request.stale &&
      request.representation === output.representation &&
      within
  }

  /**
   * Whether another publication would exceed an output limit. An output whose
   * release failed still counts, because its backing was never reclaimed. The
   * caller holds the monitor.
   */
  private fun atLimit(): Boolean =
    held.size + failedReleases >= limits.liveOutputs ||
      queued.size >= limits.awaitingPublications

  /**
   * Claims [output] for the owner and returns whether it could. The caller
   * holds the monitor.
   */
  private fun hold(output: ReaderOutputLease): Boolean {
    val claimed = output.claim { failure -> settled(output, failure) }

    if (claimed) {
      held += output
    }

    return claimed
  }

  /**
   * Requests cancellation of [requests] and takes the queued publications. The
   * caller holds the monitor.
   */
  private fun withdraw(vararg requests: ReaderContentRequest?): Withdrawal {
    val canceled = requests.filterNotNull()

    canceled.forEach { it.requestCancellation() }

    val discarded = queued.map { it.output }

    queued.clear()

    return Withdrawal(canceled, discarded)
  }

  private fun settled(output: ReaderOutputLease, failure: Throwable?) {
    synchronized(monitor) {
      held -= output

      if (failure != null) {
        failedReleases += 1
        recordFailure(failure)
      }
    }

    completeIfClosed()
  }

  private fun recordFailure(failure: Throwable) {
    val recorded = closeFailure

    if (recorded == null) {
      closeFailure = ReaderOutputReleaseException(failure)
    } else if (recorded.cause !== failure) {
      recorded.addSuppressed(failure)
    }
  }

  private fun completeIfClosed() {
    val ready =
      synchronized(monitor) {
        val settled = closing && executing == null && held.isEmpty()

        if (settled && !finished) {
          finished = true

          true
        } else {
          false
        }
      }

    if (ready) {
      closed.complete(Unit)
    }
  }

  /** Cancellation notices and releases that run outside the monitor. */
  private class Withdrawal(
    private val canceled: List<ReaderContentRequest>,
    private val discarded: List<ReaderOutputLease>,
  ) {
    /** Notifies observers first, then runs the fallible releases. */
    fun complete() {
      canceled.forEach { it.notifyCancellation() }
      discarded.forEach { it.close() }
    }
  }

  private companion object {
    const val UNCLAIMABLE = "The output is claimed, closing, or released."
  }
}
