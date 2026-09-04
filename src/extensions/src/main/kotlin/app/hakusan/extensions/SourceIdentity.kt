package app.hakusan.extensions

/**
 * Stable, opaque identity of one source.
 *
 * Equal values name the same logical source. Values are globally unique in the
 * source registry and remain stable across compatible source updates. Consumers
 * must not parse them. The source adapter owns their collision-free encoding
 * and must not substitute display or mutable package metadata for stable source
 * identity.
 *
 * @throws IllegalArgumentException if [value] is blank.
 */
data class SourceIdentity(
  val value: String,
) {
  init {
    requireIdentityComponent("Source identity", value)
  }
}

/**
 * A stable title key qualified by the source that owns it.
 *
 * Equality uses [source] and the exact [key]. Display metadata and locator
 * equivalence are not part of this identity.
 *
 * @throws IllegalArgumentException if [key] is blank.
 */
data class SourceTitleKey(
  val source: SourceIdentity,
  val key: String,
) {
  init {
    requireIdentityComponent("Title key", key)
  }
}

/**
 * A stable chapter key qualified by its source-owned title.
 *
 * Equality uses [title] and the exact [key]. The same local chapter key under
 * another title remains a different identity.
 *
 * @throws IllegalArgumentException if [key] is blank.
 */
data class SourceChapterKey(
  val title: SourceTitleKey,
  val key: String,
) {
  init {
    requireIdentityComponent("Chapter key", key)
  }
}

private fun requireIdentityComponent(
  name: String,
  value: String,
) {
  require(value.isNotBlank()) { "$name must not be blank." }
}
