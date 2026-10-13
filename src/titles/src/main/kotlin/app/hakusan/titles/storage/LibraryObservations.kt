package app.hakusan.titles.storage

import androidx.room3.ColumnInfo
import androidx.room3.Embedded
import app.hakusan.titles.CategoryId
import app.hakusan.titles.CompactProgress
import app.hakusan.titles.LibraryCategory
import app.hakusan.titles.LibraryShelf
import app.hakusan.titles.LibrarySnapshot
import app.hakusan.titles.LibraryTitle
import app.hakusan.titles.ResumeAvailability
import app.hakusan.titles.StoredStateViolationException
import app.hakusan.titles.StoredStateViolationKind.ALIAS
import app.hakusan.titles.StoredStateViolationKind.CANONICAL_ORDER
import app.hakusan.titles.StoredStateViolationKind.IDENTITY
import app.hakusan.titles.StoredStateViolationKind.MEMBERSHIP
import app.hakusan.titles.StoredStateViolationKind.OWNERSHIP
import app.hakusan.titles.StoredStateViolationKind.RESUME_STATE
import app.hakusan.titles.TitleId
import java.util.Collections
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

// The two Library observations: their queries, their row mappers, and their
// pipelines. Room reruns a query after every committed write to a table that
// it reads, so the Library query reruns after each resume-position write as
// well. That cost is accepted and not optimized here; equal results are
// suppressed only after mapping.

/**
 * The Library query: one row per category association and one row per category
 * without associations.
 *
 * The progress of a title aggregates only its current canonical chapters, and
 * only for Library members. Membership is the existence of an association,
 * tested as a set, so a title in several categories never multiplies its
 * chapter or read counts. Each association row carries the title, its counts,
 * the bounds of its canonical indexes, and its persistent resume row with the
 * chapter that the position names within the same title. The rows carry raw
 * stored values, and the query defines no order.
 */
internal const val LIBRARY_QUERY: String =
  """
  WITH member_progress AS (
    SELECT chapters.title_storage_id AS title_storage_id,
           COUNT(*) AS chapter_count,
           COUNT(read_chapters.chapter_storage_id) AS read_chapter_count,
           COUNT(DISTINCT chapters.canonical_index)
             AS distinct_canonical_index_count,
           MIN(chapters.canonical_index) AS first_canonical_index,
           MAX(chapters.canonical_index) AS last_canonical_index
    FROM chapters
    LEFT JOIN read_chapters
      ON read_chapters.chapter_storage_id = chapters.storage_id
    WHERE chapters.canonical_index IS NOT NULL
      AND chapters.title_storage_id IN (
        SELECT title_storage_id FROM title_categories
      )
    GROUP BY chapters.title_storage_id
  )
  SELECT categories.id AS category_id,
         categories.name AS category_name,
         title_categories.title_storage_id AS association_title_storage_id,
         title_categories.category_id AS association_category_id,
         titles.storage_id AS title_storage_id,
         titles.id AS title_id,
         titles.source_identity AS title_source_identity,
         titles.source_title_key AS title_source_title_key,
         titles.display_name AS title_display_name,
         titles.description AS title_description,
         COALESCE(member_progress.chapter_count, 0) AS chapter_count,
         COALESCE(member_progress.read_chapter_count, 0)
           AS read_chapter_count,
         COALESCE(member_progress.distinct_canonical_index_count, 0)
           AS distinct_canonical_index_count,
         member_progress.first_canonical_index AS first_canonical_index,
         member_progress.last_canonical_index AS last_canonical_index,
         library_resume_positions.title_storage_id
           AS position_title_storage_id,
         library_resume_positions.chapter_storage_id
           AS position_chapter_storage_id,
         library_resume_positions.unit_kind AS position_unit_kind,
         library_resume_positions.unit_index AS position_unit_index,
         library_resume_positions.updated_at AS position_updated_at,
         resume_chapters.storage_id AS resume_chapter_storage_id,
         resume_chapters.canonical_index AS resume_chapter_canonical_index,
         resume_reads.chapter_storage_id AS resume_chapter_read_storage_id
  FROM categories
  LEFT JOIN title_categories
    ON title_categories.category_id = categories.id
  LEFT JOIN titles
    ON titles.storage_id = title_categories.title_storage_id
  LEFT JOIN member_progress
    ON member_progress.title_storage_id = titles.storage_id
  LEFT JOIN library_resume_positions
    ON library_resume_positions.title_storage_id = titles.storage_id
  LEFT JOIN chapters AS resume_chapters
    ON resume_chapters.storage_id =
       library_resume_positions.chapter_storage_id
   AND resume_chapters.title_storage_id =
       library_resume_positions.title_storage_id
  LEFT JOIN read_chapters AS resume_reads
    ON resume_reads.chapter_storage_id = resume_chapters.storage_id
  """

