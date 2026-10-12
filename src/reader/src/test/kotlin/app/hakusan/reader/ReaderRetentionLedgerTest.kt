package app.hakusan.reader

import app.hakusan.reader.ReaderRetentionLedger.Admission
import app.hakusan.reader.ReaderRetentionLedger.Demand.ACTUAL_READING
import app.hakusan.reader.ReaderRetentionLedger.Demand.PREVIEW
import app.hakusan.reader.ReaderRetentionLedger.Demand.SPECULATIVE
import app.hakusan.reader.ReaderRetentionLedger.Hold
import app.hakusan.reader.ReaderRetentionLedger.Holding
import app.hakusan.reader.ReaderRetentionLedger.Lifetime
import app.hakusan.reader.ReaderRetentionLedger.Limit
import app.hakusan.reader.ReaderRetentionLedger.Lookup
import app.hakusan.reader.ReaderRetentionLedger.Range
import app.hakusan.reader.ReaderRetentionLedger.Settings
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrowsExactly
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderRetentionLedgerTest {
  private val title = ReaderTitleKey("source", "title")
  private val groups = List(4) { ReaderChapterKey(title, "chapter-$it") }
  private val time = TestTimeSource()
  private val opened = time.markNow()

  @Test
  fun `the test profile takes the qualification settings`() {
    val settings = Settings.QUALIFICATION

    assertEquals(128 * MIB, settings.aggregateBytes)
    assertEquals(32 * MIB, settings.reuseCeilingBytes)
    assertEquals(32 * MIB, settings.requiredInputBytes)
    assertEquals(180.seconds, settings.postExitRetention)

    assertThrowsExactly(IllegalArgumentException::class.java) {
      Settings(0, 0, 8, 1.seconds)
    }

    assertThrowsExactly(IllegalArgumentException::class.java) {
      Settings(8, -1, 8, 1.seconds)
    }

    assertThrowsExactly(IllegalArgumentException::class.java) {
      Settings(8, 16, 8, 1.seconds)
    }

    assertThrowsExactly(IllegalArgumentException::class.java) {
      Settings(8, 8, 0, 1.seconds)
    }

    assertThrowsExactly(IllegalArgumentException::class.java) {
      Settings(8, 8, 16, 1.seconds)
    }

    assertThrowsExactly(IllegalArgumentException::class.java) {
      Settings(8, 8, 8, Duration.ZERO)
    }

    assertThrowsExactly(IllegalArgumentException::class.java) {
      Settings(8, 8, 8, Duration.INFINITE)
    }

    val ledger = ledger(aggregate = 8, reuse = 8, required = 8)

    assertThrowsExactly(IllegalArgumentException::class.java) {
      ledger.externalBytes = 9
    }

    assertThrowsExactly(IllegalArgumentException::class.java) {
      ledger.externalBytes = -1
    }

    assertEquals(0L, ledger.externalBytes)
  }

  @Test
  fun `a shared block counts once and keeps every reason for its release`() {
    val ledger = ledger()

    val representation = ReaderRepresentation()

    ledger.beginReading(groups[0])

    val input = ledger.admitRequired(groups[0], representation, 0, 8)

    val job = ledger.held(input.range)

    assertTrue(ledger.isRetained(input.range))
    assertEquals(8L, ledger.residentBytes)
    assertEquals(8L, ledger.retainedBytes)
    assertEquals(8L, ledger.requiredBytes)
    ledger.release(checkNotNull(input.hold))
    assertEquals(8L, ledger.requiredBytes)

    assertThrowsExactly(IllegalStateException::class.java) {
      ledger.release(checkNotNull(input.hold))
    }

    ledger.release(job)
    assertEquals(0L, ledger.requiredBytes)
    assertEquals(8L, ledger.residentBytes)

    assertEquals(
      Lookup.Hit(listOf(input.range)),
      ledger.lookup(groups[0], representation, 0, 8, SPECULATIVE),
    )

    assertThrowsExactly(IllegalStateException::class.java) {
      ledger.release(job)
    }

    val foreign = ledger()

    foreign.beginReading(groups[0])

    val held = foreign.admitRequired(groups[0], representation, 0, 8)

    assertThrowsExactly(IllegalStateException::class.java) {
      ledger.release(checkNotNull(held.hold))
    }
  }

  @Test
  fun `admission needs a nonempty interval and group demand`() {
    val ledger = ledger()

    val representation = ReaderRepresentation()

    ledger.beginReading(groups[0])

    assertThrowsExactly(IllegalArgumentException::class.java) {
      ledger.admit(groups[0], representation, 4, 4, required = false)
    }

    assertThrowsExactly(IllegalArgumentException::class.java) {
      ledger.admit(groups[0], representation, -1, 4, required = true)
    }

    assertThrowsExactly(IllegalArgumentException::class.java) {
      ledger.lookup(groups[0], representation, 8, 4, ACTUAL_READING)
    }

    assertThrowsExactly(IllegalStateException::class.java) {
      ledger.admit(groups[1], representation, 0, 4, required = false)
    }

    assertNull(ledger.lifetime(groups[1]))
    assertEquals(0L, ledger.residentBytes)
  }

  @Test
  fun `an exit starts a deadline without revoking live holders`() {
    val ledger = ledger()

    val representation = ReaderRepresentation()

    ledger.beginReading(groups[0])

    val visible = ledger.admitRequired(groups[0], representation, 0, 8)

    ledger.beginReading(groups[1])

    assertEquals(
      Lifetime.Inactive(opened + 180.seconds),
      ledger.lifetime(groups[0]),
    )

    assertEquals(Lifetime.Active, ledger.lifetime(groups[1]))
    assertTrue(ledger.isRetained(visible.range))
    time += 180.seconds
    ledger.enforceExpiry()
    assertEquals(Lifetime.Expired, ledger.lifetime(groups[0]))
    assertTrue(ledger.isResident(visible.range))
    assertFalse(ledger.isRetained(visible.range))
    assertEquals(8L, ledger.residentBytes)
    assertEquals(0L, ledger.retainedBytes)

    assertEquals(
      Lookup.Miss,
      ledger.lookup(groups[0], representation, 0, 8, ACTUAL_READING),
    )

    val late = ledger.admitRequired(groups[0], representation, 8, 12)

    assertFalse(ledger.isRetained(late.range))

    assertEquals(
      Admission.NotRetained,
      ledger.admit(groups[0], representation, 12, 16, required = false),
    )

    ledger.release(checkNotNull(visible.hold))
    ledger.release(checkNotNull(late.hold))
    assertEquals(0L, ledger.residentBytes)
  }

  @Test
  fun `the deadline runs on the injected clock through every non-exit`() {
    val ledger = ledger()

    val representation = ReaderRepresentation()

    ledger.beginReading(groups[0])

    val range = ledger.retain(groups[0], representation, 0, 8)

    val deadline = opened + 10_180.seconds

    time += 10_000.seconds
    ledger.enforceExpiry()
    ledger.request(groups[0])
    ledger.beginReading(groups[0])
    assertEquals(Lifetime.Active, ledger.lifetime(groups[0]))

    assertEquals(
      Lookup.Hit(listOf(range)),
      ledger.lookup(groups[0], representation, 0, 8, ACTUAL_READING),
    )

    ledger.leaveReading()
    ledger.leaveReading()
    assertEquals(Lifetime.Inactive(deadline), ledger.lifetime(groups[0]))
    time += 179.seconds

    assertEquals(
      Lookup.Hit(listOf(range)),
      ledger.lookup(groups[0], representation, 0, 8, ACTUAL_READING),
    )

    time += 1.seconds
    assertEquals(Lifetime.Inactive(deadline), ledger.lifetime(groups[0]))
    assertTrue(ledger.isRetained(range))

    assertEquals(
      Lookup.Miss,
      ledger.lookup(groups[0], representation, 0, 8, ACTUAL_READING),
    )

    assertEquals(Lifetime.Expired, ledger.lifetime(groups[0]))
    assertFalse(ledger.isResident(range))
    ledger.beginReading(groups[0])
    assertEquals(Lifetime.Active, ledger.lifetime(groups[0]))

    assertEquals(
      Lookup.Miss,
      ledger.lookup(groups[0], representation, 0, 8, ACTUAL_READING),
    )
  }

  @Test
  fun `reentry of an unexpired group cancels its deadline`() {
    val ledger = ledger()

    val representation = ReaderRepresentation()

    ledger.beginReading(groups[0])

    val range = ledger.retain(groups[0], representation, 0, 8)

    ledger.beginReading(groups[1])
    time += 100.seconds
    ledger.beginReading(groups[0])
    assertEquals(Lifetime.Active, ledger.lifetime(groups[0]))

    assertEquals(
      Lifetime.Inactive(opened + 280.seconds),
      ledger.lifetime(groups[1]),
    )

    time += 1_000.seconds

    assertEquals(
      Lookup.Hit(listOf(range)),
      ledger.lookup(groups[0], representation, 0, 8, ACTUAL_READING),
    )

    assertEquals(Lifetime.Expired, ledger.lifetime(groups[1]))
    ledger.leaveReading()

    assertEquals(
      Lifetime.Inactive(opened + 1_280.seconds),
      ledger.lifetime(groups[0]),
    )
  }

  @Test
  fun `cancelled demand of a never-activated group drops optional claims`() {
    val ledger = ledger()

    val representation = ReaderRepresentation()

    ledger.request(groups[2])

    val decoded = ledger.admitRequired(groups[2], representation, 0, 4)

    val handoff = decoded.range

    ledger.release(checkNotNull(decoded.hold))

    val input = ledger.admitRequired(groups[2], representation, 4, 8)

    assertEquals(Lifetime.RequestOwned, ledger.lifetime(groups[2]))
    assertTrue(ledger.isRetained(handoff))
    assertTrue(ledger.isRetained(input.range))
    ledger.cancelDemand(groups[2])
    assertEquals(Lifetime.RequestOwned, ledger.lifetime(groups[2]))
    assertFalse(ledger.isResident(handoff))
    assertTrue(ledger.isResident(input.range))
    assertFalse(ledger.isRetained(input.range))

    assertEquals(
      Lookup.Miss,
      ledger.lookup(groups[2], representation, 4, 8, ACTUAL_READING),
    )

    time += 1_000.seconds
    ledger.enforceExpiry()
    assertEquals(Lifetime.RequestOwned, ledger.lifetime(groups[2]))
    assertTrue(ledger.isResident(input.range))
    ledger.release(checkNotNull(input.hold))
    assertEquals(0L, ledger.residentBytes)
  }

  @Test
  fun `a failed return keeps the deadline and ranges of an activated group`() {
    val ledger = ledger()

    val representation = ReaderRepresentation()

    ledger.beginReading(groups[0])

    val exited = ledger.retain(groups[0], representation, 0, 8)

    ledger.beginReading(groups[1])

    val current = ledger.retain(groups[1], representation, 0, 8)

    time += 50.seconds
    ledger.request(groups[0])
    ledger.cancelDemand(groups[0])
    ledger.request(groups[1])
    ledger.cancelDemand(groups[1])

    assertEquals(
      Lifetime.Inactive(opened + 180.seconds),
      ledger.lifetime(groups[0]),
    )

    assertEquals(Lifetime.Active, ledger.lifetime(groups[1]))

    assertEquals(
      Lookup.Hit(listOf(exited)),
      ledger.lookup(groups[0], representation, 0, 8, SPECULATIVE),
    )

    assertEquals(
      Lookup.Hit(listOf(current)),
      ledger.lookup(groups[1], representation, 0, 8, ACTUAL_READING),
    )
  }

  @Test
  fun `a missing byte makes a miss and never a partial hit`() {
    val ledger = ledger()

    val representation = ReaderRepresentation()

    val replacement = ReaderRepresentation()

    ledger.beginReading(groups[0])
    ledger.request(groups[1])

    val head = ledger.retain(groups[0], representation, 0, 4)

    val tail = ledger.retain(groups[0], representation, 8, 12)

    val substitute = ledger.retain(groups[0], replacement, 4, 8)

    assertEquals(Lookup.Miss, ledger.read(groups[0], representation, 0, 12))
    assertEquals(Lookup.Miss, ledger.read(groups[0], representation, 4, 8))
    assertEquals(Lookup.Miss, ledger.read(groups[0], representation, 3, 9))
    assertEquals(Lookup.Miss, ledger.read(groups[1], representation, 0, 4))
    assertEquals(Lookup.Miss, ledger.read(groups[2], representation, 0, 4))
    assertEquals(Lookup.Miss, ledger().read(groups[0], representation, 0, 4))

    assertEquals(
      Lookup.Hit(listOf(head)),
      ledger.read(groups[0], representation, 1, 3),
    )

    val middle = ledger.retain(groups[0], representation, 4, 8)

    assertEquals(
      Lookup.Hit(listOf(head, middle, tail)),
      ledger.read(groups[0], representation, 0, 12),
    )

    ledger.invalidate(representation)
    assertEquals(Lookup.Miss, ledger.read(groups[0], representation, 0, 4))

    assertEquals(
      Admission.Invalidated,
      ledger.admit(groups[0], representation, 0, 4, required = true),
    )

    assertEquals(
      listOf(substitute),
      resident(ledger, head, middle, tail, substitute),
    )

    assertEquals(
      Lookup.Hit(listOf(substitute)),
      ledger.read(groups[0], replacement, 4, 8),
    )
  }

  @Test
  fun `expired ranges leave first, then request-owned, inactive, and active`() {
    val ledger = ledger(reuse = 16)

    ledger.beginReading(groups[0])

    val expired = ledger.retain(groups[0])

    ledger.beginReading(groups[1])

    val inactive = ledger.retain(groups[1])

    time += 100.seconds
    ledger.beginReading(groups[2])

    val active = ledger.retain(groups[2])

    ledger.request(groups[3])

    val requested = ledger.retain(groups[3])

    val ranges = arrayOf(expired, requested, inactive, active)

    time += 80.seconds
    assertTrue(ledger.isRetained(expired))
    ledger.retain(groups[2])
    assertEquals(listOf(requested, inactive, active), resident(ledger, *ranges))
    ledger.retain(groups[2])
    assertEquals(listOf(inactive, active), resident(ledger, *ranges))
    ledger.retain(groups[2])
    assertEquals(listOf(active), resident(ledger, *ranges))
    ledger.retain(groups[2])
    assertEquals(emptyList<Range>(), resident(ledger, *ranges))
    assertEquals(16L, ledger.retainedBytes)
  }

  @Test
  fun `never-used ranges leave by insertion before used ranges by last use`() {
    val ledger = ledger(reuse = 16)

    val representation = ReaderRepresentation()

    ledger.beginReading(groups[0])

    val older = ledger.retain(groups[0], representation, 0, 4)

    val newer = ledger.retain(groups[0], representation, 4, 8)

    val latest = ledger.retain(groups[0], representation, 8, 12)

    val unused = ledger.retain(groups[0], representation, 12, 16)

    val ranges = arrayOf(older, newer, latest, unused)

    ledger.read(groups[0], representation, 4, 8)
    ledger.read(groups[0], representation, 0, 4)
    ledger.read(groups[0], representation, 8, 12)
    ledger.retain(groups[0], representation, 16, 20)
    assertEquals(listOf(older, newer, latest), resident(ledger, *ranges))
    ledger.retain(groups[0], representation, 20, 28)
    assertEquals(listOf(older, latest), resident(ledger, *ranges))

    val tied = ledger(reuse = 8)

    tied.beginReading(groups[0])

    val first = tied.retain(groups[0], representation, 0, 4)

    val second = tied.retain(groups[0], representation, 4, 8)

    tied.read(groups[0], representation, 4, 8)
    tied.read(groups[0], representation, 0, 8)
    tied.retain(groups[0], representation, 8, 12)
    assertEquals(listOf(second), resident(tied, first, second))
  }

  @Test
  fun `only actual reading refreshes recency`() {
    val ledger = ledger(reuse = 12)

    val representation = ReaderRepresentation()

    ledger.beginReading(groups[0])

    val previewed = ledger.retain(groups[0], representation, 0, 4)

    val speculated = ledger.retain(groups[0], representation, 4, 8)

    val used = ledger.retain(groups[0], representation, 8, 12)

    assertEquals(
      Lookup.Hit(listOf(previewed)),
      ledger.lookup(groups[0], representation, 0, 4, PREVIEW),
    )

    assertEquals(
      Lookup.Hit(listOf(speculated)),
      ledger.lookup(groups[0], representation, 4, 8, SPECULATIVE),
    )

    assertEquals(Lookup.Miss, ledger.read(groups[0], representation, 0, 16))

    assertEquals(
      Lookup.Hit(listOf(used)),
      ledger.read(groups[0], representation, 8, 12),
    )

    val arrived = ledger.retain(groups[0], representation, 12, 16)

    val ranges = arrayOf(previewed, speculated, used, arrived)

    assertEquals(listOf(speculated, used, arrived), resident(ledger, *ranges))
    ledger.retain(groups[0], representation, 16, 20)
    assertEquals(listOf(used, arrived), resident(ledger, *ranges))
    ledger.retain(groups[0], representation, 20, 24)
    assertEquals(listOf(used), resident(ledger, *ranges))
  }

  @Test
  fun `a representation above the reuse ceiling keeps its useful ranges`() {
    val ledger = ledger(reuse = 8)

    val representation = ReaderRepresentation()

    ledger.beginReading(groups[0])

    val prefix = ledger.retain(groups[0], representation, 0, 4)

    val middle = ledger.retain(groups[0], representation, 4, 8)

    val suffix = ledger.retain(groups[0], representation, 8, 12)

    assertEquals(
      listOf(middle, suffix),
      resident(ledger, prefix, middle, suffix),
    )

    assertEquals(Lookup.Miss, ledger.read(groups[0], representation, 0, 12))

    assertEquals(
      Lookup.Hit(listOf(middle, suffix)),
      ledger.read(groups[0], representation, 4, 12),
    )

    val reacquired = ledger.retain(groups[0], representation, 0, 4)

    val ranges = arrayOf(middle, suffix, reacquired)

    assertEquals(listOf(suffix, reacquired), resident(ledger, *ranges))

    assertEquals(
      Admission.NotRetained,
      ledger.admit(groups[0], representation, 0, 12, required = false),
    )

    assertEquals(listOf(suffix, reacquired), resident(ledger, *ranges))
  }

  @Test
  fun `required input continues when optional retention is impossible`() {
    val ledger = ledger(reuse = 8)

    val representation = ReaderRepresentation()

    ledger.beginReading(groups[0])

    val pinned = ledger.admitRequired(groups[0], representation, 0, 8)

    assertEquals(
      Admission.NotRetained,
      ledger.admit(groups[0], representation, 8, 12, required = false),
    )

    val input = ledger.admitRequired(groups[0], representation, 8, 12)

    val large = ledger.admitRequired(groups[0], representation, 12, 24)

    assertTrue(ledger.isRetained(pinned.range))
    assertFalse(ledger.isRetained(input.range))
    assertFalse(ledger.isRetained(large.range))
    assertEquals(24L, ledger.residentBytes)
    assertEquals(8L, ledger.retainedBytes)
    assertEquals(24L, ledger.requiredBytes)
    assertEquals(Lookup.Miss, ledger.read(groups[0], representation, 8, 12))
    ledger.release(checkNotNull(input.hold))
    ledger.release(checkNotNull(large.hold))
    assertFalse(ledger.isResident(input.range))
    assertFalse(ledger.isResident(large.range))
    assertEquals(8L, ledger.residentBytes)
  }

  @Test
  fun `the aggregate budget denies required input after optional reuse`() {
    val ledger = ledger(aggregate = 16, reuse = 8, required = 16)

    val representation = ReaderRepresentation()

    ledger.externalBytes = 8
    ledger.beginReading(groups[0])

    val cached = ledger.retain(groups[0], representation, 0, 4)

    val first = ledger.admitRequired(groups[0], representation, 4, 8)

    val second = ledger.admitRequired(groups[0], representation, 8, 12)

    assertFalse(ledger.isResident(cached))

    assertEquals(
      Admission.Saturated(Limit.AGGREGATE),
      ledger.admit(groups[0], representation, 12, 13, required = true),
    )

    assertEquals(8L, ledger.residentBytes)
    assertEquals(8L, ledger.requiredBytes)
    ledger.release(checkNotNull(first.hold))

    val third = ledger.admitRequired(groups[0], representation, 12, 16)

    assertFalse(ledger.isResident(first.range))
    assertTrue(ledger.isResident(second.range))
    assertTrue(ledger.isRetained(third.range))
  }

  @Test
  fun `required input saturates at its own ceiling`() {
    val ledger = ledger(aggregate = 64, reuse = 8, required = 16)

    val cachedRepresentation = ReaderRepresentation()

    val representation = ReaderRepresentation()

    ledger.beginReading(groups[0])

    val cached = ledger.retain(groups[0], cachedRepresentation, 0, 8)

    val input = ledger.admitRequired(groups[0], representation, 0, 16)

    assertEquals(
      Admission.Saturated(Limit.REQUIRED_INPUT),
      ledger.admit(groups[0], representation, 16, 17, required = true),
    )

    val shared = ledger.held(input.range)

    assertEquals(Holding.Saturated, ledger.hold(cached))
    assertTrue(ledger.isRetained(cached))
    assertEquals(24L, ledger.residentBytes)
    assertEquals(16L, ledger.requiredBytes)

    val arrived = ledger.retain(groups[0], representation, 16, 20)

    assertTrue(ledger.isRetained(arrived))
    ledger.release(checkNotNull(input.hold))
    ledger.release(shared)
    assertEquals(0L, ledger.requiredBytes)
    ledger.held(arrived)
    assertEquals(4L, ledger.requiredBytes)
  }

  @Test
  fun `an unheld range found before its deadline serves no holder after it`() {
    val ledger = ledger(reuse = 32)

    val representation = ReaderRepresentation()

    ledger.beginReading(groups[0])

    val found = ledger.retain(groups[0], representation, 0, 8)

    val enforced = ledger.retain(groups[0], representation, 8, 16)

    val live = ledger.admitRequired(groups[0], representation, 16, 20)

    ledger.beginReading(groups[1])
    time += 179.seconds

    assertEquals(
      Lookup.Hit(listOf(found)),
      ledger.lookup(groups[0], representation, 0, 8, SPECULATIVE),
    )

    time += 21.seconds
    assertInstanceOf(Lifetime.Inactive::class.java, ledger.lifetime(groups[0]))
    assertEquals(Holding.Unavailable, ledger.hold(found))
    assertEquals(Lifetime.Expired, ledger.lifetime(groups[0]))
    assertFalse(ledger.isResident(found))
    ledger.enforceExpiry()
    assertEquals(Holding.Unavailable, ledger.hold(enforced))
    assertFalse(ledger.isResident(enforced))
    assertFalse(ledger.isRetained(live.range))

    val shared = ledger.held(live.range)

    ledger.release(checkNotNull(live.hold))
    ledger.release(shared)
    assertEquals(0L, ledger.residentBytes)
  }

  @Test
  fun `an invalidated representation serves no new holder`() {
    val ledger = ledger()

    val representation = ReaderRepresentation()

    ledger.beginReading(groups[0])

    val cached = ledger.retain(groups[0], representation, 0, 4)

    val input = ledger.admitRequired(groups[0], representation, 4, 8)

    ledger.invalidate(representation)
    assertEquals(Holding.Unavailable, ledger.hold(cached))
    assertEquals(Holding.Unavailable, ledger.hold(input.range))
    assertTrue(ledger.isResident(input.range))
    assertEquals(4L, ledger.requiredBytes)
    ledger.release(checkNotNull(input.hold))
    assertEquals(0L, ledger.residentBytes)
  }

  @Test
  fun `the last release of expired storage reclaims it`() {
    val ledger = ledger()

    val representation = ReaderRepresentation()

    ledger.beginReading(groups[0])

    val input = ledger.admitRequired(groups[0], representation, 0, 8)

    ledger.beginReading(groups[1])
    time += 180.seconds
    assertTrue(ledger.isRetained(input.range))
    ledger.release(checkNotNull(input.hold))
    assertEquals(Lifetime.Expired, ledger.lifetime(groups[0]))
    assertFalse(ledger.isResident(input.range))
    assertEquals(0L, ledger.residentBytes)
    assertEquals(0L, ledger.retainedBytes)
  }

  @Test
  fun `expired optional admission does not evict active retained bytes`() {
    val ledger = ledger(aggregate = 8, reuse = 8, required = 8)

    val representation = ReaderRepresentation()

    ledger.beginReading(groups[0])
    ledger.beginReading(groups[1])

    val active = ledger.retain(groups[1], representation, 0, 8)

    time += 180.seconds

    assertEquals(
      Admission.NotRetained,
      ledger.admit(groups[0], ReaderRepresentation(), 0, 8, required = false),
    )

    assertTrue(ledger.isRetained(active))
    assertEquals(8L, ledger.residentBytes)
  }

  @Test
  fun `expired required input evicts optional reuse without gaining reuse`() {
    val ledger = ledger(aggregate = 8, reuse = 8, required = 8)

    val representation = ReaderRepresentation()

    ledger.beginReading(groups[0])
    ledger.beginReading(groups[1])

    val active = ledger.retain(groups[1], ReaderRepresentation(), 0, 8)

    time += 180.seconds

    val input = ledger.admitRequired(groups[0], representation, 0, 8)

    assertFalse(ledger.isResident(active))
    assertFalse(ledger.isRetained(input.range))
    assertEquals(Lifetime.Expired, ledger.lifetime(groups[0]))
    assertEquals(Lookup.Miss, ledger.read(groups[0], representation, 0, 8))
    assertEquals(8L, ledger.residentBytes)
    assertEquals(0L, ledger.retainedBytes)
    ledger.release(checkNotNull(input.hold))
    assertEquals(0L, ledger.residentBytes)
  }

  @Test
  fun `required input above the reuse ceiling evicts optional reuse`() {
    val ledger = ledger(aggregate = 16, reuse = 8, required = 16)

    ledger.beginReading(groups[0])

    val cached = ledger.retain(groups[0], ReaderRepresentation(), 0, 8)

    val input = ledger.admitRequired(groups[0], ReaderRepresentation(), 0, 12)

    assertFalse(ledger.isResident(cached))
    assertFalse(ledger.isRetained(input.range))
    assertEquals(12L, ledger.residentBytes)
    assertEquals(0L, ledger.retainedBytes)
    ledger.release(checkNotNull(input.hold))
    assertEquals(0L, ledger.residentBytes)
  }

  @Test
  fun `a failed admission evicts no partial victim prefix`() {
    val ledger = ledger(aggregate = 16, reuse = 8, required = 16)

    val representation = ReaderRepresentation()

    ledger.beginReading(groups[0])

    val pinned = ledger.admitRequired(groups[0], representation, 0, 4)

    val victim = ledger.retain(groups[0], representation, 4, 8)

    assertEquals(
      Admission.NotRetained,
      ledger.admit(groups[0], representation, 8, 16, required = false),
    )

    assertTrue(ledger.isRetained(victim))
    ledger.externalBytes = 4

    assertEquals(
      Admission.Saturated(Limit.AGGREGATE),
      ledger.admit(groups[0], representation, 8, 20, required = true),
    )

    assertTrue(ledger.isRetained(victim))
    assertTrue(ledger.isRetained(pinned.range))
    assertEquals(8L, ledger.residentBytes)
    ledger.externalBytes = 0

    val input = ledger.admitRequired(groups[0], representation, 8, 20)

    assertFalse(ledger.isResident(victim))
    assertFalse(ledger.isRetained(input.range))
    assertEquals(16L, ledger.residentBytes)
  }

  @Test
  fun `a preview of another group never activates it`() {
    val ledger = ledger()

    val representation = ReaderRepresentation()

    ledger.beginReading(groups[0])

    val exited = ledger.retain(groups[0], representation, 0, 4)

    ledger.beginReading(groups[1])
    ledger.request(groups[2])

    val requested = ledger.retain(groups[2], representation, 0, 4)

    val deadline = Lifetime.Inactive(opened + 180.seconds)

    time += 10.seconds

    assertEquals(
      Lookup.Hit(listOf(exited)),
      ledger.lookup(groups[0], representation, 0, 4, PREVIEW),
    )

    assertEquals(
      Lookup.Hit(listOf(requested)),
      ledger.lookup(groups[2], representation, 0, 4, PREVIEW),
    )

    assertEquals(deadline, ledger.lifetime(groups[0]))
    assertEquals(Lifetime.RequestOwned, ledger.lifetime(groups[2]))
    assertEquals(Lifetime.Active, ledger.lifetime(groups[1]))
  }

  @Test
  fun `qualification required input saturates below the aggregate`() {
    val ledger = ReaderRetentionLedger(Settings.QUALIFICATION, time)

    ledger.beginReading(groups[0])
    ledger.admitRequired(groups[0], ReaderRepresentation(), 0, 16 * MIB)
    ledger.admitRequired(groups[0], ReaderRepresentation(), 0, 16 * MIB)

    assertEquals(
      Admission.Saturated(Limit.REQUIRED_INPUT),
      ledger.admit(groups[0], ReaderRepresentation(), 0, 1, required = true),
    )

    assertEquals(32 * MIB, ledger.residentBytes)
    assertTrue(ledger.residentBytes < Settings.QUALIFICATION.aggregateBytes)
  }

  private fun ledger(
    aggregate: Long = 64,
    reuse: Long = 16,
    required: Long = 32,
  ): ReaderRetentionLedger {
    val retention = Settings.QUALIFICATION.postExitRetention

    val settings = Settings(aggregate, reuse, required, retention)

    return ReaderRetentionLedger(settings, time)
  }

  /** Admits an optional range that the ledger must retain. */
  private fun ReaderRetentionLedger.retain(
    group: ReaderChapterKey,
    representation: ReaderRepresentation = ReaderRepresentation(),
    start: Long = 0,
    end: Long = 4,
  ): Range {
    val admission = admit(group, representation, start, end, required = false)

    val admitted = assertInstanceOf(Admission.Admitted::class.java, admission)

    assertTrue(isRetained(admitted.range))

    return admitted.range
  }

  /** Admits a required range that the ledger must accept. */
  private fun ReaderRetentionLedger.admitRequired(
    group: ReaderChapterKey,
    representation: ReaderRepresentation,
    start: Long,
    end: Long,
  ): Admission.Admitted {
    val admission = admit(group, representation, start, end, required = true)

    return assertInstanceOf(Admission.Admitted::class.java, admission)
  }

  /** Adds a required holder that the ledger must grant. */
  private fun ReaderRetentionLedger.held(range: Range): Hold {
    val holding = assertInstanceOf(Holding.Held::class.java, hold(range))

    return holding.hold
  }

  /** Looks up an interval under actual-reading demand. */
  private fun ReaderRetentionLedger.read(
    group: ReaderChapterKey,
    representation: ReaderRepresentation,
    start: Long,
    end: Long,
  ): Lookup = lookup(group, representation, start, end, ACTUAL_READING)

  private fun resident(
    ledger: ReaderRetentionLedger,
    vararg ranges: Range,
  ): List<Range> = ranges.filter(ledger::isResident)

  private companion object {
    const val MIB = 1024L * 1024
  }
}
