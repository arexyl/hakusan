package app.hakusan.titles

/**
 * The exact source-qualified alias of one title.
 *
 * Both components are opaque and nonblank. They keep their whitespace, case,
 * and Unicode composition, and they are independent of application identities
 * and storage keys.
 */
@ConsistentCopyVisibility
internal data class SourceTitleAlias
private constructor(val sourceIdentity: String, val sourceTitleKey: String) {
  companion object {
    /**
     * Returns the alias of [sourceTitleKey] under [sourceIdentity], or null
     * when a component is blank. This is the one validation of the value.
     */
    fun ofOrNull(
      sourceIdentity: String,
      sourceTitleKey: String,
    ): SourceTitleAlias? =
      if (
        isSourceAliasComponent(sourceIdentity) &&
          isSourceAliasComponent(sourceTitleKey)
      ) {
        SourceTitleAlias(sourceIdentity, sourceTitleKey)
      } else {
        null
      }
  }
}

/**
 * The exact title-local key of one chapter.
 *
 * It is only the component local to the title. A chapter alias qualifies it
 * with its [SourceTitleAlias]. The key is opaque and nonblank. It keeps its
 * whitespace, case, and Unicode composition, and it is independent of
 * application identities and storage keys.
 */
@JvmInline
internal value class TitleLocalChapterKey
private constructor(val text: String) {
  companion object {
    /**
     * Returns the title-local key [text], or null when it is blank. This is the
     * one validation of the value.
     */
    fun ofOrNull(text: String): TitleLocalChapterKey? =
      if (isSourceAliasComponent(text)) {
        TitleLocalChapterKey(text)
      } else {
        null
      }
  }
}

/**
 * The exact alias of one chapter: its complete title alias and its exact
 * title-local key.
 *
 * Both components are validated values, so every composition is an alias.
 */
internal data class SourceChapterAlias(
  val titleAlias: SourceTitleAlias,
  val localKey: TitleLocalChapterKey,
)

/**
 * Whether [text] is a valid component of a source alias.
 *
 * This is the one rule for every component. A component is exact text that is
 * not blank, so it keeps whitespace, case, and Unicode composition.
 */
private fun isSourceAliasComponent(text: String): Boolean = text.isNotBlank()
