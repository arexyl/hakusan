package app.hakusan.extensions

import java.util.Collections

/** A title observation whose display metadata is not part of identity. */
data class SourceTitle(
  val key: SourceTitleKey,
  val displayName: String,
)

/** Details for one source-owned title. */
data class SourceTitleDetails(
  val title: SourceTitle,
  val description: String?,
)

/**
 * One source browse response.
 *
 * This result is not a complete chapter snapshot and cannot establish chapter
 * navigation order. Its item order is preserved exactly. Every title belongs
 * to [source], and each title identity occurs at most once.
 */
@ConsistentCopyVisibility
data class SourceBrowseResult private constructor(
  val source: SourceIdentity,
  val titles: List<SourceTitle>,
) {
  companion object {
    fun create(
      source: SourceIdentity,
      titles: Iterable<SourceTitle>,
    ): SourceResult<SourceBrowseResult, SourceBrowseFailure> {
      val ownedTitles = titles.toOwnedSnapshot()
      if (ownedTitles.any { it.key.source != source }) {
        return invalidBrowse(BrowseResultRejection.FOREIGN_SOURCE)
      }

      val seen = HashSet<SourceTitleKey>(ownedTitles.size)
      if (ownedTitles.any { !seen.add(it.key) }) {
        return invalidBrowse(BrowseResultRejection.DUPLICATE_TITLE)
      }

      return SourceResult.Success(
        SourceBrowseResult(
          source = source,
          titles = ownedTitles,
        ),
      )
    }

    private fun invalidBrowse(
      reason: BrowseResultRejection,
    ): SourceResult.Failure<SourceFailure.InvalidBrowseResult> =
      SourceResult.Failure(
        SourceFailure.InvalidBrowseResult(reason),
      )
  }
}

/** A chapter observation whose display metadata is not canonical order. */
data class SourceChapter(
  val key: SourceChapterKey,
  val displayName: String,
)

/**
 * One provider-defined page or image-segment boundary.
 *
 * [kind] describes provider granularity, not reader presentation mode.
 * [reference] is opaque to consumers. It does not define transport, identity,
 * viewport size, or a renderer batch. A unit becomes trusted only through
 * [SourceChapterContent.create].
 */
data class SourceContentUnit(
  val kind: SourceContentUnitKind,
  val reference: String,
)

enum class SourceContentUnitKind {
  PAGE,
  PROVIDER_SEGMENT,
}

/**
 * Ordered provider content for one chapter.
 *
 * Units retain their provider-defined order and boundaries. The list is
 * nonempty, and every opaque reference is nonblank after validation.
 */
@ConsistentCopyVisibility
data class SourceChapterContent private constructor(
  val chapter: SourceChapterKey,
  val units: List<SourceContentUnit>,
) {
  companion object {
    fun create(
      chapter: SourceChapterKey,
      units: Iterable<SourceContentUnit>,
    ): SourceResult<SourceChapterContent, SourceContentFailure> {
      val ownedUnits = units.toOwnedSnapshot()
      if (ownedUnits.isEmpty()) {
        return invalidContent(ChapterContentRejection.EMPTY_CONTENT)
      }
      if (ownedUnits.any { it.reference.isBlank() }) {
        return invalidContent(ChapterContentRejection.BLANK_REFERENCE)
      }

      return SourceResult.Success(
        SourceChapterContent(
          chapter = chapter,
          units = ownedUnits,
        ),
      )
    }

    private fun invalidContent(
      reason: ChapterContentRejection,
    ): SourceResult.Failure<SourceFailure.InvalidChapterContent> =
      SourceResult.Failure(
        SourceFailure.InvalidChapterContent(reason),
      )
  }
}

private fun <Value> Iterable<Value>.toOwnedSnapshot(): List<Value> =
  Collections.unmodifiableList(toMutableList())
