package app.hakusan.reader

import app.hakusan.reader.san.SanBinding
import app.hakusan.reader.san.SanCalls
import app.hakusan.reader.san.ScriptedCall
import app.hakusan.reader.san.ScriptedSan
import app.hakusan.reader.san.ScriptedTarget
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

/**
 * The platform of JVM tests: the scripted engine, targets without pixels, and a
 * record of the trace sections.
 */
internal class ScriptedPlatform(
  private val san: ScriptedSan,
  @Volatile var binding: SanBinding = SanBinding.Established,
) : ReaderPlatform {
  val binds = AtomicInteger()

  /** Every target ever created, in the order of its creation. */
  val created = CopyOnWriteArrayList<ScriptedTarget>()

  val recycled = CopyOnWriteArrayList<ReaderTarget>()

  /** What the adapter did that a device would fail or leak. */
  val violations = CopyOnWriteArrayList<String>()

  /** The name of every section that was opened, in order. */
  val sections = CopyOnWriteArrayList<String>()

  private val open = AtomicInteger()

  override fun bind(): SanBinding {
    binds.incrementAndGet()

    return binding
  }

  override fun calls(worker: Thread): SanCalls = san

  override fun createTarget(
    width: Int,
    height: Int,
    opaque: Boolean,
  ): ReaderTarget {
    val alpha = if (opaque) ALPHA_OPAQUE else ALPHA_PREMULTIPLIED

    return ScriptedTarget(width, height, alpha).also { created += it }
  }

  override fun recycle(target: ReaderTarget) {
    // Recorded and not thrown: a task of the worker would lose the throw.
    if (target !is ScriptedTarget || target.recycled) {
      violations += "A target was recycled twice, or it is none of this."
    } else {
      target.recycled = true
      recycled += target
    }
  }

  override fun beginSection(name: String) {
    sections += name
    open.incrementAndGet()
  }

  override fun endSection() {
    if (open.getAndDecrement() <= 0) {
      violations += "A section ended that never began."
    }
  }

  private companion object {
    const val ALPHA_OPAQUE = app.hakusan.reader.san.SanAbi.ALPHA_OPAQUE
    const val ALPHA_PREMULTIPLIED =
      app.hakusan.reader.san.SanAbi.ALPHA_PREMULTIPLIED
  }
}

/** A source that hands out the queued results, one per opening. */
internal class TestSource : ReaderEncodedSource {
  val results = ConcurrentLinkedQueue<ReaderInputOpenResult>()
  val entered = Channel<Unit>(Channel.UNLIMITED)
  val opens = AtomicInteger()

  /** Suspends every opening until it completes, when set. */
  @Volatile var hold: CompletableDeferred<Unit>? = null

  @Volatile var onOpen: () -> Unit = {}

  fun supply(input: ReaderByteInput) {
    results += ReaderInputOpenResult.Opened(input)
  }

  override suspend fun open(
    descriptor: ReaderContentDescriptor
  ): ReaderInputOpenResult {
    opens.incrementAndGet()
    onOpen()
    entered.trySend(Unit)
    hold?.await()

    return checkNotNull(results.poll()) { "No result for an opening." }
  }
}

/** Blocks the step that calls [block] until the test counts [resume] down. */
internal class StepHold {
  val entered = CompletableDeferred<Unit>()
  val resume = CountDownLatch(1)

  fun block() {
    entered.complete(Unit)
    check(resume.await(BOUND_SECONDS, TimeUnit.SECONDS))
  }

  private companion object {
    const val BOUND_SECONDS = 5L
  }
}

/**
 * Presentation as a test drives it: it takes the publications of [requests] and
 * keeps their leases until the test closes them.
 */
internal class TestPresenter(private val requests: ReaderContentRequests) {
  private val woken = Channel<Unit>(Channel.UNLIMITED)

  /** Every lease taken, in order. */
  val taken = CopyOnWriteArrayList<ReaderOutputLease>()

  init {
    requests.setPresentationWakeup { woken.trySend(Unit) }
  }

  /**
   * Takes the next publication, and waits for its wakeup when none is queued.
   */
  suspend fun take(): ReaderOutputLease {
    while (true) {
      val publication = requests.takePublication()

      if (publication != null) {
        taken += publication.output

        return publication.output
      }

      woken.receive()
    }
  }
}

/**
 * Makes presentation take each publication when it is queued and return its
 * target at once, for a test that looks at the engine and not at the output.
 */
internal fun ReaderContentRequests.consumeAtOnce() {
  setPresentationWakeup { takePublication()?.output?.close() }
}

/**
 * Suspends until [thread] has left the runnable state, which a thread that
 * spins never does. The bound of the test then fails it.
 */
internal suspend fun awaitParked(thread: Thread) {
  while (thread.state == Thread.State.RUNNABLE) {
    delay(1.milliseconds)
  }
}

/** The target of a lease in a JVM test, with the copies it received. */
internal val ReaderOutputLease.scripted: ScriptedTarget
  get() = target as ScriptedTarget

/** The rows that the lease covers, from the top of its region. */
internal val ReaderOutputLease.rows: Int
  get() = coverage.region.bottom - coverage.region.top

/** A failure outcome. */
internal fun failed(failure: ReaderContentFailure): ReaderContentTerminal =
  ReaderContentTerminal.Failed(failure)

internal fun ScriptedSan.count(name: String): Int = calls.count {
  it.name == name
}

internal fun ScriptedSan.count(matches: (ScriptedCall) -> Boolean): Int =
  calls.count(matches)

internal val ScriptedCall.isStep: Boolean
  get() = name == "jobStep"

/**
 * Whether the newest content received [bytes] bytes and its job has stepped
 * since the last feed, so that the job waits for input.
 */
internal fun ScriptedSan.steppedAt(bytes: Long): Boolean {
  val last = calls.lastOrNull { it.isStep || it.name == "contentFeed" }

  return contents.last().receivedBytes == bytes && last?.isStep == true
}

/** An input whose [count] bytes arrived and whose producer completed. */
internal fun completeInput(count: Int = 16): ControlledReaderInput =
  liveInput(count).apply {
    receiveThrough(count)
    end()
  }

/** An input of [count] bytes that has received none and stays live. */
internal fun liveInput(count: Int = 16): ControlledReaderInput =
  ControlledReaderInput(countingBytes(count), maxTransfer = 5)

/** The first [count] bytes of a counting sequence. */
internal fun countingBytes(count: Int): ByteArray =
  ByteArray(count) { it.toByte() }
