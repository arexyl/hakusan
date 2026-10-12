package app.hakusan.extensions

/**
 * A source whose results are checked against the requests that produced them.
 *
 * This is the one boundary where backend results enter the application. It
 * checks each result once, and consumers do not repeat the check:
 * - a browse result owned by another source becomes
 *   [BrowseResultRejection.FOREIGN_OWNER];
 * - details of another title become [TitleDetailsRejection.FOREIGN_TITLE];
 * - content of another chapter becomes
 *   [ChapterContentRejection.FOREIGN_CHAPTER].
 *
 * A chapter-refresh completion is bound to its request by construction and
 * passes unchanged. A completion for another request stays arbitration, which
 * [ChapterRefreshGate] reports as
 * [ChapterRefreshAcceptance.RejectedNotCurrent].
 *
 * A key that [identity] does not own violates a caller precondition and throws
 * [IllegalArgumentException] before the backend runs. Cancellation and
 * unexpected defects of the backend propagate unchanged.
 */
public class CorrelatedSource(private val backend: SourceBackend) {
  /** The stable identity of the backend, read once at construction. */
  public val identity: SourceIdentity = backend.identity

  /** Display metadata of the backend that is never identity. */
  public val displayName: String
    get() = backend.displayName

  /** Returns a browse observation owned by [identity] on success. */
  public suspend fun browse():
    SourceResult<SourceBrowseResult, SourceBrowseFailure> {
    val result = backend.browse()

    if (result is SourceResult.Success && result.value.source != identity) {
      return invalidBrowseResult(BrowseResultRejection.FOREIGN_OWNER)
    }

    return result
  }

  /**
   * Returns the details of the exact [title] on success.
   *
   * @throws IllegalArgumentException if [identity] does not own [title].
   */
  public suspend fun details(
    title: SourceTitleKey
  ): SourceResult<SourceTitleDetails, SourceDetailsFailure> {
    requireOwned(title)

    val result = backend.details(title)

    if (result is SourceResult.Success && result.value.title.key != title) {
      return invalidTitleDetails(TitleDetailsRejection.FOREIGN_TITLE)
    }

    return result
  }

  /**
   * Returns the completion that the backend bound to [request].
   *
   * @throws IllegalArgumentException if [identity] does not own the requested
   *   title.
   */
  public suspend fun refreshChapters(
    request: ChapterRefreshRequest
  ): ChapterRefreshCompletion {
    requireOwned(request.title)

    return backend.refreshChapters(request)
  }

  /**
   * Returns the content of the exact [chapter] on success.
   *
   * @throws IllegalArgumentException if [identity] does not own [chapter].
   */
  public suspend fun content(
    chapter: SourceChapterKey
  ): SourceResult<SourceChapterContent, SourceContentFailure> {
    requireOwned(chapter.title)

    val result = backend.content(chapter)

    if (result is SourceResult.Success && result.value.chapter != chapter) {
      return invalidChapterContent(ChapterContentRejection.FOREIGN_CHAPTER)
    }

    return result
  }

  private fun requireOwned(title: SourceTitleKey) {
    require(title.source == identity) {
      "The key belongs to a source other than this backend."
    }
  }
}
