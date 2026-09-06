package app.hakusan.sdk

/** Provider granularity retained by a screen-facing reading position. */
enum class ScreenContentUnitKind {
  PAGE,
  PROVIDER_SEGMENT,
}

/** One actual, zero-based position retained for Continue. */
data class ScreenReadingPosition(
  val titleId: ScreenTitleId,
  val chapterId: ScreenChapterId,
  val unitKind: ScreenContentUnitKind,
  val unitIndex: Int,
) {
  init {
    require(unitIndex >= 0) {
      "Screen content unit index must not be negative."
    }
  }
}

/** Where reading begins within the selected chapter. */
sealed interface ScreenReadingStart {
  data object Beginning : ScreenReadingStart

  data class Resume(
    val position: ScreenReadingPosition,
  ) : ScreenReadingStart
}

/** A source and application-qualified target selected for Continue. */
data class ContinueTarget(
  val titleId: ScreenTitleId,
  val chapterId: ScreenChapterId,
  val chapterKey: ScreenChapterKey,
  val start: ScreenReadingStart,
) {
  init {
    require(
      start !is ScreenReadingStart.Resume ||
        (
          start.position.titleId == titleId &&
            start.position.chapterId == chapterId
        ),
    ) {
      "A resumed position must identify the selected chapter."
    }
  }
}

/** Why Continue has no current target. */
sealed interface ContinueUnavailableReason {
  /** The accepted canonical sequence contains no chapter. */
  data object NoAvailableChapter : ContinueUnavailableReason

  /**
   * The retained position targets a chapter omitted by the current sequence.
   */
  data class SavedTargetUnavailable(
    val chapterKey: ScreenChapterKey,
    val position: ScreenReadingPosition,
  ) : ContinueUnavailableReason
}

/** Current details-screen availability of the labeled Continue action. */
sealed interface ContinueState {
  data class Ready(
    val target: ContinueTarget,
  ) : ContinueState

  data class Unavailable(
    val reason: ContinueUnavailableReason,
  ) : ContinueState
}

/** Current selection produced when the user invokes Continue. */
sealed interface ContinueSelectionResult {
  data class Selected(
    val target: ContinueTarget,
  ) : ContinueSelectionResult

  data class Unavailable(
    val reason: ContinueUnavailableReason,
  ) : ContinueSelectionResult

  /** The supplied application title identity is no longer known locally. */
  data object TitleNotFound : ContinueSelectionResult
}

/** Screen-facing resolution of the current Continue target. */
interface ContinueSelectionService {
  /** Resolves current progress again rather than trusting an older screen. */
  suspend fun selectContinue(
    titleId: ScreenTitleId,
  ): ContinueSelectionResult
}
