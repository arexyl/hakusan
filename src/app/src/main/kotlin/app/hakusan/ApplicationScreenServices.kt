package app.hakusan

import app.hakusan.extensions.ChapterRefreshAcceptance
import app.hakusan.extensions.ChapterRefreshCompletion
import app.hakusan.extensions.ChapterRefreshGate
import app.hakusan.extensions.ChapterRefreshRequest
import app.hakusan.extensions.SourceFailure
import app.hakusan.extensions.SourceResult
import app.hakusan.extensions.SourceTitleDetails
import app.hakusan.sdk.AddToLibraryScreenFailure
import app.hakusan.sdk.AddToLibraryScreenResult
import app.hakusan.sdk.BrowseScreen
import app.hakusan.sdk.BrowseScreenFailure
import app.hakusan.sdk.BrowseScreenResult
import app.hakusan.sdk.BrowseScreenService
import app.hakusan.sdk.BrowseTitleItem
import app.hakusan.sdk.CatalogScreen
import app.hakusan.sdk.ContinueSelectionFailure
import app.hakusan.sdk.ContinueSelectionResult
import app.hakusan.sdk.DetailsScreenFailure
import app.hakusan.sdk.DetailsScreenResult
import app.hakusan.sdk.LibraryScreen
import app.hakusan.sdk.LibraryScreenService
import app.hakusan.sdk.ScreenSourceId
import app.hakusan.sdk.ScreenTitleId
import app.hakusan.sdk.ScreenTitleKey
import app.hakusan.sdk.TitleDetailsScreen
import app.hakusan.sdk.TitleDetailsScreenService
import app.hakusan.titles.ChapterReconciliationFailure
import app.hakusan.titles.ChapterReconciliationResult
import app.hakusan.titles.LibraryAddFailure
import app.hakusan.titles.LibraryAddResult
import app.hakusan.titles.LibraryShelfState
import app.hakusan.titles.TitleId
import app.hakusan.titles.TitleReadingProgress
import app.hakusan.titles.Titles
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex

@Inject
@SingleIn(ApplicationScope::class)
internal class ApplicationScreenServices(
  private val sourceRegistry: SourceRegistry,
  private val titles: Titles,
) : BrowseScreenService,
  TitleDetailsScreenService,
  LibraryScreenService {
  private val refreshByTitle =
    ConcurrentHashMap<ScreenTitleKey, TitleRefreshCoordinator>()

  override fun catalog(): CatalogScreen = sourceRegistry.catalog

  override suspend fun loadBrowse(
    sourceId: ScreenSourceId,
  ): BrowseScreenResult {
    val registration = sourceRegistry.find(sourceId)
      ?: return BrowseScreenResult.Failure(
        BrowseScreenFailure.SourceNotFound,
      )
    val backend = registration.backend
    return when (val result = backend.browse()) {
      is SourceResult.Failure -> BrowseScreenResult.Failure(
        when (result.error) {
          SourceFailure.Unavailable -> BrowseScreenFailure.SourceUnavailable
          else -> BrowseScreenFailure.InvalidObservation
        },
      )

      is SourceResult.Success -> {
        val observation = result.value
        if (observation.source != backend.identity) {
          return BrowseScreenResult.Failure(
            BrowseScreenFailure.InvalidObservation,
          )
        }
        BrowseScreenResult.Success(
          BrowseScreen.of(
            source = registration.catalogItem,
            titles = observation.titles.map { title ->
              BrowseTitleItem(
                key = title.key.toScreenKey(),
                displayName = title.displayName,
              )
            },
          ),
        )
      }
    }
  }

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
    val coordinator = refreshByTitle.computeIfAbsent(titleKey) {
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
      is CoordinatedChapterLoad.Failure ->
        DetailsScreenResult.Failure(result.error)

      CoordinatedChapterLoad.RejectedNotCurrent ->
        DetailsScreenResult.RejectedNotCurrent

      is CoordinatedChapterLoad.Success -> DetailsScreenResult.Success(
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

  override fun observeLibrary(): Flow<LibraryScreen> = channelFlow {
    titles.observeLibraryShelves().collectLatest { state ->
      state.observeProgress().collect { screen ->
        send(screen)
      }
    }
  }.distinctUntilChanged()

  private fun LibraryShelfState.observeProgress(): Flow<LibraryScreen> {
    val orderedTitles = CheckpointLibraryOrder.titles(titlesById.values)
    if (orderedTitles.isEmpty()) {
      return flowOf(toLibraryScreen(emptyMap()))
    }
    val observations = orderedTitles.map { title ->
      titles.observeReadingProgress(title.id).map { progress ->
        title.id to checkNotNull(progress) {
          "A Library title must retain readable title progress."
        }
      }
    }
    return combine(observations) { values ->
      toLibraryScreen(values.toMap())
    }
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
  ): CoordinatedChapterLoad {
    reconciliation.lock()
    try {
      return when (val acceptance = gate.accept(completion)) {
        ChapterRefreshAcceptance.RejectedNotCurrent ->
          CoordinatedChapterLoad.RejectedNotCurrent

        is ChapterRefreshAcceptance.Accepted -> when (
          val result = acceptance.result
        ) {
          is SourceResult.Failure -> CoordinatedChapterLoad.Failure(
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
    snapshot: app.hakusan.titles.ReconcileChapterSnapshot,
  ): CoordinatedChapterLoad = when (
    val result = titles.reconcileChapterSnapshot(snapshot)
  ) {
    is ChapterReconciliationResult.Failure -> when (result.error) {
      ChapterReconciliationFailure.TitleNotFound ->
        CoordinatedChapterLoad.Failure(
          DetailsScreenFailure.LocalTitleNotFound,
        )
    }

    is ChapterReconciliationResult.Success -> {
      val progress = titles.observeReadingProgress(titleId).first()
        ?: return CoordinatedChapterLoad.Failure(
          DetailsScreenFailure.LocalTitleNotFound,
        )
      CoordinatedChapterLoad.Success(progress)
    }
  }
}

private sealed interface CoordinatedChapterLoad {
  data class Success(
    val progress: TitleReadingProgress,
  ) : CoordinatedChapterLoad

  data class Failure(
    val error: DetailsScreenFailure,
  ) : CoordinatedChapterLoad

  data object RejectedNotCurrent : CoordinatedChapterLoad
}
