package app.hakusan.titles.storage

import app.hakusan.titles.requireUuidV7
import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import java.util.UUID

@Entity(
  tableName = "titles",
  indices = [
    Index(
      name = "index_titles_id",
      value = ["id"],
      unique = true,
    ),
    Index(
      name = "index_titles_source_alias",
      value = ["source_identity", "source_title_key"],
      unique = true,
    ),
  ],
)
internal data class TitleEntity(
  @PrimaryKey(
    autoGenerate = true,
    algorithm = PrimaryKey.Algorithm.ROWID,
  )
  @ColumnInfo(name = "storage_id")
  val storageId: Long,
  @ColumnInfo(name = "id")
  val id: UUID,
  @ColumnInfo(name = "source_identity")
  val sourceIdentity: String,
  @ColumnInfo(name = "source_title_key")
  val sourceTitleKey: String,
  @ColumnInfo(name = "display_name")
  val displayName: String,
  @ColumnInfo(name = "description")
  val description: String?,
) {
  init {
    requireGeneratedStorageId(storageId, "Title storage id")
    requireUuidV7(id, "Title id")
    require(sourceIdentity.isNotBlank()) {
      "Source identity must not be blank."
    }
    require(sourceTitleKey.isNotBlank()) {
      "Source title key must not be blank."
    }
  }
}

@Entity(
  tableName = "chapters",
  foreignKeys = [
    ForeignKey(
      entity = TitleEntity::class,
      parentColumns = ["storage_id"],
      childColumns = ["title_storage_id"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
  indices = [
    Index(
      name = "index_chapters_id",
      value = ["id"],
      unique = true,
    ),
    Index(
      name = "index_chapters_title_alias",
      value = ["title_storage_id", "source_chapter_key"],
      unique = true,
    ),
    Index(
      name = "index_chapters_title_canonical",
      value = ["title_storage_id", "canonical_index"],
      unique = true,
    ),
    Index(
      name = "index_chapters_storage_title",
      value = ["storage_id", "title_storage_id"],
      unique = true,
    ),
  ],
)
internal data class ChapterEntity(
  @PrimaryKey(
    autoGenerate = true,
    algorithm = PrimaryKey.Algorithm.ROWID,
  )
  @ColumnInfo(name = "storage_id")
  val storageId: Long,
  @ColumnInfo(name = "id")
  val id: UUID,
  @ColumnInfo(name = "title_storage_id")
  val titleStorageId: Long,
  @ColumnInfo(name = "source_chapter_key")
  val sourceChapterKey: String,
  @ColumnInfo(name = "display_name")
  val displayName: String,
  /**
   * Null when this known chapter is absent from the accepted current snapshot.
   */
  @ColumnInfo(name = "canonical_index")
  val canonicalIndex: Int?,
) {
  init {
    requireGeneratedStorageId(storageId, "Chapter storage id")
    requireUuidV7(id, "Chapter id")
    requireStoredId(titleStorageId, "Chapter title storage id")
    require(sourceChapterKey.isNotBlank()) {
      "Source chapter key must not be blank."
    }
    require(canonicalIndex == null || canonicalIndex >= 0) {
      "Canonical chapter index must not be negative."
    }
  }
}
