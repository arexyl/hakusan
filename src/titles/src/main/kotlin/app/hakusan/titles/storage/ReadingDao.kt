package app.hakusan.titles.storage

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert
import java.util.UUID

@Dao
internal abstract class ReadingDao {
  @Query(
    """
    SELECT *
    FROM chapters
    WHERE title_storage_id = :titleStorageId
    ORDER BY canonical_index
    """,
  )
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

  @Query(
    """
    UPDATE chapters
    SET display_name = :displayName
    WHERE storage_id = :storageId
      AND title_storage_id = :titleStorageId
    """,
  )
  abstract suspend fun updateChapterMetadata(
    storageId: Long,
    titleStorageId: Long,
    displayName: String,
  ): Int

  @Query(
    """
    SELECT *
    FROM chapters
    WHERE title_storage_id = :titleStorageId
      AND id = :chapterId
    LIMIT 1
    """,
  )
  abstract suspend fun findChapterByIdForTitle(
    titleStorageId: Long,
    chapterId: UUID,
  ): ChapterEntity?

  @Query(
    """
    SELECT *
    FROM chapters
    WHERE title_storage_id = :titleStorageId
      AND id IN (:firstChapterId, :secondChapterId)
    """,
  )
  abstract suspend fun findChaptersByIdsForTitle(
    titleStorageId: Long,
    firstChapterId: UUID,
    secondChapterId: UUID,
  ): List<ChapterEntity>

  @Query(
    """
    SELECT storage_id
    FROM chapters
    WHERE title_storage_id = :titleStorageId
      AND canonical_index IS NOT NULL
    ORDER BY canonical_index DESC
    LIMIT 1
    """,
  )
  abstract suspend fun findFinalChapterStorageId(
    titleStorageId: Long,
  ): Long?

  @Query(
    """
    SELECT EXISTS(
      SELECT 1
      FROM title_categories
      WHERE title_storage_id = :titleStorageId
    ) AS is_library_member,
    EXISTS(
      SELECT 1
      FROM read_chapters
      WHERE chapter_storage_id = :chapterStorageId
    ) AS is_chapter_read
    """,
  )
  abstract suspend fun loadLibraryChapterFacts(
    titleStorageId: Long,
    chapterStorageId: Long,
  ): LibraryChapterFacts

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  abstract suspend fun insertReadChapterOrIgnore(
    chapter: ReadChapterEntity,
  )

  @Query(
    """
    SELECT *
    FROM library_resume_positions
    WHERE title_storage_id = :titleStorageId
    LIMIT 1
    """,
  )
  abstract suspend fun findLibraryResumePosition(
    titleStorageId: Long,
  ): LibraryResumePositionEntity?

  @Upsert
  abstract suspend fun upsertLibraryResumePosition(
    position: LibraryResumePositionEntity,
  )

  @Query(
    """
    DELETE FROM library_resume_positions
    WHERE title_storage_id = :titleStorageId
      AND chapter_storage_id = :chapterStorageId
    """,
  )
  abstract suspend fun deleteLibraryResumePosition(
    titleStorageId: Long,
    chapterStorageId: Long,
  ): Int

  @Query(READING_PROGRESS_QUERY)
  abstract suspend fun loadReadingProgressRows(
    titleId: UUID,
  ): List<ReadingProgressRow>
}

internal data class LibraryChapterFacts(
  @ColumnInfo(name = "is_library_member")
  val isLibraryMember: Boolean,
  @ColumnInfo(name = "is_chapter_read")
  val isChapterRead: Boolean,
)

internal data class ReadingProgressRow(
  @ColumnInfo(name = "title_id")
  val titleId: UUID,
  @ColumnInfo(name = "title_source_identity")
  val titleSourceIdentity: String,
  @ColumnInfo(name = "title_source_key")
  val titleSourceKey: String,
  @ColumnInfo(name = "is_library_member")
  val isLibraryMember: Boolean,
  @ColumnInfo(name = "chapter_id")
  val chapterId: UUID?,
  @ColumnInfo(name = "chapter_source_key")
  val chapterSourceKey: String?,
  @ColumnInfo(name = "chapter_display_name")
  val chapterDisplayName: String?,
  @ColumnInfo(name = "chapter_canonical_index")
  val chapterCanonicalIndex: Int?,
  @ColumnInfo(name = "chapter_is_read")
  val chapterIsRead: Boolean,
  @ColumnInfo(name = "resume_chapter_id")
  val resumeChapterId: UUID?,
  @ColumnInfo(name = "resume_chapter_source_key")
  val resumeChapterSourceKey: String?,
  @ColumnInfo(name = "resume_chapter_display_name")
  val resumeChapterDisplayName: String?,
  @ColumnInfo(name = "resume_chapter_canonical_index")
  val resumeChapterCanonicalIndex: Int?,
  @ColumnInfo(name = "resume_chapter_is_read")
  val resumeChapterIsRead: Boolean,
  @ColumnInfo(name = "resume_unit_kind")
  val resumeUnitKind: StoredContentUnitKind?,
  @ColumnInfo(name = "resume_unit_index")
  val resumeUnitIndex: Int?,
)

private const val READING_PROGRESS_QUERY = """
  SELECT titles.id AS title_id,
         titles.source_identity AS title_source_identity,
         titles.source_title_key AS title_source_key,
         EXISTS(
           SELECT 1
           FROM title_categories
           WHERE title_categories.title_storage_id = titles.storage_id
         ) AS is_library_member,
         chapters.id AS chapter_id,
         chapters.source_chapter_key AS chapter_source_key,
         chapters.display_name AS chapter_display_name,
         chapters.canonical_index AS chapter_canonical_index,
         read_chapters.chapter_storage_id IS NOT NULL AS chapter_is_read,
         resume_chapters.id AS resume_chapter_id,
         resume_chapters.source_chapter_key AS resume_chapter_source_key,
         resume_chapters.display_name AS resume_chapter_display_name,
         resume_chapters.canonical_index AS resume_chapter_canonical_index,
         resume_read_chapters.chapter_storage_id IS NOT NULL
           AS resume_chapter_is_read,
         library_resume_positions.unit_kind AS resume_unit_kind,
         library_resume_positions.unit_index AS resume_unit_index
  FROM titles
  LEFT JOIN chapters
    ON chapters.title_storage_id = titles.storage_id
   AND chapters.canonical_index IS NOT NULL
  LEFT JOIN read_chapters
    ON read_chapters.chapter_storage_id = chapters.storage_id
  LEFT JOIN library_resume_positions
    ON library_resume_positions.title_storage_id = titles.storage_id
  LEFT JOIN chapters AS resume_chapters
    ON resume_chapters.storage_id =
       library_resume_positions.chapter_storage_id
   AND resume_chapters.title_storage_id =
       library_resume_positions.title_storage_id
  LEFT JOIN read_chapters AS resume_read_chapters
    ON resume_read_chapters.chapter_storage_id = resume_chapters.storage_id
  WHERE titles.id = :titleId
  ORDER BY chapters.canonical_index
"""
