package app.hakusan.extensions

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class ChapterSnapshotTest {
  private val source = SourceIdentity("source")
  private val title = SourceTitleKey(source, "title")

  @Test
  fun `complete snapshots preserve explicit first to final order`() {
    val first = chapter("first", "Chapter 10")
    val second = chapter("second", "Chapter 2")
    val final = chapter("final", "Chapter 1")
    val expected = listOf(first, second, final)
    val input = expected.toMutableList()

    val snapshot = ChapterSnapshot.create(
      title = title,
      status = ChapterSequenceStatus.COMPLETE,
      chapters = input,
    ).successValue()
    input.reverse()

    assertEquals(expected, snapshot.chapters)
    assertNotEquals(
      expected.sortedBy(SourceChapter::displayName),
      snapshot.chapters,
    )
    assertThrows(UnsupportedOperationException::class.java) {
      (snapshot.chapters as MutableList).clear()
    }
  }

  @Test
  fun `complete snapshots may explicitly contain no chapters`() {
    val snapshot = ChapterSnapshot.create(
      title = title,
      status = ChapterSequenceStatus.COMPLETE,
      chapters = emptyList(),
    ).successValue()

    assertEquals(title, snapshot.title)
    assertEquals(emptyList<SourceChapter>(), snapshot.chapters)
  }

  @Test
  fun `partial and ambiguous observations are rejected`() {
    val chapters = listOf(chapter("chapter", "Chapter"))

    val partial = ChapterSnapshot.create(
      title = title,
      status = ChapterSequenceStatus.PARTIAL,
      chapters = chapters,
    )
    val ambiguous = ChapterSnapshot.create(
      title = title,
      status = ChapterSequenceStatus.AMBIGUOUS,
      chapters = chapters,
    )

    assertEquals(
      invalidSnapshot(ChapterSnapshotRejection.PARTIAL_SEQUENCE),
      partial,
    )
    assertEquals(
      invalidSnapshot(ChapterSnapshotRejection.AMBIGUOUS_ORDER),
      ambiguous,
    )
  }

  @Test
  fun `duplicate chapter identities are rejected`() {
    val key = SourceChapterKey(title, "chapter")
    val result = ChapterSnapshot.create(
      title = title,
      status = ChapterSequenceStatus.COMPLETE,
      chapters = listOf(
        SourceChapter(key, "First observation"),
        SourceChapter(key, "Conflicting observation"),
      ),
    )

    assertEquals(
      invalidSnapshot(ChapterSnapshotRejection.DUPLICATE_CHAPTER),
      result,
    )
  }

  @Test
  fun `chapters owned by another title are rejected`() {
    val otherTitle = SourceTitleKey(source, "other-title")
    val result = ChapterSnapshot.create(
      title = title,
      status = ChapterSequenceStatus.COMPLETE,
      chapters = listOf(
        SourceChapter(
          SourceChapterKey(otherTitle, "chapter"),
          "Chapter",
        ),
      ),
    )

    assertEquals(
      invalidSnapshot(ChapterSnapshotRejection.FOREIGN_TITLE),
      result,
    )
  }

  @Test
  fun `new snapshots may update metadata without changing identity`() {
    val key = SourceChapterKey(title, "chapter")
    val earlier = ChapterSnapshot.create(
      title = title,
      status = ChapterSequenceStatus.COMPLETE,
      chapters = listOf(SourceChapter(key, "Earlier name")),
    ).successValue()
    val later = ChapterSnapshot.create(
      title = title,
      status = ChapterSequenceStatus.COMPLETE,
      chapters = listOf(SourceChapter(key, "Later name")),
    ).successValue()

    assertEquals(earlier.chapters.single().key, later.chapters.single().key)
    assertNotEquals(
      earlier.chapters.single().displayName,
      later.chapters.single().displayName,
    )
  }

  @Test
  fun `refresh generations are issued once in increasing order`() {
    val gate = ChapterRefreshGate(title)
    val first = gate.issue()
    val second = gate.issue()
    val third = gate.issue()

    assertEquals(0L, first.generation.ordinal)
    assertEquals(1L, second.generation.ordinal)
    assertEquals(2L, third.generation.ordinal)
    assertEquals(3, setOf(first, second, third).size)
  }

  @Test
  fun `only the current refresh completion is accepted once`() {
    val gate = ChapterRefreshGate(title)
    val first = gate.issue()
    val current = gate.issue()
    val firstCompletion = completed(first)
    val currentCompletion = completed(current)

    assertSame(
      ChapterRefreshAcceptance.RejectedNotCurrent,
      gate.accept(firstCompletion),
    )
    assertEquals(
      currentCompletion.result,
      gate.accept(currentCompletion).acceptedResult(),
    )
    assertSame(
      ChapterRefreshAcceptance.RejectedNotCurrent,
      gate.accept(currentCompletion),
    )
  }

  @Test
  fun `late failures cannot replace the current refresh`() {
    val gate = ChapterRefreshGate(title)
    val first = gate.issue()
    val current = gate.issue()
    val lateFailure = ChapterRefreshCompletion.failed(
      request = first,
      error = SourceFailure.Unavailable,
    )
    val currentFailure = ChapterRefreshCompletion.failed(
      request = current,
      error = SourceFailure.Unavailable,
    )

    assertSame(
      ChapterRefreshAcceptance.RejectedNotCurrent,
      gate.accept(lateFailure),
    )
    assertEquals(
      SourceResult.Failure(SourceFailure.Unavailable),
      gate.accept(currentFailure).acceptedResult(),
    )
  }

  @Test
  fun `current validation failures remain authoritative failures`() {
    val gate = ChapterRefreshGate(title)
    val request = gate.issue()
    val completion = ChapterRefreshCompletion.completed(
      request = request,
      status = ChapterSequenceStatus.PARTIAL,
      chapters = listOf(chapter("chapter", "Chapter")),
    )

    assertEquals(
      invalidSnapshot(ChapterSnapshotRejection.PARTIAL_SEQUENCE),
      gate.accept(completion).acceptedResult(),
    )
  }

  @Test
  fun `a completion for another title is not current`() {
    val gate = ChapterRefreshGate(title)
    gate.issue()
    val otherTitle = SourceTitleKey(source, "other-title")
    val otherRequest = ChapterRefreshGate(otherTitle).issue()
    val completion = ChapterRefreshCompletion.completed(
      request = otherRequest,
      status = ChapterSequenceStatus.COMPLETE,
      chapters = listOf(
        SourceChapter(
          SourceChapterKey(otherTitle, "chapter"),
          "Chapter",
        ),
      ),
    )

    assertSame(
      ChapterRefreshAcceptance.RejectedNotCurrent,
      gate.accept(completion),
    )
  }

  @Test
  fun `different gates cannot exchange completions`() {
    val firstGate = ChapterRefreshGate(title)
    val secondGate = ChapterRefreshGate(title)
    val first = firstGate.issue()
    val second = secondGate.issue()

    assertEquals(first.generation, second.generation)
    assertNotEquals(first, second)
    assertSame(
      ChapterRefreshAcceptance.RejectedNotCurrent,
      firstGate.accept(completed(second)),
    )
  }

  @Test
  fun `a structurally equal request copy remains current`() {
    val gate = ChapterRefreshGate(title)
    val request = gate.issue()
    val requestCopy = request.copy()

    assertNotSame(request, requestCopy)
    assertEquals(request, requestCopy)
    assertEquals(
      completed(requestCopy).result,
      gate.accept(completed(requestCopy)).acceptedResult(),
    )
  }

  @Test
  fun `mixed issue and acceptance leave the issued request current`() {
    repeat(25) {
      val gate = ChapterRefreshGate(title)
      val previous = gate.issue()
      val previousCompletion = completed(previous)
      val (issued, previousAcceptance) = racePair(
        first = gate::issue,
        second = { gate.accept(previousCompletion) },
      )

      when (previousAcceptance) {
        is ChapterRefreshAcceptance.Accepted -> {
          assertEquals(previousCompletion.result, previousAcceptance.result)
        }

        ChapterRefreshAcceptance.RejectedNotCurrent -> {
          assertSame(
            ChapterRefreshAcceptance.RejectedNotCurrent,
            previousAcceptance,
          )
        }
      }
      val issuedCompletion = completed(issued)
      assertEquals(
        issuedCompletion.result,
        gate.accept(issuedCompletion).acceptedResult(),
      )
      assertSame(
        ChapterRefreshAcceptance.RejectedNotCurrent,
        gate.accept(issuedCompletion),
      )
    }
  }

  @Test
  fun `concurrent issue calls produce unique increasing generations`() {
    val gate = ChapterRefreshGate(title)
    val requests = race(workerCount = 4) {
      List(25) { gate.issue() }
    }.flatten()
    val ordinals = requests.map { it.generation.ordinal }.toSet()

    assertEquals(100, requests.toSet().size)
    assertEquals((0L until 100L).toSet(), ordinals)
  }

  @Test
  fun `concurrent acceptance admits one completion`() {
    val gate = ChapterRefreshGate(title)
    val completion = completed(gate.issue())

    val results = race(workerCount = 2) {
      gate.accept(completion)
    }

    assertEquals(
      1,
      results.count { it is ChapterRefreshAcceptance.Accepted },
    )
    assertEquals(
      1,
      results.count {
        it === ChapterRefreshAcceptance.RejectedNotCurrent
      },
    )
  }

  private fun chapter(
    key: String,
    displayName: String,
  ): SourceChapter = SourceChapter(
    key = SourceChapterKey(title, key),
    displayName = displayName,
  )

  private fun completed(
    request: ChapterRefreshRequest,
  ): ChapterRefreshCompletion = ChapterRefreshCompletion.completed(
    request = request,
    status = ChapterSequenceStatus.COMPLETE,
    chapters = listOf(chapter("chapter", "Chapter")),
  )
}

