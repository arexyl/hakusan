package app.hakusan

import app.hakusan.extensions.SourceChapterKey
import app.hakusan.extensions.SourceIdentity
import app.hakusan.extensions.SourceTitleKey
import app.hakusan.sdk.ScreenChapterKey
import app.hakusan.sdk.ScreenSourceId
import app.hakusan.sdk.ScreenTitleKey
import app.hakusan.titles.SourceTitleAlias

internal fun SourceTitleKey.toScreenKey(): ScreenTitleKey = ScreenTitleKey(
  sourceId = ScreenSourceId(source.value),
  sourceTitleKey = key,
)

internal fun ScreenTitleKey.toSourceKey(): SourceTitleKey = SourceTitleKey(
  source = SourceIdentity(sourceId.value),
  key = sourceTitleKey,
)

private fun SourceChapterKey.toScreenKey(): ScreenChapterKey =
  ScreenChapterKey(
    titleKey = title.toScreenKey(),
    sourceChapterKey = key,
  )

internal fun SourceTitleAlias.toScreenKey(): ScreenTitleKey = ScreenTitleKey(
  sourceId = ScreenSourceId(sourceIdentity),
  sourceTitleKey = sourceTitleKey,
)
