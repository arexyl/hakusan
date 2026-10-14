package app.hakusan

import app.hakusan.extensions.ChapterSnapshot
import app.hakusan.extensions.SourceChapter
import app.hakusan.extensions.SourceChapterKey
import app.hakusan.extensions.SourceTitleDetails
import app.hakusan.extensions.SourceTitleKey
import app.hakusan.titles.AcceptedChapter
import app.hakusan.titles.AcceptedTitleObservation
import app.hakusan.titles.SourceChapterAlias
import app.hakusan.titles.SourceTitleAlias
import app.hakusan.titles.TitleLocalChapterKey

/**
 * Returns these accepted details and the accepted complete [snapshot] as one
 * accepted observation of the title.
 *
 * Every key keeps its exact text. The chapters keep the canonical order of
 * [snapshot], and display metadata is copied unchanged.
 *
 * @throws IllegalArgumentException if a chapter of [snapshot] belongs to a
 *   title other than that of these details, which is a defect of the caller.
 */
internal fun SourceTitleDetails.toAcceptedTitleObservation(
  snapshot: ChapterSnapshot
): AcceptedTitleObservation {
  val chapters = snapshot.chapters.map(SourceChapter::toAcceptedChapter)

  return AcceptedTitleObservation.of(
    alias = title.key.toSourceTitleAlias(),
    displayName = title.displayName,
    description = description,
    chapters = chapters,
  )
}

/** Returns this chapter observation as an accepted chapter. */
private fun SourceChapter.toAcceptedChapter(): AcceptedChapter =
  AcceptedChapter(key.toSourceChapterAlias(), displayName)

/**
 * Returns the alias of this title key, with the exact components.
 *
 * Both modules reject only blank components, so the alias factory accepts every
 * title key.
 */
private fun SourceTitleKey.toSourceTitleAlias(): SourceTitleAlias =
  SourceTitleAlias.of(source.value, key)

/** Returns the alias of this chapter key, with the exact components. */
private fun SourceChapterKey.toSourceChapterAlias(): SourceChapterAlias =
  SourceChapterAlias(title.toSourceTitleAlias(), TitleLocalChapterKey.of(key))
