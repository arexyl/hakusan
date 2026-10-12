package app.hakusan.sdk

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.function.Executable

class ScreenIdentityTest {
  @Test
  fun keysKeepTheirExactComponents() {
    val exact =
      listOf(" spaced ", "MiXeD", composedCafe, decomposedCafe, "a/b?c=d")

    val keys = exact.map { component ->
      ScreenChapterKey(
        ScreenTitleKey(ScreenSourceId(component), component),
        component,
      )
    }

    assertAll(
      keys.zip(exact).map { (key, component) ->
        Executable {
          assertEquals(component, key.sourceChapterKey)
          assertEquals(component, key.titleKey.sourceTitleKey)
          assertEquals(component, key.titleKey.sourceId.value)
        }
      }
    )

    assertEquals(exact.size, keys.toSet().size)
  }

  @Test
  fun equalLocalKeysUnderDifferentOwnersStayDistinct() {
    val first = titleKey("title", source)

    val second = titleKey("title", otherSource)

    assertNotEquals(first, second)
    assertNotEquals(ScreenChapterKey(first, "1"), ScreenChapterKey(second, "1"))
    assertEquals(ScreenChapterKey(first, "1"), ScreenChapterKey(first, "1"))
  }

  @Test
  fun blankComponentsAreRejected() {
    val blanks = listOf("", " ", "\t", "\n")

    assertAll(
      blanks.flatMap { blank ->
        listOf(
          Executable {
            assertThrows<IllegalArgumentException> { ScreenSourceId(blank) }
          },
          Executable {
            assertThrows<IllegalArgumentException> { titleKey(blank) }
          },
          Executable {
            assertThrows<IllegalArgumentException> {
              ScreenChapterKey(titleKey("title"), blank)
            }
          },
        )
      }
    )
  }

  @Test
  fun applicationIdsKeepAVersion7Variant2Uuid() {
    val uuid = UUID.fromString(UuidVectors.VERSION_7)

    assertEquals(uuid, ScreenTitleId(uuid).value)
    assertEquals(uuid, ScreenChapterId(uuid).value)
  }

  @Test
  fun applicationIdsRejectOtherVersionsAndVariants() {
    val uuids =
      (UuidVectors.OTHER_VERSIONS + UuidVectors.OTHER_VARIANTS).map(
        UUID::fromString
      )

    assertAll(
      uuids.flatMap { uuid ->
        listOf(
          Executable {
            assertThrows<IllegalArgumentException> { ScreenTitleId(uuid) }
          },
          Executable {
            assertThrows<IllegalArgumentException> { ScreenChapterId(uuid) }
          },
        )
      }
    )
  }

  @Test
  fun shelfIdsArePositive() {
    assertEquals(1L, ScreenShelfId(1L).value)
    assertThrows<IllegalArgumentException> { ScreenShelfId(0L) }
    assertThrows<IllegalArgumentException> { ScreenShelfId(-1L) }
  }
}
