package app.hakusan.reader

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Stress cases on the multi-threaded default dispatcher.
 *
 * Interleavings vary between runs, and every assertion holds for each of them.
 */
class ReaderSessionConcurrencyTest {
  private val book = ReaderTestBook()

  @Test
  fun `concurrent deliveries, replacements, and closes keep the lease`() =
    readerStressTest {
      val gate = ReaderSessionGate()

      val ends = ConcurrentHashMap<ReaderSession, AtomicInteger>()

      val current = AtomicReference(track(gate.start(), ends))

      val holders = AtomicInteger()

      val committed = HashMap<ReaderSession, Long>()

      coroutineScope {
        repeat(WORKERS) { worker ->
          launch {
            val random = Random(worker)

            repeat(ROUNDS) {
              val session = current.get()

              val events =
                List(random.nextInt(1, 4)) { gate.issue(session) }
                  .mapNotNull {
                    (it as? ReaderSessionAcceptance.Accepted)?.value
                  }

              for (event in events.shuffled(random)) {
                val fails = random.nextInt(8) == 0

                val delivery = launch {
                  try {
                    gate.withCurrent(event) { recency ->
                      check(holders.incrementAndGet() == 1)

                      try {
                        assertEquals(0, ends.getValue(event.session).get())

                        val last = committed[event.session] ?: -1L

                        val expected =
                          if (event.ordinal > last) {
                            ReaderEventRecency.CURRENT
                          } else {
                            ReaderEventRecency.REORDERED
                          }

                        assertEquals(expected, recency)
                        yield()

                        if (fails) {
                          throw InjectedFailure()
                        }

                        if (recency == ReaderEventRecency.CURRENT) {
                          committed[event.session] = event.ordinal
                        }
                      } finally {
                        holders.decrementAndGet()
                      }
                    }
                  } catch (_: InjectedFailure) {}
                }

                if (random.nextInt(8) == 0) {
                  delivery.cancel()
                }

                delivery.join()
              }
            }
          }
        }

        launch {
          val random = Random(WORKERS)

          repeat(REPLACEMENTS) {
            val previous = current.get()

            when (random.nextInt(3)) {
              0 -> gate.close(previous)
              1 -> current.set(track(gate.start(), ends))
              else -> {
                current.set(track(gate.start(), ends))
                gate.close(previous)
              }
            }

            yield()
          }
        }
      }

      gate.close(current.get())
      assertTrue(ends.values.all { it.get() == 1 })
    }

  @Test
  fun `concurrent navigation emits each ordinal once until the session ends`() =
    readerStressTest {
      val gate = ReaderSessionGate()

      repeat(SESSIONS) { round ->
        val session = gate.start()

        val ended = AtomicBoolean()

        session.invokeOnEnd { ended.set(true) }

        val reader = ReaderNavigation(gate, session, book.input)

        val events = ConcurrentLinkedQueue<ReaderEvent>()

        val steps = AtomicInteger()

        val halfway = CompletableDeferred<Unit>()

        coroutineScope {
          repeat(WORKERS) { worker ->
            launch {
              val random = Random(round * WORKERS + worker)

              repeat(ROUNDS) {
                navigate(reader, random)?.let { event ->
                  events += event

                  gate.withCurrent(event.identity) { assertFalse(ended.get()) }
                }

                if (steps.incrementAndGet() == WORKERS * ROUNDS / 2) {
                  halfway.complete(Unit)
                }
              }
            }
          }

          launch {
            halfway.await()

            if (round % 2 == 0) {
              gate.close(session)
            } else {
              gate.start()
            }
          }
        }

        assertTrue(ended.get())
        assertNull(reader.snapshot().preview)
        assertNull(reader.snapshot().requested)

        assertEquals(
          ReaderNavigationRejection.CLOSING,
          reader.request(book.chapters[0].key).rejection(),
        )

        val ordinals = events.map { it.identity.ordinal }.sorted()

        assertEquals(List(ordinals.size) { it.toLong() }, ordinals)
        assertTrue(events.all { it.identity.session === session })
      }
    }

  /** Performs one random navigation step and returns its event, if any. */
  private suspend fun navigate(
    reader: ReaderNavigation,
    random: Random,
  ): ReaderEvent? {
    val actual = reader.snapshot().actual

    return when (random.nextInt(6)) {
      0 -> {
        reader.request(book.chapters.random(random).key, randomStart(random))

        null
      }

      1 -> start(reader)

      2 -> {
        reader.preview(book.chapters.random(random).key, random.nextInt(-1, 4))

        null
      }

      3 -> {
        reader.releasePreview()

        null
      }

      4 -> {
        reader.snapshot().requested?.let { reader.cancelRequest(it) }

        null
      }

      else -> actual?.let { gesture(reader, it) }
    }
  }

  private suspend fun start(reader: ReaderNavigation): ReaderEvent? {
    val request = reader.snapshot().requested ?: return null

    val content = book.content.single { it.chapter.key == request.chapter }

    val target = content.resolve(request.start)

    val position =
      if (target is ReaderStartTarget.Available) {
        target.position
      } else {
        content.positionAt(0)
      }

    val result = reader.actualStarted(request, content, position)

    return (result as? ReaderNavigationResult.Accepted)?.value
  }

  private suspend fun gesture(
    reader: ReaderNavigation,
    actual: ReaderPosition,
  ): ReaderEvent? {
    val end = book.content.single { it.chapter.key == actual.chapter }

    val position = end.positionAt(end.units.lastIndex)

    if (book.input.next(actual.chapter) != null) {
      reader.nextChapterAfterEnd(position)

      return null
    }

    val result = reader.completeFinalAfterEnd(position)

    return (result as? ReaderNavigationResult.Accepted)?.value
  }

  private fun randomStart(random: Random): ReaderStart =
    if (random.nextBoolean()) {
      ReaderStart.Beginning
    } else {
      val kind = ReaderUnitKind.entries.random(random)

      ReaderStart.Resume(kind, random.nextInt(3))
    }

  private fun track(
    session: ReaderSession,
    ends: MutableMap<ReaderSession, AtomicInteger>,
  ): ReaderSession {
    val count = AtomicInteger()

    ends[session] = count
    session.invokeOnEnd { count.incrementAndGet() }

    return session
  }

  private class InjectedFailure : RuntimeException()

  private companion object {
    const val WORKERS = 8
    const val ROUNDS = 250
    const val REPLACEMENTS = 400
    const val SESSIONS = 24
  }
}
