package app.hakusan.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReaderChapterSourceTest {
  private val book = ReaderTestBook()

  @Test
  fun `only loaded content starts reading`() = readerTest {
    val gate = ReaderSessionGate()

    val session = gate.start()

    val reader = ReaderNavigation(gate, session, book.input)

    val source =
      RecordingSource(
        mapOf(
          book.chapters[0].key to ReaderChapterResult.Loaded(book.content[0]),
          book.chapters[1].key to ReaderChapterResult.Unavailable,
          book.chapters[2].key to ReaderChapterResult.InvalidObservation,
        )
      )

    assertNull(reader.open(source, book.chapters[1]))
    assertNull(reader.open(source, book.chapters[2]))
    assertEquals(ReaderNavigationState(null, null, null), reader.snapshot())

    val event = reader.open(source, book.chapters[0])

    val started = book.content[0].positionAt(0)

    assertEquals(0L, event?.identity?.ordinal)
    assertEquals(ReaderEventPayload.ActualPosition(started), event?.payload)
    assertEquals(ReaderNavigationState(started, null, null), reader.snapshot())
    assertEquals(listOf(1, 2, 0).map(book.chapters::get), source.loads)
  }

  @Test
  fun `content of another chapter is rejected before reading starts`() =
    readerTest {
      val gate = ReaderSessionGate()

      val session = gate.start()

      val reader = ReaderNavigation(gate, session, book.input)

      val misplaced = RecordingSource {
        ReaderChapterResult.Loaded(book.content[1])
      }

      assertFailsExactly<IllegalArgumentException> {
        reader.open(misplaced, book.chapters[0])
      }

      val opening = reader.snapshot().requested

      assertEquals(book.chapters[0].key, opening?.chapter)

      assertEquals(
        ReaderNavigationState(null, null, opening),
        reader.snapshot(),
      )

      val loaded = RecordingSource {
        ReaderChapterResult.Loaded(book.content[0])
      }

      val started = book.content[0].positionAt(0)

      assertEquals(0L, reader.open(loaded, book.chapters[0])?.identity?.ordinal)

      assertFailsExactly<IllegalArgumentException> {
        reader.open(misplaced, book.chapters[2])
      }

      val pending = reader.snapshot().requested

      assertEquals(book.chapters[2].key, pending?.chapter)

      assertEquals(
        ReaderNavigationState(started, null, pending),
        reader.snapshot(),
      )

      assertEquals(listOf(0, 2).map(book.chapters::get), misplaced.loads)
      assertEquals(1L, gate.issue(session).accepted().ordinal)
    }

  /** Answers each load through [answer] and records the loaded chapters. */
  private class RecordingSource(
    private val answer: (ReaderChapter) -> ReaderChapterResult
  ) : ReaderChapterSource {
    val loads = mutableListOf<ReaderChapter>()

    constructor(
      results: Map<ReaderChapterKey, ReaderChapterResult>
    ) : this({ results.getValue(it.key) })

    override suspend fun load(chapter: ReaderChapter): ReaderChapterResult {
      loads += chapter

      return answer(chapter)
    }
  }

  /**
   * Opens [chapter] at its beginning as opening does, and returns the started
   * event, or null when the source supplied no content.
   */
  private suspend fun ReaderNavigation.open(
    source: ReaderChapterSource,
    chapter: ReaderChapter,
  ): ReaderEvent? {
    val request = request(chapter.key).accepted()

    return when (val result = source.load(chapter)) {
      is ReaderChapterResult.Loaded -> {
        val content = result.content

        actualStarted(request, content, content.positionAt(0)).accepted()
      }

      ReaderChapterResult.Unavailable,
      ReaderChapterResult.InvalidObservation -> {
        cancelRequest(request).accepted()

        null
      }
    }
  }
}
