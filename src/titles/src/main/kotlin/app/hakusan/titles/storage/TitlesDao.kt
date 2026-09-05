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
    ORDER BY categories.id, titles.storage_id
    """,
  )
  abstract fun observeLibraryShelfRows(): Flow<List<LibraryShelfRow>>
}

internal data class LibraryShelfRow(
  @ColumnInfo(name = "category_id")
  val categoryId: Long,
  @ColumnInfo(name = "category_name")
  val categoryName: String,
  @ColumnInfo(name = "title_id")
  val titleId: UUID?,
  @ColumnInfo(name = "source_identity")
  val sourceIdentity: String?,
  @ColumnInfo(name = "source_title_key")
  val sourceTitleKey: String?,
  @ColumnInfo(name = "title_display_name")
  val titleDisplayName: String?,
  @ColumnInfo(name = "title_description")
  val titleDescription: String?,
)