/**
 * The membership query: one row per category association, with the title that
 * it names. It reads only titles and their category associations, so chapter,
 * read, and resume writes never rerun it. The rows carry raw stored values, and
 * the query defines no order.
 */
internal const val LIBRARY_TITLES_QUERY: String =
  """
  SELECT title_categories.title_storage_id AS association_title_storage_id,
         title_categories.category_id AS association_category_id,
         titles.storage_id AS title_storage_id,
         titles.id AS title_id,
         titles.source_identity AS title_source_identity,
         titles.source_title_key AS title_source_title_key,
         titles.display_name AS title_display_name,
         titles.description AS title_description
  FROM title_categories
  LEFT JOIN titles
    ON titles.storage_id = title_categories.title_storage_id
  """

/**
 * One row of [LIBRARY_TITLES_QUERY], with raw stored values: a category
 * association and the title that it names, or null when no title has its key.
 */
internal data class LibraryTitleRow(
  @Embedded(prefix = "association_") val association: TitleCategoryEntity,
  @Embedded(prefix = "title_") val title: TitleEntity?,
)

/**
 * One row of [LIBRARY_QUERY], with raw stored values.
 *
 * A row without [association] stands for a category without titles. Room
 * materializes each embedded row as null exactly when all of its columns are
 * null, and it never decodes a value, so materialization never fails for stored
 * state.
 */
internal data class LibraryRow(
  @Embedded(prefix = "category_") val category: CategoryEntity,
  @Embedded(prefix = "association_") val association: TitleCategoryEntity?,
  @Embedded(prefix = "title_") val title: TitleEntity?,
  /** The number of current canonical chapters of the title. */
  @ColumnInfo(name = "chapter_count") val chapterCount: Long,
  /** The number of current canonical chapters of the title that are read. */
  @ColumnInfo(name = "read_chapter_count") val readChapterCount: Long,
  /** The number of distinct canonical indexes of the title. */
  @ColumnInfo(name = "distinct_canonical_index_count")
  val distinctCanonicalIndexCount: Long,
  /** The lowest canonical index of the title, or null without one. */
  @ColumnInfo(name = "first_canonical_index") val firstCanonicalIndex: Long?,
  /** The highest canonical index of the title, or null without one. */
  @ColumnInfo(name = "last_canonical_index") val lastCanonicalIndex: Long?,
  @Embedded(prefix = "position_") val position: LibraryResumePositionEntity?,
  /**
   * The storage key of the chapter that the position names, or null when no
   * chapter of the title has it.
   */
  @ColumnInfo(name = "resume_chapter_storage_id")
  val resumeChapterStorageId: Long?,
  /** The canonical index of the chapter that the position names. */
  @ColumnInfo(name = "resume_chapter_canonical_index")
  val resumeChapterCanonicalIndex: Int?,
  /**
   * The read row of the chapter that the position names, or null when that
   * chapter is not read.
   */
  @ColumnInfo(name = "resume_chapter_read_storage_id")
  val resumeChapterReadStorageId: Long?,
)

/**
 * Maps each result of the Library query to its snapshot on [worker] and
 * suppresses equal consecutive snapshots.
 *
 * Collecting the upstream, mapping, and comparing all run on [worker], so a
 * collector on another dispatcher, such as the main thread, never builds a
 * snapshot and receives only finished ones. A collector that already runs on
 * [worker] shares its dispatcher.
 */
internal fun Flow<List<LibraryRow>>.toLibrarySnapshots(
  worker: CoroutineDispatcher
): Flow<LibrarySnapshot> =
  map(::mapLibrarySnapshot).distinctUntilChanged().flowOn(worker)

/**
 * Maps each result of the membership query to its set of title identities on
 * [worker] and suppresses equal consecutive sets.
 *
 * Collecting the upstream, mapping, and comparing all run on [worker], so a
 * collector on another dispatcher never builds a set and receives only finished
 * ones. A collector that already runs on [worker] shares its dispatcher.
 */
internal fun Flow<List<LibraryTitleRow>>.toLibraryTitleIds(
  worker: CoroutineDispatcher
): Flow<Set<TitleId>> =
  map(::mapLibraryTitleIds).distinctUntilChanged().flowOn(worker)

