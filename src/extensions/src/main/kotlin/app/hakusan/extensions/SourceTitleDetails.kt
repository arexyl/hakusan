package app.hakusan.extensions

/**
 * Details of one source-qualified title.
 *
 * [description] is unvalidated display metadata. [CorrelatedSource] rejects
 * details whose title differs from the requested key.
 */
public data class SourceTitleDetails(
  public val title: SourceTitle,
  public val description: String?,
)

/** The reason a title-details observation is invalid. */
public enum class TitleDetailsRejection {
  /** The details describe a title other than the requested one. */
  FOREIGN_TITLE
}

internal fun invalidTitleDetails(
  reason: TitleDetailsRejection
): SourceResult.Failure<SourceFailure.InvalidTitleDetails> =
  SourceResult.Failure(SourceFailure.InvalidTitleDetails(reason))
