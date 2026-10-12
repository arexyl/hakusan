package app.hakusan.titles

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

class SourceAliasesTest {
  @Test
  fun aliasesRejectBlankComponents() {
    assertAll(
      BLANKS.flatMap { blank ->
        listOf(
          { assertNull(SourceTitleAlias.ofOrNull(blank, "title")) },
          { assertNull(SourceTitleAlias.ofOrNull("source", blank)) },
          { assertNull(TitleLocalChapterKey.ofOrNull(blank)) },
        )
      }
    )
  }

  @Test
  fun aliasesKeepExactText() {
    val alias = title(" source/α ", " Title/Key ")

    val chapter = chapter(alias, "\tChapter ")

    assertEquals(" source/α ", alias.sourceIdentity)
    assertEquals(" Title/Key ", alias.sourceTitleKey)
    assertEquals("\tChapter ", chapter.localKey.text)
    assertNotEquals(title("source", "key"), title("source", "Key"))
    assertNotEquals(title("source", "key"), title("source", " key"))
    assertNotEquals(title("source", COMPOSED), title("source", DECOMPOSED))
  }

  @Test
  fun equalLocalKeysUnderDifferentOwnersStayDistinct() {
    val first = title("first", "key")

    val second = title("second", "key")

    assertNotEquals(first, second)
    assertNotEquals(chapter(first, "1"), chapter(second, "1"))
  }

  @Test
  fun aliasesStayIndependentOfUuidAndStorageKeyValues() {
    val uuid = TitleId.generate().uuid

    val dashed = title("source", uuid.toHexDashString())

    assertEquals(uuid.toHexDashString(), dashed.sourceTitleKey)
    assertNotEquals(dashed, title("source", uuid.toHexString()))

    assertNotEquals(
      dashed,
      title("source", uuid.toHexDashString().uppercase()),
    )

    assertNotEquals(title("source", "1"), title("source", "01"))
    assertNotEquals(chapter(dashed, "1"), chapter(dashed, "01"))
  }

  private fun title(source: String, key: String): SourceTitleAlias =
    checkNotNull(SourceTitleAlias.ofOrNull(source, key))

  private fun chapter(
    title: SourceTitleAlias,
    key: String,
  ): SourceChapterAlias =
    SourceChapterAlias(title, checkNotNull(TitleLocalChapterKey.ofOrNull(key)))

  private companion object {
    const val COMPOSED = "é"
    const val DECOMPOSED = "é"

    val BLANKS = listOf("", " ", "\t", "\n", " ")
  }
}
