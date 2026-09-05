package app.hakusan.titles

import java.util.UUID

/** Durable application identity of one chapter. */
@JvmInline
value class ChapterId(
  val value: UUID,
) {
  init {
    requireUuidV7(value, "Chapter id")
  }
}

/** Exact source-qualified alias of one chapter under its title. */
data class SourceChapterAlias(
  val titleAlias: SourceTitleAlias,
  val sourceChapterKey: String,
) {
  init {
    require(sourceChapterKey.isNotBlank()) {
      "Source chapter key must not be blank."
    }
  }
}

/** One chapter observation from an accepted complete source snapshot. */
data class ReconcileSourceChapter(
  val alias: SourceChapterAlias,
  val displayName: String,
)

/**
 * One accepted complete first-to-final source chapter snapshot.
 *
 * Only a validated, current source completion may be translated into this
 * command. An empty snapshot is valid. The supplied order is canonical.
 * This command carries no source-refresh generation; the caller serializes
 * accepted per-title snapshots through reconciliation.
 */
@ConsistentCopyVisibility
data class ReconcileChapterSnapshot private constructor(
  val titleAlias: SourceTitleAlias,
  val chapters: List<ReconcileSourceChapter>,
) {
  companion object {
    fun of(
      titleAlias: SourceTitleAlias,
      chapters: Iterable<ReconcileSourceChapter>,
    ): ReconcileChapterSnapshot {
      val ownedChapters = chapters.toOwnedList()
      var allOwned = true
      val aliases = HashSet<SourceChapterAlias>(ownedChapters.size)
      ownedChapters.forEach { chapter ->
        if (chapter.alias.titleAlias != titleAlias) {
          allOwned = false
        }
        aliases += chapter.alias
      }
      require(allOwned) {
        "Every chapter must belong to the reconciled title alias."
      }
      require(aliases.size == ownedChapters.size) {
        "A chapter snapshot must not repeat a source chapter alias."
      }
      return ReconcileChapterSnapshot(titleAlias, ownedChapters)
    }
  }
}

/** Latest stored metadata and identities of one known chapter. */
@ConsistentCopyVisibility
data class Chapter internal constructor(
  val id: ChapterId,
  val titleId: TitleId,
  val alias: SourceChapterAlias,
  val displayName: String,
)

/** The current available first-to-final chapter sequence. */
@ConsistentCopyVisibility
data class CanonicalChapterSnapshot private constructor(
  val titleId: TitleId,
  val titleAlias: SourceTitleAlias,
  val chapters: List<Chapter>,
) {
  init {
    var allOwned = true
    val ids = HashSet<ChapterId>(chapters.size)
    val aliases = HashSet<SourceChapterAlias>(chapters.size)
    chapters.forEach { chapter ->
      if (
        chapter.titleId != titleId || chapter.alias.titleAlias != titleAlias
      ) {
        allOwned = false
      }
      ids += chapter.id
      aliases += chapter.alias
    }
    require(allOwned) {
      "Every canonical chapter must belong to the snapshot title."
    }
    require(ids.size == chapters.size) {
      "A canonical snapshot must not repeat a chapter identity."
    }
    require(aliases.size == chapters.size) {
      "A canonical snapshot must not repeat a source chapter alias."
    }
  }

  internal companion object {
    fun create(
      titleId: TitleId,
      titleAlias: SourceTitleAlias,
      chapters: Iterable<Chapter>,
    ): CanonicalChapterSnapshot = CanonicalChapterSnapshot(
      titleId = titleId,
      titleAlias = titleAlias,
      chapters = chapters.toOwnedList(),
    )
  }
}

/** Expected result of reconciling an accepted chapter snapshot. */
sealed interface ChapterReconciliationResult {
  @ConsistentCopyVisibility
  data class Success internal constructor(
    val snapshot: CanonicalChapterSnapshot,
  ) : ChapterReconciliationResult

  @ConsistentCopyVisibility
  data class Failure internal constructor(
    val error: ChapterReconciliationFailure,
  ) : ChapterReconciliationResult
}

sealed interface ChapterReconciliationFailure {
  /** No title matches the exact source alias in the command. */
  data object TitleNotFound : ChapterReconciliationFailure
}
