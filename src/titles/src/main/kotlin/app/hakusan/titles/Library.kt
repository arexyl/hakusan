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

/** A caller-selected, nonempty initial category identity set. */
@ConsistentCopyVisibility
data class LibraryCategorySelection private constructor(
  val categoryIds: Set<CategoryId>,
) {
  companion object {
    fun of(categoryIds: Iterable<CategoryId>): LibraryCategorySelection {
      val ownedIds = categoryIds.toOwnedSet()
      require(ownedIds.isNotEmpty()) {
        "An explicit category selection must not be empty."
      }
      return LibraryCategorySelection(ownedIds)
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

  /** The supplied application title identity is not known locally. */
  data object TitleNotFound : LibraryAddResult
}

/** Expected outcome of adding a known title with explicit categories. */
sealed interface ExplicitLibraryAddResult {
  /** Includes both a new membership and an idempotent existing membership. */
  @ConsistentCopyVisibility
  data class Success internal constructor(
    val membership: LibraryMembership,
  ) : ExplicitLibraryAddResult

  /** The operation was rejected without changing membership. */
  @ConsistentCopyVisibility
  data class Failure internal constructor(
    val error: ExplicitLibraryAddFailure,
  ) : ExplicitLibraryAddResult
}

/** Rejections specific to an explicit initial category selection. */
sealed interface ExplicitLibraryAddFailure {
  /** The supplied application title identity is not known locally. */
  data object TitleNotFound : ExplicitLibraryAddFailure

  /** At least one explicitly selected category no longer exists. */
  @ConsistentCopyVisibility
  data class CategoriesNotFound private constructor(
    val categoryIds: Set<CategoryId>,
  ) : ExplicitLibraryAddFailure {
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

/** Availability of the one retained Library resume position. */
enum class LibraryResumeAvailability {
  NONE,
  AVAILABLE,
  TEMPORARILY_UNAVAILABLE,
}

/** Compact reading progress for one title in a Library observation. */
data class LibraryTitleProgressSummary(
  val chapterCount: Int,
  val readChapterCount: Int,
  val resumeAvailability: LibraryResumeAvailability,
) {
  init {
    require(chapterCount >= 0) {
      "Library chapter count must not be negative."
    }
    require(readChapterCount in 0..chapterCount) {
      "Library read chapter count must be within the chapter count."
    }
    require(
      resumeAvailability != LibraryResumeAvailability.AVAILABLE ||
        (chapterCount > 0 && readChapterCount < chapterCount),
    ) {
      "An available Library resume requires one unread canonical chapter."
    }
  }
}

/**
 * One coherent snapshot of all stored categories and their Library titles.
 *
 * The maps and sets are semantically unordered. Each title and its compact
 * progress occur once in [titlesById], while any number of shelves may refer to
 * its identity. Empty stored categories remain present in [shelves].
 */
@ConsistentCopyVisibility
data class LibraryState private constructor(
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
    ): LibraryState = LibraryState(
      titlesById = titlesById.toOwnedMap(),
      shelves = shelves.toOwnedSet(),
    )
  }
}
