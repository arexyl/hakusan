package app.hakusan.ui

import app.hakusan.sdk.BrowseScreen
import app.hakusan.sdk.BrowseScreenFailure
import app.hakusan.sdk.BrowseScreenResult
import app.hakusan.sdk.BrowseScreenService
import app.hakusan.sdk.CatalogScreen
import app.hakusan.sdk.DetailsScreenFailure
import app.hakusan.sdk.DetailsScreenResult
import app.hakusan.sdk.TitleDetailsScreen
import app.hakusan.sdk.TitleDetailsScreenService
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class CatalogPresentationModel(
  private val browseScreenService: BrowseScreenService,
  private val titleDetailsScreenService: TitleDetailsScreenService,
) : ViewModel() {
  internal val catalog: CatalogScreen = browseScreenService.catalog()

  private val browseStates =
    mutableMapOf<SourceBrowseRoute, ScreenLoadOwner<
      BrowseScreen,
      BrowseScreenFailure,
      >>()
  private val browseJobs = mutableMapOf<SourceBrowseRoute, Job>()
  private val detailsStates =
    mutableMapOf<TitleDetailsRoute, ScreenLoadOwner<
      TitleDetailsScreen,
      DetailsScreenFailure,
      >>()
  private val detailsJobs = mutableMapOf<TitleDetailsRoute, Job>()

  internal fun browse(
    route: SourceBrowseRoute,
  ): ScreenLoadOwner<BrowseScreen, BrowseScreenFailure> =
    browseStates.getOrPut(route, ::ScreenLoadOwner)

  internal fun ensureBrowse(route: SourceBrowseRoute) {
    val owner = browse(route)
    if (route !in browseJobs) {
      launchBrowse(route, owner, owner.revision)
    }
  }

  internal fun retryBrowse(route: SourceBrowseRoute) {
    val owner = browse(route)
    browseJobs.remove(route)?.cancel()
    launchBrowse(route, owner, owner.retry())
  }

  internal fun details(
    route: TitleDetailsRoute,
  ): ScreenLoadOwner<TitleDetailsScreen, DetailsScreenFailure> =
    detailsStates.getOrPut(route, ::ScreenLoadOwner)

  internal fun ensureDetails(route: TitleDetailsRoute) {
    val owner = details(route)
    if (route !in detailsJobs) {
      launchDetails(route, owner, owner.revision)
    }
  }

  internal fun retryDetails(route: TitleDetailsRoute) {
    val owner = details(route)
    detailsJobs.remove(route)?.cancel()
    launchDetails(route, owner, owner.retry())
  }

  internal fun discard(route: NavKey) {
    when (route) {
      is SourceBrowseRoute -> {
        browseJobs.remove(route)?.cancel()
        browseStates.remove(route)
      }

      is TitleDetailsRoute -> {
        detailsJobs.remove(route)?.cancel()
        detailsStates.remove(route)
      }

      else -> Unit
    }
  }

  private fun launchBrowse(
    route: SourceBrowseRoute,
    owner: ScreenLoadOwner<BrowseScreen, BrowseScreenFailure>,
    revision: Long,
  ) {
    val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
      when (
        val result = browseScreenService.loadBrowse(
          route.toScreenSourceId(),
        )
      ) {
        is BrowseScreenResult.Success -> owner.publishContent(
          expectedRevision = revision,
          content = result.screen,
        )

        is BrowseScreenResult.Failure -> owner.publishFailure(
          expectedRevision = revision,
          failure = result.error,
        )
      }
    }
    browseJobs[route] = job
    job.start()
  }

  private fun launchDetails(
    route: TitleDetailsRoute,
    owner: ScreenLoadOwner<TitleDetailsScreen, DetailsScreenFailure>,
    revision: Long,
  ) {
    val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
      when (
        val result = titleDetailsScreenService.loadDetails(
          route.toScreenTitleKey(),
        )
      ) {
        is DetailsScreenResult.Success -> owner.publishContent(
          expectedRevision = revision,
          content = result.screen,
        )

        is DetailsScreenResult.Failure -> owner.publishFailure(
          expectedRevision = revision,
          failure = result.error,
        )

        DetailsScreenResult.RejectedNotCurrent ->
          owner.publishSuperseded(revision)
      }
    }
    detailsJobs[route] = job
    job.start()
  }

  companion object {
    fun factory(
      browseScreenService: () -> BrowseScreenService,
      titleDetailsScreenService: () -> TitleDetailsScreenService,
    ): ViewModelProvider.Factory = viewModelFactory {
      initializer {
        CatalogPresentationModel(
          browseScreenService = browseScreenService(),
          titleDetailsScreenService = titleDetailsScreenService(),
        )
      }
    }
  }
}

internal sealed interface ScreenLoadState<out Content, out Failure> {
  data object Loading : ScreenLoadState<Nothing, Nothing>

  data object Superseded : ScreenLoadState<Nothing, Nothing>

  data class Loaded<Content>(
    val content: Content,
  ) : ScreenLoadState<Content, Nothing>

  data class Failed<Failure>(
    val failure: Failure,
  ) : ScreenLoadState<Nothing, Failure>
}

internal class ScreenLoadOwner<Content, Failure> {
  var state: ScreenLoadState<Content, Failure> by mutableStateOf(
    ScreenLoadState.Loading,
  )
    private set

  var revision by mutableLongStateOf(0L)
    private set

  fun retry(): Long {
    check(revision < Long.MAX_VALUE) {
      "A screen load exhausted its revision space."
    }
    revision += 1L
    state = ScreenLoadState.Loading
    return revision
  }

  fun publishContent(
    expectedRevision: Long,
    content: Content,
  ): Boolean = publish(
    expectedRevision = expectedRevision,
    nextState = ScreenLoadState.Loaded(content),
  )

  fun publishFailure(
    expectedRevision: Long,
    failure: Failure,
  ): Boolean = publish(
    expectedRevision = expectedRevision,
    nextState = ScreenLoadState.Failed(failure),
  )

  fun publishSuperseded(expectedRevision: Long): Boolean = publish(
    expectedRevision = expectedRevision,
    nextState = ScreenLoadState.Superseded,
  )

  private fun publish(
    expectedRevision: Long,
    nextState: ScreenLoadState<Content, Failure>,
  ): Boolean {
    if (
      expectedRevision != revision ||
      state !is ScreenLoadState.Loading
    ) {
      return false
    }
    state = nextState
    return true
  }
}
