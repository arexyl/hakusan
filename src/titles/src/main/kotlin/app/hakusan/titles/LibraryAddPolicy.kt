package app.hakusan.titles

/** Pure product decision for one nonmember's initial category assignment. */
internal object LibraryAddPolicy {
  fun resolveAutomatic(
    categories: List<LibraryCategory>,
  ): AutomaticCategoryResolution {
    when (categories.size) {
      0 -> return AutomaticCategoryResolution.CreateDefault
      1 -> return CategoryAssignment(
        setOf(categories.single().id),
      )
    }

    requireUniqueCategoryIds(categories)
    return AutomaticCategoryResolution.SelectionRequired(
      categories.toOwnedSet(),
    )
  }

  fun resolveExplicit(
    categories: List<LibraryCategory>,
    selection: LibraryCategorySelection,
  ): ExplicitCategoryResolution {
    val categoryIds = requireUniqueCategoryIds(categories)
    val missingIds = selection.categoryIds - categoryIds
    return if (missingIds.isEmpty()) {
      CategoryAssignment(selection.categoryIds)
    } else {
      ExplicitCategoryResolution.CategoriesNotFound(missingIds.toOwnedSet())
    }
  }

  private fun requireUniqueCategoryIds(
    categories: List<LibraryCategory>,
  ): Set<CategoryId> =
    categories.mapTo(HashSet()) { it.id }.also { categoryIds ->
      check(categoryIds.size == categories.size) {
        "Category identities must be unique."
      }
    }
}

internal sealed interface AutomaticCategoryResolution {
  data object CreateDefault : AutomaticCategoryResolution

  data class SelectionRequired(
    val categories: Set<LibraryCategory>,
  ) : AutomaticCategoryResolution {
    init {
      require(categories.size > 1) {
        "Selection is required only when several categories exist."
      }
    }
  }
}

internal sealed interface ExplicitCategoryResolution {
  data class CategoriesNotFound(
    val categoryIds: Set<CategoryId>,
  ) : ExplicitCategoryResolution {
    init {
      require(categoryIds.isNotEmpty()) {
        "At least one missing category id is required."
      }
    }
  }
}

internal data class CategoryAssignment(
  val categoryIds: Set<CategoryId>,
) : AutomaticCategoryResolution, ExplicitCategoryResolution {
  init {
    require(categoryIds.isNotEmpty()) {
      "A resolved category assignment must not be empty."
    }
  }
}
