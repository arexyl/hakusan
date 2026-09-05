package app.hakusan.extensions

/**
 * The value-channel outcome of a source operation.
 *
 * Expected source failures use [Failure]. Diagnostic text and transport causes
 * are not the machine-readable contract.
 */
sealed interface SourceResult<out Value> {
  data class Success<Value>(
    val value: Value,
  ) : SourceResult<Value>

  data class Failure(
    val error: SourceFailure,
  ) : SourceResult<Nothing>
}

/** A source-domain failure that callers can handle without parsing text. */
sealed interface SourceFailure {
  /** The requested source observation is currently unavailable. */
  data object Unavailable : ChapterRefreshFailure

  data class InvalidBrowseResult(
    val reason: BrowseResultRejection,
  ) : SourceFailure

  data class InvalidChapterContent(
    val reason: ChapterContentRejection,
  ) : SourceFailure

  data class InvalidChapterSnapshot(
    val reason: ChapterSnapshotRejection,
  ) : ChapterRefreshFailure
}

/** Failures that can complete a chapter refresh request. */
sealed interface ChapterRefreshFailure : SourceFailure

enum class BrowseResultRejection {
  DUPLICATE_TITLE,
  FOREIGN_SOURCE,
}

enum class ChapterContentRejection {
  BLANK_REFERENCE,
  EMPTY_CONTENT,
}

enum class ChapterSnapshotRejection {
  AMBIGUOUS_ORDER,
  DUPLICATE_CHAPTER,
  FOREIGN_TITLE,
  PARTIAL_SEQUENCE,
}
