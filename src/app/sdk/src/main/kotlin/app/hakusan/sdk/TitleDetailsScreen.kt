package app.hakusan.sdk

/**
 * One chapter of a title-details screen.
 *
 * [displayName] is unvalidated display metadata and never identity.
 */
public data class DetailsChapterItem(
  public val id: ScreenChapterId,
  public val titleId: ScreenTitleId,
  public val key: ScreenChapterKey,
  public val displayName: String,
  public val isRead: Boolean,
)

/**
 * One reconciled title with its canonical chapters and Continue state.
 *
 * [chapters] keeps canonical reading order. The screen carries no Library
 * membership. Display metadata is unvalidated and never identity.
 */
@ConsistentCopyVisibility
public data class TitleDetailsScreen
private constructor(
  public val id: ScreenTitleId,
  public val key: ScreenTitleKey,
  public val sourceDisplayName: String,
  public val displayName: String,
  public val description: String?,
  public val chapters: List<DetailsChapterItem>,
  public val continueState: ContinueState,
) {
  public companion object {
    /**
     * Keeps an owned copy of [chapters] in canonical order.
     *
     * An empty chapter list is valid. [continueState] must agree with the
     * chapters:
     * - a ready target names exactly one listed chapter, and a resumed target
     *   names an unread one;
     * - no available chapter means an empty list;
     * - an unavailable saved target names no listed chapter.
     *
     * @throws IllegalArgumentException if a chapter belongs to another title,
     *   two chapters share an identity or a key, or [continueState] contradicts
     *   the chapters.
     */
    public fun of(
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
        ownedChapters.all { it.titleId == id && it.key.titleKey == key }
      ) {
        "Every details chapter must belong to the screen title."
      }

      require(ownedChapters.distinctBy { it.id }.size == ownedChapters.size) {
        "A details screen must not repeat a chapter identity."
      }

      require(ownedChapters.distinctBy { it.key }.size == ownedChapters.size) {
        "A details screen must not repeat a chapter key."
      }

      requireContinueAgreement(id, key, ownedChapters, continueState)

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

/** The expected completion of one title-details load. */
public sealed interface DetailsScreenResult {
  /** The load produced reconciled details. */
  public data class Success(public val screen: TitleDetailsScreen) :
    DetailsScreenResult

  /** The load failed in a way that decides the screen's recovery. */
  public data class Failure(public val error: DetailsScreenFailure) :
    DetailsScreenResult

  /**
   * A newer load of the same title superseded this one.
   *
   * This is arbitration, not a failure. It never replaces shown content.
   */
  public data object RejectedNotCurrent : DetailsScreenResult
}

/** A details failure that callers can handle without parsing diagnostics. */
public sealed interface DetailsScreenFailure {
  /** No source with the title's source identity is registered. */
  public data object SourceNotFound : DetailsScreenFailure

  /** The source cannot provide the title details right now. */
  public data object DetailsUnavailable : DetailsScreenFailure

  /** The source cannot provide the chapter list right now. */
  public data object ChaptersUnavailable : DetailsScreenFailure

  /** The source returned contradictory title details. */
  public data object InvalidTitleObservation : DetailsScreenFailure

  /** The source returned no valid complete chapter snapshot. */
  public data object InvalidChapterSnapshot : DetailsScreenFailure
}

/** Screen-facing loading of reconciled title details. */
public interface TitleDetailsScreenService {
  /**
   * Loads the details, canonical chapters, and read state of [titleKey].
   *
   * Cancellation and unexpected defects propagate and never become a
   * [DetailsScreenFailure].
   */
  public suspend fun loadDetails(titleKey: ScreenTitleKey): DetailsScreenResult
}

private fun requireContinueAgreement(
  titleId: ScreenTitleId,
  titleKey: ScreenTitleKey,
  chapters: List<DetailsChapterItem>,
  state: ContinueState,
) {
  when (state) {
    is ContinueState.Ready -> requireReadyTarget(chapters, state.target)

    is ContinueState.Unavailable ->
      when (val reason = state.reason) {
        ContinueUnavailableReason.NoAvailableChapter ->
          require(chapters.isEmpty()) {
            "Continue lacks a chapter only when the chapter list is empty."
          }

        is ContinueUnavailableReason.SavedTargetUnavailable ->
          requireUnavailableSavedTarget(titleId, titleKey, chapters, reason)
      }
  }
}

private fun requireReadyTarget(
  chapters: List<DetailsChapterItem>,
  target: ContinueTarget,
) {
  val chapter = chapters.singleOrNull {
    it.id == target.chapterId &&
      it.titleId == target.titleId &&
      it.key == target.chapterKey
  }

  require(chapter != null) {
    "The Continue target must name one chapter of the screen."
  }

  require(target.start !is ScreenReadingStart.Resume || !chapter.isRead) {
    "A resumed Continue target must not name a read chapter."
  }
}

private fun requireUnavailableSavedTarget(
  titleId: ScreenTitleId,
  titleKey: ScreenTitleKey,
  chapters: List<DetailsChapterItem>,
  reason: ContinueUnavailableReason.SavedTargetUnavailable,
) {
  require(
    reason.chapterKey.titleKey == titleKey && reason.position.titleId == titleId
  ) {
    "The unavailable saved target must belong to the screen title."
  }

  require(
    chapters.none {
      it.id == reason.position.chapterId || it.key == reason.chapterKey
    }
  ) {
    "The unavailable saved target must not name a listed chapter."
  }
}
