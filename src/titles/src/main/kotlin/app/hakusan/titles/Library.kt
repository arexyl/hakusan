package app.hakusan.titles

import java.util.Collections

/**
 * One Library category: its identity and its current name.
 *
 * The name is mutable presentation metadata. It is neither unique nor an
 * identity, so two categories may share a name. Only the read boundary of
 * `:titles` creates a category, from a validated stored row.
 */
@ConsistentCopyVisibility
public data class LibraryCategory
internal constructor(
  /** The identity of the category. */
  public val id: CategoryId,
  /** The current name of the category. */
  public val name: String,
)

/**
 * A nonempty set of categories that a caller selected for an explicit Add.
 *
 * The set is owned and carries no order. A repeated identity counts once.
 */
@ConsistentCopyVisibility
public data class LibraryCategorySelection
private constructor(
  /** The selected category identities. */
  public val categoryIds: Set<CategoryId>
) {
  public companion object {
    /**
     * Returns the selection of [categoryIds].
     *
     * @throws IllegalArgumentException if [categoryIds] is empty.
     */
    public fun of(categoryIds: Iterable<CategoryId>): LibraryCategorySelection {
      val owned = categoryIds.toOwnedSet()

      require(owned.isNotEmpty()) {
        "A category selection must not be empty."
      }

      return LibraryCategorySelection(owned)
    }
  }
}

/**
 * The Library membership of one title: every category it belongs to.
 *
 * A Library title belongs to at least one category, so the set is never empty.
 * Each category counts the title once, and several categories share one title
 * and one reading-progress record. The set is owned and carries no order.
 */
@ConsistentCopyVisibility
public data class LibraryMembership
private constructor(
  /** The identity of the title. */
  public val titleId: TitleId,
  /** The categories of the title. */
  public val categoryIds: Set<CategoryId>,
) {
  internal companion object {
    /**
     * Returns the membership of [titleId] in [categoryIds].
     *
     * @throws IllegalArgumentException if [categoryIds] is empty.
     */
    fun of(
      titleId: TitleId,
      categoryIds: Iterable<CategoryId>,
    ): LibraryMembership {
      val owned = categoryIds.toOwnedSet()

      require(owned.isNotEmpty()) {
        "A Library membership requires at least one category."
      }

      return LibraryMembership(titleId, owned)
    }
  }
}

/**
 * The outcome of an automatic Add.
 *
 * Its cases are disjoint from those of [ExplicitLibraryAddResult], so neither
 * Add form returns an outcome of the other.
 */
public sealed interface LibraryAddResult {
  /**
   * The title is in the Library with [membership].
   *
   * For a title that was already a member, [membership] is its unchanged
   * current membership.
   */
  @ConsistentCopyVisibility
  public data class Success
  internal constructor(
    /** The membership of the title after the Add. */
    public val membership: LibraryMembership
  ) : LibraryAddResult

  /**
   * Several categories exist, so the Add needs an explicit nonempty selection
   * from [categories]. Nothing changed.
   */
  @ConsistentCopyVisibility
  public data class CategorySelectionRequired
  private constructor(
    /**
     * The categories current in the transaction of the Add, at least two, each
     * identity once. The set carries no order.
     */
    public val categories: Set<LibraryCategory>
  ) : LibraryAddResult {
    internal companion object {
      /**
       * Returns the outcome for [categories], whose identities the read
       * boundary already found unique.
       *
       * @throws IllegalArgumentException if [categories] has fewer than two
       *   categories.
       */
      fun of(categories: Iterable<LibraryCategory>): CategorySelectionRequired {
        val owned = categories.toOwnedSet()

        require(owned.size > 1) {
          "A category selection is required only among several categories."
        }

        return CategorySelectionRequired(owned)
      }
    }
  }

  /** No stored title has the identity. Nothing changed. */
  public data object TitleNotFound : LibraryAddResult
}

/**
 * The outcome of an explicit Add.
 *
 * Its cases are disjoint from those of [LibraryAddResult], so neither Add form
 * returns an outcome of the other.
 */
public sealed interface ExplicitLibraryAddResult {
  /**
   * The title is in the Library with [membership].
   *
   * For a title that was already a member, [membership] is its unchanged
   * current membership, whatever the selection named.
   */
  @ConsistentCopyVisibility
  public data class Success
  internal constructor(
    /** The membership of the title after the Add. */
    public val membership: LibraryMembership
  ) : ExplicitLibraryAddResult

  /** No stored title has the identity. Nothing changed. */
  public data object TitleNotFound : ExplicitLibraryAddResult

  /**
   * Selected categories no longer exist, so the whole Add was rejected and
   * nothing changed.
   */
  @ConsistentCopyVisibility
  public data class CategoriesNotFound
  private constructor(
    /** The selected identities that no current category has, at least one. */
    public val categoryIds: Set<CategoryId>
  ) : ExplicitLibraryAddResult {
    internal companion object {
      /**
       * Returns the outcome for the missing [categoryIds].
       *
       * @throws IllegalArgumentException if [categoryIds] is empty.
       */
      fun of(categoryIds: Iterable<CategoryId>): CategoriesNotFound {
        val owned = categoryIds.toOwnedSet()

        require(owned.isNotEmpty()) {
          "A rejected selection names at least one missing category."
        }

        return CategoriesNotFound(owned)
      }
    }
  }
}

/** Returns an unmodifiable copy of these elements, each element once. */
private fun <T> Iterable<T>.toOwnedSet(): Set<T> =
  Collections.unmodifiableSet(toCollection(LinkedHashSet()))