/**
 * Validates [rows], one result of [LIBRARY_QUERY], and returns its snapshot.
 *
 * The checks run in order: the column checks of every row, then the relations
 * inside every row, then the consistency across rows. A later malformed row
 * therefore never hides an earlier violation of the same phase.
 * - Columns: the checks of each stored row, and counts within the [Int] range.
 * - Inside a row: a category without titles carries no title state, an
 *   association names an existing title and the category of its row, the
 *   canonical indexes of the title are dense from zero, the read count stays
 *   within the chapter count, and a resume position belongs to the title, names
 *   a chapter of it, is not on a read chapter, and is available only while an
 *   unread canonical chapter exists.
 * - Across rows: each category keeps one name, each title keeps one storage
 *   key, one alias, one set of details, and one compact progress, each category
 *   lists each title once, and a category without titles has one such row and
 *   no titles.
 *
 * @throws StoredStateViolationException if a row or the set of rows violates an
 *   invariant.
 */
internal fun mapLibrarySnapshot(rows: List<LibraryRow>): LibrarySnapshot {
  val decoded = rows.map(::decode)

  val entries = decoded.map { row -> row.toEntry() }

  val assembly = SnapshotAssembly()

  entries.forEach(assembly::add)

  return assembly.build()
}

/**
 * Validates [rows], one result of [LIBRARY_TITLES_QUERY], and returns the
 * identities of the Library titles.
 *
 * The checks run in order, so a later malformed row never hides an earlier
 * violation of the same phase:
 * - Columns: the checks of each stored association, then of its title.
 * - Inside a row: the association names an existing title, and the joined title
 *   has the storage key that the association names.
 * - Across rows: each title identity keeps one storage key, and each title is
 *   associated with each category once.
 *
 * The query does not read categories. Their foreign key keeps each named
 * category present, and the Library observation reports the categories.
 *
 * @throws StoredStateViolationException if a row or the set of rows violates an
 *   invariant.
 */
internal fun mapLibraryTitleIds(rows: List<LibraryTitleRow>): Set<TitleId> {
  val decoded = rows.map { row ->
    val association = StoredTitleCategory.of(row.association)

    association to row.title?.let(StoredTitle::of)
  }

  val members = decoded.map { (association, title) ->
    association to memberTitle(association, title)
  }

  val storageIds = HashMap<TitleId, Long>()

  val associations = HashSet<StoredTitleCategory>()

  members.forEach { (association, title) ->
    val storageId = storageIds.putIfAbsent(title.id, title.storageId)

    if (storageId != null && storageId != title.storageId) {
      violated(IDENTITY, "Several stored titles rows share one identity.")
    }

    if (!associations.add(association)) {
      violated(MEMBERSHIP, "Stored title_categories rows repeat a title.")
    }
  }

  return Collections.unmodifiableSet(storageIds.keys)
}

/**
 * Returns [title], the title that [association] names, after checking that it
 * exists and that the association belongs to it.
 */
private fun memberTitle(
  association: StoredTitleCategory,
  title: StoredTitle?,
): StoredTitle {
  if (title == null) {
    violated(MEMBERSHIP, "A stored title_categories row names no title.")
  }

  if (association.titleStorageId != title.storageId) {
    violated(
      OWNERSHIP,
      "A stored title_categories row belongs to another title.",
    )
  }

  return title
}

/** A Library row whose columns passed their checks. */
private class DecodedRow(
  val category: StoredCategory,
  val association: StoredTitleCategory?,
  val title: StoredTitle?,
  val chapterCount: Int,
  val readChapterCount: Int,
  val distinctCanonicalIndexCount: Long,
  val firstCanonicalIndex: Long?,
  val lastCanonicalIndex: Long?,
  val position: StoredResumePosition?,
  val resumeChapterStorageId: Long?,
  val resumeChapterCanonicalIndex: Int?,
  val resumeChapterReadStorageId: Long?,
)

/** One validated Library row: a category with one title, or without any. */
private sealed interface ShelfEntry {
  /** The category of the row. */
  val category: StoredCategory
}

/** The row of a category without titles. */
private class EmptyShelf(override val category: StoredCategory) : ShelfEntry

/**
 * The row of the title [titleId], with the storage key [storageId], in
 * [category].
 */
private class ShelfMember(
  override val category: StoredCategory,
  val storageId: Long,
  val titleId: TitleId,
  val title: LibraryTitle,
) : ShelfEntry

