package app.hakusan.extensions

/**
 * Source-owned operations consumed through application adapters.
 *
 * Calls may suspend, but this contract does not own their task, dispatcher, or
 * lifetime. Implementations return expected source-domain failures and let
 * caller cancellation and unexpected defects propagate.
 */
interface SourceBackend {
  /** Stable opaque identity owned by this backend. */
  val identity: SourceIdentity

  /** Human-readable metadata that must not be used as identity. */
  val displayName: String

  /** Returns a browse observation owned by [identity] on success. */
  suspend fun browse(): SourceResult<SourceBrowseResult>

  /**
   * Returns details for the exact [title] on success.
   *
   * @throws IllegalArgumentException when [title] belongs to another source.
   */
  suspend fun details(
    title: SourceTitleKey,
  ): SourceResult<SourceTitleDetails>

  /**
   * Returns a completion correlated with the exact [request].
   *
   * A successful snapshot is owned by [ChapterRefreshRequest.title].
   *
   * @throws IllegalArgumentException when the requested title belongs to
   * another source.
   */
  suspend fun refreshChapters(
    request: ChapterRefreshRequest,
  ): ChapterRefreshCompletion

  /**
   * Returns content for the exact [chapter] on success.
   *
   * @throws IllegalArgumentException when [chapter] belongs to another source.
   */
  suspend fun content(
    chapter: SourceChapterKey,
  ): SourceResult<SourceChapterContent>
}
