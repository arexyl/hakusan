package app.hakusan.titles.storage

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import java.util.Objects

/**
 * One title row. Its storage key is private to the database.
 *
 * [id] holds the raw stored UUID bytes, which the read boundary decodes. Treat
 * the array as immutable. Equality compares it by content.
 */
@Entity(
  tableName = "titles",
  indices =
    [
      Index(name = "index_titles_id", value = ["id"], unique = true),
      Index(
        name = "index_titles_source_alias",
        value = ["source_identity", "source_title_key"],
        unique = true,
      ),
    ],
)
internal data class TitleEntity(
  @PrimaryKey(autoGenerate = true, algorithm = PrimaryKey.Algorithm.ROWID)
  @ColumnInfo(name = "storage_id")
  val storageId: Long,
  @ColumnInfo(name = "id") val id: ByteArray,
  @ColumnInfo(name = "source_identity") val sourceIdentity: String,
  @ColumnInfo(name = "source_title_key") val sourceTitleKey: String,
  @ColumnInfo(name = "display_name") val displayName: String,
  @ColumnInfo(name = "description") val description: String?,
) {
  override fun equals(other: Any?): Boolean =
    other is TitleEntity &&
      storageId == other.storageId &&
      id contentEquals other.id &&
      sourceIdentity == other.sourceIdentity &&
      sourceTitleKey == other.sourceTitleKey &&
      displayName == other.displayName &&
      description == other.description

  override fun hashCode(): Int =
    Objects.hash(
      storageId,
      id.contentHashCode(),
      sourceIdentity,
      sourceTitleKey,
      displayName,
      description,
    )
}

/**
 * One known chapter row.
 *
 * A null [canonicalIndex] marks a known chapter that the newest accepted
 * snapshot omits. [id] holds the raw stored UUID bytes, which the read boundary
 * decodes. Treat the array as immutable. Equality compares it by content.
 */
@Entity(
  tableName = "chapters",
  foreignKeys =
    [
      ForeignKey(
        entity = TitleEntity::class,
        parentColumns = ["storage_id"],
        childColumns = ["title_storage_id"],
        onDelete = ForeignKey.CASCADE,
      )
    ],
  indices =
    [
      Index(name = "index_chapters_id", value = ["id"], unique = true),
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
  @PrimaryKey(autoGenerate = true, algorithm = PrimaryKey.Algorithm.ROWID)
  @ColumnInfo(name = "storage_id")
  val storageId: Long,
  @ColumnInfo(name = "id") val id: ByteArray,
  @ColumnInfo(name = "title_storage_id") val titleStorageId: Long,
  @ColumnInfo(name = "source_chapter_key") val sourceChapterKey: String,
  @ColumnInfo(name = "display_name") val displayName: String,
  @ColumnInfo(name = "canonical_index") val canonicalIndex: Int?,
) {
  override fun equals(other: Any?): Boolean =
    other is ChapterEntity &&
      storageId == other.storageId &&
      id contentEquals other.id &&
      titleStorageId == other.titleStorageId &&
      sourceChapterKey == other.sourceChapterKey &&
      displayName == other.displayName &&
      canonicalIndex == other.canonicalIndex

  override fun hashCode(): Int =
    Objects.hash(
      storageId,
      id.contentHashCode(),
      titleStorageId,
      sourceChapterKey,
      displayName,
      canonicalIndex,
    )
}
