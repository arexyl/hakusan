package app.hakusan.sdk

/** One canonical chapter entry on a title-details screen. */
data class DetailsChapterItem(
  val id: ScreenChapterId,
  val titleId: ScreenTitleId,
  val key: ScreenChapterKey,
  val displayName: String,
  val isRead: Boolean,
)

/** One coherent title-details and canonical-reading snapshot. */
@ConsistentCopyVisibility
data class TitleDetailsScreen private constructor(
  val id: ScreenTitleId,
  val key: ScreenTitleKey,
  val sourceDisplayName: String,
  val displayName: String,
  val description: String?,
  val chapters: List<DetailsChapterItem>,
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
        state = continueState,
      )
      return TitleDetailsScreen(
        id = id,
        key = key,
        sourceDisplayName = sourceDisplayName,
        displayName = displayName,
        description = description,
        chapters = ownedChapters,
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
}

/** Screen-facing loading of one title and its current chapter state. */
interface TitleDetailsScreenService {
  suspend fun loadDetails(
    titleKey: ScreenTitleKey,
  ): DetailsScreenResult
}

private fun validateContinue(
  titleId: ScreenTitleId,
  titleKey: ScreenTitleKey,
  chapters: List<DetailsChapterItem>,
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
