package app.hakusan.titles

import app.hakusan.titles.storage.AutomaticAddDecision
import app.hakusan.titles.storage.CategoryEntity
import app.hakusan.titles.storage.CurrentCategories
import app.hakusan.titles.storage.TitleCategoryEntity
import app.hakusan.titles.storage.decideAutomaticAdd
import app.hakusan.titles.storage.mapMembership
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows

class LibraryFixturesTest {
  @Test
  fun categoriesMatchTheReadBoundary() {
    val current =
      CurrentCategories.of(
        listOf(CategoryEntity(1, "Default"), CategoryEntity(2, "Default"))
      )

    assertEquals(
      setOf(
        libraryCategory(categoryId(1), "Default"),
        libraryCategory(categoryId(2), "Default"),
      ),
      current.categories,
    )
  }

  @Test
  fun successMatchesTheMembershipThatTheReadBoundaryReads() {
    val stored =
      mapMembership(
        titleStorageId = TITLE_KEY,
        titleId = TITLE_ID,
        rows =
          listOf(
            TitleCategoryEntity(TITLE_KEY, 2),
            TitleCategoryEntity(TITLE_KEY, 5),
          ),
      )

    val membership =
      libraryMembership(TITLE_ID, setOf(categoryId(5), categoryId(2)))

    assertEquals(stored, membership)

    assertEquals(
      LibraryAddResult.Success(checkNotNull(stored)),
      libraryAddSuccess(membership),
    )
  }

  @Test
  fun selectionRequiredMatchesTheOutcomeOfTheAddPolicy() {
    val current =
      CurrentCategories.of(
        listOf(
          CategoryEntity(1, "Default"),
          CategoryEntity(2, "Later"),
          CategoryEntity(7, "Done"),
        )
      )

    val decision =
      assertInstanceOf(
        AutomaticAddDecision.SelectionRequired::class.java,
        decideAutomaticAdd(current),
      )

    val built =
      categorySelectionRequired(
        setOf(
          libraryCategory(categoryId(7), "Done"),
          libraryCategory(categoryId(1), "Default"),
          libraryCategory(categoryId(2), "Later"),
        )
      )

    assertEquals(
      LibraryAddResult.CategorySelectionRequired.of(decision.categories),
      built,
    )
  }

  @Test
  fun impossibleLibraryValuesAreRejected() {
    val first = libraryCategory(categoryId(1), "First")

    val renamed = libraryCategory(categoryId(1), "Renamed")

    val second = libraryCategory(categoryId(2), "Second")

    assertAll(
      rejects { libraryMembership(TITLE_ID, emptySet()) },
      rejects { categorySelectionRequired(setOf(first)) },
      rejects { categorySelectionRequired(setOf(first, renamed)) },
      rejects { categorySelectionRequired(setOf(first, renamed, second)) },
    )
  }

  private fun rejects(build: () -> Unit): () -> Unit = {
    assertThrows<IllegalArgumentException> { build() }
  }

  private companion object {
    const val TITLE_KEY = 31L

    val TITLE_ID = TitleId.generate()
  }
}
