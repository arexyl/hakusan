package app.hakusan

import app.hakusan.titles.AcceptedTitleObservation
import app.hakusan.titles.ActualPositionResult
import app.hakusan.titles.ActualPositionUpdate
import app.hakusan.titles.ChapterBoundaryCompletion
import app.hakusan.titles.ChapterBoundaryResult
import app.hakusan.titles.ExplicitLibraryAddResult
import app.hakusan.titles.FinalChapterCompletion
import app.hakusan.titles.FinalCompletionResult
import app.hakusan.titles.LibraryAddResult
import app.hakusan.titles.LibraryCategorySelection
import app.hakusan.titles.LibrarySnapshot
import app.hakusan.titles.ProgressSnapshot
import app.hakusan.titles.TitleId
import app.hakusan.titles.Titles
import kotlinx.coroutines.flow.Flow
import org.junit.jupiter.api.Assertions.fail

/**
 * A [Titles] whose operations run the scripted lambdas of a test.
 *
 * Every operation that a test leaves unscripted fails the test. Scripts build
 * their `:titles` values with the `:titles` test fixtures.
 */
internal class FakeTitles(
  private val onReconcileTitle:
    suspend (AcceptedTitleObservation) -> ProgressSnapshot =
    {
      unexpected("reconcileTitle")
    },
  private val onAutomaticAdd: suspend (TitleId) -> LibraryAddResult = {
    unexpected("addToLibrary")
  },
  private val onExplicitAdd:
    suspend (TitleId, LibraryCategorySelection) -> ExplicitLibraryAddResult =
    { _, _ ->
      unexpected("addToLibrary with a selection")
    },
  private val onObserveLibrary: () -> Flow<LibrarySnapshot> = {
    unexpected("observeLibrary")
  },
  private val onObserveLibraryTitleIds: () -> Flow<Set<TitleId>> = {
    unexpected("observeLibraryTitleIds")
  },
  private val onReadProgress: suspend (TitleId) -> ProgressSnapshot? = {
    unexpected("readProgress")
  },
  private val onRecordActualPosition:
    suspend (ActualPositionUpdate) -> ActualPositionResult =
    {
      unexpected("recordActualPosition")
    },
  private val onCompleteChapterBoundary:
    suspend (ChapterBoundaryCompletion) -> ChapterBoundaryResult =
    {
      unexpected("completeChapterBoundary")
    },
  private val onCompleteFinalChapter:
    suspend (FinalChapterCompletion) -> FinalCompletionResult =
    {
      unexpected("completeFinalChapter")
    },
) : Titles {
  override suspend fun reconcileTitle(
    observation: AcceptedTitleObservation
  ): ProgressSnapshot = onReconcileTitle(observation)

  override suspend fun addToLibrary(titleId: TitleId): LibraryAddResult =
    onAutomaticAdd(titleId)

  override suspend fun addToLibrary(
    titleId: TitleId,
    selection: LibraryCategorySelection,
  ): ExplicitLibraryAddResult = onExplicitAdd(titleId, selection)

  override fun observeLibrary(): Flow<LibrarySnapshot> = onObserveLibrary()

  override fun observeLibraryTitleIds(): Flow<Set<TitleId>> =
    onObserveLibraryTitleIds()

  override suspend fun readProgress(titleId: TitleId): ProgressSnapshot? =
    onReadProgress(titleId)

  override suspend fun recordActualPosition(
    update: ActualPositionUpdate
  ): ActualPositionResult = onRecordActualPosition(update)

  override suspend fun completeChapterBoundary(
    completion: ChapterBoundaryCompletion
  ): ChapterBoundaryResult = onCompleteChapterBoundary(completion)

  override suspend fun completeFinalChapter(
    completion: FinalChapterCompletion
  ): FinalCompletionResult = onCompleteFinalChapter(completion)
}

private fun unexpected(operation: String): Nothing =
  fail("The Titles operation $operation was not expected to run.")
