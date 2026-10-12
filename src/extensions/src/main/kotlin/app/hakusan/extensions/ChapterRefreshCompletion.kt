package app.hakusan.extensions

/**
 * The outcome of one chapter refresh, bound to the [request] it answers.
 *
 * A backend reports only an expected backend failure. Validation failures come
 * only from [ChapterSnapshot.create] and from [CorrelatedSource].
 */
@ConsistentCopyVisibility
public data class ChapterRefreshCompletion
private constructor(
  public val request: ChapterRefreshRequest,
  public val result: SourceResult<ChapterSnapshot, ChapterRefreshFailure>,
) {
  public companion object {
    /**
     * Completes [request] with [chapters] validated as the snapshot of the
     * requested title.
     */
    public fun completed(
      request: ChapterRefreshRequest,
      status: ChapterSequenceStatus,
      chapters: Iterable<SourceChapter>,
    ): ChapterRefreshCompletion {
      val snapshot = ChapterSnapshot.create(request.title, status, chapters)

      return ChapterRefreshCompletion(request, snapshot)
    }

    /** Completes [request] with the backend failure [error]. */
    public fun failed(
      request: ChapterRefreshRequest,
      error: SourceFailure.Unavailable,
    ): ChapterRefreshCompletion =
      ChapterRefreshCompletion(request, SourceResult.Failure(error))

    /**
     * Completes [request] as answered by a completion that the backend bound to
     * another request.
     */
    internal fun foreignRequest(
      request: ChapterRefreshRequest
    ): ChapterRefreshCompletion =
      ChapterRefreshCompletion(
        request,
        SourceResult.Failure(
          SourceFailure.InvalidChapterSnapshot(
            ChapterSnapshotRejection.FOREIGN_REQUEST
          )
        ),
      )
  }
}
