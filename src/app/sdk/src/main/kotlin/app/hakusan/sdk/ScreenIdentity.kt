package app.hakusan.sdk

import java.util.UUID

/**
 * The exact, opaque identity of one source as screens see it.
 *
 * Screens compare values exactly and never parse or normalize them.
 *
 * @throws IllegalArgumentException if [value] is blank.
 */
public data class ScreenSourceId(public val value: String) {
  init {
    requireIdentityComponent("Screen source id", value)
  }
}

/**
 * A title key qualified by the source that owns it.
 *
 * Equality uses [sourceId] and the exact source-local [sourceTitleKey]. Equal
 * local keys under different sources stay distinct.
 *
 * @throws IllegalArgumentException if [sourceTitleKey] is blank.
 */
public data class ScreenTitleKey(
  public val sourceId: ScreenSourceId,
  public val sourceTitleKey: String,
) {
  init {
    requireIdentityComponent("Screen title key", sourceTitleKey)
  }
}

/**
 * A chapter key qualified by the complete key of its title.
 *
 * Equality uses [titleKey] and the exact title-local [sourceChapterKey]. Equal
 * local keys under different titles stay distinct.
 *
 * @throws IllegalArgumentException if [sourceChapterKey] is blank.
 */
public data class ScreenChapterKey(
  public val titleKey: ScreenTitleKey,
  public val sourceChapterKey: String,
) {
  init {
    requireIdentityComponent("Screen chapter key", sourceChapterKey)
  }
}

/**
 * The application identity of one title.
 *
 * The UUID is opaque. Its timestamp and bytes carry no product meaning.
 *
 * @throws IllegalArgumentException if [value] is not an RFC 9562 UUIDv7.
 */
@JvmInline
public value class ScreenTitleId(public val value: UUID) {
  init {
    requireApplicationUuid("Screen title id", value)
  }
}

/**
 * The application identity of one chapter.
 *
 * The UUID is opaque. Its timestamp and bytes carry no product meaning.
 *
 * @throws IllegalArgumentException if [value] is not an RFC 9562 UUIDv7.
 */
@JvmInline
public value class ScreenChapterId(public val value: UUID) {
  init {
    requireApplicationUuid("Screen chapter id", value)
  }
}

/**
 * The opaque screen key of one Library shelf.
 *
 * It names one stored category and defines no order. It is valid only for the
 * current database, so never save it in a route, saved state, or another store.
 *
 * @throws IllegalArgumentException if [value] is not positive.
 */
@JvmInline
public value class ScreenShelfId(public val value: Long) {
  init {
    require(value > 0L) { "Screen shelf id must be positive." }
  }
}

private fun requireIdentityComponent(name: String, value: String) {
  require(value.isNotBlank()) { "$name must not be blank." }
}

private fun requireApplicationUuid(name: String, value: UUID) {
  require(value.variant() == 2 && value.version() == 7) {
    "$name must be an RFC 9562 UUIDv7."
  }
}
