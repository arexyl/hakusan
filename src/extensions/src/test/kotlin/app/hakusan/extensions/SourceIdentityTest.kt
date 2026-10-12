package app.hakusan.extensions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SourceIdentityTest {
  private val source = SourceIdentity("source")
  private val otherSource = SourceIdentity("other-source")

  @Test
  fun `equal complete keys are one identity`() {
    val first = SourceChapterKey(SourceTitleKey(SourceIdentity("s"), "t"), "c")

    val second = SourceChapterKey(SourceTitleKey(SourceIdentity("s"), "t"), "c")

    assertEquals(first, second)
    assertEquals(first.hashCode(), second.hashCode())
  }

  @Test
  fun `equal local keys under different owners stay distinct`() {
    val title = SourceTitleKey(source, "title")

    val chapter = SourceChapterKey(title, "chapter")

    assertNotEquals(SourceTitleKey(otherSource, "title"), title)

    assertNotEquals(
      SourceChapterKey(SourceTitleKey(source, "t"), "chapter"),
      chapter,
    )

    assertNotEquals(
      SourceChapterKey(SourceTitleKey(otherSource, "title"), "chapter"),
      chapter,
    )
  }

  @Test
  fun `same-named works from different sources stay separate`() {
    val first = SourceTitleKey(source, "work")

    val second = SourceTitleKey(otherSource, "work")

    val titles = mapOf(first to "Shared name", second to "Shared name")

    assertEquals(2, titles.size)
  }

  @Test
  fun `structural keys avoid delimiter collisions`() {
    val first = SourceTitleKey(SourceIdentity("source:part"), "title")

    val second = SourceTitleKey(SourceIdentity("source"), "part:title")

    assertNotEquals(first, second)
  }

  @Test
  fun `different keys survive a hash collision`() {
    val first = SourceTitleKey(source, "FB")

    val second = SourceTitleKey(source, "Ea")

    assertEquals(first.hashCode(), second.hashCode())
    assertNotEquals(first, second)
    assertEquals(2, hashSetOf(first, second).size)
  }

  @Test
  fun `accepted components are not normalized`() {
    val upper = SourceIdentity("SOURCE")

    val relative = SourceTitleKey(upper, "/work")

    val chapter = SourceChapterKey(relative, " CHAPTER ")

    assertEquals("SOURCE", upper.value)
    assertEquals(" CHAPTER ", chapter.key)
    assertEquals(" work ", SourceTitleKey(upper, " work ").key)
    assertNotEquals(SourceIdentity("source"), upper)
    assertNotEquals(SourceChapterKey(relative, "chapter"), chapter)

    assertNotEquals(
      SourceTitleKey(upper, "https://example.test/work"),
      relative,
    )

    assertNotEquals(
      SourceTitleKey(upper, "cafe" + Char(0x0301)),
      SourceTitleKey(upper, "caf" + Char(0x00E9)),
    )
  }

  @Test
  fun `blank components are rejected on construction and copy`() {
    val title = SourceTitleKey(source, "title")

    val chapter = SourceChapterKey(title, "chapter")

    for (blank in blankTexts) {
      assertThrows(IllegalArgumentException::class.java) {
        SourceIdentity(blank)
      }

      assertThrows(IllegalArgumentException::class.java) {
        SourceTitleKey(source, blank)
      }

      assertThrows(IllegalArgumentException::class.java) {
        SourceChapterKey(title, blank)
      }

      assertThrows(IllegalArgumentException::class.java) {
        source.copy(value = blank)
      }

      assertThrows(IllegalArgumentException::class.java) {
        title.copy(key = blank)
      }

      assertThrows(IllegalArgumentException::class.java) {
        chapter.copy(key = blank)
      }
    }
  }
}
