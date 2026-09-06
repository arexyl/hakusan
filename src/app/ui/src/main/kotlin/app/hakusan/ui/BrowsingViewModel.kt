package app.hakusan.ui

import app.hakusan.sdk.BrowseScreen
import app.hakusan.sdk.BrowseScreenFailure
import app.hakusan.sdk.BrowseScreenResult
import app.hakusan.sdk.BrowseScreenService
import app.hakusan.sdk.CatalogScreen
import app.hakusan.sdk.ContinueSelectionResult
import app.hakusan.sdk.ContinueSelectionService
import app.hakusan.sdk.ContinueTarget
import app.hakusan.sdk.ContinueUnavailableReason
import app.hakusan.sdk.DetailsScreenFailure
import app.hakusan.sdk.DetailsScreenResult
import app.hakusan.sdk.ScreenTitleId
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

internal data class DetailsOwnerKey(
  val destination: PrimaryDestination,
  val route: TitleDetailsRoute,
)

class BrowsingViewModel(
  private val browseService: BrowseScreenService,
  private val detailsService: TitleDetailsScreenService,
  private val continueService: ContinueSelectionService,
) : ViewModel() {
  internal val catalog: CatalogScreen = browseService.catalog()

  private val browseStates =
    mutableMapOf<SourceBrowseRoute, ScreenLoadOwner<
      BrowseScreen,
      BrowseScreenFailure,
      >>()
  private val browseJobs = mutableMapOf<SourceBrowseRoute, Job>()
  private val detailsStates =
    mutableMapOf<DetailsOwnerKey, ScreenLoadOwner<
      TitleDetailsScreen,
      DetailsScreenFailure,
      >>()
  private val detailsJobs = mutableMapOf<DetailsOwnerKey, Job>()
  private val continueStates =
    mutableMapOf<DetailsOwnerKey, ContinueActionOwner>()
  private val continueJobs = mutableMapOf<DetailsOwnerKey, Job>()

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
    owner: DetailsOwnerKey,
  ): ScreenLoadOwner<TitleDetailsScreen, DetailsScreenFailure> =
    detailsStates.getOrPut(owner, ::ScreenLoadOwner)

  internal fun ensureDetails(key: DetailsOwnerKey) {
    val owner = details(key)
    if (key !in detailsJobs) {
      launchDetails(key, owner, owner.revision)
    }
  }

  internal fun retryDetails(key: DetailsOwnerKey) {
    val owner = details(key)
    resetContinue(key)
    detailsJobs.remove(key)?.cancel()
    launchDetails(key, owner, owner.retry())
  }

  internal fun continueAction(
    owner: DetailsOwnerKey,
  ): ContinueActionOwner =
    continueStates.getOrPut(owner, ::ContinueActionOwner)

  internal fun selectContinue(key: DetailsOwnerKey) {
    val detailsState = detailsStates[key]?.state
    if (detailsState !is ScreenLoadState.Loaded) {
      return
    }
    val owner = continueAction(key)
    if (owner.state == ContinueActionState.Selecting) {
      return
    }
    check(key !in continueJobs) {
      "A non-selecting Continue action must not retain an active job."
    }
    launchContinue(
      key = key,
      owner = owner,
      titleId = detailsState.content.id,
      revision = owner.startSelection(),
    )
  }

  internal fun discard(
    destination: PrimaryDestination,
    route: NavKey,
  ) {
    when (route) {
      is SourceBrowseRoute -> {
        browseJobs.remove(route)?.cancel()
        browseStates.remove(route)
      }

      is TitleDetailsRoute -> {
        val key = DetailsOwnerKey(destination, route)
        discardContinue(key)
        detailsJobs.remove(key)?.cancel()
        detailsStates.remove(key)
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
        val result = browseService.loadBrowse(
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
    key: DetailsOwnerKey,
    owner: ScreenLoadOwner<TitleDetailsScreen, DetailsScreenFailure>,
    revision: Long,
  ) {
    val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
      when (
        val result = detailsService.loadDetails(
          key.route.toScreenTitleKey(),
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
    detailsJobs[key] = job
    job.start()
  }

  private fun launchContinue(
    key: DetailsOwnerKey,
    owner: ContinueActionOwner,
    titleId: ScreenTitleId,
    revision: Long,
  ) {
    lateinit var job: Job
    job = viewModelScope.launch(start = CoroutineStart.LAZY) {
      try {
        when (
          val result = continueService.selectContinue(titleId)
        ) {
          is ContinueSelectionResult.Selected -> owner.publishSelected(
            expectedRevision = revision,
            target = result.target,
          )

          is ContinueSelectionResult.Unavailable ->
            owner.publishUnavailable(
              expectedRevision = revision,
              reason = result.reason,
            )

          ContinueSelectionResult.TitleNotFound ->
            owner.publishTitleNotFound(revision)
        }
      } finally {
        continueJobs.remove(key, job)
      }
    }
    continueJobs[key] = job
    job.start()
  }

  private fun resetContinue(key: DetailsOwnerKey) {
    continueStates[key]?.clear()
    continueJobs.remove(key)?.cancel()
  }

  private fun discardContinue(key: DetailsOwnerKey) {
    continueStates.remove(key)?.clear()
    continueJobs.remove(key)?.cancel()
  }

  companion object {
    fun factory(
      browseService: () -> BrowseScreenService,
      detailsService: () -> TitleDetailsScreenService,
      continueService: () -> ContinueSelectionService,
    ): ViewModelProvider.Factory = viewModelFactory {
      initializer {
        BrowsingViewModel(
          browseService = browseService(),
          detailsService = detailsService(),
          continueService = continueService(),
        )
      }
    }
  }
}

internal sealed interface ContinueActionState {
  data object Idle : ContinueActionState

  data object Selecting : ContinueActionState

  data class Selected(
    val target: ContinueTarget,
  ) : ContinueActionState

  data class Unavailable(
    val reason: ContinueUnavailableReason,
  ) : ContinueActionState

  data object TitleNotFound : ContinueActionState
}

internal class ContinueActionOwner {
  var state: ContinueActionState by mutableStateOf(ContinueActionState.Idle)
    private set

  var revision by mutableLongStateOf(0L)
    private set

  fun startSelection(): Long {
    advanceRevision()
    state = ContinueActionState.Selecting
    return revision
  }

  fun publishSelected(
    expectedRevision: Long,
    target: ContinueTarget,
  ): Boolean = publish(
    expectedRevision = expectedRevision,
    nextState = ContinueActionState.Selected(target),
  )

  fun publishUnavailable(
    expectedRevision: Long,
    reason: ContinueUnavailableReason,
  ): Boolean = publish(
    expectedRevision = expectedRevision,
    nextState = ContinueActionState.Unavailable(reason),
  )

  fun publishTitleNotFound(expectedRevision: Long): Boolean = publish(
    expectedRevision = expectedRevision,
    nextState = ContinueActionState.TitleNotFound,
  )

  fun clear() {
    advanceRevision()
    state = ContinueActionState.Idle
  }

  private fun publish(
    expectedRevision: Long,
    nextState: ContinueActionState,
  ): Boolean {
    if (
      expectedRevision != revision ||
      state != ContinueActionState.Selecting
    ) {
      return false
    }
    state = nextState
    return true
  }

  private fun advanceRevision() {
    check(revision < Long.MAX_VALUE) {
      "A Continue action exhausted its revision space."
    }
    revision += 1L
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
