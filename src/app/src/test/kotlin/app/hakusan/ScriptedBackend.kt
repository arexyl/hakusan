package app.hakusan

import app.hakusan.extensions.ChapterRefreshCompletion
import app.hakusan.extensions.ChapterRefreshRequest
import app.hakusan.extensions.SourceBackend
import app.hakusan.extensions.SourceBrowseFailure
import app.hakusan.extensions.SourceBrowseResult
import app.hakusan.extensions.SourceChapterContent
import app.hakusan.extensions.SourceChapterKey
import app.hakusan.extensions.SourceContentFailure
import app.hakusan.extensions.SourceDetailsFailure
import app.hakusan.extensions.SourceIdentity
import app.hakusan.extensions.SourceResult
import app.hakusan.extensions.SourceTitleDetails
import app.hakusan.extensions.SourceTitleKey
import org.junit.jupiter.api.Assertions.fail

private typealias BrowseOutcome =
  SourceResult<SourceBrowseResult, SourceBrowseFailure>

private typealias DetailsOutcome =
  SourceResult<SourceTitleDetails, SourceDetailsFailure>

private typealias ContentOutcome =
  SourceResult<SourceChapterContent, SourceContentFailure>

/**
 * A backend whose operations run the scripted lambdas of a test.
 *
 * Every operation that a test leaves unscripted fails the test. Each read of
 * [displayName] runs [onDisplayName].
 */
internal class ScriptedBackend(
  override val identity: SourceIdentity,
  private val onDisplayName: () -> String = { "Scripted" },
  private val onBrowse: suspend () -> BrowseOutcome = { unexpected("browse") },
  private val onDetails: suspend (SourceTitleKey) -> DetailsOutcome = {
    unexpected("details")
  },
  private val onRefreshChapters:
    suspend (ChapterRefreshRequest) -> ChapterRefreshCompletion =
    {
      unexpected("refreshChapters")
    },
  private val onContent: suspend (SourceChapterKey) -> ContentOutcome = {
    unexpected("content")
  },
) : SourceBackend {
  override val displayName: String
    get() = onDisplayName()

  override suspend fun browse(): BrowseOutcome = onBrowse()

  override suspend fun details(title: SourceTitleKey): DetailsOutcome =
    onDetails(title)

  override suspend fun refreshChapters(
    request: ChapterRefreshRequest
  ): ChapterRefreshCompletion = onRefreshChapters(request)

  override suspend fun content(chapter: SourceChapterKey): ContentOutcome =
    onContent(chapter)
}

private fun unexpected(operation: String): Nothing =
  fail("The backend operation $operation was not expected to run.")
