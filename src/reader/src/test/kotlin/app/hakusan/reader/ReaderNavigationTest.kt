package app.hakusan.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ReaderNavigationTest {
  private val book = ReaderTestBook()

  @Test
  fun `closing rejects every operation and keeps the actual position`() =
    readerTest {
      val gate = ReaderSessionGate()

      val session = gate.start()

      val reader = ReaderNavigation(gate, session, book.input)

      val content = book.content[0]

      val opening = reader.request(content.chapter.key).accepted()

      reader.actualStarted(opening, content, content.positionAt(0)).accepted()
      reader.preview(content.chapter.key, 1).accepted()

      val pending = reader.releasePreview().accepted()

      reader.preview(content.chapter.key, 2).accepted()
      reader.beginClose()
      reader.beginClose()

      assertEquals(
        ReaderNavigationState(content.positionAt(0), null, null),
        reader.snapshot(),
      )

      val operations =
        listOf(
          reader.request(content.chapter.key),
          reader.preview(content.chapter.key, 1),
          reader.releasePreview(),
          reader.cancelRequest(pending),
          reader.actualStarted(pending, content, content.positionAt(1)),
          reader.nextChapterAfterEnd(content.positionAt(0)),
          reader.completeFinalAfterEnd(content.positionAt(0)),
        )

      for (operation in operations) {
        assertEquals(ReaderNavigationRejection.CLOSING, operation.rejection())
      }

      assertEquals(1L, gate.issue(session).accepted().ordinal)
    }

  @Test
  fun `the end of the session settles navigation without a paired call`() =
    readerTest {
      val gate = ReaderSessionGate()

      val endings: List<suspend (ReaderSession) -> Unit> =
        listOf({ gate.close(it) }, { gate.start() })

      val content = book.content[0]

      for (end in endings) {
        val session = gate.start()

        val reader = ReaderNavigation(gate, session, book.input)

        val opening = reader.request(content.chapter.key).accepted()

        reader.actualStarted(opening, content, content.positionAt(0)).accepted()
        reader.preview(content.chapter.key, 2).accepted()
        reader.request(book.chapters[1].key).accepted()
        end(session)

        assertEquals(
          ReaderNavigationState(content.positionAt(0), null, null),
          reader.snapshot(),
        )

        assertEquals(
          ReaderNavigationRejection.CLOSING,
          reader.request(book.chapters[0].key).rejection(),
        )
      }

      val current = gate.start()

      val foreign = ReaderNavigation(ReaderSessionGate(), current, book.input)

      assertEquals(
        ReaderNavigationRejection.CLOSING,
        foreign.request(book.chapters[0].key).rejection(),
      )

      gate.close(current)

      val late = ReaderNavigation(gate, current, book.input)

      assertEquals(ReaderNavigationState(null, null, null), late.snapshot())

      assertEquals(
        ReaderNavigationRejection.CLOSING,
        late.request(book.chapters[0].key).rejection(),
      )
    }

  @Test
  fun `preview and its cancellation leave the actual position unchanged`() =
    readerTest {
      val gate = ReaderSessionGate()

      val session = gate.start()

      val reader = ReaderNavigation(gate, session, book.input)

      val content = book.content[0]

      val opening = reader.request(content.chapter.key).accepted()

      val event =
        reader.actualStarted(opening, content, content.positionAt(0)).accepted()

      assertEquals(0L, event.identity.ordinal)

      assertEquals(
        ReaderEventPayload.ActualPosition(content.positionAt(0)),
        event.payload,
      )

      assertEquals(
        content.positionAt(2),
        reader.preview(content.chapter.key, 2).accepted(),
      )

      assertEquals(
        ReaderNavigationState(
          content.positionAt(0),
          content.positionAt(2),
          null,
        ),
        reader.snapshot(),
      )

      reader.cancelPreview()
      assertNull(reader.snapshot().preview)
      reader.preview(content.chapter.key, 1).accepted()

      val seek = reader.releasePreview().accepted()

      assertEquals(content.chapter.key, seek.chapter)
      assertEquals(ReaderStart.Resume(ReaderUnitKind.PAGE, 1), seek.start)

      assertEquals(
        ReaderNavigationState(content.positionAt(0), null, seek),
        reader.snapshot(),
      )

      reader.cancelRequest(seek).accepted()

      assertEquals(
        ReaderNavigationRejection.STALE_REQUEST,
        reader.actualStarted(seek, content, content.positionAt(1)).rejection(),
      )

      assertEquals(content.positionAt(0), reader.snapshot().actual)
      assertEquals(1L, gate.issue(session).accepted().ordinal)
    }

  @Test
  fun `a preview across a chapter boundary is stale`() = readerTest {
    val gate = ReaderSessionGate()

    val session = gate.start()

    val reader = ReaderNavigation(gate, session, book.input)

    val old = book.content[0]

    val last = old.positionAt(2)

    val resume = ReaderStart.Resume(last.unitKind, last.unitIndex)

    val opening = reader.request(old.chapter.key, resume).accepted()

    reader.actualStarted(opening, old, last).accepted()
    reader.preview(old.chapter.key, 1).accepted()

    val next = reader.nextChapterAfterEnd(last).accepted()

    assertNull(reader.snapshot().preview)
    reader.preview(old.chapter.key, 1).accepted()

    val successor = book.content[1]

    reader.actualStarted(next, successor, successor.positionAt(0)).accepted()

    assertEquals(
      ReaderNavigationRejection.STALE_REQUEST,
      reader.releasePreview().rejection(),
    )

    assertEquals(
      ReaderNavigationRejection.STALE_REQUEST,
      reader.preview(old.chapter.key, 1).rejection(),
    )

    reader.preview(successor.chapter.key, 1).accepted()

    assertEquals(
      ReaderNavigationRejection.STALE_REQUEST,
      reader.preview(old.chapter.key, 2).rejection(),
    )

    assertEquals(successor.positionAt(1), reader.snapshot().preview)

    val seek = reader.releasePreview().accepted()

    assertEquals(successor.chapter.key, seek.chapter)

    assertEquals(
      ReaderStart.Resume(ReaderUnitKind.PROVIDER_SEGMENT, 1),
      seek.start,
    )

    assertEquals(
      ReaderNavigationState(successor.positionAt(0), null, seek),
      reader.snapshot(),
    )

    assertEquals(2L, gate.issue(session).accepted().ordinal)
  }

  @Test
  fun `a boundary event needs the canonical successor to start`() = readerTest {
    val gate = ReaderSessionGate()

    val session = gate.start()

    val reader = ReaderNavigation(gate, session, book.input)

    val old = book.content[0]

    val last = old.positionAt(2)

    val resume = ReaderStart.Resume(last.unitKind, last.unitIndex)

    val opening = reader.request(old.chapter.key, resume).accepted()

    reader.actualStarted(opening, old, last).accepted()

    val next = reader.nextChapterAfterEnd(last).accepted()

    val successor = book.content[1]

    assertEquals(successor.chapter.key, next.chapter)
    assertEquals(ReaderStart.Beginning, next.start)
    assertEquals(last, reader.snapshot().actual)
    assertSame(next, reader.snapshot().requested)

    assertFailsExactly<IllegalArgumentException> {
      reader.actualStarted(next, book.content[2], book.content[2].positionAt(0))
    }

    assertFailsExactly<IllegalArgumentException> {
      reader.actualStarted(next, successor, successor.positionAt(1))
    }

    val event =
      reader.actualStarted(next, successor, successor.positionAt(0)).accepted()

    assertEquals(1L, event.identity.ordinal)

    assertEquals(
      ReaderEventPayload.ChapterBoundary(
        old.chapter.key,
        successor.positionAt(0),
      ),
      event.payload,
    )

    assertEquals(
      ReaderNavigationState(successor.positionAt(0), null, null),
      reader.snapshot(),
    )

    assertEquals(
      ReaderNavigationRejection.STALE_REQUEST,
      reader
        .actualStarted(next, successor, successor.positionAt(0))
        .rejection(),
    )
  }

  @Test
  fun `a failed start withdraws its request without an event`() = readerTest {
    val gate = ReaderSessionGate()

    val session = gate.start()

    val reader = ReaderNavigation(gate, session, book.input)

    val old = book.content[0]

    val last = old.positionAt(2)

    val resume = ReaderStart.Resume(last.unitKind, last.unitIndex)

    val opening = reader.request(old.chapter.key, resume).accepted()

    reader.actualStarted(opening, old, last).accepted()

    val failed = reader.nextChapterAfterEnd(last).accepted()

    reader.cancelRequest(failed).accepted()
    assertEquals(ReaderNavigationState(last, null, null), reader.snapshot())

    assertEquals(
      ReaderNavigationRejection.STALE_REQUEST,
      reader.cancelRequest(failed).rejection(),
    )

    val successor = book.content[1]

    assertEquals(
      ReaderNavigationRejection.STALE_REQUEST,
      reader
        .actualStarted(failed, successor, successor.positionAt(0))
        .rejection(),
    )

    val retry = reader.nextChapterAfterEnd(last).accepted()

    val event =
      reader.actualStarted(retry, successor, successor.positionAt(0)).accepted()

    assertEquals(1L, event.identity.ordinal)

    assertEquals(
      ReaderEventPayload.ChapterBoundary(
        old.chapter.key,
        successor.positionAt(0),
      ),
      event.payload,
    )
  }

  @Test
  fun `an unavailable start withdraws its request without an event`() =
    readerTest {
      val gate = ReaderSessionGate()

      val session = gate.start()

      val reader = ReaderNavigation(gate, session, book.input)

      val content = book.content[0]

      val starts =
        listOf(
          ReaderStart.Resume(ReaderUnitKind.PAGE, 3),
          ReaderStart.Resume(ReaderUnitKind.PROVIDER_SEGMENT, 0),
        )

      for (start in starts) {
        val request = reader.request(content.chapter.key, start).accepted()

        assertEquals(
          ReaderNavigationRejection.UNAVAILABLE_START,
          reader
            .actualStarted(request, content, content.positionAt(0))
            .rejection(),
        )

        assertEquals(ReaderNavigationState(null, null, null), reader.snapshot())
      }

      assertEquals(0L, gate.issue(session).accepted().ordinal)
    }

  @Test
  fun `every other rejection carries its reason`() = readerTest {
    val gate = ReaderSessionGate()

    val session = gate.start()

    val reader = ReaderNavigation(gate, session, book.input)

    val foreignTitle = ReaderTitleKey("source", "other")

    val unknown =
      listOf(
        ReaderChapterKey(book.title, "missing"),
        ReaderChapterKey(foreignTitle, book.chapters[0].key.chapter),
      )

    for (chapter in unknown) {
      assertEquals(
        ReaderNavigationRejection.UNKNOWN_CHAPTER,
        reader.request(chapter).rejection(),
      )
    }

    val content = book.content[0]

    assertEquals(
      ReaderNavigationRejection.STALE_REQUEST,
      reader.preview(content.chapter.key, 0).rejection(),
    )

    val replaced = reader.request(content.chapter.key).accepted()

    val opening = reader.request(content.chapter.key).accepted()

    assertEquals(
      ReaderNavigationRejection.STALE_REQUEST,
      reader
        .actualStarted(replaced, content, content.positionAt(0))
        .rejection(),
    )

    assertEquals(
      ReaderNavigationRejection.STALE_REQUEST,
      reader.cancelRequest(replaced).rejection(),
    )

    reader.actualStarted(opening, content, content.positionAt(0)).accepted()

    for (index in listOf(-1, 3)) {
      assertEquals(
        ReaderNavigationRejection.UNAVAILABLE_START,
        reader.preview(content.chapter.key, index).rejection(),
      )
    }

    assertEquals(
      ReaderNavigationRejection.STALE_REQUEST,
      reader.preview(book.chapters[1].key, 0).rejection(),
    )

    for (chapter in unknown) {
      assertEquals(
        ReaderNavigationRejection.UNKNOWN_CHAPTER,
        reader.preview(chapter, 0).rejection(),
      )
    }

    assertEquals(
      ReaderNavigationRejection.STALE_REQUEST,
      reader.releasePreview().rejection(),
    )

    for (position in listOf(content.positionAt(0), content.positionAt(2))) {
      assertEquals(
        ReaderNavigationRejection.STALE_REQUEST,
        reader.nextChapterAfterEnd(position).rejection(),
      )
    }

    val final = book.content[2]

    assertEquals(
      ReaderNavigationRejection.STALE_REQUEST,
      reader.completeFinalAfterEnd(final.positionAt(0)).rejection(),
    )

    assertEquals(
      ReaderNavigationState(content.positionAt(0), null, null),
      reader.snapshot(),
    )

    assertEquals(1L, gate.issue(session).accepted().ordinal)
  }

  @Test
  fun `only the gesture after the final unit completes the title`() =
    readerTest {
      val gate = ReaderSessionGate()

      val session = gate.start()

      val reader = ReaderNavigation(gate, session, book.input)

      val first = book.content[0]

      val firstEnd = first.positionAt(2)

      val resume = ReaderStart.Resume(firstEnd.unitKind, firstEnd.unitIndex)

      val opening = reader.request(first.chapter.key, resume).accepted()

      reader.actualStarted(opening, first, firstEnd).accepted()

      assertFailsExactly<IllegalArgumentException> {
        reader.completeFinalAfterEnd(firstEnd)
      }

      val final = book.content[2]

      val jump = reader.request(final.chapter.key).accepted()

      val event =
        reader.actualStarted(jump, final, final.positionAt(0)).accepted()

      assertEquals(
        ReaderEventPayload.ActualPosition(final.positionAt(0)),
        event.payload,
      )

      assertFailsExactly<IllegalArgumentException> {
        reader.nextChapterAfterEnd(final.positionAt(0))
      }

      val completion =
        reader.completeFinalAfterEnd(final.positionAt(0)).accepted()

      assertEquals(2L, completion.identity.ordinal)

      assertEquals(
        ReaderEventPayload.FinalCompletion(final.chapter.key),
        completion.payload,
      )

      assertEquals(
        ReaderNavigationRejection.STALE_REQUEST,
        reader.completeFinalAfterEnd(final.positionAt(0)).rejection(),
      )

      assertEquals(3L, gate.issue(session).accepted().ordinal)
    }

  @Test
  fun `a new start in the final chapter can complete again`() = readerTest {
    val gate = ReaderSessionGate()

    val session = gate.start()

    val reader = ReaderNavigation(gate, session, book.input)

    val final = book.content[2]

    val end = final.positionAt(0)

    val opening = reader.request(final.chapter.key).accepted()

    reader.actualStarted(opening, final, end).accepted()
    reader.completeFinalAfterEnd(end).accepted()

    val reopening = reader.request(final.chapter.key).accepted()

    reader.actualStarted(reopening, final, end).accepted()

    val completion = reader.completeFinalAfterEnd(end).accepted()

    assertEquals(3L, completion.identity.ordinal)

    assertEquals(
      ReaderEventPayload.FinalCompletion(final.chapter.key),
      completion.payload,
    )
  }

  @Test
  fun `events of an ended session are rejected before delivery`() = readerTest {
    val gate = ReaderSessionGate()

    val session = gate.start()

    val reader = ReaderNavigation(gate, session, book.input)

    val content = book.content[1]

    val opening = reader.request(content.chapter.key).accepted()

    val event =
      reader.actualStarted(opening, content, content.positionAt(0)).accepted()

    val pending = reader.request(book.chapters[0].key).accepted()

    gate.start()

    var delivered = false

    assertSame(
      ReaderSessionAcceptance.RejectedNotCurrent,
      gate.withCurrent(event.identity) { delivered = true },
    )

    assertEquals(
      ReaderNavigationRejection.CLOSING,
      reader
        .actualStarted(pending, book.content[0], book.content[0].positionAt(0))
        .rejection(),
    )

    assertFalse(delivered)
    assertEquals(content.positionAt(0), reader.snapshot().actual)
    assertNull(reader.snapshot().requested)
  }
}
