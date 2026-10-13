package app.hakusan.titles.storage

import app.hakusan.titles.CategoryId
import app.hakusan.titles.LibraryCategory
import app.hakusan.titles.LibraryMembership
import app.hakusan.titles.StoredStateViolationException
import app.hakusan.titles.StoredStateViolationKind.IDENTITY
import app.hakusan.titles.StoredStateViolationKind.MEMBERSHIP
import app.hakusan.titles.StoredStateViolationKind.OWNERSHIP
import app.hakusan.titles.TitleId
import java.util.Collections

/**
 * The validated categories current in one transaction: every stored category,
 * each identity once.
 */
internal class CurrentCategories
private constructor(
  /** The categories. The set carries no order. */
  val categories: Set<LibraryCategory>,
  private val ids: Set<CategoryId>,
) {
  /** Returns whether a current category has the identity [id]. */
  operator fun contains(id: CategoryId): Boolean = id in ids

  companion object {
    /**
     * Validates [rows], every stored category.
     *
     * The checks run in order: the row-local checks of every row, then unique
     * identities. A later malformed row therefore never hides an earlier
     * violation.
     *
     * @throws StoredStateViolationException if a row or the set of rows
     *   violates an invariant.
     */
    fun of(rows: List<CategoryEntity>): CurrentCategories {
      val stored = rows.map(StoredCategory::of)

      val ids = stored.mapTo(HashSet(), StoredCategory::id)

      if (ids.size != stored.size) {
        violated(IDENTITY, "Stored categories rows repeat an identity.")
      }

      val categories =
        stored.mapTo(LinkedHashSet()) { category ->
          LibraryCategory(category.id, category.name)
        }

      return CurrentCategories(
        categories = Collections.unmodifiableSet(categories),
        ids = ids,
      )
    }
  }
}

/**
 * Validates [rows], the category associations of the title [titleStorageId]
 * with the identity [titleId], and returns its membership, or null when it has
 * no association.
 *
 * The checks run in order: the row-local checks of every row, then ownership,
 * then one association per category. A later malformed row therefore never
 * hides an earlier violation. The foreign key keeps each named category
 * present, and Add reads no category of a member, so this read does not check
 * that the categories exist.
 *
 * @throws StoredStateViolationException if a row or the set of rows violates an
 *   invariant.
 */
internal fun mapMembership(
  titleStorageId: Long,
  titleId: TitleId,
  rows: List<TitleCategoryEntity>,
): LibraryMembership? {
  val associations = rows.map(StoredTitleCategory::of)

  if (
    associations.any { association ->
      association.titleStorageId != titleStorageId
    }
  ) {
    violated(
      OWNERSHIP,
      "A stored title_categories row belongs to another title.",
    )
  }

  val categoryIds =
    associations.mapTo(LinkedHashSet(), StoredTitleCategory::categoryId)

  if (categoryIds.size != associations.size) {
    violated(MEMBERSHIP, "Stored title_categories rows repeat a category.")
  }

  return if (categoryIds.isEmpty()) {
    null
  } else {
    LibraryMembership.of(titleId, categoryIds)
  }
}
