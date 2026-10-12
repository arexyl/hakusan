package app.hakusan.extensions

/**
 * The stable, opaque identity of one source.
 *
 * Equal values name the same logical source. The source adapter owns a
 * collision-free encoding and never substitutes display or package metadata.
 * Consumers compare values exactly and never parse or normalize them.
 *
 * @throws IllegalArgumentException if [value] is blank.
 */
public data class SourceIdentity(public val value: String) {
  init {
    requireIdentityComponent("Source identity", value)
  }
}

/**
 * A title key qualified by the source that owns it.
 *
 * Equality uses [source] and the exact source-local [key]. Equal local keys
 * under different sources stay distinct.
 *
 * @throws IllegalArgumentException if [key] is blank.
 */
public data class SourceTitleKey(
  public val source: SourceIdentity,
  public val key: String,
) {
  init {
    requireIdentityComponent("Title key", key)
  }
}

/**
 * A chapter key qualified by the complete key of its title.
 *
 * Equality uses [title] and the exact title-local [key]. Equal local keys under
 * different titles stay distinct.
 *
 * @throws IllegalArgumentException if [key] is blank.
 */
public data class SourceChapterKey(
  public val title: SourceTitleKey,
  public val key: String,
) {
  init {
    requireIdentityComponent("Chapter key", key)
  }
}

private fun requireIdentityComponent(name: String, value: String) {
  require(value.isNotBlank()) { "$name must not be blank." }
}
