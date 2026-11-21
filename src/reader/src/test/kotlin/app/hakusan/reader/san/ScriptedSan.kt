package app.hakusan.reader.san

import app.hakusan.reader.ReaderTarget
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** One call that a [ScriptedSan] answered, with the status it returned. */
internal data class ScriptedCall(
  val name: String,
  val thread: Thread,
  val status: Int,
  val handle: Long,
  val resetPeak: Boolean = false,
)

/** A copy destination of JVM tests, which records the copies into it. */
internal class ScriptedTarget(
  val width: Int,
  val height: Int,
  val alpha: Int = SanAbi.ALPHA_OPAQUE,
) : ReaderTarget {
  data class Copy(
    val lease: Long,
    val firstRow: Int,
    val rowCount: Int,
    val destinationFirstRow: Int,
  )

  val copies: MutableList<Copy> = CopyOnWriteArrayList()
}

/** The input that one content of a [ScriptedSan] received. */
internal class ScriptedContent(val handle: Long) {
  data class Feed(val position: Long, val length: Int, val endOfInput: Boolean)

  val feeds: MutableList<Feed> = CopyOnWriteArrayList()
  private val received = ByteArrayOutputStream()

  /** The bytes that no step has consumed. */
  @Volatile
  var staged: Int = 0
    internal set

  @Volatile
  var ended: Boolean = false
    internal set

  @Volatile internal var job: ScriptedJob? = null

  val receivedBytes: Long
    get() = synchronized(received) { received.size().toLong() }

  fun bytes(): ByteArray = synchronized(received) { received.toByteArray() }

  internal fun keep(bytes: ByteArray, offset: Int, length: Int) {
    synchronized(received) { received.write(bytes, offset, length) }
  }
}

/**
 * A step of a scripted job: it describes, admits, and covers through its
 * receiver and returns the status of the step.
 */
internal typealias ScriptedStep = ScriptedJob.() -> Int

/**
 * One job of a [ScriptedSan], and the view that its [ScriptedStep] has of it.
 *
 * The job keeps the rules of the header around the script and fails a script
 * that breaks one: a set cancellation flag ends the job before the script runs,
 * `INPUT_NEEDED` consumes the staged bytes, a step extends the revision once,
 * and a completion covers every row of the lease.
 */
