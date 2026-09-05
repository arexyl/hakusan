package app.hakusan.titles

/** Provider granularity retained for one actual reading position. */
enum class ReadingContentUnitKind {
  PAGE,
  PROVIDER_SEGMENT,
}

/** One actual chapter-local reading position. */
data class ReadingPosition(
  val titleId: TitleId,
  val chapterId: ChapterId,
  val unitKind: ReadingContentUnitKind,
  /** Zero-based position in the provider-supplied unit sequence. */
  val unitIndex: Int,
) {
  init {
    require(unitIndex >= 0) {
      "Content unit index must not be negative."
    }
  }
}

/**
 * One actual position update classified by the owning reader-session gate.
 * Construct it anew for each gate delivery; [recency] is not reusable state.
 */
data class ActualPositionUpdate(
  val position: ReadingPosition,
  val recency: ProgressEventRecency,
)

/**
 * A completed chapter followed by the unit that actually began next.
 * Construct it anew for each gate delivery; [recency] is not reusable state.
 */
data class ChapterBoundaryCompletion(
  val completedChapterId: ChapterId,
  val startedPosition: ReadingPosition,
  val recency: ProgressEventRecency,
) {
  init {
    require(completedChapterId != startedPosition.chapterId) {
      "A chapter boundary must cross two distinct chapters."
    }
  }
}

/** Completion intent after the final unit of the current final chapter. */
data class FinalChapterCompletion(
  val titleId: TitleId,
  val chapterId: ChapterId,
)

/** Reader-gate classification controlling latest-position effects. */
enum class ProgressEventRecency {
  /** Apply the event's latest-position and order-independent effects. */
  CURRENT,

  /**
   * Suppress latest-position writes. Completion still applies read status and
   * same-chapter position cleanup; an actual-position update does not mutate.
   */
  REORDERED,
}

/** Read state for one currently available canonical chapter. */
@ConsistentCopyVisibility
data class ChapterReadingState internal constructor(
  val chapter: Chapter,
  val isRead: Boolean,
)

/** The one persistent Library position and its current availability. */
@ConsistentCopyVisibility
data class LibraryResumePosition internal constructor(
  val chapter: Chapter,
  val position: ReadingPosition,
  val isCurrentlyAvailable: Boolean,
) {
  init {
    require(chapter.id == position.chapterId) {
      "Resume chapter and position identities must agree."
    }
    require(chapter.titleId == position.titleId) {
      "Resume chapter and position titles must agree."
    }
  }
}

/**
 * Coherent current progress for one known title.
 *
 * [canonicalChapters] preserves the accepted first-to-final sequence. A
 * resume target omitted by that sequence remains available through
 * [libraryResumePosition] with `isCurrentlyAvailable == false`.
 */
@ConsistentCopyVisibility
data class TitleReadingProgress private constructor(
  val titleId: TitleId,
  val titleAlias: SourceTitleAlias,
  val isInLibrary: Boolean,
  val canonicalChapters: List<ChapterReadingState>,
  val libraryResumePosition: LibraryResumePosition?,
) {
  init {
    val canonicalIds = canonicalChapters.map { it.chapter.id }
    require(canonicalIds.toSet().size == canonicalIds.size) {
      "Canonical reading progress must not repeat a chapter identity."
    }
    require(
      canonicalChapters.map { it.chapter.alias }.toSet().size ==
        canonicalChapters.size
    ) {
      "Canonical reading progress must not repeat a source chapter alias."
    }
    require(canonicalChapters.all { state ->
      state.chapter.titleId == titleId &&
        state.chapter.alias.titleAlias == titleAlias
    }) {
      "Every canonical chapter must belong to the progress title."
    }
    require(
      libraryResumePosition == null ||
        (
          libraryResumePosition.position.titleId == titleId &&
            libraryResumePosition.chapter.alias.titleAlias == titleAlias
        )
    ) {
      "A resume position must belong to the progress title."
    }
    require(
      libraryResumePosition == null ||
        canonicalChapters.none { state ->
          state.chapter.alias == libraryResumePosition.chapter.alias &&
            state.chapter.id != libraryResumePosition.chapter.id
        }
    ) {
      "One source chapter alias must not identify two chapters."
    }
    require(libraryResumePosition == null || isInLibrary) {
      "Only a Library title may expose a persistent resume position."
    }
    require(
      libraryResumePosition == null ||
        libraryResumePosition.isCurrentlyAvailable ==
        (libraryResumePosition.chapter.id in canonicalIds)
    ) {
      "Resume availability must derive from the canonical sequence."
    }
    require(
      libraryResumePosition == null ||
        canonicalChapters.none {
          it.chapter.id == libraryResumePosition.chapter.id && it.isRead
        }
    ) {
      "A read chapter must not retain a resume position."
    }
  }

  internal companion object {
    fun create(
      titleId: TitleId,
      titleAlias: SourceTitleAlias,
      isInLibrary: Boolean,
      canonicalChapters: Iterable<ChapterReadingState>,
      libraryResumePosition: LibraryResumePosition?,
    ): TitleReadingProgress = TitleReadingProgress(
      titleId = titleId,
      titleAlias = titleAlias,
      isInLibrary = isInLibrary,
      canonicalChapters = canonicalChapters.toOwnedList(),
      libraryResumePosition = libraryResumePosition,
    )
  }
}

enum class ActualPositionNotPersisted {
  CHAPTER_ALREADY_READ,
  REORDERED_EVENT,
  TITLE_NOT_IN_LIBRARY,
}

sealed interface ActualPositionResult {
  @ConsistentCopyVisibility
  data class Persisted internal constructor(
    val progress: TitleReadingProgress,
  ) : ActualPositionResult

  @ConsistentCopyVisibility
  data class NotPersisted internal constructor(
    val reason: ActualPositionNotPersisted,
    val progress: TitleReadingProgress,
  ) : ActualPositionResult

  @ConsistentCopyVisibility
  data class Failure internal constructor(
    val error: ReadingProgressFailure,
  ) : ActualPositionResult
}

sealed interface CompletionResult {
  /**
   * The completion was accepted. A reordered event still converges read
   * status and completed-position cleanup, but writes no successor position.
   */
  @ConsistentCopyVisibility
  data class Success internal constructor(
    val progress: TitleReadingProgress,
  ) : CompletionResult

  @ConsistentCopyVisibility
  data class Failure internal constructor(
    val error: ReadingProgressFailure,
  ) : CompletionResult
}

sealed interface ReadingProgressFailure {
  data object TitleNotFound : ReadingProgressFailure

  @ConsistentCopyVisibility
  data class ChaptersNotFound private constructor(
    val chapterIds: Set<ChapterId>,
  ) : ReadingProgressFailure {
    init {
      require(chapterIds.isNotEmpty()) {
        "At least one missing chapter id is required."
      }
    }

    internal companion object {
      fun create(
        chapterIds: Iterable<ChapterId>,
      ): ChaptersNotFound = ChaptersNotFound(chapterIds.toOwnedSet())
    }
  }

  /** The command no longer matches the current available canonical sequence. */
  @ConsistentCopyVisibility
  data class CanonicalSequenceChanged private constructor(
    val chapterIds: Set<ChapterId>,
  ) : ReadingProgressFailure {
    init {
      require(chapterIds.isNotEmpty()) {
        "At least one changed chapter id is required."
      }
    }

    internal companion object {
      fun create(
        chapterIds: Iterable<ChapterId>,
      ): CanonicalSequenceChanged =
        CanonicalSequenceChanged(chapterIds.toOwnedSet())
    }
  }
}
