package app.hakusan.titles

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows

class TitleObservationsTest {
  @Test
  fun observationKeepsDetailsAndOrder() {
    val chapters = listOf(chapter(TITLE, "2"), chapter(TITLE, "10"))

    val observation =
      AcceptedTitleObservation.of(TITLE, "Title", null, chapters)

    assertEquals(TITLE, observation.alias)
    assertEquals("Title", observation.displayName)
    assertNull(observation.description)
    assertEquals(chapters, observation.chapters)
  }

  @Test
  fun emptySnapshotIsAValidObservation() {
    val observation =
      AcceptedTitleObservation.of(TITLE, "Title", "About", emptyList())

    assertTrue(observation.chapters.isEmpty())
  }

  @Test
  fun observationRejectsForeignChapters() {
    val other = SourceTitleAlias.of("other", "title")

    val error =
      assertThrows<IllegalArgumentException> {
        AcceptedTitleObservation.of(
          TITLE,
          "Title",
          null,
          listOf(chapter(TITLE, "1"), chapter(other, "2")),
        )
      }

    assertFalse(error.message.orEmpty().contains("other"))
  }

  @Test
  fun observationRejectsRepeatedChapterAliases() {
    val error =
      assertThrows<IllegalArgumentException> {
        AcceptedTitleObservation.of(
          TITLE,
          "Title",
          null,
          listOf(chapter(TITLE, "1"), chapter(TITLE, "1", "Again")),
        )
      }

    assertFalse(error.message.orEmpty().contains("Again"))
  }

  @Test
  fun observationOwnsAnImmutableCopyOfItsChapters() {
    val source = mutableListOf(chapter(TITLE, "1"))

    val observation = AcceptedTitleObservation.of(TITLE, "Title", null, source)

    source += chapter(TITLE, "2")

    assertEquals(1, observation.chapters.size)

    assertThrows<UnsupportedOperationException> {
      @Suppress("UNCHECKED_CAST")
      (observation.chapters as MutableList<AcceptedChapter>).clear()
    }
  }

  @Test
  fun throwingAliasFactoriesRejectBlankComponents() {
    assertAll(
      {
        assertThrows<IllegalArgumentException> { SourceTitleAlias.of(" ", "k") }
      },
      {
        assertThrows<IllegalArgumentException> { SourceTitleAlias.of("s", "") }
      },
      {
        assertThrows<IllegalArgumentException> { TitleLocalChapterKey.of("\t") }
      },
    )
  }

  @Test
  fun throwingAliasFactoriesKeepExactText() {
    val alias = SourceTitleAlias.of(" source ", " Key ")

    assertEquals(" source ", alias.sourceIdentity)
    assertEquals(" Key ", alias.sourceTitleKey)
    assertEquals(" 01 ", TitleLocalChapterKey.of(" 01 ").text)
  }

  private fun chapter(
    title: SourceTitleAlias,
    key: String,
    name: String = "Chapter $key",
  ): AcceptedChapter =
    AcceptedChapter(
      SourceChapterAlias(title, TitleLocalChapterKey.of(key)),
      name,
    )

  private companion object {
    val TITLE = SourceTitleAlias.of("source", "title")
  }
}
