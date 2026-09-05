package app.hakusan.titles

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class LibraryContractTest {
  @Test
  fun `identities and aliases validate without normalizing`() {
    val titleId = TitleId(TITLE_ID)
    val categoryId = CategoryId(1)
    val alias = SourceTitleAlias(
      sourceIdentity = " source/α ",
      sourceTitleKey = " title/key ",
    )

    assertEquals(TITLE_ID, titleId.value)
    assertEquals(1, categoryId.value)
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
      CategoryId(0)
    }
    assertThrows(IllegalArgumentException::class.java) {
      SourceTitleAlias(" ", "title")
    }
    assertThrows(IllegalArgumentException::class.java) {
      SourceTitleAlias("source", "\t")
    }
  }

  @Test
  fun `explicit category selection is nonempty distinct and owned`() {
    val input = mutableListOf(CategoryId(1), CategoryId(1), CategoryId(2))
    val selection = LibraryCategorySelection.Explicit.of(input)

    input.clear()

    assertEquals(setOf(CategoryId(1), CategoryId(2)), selection.categoryIds)
    assertThrows(IllegalArgumentException::class.java) {
      LibraryCategorySelection.Explicit.of(emptyList())
    }
  }

  @Test
  fun `Library Add policy resolves automatic and explicit choices`() {
    val first = category(1, "Default")
    val renamed = category(2, "Want to read")
    val duplicateName = category(3, "Want to read")

    assertSame(
      InitialCategoryResolution.CreateDefault,
      LibraryAddPolicy.resolve(
        categories = emptyList(),
        selection = LibraryCategorySelection.Automatic,
      ),
    )
    assertEquals(
      InitialCategoryResolution.Assign(setOf(renamed.id)),
      LibraryAddPolicy.resolve(
        categories = listOf(renamed),
        selection = LibraryCategorySelection.Automatic,
      ),
    )
    assertEquals(
      InitialCategoryResolution.SelectionRequired(
        setOf(first, renamed, duplicateName),
      ),
      LibraryAddPolicy.resolve(
        categories = listOf(first, renamed, duplicateName),
        selection = LibraryCategorySelection.Automatic,
      ),
    )
    assertEquals(
      InitialCategoryResolution.Assign(setOf(renamed.id, duplicateName.id)),
      LibraryAddPolicy.resolve(
        categories = listOf(first, renamed, duplicateName),
        selection = LibraryCategorySelection.Explicit.of(
          listOf(renamed.id, duplicateName.id),
        ),
      ),
    )
    assertEquals(
      InitialCategoryResolution.CategoriesNotFound(setOf(CategoryId(4))),
      LibraryAddPolicy.resolve(
        categories = listOf(first, renamed),
        selection = LibraryCategorySelection.Explicit.of(
          listOf(first.id, CategoryId(4)),
        ),
      ),
    )
  }

  private fun category(
    id: Long,
    name: String,
  ): LibraryCategory = LibraryCategory(CategoryId(id), name)

  private companion object {
    val TITLE_ID: UUID =
      UUID.fromString("00000000-0000-7000-8000-000000000001")
  }
}
