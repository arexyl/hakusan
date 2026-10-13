package app.hakusan.reader

/** The outcome of [ReaderChapterSource.load]. */
public sealed interface ReaderChapterResult {
  /**
   * The source supplied [content] for the requested chapter. Content of another
   * chapter is a defect of the source, not a result.
   */
  public data class Loaded(public val content: ReaderChapterContent) :
    ReaderChapterResult

  /** The source cannot supply the chapter now. A later load may succeed. */
  public data object Unavailable : ReaderChapterResult

  /**
   * The source answered, but its answer violates the input contract, for
   * example content without a unit or an answer for another chapter. The
   * implementation reports such an answer here instead of loading it.
   */
  public data object InvalidObservation : ReaderChapterResult
}

/**
 * Loads the content of one chapter of the pinned sequence when reading opens
 * it. The application implements it, and the reader never learns where the
 * content comes from.
 *
 * The caller owns the task. Cancellation propagates, and unexpected defects
 * propagate; neither becomes a result. Expected source outcomes are values.
 *
 * A loaded result holds content of the requested chapter. Navigation never
 * starts reading with content of another chapter, and it throws for such
 * content while the request is pending.
 */
public fun interface ReaderChapterSource {
  /** Loads the provider units of [chapter]. Each call is a new observation. */
  public suspend fun load(chapter: ReaderChapter): ReaderChapterResult
}
