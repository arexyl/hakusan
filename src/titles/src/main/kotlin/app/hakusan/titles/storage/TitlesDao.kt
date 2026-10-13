package app.hakusan.titles.storage

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

/**
 * The narrow persistence primitives of `:titles`.
 *
 * Each primitive is one statement and decides no product question. Policy
 * owners coordinate them inside a transaction, and the read boundary validates
 * every row they return.
 */
@Dao
internal interface TitlesDao {
  /**
   * Returns the title rows with the exact alias. The unique alias index allows
   * one row at most; the read boundary checks it anyway.
   */
  @Query(
    "SELECT * FROM titles WHERE source_identity = :sourceIdentity " +
      "AND source_title_key = :sourceTitleKey"
  )
  suspend fun findTitlesByAlias(
    sourceIdentity: String,
    sourceTitleKey: String,
  ): List<TitleEntity>

  /**
   * Returns the title rows with the identity [id]. The unique identity index
   * allows one row at most; the read boundary checks it anyway.
   */
  @Query("SELECT * FROM titles WHERE id = :id")
  suspend fun findTitlesById(id: ByteArray): List<TitleEntity>

  /**
   * Inserts [title] and returns its storage key, or -1 when a unique constraint
   * ignores the row. Call it only after an alias miss in the same write
   * transaction, so an ignored row means an identity collision.
   */
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertTitleOrIgnore(title: TitleEntity): Long

  /** Replaces the details of the title [storageId]. Returns the row count. */
  @Query(
    "UPDATE titles SET display_name = :displayName, " +
      "description = :description WHERE storage_id = :storageId"
  )
  suspend fun updateTitleDetails(
    storageId: Long,
    displayName: String,
    description: String?,
  ): Int

  /** Returns every known chapter of the title, in storage-key order. */
  @Query(
    "SELECT * FROM chapters WHERE title_storage_id = :titleStorageId " +
      "ORDER BY storage_id"
  )
  suspend fun loadChapters(titleStorageId: Long): List<ChapterEntity>

  /**
   * Inserts [chapter] and returns its storage key, or -1 when a unique
   * constraint ignores the row. Call it only for a local key and a canonical
   * index that no chapter of the title holds in the same write transaction, so
   * an ignored row means an identity collision.
   */
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertChapterOrIgnore(chapter: ChapterEntity): Long

  /** Returns whether a stored chapter has the identity [id]. */
  @Query("SELECT EXISTS (SELECT 1 FROM chapters WHERE id = :id)")
  suspend fun hasChapterWithId(id: ByteArray): Boolean

  /**
   * Clears the canonical index of the chapter [storageId] of the title
   * [titleStorageId]. Returns the row count.
   */
  @Query(
    "UPDATE chapters SET canonical_index = NULL " +
      "WHERE storage_id = :storageId AND title_storage_id = :titleStorageId"
  )
  suspend fun clearCanonicalIndex(storageId: Long, titleStorageId: Long): Int

  /**
   * Stores the display name and canonical index of the chapter [storageId] of
   * the title [titleStorageId]. Returns the row count.
   */
  @Query(
    "UPDATE chapters SET display_name = :displayName, " +
      "canonical_index = :canonicalIndex " +
      "WHERE storage_id = :storageId AND title_storage_id = :titleStorageId"
  )
  suspend fun updateChapter(
    storageId: Long,
    titleStorageId: Long,
    displayName: String,
    canonicalIndex: Int,
  ): Int

  /** Returns the read rows of the chapters of the title. */
  @Query(
    "SELECT read_chapters.* FROM read_chapters " +
      "INNER JOIN chapters " +
      "ON chapters.storage_id = read_chapters.chapter_storage_id " +
      "WHERE chapters.title_storage_id = :titleStorageId " +
      "ORDER BY read_chapters.chapter_storage_id"
  )
  suspend fun loadReadChapters(titleStorageId: Long): List<ReadChapterEntity>

  /** Returns whether the title has at least one category association. */
  @Query(
    "SELECT EXISTS (SELECT 1 FROM title_categories " +
      "WHERE title_storage_id = :titleStorageId)"
  )
  suspend fun isLibraryMember(titleStorageId: Long): Boolean

  /**
   * Returns the persistent resume rows of the title. The primary key allows one
   * row at most; the read boundary checks it anyway.
   */
  @Query(
    "SELECT * FROM library_resume_positions " +
      "WHERE title_storage_id = :titleStorageId"
  )
  suspend fun loadResumePositions(
    titleStorageId: Long
  ): List<LibraryResumePositionEntity>

  /** Returns the category associations of the title, in category order. */
  @Query(
    "SELECT * FROM title_categories " +
      "WHERE title_storage_id = :titleStorageId ORDER BY category_id"
  )
  suspend fun loadTitleCategories(
    titleStorageId: Long
  ): List<TitleCategoryEntity>

  /** Returns every stored category, in identity order. */
  @Query("SELECT * FROM categories ORDER BY id")
  suspend fun loadCategories(): List<CategoryEntity>

  /**
   * Inserts [category] with a generated identity and returns that identity. The
   * row of [category] must carry the identity 0, which Room treats as unset.
   */
  @Insert suspend fun insertCategory(category: CategoryEntity): Long

  /** Inserts [associations]. A row that conflicts fails the statement. */
  @Insert
  suspend fun insertTitleCategories(associations: List<TitleCategoryEntity>)
}
