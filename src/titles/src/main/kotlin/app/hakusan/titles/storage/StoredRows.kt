package app.hakusan.titles.storage

import app.hakusan.titles.ChapterId
import app.hakusan.titles.SourceTitleAlias
import app.hakusan.titles.StoredStateViolationException
import app.hakusan.titles.StoredStateViolationKind
import app.hakusan.titles.StoredStateViolationKind.ALIAS
import app.hakusan.titles.StoredStateViolationKind.CANONICAL_ORDER
import app.hakusan.titles.StoredStateViolationKind.IDENTITY
import app.hakusan.titles.StoredStateViolationKind.RESUME_STATE
import app.hakusan.titles.StoredStateViolationKind.STORAGE_KEY
import app.hakusan.titles.TitleId
import app.hakusan.titles.TitleLocalChapterKey

// The row-local checks of the read boundary. Room materializes entities
// without validation, and entities keep raw values wherever Room's own
// decoding could fail untyped or lossily, so materialization never throws for
// stored state. Each factory below is the one place that turns a row of its
// entity into a trusted value. It decodes and checks the columns in table
// order, so the first violated check of a row is the one reported. Checks stay
// even where SQL normally enforces the same shape. Messages name the table and
// column, never a stored value.

/**
 * A title row that passed the row-local checks. Its storage key is positive.
 */
@ConsistentCopyVisibility
internal data class StoredTitle
private constructor(
  val storageId: Long,
  val id: TitleId,
  val alias: SourceTitleAlias,
  val displayName: String,
  val description: String?,
) {
  companion object {
    /**
     * Checks [row] and returns its trusted value.
     *
     * @throws StoredStateViolationException if a column violates its invariant.
     */
    fun of(row: TitleEntity): StoredTitle {
      val storageId = positiveKey(row.storageId, "titles.storage_id")

      val id =
        row.id.toTitleIdOrNull()
          ?: violated(IDENTITY, "A stored titles.id is not a 16-byte UUIDv7.")

      val alias =
        SourceTitleAlias.ofOrNull(row.sourceIdentity, row.sourceTitleKey)
          ?: violated(ALIAS, "A stored titles row has a blank source alias.")

      return StoredTitle(
        storageId = storageId,
        id = id,
        alias = alias,
        displayName = row.displayName,
        description = row.description,
      )
    }
  }
}

/**
 * A chapter row that passed the row-local checks.
 *
 * Its storage keys are positive, its title-local key is nonblank, and its
 * canonical index is null or nonnegative. Whether the chapter belongs to a
 * title of the read, and whether the canonical order is dense, are cross-row
 * checks.
 */
@ConsistentCopyVisibility
internal data class StoredChapter
private constructor(
  val storageId: Long,
  val id: ChapterId,
  val titleStorageId: Long,
  val localKey: TitleLocalChapterKey,
  val displayName: String,
  val canonicalIndex: Int?,
) {
  companion object {
    /**
     * Checks [row] and returns its trusted value.
     *
     * @throws StoredStateViolationException if a column violates its invariant.
     */
    fun of(row: ChapterEntity): StoredChapter {
      val storageId = positiveKey(row.storageId, "chapters.storage_id")

      val id =
        row.id.toChapterIdOrNull()
          ?: violated(
            IDENTITY,
            "A stored chapters.id is not a 16-byte UUIDv7.",
          )

      val titleStorageId =
        positiveKey(row.titleStorageId, "chapters.title_storage_id")

      val localKey =
        TitleLocalChapterKey.ofOrNull(row.sourceChapterKey)
          ?: violated(ALIAS, "A stored chapters.source_chapter_key is blank.")

      val canonicalIndex = row.canonicalIndex

      if (canonicalIndex != null && canonicalIndex < 0) {
        violated(
          CANONICAL_ORDER,
          "A stored chapters.canonical_index is negative.",
        )
      }

      return StoredChapter(
        storageId = storageId,
        id = id,
        titleStorageId = titleStorageId,
        localKey = localKey,
        displayName = row.displayName,
        canonicalIndex = canonicalIndex,
      )
    }
  }
}

