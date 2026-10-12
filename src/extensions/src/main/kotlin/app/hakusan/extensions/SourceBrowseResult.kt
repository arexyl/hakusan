package app.hakusan.extensions

/**
 * One browse observation of a source.
 *
 * Every title belongs to [source], each title identity occurs at most once, and
 * [titles] keeps the source's exact order. An empty result is valid. A browse
 * result never establishes chapter order.
 */
@ConsistentCopyVisibility
public data class SourceBrowseResult
private constructor(
  public val source: SourceIdentity,
  public val titles: List<SourceTitle>,
) {
  public companion object {
    /**
     * Validates an owned, ordered copy of [titles] as the browse result of
     * [source].
     */
    public fun create(
      source: SourceIdentity,
      titles: Iterable<SourceTitle>,
    ): SourceResult<SourceBrowseResult, SourceBrowseFailure> {
      val ownedTitles = titles.toOwnedList()

      if (ownedTitles.any { it.key.source != source }) {
        return invalidBrowseResult(BrowseResultRejection.FOREIGN_SOURCE)
      }

      val seen = HashSet<SourceTitleKey>(ownedTitles.size)

      if (ownedTitles.any { !seen.add(it.key) }) {
        return invalidBrowseResult(BrowseResultRejection.DUPLICATE_TITLE)
      }

      return SourceResult.Success(SourceBrowseResult(source, ownedTitles))
    }
  }
}

/** The reason a browse observation is invalid. */
public enum class BrowseResultRejection {
  /** The same title identity occurs more than once. */
  DUPLICATE_TITLE,

  /** The result is owned by a source other than the requested one. */
  FOREIGN_OWNER,

  /** A title belongs to a source other than the result's owner. */
  FOREIGN_SOURCE,
}

internal fun invalidBrowseResult(
  reason: BrowseResultRejection
): SourceResult.Failure<SourceFailure.InvalidBrowseResult> =
  SourceResult.Failure(SourceFailure.InvalidBrowseResult(reason))
