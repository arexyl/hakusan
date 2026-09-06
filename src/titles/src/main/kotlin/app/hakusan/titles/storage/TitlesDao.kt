package app.hakusan.titles.storage

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import java.util.UUID
import kotlinx.coroutines.flow.Flow

@Dao
internal abstract class TitlesDao {
  @Query(
    """
    SELECT *
    FROM titles
    WHERE source_identity = :sourceIdentity
      AND source_title_key = :sourceTitleKey
    LIMIT 1
    """,
  )
  abstract suspend fun findTitleByAlias(
    sourceIdentity: String,
    sourceTitleKey: String,
  ): TitleEntity?

  @Query("SELECT * FROM titles WHERE id = :id LIMIT 1")
  abstract suspend fun findTitleById(id: UUID): TitleEntity?

  /**
   * Used only after an exact-alias miss inside the serialized write
   * transaction. An ignored valid row therefore represents a UUID conflict.
   */
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  abstract suspend fun insertTitleOrIgnore(title: TitleEntity): Long

  @Query(
    """
    UPDATE titles
    SET display_name = :displayName,
        description = :description
    WHERE storage_id = :storageId
    """,
  )
  abstract suspend fun updateTitleMetadata(
    storageId: Long,
    displayName: String,
    description: String?,
  ): Int

  @Query("SELECT * FROM categories ORDER BY id")
  abstract suspend fun loadCategories(): List<CategoryEntity>

  @Insert
  abstract suspend fun insertCategory(category: CategoryEntity): Long

  @Query(
    """
    SELECT category_id
    FROM title_categories
    WHERE title_storage_id = :titleStorageId
    ORDER BY category_id
    """,
  )
  abstract suspend fun findTitleCategoryIds(
    titleStorageId: Long,
  ): List<Long>

  @Insert
  abstract suspend fun insertTitleCategories(
    associations: List<TitleCategoryEntity>,
  )

  @Query(
    """
    SELECT categories.id AS category_id,
           categories.name AS category_name,
           titles.id AS title_id,
           titles.source_identity AS source_identity,
           titles.source_title_key AS source_title_key,
           titles.display_name AS title_display_name,
           titles.description AS title_description
    FROM categories
    LEFT JOIN title_categories
      ON title_categories.category_id = categories.id
    LEFT JOIN titles
      ON titles.storage_id = title_categories.title_storage_id
    ORDER BY categories.id, title_categories.title_storage_id
    """,
  )
  abstract fun observeLibraryShelfRows(): Flow<List<LibraryShelfRow>>

  @Query(LIBRARY_SUMMARY_QUERY)
  abstract fun observeLibrarySummaryRows(): Flow<List<LibrarySummaryRow>>
}

internal interface LibraryShelfProjection {
  val categoryId: Long
  val categoryName: String
  val titleId: UUID?
  val sourceIdentity: String?
  val sourceTitleKey: String?
  val titleDisplayName: String?
  val titleDescription: String?
}

internal data class LibraryShelfRow(
  @ColumnInfo(name = "category_id")
  override val categoryId: Long,
  @ColumnInfo(name = "category_name")
  override val categoryName: String,
  @ColumnInfo(name = "title_id")
  override val titleId: UUID?,
  @ColumnInfo(name = "source_identity")
  override val sourceIdentity: String?,
  @ColumnInfo(name = "source_title_key")
  override val sourceTitleKey: String?,
  @ColumnInfo(name = "title_display_name")
  override val titleDisplayName: String?,
  @ColumnInfo(name = "title_description")
  override val titleDescription: String?,
) : LibraryShelfProjection

internal data class LibrarySummaryRow(
  @ColumnInfo(name = "category_id")
  override val categoryId: Long,
  @ColumnInfo(name = "category_name")
  override val categoryName: String,
  @ColumnInfo(name = "title_id")
  override val titleId: UUID?,
  @ColumnInfo(name = "source_identity")
  override val sourceIdentity: String?,
  @ColumnInfo(name = "source_title_key")
  override val sourceTitleKey: String?,
  @ColumnInfo(name = "title_display_name")
  override val titleDisplayName: String?,
  @ColumnInfo(name = "title_description")
  override val titleDescription: String?,
  @ColumnInfo(name = "chapter_count")
  val chapterCount: Long,
  @ColumnInfo(name = "read_chapter_count")
  val readChapterCount: Long,
  @ColumnInfo(name = "has_resume")
  val hasResume: Boolean,
  @ColumnInfo(name = "resume_is_available")
  val resumeIsAvailable: Boolean,
  @ColumnInfo(name = "resume_is_read")
  val resumeIsRead: Boolean,
) : LibraryShelfProjection

private const val LIBRARY_SUMMARY_QUERY = """
  WITH canonical_progress AS (
    SELECT chapters.title_storage_id AS title_storage_id,
           COUNT(chapters.storage_id) AS chapter_count,
           COUNT(read_chapters.chapter_storage_id) AS read_chapter_count
    FROM chapters
    LEFT JOIN read_chapters
      ON read_chapters.chapter_storage_id = chapters.storage_id
    WHERE chapters.canonical_index IS NOT NULL
    GROUP BY chapters.title_storage_id
  )
  SELECT categories.id AS category_id,
         categories.name AS category_name,
         titles.id AS title_id,
         titles.source_identity AS source_identity,
         titles.source_title_key AS source_title_key,
         titles.display_name AS title_display_name,
         titles.description AS title_description,
         COALESCE(canonical_progress.chapter_count, 0) AS chapter_count,
         COALESCE(canonical_progress.read_chapter_count, 0)
           AS read_chapter_count,
         library_resume_positions.title_storage_id IS NOT NULL AS has_resume,
         resume_chapters.canonical_index IS NOT NULL AS resume_is_available,
         resume_read_chapters.chapter_storage_id IS NOT NULL AS resume_is_read
  FROM categories
  LEFT JOIN title_categories
    ON title_categories.category_id = categories.id
  LEFT JOIN titles
    ON titles.storage_id = title_categories.title_storage_id
  LEFT JOIN canonical_progress
    ON canonical_progress.title_storage_id = titles.storage_id
  LEFT JOIN library_resume_positions
    ON library_resume_positions.title_storage_id = titles.storage_id
  LEFT JOIN chapters AS resume_chapters
    ON resume_chapters.storage_id =
       library_resume_positions.chapter_storage_id
   AND resume_chapters.title_storage_id =
       library_resume_positions.title_storage_id
  LEFT JOIN read_chapters AS resume_read_chapters
    ON resume_read_chapters.chapter_storage_id = resume_chapters.storage_id
  ORDER BY categories.id, title_categories.title_storage_id
"""
