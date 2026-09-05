package app.hakusan.titles.storage

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Dao
internal abstract class ReadingDao {
  @Query("SELECT * FROM chapters WHERE title_storage_id = :titleStorageId")
  abstract suspend fun loadChapters(
    titleStorageId: Long,
  ): List<ChapterEntity>

  @Query(
    """
    UPDATE chapters
    SET canonical_index = NULL
    WHERE title_storage_id = :titleStorageId
      AND canonical_index IS NOT NULL
    """,
  )
  abstract suspend fun clearCanonicalIndexes(
    titleStorageId: Long,
  ): Int

  /** See the serialized exact-alias precondition in RoomReading. */
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  abstract suspend fun insertChapterOrIgnore(
    chapter: ChapterEntity,
  ): Long

  @Query(
    """
    UPDATE chapters
    SET display_name = :displayName,
        canonical_index = :canonicalIndex
    WHERE storage_id = :storageId
      AND title_storage_id = :titleStorageId
    """,
  )
  abstract suspend fun updateChapterSnapshotState(
    storageId: Long,
    titleStorageId: Long,
    displayName: String,
    canonicalIndex: Int,
  ): Int
}
