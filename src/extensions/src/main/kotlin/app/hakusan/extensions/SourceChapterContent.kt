package app.hakusan.extensions

/**
 * One provider-defined content boundary of a chapter.
 *
 * [reference] is opaque to consumers. It defines no transport, identity,
 * viewport, or renderer batch. A unit is trusted only inside a
 * [SourceChapterContent] that its factory validated.
 */
public data class SourceContentUnit(
  public val kind: SourceContentUnitKind,
  public val reference: String,
)

/**
 * The provider granularity that one [SourceContentUnit] records.
 *
 * A kind does not choose a reader mode, define a viewport, become a persisted
 * position identity, or expose transport.
 */
public enum class SourceContentUnitKind {
  /** One page as the provider defines it. */
  PAGE,

  /** One segment of a longer provider image. */
  PROVIDER_SEGMENT,
}

/**
 * The ordered content of one chapter.
 *
 * [units] is nonempty, keeps the provider's order and boundaries, and holds
 * only nonblank references.
 */
@ConsistentCopyVisibility
public data class SourceChapterContent
private constructor(
  public val chapter: SourceChapterKey,
  public val units: List<SourceContentUnit>,
) {
  public companion object {
    /**
     * Validates an owned, ordered copy of [units] as the content of [chapter].
     */
    public fun create(
      chapter: SourceChapterKey,
      units: Iterable<SourceContentUnit>,
    ): SourceResult<SourceChapterContent, SourceContentFailure> {
      val ownedUnits = units.toOwnedList()

      if (ownedUnits.isEmpty()) {
        return invalidChapterContent(ChapterContentRejection.EMPTY_CONTENT)
      }

      if (ownedUnits.any { it.reference.isBlank() }) {
        return invalidChapterContent(ChapterContentRejection.BLANK_REFERENCE)
      }

      return SourceResult.Success(SourceChapterContent(chapter, ownedUnits))
    }
  }
}

/** The reason a chapter-content observation is invalid. */
public enum class ChapterContentRejection {
  /** A unit has a blank reference. */
  BLANK_REFERENCE,

  /** The content has no unit. */
  EMPTY_CONTENT,

  /** The content belongs to a chapter other than the requested one. */
  FOREIGN_CHAPTER,
}

internal fun invalidChapterContent(
  reason: ChapterContentRejection
): SourceResult.Failure<SourceFailure.InvalidChapterContent> =
  SourceResult.Failure(SourceFailure.InvalidChapterContent(reason))
