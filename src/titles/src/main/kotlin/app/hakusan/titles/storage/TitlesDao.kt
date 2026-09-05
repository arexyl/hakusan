package app.hakusan.titles.storage

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import java.util.UUID

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
}
