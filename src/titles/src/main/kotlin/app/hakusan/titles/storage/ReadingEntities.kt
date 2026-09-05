package app.hakusan.titles.storage

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/** Row presence records read status independently of Library membership. */
@Entity(
  tableName = "read_chapters",
  foreignKeys = [
    ForeignKey(
      entity = ChapterEntity::class,
      parentColumns = ["storage_id"],
      childColumns = ["chapter_storage_id"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
)
internal data class ReadChapterEntity(
  @PrimaryKey
  @ColumnInfo(name = "chapter_storage_id")
  val chapterStorageId: Long,
) {
  init {
    requireStoredId(chapterStorageId, "Read chapter storage id")
  }
}

internal enum class StoredContentUnitKind {
  PAGE,
  PROVIDER_SEGMENT,
}

/** The single persistent Library resume position for one title. */
@Entity(
  tableName = "library_resume_positions",
  foreignKeys = [
    ForeignKey(
      entity = ChapterEntity::class,
      parentColumns = ["storage_id", "title_storage_id"],
      childColumns = ["chapter_storage_id", "title_storage_id"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
  indices = [
    Index(
      name = "index_library_resume_positions_chapter_title",
      value = ["chapter_storage_id", "title_storage_id"],
    ),
  ],
)
internal data class LibraryResumePositionEntity(
  @PrimaryKey
  @ColumnInfo(name = "title_storage_id")
  val titleStorageId: Long,
  @ColumnInfo(name = "chapter_storage_id")
  val chapterStorageId: Long,
  @ColumnInfo(name = "unit_kind")
  val unitKind: StoredContentUnitKind,
  /** Zero-based position in the provider-supplied content-unit sequence. */
  @ColumnInfo(name = "unit_index")
  val unitIndex: Int,
) {
  init {
    requireStoredId(titleStorageId, "Position title storage id")
    requireStoredId(chapterStorageId, "Position chapter storage id")
    require(unitIndex >= 0) {
      "Content unit index must not be negative."
    }
  }
}
