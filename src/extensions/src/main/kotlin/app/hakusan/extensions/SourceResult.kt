package app.hakusan.extensions

/**
 * The value-channel outcome of a source operation.
 *
 * Expected source failures use [Failure], and [Error] narrows them to the
 * operation that produced this result. Diagnostic text and transport causes
 * are not the machine-readable contract.
 */
sealed interface SourceResult<out Value, out Error : SourceFailure> {
  data class Success<Value>(
    val value: Value,
  ) : SourceResult<Value, Nothing>

  data class Failure<Error : SourceFailure>(
    val error: Error,
  ) : SourceResult<Nothing, Error>
}

/** A source-domain failure that callers can handle without parsing text. */
sealed interface SourceFailure {
  /** The requested source observation is currently unavailable. */
  data object Unavailable :
    SourceBrowseFailure,
    SourceDetailsFailure,
    ChapterRefreshFailure,
    SourceContentFailure

  data class InvalidBrowseResult(
    val reason: BrowseResultRejection,
  ) : SourceBrowseFailure

  data class InvalidChapterContent(
    val reason: ChapterContentRejection,
  ) : SourceContentFailure

  data class InvalidChapterSnapshot(
    val reason: ChapterSnapshotRejection,
  ) : ChapterRefreshFailure
}

/** Failures returned by a source browse operation. */
sealed interface SourceBrowseFailure : SourceFailure

/** Failures returned by a source title-details operation. */
sealed interface SourceDetailsFailure : SourceFailure

/** Failures that can complete a chapter refresh request. */
sealed interface ChapterRefreshFailure : SourceFailure

/** Failures returned by a source chapter-content operation. */
sealed interface SourceContentFailure : SourceFailure

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