internal class ScriptedJob
internal constructor(
  val handle: Long,
  val content: ScriptedContent,
  private val script: ScriptedStep,
  private val nextHandle: () -> Long,
) {
  internal val cancelFlag = AtomicBoolean()
  internal val cancelsInFlight = AtomicInteger()
  internal val report = LongArray(SanAbi.JOB_REPORT_LENGTH)
  private var coveredInStep = false

  @Volatile
  var terminal: Int? = null
    internal set

  @Volatile internal var released = false

  /** The caller's claim, from the first publication until its release. */
  @Volatile internal var callerClaim = false

  /** The steps that the script has finished. */
  var steps: Int = 0
    private set

  val canceled: Boolean
    get() = cancelFlag.get()

  val lease: Long
    get() = report[SanAbi.JOB_REPORT_LEASE]

  /** Whether the lease slot of the job still counts against the limit. */
  internal val leaseLive: Boolean
    get() = terminal == null || callerClaim

  /** Writes the image and grid elements, as the admission step does. */
  fun describe(
    imageWidth: Int,
    imageHeight: Int,
    outputWidth: Int = imageWidth,
    outputHeight: Int = imageHeight,
  ) {
    check(report[SanAbi.JOB_REPORT_IMAGE_WIDTH] == 0L) {
      "A script described its image twice."
    }

    report[SanAbi.JOB_REPORT_IMAGE_WIDTH] = imageWidth.toLong()
    report[SanAbi.JOB_REPORT_IMAGE_HEIGHT] = imageHeight.toLong()
    report[SanAbi.JOB_REPORT_OUTPUT_WIDTH] = outputWidth.toLong()
    report[SanAbi.JOB_REPORT_OUTPUT_HEIGHT] = outputHeight.toLong()
  }

  /** Writes the lease elements, as the admission of a lease does. */
  fun admit(
    width: Int,
    height: Int,
    left: Int = 0,
    top: Int = 0,
    alpha: Int = SanAbi.ALPHA_OPAQUE,
    earlyPublication: Boolean = true,
  ) {
    val described = report[SanAbi.JOB_REPORT_IMAGE_WIDTH] != 0L
    val admitted = report[SanAbi.JOB_REPORT_LEASE_WIDTH] != 0L

    check(described && !admitted) {
      "A script admitted a lease twice or before it described the image."
    }

    report[SanAbi.JOB_REPORT_LEASE_WIDTH] = width.toLong()
    report[SanAbi.JOB_REPORT_LEASE_HEIGHT] = height.toLong()
    report[SanAbi.JOB_REPORT_LEASE_LEFT] = left.toLong()
    report[SanAbi.JOB_REPORT_LEASE_TOP] = top.toLong()
    report[SanAbi.JOB_REPORT_ALPHA] = alpha.toLong()

    report[SanAbi.JOB_REPORT_EARLY_PUBLICATION] =
      if (earlyPublication) 1L else 0L
  }

  /** Publishes the lease rows `[0, rows)`, which extends the coverage. */
  fun cover(rows: Int) {
    val covered = report[SanAbi.JOB_REPORT_COVERED_ROWS]

    check(rows > covered && rows <= report[SanAbi.JOB_REPORT_LEASE_HEIGHT]) {
      "A script covered $rows rows after $covered, outside its lease."
    }

    if (lease == SanAbi.NO_HANDLE) {
      report[SanAbi.JOB_REPORT_LEASE] = nextHandle()
      callerClaim = true
    }

    report[SanAbi.JOB_REPORT_COVERED_ROWS] = rows.toLong()
    coveredInStep = true
  }

  /** Reports the image rows decoded so far. */
  fun decoded(rows: Int) {
    report[SanAbi.JOB_REPORT_DECODED_ROWS] = rows.toLong()
  }

  /** Sets warning [bits], which stay set. */
  fun warn(bits: Long) {
    report[SanAbi.JOB_REPORT_WARNINGS] =
      report[SanAbi.JOB_REPORT_WARNINGS] or bits
  }

  /** Runs one step under the rules of the header and returns its status. */
  internal fun step(): Int {
    if (cancelFlag.get()) {
      return end(SanAbi.STATUS_CANCELED)
    }

    coveredInStep = false

    val status = script()

    steps += 1

    if (coveredInStep) {
      check(status != SanAbi.STATUS_CANCELED) { "A canceled step published." }

      report[SanAbi.JOB_REPORT_REVISION] += 1L
    }

    return when (status) {
      SanAbi.STATUS_INPUT_NEEDED -> {
        check(!content.ended) { "A script needed input after its end." }

        content.staged = 0

        status
      }

      SanAbi.STATUS_PROGRESS -> {
        check(content.staged > 0) { "A script progressed past no byte." }

        status
      }

      SanAbi.STATUS_CANCELED -> {
        check(cancelFlag.get()) { "A script canceled without the flag." }

        end(status)
      }

      SanAbi.STATUS_OK -> {
        val height = report[SanAbi.JOB_REPORT_LEASE_HEIGHT]
        val covered = report[SanAbi.JOB_REPORT_COVERED_ROWS]

        check(height != 0L && covered == height) {
          "A script completed with $covered of $height rows covered."
        }

        end(status)
      }

      in FAILURES -> end(status)

      else -> error("A script returned the status $status, which no step has.")
    }
  }

  /** Ends the job with [status]: it is quiescent, and its claim ends. */
  internal fun end(status: Int): Int {
    terminal = status
    content.staged = 0

    return status
  }

  private companion object {
    val FAILURES =
      setOf(
        SanAbi.STATUS_UNSUPPORTED_FORMAT,
        SanAbi.STATUS_UNSUPPORTED_OPERATION,
        SanAbi.STATUS_INVALID_CONTENT,
        SanAbi.STATUS_TRUNCATED_CONTENT,
        SanAbi.STATUS_BUDGET_EXCEEDED,
        SanAbi.STATUS_ALLOCATION_FAILED,
        SanAbi.STATUS_ENGINE_FAILURE,
      )
  }
}

