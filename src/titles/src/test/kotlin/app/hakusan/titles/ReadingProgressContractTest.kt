package app.hakusan.titles

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReadingProgressContractTest {
  @Test
  fun `chapter identities and aliases retain exact validated values`() {
    val id = ChapterId(CHAPTER_ID)
    val alias = SourceChapterAlias(
      titleAlias = TITLE_ALIAS,
      sourceChapterKey = " chapter/é ",
    )

    assertEquals(CHAPTER_ID, id.value)
    assertEquals(" chapter/é ", alias.sourceChapterKey)
    assertFalse(
      alias == SourceChapterAlias(TITLE_ALIAS, " chapter/e\u0301 "),
    )
    assertThrows(IllegalArgumentException::class.java) {
      ChapterId(UUID.fromString("00000000-0000-4000-8000-000000000001"))
    }
    assertThrows(IllegalArgumentException::class.java) {
      SourceChapterAlias(TITLE_ALIAS, " ")
    }
  }

  @Test
  fun `chapter snapshot owns canonical order and accepts empty`() {
    val chapters = mutableListOf(
      observed("opening", "Chapter 10"),
      observed("middle", "Chapter 2"),
      observed("final", "Chapter 1"),
    )
    val snapshot = ReconcileChapterSnapshot.of(TITLE_ALIAS, chapters)

    chapters.reverse()

    assertEquals(
      listOf("opening", "middle", "final"),
      snapshot.chapters.map { it.alias.sourceChapterKey },
    )
    assertTrue(
      ReconcileChapterSnapshot.of(TITLE_ALIAS, emptyList()).chapters.isEmpty(),
    )
  }

  @Test
  fun `chapter snapshot rejects foreign and duplicate aliases`() {
    val foreignTitle = SourceTitleAlias("other-source", "title")

    assertThrows(IllegalArgumentException::class.java) {
      ReconcileChapterSnapshot.of(
        TITLE_ALIAS,
        listOf(
          ReconcileSourceChapter(
            SourceChapterAlias(foreignTitle, "chapter"),
            "Chapter",
          ),
        ),
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      ReconcileChapterSnapshot.of(
        TITLE_ALIAS,
        listOf(observed("same", "One"), observed("same", "Two")),
      )
    }
  }

  private fun observed(
    key: String,
    displayName: String,
  ): ReconcileSourceChapter = ReconcileSourceChapter(
    alias = SourceChapterAlias(TITLE_ALIAS, key),
    displayName = displayName,
  )

  private companion object {
    val TITLE_ALIAS = SourceTitleAlias("source", "title")
    val CHAPTER_ID: UUID =
      UUID.fromString("00000000-0000-7000-8000-000000000002")
  }
}
