package app.hakusan.titles

import kotlin.uuid.Uuid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LibraryTest {
  @Test
  fun categoryIdentitiesArePositive() {
    assertEquals(1L, CategoryId.ofOrNull(1)?.key)
    assertEquals(Long.MAX_VALUE, CategoryId.ofOrNull(Long.MAX_VALUE)?.key)
    assertNull(CategoryId.ofOrNull(0))
    assertNull(CategoryId.ofOrNull(-1))
    assertNull(CategoryId.ofOrNull(Long.MIN_VALUE))
  }

  @Test
  fun selectionCountsARepeatedIdentityOnce() {
    val selection = LibraryCategorySelection.of(listOf(FIRST, SECOND, FIRST))

    assertEquals(setOf(FIRST, SECOND), selection.categoryIds)
    assertEquals(LibraryCategorySelection.of(listOf(SECOND, FIRST)), selection)
  }

  @Test
  fun selectionRejectsAnEmptySet() {
    assertThrows<IllegalArgumentException> {
      LibraryCategorySelection.of(emptyList())
    }
  }

  @Test
  fun selectionOwnsAnImmutableCopy() {
    val source = mutableListOf(FIRST)

    val selection = LibraryCategorySelection.of(source)

    source += SECOND

    assertEquals(setOf(FIRST), selection.categoryIds)

    assertThrows<UnsupportedOperationException> {
      @Suppress("UNCHECKED_CAST")
      (selection.categoryIds as MutableSet<CategoryId>).clear()
    }
  }

  @Test
  fun membershipIsNonemptyAndOwned() {
    val source = mutableListOf(FIRST, SECOND, FIRST)

    val membership = LibraryMembership.of(TITLE, source)

    source.clear()

    assertEquals(TITLE, membership.titleId)
    assertEquals(setOf(FIRST, SECOND), membership.categoryIds)

    assertThrows<UnsupportedOperationException> {
      @Suppress("UNCHECKED_CAST")
      (membership.categoryIds as MutableSet<CategoryId>).clear()
    }

    assertThrows<IllegalArgumentException> {
      LibraryMembership.of(TITLE, emptyList())
    }
  }

  @Test
  fun selectionRequiredNamesSeveralCategories() {
    val first = LibraryCategory(FIRST, "Shared")

    val second = LibraryCategory(SECOND, "Shared")

    val required =
      LibraryAddResult.CategorySelectionRequired.of(listOf(first, second))

    assertEquals(setOf(first, second), required.categories)

    assertThrows<IllegalArgumentException> {
      LibraryAddResult.CategorySelectionRequired.of(listOf(first))
    }

    assertThrows<IllegalArgumentException> {
      LibraryAddResult.CategorySelectionRequired.of(emptyList())
    }
  }

  @Test
  fun categoriesNotFoundNamesAMissingCategory() {
    val missing = ExplicitLibraryAddResult.CategoriesNotFound.of(listOf(FIRST))

    assertEquals(setOf(FIRST), missing.categoryIds)

    assertThrows<IllegalArgumentException> {
      ExplicitLibraryAddResult.CategoriesNotFound.of(emptyList())
    }
  }

  @Test
  fun automaticAndExplicitResultsAreDisjoint() {
    val membership = LibraryMembership.of(TITLE, listOf(FIRST))

    val automatic: Any = LibraryAddResult.Success(membership)

    val explicit: Any = ExplicitLibraryAddResult.Success(membership)

    val automaticMissing: Any = LibraryAddResult.TitleNotFound
    val explicitMissing: Any = ExplicitLibraryAddResult.TitleNotFound

    assertNotEquals(automatic, explicit)
    assertNotEquals(automaticMissing, explicitMissing)
    assertFalse(automatic is ExplicitLibraryAddResult)
    assertFalse(explicit is LibraryAddResult)
    assertFalse(automaticMissing is ExplicitLibraryAddResult)
    assertFalse(explicitMissing is LibraryAddResult)
  }

  private companion object {
    val FIRST = checkNotNull(CategoryId.ofOrNull(1))
    val SECOND = checkNotNull(CategoryId.ofOrNull(2))

    val TITLE =
      checkNotNull(
        TitleId.ofOrNull(Uuid.parse("0192a6f0-7c3e-7d4a-9f12-3456789abcde"))
      )
  }
}
