package app.hakusan.extensions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SourceIdentityTest {
  @Test
  fun `equal identities have equal hashes`() {
    val first = SourceChapterKey(
      title = SourceTitleKey(
        source = SourceIdentity("source"),
        key = "title",
      ),
      key = "chapter",
    )
    val second = SourceChapterKey(
      title = SourceTitleKey(
        source = SourceIdentity("source"),
        key = "title",
      ),
      key = "chapter",
    )

    assertEquals(first, second)
    assertEquals(first.hashCode(), second.hashCode())
  }

  @Test
  fun `display names do not merge title identities`() {
    val sharedDisplayName = "Shared title"
    val first = SourceTitleKey(SourceIdentity("first"), "work")
    val second = SourceTitleKey(SourceIdentity("second"), "work")
    val titles = mapOf(
      first to sharedDisplayName,
      second to sharedDisplayName,
    )

    assertEquals(2, titles.size)
  }

  @Test
  fun `chapter keys remain scoped to their title`() {
    val source = SourceIdentity("source")
    val first = SourceChapterKey(
      SourceTitleKey(source, "first-title"),
      "shared-chapter",
    )
    val second = SourceChapterKey(
      SourceTitleKey(source, "second-title"),
      "shared-chapter",
    )
    val third = SourceChapterKey(
      SourceTitleKey(SourceIdentity("other-source"), "first-title"),
      "shared-chapter",
    )

    assertNotEquals(first, second)
    assertNotEquals(first, third)
  }

  @Test
  fun `structural identities avoid delimiter collisions`() {
    val first = SourceTitleKey(
      source = SourceIdentity("source:part"),
      key = "title",
    )
    val second = SourceTitleKey(
      source = SourceIdentity("source"),
      key = "part:title",
    )

    assertNotEquals(first, second)
  }

  @Test
  fun `opaque identity values are not normalized`() {
    val source = SourceIdentity("SOURCE")
    val composed = SourceTitleKey(source, "caf\u00e9")
    val decomposed = SourceTitleKey(source, "cafe\u0301")
    val spaced = SourceTitleKey(source, " work ")
    val relativeLocator = SourceTitleKey(source, "/work")
    val absoluteLocator = SourceTitleKey(
      source,
      "https://example.test/work",
    )
    val exactChapter = SourceChapterKey(relativeLocator, " CHAPTER ")
    val normalizedChapter = SourceChapterKey(relativeLocator, "chapter")

    assertEquals("SOURCE", source.value)
    assertNotEquals(source, SourceIdentity("source"))
    assertNotEquals(composed, decomposed)
    assertEquals(" work ", spaced.key)
    assertNotEquals(relativeLocator, absoluteLocator)
    assertEquals(" CHAPTER ", exactChapter.key)
    assertNotEquals(exactChapter, normalizedChapter)
  }

  @Test
  fun `different keys survive a hash collision`() {
    val source = SourceIdentity("source")
    val first = SourceTitleKey(source, "FB")
    val second = SourceTitleKey(source, "Ea")

    assertEquals(first.hashCode(), second.hashCode())
    assertNotEquals(first, second)
    assertEquals(2, hashSetOf(first, second).size)
  }

  @Test
  fun `blank identity components are rejected`() {
    val source = SourceIdentity("source")
    val title = SourceTitleKey(source, "title")

    listOf("", " ", "\t\n", "\u2003").forEach { value ->
      assertThrows(IllegalArgumentException::class.java) {
        SourceIdentity(value)
      }
      assertThrows(IllegalArgumentException::class.java) {
        SourceTitleKey(source, value)
      }
      assertThrows(IllegalArgumentException::class.java) {
        SourceChapterKey(title, value)
      }
      assertThrows(IllegalArgumentException::class.java) {
        source.copy(value = value)
      }
      assertThrows(IllegalArgumentException::class.java) {
        title.copy(key = value)
      }
      assertThrows(IllegalArgumentException::class.java) {
        SourceChapterKey(title, "chapter").copy(key = value)
      }
    }
  }
}
