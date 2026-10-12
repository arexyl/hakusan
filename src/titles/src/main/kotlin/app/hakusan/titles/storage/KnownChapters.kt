package app.hakusan.titles.storage

import app.hakusan.titles.StoredStateViolationException
import app.hakusan.titles.StoredStateViolationKind.ALIAS
import app.hakusan.titles.StoredStateViolationKind.CANONICAL_ORDER
import app.hakusan.titles.StoredStateViolationKind.IDENTITY
import app.hakusan.titles.StoredStateViolationKind.OWNERSHIP
import app.hakusan.titles.TitleLocalChapterKey

/**
 * The validated known chapters of one title: every stored chapter, whether the
 * newest accepted snapshot lists it or omits it.
 *
 * Every chapter belongs to the title, identities and local keys are unique, and
 * the canonical indexes are dense from zero.
 */
internal class KnownChapters
private constructor(
  /**
   * The chapters of the canonical sequence in order. The chapter at position
   * `i` has the canonical index `i`.
   */
  val canonical: List<StoredChapter>,
  private val byLocalKey: Map<TitleLocalChapterKey, StoredChapter>,
  private val byStorageId: Map<Long, StoredChapter>,
) {
  /** Returns the known chapter with [localKey], or null when none has it. */
  fun withLocalKey(localKey: TitleLocalChapterKey): StoredChapter? =
    byLocalKey[localKey]

  /** Returns the known chapter with [storageId], or null when none has it. */
  fun withStorageId(storageId: Long): StoredChapter? = byStorageId[storageId]

  companion object {
    /**
     * Validates [rows], the stored chapters of the title [titleStorageId].
     *
     * The checks run in order: the row-local checks of every row, then
     * ownership, then unique identities and local keys, then canonical density.
     * A later malformed row therefore never hides an earlier violation.
     *
     * @throws StoredStateViolationException if a row or the set of rows
     *   violates an invariant.
     */
    fun of(titleStorageId: Long, rows: List<ChapterEntity>): KnownChapters {
      val chapters = rows.map(StoredChapter::of)

      if (
        chapters.any { chapter -> chapter.titleStorageId != titleStorageId }
      ) {
        violated(OWNERSHIP, "A stored chapters row belongs to another title.")
      }

      val byStorageId = chapters.associateBy(StoredChapter::storageId)

      val ids = chapters.mapTo(HashSet(), StoredChapter::id)

      if (byStorageId.size != chapters.size || ids.size != chapters.size) {
        violated(
          IDENTITY,
          "Stored chapters rows of a title repeat an identity.",
        )
      }

      val byLocalKey = chapters.associateBy(StoredChapter::localKey)

      if (byLocalKey.size != chapters.size) {
        violated(ALIAS, "Stored chapters rows of a title repeat a local key.")
      }

      val canonical =
        chapters
          .filter { chapter -> chapter.canonicalIndex != null }
          .sortedBy { chapter -> chapter.canonicalIndex }

      if (
        canonical.withIndex().any { (index, chapter) ->
          chapter.canonicalIndex != index
        }
      ) {
        violated(
          CANONICAL_ORDER,
          "Stored canonical indexes of a title are not dense from zero.",
        )
      }

      return KnownChapters(canonical, byLocalKey, byStorageId)
    }
  }
}
