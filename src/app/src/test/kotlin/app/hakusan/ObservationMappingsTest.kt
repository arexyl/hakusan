package app.hakusan

import app.hakusan.extensions.ChapterSequenceStatus
import app.hakusan.extensions.ChapterSnapshot
import app.hakusan.extensions.SourceChapter
import app.hakusan.extensions.SourceChapterKey
import app.hakusan.extensions.SourceIdentity
import app.hakusan.extensions.SourceResult
import app.hakusan.extensions.SourceTitle
import app.hakusan.extensions.SourceTitleDetails
import app.hakusan.extensions.SourceTitleKey
import app.hakusan.titles.AcceptedChapter
import app.hakusan.titles.SourceChapterAlias
import app.hakusan.titles.SourceTitleAlias
import app.hakusan.titles.TitleLocalChapterKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class ObservationMappingsTest {
  private val title = SourceTitleKey(SourceIdentity("source"), "title")

  @Test
  fun `keys and display texts reach the observation exactly`() {
    // The title key ends in U+0065 U+0301, which stays decomposed.
    val decomposedCafe = "Cafe" + Char(0x0301)

    val exactTitle = SourceTitleKey(SourceIdentity(" Source "), decomposedCafe)

    val localKeys =
      listOf(" padded ", "Case", "case", "https://example.test/a?b=c#d")

    val chapters = localKeys.map { key ->
      SourceChapter(SourceChapterKey(exactTitle, key), " Name $key ")
    }

    val details =
      SourceTitleDetails(SourceTitle(exactTitle, " Title "), " About ")

    val observation =
      details.toAcceptedTitleObservation(snapshot(exactTitle, chapters))

    val alias = SourceTitleAlias.of(" Source ", decomposedCafe)

    val expected = localKeys.map { key ->
      val chapterAlias = SourceChapterAlias(alias, TitleLocalChapterKey.of(key))

      AcceptedChapter(chapterAlias, " Name $key ")
    }

    assertEquals(alias, observation.alias)
    assertEquals(" Title ", observation.displayName)
    assertEquals(" About ", observation.description)
    assertEquals(expected, observation.chapters)
  }

  @Test
  fun `chapters keep the canonical order of the snapshot`() {
    // No sort of the keys or the labels reconstructs this order.
    val chapters =
      listOf("c10" to "Chapter 10", "c1" to "Chapter 1", "c2" to "Chapter 2")
        .map { (key, name) ->
          SourceChapter(SourceChapterKey(title, key), name)
        }

    val details = SourceTitleDetails(SourceTitle(title, "Title"), null)

    val observation =
      details.toAcceptedTitleObservation(snapshot(title, chapters))

    assertEquals(
      listOf("c10", "c1", "c2"),
      observation.chapters.map { it.alias.localKey.text },
    )

    assertEquals(
      listOf("Chapter 10", "Chapter 1", "Chapter 2"),
      observation.chapters.map { it.displayName },
    )
  }

  @Test
  fun `an absent description and an empty snapshot stay absent and empty`() {
    val details = SourceTitleDetails(SourceTitle(title, "Title"), null)

    val observation =
      details.toAcceptedTitleObservation(snapshot(title, emptyList()))

    assertNull(observation.description)
    assertEquals(emptyList<AcceptedChapter>(), observation.chapters)
  }

  @Test
  fun `a snapshot of another title is a defect`() {
    val other = SourceTitleKey(SourceIdentity("source"), "other")

    val chapters = listOf(SourceChapter(SourceChapterKey(other, "c1"), "C1"))

    val details = SourceTitleDetails(SourceTitle(title, "Title"), null)

    val otherSnapshot = snapshot(other, chapters)

    assertThrows(IllegalArgumentException::class.java) {
      details.toAcceptedTitleObservation(otherSnapshot)
    }
  }

  private fun snapshot(
    title: SourceTitleKey,
    chapters: List<SourceChapter>,
  ): ChapterSnapshot =
    when (
      val result =
        ChapterSnapshot.create(title, ChapterSequenceStatus.COMPLETE, chapters)
    ) {
      is SourceResult.Success -> result.value
      is SourceResult.Failure ->
        fail("Expected a snapshot, got ${result.error}.")
    }
}
