package app.hakusan.titles

/** Returns the Library category [id] with its current [name]. */
public fun libraryCategory(id: CategoryId, name: String): LibraryCategory =
  LibraryCategory(id, name)

/**
 * Returns the Library membership of [titleId] in [categoryIds].
 *
 * @throws IllegalArgumentException if [categoryIds] is empty.
 */
public fun libraryMembership(
  titleId: TitleId,
  categoryIds: Set<CategoryId>,
): LibraryMembership = LibraryMembership.of(titleId, categoryIds)

/** Returns the outcome of an automatic Add that left [membership]. */
public fun libraryAddSuccess(
  membership: LibraryMembership
): LibraryAddResult.Success = LibraryAddResult.Success(membership)

/**
 * Returns the outcome of an automatic Add that needs a selection from
 * [categories].
 *
 * @throws IllegalArgumentException if [categories] has fewer than two
 *   categories or repeats a category identity.
 */
public fun categorySelectionRequired(
  categories: Set<LibraryCategory>
): LibraryAddResult.CategorySelectionRequired {
  val ids = categories.mapTo(HashSet(), LibraryCategory::id)

  require(ids.size == categories.size) {
    "Categories must not repeat an identity."
  }

  return LibraryAddResult.CategorySelectionRequired.of(categories)
}
