package app.hakusan.titles.storage

import app.hakusan.titles.AcceptedChapter
import app.hakusan.titles.AcceptedTitleObservation
import app.hakusan.titles.TitleLocalChapterKey

/**
 * The writes that reconcile the known chapters of one title with an accepted
 * complete snapshot.
 *
 * Apply [clearedIndexes] first and then [writes] in order. No step then needs
 * two chapters of the title to share a canonical index. A known chapter whose
 * index is cleared and that no write names is omitted: it keeps its identity,
 * its read status, and its resume position, and has no canonical index.
 */
internal data class ChapterPlan(
  /**
   * The storage keys of the canonical chapters after the common prefix whose
   * canonical index changes, in their current order.
   */
  val clearedIndexes: List<Long>,
  /** The chapter writes in accepted order. */
  val writes: List<ChapterWrite>,
)

/** One chapter write of a [ChapterPlan]. */
internal sealed interface ChapterWrite {
  /** The final canonical index of the chapter. */
  val canonicalIndex: Int

  /**
   * Stores the display name and canonical index of the known chapter
   * [storageId]. At least one of them changes.
   */
  data class Update(
    val storageId: Long,
    val displayName: String,
    override val canonicalIndex: Int,
  ) : ChapterWrite

  /** Inserts a new chapter, which receives a new identity. */
  data class Insert(
    val localKey: TitleLocalChapterKey,
    val displayName: String,
    override val canonicalIndex: Int,
  ) : ChapterWrite
}

/**
 * Plans the reconciliation of the [known] chapters of a title with the
 * canonical sequence of [observation].
 *
 * The observation guarantees that its chapters belong to the title and repeat
 * no local key. Chapters reconcile by exact local key. The longest common
 * prefix of the current and the accepted sequence keeps its indexes and is
 * written only when its display name changes. Every later canonical chapter
 * whose index changes is cleared first. The writes then assign the final dense
 * indexes in accepted order and insert unknown chapters. A chapter whose index
 * and display name stay equal is not written. This one rule covers append, tail
 * removal, prepend, reorder, and reappearance.
 */
internal fun planChapters(
  known: KnownChapters,
  observation: AcceptedTitleObservation,
): ChapterPlan {
  val current = known.canonical

  val accepted = observation.chapters

  val prefix = commonPrefixLength(current, accepted)

  val acceptedIndexes =
    accepted.withIndex().associate { (index, chapter) ->
      chapter.alias.localKey to index
    }

  val cleared =
    current.drop(prefix).filter { chapter ->
      acceptedIndexes[chapter.localKey] != chapter.canonicalIndex
    }

  val clearedKeys = cleared.mapTo(HashSet(), StoredChapter::storageId)

  val writes = accepted.mapIndexedNotNull { index, chapter ->
    planWrite(known, clearedKeys, index, chapter)
  }

  return ChapterPlan(cleared.map(StoredChapter::storageId), writes)
}

/** The length of the longest common local-key prefix of both sequences. */
private fun commonPrefixLength(
  current: List<StoredChapter>,
  accepted: List<AcceptedChapter>,
): Int {
  val bound = minOf(current.size, accepted.size)

  val mismatch =
    (0 until bound).firstOrNull { index ->
      current[index].localKey != accepted[index].alias.localKey
    }

  return mismatch ?: bound
}

/**
 * Returns the write that gives [chapter] the canonical index [index], or null
 * when the stored chapter already has that index and display name.
 */
private fun planWrite(
  known: KnownChapters,
  clearedKeys: Set<Long>,
  index: Int,
  chapter: AcceptedChapter,
): ChapterWrite? {
  val stored =
    known.withLocalKey(chapter.alias.localKey)
      ?: return ChapterWrite.Insert(
        localKey = chapter.alias.localKey,
        displayName = chapter.displayName,
        canonicalIndex = index,
      )

  val indexBeforeWrite =
    if (stored.storageId in clearedKeys) null else stored.canonicalIndex

  val isUnchanged =
    indexBeforeWrite == index && stored.displayName == chapter.displayName

  return if (isUnchanged) {
    null
  } else {
    ChapterWrite.Update(
      storageId = stored.storageId,
      displayName = chapter.displayName,
      canonicalIndex = index,
    )
  }
}
