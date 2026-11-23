package app.hakusan.reader

import app.hakusan.reader.san.ScriptedSan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Checks which targets the pool keeps for a later output and which it recycles,
 * on the thread of the test in place of the worker.
 */
class ReaderTargetPoolTest {
  private val platform = ScriptedPlatform(ScriptedSan())

  @Test
  fun `an output of the same geometry gets the targets of the one before`() {
    val pool = pool()
    val first = create(pool)
    val second = create(pool)

    first.rows = 3
    hand(first)
    pool.release(first)
    pool.release(second)

    // Presentation still draws the first one.
    assertSame(second, create(pool))
    assertNotSame(first, create(pool))

    pool.post(first, first.ordinal)
    pool.applyReturns()

    val reused = create(pool)

    assertSame(first, reused)
    assertEquals(0, reused.rows)
    assertTrue(reused.needed)
    assertEquals(3, platform.created.size)
    assertEquals(emptyList<ReaderTarget>(), platform.recycled)
  }

  @Test
  fun `a target with or without alpha serves no output of the other kind`() {
    val pool = pool()
    val opaque = create(pool)

    pool.release(opaque)

    val alpha = checkNotNull(pool.create(WIDTH, HEIGHT, opaque = false))

    assertNotSame(opaque, alpha)
    assertEquals(listOf<ReaderTarget>(opaque.backing), platform.recycled)
  }

  @Test
  fun `an output of another geometry recycles the kept targets first`() {
    val pool = pool(count = 2)
    val first = create(pool)
    val second = create(pool)

    pool.release(first)
    pool.release(second)

    // Both kept targets fill the ceiling, and neither refuses the new one.
    val other = pool.create(WIDTH, HEIGHT + 1, opaque = true)

    assertNotNull(other)

    assertEquals(
      setOf(first.backing, second.backing),
      platform.recycled.toSet(),
    )

    // The geometry that was reserved last is the one that stays.
    pool.release(checkNotNull(other))

    assertTrue(other.kept)
    assertEquals(2, platform.recycled.size)
  }

  @Test
  fun `a target of an earlier geometry is recycled when it returns`() {
    val pool = pool()
    val earlier = create(pool)

    hand(earlier)
    pool.release(earlier)

    val later = checkNotNull(pool.create(WIDTH, HEIGHT + 1, opaque = true))

    pool.post(earlier, earlier.ordinal)
    pool.applyReturns()

    assertEquals(listOf<ReaderTarget>(earlier.backing), platform.recycled)
    assertTrue(later.needed)
  }

  @Test
  fun `no more than the targets of one output are kept`() {
    val pool = pool()
    val targets = List(3) { create(pool) }

    targets.forEach(pool::release)

    assertEquals(listOf<ReaderTarget>(targets[2].backing), platform.recycled)
    assertSame(targets[0], create(pool))
    assertSame(targets[1], create(pool))
    assertEquals(3, platform.created.size)
  }

  @Test
  fun `a ceiling refuses a target only for targets in use`() {
    val pool = pool(count = 2)
    val needed = create(pool)
    val held = create(pool)

    hand(held)
    pool.release(held)

    assertNull(pool.create(WIDTH, HEIGHT, opaque = true))
    assertTrue(pool.awaitsRoom)

    pool.post(held, held.ordinal)
    pool.applyReturns()

    assertFalse(pool.awaitsRoom)
    assertSame(held, create(pool))
    assertTrue(needed.needed)
  }

  @Test
  fun `the return of a lease that was never handed over frees no later one`() {
    val pool = pool()
    val target = create(pool)

    // A lease of the first output that presentation refused.
    val refused = target.issue()

    pool.release(target)

    val reused = create(pool)

    assertSame(target, reused)
    hand(reused)
    assertNotEquals(refused, reused.ordinal)

    pool.post(reused, refused)
    pool.applyReturns()

    assertTrue(reused.held)

    pool.post(reused, reused.ordinal)
    pool.applyReturns()

    assertFalse(reused.held)
  }

  @Test
  fun `a target whose copy failed is recycled and serves no later output`() {
    val pool = pool()
    val target = create(pool)

    target.failed = true
    pool.release(target)

    assertEquals(listOf<ReaderTarget>(target.backing), platform.recycled)
    assertNotSame(target, create(pool))
  }

  @Test
  fun `a drained pool keeps nothing and empties with the last return`() {
    val pool = pool()
    val kept = create(pool)
    val held = create(pool)
    var emptied = 0

    pool.onEmpty = { emptied += 1 }
    hand(held)
    pool.release(kept)
    pool.release(held)
    pool.drain()

    assertEquals(listOf<ReaderTarget>(kept.backing), platform.recycled)
    assertFalse(pool.isEmpty)
    assertEquals(0, emptied)

    pool.post(held, held.ordinal)
    pool.applyReturns()

    assertTrue(pool.isEmpty)
    assertEquals(1, emptied)
    assertEquals(platform.created.toSet(), platform.recycled.toSet())
    assertEquals(emptyList<String>(), platform.violations)
  }

  private fun pool(count: Int = 4): ReaderTargetPool =
    ReaderTargetPool(platform, maxBytes = Long.MAX_VALUE, maxCount = count)

  private fun create(pool: ReaderTargetPool): ReaderTargetPool.Target =
    checkNotNull(pool.create(WIDTH, HEIGHT, opaque = true))

  /** Hands a display lease of [target] to presentation, as an output does. */
  private fun hand(target: ReaderTargetPool.Target) {
    target.hand(target.issue())
  }

  private companion object {
    const val WIDTH = 4
    const val HEIGHT = 3
  }
}
