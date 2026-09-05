package app.hakusan.titles

/** Pure product decision for one nonmember's initial category assignment. */
internal object LibraryAddPolicy {
  fun resolve(
    categories: List<LibraryCategory>,
    selection: LibraryCategorySelection,
  ): InitialCategoryResolution {
    val categoriesById = categories.associateBy(LibraryCategory::id)
    check(categoriesById.size == categories.size) {
      "Category identities must be unique."
    }

    return when (selection) {
      LibraryCategorySelection.Automatic -> when (categories.size) {
        0 -> InitialCategoryResolution.CreateDefault
        1 -> InitialCategoryResolution.Assign(
          setOf(categories.single().id),
        )

        else -> InitialCategoryResolution.SelectionRequired(
          categories.toOwnedSet(),
        )
      }

      is LibraryCategorySelection.Explicit -> {
        val missingIds = selection.categoryIds - categoriesById.keys
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