/**
 * A category row that passed the row-local checks. Its identity is positive.
 */
@ConsistentCopyVisibility
internal data class StoredCategory
private constructor(val id: Long, val name: String) {
  companion object {
    /**
     * Checks [row] and returns its trusted value.
     *
     * @throws StoredStateViolationException if a column violates its invariant.
     */
    fun of(row: CategoryEntity): StoredCategory {
      val id = positiveKey(row.id, "categories.id")

      return StoredCategory(id = id, name = row.name)
    }
  }
}

/** A category association that passed the row-local checks. */
@ConsistentCopyVisibility
internal data class StoredTitleCategory
private constructor(val titleStorageId: Long, val categoryId: Long) {
  companion object {
    /**
     * Checks [row] and returns its trusted value.
     *
     * @throws StoredStateViolationException if a column violates its invariant.
     */
    fun of(row: TitleCategoryEntity): StoredTitleCategory {
      val titleStorageId =
        positiveKey(row.titleStorageId, "title_categories.title_storage_id")

      val categoryId =
        positiveKey(row.categoryId, "title_categories.category_id")

      return StoredTitleCategory(
        titleStorageId = titleStorageId,
        categoryId = categoryId,
      )
    }
  }
}

/** A read-status row that passed the row-local checks. */
@ConsistentCopyVisibility
internal data class StoredReadChapter
private constructor(val chapterStorageId: Long) {
  companion object {
    /**
     * Checks [row] and returns its trusted value.
     *
     * @throws StoredStateViolationException if a column violates its invariant.
     */
    fun of(row: ReadChapterEntity): StoredReadChapter {
      val chapterStorageId =
        positiveKey(row.chapterStorageId, "read_chapters.chapter_storage_id")

      return StoredReadChapter(chapterStorageId)
    }
  }
}

/**
 * A persistent resume row that passed the row-local checks.
 *
 * Its storage keys are positive, its unit kind is known, and its unit index is
 * nonnegative. Ownership, read-state consistency, and Library membership are
 * cross-row checks.
 */
@ConsistentCopyVisibility
internal data class StoredResumePosition
private constructor(
  val titleStorageId: Long,
  val chapterStorageId: Long,
  val unitKind: StoredContentUnitKind,
  val unitIndex: Int,
  val updatedAt: Long,
) {
  companion object {
    /**
     * Checks [row] and returns its trusted value.
     *
     * @throws StoredStateViolationException if a column violates its invariant.
     */
    fun of(row: LibraryResumePositionEntity): StoredResumePosition {
      val titleStorageId =
        positiveKey(
          row.titleStorageId,
          "library_resume_positions.title_storage_id",
        )

      val chapterStorageId =
        positiveKey(
          row.chapterStorageId,
          "library_resume_positions.chapter_storage_id",
        )

      val unitKind =
        StoredContentUnitKind.ofStoredTextOrNull(row.unitKind)
          ?: violated(
            RESUME_STATE,
            "A stored library_resume_positions.unit_kind is unknown.",
          )

      if (row.unitIndex < 0) {
        violated(
          RESUME_STATE,
          "A stored library_resume_positions.unit_index is negative.",
        )
      }

      return StoredResumePosition(
        titleStorageId = titleStorageId,
        chapterStorageId = chapterStorageId,
        unitKind = unitKind,
        unitIndex = row.unitIndex,
        updatedAt = row.updatedAt,
      )
    }
  }
}

/** Returns [key], or raises a violation when it is not positive. */
private fun positiveKey(key: Long, column: String): Long =
  if (key > 0) {
    key
  } else {
    violated(STORAGE_KEY, "A stored $column is not positive.")
  }

private fun violated(kind: StoredStateViolationKind, message: String): Nothing =
  throw StoredStateViolationException(kind, message)
