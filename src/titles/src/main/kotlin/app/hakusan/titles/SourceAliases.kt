package app.hakusan.titles

/**
 * The exact source-qualified alias of one title.
 *
 * Both components are opaque and nonblank. They keep their whitespace, case,
 * and Unicode composition, and they are independent of application identities
 * and storage keys. Equal source title keys under different source identities
 * are different aliases.
 */
@ConsistentCopyVisibility
public data class SourceTitleAlias
private constructor(
  /** The exact identity of the source that owns the title. */
  public val sourceIdentity: String,
  /** The exact source-local key of the title. */
  public val sourceTitleKey: String,
) {
  public companion object {
    /**
     * Returns the alias of [sourceTitleKey] under [sourceIdentity].
     *
     * @throws IllegalArgumentException if a component is blank.
     */
    public fun of(
      sourceIdentity: String,
      sourceTitleKey: String,
    ): SourceTitleAlias =
      requireNotNull(ofOrNull(sourceIdentity, sourceTitleKey)) {
        "A source title alias requires nonblank components."
      }

    /**
     * Returns the alias of [sourceTitleKey] under [sourceIdentity], or null
     * when a component is blank. This is the one validation of the value.
     */
    internal fun ofOrNull(
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
public value class TitleLocalChapterKey
private constructor(
  /** The exact text of the key. */
  public val text: String
) {
  public companion object {
    /**
     * Returns the title-local key [text].
     *
     * @throws IllegalArgumentException if [text] is blank.
     */
    public fun of(text: String): TitleLocalChapterKey =
      requireNotNull(ofOrNull(text)) {
        "A title-local chapter key must not be blank."
      }

    /**
     * Returns the title-local key [text], or null when it is blank. This is the
     * one validation of the value.
     */
    internal fun ofOrNull(text: String): TitleLocalChapterKey? =
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
 * Both components are validated values, so every composition is an alias. Equal
 * local keys under different title aliases are different aliases.
 */
public data class SourceChapterAlias(
  /** The alias of the title that owns the chapter. */
  public val titleAlias: SourceTitleAlias,
  /** The exact title-local key of the chapter. */
  public val localKey: TitleLocalChapterKey,
)

/**
 * Whether [text] is a valid component of a source alias.
 *
 * This is the one rule for every component. A component is exact text that is
 * not blank, so it keeps whitespace, case, and Unicode composition.
 */
private fun isSourceAliasComponent(text: String): Boolean = text.isNotBlank()
