package app.hakusan

import app.hakusan.extensions.ChapterRefreshAcceptance
import app.hakusan.extensions.ChapterRefreshCompletion
import app.hakusan.extensions.ChapterRefreshGate
import app.hakusan.extensions.ChapterRefreshRequest
import app.hakusan.extensions.SourceFailure
import app.hakusan.extensions.SourceResult
import app.hakusan.extensions.SourceTitleDetails
import app.hakusan.extensions.SourceTitleKey
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
import kotlinx.coroutines.sync.withLock

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
    val coordinator = acquireCoordinator(titleKey, sourceTitleKey)
    try {
      val request = coordinator.issue()
      val details = when (val result = backend.details(sourceTitleKey)) {
        is SourceResult.Failure -> {
          val failure = when (result.error) {
            SourceFailure.Unavailable ->
              DetailsScreenFailure.DetailsUnavailable

            else -> DetailsScreenFailure.InvalidTitleObservation
          }
          return coordinator.finishBeforeChapters(
            request = request,
            result = DetailsScreenResult.Failure(failure),
          )
        }

        is SourceResult.Success -> result.value
      }
      if (details.title.key != sourceTitleKey) {
        return coordinator.finishBeforeChapters(
          request = request,
          result = DetailsScreenResult.Failure(
            DetailsScreenFailure.InvalidTitleObservation,
          ),
        )
      }
      if (!coordinator.isCurrent(request)) {
        return DetailsScreenResult.RejectedNotCurrent
      }

      val completion = backend.refreshChapters(request)
      return when (
        val result = coordinator.acceptAndRead(
          completion = completion,
          details = details,
          titles = titles,
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
    } finally {
      releaseCoordinator(titleKey, coordinator)
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

  private fun acquireCoordinator(
    titleKey: ScreenTitleKey,
    sourceTitleKey: SourceTitleKey,
  ): TitleRefreshCoordinator = checkNotNull(
    coordinators.compute(titleKey) { _, current ->
      (current ?: TitleRefreshCoordinator(sourceTitleKey)).also {
        it.retain()
      }
    },
  )

  private fun releaseCoordinator(
    titleKey: ScreenTitleKey,
    coordinator: TitleRefreshCoordinator,
  ) {
    coordinators.compute(titleKey) { _, current ->
      check(current === coordinator) {
        "A title refresh coordinator must retain its active owner."
      }
      if (coordinator.release()) null else coordinator
    }
  }
}

private class TitleRefreshCoordinator(
  titleKey: SourceTitleKey,
) {
  private val gate = ChapterRefreshGate(titleKey)
  private val state = Any()
  private val reconciliation = Mutex()
  private var currentRequest: ChapterRefreshRequest? = null
  private var activeLoads = 0

  fun retain() {
    check(activeLoads < Int.MAX_VALUE) {
      "Too many active loads for one title."
    }
    activeLoads += 1
  }

  /** Returns true when no load still owns this coordinator. */
  fun release(): Boolean {
    check(activeLoads > 0) {
      "A title refresh coordinator was released without an owner."
    }
    activeLoads -= 1
    return activeLoads == 0
  }

  fun issue(): ChapterRefreshRequest = synchronized(state) {
    gate.issue().also { currentRequest = it }
  }

  fun isCurrent(request: ChapterRefreshRequest): Boolean =
    synchronized(state) {
      currentRequest == request
    }

  suspend fun finishBeforeChapters(
    request: ChapterRefreshRequest,
    result: DetailsScreenResult,
  ): DetailsScreenResult = reconciliation.withLock {
    val accepted = synchronized(state) {
      if (currentRequest != request) {
        false
      } else {
        currentRequest = null
        true
      }
    }
    if (accepted) result else DetailsScreenResult.RejectedNotCurrent
  }

  suspend fun acceptAndRead(
    completion: ChapterRefreshCompletion,
    details: SourceTitleDetails,
    titles: Titles,
  ): ChapterLoadResult = reconciliation.withLock {
    val acceptance = synchronized(state) {
      if (currentRequest != completion.request) {
        ChapterRefreshAcceptance.RejectedNotCurrent
      } else {
        gate.accept(completion).also { result ->
          check(result is ChapterRefreshAcceptance.Accepted) {
            "The current title load must retain its refresh request."
          }
          currentRequest = null
        }
      }
    }
    when (acceptance) {
      ChapterRefreshAcceptance.RejectedNotCurrent ->
        ChapterLoadResult.RejectedNotCurrent

      is ChapterRefreshAcceptance.Accepted -> {
        val titleId = titles.reconcileSourceTitle(details.toReconcileTitle())
        when (val result = acceptance.result) {
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
