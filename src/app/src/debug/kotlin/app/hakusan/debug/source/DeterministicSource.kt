package app.hakusan.debug.source

import app.hakusan.extensions.ChapterRefreshCompletion
import app.hakusan.extensions.ChapterRefreshRequest
import app.hakusan.extensions.ChapterSequenceStatus
import app.hakusan.extensions.SourceBackend
import app.hakusan.extensions.SourceBrowseResult
import app.hakusan.extensions.SourceChapter
import app.hakusan.extensions.SourceChapterContent
import app.hakusan.extensions.SourceChapterKey
import app.hakusan.extensions.SourceContentUnit
import app.hakusan.extensions.SourceContentUnitKind
import app.hakusan.extensions.SourceFailure
import app.hakusan.extensions.SourceIdentity
import app.hakusan.extensions.SourceResult
import app.hakusan.extensions.SourceTitle
import app.hakusan.extensions.SourceTitleDetails
import app.hakusan.extensions.SourceTitleKey

internal enum class UnavailableOperation {
  NONE,
  BROWSE,
  DETAILS,
  CHAPTERS,
  CONTENT,
}

/** An immutable, in-memory source used only by the Debug application. */
internal class DeterministicSource(
  private val unavailableOperation: UnavailableOperation =
    UnavailableOperation.NONE,
) : SourceBackend {
  override val identity = SourceIdentity("app.hakusan.debug.source")

  override val displayName = "Deterministic source"

  private val title = SourceTitle(
    key = SourceTitleKey(identity, "canonical-order-fixture"),
    displayName = "Canonical Order Fixture",
  )
  private val titleDetails = SourceTitleDetails(
    title = title,
    description = "Deterministic content for Hakusan checkpoint verification.",
  )

  private val opening = SourceChapter(
    key = SourceChapterKey(title.key, "opening"),
    displayName = "Chapter 10",
  )
  private val middle = SourceChapter(
    key = SourceChapterKey(title.key, "middle"),
    displayName = "Chapter 2",
  )
  private val final = SourceChapter(
    key = SourceChapterKey(title.key, "final"),
    displayName = "Chapter 1",
  )
  private val chapters = listOf(opening, middle, final)

  private val contentByChapter = mapOf(
    opening.key to listOf(
      SourceContentUnit(
        kind = SourceContentUnitKind.PAGE,
        reference = "opening-page-10",
      ),
      SourceContentUnit(
        kind = SourceContentUnitKind.PAGE,
        reference = "opening-page-2",
      ),
      SourceContentUnit(
        kind = SourceContentUnitKind.PAGE,
        reference = "opening-page-1",
      ),
    ),
    middle.key to listOf(
      SourceContentUnit(
        kind = SourceContentUnitKind.PROVIDER_SEGMENT,
        reference = "middle-segment-10",
      ),
      SourceContentUnit(
        kind = SourceContentUnitKind.PROVIDER_SEGMENT,
        reference = "middle-segment-2",
      ),
      SourceContentUnit(
        kind = SourceContentUnitKind.PROVIDER_SEGMENT,
        reference = "middle-segment-1",
      ),
    ),
    final.key to listOf(
      SourceContentUnit(
        kind = SourceContentUnitKind.PAGE,
        reference = "final-page",
      ),
    ),
  )

  override suspend fun browse(): SourceResult<SourceBrowseResult> {
    if (unavailableOperation == UnavailableOperation.BROWSE) {
      return unavailable()
    }
    return SourceBrowseResult.create(identity, listOf(title))
  }

  override suspend fun details(
    title: SourceTitleKey,
  ): SourceResult<SourceTitleDetails> {
    requireOwned(title)
    if (title != this.title.key) {
      return unavailable()
    }
    if (unavailableOperation == UnavailableOperation.DETAILS) {
      return unavailable()
    }
    return SourceResult.Success(titleDetails)
  }

  override suspend fun refreshChapters(
    request: ChapterRefreshRequest,
  ): ChapterRefreshCompletion {
    requireOwned(request.title)
    if (
      request.title != title.key ||
      unavailableOperation == UnavailableOperation.CHAPTERS
    ) {
      return ChapterRefreshCompletion.failed(
        request,
        SourceFailure.Unavailable,
      )
    }
    return ChapterRefreshCompletion.completed(
      request = request,
      status = ChapterSequenceStatus.COMPLETE,
      chapters = chapters,
    )
  }

  override suspend fun content(
    chapter: SourceChapterKey,
  ): SourceResult<SourceChapterContent> {
    requireOwned(chapter)
    if (chapter.title != title.key || chapter !in contentByChapter) {
      return unavailable()
    }
    if (unavailableOperation == UnavailableOperation.CONTENT) {
      return unavailable()
    }
    return SourceChapterContent.create(
      chapter = chapter,
      units = checkNotNull(contentByChapter[chapter]),
    )
  }

  private fun requireOwned(
    key: SourceTitleKey,
  ) {
    require(key.source == identity) {
      "Title does not belong to this source backend."
    }
  }

  private fun requireOwned(
    key: SourceChapterKey,
  ) {
    require(key.title.source == identity) {
      "Chapter does not belong to this source backend."
    }
  }

  private fun unavailable(): SourceResult.Failure =
    SourceResult.Failure(SourceFailure.Unavailable)
}
