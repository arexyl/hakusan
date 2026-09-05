package app.hakusan

import app.hakusan.extensions.ChapterRefreshAcceptance
import app.hakusan.extensions.ChapterRefreshCompletion
import app.hakusan.extensions.ChapterRefreshGate
import app.hakusan.extensions.ChapterRefreshRequest
import app.hakusan.extensions.SourceFailure
import app.hakusan.extensions.SourceResult
import app.hakusan.sdk.AddToLibraryScreenFailure
import app.hakusan.sdk.AddToLibraryScreenResult
import app.hakusan.sdk.ContinueSelectionFailure
import app.hakusan.sdk.ContinueSelectionResult
import app.hakusan.sdk.DetailsScreenFailure
import app.hakusan.sdk.DetailsScreenResult
import app.hakusan.sdk.ScreenTitleId
import app.hakusan.sdk.ScreenTitleKey
import app.hakusan.sdk.TitleDetailsScreenService
import app.hakusan.titles.ChapterReconciliationFailure
import app.hakusan.titles.ChapterReconciliationResult
import app.hakusan.titles.LibraryAddFailure
import app.hakusan.titles.LibraryAddResult
import app.hakusan.titles.ReconcileChapterSnapshot
import app.hakusan.titles.TitleId
import app.hakusan.titles.TitleReadingProgress
import app.hakusan.titles.Titles
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex

@Inject
@SingleIn(AppScope::class)
internal class TitleDetailsScreenAdapter(
  private val sourceRegistry: SourceRegistry,
  private val titles: Titles,
) : TitleDetailsScreenService {
  private val coordinators =
    ConcurrentHashMap<ScreenTitleKey, TitleRefreshCoordinator>()

  override suspend fun loadDetails(
    titleKey: ScreenTitleKey,
  ): DetailsScreenResult {
    val registration = sourceRegistry.find(titleKey.sourceId)
      ?: return DetailsScreenResult.Failure(
        DetailsScreenFailure.SourceNotFound,
      )
    val backend = registration.backend
    val sourceTitleKey = titleKey.toSourceKey()
    val details = when (val result = backend.details(sourceTitleKey)) {
      is SourceResult.Failure -> {
        val failure = when (result.error) {
          SourceFailure.Unavailable -> DetailsScreenFailure.DetailsUnavailable
          else -> DetailsScreenFailure.InvalidTitleObservation
        }
        return DetailsScreenResult.Failure(failure)
      }

      is SourceResult.Success -> result.value
    }
    if (details.title.key != sourceTitleKey) {
      return DetailsScreenResult.Failure(
        DetailsScreenFailure.InvalidTitleObservation,
      )
    }

    val titleId = titles.reconcileSourceTitle(details.toReconcileTitle())
    val coordinator = coordinators.computeIfAbsent(titleKey) {
      TitleRefreshCoordinator(sourceTitleKey)
    }
    val request = coordinator.issue()
    val completion = backend.refreshChapters(request)
    return when (
      val result = coordinator.acceptAndRead(
        completion = completion,
        titles = titles,
        titleId = titleId,
      )
    ) {
      is ChapterLoadResult.Failure ->
        DetailsScreenResult.Failure(result.error)

      ChapterLoadResult.RejectedNotCurrent ->
        DetailsScreenResult.RejectedNotCurrent

      is ChapterLoadResult.Success -> DetailsScreenResult.Success(
        result.progress.toDetailsScreen(
          sourceDisplayName = registration.catalogItem.displayName,
          details = details,
        ),
      )
    }
  }

  override suspend fun addToLibrary(
    titleId: ScreenTitleId,
  ): AddToLibraryScreenResult = when (
    val result = titles.addToLibrary(TitleId(titleId.value))
  ) {
    is LibraryAddResult.Success -> AddToLibraryScreenResult.Success
    is LibraryAddResult.CategorySelectionRequired ->
      AddToLibraryScreenResult.CategorySelectionRequired

    is LibraryAddResult.Failure -> when (result.error) {
      LibraryAddFailure.TitleNotFound -> AddToLibraryScreenResult.Failure(
        AddToLibraryScreenFailure.TitleNotFound,
      )

      is LibraryAddFailure.CategoriesNotFound -> error(
        "Automatic Library Add cannot select missing categories.",
      )
    }
  }

  override suspend fun selectContinue(
    titleId: ScreenTitleId,
  ): ContinueSelectionResult {
    val progress = titles.observeReadingProgress(TitleId(titleId.value)).first()
      ?: return ContinueSelectionResult.Failure(
        ContinueSelectionFailure.TitleNotFound,
      )
    return progress.toContinueState().toSelectionResult()
  }
}

private class TitleRefreshCoordinator(
  titleKey: app.hakusan.extensions.SourceTitleKey,
) {
  private val gate = ChapterRefreshGate(titleKey)
  private val reconciliation = Mutex()

  fun issue(): ChapterRefreshRequest = gate.issue()

  suspend fun acceptAndRead(
    completion: ChapterRefreshCompletion,
    titles: Titles,
    titleId: TitleId,
  ): ChapterLoadResult {
    reconciliation.lock()
    try {
      return when (val acceptance = gate.accept(completion)) {
        ChapterRefreshAcceptance.RejectedNotCurrent ->
          ChapterLoadResult.RejectedNotCurrent

        is ChapterRefreshAcceptance.Accepted -> when (
          val result = acceptance.result
        ) {
          is SourceResult.Failure -> ChapterLoadResult.Failure(
            when (result.error) {
              SourceFailure.Unavailable ->
                DetailsScreenFailure.ChaptersUnavailable

              is SourceFailure.InvalidChapterSnapshot ->
                DetailsScreenFailure.InvalidChapterSnapshot

              else -> DetailsScreenFailure.InvalidChapterSnapshot
            },
          )

          is SourceResult.Success -> reconcileAndRead(
            titles = titles,
            titleId = titleId,
            snapshot = result.value.toReconcileSnapshot(),
          )
        }
      }
    } finally {
      reconciliation.unlock()
    }
  }

  private suspend fun reconcileAndRead(
    titles: Titles,
    titleId: TitleId,
    snapshot: ReconcileChapterSnapshot,
  ): ChapterLoadResult = when (
    val result = titles.reconcileChapterSnapshot(snapshot)
  ) {
    is ChapterReconciliationResult.Failure -> when (result.error) {
      ChapterReconciliationFailure.TitleNotFound ->
        ChapterLoadResult.Failure(
          DetailsScreenFailure.LocalTitleNotFound,
        )
    }

    is ChapterReconciliationResult.Success -> {
      val progress = titles.observeReadingProgress(titleId).first()
        ?: return ChapterLoadResult.Failure(
          DetailsScreenFailure.LocalTitleNotFound,
        )
      ChapterLoadResult.Success(progress)
    }
  }
}

private sealed interface ChapterLoadResult {
  data class Success(
    val progress: TitleReadingProgress,
  ) : ChapterLoadResult

  data class Failure(
    val error: DetailsScreenFailure,
  ) : ChapterLoadResult

  data object RejectedNotCurrent : ChapterLoadResult
}