/**
 * An engine for JVM tests that keeps the call rules of the header `san.h` and
 * decodes nothing: each job runs the [ScriptedStep] that the test queued.
 *
 * It answers a call out of order with the status that San returns for it, so a
 * test finds a defect of the caller in [calls]. It also finds what San cannot
 * detect and records it in [violations], because a caller may swallow a throw:
 * a call beside another call or off the thread of the first call, both of which
 * throw as the calls on the library do, and a cancellation that overlaps the
 * release of its job.
 */
internal class ScriptedSan : SanCalls {
  private val busy = AtomicBoolean()
  private val handles = AtomicLong(FIRST_HANDLE)
  private val scripts = ConcurrentLinkedQueue<ScriptedStep>()
  private val injected = ConcurrentHashMap<String, ConcurrentLinkedQueue<Int>>()
  private val liveContents = ConcurrentHashMap<Long, ScriptedContent>()
  private val ownedJobs = ConcurrentHashMap<Long, ScriptedJob>()
  private val leases = ConcurrentHashMap<Long, ScriptedJob>()
  private val closedByShutdown = ConcurrentHashMap.newKeySet<Long>()

  @Volatile private var worker: Thread? = null
  @Volatile private var engine = SanAbi.NO_HANDLE
  @Volatile private var closing = false

  /** Every answered call, in the order of its return. */
  val calls: MutableList<ScriptedCall> = CopyOnWriteArrayList()

  /** The broken call rules that San cannot detect, in their order. */
  val violations: MutableList<String> = CopyOnWriteArrayList()

  /** Every content ever opened, in the order of its opening. */
  val contents: MutableList<ScriptedContent> = CopyOnWriteArrayList()

  /** Runs inside every cancellation, so that a test can hold one open. */
  @Volatile var duringCancel: () -> Unit = {}

  /** The limits of the last creation, in the order of the parameters. */
  @Volatile
  var limits: List<Long> = emptyList()
    private set

  val engineLive: Boolean
    get() = engine != SanAbi.NO_HANDLE

  val liveContentCount: Int
    get() = liveContents.size

  val ownedJobCount: Int
    get() = ownedJobs.size

  val heldLeaseCount: Int
    get() = leases.size

  /** Queues the script of the next requested job. */
  fun script(step: ScriptedStep) {
    scripts += step
  }

  /**
   * Makes the next call of [call] return the failure [status] before any of its
   * checks. Only a call whose failure keeps nothing takes one; a step fails
   * through its script.
   */
  fun failNext(call: String, status: Int) {
    require(call in INJECTABLE && status < 0) {
      "$call takes no injected status $status."
    }

    injected.computeIfAbsent(call) { ConcurrentLinkedQueue() } += status
  }

