package app.hakusan.debug.source

import app.hakusan.extensions.ChapterRefreshCompletion
import app.hakusan.extensions.ChapterRefreshRequest
import app.hakusan.extensions.ChapterSequenceStatus
import app.hakusan.extensions.SourceBackend
import app.hakusan.extensions.SourceBrowseFailure
import app.hakusan.extensions.SourceBrowseResult
import app.hakusan.extensions.SourceChapterContent
import app.hakusan.extensions.SourceChapterKey
import app.hakusan.extensions.SourceContentFailure
import app.hakusan.extensions.SourceDetailsFailure
import app.hakusan.extensions.SourceFailure
import app.hakusan.extensions.SourceIdentity
import app.hakusan.extensions.SourceResult
import app.hakusan.extensions.SourceTitleDetails
import app.hakusan.extensions.SourceTitleKey

/** An operation of [DeterministicSource] that can be made unavailable. */
internal enum class DeterministicOperation {
  BROWSE,
  DETAILS,
  REFRESH_CHAPTERS,
  CONTENT,
}

/**
 * The Debug backend, derived only from [DeterministicFixture].
 *
 * Each operation in [unavailable] returns
 * [Unavailable][SourceFailure.Unavailable] on every call. An unknown key of
 * this source returns it too. The backend reads no clock, randomness, file,
 * network, or Android API and keeps no state, so equal calls return equal
 * results.
 */
internal class DeterministicSource(unavailable: Set<DeterministicOperation>) :
  SourceBackend {
  private val unavailableOperations = unavailable.toSet()

  override val identity: SourceIdentity = DeterministicFixture.identity

  override val displayName: String = DeterministicFixture.displayName

  override suspend fun browse():
    SourceResult<SourceBrowseResult, SourceBrowseFailure> {
    if (DeterministicOperation.BROWSE in unavailableOperations) {
      return unavailableResult()
    }

    return SourceBrowseResult.create(
      identity,
      listOf(DeterministicFixture.title),
    )
  }

  /**
   * Returns the details of the fixture title.
   *
   * @throws IllegalArgumentException if this source does not own [title].
   */
  override suspend fun details(
    title: SourceTitleKey
  ): SourceResult<SourceTitleDetails, SourceDetailsFailure> {
    requireOwned(title)

    if (
      DeterministicOperation.DETAILS in unavailableOperations ||
        title != DeterministicFixture.title.key
    ) {
      return unavailableResult()
    }

    val details =
      SourceTitleDetails(
        DeterministicFixture.title,
        DeterministicFixture.description,
      )

    return SourceResult.Success(details)
  }

  /**
   * Completes [request] with the fixture chapters in canonical order.
   *
   * @throws IllegalArgumentException if this source does not own the requested
   *   title.
   */
  override suspend fun refreshChapters(
    request: ChapterRefreshRequest
  ): ChapterRefreshCompletion {
    requireOwned(request.title)

    if (
      DeterministicOperation.REFRESH_CHAPTERS in unavailableOperations ||
        request.title != DeterministicFixture.title.key
    ) {
      return ChapterRefreshCompletion.failed(request, SourceFailure.Unavailable)
    }

    return ChapterRefreshCompletion.completed(
      request = request,
      status = ChapterSequenceStatus.COMPLETE,
      chapters = DeterministicFixture.chapters.map { it.chapter },
    )
  }

  /**
   * Returns the content units of one fixture chapter.
   *
   * @throws IllegalArgumentException if this source does not own [chapter].
   */
  override suspend fun content(
    chapter: SourceChapterKey
  ): SourceResult<SourceChapterContent, SourceContentFailure> {
    requireOwned(chapter.title)

    val fixtureChapter =
      DeterministicFixture.chapters.firstOrNull { it.chapter.key == chapter }

    if (
      DeterministicOperation.CONTENT in unavailableOperations ||
        fixtureChapter == null
    ) {
      return unavailableResult()
    }

    return SourceChapterContent.create(chapter, fixtureChapter.units)
  }

  private fun requireOwned(title: SourceTitleKey) {
    require(title.source == identity) {
      "The key belongs to a source other than this backend."
    }
  }

  private fun unavailableResult():
    SourceResult.Failure<SourceFailure.Unavailable> =
    SourceResult.Failure(SourceFailure.Unavailable)
}
