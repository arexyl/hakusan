package app.hakusan.titles

import java.util.UUID

/** Durable application identity of one title. */
@JvmInline
value class TitleId(
  val value: UUID,
) {
  init {
    requireUuidV7(value, "Title id")
  }
}

/**
 * The exact source-qualified alias used to reconcile a source title.
 *
 * Both components are opaque. They are preserved without trimming,
 * normalization, or case folding.
 */
data class SourceTitleAlias(
  val sourceIdentity: String,
  val sourceTitleKey: String,
) {
  init {
    require(sourceIdentity.isNotBlank()) {
      "Source identity must not be blank."
    }
    require(sourceTitleKey.isNotBlank()) {
      "Source title key must not be blank."
    }
  }
}

/**
 * One accepted complete title-details observation to reconcile.
 *
 * The metadata replaces the previous values for [alias]. A null description
 * therefore clears a previously stored description. Browse-only observations
 * must not be translated into this command.
 */
data class ReconcileSourceTitle(
  val alias: SourceTitleAlias,
  val displayName: String,
  val description: String?,
)

/** Latest stored metadata and compact progress for one Library title. */
@ConsistentCopyVisibility
data class LibraryTitle internal constructor(
  val id: TitleId,
  val alias: SourceTitleAlias,
  val displayName: String,
  val description: String?,
  val progress: LibraryTitleProgressSummary,
)
