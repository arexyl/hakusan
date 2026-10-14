package app.hakusan.titles

import app.hakusan.titles.storage.CategoryEntity
import app.hakusan.titles.storage.CurrentCategories
import app.hakusan.titles.storage.toChapterIdOrNull
import app.hakusan.titles.storage.toStoredBytes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows

class IdentityFixturesTest {
  @Test
  fun chapterIdentitiesAreFixedUuidV7Values() {
    val ids = SEQUENCES.map(::chapterId)

    assertAll(
      { assertEquals(ids, SEQUENCES.map(::chapterId)) },
      { assertEquals(SEQUENCES.size, ids.toSet().size) },
      {
        assertEquals(
          ids,
          ids.map { id -> id.toStoredBytes().toChapterIdOrNull() },
        )
      },
    )
  }

  @Test
  fun categoryIdentityMatchesTheReadBoundary() {
    val current = CurrentCategories.of(listOf(CategoryEntity(7, "Seven")))

    assertEquals(current.categories.single().id, categoryId(7))
    assertNotEquals(categoryId(7), categoryId(8))
  }

  @Test
  fun invalidIdentityInputsAreRejected() {
    assertAll(
      { assertThrows<IllegalArgumentException> { chapterId(-1) } },
      { assertThrows<IllegalArgumentException> { categoryId(0) } },
      { assertThrows<IllegalArgumentException> { categoryId(-1) } },
    )
  }

  private companion object {
    val SEQUENCES = listOf(0, 1, 2, 255, Int.MAX_VALUE)
  }
}
