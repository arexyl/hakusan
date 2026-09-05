package app.hakusan.titles

/** Database-local identity of one Library category. */
@JvmInline
value class CategoryId(
  val value: Long,
) {
  init {
    require(value > 0) {
      "Category id must be positive."
    }
  }
}

/** Selects how a title receives its initial category associations. */
sealed interface LibraryCategorySelection {
  /** Apply the zero, one, or multiple-category Library Add rules. */
  data object Automatic : LibraryCategorySelection

  /** A caller-selected, nonempty category identity set. */
  @ConsistentCopyVisibility
  data class Explicit private constructor(
    val categoryIds: Set<CategoryId>,
  ) : LibraryCategorySelection {
    companion object {
      fun of(categoryIds: Iterable<CategoryId>): Explicit {
        val ownedIds = categoryIds.toOwnedSet()
        require(ownedIds.isNotEmpty()) {
          "An explicit category selection must not be empty."
        }
        return Explicit(ownedIds)
      }
    }
  }
}

/** One user-defined category. Its mutable name is not its identity. */
@ConsistentCopyVisibility
data class LibraryCategory internal constructor(
  val id: CategoryId,
  val name: String,
)

/** The complete category association set for one Library title. */
@ConsistentCopyVisibility
data class LibraryMembership private constructor(
  val titleId: TitleId,
  val categoryIds: Set<CategoryId>,
) {
  init {
    require(categoryIds.isNotEmpty()) {
      "A Library membership must have at least one category."
    }
  }

  internal companion object {
    fun create(
      titleId: TitleId,
      categoryIds: Iterable<CategoryId>,
    ): LibraryMembership = LibraryMembership(
      titleId = titleId,
      categoryIds = categoryIds.toOwnedSet(),
    )
  }
}

/** Expected outcome of adding a known title to the Library. */
sealed interface LibraryAddResult {
  /**
   * The title is in the Library with this membership.
   *
   * Repeating Add for an existing member returns the same form without
   * changing its associations.
   */
  @ConsistentCopyVisibility
  data class Success internal constructor(
    val membership: LibraryMembership,
  ) : LibraryAddResult

  /**
   * Multiple categories exist and the caller must choose one or more.
   * No presentation mechanism is implied.
   */
  @ConsistentCopyVisibility
  data class CategorySelectionRequired internal constructor(
    val categories: Set<LibraryCategory>,
  ) : LibraryAddResult

  /** The operation was rejected without changing membership. */
  @ConsistentCopyVisibility
  data class Failure internal constructor(
    val error: LibraryAddFailure,
  ) : LibraryAddResult
}

/** Caller-actionable reasons why Library Add did not change membership. */
sealed interface LibraryAddFailure {
  /** The supplied application title identity is not known locally. */
  data object TitleNotFound : LibraryAddFailure

  /** At least one explicitly selected category no longer exists. */
  @ConsistentCopyVisibility
  data class CategoriesNotFound private constructor(
    val categoryIds: Set<CategoryId>,
  ) : LibraryAddFailure {
    init {
      require(categoryIds.isNotEmpty()) {
        "At least one missing category id is required."
      }
    }

    internal companion object {
      fun create(
        categoryIds: Iterable<CategoryId>,
      ): CategoriesNotFound = CategoriesNotFound(categoryIds.toOwnedSet())
    }
  }
}

/** One category shelf referencing shared title state by identity. */
@ConsistentCopyVisibility
data class LibraryShelf private constructor(
  val category: LibraryCategory,
  /** Semantically unordered; presentation sorting belongs to its consumer. */
  val titleIds: Set<TitleId>,
) {
  val titleCount: Int
    get() = titleIds.size

  internal companion object {
    fun create(
      category: LibraryCategory,
      titleIds: Iterable<TitleId>,
    ): LibraryShelf = LibraryShelf(
      category = category,
      titleIds = titleIds.toOwnedSet(),
    )
  }
}

/**
 * A coherent snapshot of all stored categories and their Library members.
 *
 * The maps and sets are semantically unordered. Each Library title occurs once
 * in [titlesById], while any number of shelves may reference its identity.
 * Empty stored categories remain present in [shelves].
 */
@ConsistentCopyVisibility
data class LibraryShelfState private constructor(
  val titlesById: Map<TitleId, LibraryTitle>,
  val shelves: Set<LibraryShelf>,
) {
  init {
    require(titlesById.all { (id, title) -> id == title.id }) {
      "Each title map key must match its title identity."
    }

    val categoryIds = HashSet<CategoryId>(shelves.size)
    val referencedTitleIds = HashSet<TitleId>(titlesById.size)
    shelves.forEach { shelf ->
      categoryIds += shelf.category.id
      referencedTitleIds.addAll(shelf.titleIds)
    }
    require(categoryIds.size == shelves.size) {
      "Each category must have exactly one shelf."
    }

    require(referencedTitleIds == titlesById.keys) {
      "Shelf membership and shared title state must agree."
    }
  }

  internal companion object {
    fun create(
      titlesById: Map<TitleId, LibraryTitle>,
      shelves: Iterable<LibraryShelf>,
    ): LibraryShelfState = LibraryShelfState(
      titlesById = titlesById.toOwnedMap(),
      shelves = shelves.toOwnedSet(),
    )
  }
}
