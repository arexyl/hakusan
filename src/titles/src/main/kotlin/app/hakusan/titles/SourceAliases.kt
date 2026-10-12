package app.hakusan.titles

/**
 * The exact source-qualified alias of one title.
 *
 * Both components are opaque and nonblank. They keep their whitespace, case,
 * and Unicode composition, and they are independent of application identities
 * and storage keys.
 */
internal data class SourceTitleAlias(
  val sourceIdentity: String,
  val sourceTitleKey: String,
) {
  init {
    require(sourceIdentity.isNotBlank()) { "A source identity is blank." }
    require(sourceTitleKey.isNotBlank()) { "A source title key is blank." }
  }
}

/**
 * The exact alias of one chapter under its source-qualified title.
 *
 * The chapter key is opaque and nonblank, keeps its exact text, and is
 * independent of application identities and storage keys.
 */
internal data class SourceChapterAlias(
  val titleAlias: SourceTitleAlias,
  val sourceChapterKey: String,
) {
  init {
    require(sourceChapterKey.isNotBlank()) { "A source chapter key is blank." }
  }
}
