package app.hakusan.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrowsExactly
import org.junit.jupiter.api.Test

class ReaderInputTest {
  private val book = ReaderTestBook()

  @Test
  fun `canonical order is pinned and independent of labels`() {
    val source = book.chapters.toMutableList()

    val input = ReaderInput.of(book.title, source)

    source.reverse()
    source.clear()
    assertEquals(book.chapters, input.chapters)
    assertEquals(listOf("10", "2", "1"), input.chapters.map { it.displayName })
    assertEquals(book.chapters[1], input.next(book.chapters[0].key))
    assertEquals(book.chapters[0], input.previous(book.chapters[1].key))
    assertNull(input.previous(book.chapters[0].key))
    assertNull(input.next(book.chapters[2].key))
    assertEquals(book.chapters[2], input.find(book.chapters[2].key))

    assertThrowsExactly(UnsupportedOperationException::class.java) {
      (input.chapters as MutableList).clear()
    }
  }

  @Test
  fun `a chapter outside the sequence is unknown`() {
    val input = ReaderInput.of(book.title, book.chapters.take(2))

    val outside = book.chapters[2].key

    assertNull(input.find(outside))

    assertThrowsExactly(IllegalArgumentException::class.java) {
      input.next(outside)
    }

    assertThrowsExactly(IllegalArgumentException::class.java) {
      input.previous(outside)
    }
  }

  @Test
  fun `keys are exact source-qualified aliases`() {
    val chapter = ReaderChapterKey(book.title, "chapter")

    val foreign = ReaderTitleKey("other", "title")

    val composed = ReaderTitleKey("source", "caf\u00e9")

    assertNotEquals(book.title, ReaderTitleKey("Source", "title"))
    assertNotEquals(book.title, ReaderTitleKey("source", " title"))
    assertNotEquals(composed, ReaderTitleKey("source", "cafe\u0301"))
    assertNotEquals(chapter, ReaderChapterKey(foreign, "chapter"))
    assertEquals(chapter, ReaderChapterKey(book.title.copy(), "chapter"))
    assertEquals(" title ", ReaderTitleKey("source", " title ").title)

    for (blank in listOf("", " ", "\t")) {
      assertThrowsExactly(IllegalArgumentException::class.java) {
        ReaderTitleKey(blank, "title")
      }

      assertThrowsExactly(IllegalArgumentException::class.java) {
        ReaderTitleKey("source", blank)
      }

      assertThrowsExactly(IllegalArgumentException::class.java) {
        ReaderChapterKey(book.title, blank)
      }
    }
  }

  @Test
  fun `a sequence rejects contradictions`() {
    val foreign = ReaderTitleKey("other", "title")

    assertThrowsExactly(IllegalArgumentException::class.java) {
      ReaderInput.of(book.title, emptyList())
    }

    assertThrowsExactly(IllegalArgumentException::class.java) {
      ReaderInput.of(book.title, listOf(book.chapters[0], book.chapters[0]))
    }

    assertThrowsExactly(IllegalArgumentException::class.java) {
      ReaderInput.of(
        book.title,
        listOf(book.chapters[0], book.chapters[0].copy(displayName = "copy")),
      )
    }

    assertThrowsExactly(IllegalArgumentException::class.java) {
      ReaderInput.of(foreign, book.chapters)
    }
  }

  @Test
  fun `content keeps provider order, kinds, and repeated references`() {
    val units = book.content[0].units.toMutableList()

    val content = ReaderChapterContent.of(book.chapters[0], units)

    units.clear()

    assertEquals(
      listOf("repeated", "repeated", "last"),
      content.units.map { it.reference },
    )

    assertEquals(
      listOf(ReaderUnitKind.PROVIDER_SEGMENT),
      book.content[1].units.map { it.kind }.distinct(),
    )

    assertNotEquals(content.positionAt(0), content.positionAt(1))

    assertThrowsExactly(UnsupportedOperationException::class.java) {
      (content.units as MutableList).clear()
    }

    assertThrowsExactly(IllegalArgumentException::class.java) {
      content.positionAt(3)
    }

    assertThrowsExactly(IllegalArgumentException::class.java) {
      ReaderChapterContent.of(book.chapters[0], emptyList())
    }

    assertThrowsExactly(IllegalArgumentException::class.java) {
      ReaderContentUnit(ReaderUnitKind.PAGE, " ")
    }
  }

  @Test
  fun `an incompatible resume is unavailable, never clamped or restarted`() {
    val content = book.content[1]

    assertEquals(
      ReaderStartTarget.Available(content.positionAt(0)),
      content.resolve(ReaderStart.Beginning),
    )

    assertEquals(
      ReaderStartTarget.Available(content.positionAt(2)),
      content.resolve(ReaderStart.Resume(ReaderUnitKind.PROVIDER_SEGMENT, 2)),
    )

    assertEquals(
      ReaderStartTarget.Unavailable,
      content.resolve(ReaderStart.Resume(ReaderUnitKind.PROVIDER_SEGMENT, 3)),
    )

    assertEquals(
      ReaderStartTarget.Unavailable,
      content.resolve(ReaderStart.Resume(ReaderUnitKind.PAGE, 1)),
    )

    assertThrowsExactly(IllegalArgumentException::class.java) {
      ReaderStart.Resume(ReaderUnitKind.PAGE, -1)
    }
  }

  @Test
  fun `a descriptor addresses one unit of the current content`() {
    val content = book.content[0]

    val descriptor = ReaderContentDescriptor.of(content, content.positionAt(2))

    assertEquals(content.positionAt(2), descriptor.position)
    assertEquals("last", descriptor.reference)

    assertThrowsExactly(IllegalArgumentException::class.java) {
      ReaderContentDescriptor.of(
        content,
        content.positionAt(0).copy(unitKind = ReaderUnitKind.PROVIDER_SEGMENT),
      )
    }

    assertThrowsExactly(IllegalArgumentException::class.java) {
      ReaderContentDescriptor.of(content, book.content[2].positionAt(0))
    }

    assertThrowsExactly(IllegalArgumentException::class.java) {
      ReaderContentDescriptor.of(
        content,
        content.positionAt(0).copy(unitIndex = 3),
      )
    }
  }
}
