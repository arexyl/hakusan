package app.hakusan.reader

import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The executable specification of the encoded-retention policy.
 *
 * The ledger accounts representation-qualified byte ranges, never their bytes.
 * It holds no native state and runs no timer: [time] is the one injected
 * monotonic time domain, and expiry takes effect only at the opportunities that
 * the policy names. Retention groups are opaque keys; the ledger never
 * interprets chapter order. It is confined to one caller and is not
 * synchronized.
 */
internal class ReaderRetentionLedger(
  private val settings: Settings,
  private val time: TimeSource.WithComparableMarks,
) {
  /**
   * The budgets and lifetimes that the policy applies.
   *
   * The ceilings apply together with the aggregate budget, never as partitions,
   * so neither ceiling exceeds it.
   *
   * @throws IllegalArgumentException if a budget or the retention is out of
   *   range.
   */
  data class Settings(
    /** The native aggregate budget, in bytes. */
    val aggregateBytes: Long,
    /** The ceiling of optional reuse retention, in bytes. */
    val reuseCeilingBytes: Long,
    /**
     * The ceiling of required encoded input, in bytes. It counts held blocks
     * only and reserves no transient-staging allowance inside it. That
     * allowance is an open model choice of the ledger, settled at cache
     * materialization.
     */
    val requiredInputBytes: Long,
    /** The retention of an exited group before its entries expire. */
    val postExitRetention: Duration,
  ) {
    init {
      require(aggregateBytes > 0) { "The aggregate budget must be positive." }

      require(reuseCeilingBytes in 0..aggregateBytes) {
        "The reuse ceiling must fit the aggregate budget."
      }

      require(requiredInputBytes in 1..aggregateBytes) {
        "The required-input ceiling must be positive and fit the aggregate."
      }

      require(postExitRetention.isPositive() && postExitRetention.isFinite()) {
        "The post-exit retention must be a positive finite duration."
      }
    }

    companion object {
      private const val MIB = 1024L * 1024

      /** The first-iteration selections of the reader qualification. */
      val QUALIFICATION =
        Settings(
          aggregateBytes = 128 * MIB,
          reuseCeilingBytes = 32 * MIB,
          requiredInputBytes = 32 * MIB,
          postExitRetention = 180.seconds,
        )
    }
  }

  /** The lifetime of one retention group. */
  sealed interface Lifetime {
    /** Requested by explicit navigation, and never activated. */
    data object RequestOwned : Lifetime

    /** The group of actual reading. It never expires by time. */
    data object Active : Lifetime

    /** Exited by actual reading. Its entries expire at [deadline]. */
    data class Inactive(val deadline: ComparableTimeMark) : Lifetime

    /** Past its deadline. Its entries serve no reuse. */
    data object Expired : Lifetime
  }

  /** The demand on whose behalf a lookup runs. */
  enum class Demand {
    /** Reading at the actual position. Only it refreshes recency. */
    ACTUAL_READING,

    /** A slider preview, which is cache-only. */
    PREVIEW,

    /** A lookup that no reading demanded. */
    SPECULATIVE,
  }

  /** A ceiling that refused required input. */
  enum class Limit {
    /** The native aggregate budget. */
    AGGREGATE,

    /** The required-input ceiling. */
    REQUIRED_INPUT,
  }

  /** One accounted physical block: the half-open interval [start, end). */
  class Range
  internal constructor(
    val group: ReaderChapterKey,
    val representation: ReaderRepresentation,
    val start: Long,
    val end: Long,
    internal val inserted: Long,
  ) {
    val bytes: Long
      get() = end - start
  }

  /** One required holder of a range. It is released exactly once. */
  class Hold internal constructor(val range: Range)

  /** The outcome of admitting a range. */
  sealed interface Admission {
    /**
     * The ledger accounts [range]. A required admission carries its [hold]; an
     * optional admission is always retained.
     */
    data class Admitted(val range: Range, val hold: Hold?) : Admission

    /** Optional retention is impossible, so the ledger accounts nothing. */
    data object NotRetained : Admission

    /** The representation is invalidated, so nothing of it is admitted. */
    data object Invalidated : Admission

    /** Required input does not fit [limit], so nothing was evicted. */
    data class Saturated(val limit: Limit) : Admission
  }

  /** The outcome of adding a required holder to a range. */
  sealed interface Holding {
    /** The range gained [hold]. */
    data class Held(val hold: Hold) : Holding

    /**
     * The range serves no new holder: it is reclaimed, which includes an unheld
     * range whose reuse expired or was evicted, or its representation is
     * invalidated. The owning source reacquires it, as after a miss.
     */
    data object Unavailable : Holding

    /** The range would exceed the required-input ceiling, so it gains none. */
    data object Saturated : Holding
  }

  /** The outcome of a lookup. */
  sealed interface Lookup {
    /** Retained [ranges] cover the whole interval. */
    data class Hit(val ranges: List<Range>) : Lookup

    /**
     * Part of the interval is not retained. The owning source reacquires it; a
     * miss is never an end of input.
     */
    data object Miss : Lookup
  }

  /** Every reason that prevents the release of one range. */
  private class Entry(val range: Range, var retained: Boolean) {
    val holds = HashSet<Hold>()
    var lastUse: Long? = null

    val released: Boolean
      get() = !retained && holds.isEmpty()
  }

  private val groups = HashMap<ReaderChapterKey, Lifetime>()
  private val entries = LinkedHashMap<Range, Entry>()
  private val invalidated = HashSet<ReaderRepresentation>()
  private var active: ReaderChapterKey? = null
  private var ordinal = 0L

  /**
   * The aggregate bytes that holders outside this ledger occupy, such as codec
   * state, workspace, and output leases. Admission evicts optional reuse to fit
   * them and denies required input that still cannot fit. A change evicts
   * nothing by itself. This model of capacity held outside the ledger is an
   * open model choice, settled at cache materialization. Assigning a value
   * outside the aggregate budget throws [IllegalArgumentException].
   */
  var externalBytes: Long = 0
    set(value) {
      require(value in 0..settings.aggregateBytes) {
        "The external bytes must fit the aggregate budget."
      }

      field = value
    }

  /** The bytes of every accounted block, each counted once. */
  val residentBytes: Long
    get() = entries.values.sumOf { it.range.bytes }

  /** The bytes of blocks that optional reuse retains. */
  val retainedBytes: Long
    get() = entries.values.filter { it.retained }.sumOf { it.range.bytes }

  /** The bytes of blocks that a required holder holds. */
  val requiredBytes: Long
    get() =
      entries.values.filter { it.holds.isNotEmpty() }.sumOf { it.range.bytes }

  /** Returns the lifetime of [group], or null when it never had demand. */
  fun lifetime(group: ReaderChapterKey): Lifetime? = groups[group]

  /** Returns whether [range] is still accounted. */
  fun isResident(range: Range): Boolean = range in entries

  /** Returns whether optional reuse retains [range]. */
  fun isRetained(range: Range): Boolean = entries[range]?.retained == true

  /**
   * Registers explicit navigation demand for [group]. A group without earlier
   * demand stays request-owned until actual reading begins; any other group
   * keeps its lifetime.
   */
  fun request(group: ReaderChapterKey) {
    enforceExpiry()
    groups.putIfAbsent(group, Lifetime.RequestOwned)
  }

  /**
   * Begins or resumes actual reading in [group]. The previous group of actual
   * reading exits, and an unexpired [group] loses its deadline. Expiry is
   * enforced first, so an expired group never regains its entries.
   */
  fun beginReading(group: ReaderChapterKey) {
    enforceExpiry()

    if (active == group) return

    leaveReading()
    groups[group] = Lifetime.Active
    active = group
  }

  /**
   * Ends actual reading in the active group, when there is one, and starts its
   * post-exit deadline. Reader closure exits the same way.
   */
  fun leaveReading() {
    val outgoing = active ?: return

    val deadline = time.markNow() + settings.postExitRetention

    groups[outgoing] = Lifetime.Inactive(deadline)
    active = null
  }

  /**
   * Withdraws cancelled, failed, superseded, or closed demand for [group]. A
   * request-owned group drops its optional claims without a deadline, and its
   * holders stay. An active or exited group keeps its lifetime and ranges.
   */
  fun cancelDemand(group: ReaderChapterKey) {
    enforceExpiry()

    if (groups[group] == Lifetime.RequestOwned) {
      dropRetention { it.group == group }
    }
  }

  /**
   * Accounts the arrival of [start, end) of [representation] for [group].
   *
   * The range is retained for reuse when it fits the reuse ceiling after
   * eviction in victim order. A [required] range is also held, and when it
   * cannot be retained it stays accounted only for its holder. Saturated
   * required input evicts nothing.
   *
   * @throws IllegalArgumentException if the interval is empty or negative.
   * @throws IllegalStateException if [group] never had demand.
   */
  fun admit(
    group: ReaderChapterKey,
    representation: ReaderRepresentation,
    start: Long,
    end: Long,
    required: Boolean,
  ): Admission {
    requireInterval(start, end)
    enforceExpiry()

    val lifetime = checkNotNull(groups[group]) { "The group has no demand." }

    if (representation in invalidated) return Admission.Invalidated

    val bytes = end - start

    if (required && bytes > settings.requiredInputBytes - requiredBytes) {
      return Admission.Saturated(Limit.REQUIRED_INPUT)
    }

    val retainable =
      lifetime != Lifetime.Expired && bytes <= settings.reuseCeilingBytes

    val retainedPlan =
      if (retainable) evictionPlan(bytes, retain = true) else null

    if (retainedPlan == null && !required) return Admission.NotRetained

    val plan =
      retainedPlan
        ?: evictionPlan(bytes, retain = false)
        ?: return Admission.Saturated(Limit.AGGREGATE)

    plan.forEach { it.dropRetention() }

    val range = Range(group, representation, start, end, nextOrdinal())

    val entry = Entry(range, retained = retainedPlan != null)

    val hold = if (required) Hold(range).also(entry.holds::add) else null

    entries[range] = entry

    return Admission.Admitted(range, hold)
  }

  /**
   * Looks up [start, end) of [representation] in [group]. Expiry is enforced
   * before reuse. Only a hit under actual-reading demand refreshes the recency
   * of the ranges that serve it.
   *
   * @throws IllegalArgumentException if the interval is empty or negative.
   */
  fun lookup(
    group: ReaderChapterKey,
    representation: ReaderRepresentation,
    start: Long,
    end: Long,
    demand: Demand,
  ): Lookup {
    requireInterval(start, end)
    enforceExpiry()

    val covering =
      covering(group, representation, start, end) ?: return Lookup.Miss

    if (demand == Demand.ACTUAL_READING) {
      val use = nextOrdinal()

      covering.forEach { it.lastUse = use }
    }

    return Lookup.Hit(covering.map { it.range })
  }

  /**
   * Adds a required holder to [range]. Expiry is enforced first, so an unheld
   * range found before its deadline serves no holder after it. An unheld range
   * gains a holder only while optional reuse retains it. A held range is live
   * input that another holder may share, even after its group expired; whether
   * an expired range that is still held admits new holders is an open model
   * choice, settled at cache materialization. An invalidated representation
   * serves none. A range that is unheld when the call arrives must fit the
   * required-input ceiling, even if it was held and released before.
   */
  fun hold(range: Range): Holding {
    enforceExpiry()

    val entry = entries[range]

    if (entry == null || range.representation in invalidated) {
      return Holding.Unavailable
    }

    val unheld = entry.holds.isEmpty()

    if (unheld && range.bytes > settings.requiredInputBytes - requiredBytes) {
      return Holding.Saturated
    }

    val hold = Hold(range).also(entry.holds::add)

    return Holding.Held(hold)
  }

  /**
   * Releases [hold]. Expiry is enforced, and the range is reclaimed when no
   * other reason retains it.
   *
   * @throws IllegalStateException if [hold] is already released or foreign.
   */
  fun release(hold: Hold) {
    val entry = entries[hold.range]

    check(entry != null && entry.holds.remove(hold)) {
      "A hold is released exactly once."
    }

    if (entry.released) entries.remove(hold.range)

    enforceExpiry()
  }

  /**
   * Marks [representation] invalid, for example on known corruption. Its ranges
   * stop serving reuse, and it admits nothing more. Holders stay. The ledger
   * keeps the token for its own lifetime; how long an invalidated
   * representation token lives is an open model choice, settled at cache
   * materialization.
   */
  fun invalidate(representation: ReaderRepresentation) {
    invalidated += representation
    dropRetention { it.representation == representation }
  }

  /**
   * Expires every exited group whose deadline has passed and reclaims its
   * unheld ranges. The ledger calls it before reuse, admission, a new holder,
   * and demand changes, and after a holder is released; the owner calls it at
   * foreground and cleanup opportunities.
   */
  fun enforceExpiry() {
    val expired = HashSet<ReaderChapterKey>()

    for (group in groups.entries) {
      val lifetime = group.value

      if (lifetime is Lifetime.Inactive && lifetime.deadline.hasPassedNow()) {
        group.setValue(Lifetime.Expired)
        expired += group.key
      }
    }

    if (expired.isNotEmpty()) dropRetention { it.group in expired }
  }

  /**
   * Returns the retained ranges of [group] that cover
   * [start, end) of [representation], or null when a byte of it is missing.
   */
  private fun covering(
    group: ReaderChapterKey,
    representation: ReaderRepresentation,
    start: Long,
    end: Long,
  ): List<Entry>? {
    val candidates =
      entries.values
        .filter {
          it.retained &&
            it.range.group == group &&
            it.range.representation == representation
        }
        .sortedBy { it.range.start }

    val covering = ArrayList<Entry>()

    var cursor = start

    for (candidate in candidates) {
      if (cursor >= end || candidate.range.start > cursor) break

      if (candidate.range.end > cursor) {
        covering += candidate
        cursor = candidate.range.end
      }
    }

    return covering.takeIf { cursor >= end }
  }

  /**
   * Returns the shortest prefix of the victim order whose eviction admits
   * [bytes] under the aggregate budget, beside [externalBytes], and, when
   * [retain] is set, under the reuse ceiling. Returns null when no such prefix
   * exists.
   */
  private fun evictionPlan(bytes: Long, retain: Boolean): List<Entry>? {
    val room = settings.aggregateBytes - externalBytes - residentBytes
    val aggregateExcess = bytes - room

    val reuseExcess =
      if (retain) {
        bytes - (settings.reuseCeilingBytes - retainedBytes)
      } else {
        Long.MIN_VALUE
      }

    var excess = maxOf(aggregateExcess, reuseExcess)

    val plan = ArrayList<Entry>()

    for (victim in victims()) {
      if (excess <= 0) break

      plan += victim
      excess -= victim.range.bytes
    }

    return plan.takeIf { excess <= 0 }
  }

  /**
   * Returns the eligible ranges in victim order: by retention class, then
   * never-used ranges by insertion before used ranges by last actual use, with
   * ties by insertion. Only unheld retained ranges are eligible.
   */
  private fun victims(): List<Entry> =
    entries.values
      .filter { it.retained && it.holds.isEmpty() }
      .sortedWith(
        compareBy(
          { victimClass(groups.getValue(it.range.group)) },
          { it.lastUse != null },
          { it.lastUse ?: it.range.inserted },
          { it.range.inserted },
        )
      )

  /**
   * Ranks a lifetime in the victim order. Expiry is enforced before every
   * eviction, so expired ranges have already left before any other class.
   */
  private fun victimClass(lifetime: Lifetime): Int =
    when (lifetime) {
      Lifetime.Expired -> 0
      Lifetime.RequestOwned -> 1
      is Lifetime.Inactive -> 2
      Lifetime.Active -> 3
    }

  /** Drops the optional claim of every range that matches [predicate]. */
  private fun dropRetention(predicate: (Range) -> Boolean) {
    entries.values.filter { predicate(it.range) }.forEach { it.dropRetention() }
  }

  /** Drops the optional claim of this range and reclaims it when unheld. */
  private fun Entry.dropRetention() {
    retained = false

    if (released) entries.remove(range)
  }

  private fun nextOrdinal(): Long {
    ordinal = Math.incrementExact(ordinal)

    return ordinal
  }

  private fun requireInterval(start: Long, end: Long) {
    require(start >= 0 && end > start) {
      "A range must be a nonempty interval at a nonnegative offset."
    }
  }
}