  override fun engineCreate(
    aggregateBudgetBytes: Long,
    contentCount: Long,
    jobCount: Long,
    contentLengthBytes: Long,
    inputPortionBytes: Long,
    imageAxisPixels: Long,
    imageAreaPixels: Long,
    metadataBytes: Long,
    metadataExpandedBytes: Long,
    metadataPayloadBytes: Long,
    heldChunkBytes: Long,
    iccTagCount: Long,
    exifEntryCount: Long,
    outputBytes: Long,
    outputLeaseBytes: Long,
    outputLeaseCount: Long,
    engineOut: LongArray,
  ): Int =
    answer("engineCreate", SanAbi.NO_HANDLE, out = engineOut) {
      val requested =
        listOf(
          aggregateBudgetBytes,
          contentCount,
          jobCount,
          contentLengthBytes,
          inputPortionBytes,
          imageAxisPixels,
          imageAreaPixels,
          metadataBytes,
          metadataExpandedBytes,
          metadataPayloadBytes,
          heldChunkBytes,
          iccTagCount,
          exifEntryCount,
          outputBytes,
          outputLeaseBytes,
          outputLeaseCount,
        )

      val counts = listOf(contentCount, jobCount, outputLeaseCount)

      val refused =
        requested.any { it < 1 } ||
          counts.any { it > COUNT_LIMIT } ||
          engineLive

      if (refused) {
        return@answer SanAbi.STATUS_BUDGET_EXCEEDED
      }

      limits = requested
      closing = false
      engine = handles.getAndIncrement()
      engineOut[0] = engine

      SanAbi.STATUS_OK
    }

  override fun engineShutdown(engine: Long): Int =
    answer("engineShutdown", engine) {
      if (!names(engine)) {
        return@answer SanAbi.STATUS_INVALID_HANDLE
      }

      closing = true
      closedByShutdown += liveContents.keys
      liveContents.values.forEach(::close)

      if (ownedJobs.isNotEmpty() || leases.isNotEmpty()) {
        return@answer SanAbi.STATUS_PENDING
      }

      closedByShutdown.clear()
      this.engine = SanAbi.NO_HANDLE

      SanAbi.STATUS_OK
    }

  override fun engineCounters(
    engine: Long,
    resetPeak: Boolean,
    countersOut: LongArray,
  ): Int =
    answer("engineCounters", engine, resetPeak) {
      if (countersOut.size != SanAbi.ENGINE_COUNTERS_LENGTH) {
        return@answer SanAbi.STATUS_BINDING_MISMATCH
      }

      if (!names(engine)) {
        return@answer SanAbi.STATUS_INVALID_HANDLE
      }

      countersOut.fill(0L)

      countersOut[SanAbi.ENGINE_COUNTERS_CONTENT_COUNT] =
        liveContents.size.toLong()

      countersOut[SanAbi.ENGINE_COUNTERS_JOB_COUNT] = ownedJobs.size.toLong()
      countersOut[SanAbi.ENGINE_COUNTERS_LEASE_COUNT] = liveLeases().toLong()

      SanAbi.STATUS_OK
    }

  override fun contentOpen(engine: Long, contentOut: LongArray): Int =
    answer("contentOpen", engine, out = contentOut) {
      val status =
        when {
          !names(engine) -> SanAbi.STATUS_INVALID_HANDLE
          closing -> SanAbi.STATUS_CLOSED

          liveContents.size >= limits[CONTENT_COUNT] ->
            SanAbi.STATUS_BUDGET_EXCEEDED

          else -> SanAbi.STATUS_OK
        }

      if (status == SanAbi.STATUS_OK) {
        val content = ScriptedContent(handles.getAndIncrement())

        liveContents[content.handle] = content
        contents += content
        contentOut[0] = content.handle
      }

      status
    }

  override fun contentFeed(
    content: Long,
    position: Long,
    bytes: ByteArray,
    offset: Int,
    length: Int,
    endOfInput: Boolean,
  ): Int =
    answer("contentFeed", content) {
      val inArray =
        offset >= 0 && length >= 0 && offset.toLong() + length <= bytes.size

      if (!inArray) {
        return@answer SanAbi.STATUS_BINDING_MISMATCH
      }

      val open = liveContents[content] ?: return@answer closedOrInvalid(content)

      if (open.job?.terminal != null) {
        return@answer SanAbi.STATUS_CLOSED
      }

      val lengthAdmitted =
        if (length == 0) endOfInput else length <= limits[INPUT_PORTION_BYTES]

      val admitted =
        lengthAdmitted &&
          !open.ended &&
          open.staged == 0 &&
          position == open.receivedBytes

      if (!admitted) {
        return@answer SanAbi.STATUS_BINDING_MISMATCH
      }

      open.keep(bytes, offset, length)
      open.feeds += ScriptedContent.Feed(position, length, endOfInput)
      open.staged = length
      open.ended = endOfInput

      SanAbi.STATUS_OK
    }