/** Runs the column checks of [row], in column order. */
private fun decode(row: LibraryRow): DecodedRow {
  val category = StoredCategory.of(row.category)

  val association = row.association?.let(StoredTitleCategory::of)

  val title = row.title?.let(StoredTitle::of)

  val chapterCount = libraryCount(row.chapterCount, "chapter count")

  val readChapterCount =
    libraryCount(row.readChapterCount, "read chapter count")

  val position = row.position?.let(StoredResumePosition::of)

  val canonicalIndex = row.resumeChapterCanonicalIndex

  if (canonicalIndex != null && canonicalIndex < 0) {
    violated(CANONICAL_ORDER, "A stored chapters.canonical_index is negative.")
  }

  return DecodedRow(
    category = category,
    association = association,
    title = title,
    chapterCount = chapterCount,
    readChapterCount = readChapterCount,
    distinctCanonicalIndexCount = row.distinctCanonicalIndexCount,
    firstCanonicalIndex = row.firstCanonicalIndex,
    lastCanonicalIndex = row.lastCanonicalIndex,
    position = position,
    resumeChapterStorageId = row.resumeChapterStorageId,
    resumeChapterCanonicalIndex = canonicalIndex,
    resumeChapterReadStorageId = row.resumeChapterReadStorageId,
  )
}

/**
 * Returns [count] as an [Int], or raises a violation when it is negative or
 * exceeds the [Int] range.
 */
private fun libraryCount(count: Long, name: String): Int =
  if (count in 0L..Int.MAX_VALUE.toLong()) {
    count.toInt()
  } else {
    violated(MEMBERSHIP, "A Library $name is outside its range.")
  }

/** Runs the checks of the relations inside this row. */
private fun DecodedRow.toEntry(): ShelfEntry {
  val association = association ?: return toEmptyShelf()

  val title = memberTitle(association, title)

  if (association.categoryId != category.id) {
    violated(
      OWNERSHIP,
      "A stored title_categories row belongs to another category.",
    )
  }

  if (!hasDenseCanonicalOrder()) {
    violated(
      CANONICAL_ORDER,
      "Stored canonical indexes of a title are not dense from zero.",
    )
  }

  if (readChapterCount > chapterCount) {
    violated(MEMBERSHIP, "A Library read chapter count exceeds its chapters.")
  }

  val availability = resumeAvailability(title)

  val hasUnreadChapter = readChapterCount < chapterCount

  if (availability == ResumeAvailability.AVAILABLE && !hasUnreadChapter) {
    violated(
      RESUME_STATE,
      "An available resume position has no unread canonical chapter.",
    )
  }

  val progress =
    CompactProgress(
      chapterCount = chapterCount,
      readChapterCount = readChapterCount,
      resumeAvailability = availability,
    )

  val libraryTitle =
    LibraryTitle(
      alias = title.alias,
      displayName = title.displayName,
      description = title.description,
      progress = progress,
    )

  return ShelfMember(category, title.storageId, title.id, libraryTitle)
}

/**
 * Returns whether the canonical indexes of the title in this row run from zero
 * to one below its chapter count, each once.
 */
private fun DecodedRow.hasDenseCanonicalOrder(): Boolean {
  val count = chapterCount.toLong()

  return if (count == 0L) {
    distinctCanonicalIndexCount == 0L &&
      firstCanonicalIndex == null &&
      lastCanonicalIndex == null
  } else {
    distinctCanonicalIndexCount == count &&
      firstCanonicalIndex == 0L &&
      lastCanonicalIndex == count - 1L
  }
}

/** Returns the row of a category without titles, which has no title state. */
private fun DecodedRow.toEmptyShelf(): EmptyShelf {
  val hasTitleState =
    title != null ||
      chapterCount != 0 ||
      readChapterCount != 0 ||
      distinctCanonicalIndexCount != 0L ||
      firstCanonicalIndex != null ||
      lastCanonicalIndex != null ||
      position != null ||
      resumeChapterStorageId != null ||
      resumeChapterCanonicalIndex != null ||
      resumeChapterReadStorageId != null

  if (hasTitleState) {
    violated(MEMBERSHIP, "A stored category without titles has title state.")
  }

  return EmptyShelf(category)
}

/**
 * Validates the resume state of [title] in this row and returns its
 * availability.
 *
 * The checks run in order: title ownership, chapter ownership, then read-state
 * consistency. A chapter without a canonical index makes the position
 * temporarily unavailable.
 */
