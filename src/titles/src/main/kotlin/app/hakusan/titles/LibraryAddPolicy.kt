package app.hakusan.titles

/** Pure product decision for one nonmember's initial category assignment. */
internal object LibraryAddPolicy {
  fun resolve(
    categories: List<LibraryCategory>,
    selection: LibraryCategorySelection,
  ): InitialCategoryResolution {
    if (selection == LibraryCategorySelection.Automatic) {
      when (categories.size) {
        0 -> return InitialCategoryResolution.CreateDefault
        1 -> return InitialCategoryResolution.Assign(
          setOf(categories.single().id),
        )
      }
    }

    val categoryIds = categories.mapTo(HashSet()) { it.id }
    check(categoryIds.size == categories.size) {
      "Category identities must be unique."
    }

    return when (selection) {
      LibraryCategorySelection.Automatic ->
        InitialCategoryResolution.SelectionRequired(categories.toOwnedSet())

      is LibraryCategorySelection.Explicit -> {
        val missingIds = selection.categoryIds - categoryIds
        if (missingIds.isEmpty()) {
          InitialCategoryResolution.Assign(selection.categoryIds)
        } else {
          InitialCategoryResolution.CategoriesNotFound(
            missingIds.toOwnedSet(),
          )
        }
      }
    }
  }
}

internal sealed interface InitialCategoryResolution {
  data object CreateDefault : InitialCategoryResolution

  data class Assign(
    val categoryIds: Set<CategoryId>,
  ) : InitialCategoryResolution {
    init {
      require(categoryIds.isNotEmpty()) {
        "A resolved category assignment must not be empty."
      }
    }
  }

  data class SelectionRequired(
    val categories: Set<LibraryCategory>,
  ) : InitialCategoryResolution {
    init {
      require(categories.size > 1) {
        "Selection is required only when several categories exist."
      }
    }
  }

  data class CategoriesNotFound(
    val categoryIds: Set<CategoryId>,
  ) : InitialCategoryResolution {
    init {
      require(categoryIds.isNotEmpty()) {
        "At least one missing category id is required."
      }
    }
  }
}
