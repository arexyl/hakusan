package app.hakusan.titles.storage

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import app.hakusan.titles.ContentUnitKind

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

/**
 * The provider granularity of a stored content unit.
 *
 * Each kind owns its stored text, which is part of the persisted schema. A
 * renamed constant keeps the text, and every write of a resume row stores it.
 */
internal enum class StoredContentUnitKind(
  /** The text that the `unit_kind` column stores for this kind. */
  val storedText: String
) {
  PAGE("PAGE"),
  PROVIDER_SEGMENT("PROVIDER_SEGMENT");

  companion object {
    /** Returns the kind whose stored text is [text], or null when none is. */
    fun ofStoredTextOrNull(text: String): StoredContentUnitKind? =
      entries.firstOrNull { kind ->
        kind.storedText == text
      }

    /**
     * Returns the stored kind of the public [kind]. This is the one mapping
     * that every write of a resume row uses.
     */
    fun of(kind: ContentUnitKind): StoredContentUnitKind =
      when (kind) {
        ContentUnitKind.PAGE -> PAGE
        ContentUnitKind.PROVIDER_SEGMENT -> PROVIDER_SEGMENT
      }
  }
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
  /**
   * The [StoredContentUnitKind.storedText] of the unit kind. Room materializes
   * it as text, so an unknown value reaches the read boundary instead of
   * failing inside Room's enum conversion.
   */
  @ColumnInfo(name = "unit_kind") val unitKind: String,
  /** The zero-based index in the provider-supplied unit sequence. */
  @ColumnInfo(name = "unit_index") val unitIndex: Int,
  /**
   * The UTC time, in epoch milliseconds, when the stored position last changed.
   * It is reconciliation evidence for backup import, not product order.
   */
  @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
