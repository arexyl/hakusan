package app.hakusan.titles

import java.util.Collections

/**
 * One coherent observation of the Library, read in one statement.
 *
 * The snapshot is normalized: each Library title appears once in [titles],
 * keyed by its identity, and each shelf references its titles by identity.
 * Every stored category has one shelf, including a category without titles. The
 * snapshot carries no product order. Its map and sets are unordered, and their
 * iteration order has no meaning; presentation applies an explicit order. The
 * snapshot is owned: later changes to storage never change it.
 */
@ConsistentCopyVisibility
public data class LibrarySnapshot
private constructor(
  /** Every Library title, keyed by its identity. */
  public val titles: Map<TitleId, LibraryTitle>,
  /** One shelf per stored category. */
  public val shelves: Set<LibraryShelf>,
) {
  internal companion object {
    /**
     * Returns the snapshot of validated [titles] and [shelves]. The read
     * boundary and the test fixtures of `:titles` are the only callers, and
     * each establishes every invariant: one shelf per category, and the titles
     * of the shelves are exactly the keys of [titles].
     *
     * The snapshot takes ownership of both collections and wraps them without a
     * copy, so the caller must neither keep nor change them.
     */
    fun create(
      titles: Map<TitleId, LibraryTitle>,
      shelves: Set<LibraryShelf>,
    ): LibrarySnapshot =
      LibrarySnapshot(
        titles = Collections.unmodifiableMap(titles),
        shelves = Collections.unmodifiableSet(shelves),
      )
  }
}

/**
 * The shelf of one category: the category and the identities of its titles.
 *
 * Membership defines the shelf, so its title count is the size of [titleIds]. A
 * title in several categories appears on each of their shelves and still counts
 * once on each.
 */
@ConsistentCopyVisibility
public data class LibraryShelf
private constructor(
  /** The category of the shelf. */
  public val category: LibraryCategory,
  /** The identities of the titles in the category. The set has no order. */
  public val titleIds: Set<TitleId>,
) {
  internal companion object {
    /**
     * Returns the shelf of [category] with [titleIds], whose associations the
     * read boundary validated or the test fixtures built. The shelf takes
     * ownership of [titleIds] and wraps it without a copy, so the caller must
     * neither keep nor change it.
     */
    fun create(
      category: LibraryCategory,
      titleIds: Set<TitleId>,
    ): LibraryShelf =
      LibraryShelf(category, Collections.unmodifiableSet(titleIds))
  }
}

/**
 * One Library title in a [LibrarySnapshot], with its compact progress.
 *
 * The snapshot keys the title by its identity, so the title does not repeat it.
 * Only the read boundary and the test fixtures of `:titles` create a title. The
 * read boundary creates it from validated stored rows.
 */
@ConsistentCopyVisibility
public data class LibraryTitle
internal constructor(
  /** The exact source alias of the title. */
  public val alias: SourceTitleAlias,
  /** The display name of the newest accepted details. */
  public val displayName: String,
  /** The description of the newest accepted details, or null without one. */
  public val description: String?,
  /** The compact reading progress of the title. */
  public val progress: CompactProgress,
)

/**
 * The compact reading progress of one Library title.
 *
 * Both counts cover the current canonical chapters only, the chapters of the
 * newest accepted snapshot. A read chapter that the newest snapshot omits
 * counts in neither. The read count never exceeds the chapter count. Only the
 * read boundary and the test fixtures of `:titles` create a value. The read
 * boundary validates both counts and the resume availability first.
 */
@ConsistentCopyVisibility
public data class CompactProgress
internal constructor(
  /** The number of current canonical chapters. */
  public val chapterCount: Int,
  /** The number of current canonical chapters that are read. */
  public val readChapterCount: Int,
  /** Whether the latest resume position of the title can be resumed. */
  public val resumeAvailability: ResumeAvailability,
)

/** The availability of the latest resume position of a title. */
public enum class ResumeAvailability {
  /** The title has no resume position. */
  NONE,

  /** The position is in a chapter of the current canonical order. */
  AVAILABLE,

  /**
   * The position is in a known chapter that the newest accepted snapshot omits.
   * The position is kept, and it becomes available again when its chapter
   * returns.
   */
  TEMPORARILY_UNAVAILABLE,
}
