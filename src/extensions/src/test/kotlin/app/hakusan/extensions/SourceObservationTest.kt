package app.hakusan.extensions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SourceObservationTest {
  private val source = SourceIdentity("source")
  private val title = SourceTitleKey(source, "title")
  private val chapter = SourceChapterKey(title, "chapter")

  @Test
  fun `unavailability belongs to every operation failure set`() {
    val browse: SourceBrowseFailure = SourceFailure.Unavailable
    val details: SourceDetailsFailure = SourceFailure.Unavailable
    val refresh: ChapterRefreshFailure = SourceFailure.Unavailable
    val content: SourceContentFailure = SourceFailure.Unavailable

    assertSame(browse, details)
    assertSame(details, refresh)
    assertSame(refresh, content)
  }

  @Test
  fun `browse results keep an owned copy in exact order`() {
    val first = SourceTitle(SourceTitleKey(source, "first"), "Shared name")

    val second = SourceTitle(SourceTitleKey(source, "second"), "Shared name")

    val input = mutableListOf(second, first)

    val result = SourceBrowseResult.create(source, input).successValue()

    input.clear()
    assertEquals(source, result.source)
    assertEquals(listOf(second, first), result.titles)

    assertThrows(UnsupportedOperationException::class.java) {
      (result.titles as MutableList).clear()
    }
  }

  @Test
  fun `empty browse results are valid`() {
    val result = SourceBrowseResult.create(source, emptyList()).successValue()

    assertEquals(source, result.source)
    assertEquals(emptyList<SourceTitle>(), result.titles)
  }

  @Test
  fun `browse results reject duplicate title identities`() {
    val titles =
      listOf(SourceTitle(title, "First"), SourceTitle(title, "Conflicting"))

    val result = SourceBrowseResult.create(source, titles)

    assertEquals(
      SourceFailure.InvalidBrowseResult(BrowseResultRejection.DUPLICATE_TITLE),
      result.failureValue(),
    )
  }

  @Test
  fun `browse results reject titles of another source`() {
    val foreign = SourceTitleKey(SourceIdentity("other"), "title")

    val titles =
      listOf(SourceTitle(title, "Own"), SourceTitle(foreign, "Foreign"))

    val result = SourceBrowseResult.create(source, titles)

    assertEquals(
      SourceFailure.InvalidBrowseResult(BrowseResultRejection.FOREIGN_SOURCE),
      result.failureValue(),
    )
  }

  @Test
  fun `title details keep display metadata exactly`() {
    val details = SourceTitleDetails(SourceTitle(title, ""), "  Description  ")

    assertEquals("", details.title.displayName)
    assertEquals("  Description  ", details.description)
  }

  @Test
  fun `chapter content keeps an owned copy of provider units`() {
    val page = SourceContentUnit(SourceContentUnitKind.PAGE, "page")

    val segment =
      SourceContentUnit(SourceContentUnitKind.PROVIDER_SEGMENT, "segment")

    val input = mutableListOf(page, segment, page)

    val content = SourceChapterContent.create(chapter, input).successValue()

    input.clear()
    assertEquals(chapter, content.chapter)
    assertEquals(listOf(page, segment, page), content.units)

    assertThrows(UnsupportedOperationException::class.java) {
      (content.units as MutableList).clear()
    }
  }

  @Test
  fun `empty chapter content is rejected`() {
    val content = SourceChapterContent.create(chapter, emptyList())

    assertEquals(
      SourceFailure.InvalidChapterContent(
        ChapterContentRejection.EMPTY_CONTENT
      ),
      content.failureValue(),
    )
  }

  @Test
  fun `blank content references are rejected`() {
    val page = SourceContentUnit(SourceContentUnitKind.PAGE, "page")

    for (blank in blankTexts) {
      val unit = SourceContentUnit(SourceContentUnitKind.PAGE, blank)

      val content = SourceChapterContent.create(chapter, listOf(page, unit))

      assertEquals(
        SourceFailure.InvalidChapterContent(
          ChapterContentRejection.BLANK_REFERENCE
        ),
        content.failureValue(),
      )
    }
  }
}
