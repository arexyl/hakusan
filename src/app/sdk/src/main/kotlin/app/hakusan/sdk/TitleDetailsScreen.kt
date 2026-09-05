package app.hakusan.sdk

/** One canonical chapter entry on a title-details screen. */
data class DetailsChapterItem(
  val id: ScreenChapterId,
  val titleId: ScreenTitleId,
  val key: ScreenChapterKey,
  val displayName: String,
  val isRead: Boolean,
)

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

/** One coherent title-details and canonical-reading snapshot. */
@ConsistentCopyVisibility
data class TitleDetailsScreen private constructor(
  val id: ScreenTitleId,
  val key: ScreenTitleKey,
  val sourceDisplayName: String,
  val displayName: String,
  val description: String?,
  val chapters: List<DetailsChapterItem>,
  val isInLibrary: Boolean,
  val continueState: ContinueState,
) {
  companion object {
    fun of(
      id: ScreenTitleId,
      key: ScreenTitleKey,
      sourceDisplayName: String,
      displayName: String,
      description: String?,
      chapters: Iterable<DetailsChapterItem>,
      isInLibrary: Boolean,
      continueState: ContinueState,
    ): TitleDetailsScreen {
      val ownedChapters = chapters.toOwnedList()
      require(
        ownedChapters.all { it.titleId == id && it.key.titleKey == key },
      ) {
        "Every details chapter must belong to the screen title."
      }
      require(
        ownedChapters.distinctBy { it.id }.size == ownedChapters.size,
      ) {
        "A details screen must not repeat a chapter identity."
      }
      require(
        ownedChapters.distinctBy { it.key }.size == ownedChapters.size,
      ) {
        "A details screen must not repeat a chapter key."
      }
      validateContinue(
        titleId = id,
        titleKey = key,
        chapters = ownedChapters,
        isInLibrary = isInLibrary,
        state = continueState,
      )
      return TitleDetailsScreen(
        id = id,
        key = key,
        sourceDisplayName = sourceDisplayName,
        displayName = displayName,
        description = description,
        chapters = ownedChapters,
        isInLibrary = isInLibrary,
        continueState = continueState,
      )
    }
  }
}

/** Expected completion of one title-details load. */
sealed interface DetailsScreenResult {
  data class Success(
    val screen: TitleDetailsScreen,
  ) : DetailsScreenResult

  data class Failure(
    val error: DetailsScreenFailure,
  ) : DetailsScreenResult

  /** A newer title refresh owns the observable screen state. */
  data object RejectedNotCurrent : DetailsScreenResult
}

/** A details failure that callers can handle without parsing diagnostics. */
sealed interface DetailsScreenFailure {
  data object SourceNotFound : DetailsScreenFailure

  data object DetailsUnavailable : DetailsScreenFailure

  data object ChaptersUnavailable : DetailsScreenFailure

  data object InvalidTitleObservation : DetailsScreenFailure

  data object InvalidChapterSnapshot : DetailsScreenFailure

  /** Reconciliation no longer found the title established by this load. */
  data object LocalTitleNotFound : DetailsScreenFailure
}

/** Expected completion of the checkpoint's automatic Library Add. */
sealed interface AddToLibraryScreenResult {
  /** Includes both a new membership and an idempotent existing membership. */
  data object Success : AddToLibraryScreenResult

  /** Several categories exist; their chooser belongs to a later slice. */
  data object CategorySelectionRequired : AddToLibraryScreenResult

  data class Failure(
    val error: AddToLibraryScreenFailure,
  ) : AddToLibraryScreenResult
}

sealed interface AddToLibraryScreenFailure {
  data object TitleNotFound : AddToLibraryScreenFailure
}

/** Current selection produced when the user invokes Continue. */
sealed interface ContinueSelectionResult {
  data class Selected(
    val target: ContinueTarget,
  ) : ContinueSelectionResult

  data class Unavailable(
    val reason: ContinueUnavailableReason,
  ) : ContinueSelectionResult

  data class Failure(
    val error: ContinueSelectionFailure,
  ) : ContinueSelectionResult
}

sealed interface ContinueSelectionFailure {
  data object TitleNotFound : ContinueSelectionFailure
}

/** Screen-facing title operations. */
interface TitleDetailsScreenService {
  suspend fun loadDetails(
    titleKey: ScreenTitleKey,
  ): DetailsScreenResult

  suspend fun addToLibrary(
    titleId: ScreenTitleId,
  ): AddToLibraryScreenResult

  /** Resolves current progress again rather than trusting an older screen. */
  suspend fun selectContinue(
    titleId: ScreenTitleId,
  ): ContinueSelectionResult
}

private fun validateContinue(
  titleId: ScreenTitleId,
  titleKey: ScreenTitleKey,
  chapters: List<DetailsChapterItem>,
  isInLibrary: Boolean,
  state: ContinueState,
) {
  when (state) {
    is ContinueState.Ready -> {
      val target = state.target
      require(
        target.titleId == titleId &&
          target.chapterKey.titleKey == titleKey,
      ) {
        "The Continue target must belong to the screen title."
      }
      val chapter = chapters.singleOrNull {
        it.id == target.chapterId && it.key == target.chapterKey
      }
      require(chapter != null) {
        "The Continue target must identify one canonical chapter."
      }
      if (target.start is ScreenReadingStart.Resume) {
        require(isInLibrary) {
          "Only a Library title may expose a persistent resume position."
        }
        require(!chapter.isRead) {
          "A read chapter must not expose a resume position."
        }
      } else {
        val firstUnread = chapters.firstOrNull { !it.isRead }
        val expectedChapter = firstUnread ?: chapters.last()
        require(chapter == expectedChapter) {
          "A beginning target must select the first unread or final chapter."
        }
      }
    }

    is ContinueState.Unavailable -> when (val reason = state.reason) {
      ContinueUnavailableReason.NoAvailableChapter -> {
        require(chapters.isEmpty()) {
          "Continue is chapterless only when no canonical chapter is available."
        }
      }

      is ContinueUnavailableReason.SavedTargetUnavailable -> {
        require(isInLibrary) {
          "Only a Library title may retain a persistent unavailable target."
        }
        require(reason.chapterKey.titleKey == titleKey) {
          "The unavailable saved target must belong to the screen title."
        }
        require(reason.position.titleId == titleId) {
          "The unavailable saved position must belong to the screen title."
        }
        require(
          chapters.none {
            it.id == reason.position.chapterId || it.key == reason.chapterKey
          },
        ) {
          "An unavailable saved target must be absent from the canonical list."
        }
      }
    }
  }
}
