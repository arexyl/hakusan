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
    val selection = LibraryCategorySelection.of(input)

    input.clear()

    assertEquals(setOf(CategoryId(1), CategoryId(2)), selection.categoryIds)
    assertThrows(IllegalArgumentException::class.java) {
      LibraryCategorySelection.of(emptyList())
    }
  }

  @Test
  fun `Library Add policy resolves automatic and explicit choices`() {
    val first = category(1, "Default")
    val renamed = category(2, "Want to read")
    val duplicateName = category(3, "Want to read")

    assertSame(
      AutomaticCategoryResolution.CreateDefault,
      LibraryAddPolicy.resolveAutomatic(emptyList()),
    )
    assertEquals(
      CategoryAssignment(setOf(renamed.id)),
      LibraryAddPolicy.resolveAutomatic(listOf(renamed)),
    )
    assertEquals(
      AutomaticCategoryResolution.SelectionRequired(
        setOf(first, renamed, duplicateName),
      ),
      LibraryAddPolicy.resolveAutomatic(listOf(first, renamed, duplicateName)),
    )
    assertEquals(
      CategoryAssignment(setOf(renamed.id, duplicateName.id)),
      LibraryAddPolicy.resolveExplicit(
        categories = listOf(first, renamed, duplicateName),
        selection = LibraryCategorySelection.of(
          listOf(renamed.id, duplicateName.id),
        ),
      ),
    )
    assertEquals(
      ExplicitCategoryResolution.CategoriesNotFound(setOf(CategoryId(4))),
      LibraryAddPolicy.resolveExplicit(
        categories = listOf(first, renamed),
        selection = LibraryCategorySelection.of(
          listOf(first.id, CategoryId(4)),
        ),
      ),
    )
    assertThrows(IllegalStateException::class.java) {
      LibraryAddPolicy.resolveAutomatic(
        listOf(first, category(1, "Renamed")),
      )
    }
  }

  @Test
  fun `explicit Library Add rejects duplicate category identities`() {
    val storedCategory = category(1, "Default")

    assertThrows(IllegalStateException::class.java) {
      LibraryAddPolicy.resolveExplicit(
        categories = listOf(storedCategory, category(1, "Renamed")),
        selection = LibraryCategorySelection.of(listOf(storedCategory.id)),
      )
    }
  }

  @Test
  fun `Library state normalizes shared title state and derives counts`() {
    val progress = LibraryTitleProgressSummary(
      chapterCount = 3,
      readChapterCount = 1,
      resumeAvailability = LibraryResumeAvailability.AVAILABLE,
    )
    val title = LibraryTitle(
      id = TitleId(TITLE_ID),
      alias = SourceTitleAlias("source", "title"),
      displayName = "Title",
      description = "Description",
      progress = progress,
    )
    val firstShelfTitleIds = mutableListOf(title.id)
    val firstShelf = LibraryShelf.create(
      category = category(1, "Default"),
      titleIds = firstShelfTitleIds,
    )
    val secondShelf = LibraryShelf.create(
      category = category(2, "Reading"),
      titleIds = listOf(title.id),
    )
    val mutableTitles = linkedMapOf(title.id to title)
    val mutableShelves = mutableListOf(firstShelf, secondShelf)
    val state = LibraryState.create(mutableTitles, mutableShelves)

    mutableTitles.clear()
    mutableShelves.clear()
    firstShelfTitleIds.clear()

    assertEquals(mapOf(title.id to title), state.titlesById)
    assertEquals(progress, state.titlesById.getValue(title.id).progress)
    assertEquals(setOf(firstShelf, secondShelf), state.shelves)
    assertEquals(1, state.shelves.single { it == firstShelf }.titleCount)
    assertThrows(IllegalArgumentException::class.java) {
      LibraryState.create(
        titlesById = emptyMap(),
        shelves = listOf(firstShelf),
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      LibraryState.create(
        titlesById = mapOf(TitleId(OTHER_TITLE_ID) to title),
        shelves = listOf(
          LibraryShelf.create(
            category = category(1, "Default"),
            titleIds = listOf(TitleId(OTHER_TITLE_ID)),
          ),
        ),
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      LibraryState.create(
        titlesById = mapOf(title.id to title),
        shelves = listOf(
          firstShelf,
          LibraryShelf.create(
            category = category(1, "Renamed"),
            titleIds = listOf(title.id),
          ),
        ),
      )
    }
  }

  @Test
  fun `Library progress validates counts and resume availability`() {
    val progress = LibraryTitleProgressSummary(
      chapterCount = 3,
      readChapterCount = 1,
      resumeAvailability = LibraryResumeAvailability.AVAILABLE,
    )
    assertEquals(1, progress.readChapterCount)
    assertThrows(IllegalArgumentException::class.java) {
      LibraryTitleProgressSummary(
        chapterCount = 1,
        readChapterCount = 1,
        resumeAvailability = LibraryResumeAvailability.AVAILABLE,
      )
    }
  }

  private fun category(
    id: Long,
    name: String,
  ): LibraryCategory = LibraryCategory(CategoryId(id), name)

  private companion object {
    val TITLE_ID: UUID =
      UUID.fromString("00000000-0000-7000-8000-000000000001")
    val OTHER_TITLE_ID: UUID =
      UUID.fromString("00000000-0000-7000-8000-000000000002")
  }
}