  override fun contentClose(content: Long): Int =
    answer("contentClose", content) {
      val open = liveContents[content] ?: return@answer closedOrInvalid(content)

      close(open)

      SanAbi.STATUS_OK
    }

  override fun jobRequest(
    content: Long,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    maxOutputWidth: Int,
    jobOut: LongArray,
  ): Int =
    answer("jobRequest", content, out = jobOut) {
      val open = liveContents[content] ?: return@answer closedOrInvalid(content)
      val whole = left == 0 && top == 0 && right == 0 && bottom == 0
      val region = left in 0..<right && top in 0..<bottom

      val status =
        when {
          open.job != null || maxOutputWidth < 0 || !(whole || region) ->
            SanAbi.STATUS_BINDING_MISMATCH

          ownedJobs.size >= limits[JOB_COUNT] ||
            liveLeases() >= limits[OUTPUT_LEASE_COUNT] ->
            SanAbi.STATUS_BUDGET_EXCEEDED

          else -> SanAbi.STATUS_OK
        }

      if (status == SanAbi.STATUS_OK) {
        val script = checkNotNull(scripts.poll()) { "No script for a job." }

        val job =
          ScriptedJob(handles.getAndIncrement(), open, script) {
            handles.getAndIncrement()
          }

        open.job = job
        ownedJobs[job.handle] = job
        jobOut[0] = job.handle
      }

      status
    }

  override fun jobStep(job: Long, reportOut: LongArray): Int =
    answer("jobStep", job) {
      if (reportOut.size != SanAbi.JOB_REPORT_LENGTH) {
        return@answer SanAbi.STATUS_BINDING_MISMATCH
      }

      val owned = ownedJobs[job] ?: return@answer SanAbi.STATUS_INVALID_HANDLE

      if (owned.terminal != null) {
        return@answer SanAbi.STATUS_CLOSED
      }

      val status = owned.step()

      if (owned.callerClaim) {
        leases[owned.lease] = owned
      }

      owned.report.copyInto(reportOut)

      status
    }

  /** Any thread may call it, also beside a step of [job]. */
  override fun jobCancel(job: Long): Int {
    val owned = ownedJobs[job]

    owned?.cancelsInFlight?.incrementAndGet()

    try {
      duringCancel()

      if (owned != null && owned.terminal == null) {
        owned.cancelFlag.set(true)
      }

      if (owned?.released == true) {
        violations += "jobCancel ran beside jobRelease."
      }
    } finally {
      owned?.cancelsInFlight?.decrementAndGet()
    }

    val status =
      if (owned == null) SanAbi.STATUS_INVALID_HANDLE else SanAbi.STATUS_OK

    calls += ScriptedCall("jobCancel", Thread.currentThread(), status, job)

    return status
  }

  override fun jobRelease(job: Long): Int =
    answer("jobRelease", job) {
      val owned = ownedJobs[job] ?: return@answer SanAbi.STATUS_INVALID_HANDLE

      if (owned.terminal == null) {
        return@answer SanAbi.STATUS_BINDING_MISMATCH
      }

      owned.released = true

      // Every cancellation returns before the release of its job.
      if (owned.cancelsInFlight.get() != 0) {
        violations += "jobRelease ran beside jobCancel."
      }

      ownedJobs -= job

      SanAbi.STATUS_OK
    }