private fun <First, Second> racePair(
  first: () -> First,
  second: () -> Second,
): Pair<First, Second> {
  val executor = Executors.newFixedThreadPool(2) { task ->
    Thread(task, "chapter-refresh-mixed-race").apply {
      isDaemon = true
    }
  }
  val start = CyclicBarrier(3)
  val firstResult = executor.submit<First> {
    start.await(5L, TimeUnit.SECONDS)
    first()
  }
  val secondResult = executor.submit<Second> {
    start.await(5L, TimeUnit.SECONDS)
    second()
  }

  return try {
    start.await(5L, TimeUnit.SECONDS)
    firstResult.get(5L, TimeUnit.SECONDS) to
      secondResult.get(5L, TimeUnit.SECONDS)
  } finally {
    executor.shutdownNow()
    executor.awaitTermination(5L, TimeUnit.SECONDS)
  }
}

private fun <Value> race(
  workerCount: Int,
  action: () -> Value,
): List<Value> {
  val executor = Executors.newFixedThreadPool(workerCount) { task ->
    Thread(task, "chapter-refresh-race").apply {
      isDaemon = true
    }
  }
  val start = CyclicBarrier(workerCount + 1)
  val futures = List(workerCount) {
    executor.submit<Value> {
      start.await(5L, TimeUnit.SECONDS)
      action()
    }
  }

  return try {
    start.await(5L, TimeUnit.SECONDS)
    futures.map { it.get(5L, TimeUnit.SECONDS) }
  } finally {
    executor.shutdownNow()
    executor.awaitTermination(5L, TimeUnit.SECONDS)
  }
}

private fun invalidSnapshot(
  reason: ChapterSnapshotRejection,
): SourceResult.Failure<SourceFailure.InvalidChapterSnapshot> =
  SourceResult.Failure(
    SourceFailure.InvalidChapterSnapshot(reason),
  )

private fun <Value> SourceResult<Value, *>.successValue(): Value = when (this) {
  is SourceResult.Success -> value
  is SourceResult.Failure -> fail("Expected success, got $error")
}

private fun ChapterRefreshAcceptance.acceptedResult():
  SourceResult<ChapterSnapshot, ChapterRefreshFailure> = when (this) {
    is ChapterRefreshAcceptance.Accepted -> result
    ChapterRefreshAcceptance.RejectedNotCurrent -> {
      fail("Expected current completion")
    }
  }
