package app.hakusan.extensions

import java.util.Collections

/** The backend's claim about one observed chapter sequence. */
enum class ChapterSequenceStatus {
  AMBIGUOUS,
  COMPLETE,
  PARTIAL,
}

/**
 * A validated, explicit first-to-final chapter sequence.
 *
 * The order in [chapters] is canonical. Consumers must not rederive it from
 * display metadata, request completion timing, or another sorting rule. An
 * empty list is a valid complete observation for a title with no available
 * chapters.
 */
@ConsistentCopyVisibility
data class ChapterSnapshot private constructor(
  val title: SourceTitleKey,
  val chapters: List<SourceChapter>,
) {
  companion object {
    fun create(
      title: SourceTitleKey,
      status: ChapterSequenceStatus,
      chapters: Iterable<SourceChapter>,
    ): SourceResult<ChapterSnapshot, ChapterRefreshFailure> {
      when (status) {
        ChapterSequenceStatus.AMBIGUOUS -> {
          return invalidSnapshot(
            ChapterSnapshotRejection.AMBIGUOUS_ORDER,
          )
        }

        ChapterSequenceStatus.PARTIAL -> {
          return invalidSnapshot(
            ChapterSnapshotRejection.PARTIAL_SEQUENCE,
          )
        }

        ChapterSequenceStatus.COMPLETE -> Unit
      }

      val ownedChapters = chapters.toOwnedChapterSnapshot()
      if (ownedChapters.any { it.key.title != title }) {
        return invalidSnapshot(
          ChapterSnapshotRejection.FOREIGN_TITLE,
        )
      }

      val seen = HashSet<SourceChapterKey>(ownedChapters.size)
      if (ownedChapters.any { !seen.add(it.key) }) {
        return invalidSnapshot(
          ChapterSnapshotRejection.DUPLICATE_CHAPTER,
        )
      }

      return SourceResult.Success(
        ChapterSnapshot(
          title = title,
          chapters = ownedChapters,
        ),
      )
    }

    private fun invalidSnapshot(
      reason: ChapterSnapshotRejection,
    ): SourceResult.Failure<SourceFailure.InvalidChapterSnapshot> =
      SourceResult.Failure(
        SourceFailure.InvalidChapterSnapshot(reason),
      )
  }
}

private fun <Value> Iterable<Value>.toOwnedChapterSnapshot(): List<Value> =
  Collections.unmodifiableList(toMutableList())
