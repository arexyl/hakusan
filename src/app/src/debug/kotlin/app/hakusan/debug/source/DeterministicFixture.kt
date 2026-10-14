package app.hakusan.debug.source

import app.hakusan.extensions.SourceChapter
import app.hakusan.extensions.SourceChapterKey
import app.hakusan.extensions.SourceContentUnit
import app.hakusan.extensions.SourceContentUnitKind.PAGE
import app.hakusan.extensions.SourceContentUnitKind.PROVIDER_SEGMENT
import app.hakusan.extensions.SourceIdentity
import app.hakusan.extensions.SourceTitle
import app.hakusan.extensions.SourceTitleKey

/**
 * The one description of the Debug source.
 *
 * [DeterministicSource] and its tests derive every identity, display text,
 * chapter, and content unit from it. No ascending or descending sort of the
 * chapter labels, by text or by number, reconstructs the canonical order. The
 * units cover both granularities.
 */
internal object DeterministicFixture {
  /** The identity of the source. */
  val identity: SourceIdentity = SourceIdentity("app.hakusan.debug.source")

  /** The display name of the source. */
  val displayName: String = "Deterministic source"

  /** The one title of the source. */
  val title: SourceTitle =
    SourceTitle(
      key = SourceTitleKey(identity, "canonical-order-fixture"),
      displayName = "Canonical Order Fixture",
    )

  /** The description in the details of [title]. */
  val description: String =
    "Deterministic debug content with canonical chapter ordering."

  /** The chapters of [title] in canonical order, with their content. */
  val chapters: List<FixtureChapter> =
    listOf(
      FixtureChapter(
        chapter =
          SourceChapter(SourceChapterKey(title.key, "opening"), "Chapter 10"),
        units =
          listOf(
            SourceContentUnit(PAGE, "opening-page-10"),
            SourceContentUnit(PAGE, "opening-page-2"),
            SourceContentUnit(PAGE, "opening-page-1"),
          ),
      ),
      FixtureChapter(
        chapter =
          SourceChapter(SourceChapterKey(title.key, "middle"), "Chapter 1"),
        units =
          listOf(
            SourceContentUnit(PROVIDER_SEGMENT, "middle-segment-10"),
            SourceContentUnit(PROVIDER_SEGMENT, "middle-segment-2"),
            SourceContentUnit(PROVIDER_SEGMENT, "middle-segment-1"),
          ),
      ),
      FixtureChapter(
        chapter =
          SourceChapter(SourceChapterKey(title.key, "final"), "Chapter 2"),
        units = listOf(SourceContentUnit(PAGE, "final-page")),
      ),
    )
}

/** One chapter of [DeterministicFixture] and its content units in order. */
internal data class FixtureChapter(
  val chapter: SourceChapter,
  val units: List<SourceContentUnit>,
)
