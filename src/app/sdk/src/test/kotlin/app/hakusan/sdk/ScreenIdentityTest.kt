package app.hakusan.sdk

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ScreenIdentityTest {
  @Test
  fun `source keys remain exact and structurally qualified`() {
    val source = ScreenSourceId(" source/alpha ")
    val title = ScreenTitleKey(source, " title/key ")
    val chapter = ScreenChapterKey(title, " chapter/key ")

    assertEquals(" source/alpha ", source.value)
    assertEquals(" title/key ", title.sourceTitleKey)
    assertEquals(" chapter/key ", chapter.sourceChapterKey)
    assertNotEquals(source, ScreenSourceId("source/alpha"))
    assertNotEquals(
      ScreenTitleKey(ScreenSourceId("source"), "é"),
      ScreenTitleKey(ScreenSourceId("source"), "e\u0301"),
    )
  }

  @Test
  fun `blank keys and invalid local identities are rejected`() {
    assertThrows(IllegalArgumentException::class.java) {
      ScreenSourceId("\t")
    }
    assertThrows(IllegalArgumentException::class.java) {
      ScreenTitleKey(ScreenSourceId("source"), " ")
    }
    assertThrows(IllegalArgumentException::class.java) {
      ScreenChapterKey(
        ScreenTitleKey(ScreenSourceId("source"), "title"),
        "\n",
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      ScreenTitleId(
        UUID.fromString("00000000-0000-4000-8000-000000000001"),
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      ScreenChapterId(
        UUID.fromString("00000000-0000-7000-7000-000000000001"),
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      ScreenShelfId(0)
    }
  }
}
