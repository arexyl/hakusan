package app.hakusan

import app.hakusan.extensions.ChapterSnapshot
import app.hakusan.extensions.SourceTitleDetails
import app.hakusan.titles.ReconcileChapterSnapshot
import app.hakusan.titles.ReconcileSourceChapter
import app.hakusan.titles.ReconcileSourceTitle
import app.hakusan.titles.SourceChapterAlias
import app.hakusan.titles.SourceTitleAlias

internal fun SourceTitleDetails.toReconcileTitle(): ReconcileSourceTitle =
  ReconcileSourceTitle(
    alias = SourceTitleAlias(
      sourceIdentity = title.key.source.value,
      sourceTitleKey = title.key.key,
    ),
    displayName = title.displayName,
    description = description,
  )

internal fun ChapterSnapshot.toReconcileSnapshot(): ReconcileChapterSnapshot {
  val titleAlias = SourceTitleAlias(
    sourceIdentity = title.source.value,
    sourceTitleKey = title.key,
  )
  return ReconcileChapterSnapshot.of(
    titleAlias = titleAlias,
    chapters = chapters.map { chapter ->
      ReconcileSourceChapter(
        alias = SourceChapterAlias(
          titleAlias = titleAlias,
          sourceChapterKey = chapter.key.key,
        ),
        displayName = chapter.displayName,
      )
    },
  )
}
