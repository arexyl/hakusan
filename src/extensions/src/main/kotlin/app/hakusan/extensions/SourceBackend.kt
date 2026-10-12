package app.hakusan.extensions

/**
 * The implementation of one source.
 *
 * One instance owns one stable [identity] and four one-shot operations. The
 * operations suspend, but the caller owns their task, execution, and
 * cancellation. Expected failures return in the value channel of each
 * operation. Cancellation and unexpected defects propagate.
 *
 * Results enter the application only through [CorrelatedSource], which checks
 * that each result belongs to its request.
 */
public interface SourceBackend {
  /** The stable identity of this source. */
  public val identity: SourceIdentity

  /** Display metadata that is never identity. */
  public val displayName: String

  /** Returns a browse observation owned by [identity] on success. */
  public suspend fun browse():
    SourceResult<SourceBrowseResult, SourceBrowseFailure>

  /**
   * Returns the details of the exact [title] on success.
   *
   * The caller guarantees that [identity] owns [title].
   */
  public suspend fun details(
    title: SourceTitleKey
  ): SourceResult<SourceTitleDetails, SourceDetailsFailure>

  /**
   * Returns the completion of the exact [request].
   *
   * The caller guarantees that [identity] owns the requested title.
   */
  public suspend fun refreshChapters(
    request: ChapterRefreshRequest
  ): ChapterRefreshCompletion

  /**
   * Returns the content of the exact [chapter] on success.
   *
   * The caller guarantees that [identity] owns [chapter].
   */
  public suspend fun content(
    chapter: SourceChapterKey
  ): SourceResult<SourceChapterContent, SourceContentFailure>
}
