package app.hakusan.sdk

/** The provider granularity of the unit that a reading position names. */
public enum class ScreenContentUnitKind {
  /** One page. */
  PAGE,

  /** One provider-defined segment, which resumes at its start. */
  PROVIDER_SEGMENT,
}

/**
 * One actual reading position: a title, a chapter, and a zero-based unit.
 *
 * @throws IllegalArgumentException if [unitIndex] is negative.
 */
public data class ScreenReadingPosition(
  public val titleId: ScreenTitleId,
  public val chapterId: ScreenChapterId,
  public val unitKind: ScreenContentUnitKind,
  public val unitIndex: Int,
) {
  init {
    require(unitIndex >= 0) { "A reading unit index must not be negative." }
  }
}

/** Where reading starts within the selected chapter. */
public sealed interface ScreenReadingStart {
  /** Reading starts at the beginning of the chapter. */
  public data object Beginning : ScreenReadingStart

  /** Reading resumes at the saved [position]. */
  public data class Resume(public val position: ScreenReadingPosition) :
    ScreenReadingStart
}

/**
 * The chapter that Continue opens, with its exact identities.
 *
 * @throws IllegalArgumentException if a resumed position names another title or
 *   chapter.
 */
public data class ContinueTarget(
  public val titleId: ScreenTitleId,
  public val chapterId: ScreenChapterId,
  public val chapterKey: ScreenChapterKey,
  public val start: ScreenReadingStart,
) {
  init {
    require(
      start !is ScreenReadingStart.Resume ||
        start.position.titleId == titleId &&
          start.position.chapterId == chapterId
    ) {
      "A resumed position must name the target title and chapter."
    }
  }
}

/** Why Continue has no target. */
public sealed interface ContinueUnavailableReason {
  /** The canonical chapter sequence contains no chapter. */
  public data object NoAvailableChapter : ContinueUnavailableReason

  /**
   * The saved [position] names a chapter, [chapterKey], that the current
   * canonical sequence omits.
   *
   * Continue keeps the position and never falls back to another chapter.
   */
  public data class SavedTargetUnavailable(
    public val chapterKey: ScreenChapterKey,
    public val position: ScreenReadingPosition,
  ) : ContinueUnavailableReason
}

/** The availability of Continue on a title-details screen. */
public sealed interface ContinueState {
  /** Continue opens [target]. */
  public data class Ready(public val target: ContinueTarget) : ContinueState

  /** Continue is disabled for [reason]. */
  public data class Unavailable(public val reason: ContinueUnavailableReason) :
    ContinueState
}

/** The outcome of one Continue invocation. */
public sealed interface ContinueSelectionResult {
  /** Continue opens [target]. */
  public data class Selected(public val target: ContinueTarget) :
    ContinueSelectionResult

  /** Continue has no target for [reason]. */
  public data class Unavailable(public val reason: ContinueUnavailableReason) :
    ContinueSelectionResult

  /** The title is no longer known locally. */
  public data object TitleNotFound : ContinueSelectionResult
}

/** Screen-facing resolution of Continue. */
public interface ContinueSelectionService {
  /**
   * Resolves the current progress of the title [titleId] again.
   *
   * Each invocation reads current state and never trusts an older details
   * snapshot. Cancellation and unexpected defects propagate.
   */
  public suspend fun selectContinue(
    titleId: ScreenTitleId
  ): ContinueSelectionResult
}
