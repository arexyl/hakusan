package app.hakusan.extensions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class SourceObservationTest {
  private val source = SourceIdentity("source")

  @Test
  fun `unavailability belongs to every source operation failure set`() {
    val browse: SourceBrowseFailure = SourceFailure.Unavailable
    val details: SourceDetailsFailure = SourceFailure.Unavailable
    val refresh: ChapterRefreshFailure = SourceFailure.Unavailable
    val content: SourceContentFailure = SourceFailure.Unavailable

    assertSame(browse, details)
    assertSame(details, refresh)
    assertSame(refresh, content)
  }

  @Test
  fun `browse results preserve owner order and equal display names`() {
    val first = SourceTitle(
      key = SourceTitleKey(source, "first"),
      displayName = "Shared name",
    )
    val second = SourceTitle(
      key = SourceTitleKey(source, "second"),
      displayName = "Shared name",
    )
    val input = mutableListOf(first, second)

    val result = SourceBrowseResult.create(source, input).successValue()
    input.clear()

    assertEquals(source, result.source)
    assertEquals(listOf(first, second), result.titles)
    assertThrows(UnsupportedOperationException::class.java) {
      (result.titles as MutableList).clear()
    }
  }

  @Test
  fun `browse results reject duplicate title identities`() {
    val key = SourceTitleKey(source, "title")
    val result = SourceBrowseResult.create(
      source = source,
      titles = listOf(
        SourceTitle(key, "First observation"),
        SourceTitle(key, "Conflicting observation"),
      ),
    )

    assertEquals(
      SourceFailure.InvalidBrowseResult(
        BrowseResultRejection.DUPLICATE_TITLE,
      ),
      result.failureValue(),
    )
  }

  @Test
  fun `browse results reject titles owned by another source`() {
    val result = SourceBrowseResult.create(
      source = source,
      titles = listOf(
        SourceTitle(
          SourceTitleKey(SourceIdentity("other"), "title"),
          "Title",
        ),
      ),
    )

    assertEquals(
      SourceFailure.InvalidBrowseResult(
        BrowseResultRejection.FOREIGN_SOURCE,
      ),
      result.failureValue(),
    )
  }

  @Test
  fun `empty browse results retain their source`() {
    val result = SourceBrowseResult.create(
      source = source,
      titles = emptyList(),
    ).successValue()

    assertEquals(source, result.source)
    assertEquals(emptyList<SourceTitle>(), result.titles)
  }

  @Test
  fun `title details preserve source metadata exactly`() {
    val title = SourceTitle(
      key = SourceTitleKey(source, "title"),
      displayName = "",
    )
    val details = SourceTitleDetails(
      title = title,
      description = "  Description  ",
    )

    assertEquals("", details.title.displayName)
    assertEquals("  Description  ", details.description)
  }

  @Test
  fun `chapter content preserves provider order and boundaries`() {
    val chapter = SourceChapterKey(
      SourceTitleKey(source, "title"),
      "chapter",
    )
    val page = SourceContentUnit(
      kind = SourceContentUnitKind.PAGE,
      reference = "page-reference",
    )
    val longSegment = SourceContentUnit(
      kind = SourceContentUnitKind.PROVIDER_SEGMENT,
      reference = "long-segment-reference",
    )
    val input = mutableListOf(page, longSegment, page)

    val content = SourceChapterContent.create(
      chapter,
      input,
    ).successValue()
    input.clear()

    assertEquals(chapter, content.chapter)
    assertEquals(listOf(page, longSegment, page), content.units)
    assertThrows(UnsupportedOperationException::class.java) {
      (content.units as MutableList).clear()
    }
  }

  @Test
  fun `empty chapter content returns a structured failure`() {
    val chapter = SourceChapterKey(
      SourceTitleKey(source, "title"),
      "chapter",
    )

    val content = SourceChapterContent.create(chapter, emptyList())

    assertEquals(
      SourceFailure.InvalidChapterContent(
        ChapterContentRejection.EMPTY_CONTENT,
      ),
      content.failureValue(),
    )
  }

  @Test
  fun `blank content references are rejected`() {
    listOf("", " ", "\t\n", "\u2003").forEach { value ->
      val unit = SourceContentUnit(
        kind = SourceContentUnitKind.PAGE,
        reference = value,
      )
      val chapter = SourceChapterKey(
        SourceTitleKey(source, "title"),
        "chapter",
      )

      assertEquals(
        SourceFailure.InvalidChapterContent(
          ChapterContentRejection.BLANK_REFERENCE,
        ),
        SourceChapterContent.create(
          chapter = chapter,
          units = listOf(unit),
        ).failureValue(),
      )
    }
  }
}

private fun <Value> SourceResult<Value, *>.successValue(): Value = when (this) {
  is SourceResult.Success -> value
  is SourceResult.Failure -> fail("Expected success, got $error")
}

private fun <Error : SourceFailure> SourceResult<*, Error>.failureValue():
  Error = when (this) {
    is SourceResult.Success -> fail("Expected failure, got $value")
    is SourceResult.Failure -> error
  }
