package app.hakusan.titles.storage

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/** Read status of one chapter, recorded by row presence. */
@Entity(
  tableName = "read_chapters",
  foreignKeys =
    [
      ForeignKey(
        entity = ChapterEntity::class,
        parentColumns = ["storage_id"],
        childColumns = ["chapter_storage_id"],
        onDelete = ForeignKey.CASCADE,
      )
    ],
)
internal data class ReadChapterEntity(
  @PrimaryKey
  @ColumnInfo(name = "chapter_storage_id")
  val chapterStorageId: Long
)

/** The provider granularity of a stored content unit. */
internal enum class StoredContentUnitKind {
  PAGE,
  PROVIDER_SEGMENT,
}

/**
 * The one persistent resume position of a Library title.
 *
 * The composite foreign key requires the chapter to belong to the title.
 */
@Entity(
  tableName = "library_resume_positions",
  foreignKeys =
    [
      ForeignKey(
        entity = ChapterEntity::class,
        parentColumns = ["storage_id", "title_storage_id"],
        childColumns = ["chapter_storage_id", "title_storage_id"],
        onDelete = ForeignKey.CASCADE,
      )
    ],
  indices =
    [
      Index(
        name = "index_library_resume_positions_chapter_title",
        value = ["chapter_storage_id", "title_storage_id"],
      )
    ],
)
internal data class LibraryResumePositionEntity(
  @PrimaryKey @ColumnInfo(name = "title_storage_id") val titleStorageId: Long,
  @ColumnInfo(name = "chapter_storage_id") val chapterStorageId: Long,
  @ColumnInfo(name = "unit_kind") val unitKind: StoredContentUnitKind,
  /** The zero-based index in the provider-supplied unit sequence. */
  @ColumnInfo(name = "unit_index") val unitIndex: Int,
  /**
   * The UTC time, in epoch milliseconds, when the stored position last changed.
   * It is reconciliation evidence for backup import, not product order.
   */
  @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
