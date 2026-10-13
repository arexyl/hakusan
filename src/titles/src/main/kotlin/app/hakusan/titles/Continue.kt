package app.hakusan.titles

/**
 * The target of Continue for one progress snapshot, one case per rule of the
 * Continue order.
 *
 * Source availability is not known to `:titles`. A chapter counts as available
 * while the canonical order of the snapshot lists it.
 */
public sealed interface ContinueResolution {
  /** Continue resumes the saved [target]. */
  @ConsistentCopyVisibility
  public data class Resume
  internal constructor(
    /** The saved position, in a chapter of the canonical order. */
    public val target: ResumeTarget.Available
  ) : ContinueResolution

  /**
   * The saved [target] is in a chapter that the canonical order omits. Continue
   * keeps it and never chooses another chapter.
   */
  @ConsistentCopyVisibility
  public data class SavedTargetUnavailable
  internal constructor(
    /** The saved position, in a temporarily unavailable chapter. */
    public val target: ResumeTarget.TemporarilyUnavailable
  ) : ContinueResolution

  /**
   * The title has no saved position, and Continue opens [chapter], the first
   * unread chapter in canonical order, at its beginning.
   */
  @ConsistentCopyVisibility
  public data class FirstUnread
  internal constructor(
    /** The first unread chapter. */
    public val chapter: Chapter
  ) : ContinueResolution

  /**
   * The title has no saved position, every chapter is read, and Continue opens
   * [chapter], the final chapter in canonical order, at its beginning.
   */
  @ConsistentCopyVisibility
  public data class AllRead
  internal constructor(
    /** The final chapter. */
    public val chapter: Chapter
  ) : ContinueResolution

  /**
   * The title has no saved position and no available chapter, so Continue is
   * unavailable.
   */
  public data object NoAvailableChapter : ContinueResolution
}

/**
 * Resolves Continue from this snapshot alone.
 *
 * The rules apply in order: resume an available saved position; keep a saved
 * position in a temporarily unavailable chapter; open the first unread chapter
 * in canonical order; open the final chapter when every chapter is read;
 * otherwise report that no chapter is available. Canonical order is the order
 * of [ProgressSnapshot.chapters], never an order of names or numbers.
 */
public fun ProgressSnapshot.resolveContinue(): ContinueResolution {
  when (val target = resumeTarget) {
    is ResumeTarget.Available -> return ContinueResolution.Resume(target)

    is ResumeTarget.TemporarilyUnavailable ->
      return ContinueResolution.SavedTargetUnavailable(target)

    null -> Unit
  }

  val firstUnread = chapters.firstOrNull { state -> !state.isRead }

  if (firstUnread != null) {
    return ContinueResolution.FirstUnread(firstUnread.chapter)
  }

  val final =
    chapters.lastOrNull() ?: return ContinueResolution.NoAvailableChapter

  return ContinueResolution.AllRead(final.chapter)
}
