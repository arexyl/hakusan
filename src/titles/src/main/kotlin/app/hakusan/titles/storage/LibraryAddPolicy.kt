package app.hakusan.titles.storage

import app.hakusan.titles.CategoryId
import app.hakusan.titles.LibraryCategory
import app.hakusan.titles.LibraryCategorySelection

// The pure Add policy of the Library. It decides the Add of a title outside
// the Library from the categories current in the transaction and nothing else.
// A category name never takes part, so Default is neither looked up nor
// recreated by name. The transaction applies the decision; DAOs decide nothing.

/** The decision of an automatic Add for a title outside the Library. */
internal sealed interface AutomaticAddDecision {
  /** No category exists: create Default and assign the title to it. */
  data object CreateDefault : AutomaticAddDecision

  /** One category exists: assign the title to [categoryId]. */
  data class Assign(val categoryId: CategoryId) : AutomaticAddDecision

  /**
   * Several categories exist: change nothing and return [categories] for an
   * explicit selection.
   */
  data class SelectionRequired(val categories: Set<LibraryCategory>) :
    AutomaticAddDecision
}

/** The decision of an explicit Add for a title outside the Library. */
internal sealed interface ExplicitAddDecision {
  /** Every selected category exists: assign the title to [categoryIds]. */
  data class Assign(val categoryIds: Set<CategoryId>) : ExplicitAddDecision

  /**
   * The selected [categoryIds] no longer exist: reject the whole Add and change
   * nothing.
   */
  data class CategoriesNotFound(val categoryIds: Set<CategoryId>) :
    ExplicitAddDecision
}

/** Decides the automatic Add from the current [categories]. */
internal fun decideAutomaticAdd(
  categories: CurrentCategories
): AutomaticAddDecision {
  val current = categories.categories

  return when (current.size) {
    0 -> AutomaticAddDecision.CreateDefault
    1 -> AutomaticAddDecision.Assign(current.single().id)
    else -> AutomaticAddDecision.SelectionRequired(current)
  }
}

/** Decides the explicit Add of [selection] against the current [categories]. */
internal fun decideExplicitAdd(
  categories: CurrentCategories,
  selection: LibraryCategorySelection,
): ExplicitAddDecision {
  val selected = selection.categoryIds

  val missing = selected.filterNotTo(LinkedHashSet()) { id -> id in categories }

  return if (missing.isEmpty()) {
    ExplicitAddDecision.Assign(selected)
  } else {
    ExplicitAddDecision.CategoriesNotFound(missing)
  }
}
