package app.hakusan

import app.hakusan.extensions.SourceIdentity
import app.hakusan.extensions.SourceTitle
import app.hakusan.extensions.SourceTitleKey
import app.hakusan.sdk.BrowseTitleItem
import app.hakusan.sdk.ScreenSourceId
import app.hakusan.sdk.ScreenTitleKey

/** Returns the screen identity of this source, with the exact value. */
internal fun SourceIdentity.toScreenSourceId(): ScreenSourceId =
  ScreenSourceId(value)

/** Returns the source identity that this screen identity names exactly. */
internal fun ScreenSourceId.toSourceIdentity(): SourceIdentity =
  SourceIdentity(value)

/** Returns the screen key of this title, with the exact local key. */
internal fun SourceTitleKey.toScreenTitleKey(): ScreenTitleKey =
  ScreenTitleKey(source.toScreenSourceId(), key)

/** Returns this title observation as a browse item. */
internal fun SourceTitle.toBrowseTitleItem(): BrowseTitleItem =
  BrowseTitleItem(key.toScreenTitleKey(), displayName)
