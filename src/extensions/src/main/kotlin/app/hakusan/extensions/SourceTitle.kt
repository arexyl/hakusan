package app.hakusan.extensions

/**
 * One title observation.
 *
 * [displayName] is unvalidated display metadata and never identity.
 * Presentation supplies a fallback for a blank or unusual value.
 */
public data class SourceTitle(
  public val key: SourceTitleKey,
  public val displayName: String,
)
