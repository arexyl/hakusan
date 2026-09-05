package app.hakusan.titles

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class LibraryContractTest {
  @Test
  fun `title identities and aliases validate without normalizing`() {
    val titleId = TitleId(TITLE_ID)
    val alias = SourceTitleAlias(
      sourceIdentity = " source/α ",
      sourceTitleKey = " title/key ",
    )

    assertEquals(TITLE_ID, titleId.value)
    assertEquals(" source/α ", alias.sourceIdentity)
    assertEquals(" title/key ", alias.sourceTitleKey)
    assertNotEquals(alias, SourceTitleAlias("source/α", " title/key "))
    assertNotEquals(alias, SourceTitleAlias(" source/Α ", " title/key "))
    assertNotEquals(
      SourceTitleAlias("source", "é"),
      SourceTitleAlias("source", "e\u0301"),
    )
    assertThrows(IllegalArgumentException::class.java) {
      TitleId(UUID.fromString("00000000-0000-4000-8000-000000000001"))
    }
    assertThrows(IllegalArgumentException::class.java) {
      SourceTitleAlias(" ", "title")
    }
    assertThrows(IllegalArgumentException::class.java) {
      SourceTitleAlias("source", "\t")
    }
  }

  private companion object {
    val TITLE_ID: UUID =
      UUID.fromString("00000000-0000-7000-8000-000000000001")
  }
}