private fun DecodedRow.resumeAvailability(
  title: StoredTitle
): ResumeAvailability {
  val position = position

  if (position == null) {
    val hasChapter =
      resumeChapterStorageId != null ||
        resumeChapterCanonicalIndex != null ||
        resumeChapterReadStorageId != null

    if (hasChapter) {
      violated(RESUME_STATE, "A stored resume chapter has no resume position.")
    }

    return ResumeAvailability.NONE
  }

  if (position.titleStorageId != title.storageId) {
    violated(OWNERSHIP, "A stored resume position belongs to another title.")
  }

  if (resumeChapterStorageId != position.chapterStorageId) {
    violated(
      OWNERSHIP,
      "A stored resume position names a chapter of another title.",
    )
  }

  if (resumeChapterReadStorageId != null) {
    violated(RESUME_STATE, "A stored resume position is on a read chapter.")
  }

  return if (resumeChapterCanonicalIndex == null) {
    ResumeAvailability.TEMPORARILY_UNAVAILABLE
  } else {
    ResumeAvailability.AVAILABLE
  }
}

/**
 * Checks the consistency across validated rows and accumulates the snapshot.
 *
 * The accumulated collections become the collections of the snapshot without a
 * copy.
 */
private class SnapshotAssembly {
  private val titles = HashMap<TitleId, LibraryTitle>()

  private val storageIds = HashMap<TitleId, Long>()

  private val shelves = HashMap<CategoryId, ShelfAccumulator>()

  /** Checks [entry] against the earlier rows and accumulates it. */
  fun add(entry: ShelfEntry) {
    val shelf = shelfOf(entry.category)

    when (entry) {
      is EmptyShelf -> addEmptyRow(shelf)
      is ShelfMember -> addMember(shelf, entry)
    }
  }

  /** Returns the snapshot. Call it once, after the last row. */
  fun build(): LibrarySnapshot {
    val built =
      shelves.values.mapTo(HashSet(shelves.size)) { shelf ->
        LibraryShelf.create(shelf.category, shelf.titleIds)
      }

    return LibrarySnapshot.create(titles, built)
  }

  /** Returns the shelf of [category], whose name every row must repeat. */
  private fun shelfOf(category: StoredCategory): ShelfAccumulator {
    val shelf =
      shelves.getOrPut(category.id) {
        ShelfAccumulator(LibraryCategory(category.id, category.name))
      }

    if (shelf.category.name != category.name) {
      violated(IDENTITY, "Stored categories rows disagree in a name.")
    }

    return shelf
  }

  private fun addEmptyRow(shelf: ShelfAccumulator) {
    if (shelf.hasEmptyRow) {
      violated(IDENTITY, "Stored categories rows repeat an identity.")
    }

    if (shelf.titleIds.isNotEmpty()) {
      violated(MEMBERSHIP, "A stored category without titles has titles.")
    }

    shelf.hasEmptyRow = true
  }

  private fun addMember(shelf: ShelfAccumulator, member: ShelfMember) {
    addTitle(member)

    if (!shelf.titleIds.add(member.titleId)) {
      violated(MEMBERSHIP, "Stored title_categories rows repeat a title.")
    }

    if (shelf.hasEmptyRow) {
      violated(MEMBERSHIP, "A stored category without titles has titles.")
    }
  }

  /** Accumulates the title of [member], which every row must repeat. */
  private fun addTitle(member: ShelfMember) {
    val id = member.titleId

    val current = titles.putIfAbsent(id, member.title)

    val storageId = storageIds.putIfAbsent(id, member.storageId)

    if (current == null) {
      return
    }

    if (storageId != member.storageId) {
      violated(IDENTITY, "Several stored titles rows share one identity.")
    }

    checkSameTitle(current, member.title)
  }

  private class ShelfAccumulator(val category: LibraryCategory) {
    /** The titles of the shelf. The shelf takes this set without a copy. */
    val titleIds = HashSet<TitleId>()

    /** Whether a row stood for this category without titles. */
    var hasEmptyRow = false
  }
}

/**
 * Raises a violation when the rows of one title yield different values, with
 * the kind of the first field that differs.
 */
private fun checkSameTitle(current: LibraryTitle, other: LibraryTitle) {
  if (current.alias != other.alias) {
    violated(ALIAS, "Stored rows of one title disagree in its alias.")
  }

  val hasSameDetails =
    current.displayName == other.displayName &&
      current.description == other.description

  if (!hasSameDetails) {
    violated(IDENTITY, "Stored rows of one title disagree in its details.")
  }

  val progress = current.progress
  val otherProgress = other.progress

  val hasSameCounts =
    progress.chapterCount == otherProgress.chapterCount &&
      progress.readChapterCount == otherProgress.readChapterCount

  if (!hasSameCounts) {
    violated(MEMBERSHIP, "Stored rows of one title disagree in its counts.")
  }

  if (progress.resumeAvailability != otherProgress.resumeAvailability) {
    violated(RESUME_STATE, "Stored rows of one title disagree in its resume.")
  }
}
