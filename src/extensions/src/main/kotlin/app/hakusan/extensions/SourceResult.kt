package app.hakusan.extensions

/**
 * The value-channel outcome of one source operation.
 *
 * [Error] narrows expected failures to the operation that produced this result,
 * so a failure cannot cross into another operation's contract. Cancellation and
 * unexpected defects never travel through this channel.
 */
public sealed interface SourceResult<out Value, out Error : SourceFailure> {
  /** The operation produced [value]. */
  public data class Success<out Value>(public val value: Value) :
    SourceResult<Value, Nothing>

  /** The operation ended with the expected failure [error]. */
  public data class Failure<out Error : SourceFailure>(
    public val error: Error
  ) : SourceResult<Nothing, Error>
}

/**
 * An expected source failure as a machine-readable value.
 *
 * The `Invalid*` failures are created only inside this module, by the
 * validating factories and by [CorrelatedSource]. A backend cannot report one
 * itself.
 */
public sealed interface SourceFailure {
  /**
   * The requested observation is currently unavailable.
   *
   * An unknown key that is correctly qualified by its source also yields this
   * failure. It does not prove permanent deletion and never authorizes erasing
   * retained application state.
   */
  public data object Unavailable :
    SourceBrowseFailure,
    SourceDetailsFailure,
    ChapterRefreshFailure,
    SourceContentFailure

  /** A browse observation violated its invariants for [reason]. */
  @ConsistentCopyVisibility
  public data class InvalidBrowseResult
  internal constructor(public val reason: BrowseResultRejection) :
    SourceBrowseFailure

  /** A title-details observation violated its invariants for [reason]. */
  @ConsistentCopyVisibility
  public data class InvalidTitleDetails
  internal constructor(public val reason: TitleDetailsRejection) :
    SourceDetailsFailure

  /** A chapter-content observation violated its invariants for [reason]. */
  @ConsistentCopyVisibility
  public data class InvalidChapterContent
  internal constructor(public val reason: ChapterContentRejection) :
    SourceContentFailure

  /** A chapter-sequence observation violated its invariants for [reason]. */
  @ConsistentCopyVisibility
  public data class InvalidChapterSnapshot
  internal constructor(public val reason: ChapterSnapshotRejection) :
    ChapterRefreshFailure
}

/** The failures of the browse operation. */
public sealed interface SourceBrowseFailure : SourceFailure

/** The failures of the title-details operation. */
public sealed interface SourceDetailsFailure : SourceFailure

/** The failures that can complete a chapter-refresh request. */
public sealed interface ChapterRefreshFailure : SourceFailure

/** The failures of the chapter-content operation. */
public sealed interface SourceContentFailure : SourceFailure
