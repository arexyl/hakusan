package app.hakusan.titles.storage

import app.hakusan.titles.CategoryId
import app.hakusan.titles.LibraryCategory
import app.hakusan.titles.StoredStateViolationException
import app.hakusan.titles.StoredStateViolationKind
import app.hakusan.titles.StoredStateViolationKind.IDENTITY
import app.hakusan.titles.StoredStateViolationKind.MEMBERSHIP
import app.hakusan.titles.StoredStateViolationKind.OWNERSHIP
import app.hakusan.titles.StoredStateViolationKind.STORAGE_KEY
import app.hakusan.titles.TitleId
import kotlin.uuid.Uuid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LibraryRowsTest {
  @Test
  fun currentCategoriesKeepEveryCategoryWithItsName() {
    val current =
      CurrentCategories.of(
        listOf(
          CategoryEntity(id = 1, name = "Default"),
          CategoryEntity(id = 2, name = "Default"),
          CategoryEntity(id = 3, name = ""),
        )
      )

    assertEquals(
      setOf(
        LibraryCategory(id(1), "Default"),
        LibraryCategory(id(2), "Default"),
        LibraryCategory(id(3), ""),
      ),
      current.categories,
    )

    assertTrue(id(2) in current)
    assertFalse(id(4) in current)
    assertTrue(CurrentCategories.of(emptyList()).categories.isEmpty())
  }

  @Test
  fun repeatedCategoryIdentityIsAnIdentityViolation() {
    val rows =
      listOf(
        CategoryEntity(id = 1, name = "First"),
        CategoryEntity(id = 1, name = "Second"),
      )

    assertEquals(IDENTITY, violationOf { CurrentCategories.of(rows) }.kind)
  }

  @Test
  fun categoryRowChecksPrecedeTheIdentityCheck() {
    val rows =
      listOf(
        CategoryEntity(id = 1, name = "First"),
        CategoryEntity(id = 1, name = "Second"),
        CategoryEntity(id = 0, name = "Malformed"),
      )

    assertEquals(STORAGE_KEY, violationOf { CurrentCategories.of(rows) }.kind)
  }

  @Test
  fun titleWithoutAssociationIsNoMember() {
    assertNull(mapMembership(TITLE_KEY, TITLE, emptyList()))
  }

  @Test
  fun associationsFormTheMembershipOfTheTitle() {
    val membership =
      mapMembership(
        TITLE_KEY,
        TITLE,
        listOf(association(categoryKey = 2), association(categoryKey = 5)),
      )

    assertEquals(TITLE, membership?.titleId)
    assertEquals(setOf(id(2), id(5)), membership?.categoryIds)
  }

  @Test
  fun associationViolationsMapToTheirKinds() {
    val cases =
      listOf(
        listOf(association(titleKey = 0)) to STORAGE_KEY,
        listOf(association(categoryKey = -1)) to STORAGE_KEY,
        listOf(association(titleKey = OTHER_TITLE_KEY)) to OWNERSHIP,
        listOf(association(), association()) to MEMBERSHIP,
      )

    assertEquals(
      cases.map { (_, kind) -> kind },
      cases.map { (rows, _) -> kindOf(rows) },
    )
  }

  @Test
  fun associationChecksRunInOrder() {
    val repeated = listOf(association(), association())

    val foreign = association(titleKey = OTHER_TITLE_KEY)

    val malformed = association(categoryKey = 0)

    assertEquals(STORAGE_KEY, kindOf(repeated + foreign + malformed))
    assertEquals(OWNERSHIP, kindOf(repeated + foreign))
  }

  @Test
  fun violationMessagesCarryNoStoredValue() {
    val messages =
      listOf(
          violationOf {
            CurrentCategories.of(
              listOf(
                CategoryEntity(id = 4_242, name = "name-category"),
                CategoryEntity(id = 4_242, name = "name-category"),
              )
            )
          },
          violationOf {
            mapMembership(
              TITLE_KEY,
              TITLE,
              listOf(association(titleKey = OTHER_TITLE_KEY)),
            )
          },
          violationOf {
            mapMembership(
              TITLE_KEY,
              TITLE,
              listOf(association(), association()),
            )
          },
        )
        .map { violation -> violation.message.orEmpty() }

    assertTrue(
      messages.none { message ->
        listOf("4242", "name-category", "$TITLE_KEY", "$OTHER_TITLE_KEY")
          .any(message::contains)
      },
      messages.joinToString(),
    )
  }

  private fun kindOf(
    rows: List<TitleCategoryEntity>
  ): StoredStateViolationKind {
    val violation = violationOf { mapMembership(TITLE_KEY, TITLE, rows) }

    return violation.kind
  }

  private fun violationOf(read: () -> Unit): StoredStateViolationException =
    assertThrows<StoredStateViolationException> { read() }

  private fun association(
    titleKey: Long = TITLE_KEY,
    categoryKey: Long = 7,
  ): TitleCategoryEntity =
    TitleCategoryEntity(titleStorageId = titleKey, categoryId = categoryKey)

  private fun id(key: Long): CategoryId = checkNotNull(CategoryId.ofOrNull(key))

  private companion object {
    const val TITLE_KEY = 31L
    const val OTHER_TITLE_KEY = 53L

    val TITLE =
      checkNotNull(
        TitleId.ofOrNull(Uuid.parse("0192a6f0-7c3e-7d4a-9f12-3456789abcde"))
      )
  }
}