  override fun outputCopy(
    lease: Long,
    firstRow: Int,
    rowCount: Int,
    destination: ReaderTarget,
    destinationFirstRow: Int,
  ): Int =
    answer("outputCopy", lease) {
      if (destination !is ScriptedTarget) {
        return@answer SanAbi.STATUS_BINDING_MISMATCH
      }

      val holder = leases[lease] ?: return@answer SanAbi.STATUS_INVALID_HANDLE
      val report = holder.report
      val opaque = SanAbi.ALPHA_OPAQUE.toLong()

      // The checks of the header, in its order.
      val copies =
        destination.width.toLong() == report[SanAbi.JOB_REPORT_LEASE_WIDTH] &&
          (destination.alpha.toLong() != opaque ||
            report[SanAbi.JOB_REPORT_ALPHA] == opaque) &&
          rowCount >= 1 &&
          firstRow >= 0 &&
          firstRow.toLong() + rowCount <=
            report[SanAbi.JOB_REPORT_COVERED_ROWS] &&
          destinationFirstRow >= 0 &&
          destinationFirstRow.toLong() + rowCount <= destination.height

      if (!copies) {
        return@answer SanAbi.STATUS_BINDING_MISMATCH
      }

      destination.copies +=
        ScriptedTarget.Copy(lease, firstRow, rowCount, destinationFirstRow)

      SanAbi.STATUS_OK
    }

  override fun outputRelease(lease: Long): Int =
    answer("outputRelease", lease) {
      val holder =
        leases.remove(lease) ?: return@answer SanAbi.STATUS_INVALID_HANDLE

      holder.callerClaim = false

      SanAbi.STATUS_OK
    }

  /** Closes [content], which ends its live job as canceled. */
  private fun close(content: ScriptedContent) {
    val job = content.job

    if (job != null && job.terminal == null) {
      job.end(SanAbi.STATUS_CANCELED)
    }

    liveContents -= content.handle
  }

  private fun names(engine: Long): Boolean =
    engine != SanAbi.NO_HANDLE && engine == this.engine

  /** The status of a call on a content that is not live. */
  private fun closedOrInvalid(content: Long): Int =
    if (content in closedByShutdown) {
      SanAbi.STATUS_CLOSED
    } else {
      SanAbi.STATUS_INVALID_HANDLE
    }

  /** The lease slots in use: one per job, from its request on. */
  private fun liveLeases(): Int {
    val jobs = contents.mapNotNull { it.job }

    return jobs.count { it.leaseLive }
  }

  /**
   * Answers one serialized call. With [out], the call writes a handle: a wrong
   * array changes nothing, and every other failure leaves no handle there.
   */
  private inline fun answer(
    name: String,
    handle: Long,
    resetPeak: Boolean = false,
    out: LongArray? = null,
    call: () -> Int,
  ): Int {
    enter(name)

    try {
      val status =
        if (out != null && out.size != 1) {
          SanAbi.STATUS_BINDING_MISMATCH
        } else {
          out?.set(0, SanAbi.NO_HANDLE)

          injected[name]?.poll() ?: call()
        }

      calls +=
        ScriptedCall(name, Thread.currentThread(), status, handle, resetPeak)

      return status
    } finally {
      busy.set(false)
    }
  }

  /** Admits a call of the thread of the first call, one at a time. */
  private fun enter(name: String) {
    val caller = Thread.currentThread()
    val pinned = worker ?: caller.also { worker = it }

    if (pinned !== caller) {
      violate("$name ran off the thread of the first call.")
    }

    if (!busy.compareAndSet(false, true)) {
      violate("$name ran beside another call.")
    }
  }

  private fun violate(message: String): Nothing {
    violations += message

    error(message)
  }

  private companion object {
    const val FIRST_HANDLE = 0x1001L
    const val COUNT_LIMIT = 65_535L

    // Positions in the limits of a creation.
    const val CONTENT_COUNT = 1
    const val JOB_COUNT = 2
    const val INPUT_PORTION_BYTES = 4
    const val OUTPUT_LEASE_COUNT = 15

    val INJECTABLE =
      setOf("engineCreate", "contentOpen", "contentFeed", "jobRequest")
  }
}
