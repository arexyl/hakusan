package app.hakusan.extensions

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class ChapterRefreshGateTest {
  private val source = SourceIdentity("source")
  private val title = SourceTitleKey(source, "title")

  @Test
  fun `issued requests are unique and increasing`() {
    val gate = ChapterRefreshGate(title)

    val requests = List(3) { gate.issue() }

    assertEquals(listOf(0L, 1L, 2L), requests.map { it.ordinal })
    assertEquals(3, requests.toSet().size)
    assertTrue(requests.all { it.title == title })
  }

  @Test
  fun `a newer request supersedes and the current one is accepted once`() {
    val gate = ChapterRefreshGate(title)

    val superseded = completed(gate.issue())

    val current = completed(gate.issue())

    assertSame(
      ChapterRefreshAcceptance.RejectedNotCurrent,
      gate.accept(superseded),
    )

    assertEquals(current.result, gate.accept(current).acceptedResult())

    assertSame(
      ChapterRefreshAcceptance.RejectedNotCurrent,
      gate.accept(current),
    )
  }

  @Test
  fun `a late failure cannot replace the current request`() {
    val gate = ChapterRefreshGate(title)

    val late =
      ChapterRefreshCompletion.failed(gate.issue(), SourceFailure.Unavailable)

    val current =
      ChapterRefreshCompletion.failed(gate.issue(), SourceFailure.Unavailable)

    assertSame(ChapterRefreshAcceptance.RejectedNotCurrent, gate.accept(late))

    assertEquals(
      SourceResult.Failure(SourceFailure.Unavailable),
      gate.accept(current).acceptedResult(),
    )
  }

  @Test
  fun `a current validation failure is the accepted result`() {
    val gate = ChapterRefreshGate(title)

    val completion =
      ChapterRefreshCompletion.completed(
        request = gate.issue(),
        status = ChapterSequenceStatus.PARTIAL,
        chapters = listOf(chapter(title)),
      )

    assertEquals(
      SourceFailure.InvalidChapterSnapshot(
        ChapterSnapshotRejection.PARTIAL_SEQUENCE
      ),
      gate.accept(completion).acceptedResult().failureValue(),
    )
  }

  @Test
  fun `a future request of the same gate is not current`() {
    val gate = ChapterRefreshGate(title)

    val current = gate.issue()

    val future = current.copy(ordinal = current.ordinal + 1L)

    assertSame(
      ChapterRefreshAcceptance.RejectedNotCurrent,
      gate.accept(completed(future)),
    )

    assertEquals(
      completed(current).result,
      gate.accept(completed(current)).acceptedResult(),
    )
  }

  @Test
  fun `a completion for another title is not current`() {
    val gate = ChapterRefreshGate(title)

    gate.issue()

    val otherTitle = SourceTitleKey(source, "other-title")

    val otherRequest = ChapterRefreshGate(otherTitle).issue()

    val completion =
      ChapterRefreshCompletion.completed(
        request = otherRequest,
        status = ChapterSequenceStatus.COMPLETE,
        chapters = listOf(chapter(otherTitle)),
      )

    assertSame(
      ChapterRefreshAcceptance.RejectedNotCurrent,
      gate.accept(completion),
    )
  }

  @Test
  fun `gates of one title cannot exchange completions`() {
    val firstGate = ChapterRefreshGate(title)

    val secondGate = ChapterRefreshGate(title)

    val first = firstGate.issue()

    val second = secondGate.issue()

    assertEquals(first.ordinal, second.ordinal)
    assertNotEquals(first, second)

    assertSame(
      ChapterRefreshAcceptance.RejectedNotCurrent,
      firstGate.accept(completed(second)),
    )
  }

  @Test
  fun `a structurally equal copy of the current request is current`() {
    val gate = ChapterRefreshGate(title)

    val request = gate.issue()

    val copy = request.copy()

    assertNotSame(request, copy)
    assertEquals(request, copy)

    assertEquals(
      completed(copy).result,
      gate.accept(completed(copy)).acceptedResult(),
    )
  }

  @Test
  fun `issue racing with acceptance leaves the issued request current`() {
    repeat(25) {
      val gate = ChapterRefreshGate(title)

      val previous = completed(gate.issue())

      val results = raceAll(listOf(gate::issue, { gate.accept(previous) }))

      val issued = results[0] as ChapterRefreshRequest
      val previousAcceptance = results[1] as ChapterRefreshAcceptance

      if (previousAcceptance is ChapterRefreshAcceptance.Accepted) {
        assertEquals(previous.result, previousAcceptance.result)
      }

      val current = completed(issued)

      assertEquals(current.result, gate.accept(current).acceptedResult())

      assertSame(
        ChapterRefreshAcceptance.RejectedNotCurrent,
        gate.accept(current),
      )
    }
  }

  @Test
  fun `concurrent issuance increases and only the last request is current`() {
    val gate = ChapterRefreshGate(title)

    val sequences = race(workerCount = 4) { List(25) { gate.issue() } }

    val requests = sequences.flatten()

    val last = requests.maxBy { it.ordinal }

    for (sequence in sequences) {
      assertTrue(sequence.zipWithNext().all { (a, b) -> a.ordinal < b.ordinal })
    }

    assertEquals(100, requests.toSet().size)
    assertEquals((0L until 100L).toList(), requests.map { it.ordinal }.sorted())

    for (request in requests - last) {
      assertSame(
        ChapterRefreshAcceptance.RejectedNotCurrent,
        gate.accept(completed(request)),
      )
    }

    assertEquals(
      completed(last).result,
      gate.accept(completed(last)).acceptedResult(),
    )
  }

  @Test
  fun `concurrent acceptance admits one completion`() {
    val gate = ChapterRefreshGate(title)

    val completion = completed(gate.issue())

    val acceptances = race(workerCount = 2) { gate.accept(completion) }

    assertEquals(
      1,
      acceptances.count { it is ChapterRefreshAcceptance.Accepted },
    )

    assertEquals(
      1,
      acceptances.count { it === ChapterRefreshAcceptance.RejectedNotCurrent },
    )
  }

  private fun chapter(owner: SourceTitleKey): SourceChapter =
    SourceChapter(SourceChapterKey(owner, "chapter"), "Chapter")

  private fun completed(
    request: ChapterRefreshRequest
  ): ChapterRefreshCompletion =
    ChapterRefreshCompletion.completed(
      request = request,
      status = ChapterSequenceStatus.COMPLETE,
      chapters = listOf(chapter(request.title)),
    )
}

private const val RACE_TIMEOUT_SECONDS = 5L

private fun ChapterRefreshAcceptance.acceptedResult():
  SourceResult<ChapterSnapshot, ChapterRefreshFailure> =
  when (this) {
    is ChapterRefreshAcceptance.Accepted -> result
    ChapterRefreshAcceptance.RejectedNotCurrent -> fail("Expected acceptance.")
  }

/**
 * Starts [action] on [workerCount] threads at once and returns every result.
 */
private fun <Value> race(workerCount: Int, action: () -> Value): List<Value> =
  raceAll(List(workerCount) { action })

/** Starts every action on its own thread at once and returns their results. */
private fun <Value> raceAll(actions: List<() -> Value>): List<Value> {
  val executor = Executors.newFixedThreadPool(actions.size)

  val start = CyclicBarrier(actions.size + 1)

  val futures = actions.map { action ->
    executor.submit<Value> {
      start.await(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

      action()
    }
  }

  try {
    start.await(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    return futures.map { it.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
  } finally {
    executor.shutdownNow()
    executor.awaitTermination(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
  }
}
