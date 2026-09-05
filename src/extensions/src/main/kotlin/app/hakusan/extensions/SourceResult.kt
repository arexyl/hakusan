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
  data class InvalidBrowseResult(
    val reason: BrowseResultRejection,
  ) : SourceFailure

  data class InvalidChapterContent(
    val reason: ChapterContentRejection,
  ) : SourceFailure
}

enum class BrowseResultRejection {
  DUPLICATE_TITLE,
  FOREIGN_SOURCE,
}

enum class ChapterContentRejection {
  BLANK_REFERENCE,
  EMPTY_CONTENT,
}
