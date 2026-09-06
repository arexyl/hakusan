package app.hakusan

import app.hakusan.sdk.AddToLibraryScreenResult
import app.hakusan.sdk.BrowseScreen
import app.hakusan.sdk.BrowseScreenFailure
import app.hakusan.sdk.BrowseScreenResult
import app.hakusan.sdk.BrowseScreenService
import app.hakusan.sdk.BrowseTitleItem
import app.hakusan.sdk.CatalogScreen
import app.hakusan.sdk.CatalogSourceItem
import app.hakusan.sdk.ContinueSelectionResult
import app.hakusan.sdk.ContinueState
import app.hakusan.sdk.ContinueUnavailableReason
import app.hakusan.sdk.DetailsScreenFailure
import app.hakusan.sdk.DetailsScreenResult
import app.hakusan.sdk.ScreenSourceId
import app.hakusan.sdk.ScreenTitleId
import app.hakusan.sdk.ScreenTitleKey
import app.hakusan.sdk.TitleDetailsScreen
import app.hakusan.sdk.TitleDetailsScreenService
import app.hakusan.ui.CatalogPresentationModel
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelProvider
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HakusanCatalogStatesAndroidTest {
  @get:Rule
  val compose = createAndroidComposeRule<HakusanActivity>()

  @Test
  fun rendersBrowseAndDetailsLoadingFailureRetryAndEmptyChapters() {
    val browse = ControlledBrowseScreenService(catalog(SOURCE))
    val details = ControlledTitleDetailsScreenService()
    installModel(browse, details)

    compose.onNodeWithContentDescription("Catalog").performClick()
    compose.onNodeWithText(SOURCE.displayName).performClick()
    compose.onNodeWithText("Loading titles").assertExists()
    compose.onNode(
      hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate),
    ).assertExists()
    awaitRequests(browse.requests, 1)

    browse.complete(
      BrowseScreenResult.Failure(BrowseScreenFailure.SourceUnavailable),
    )
    compose.onNodeWithText("Unable to load titles").assertExists()
    compose.onNodeWithText("Retry").performClick()
    compose.onNodeWithText("Loading titles").assertExists()
    awaitRequests(browse.requests, 2)
    assertEquals(listOf(SOURCE.id, SOURCE.id), browse.requests)

    browse.complete(BrowseScreenResult.Success(BROWSE_WITH_TITLE))
    compose.onNodeWithText(TITLE.displayName).performClick()
    compose.onNodeWithText("Loading title").assertExists()
    awaitRequests(details.requests, 1)

    details.complete(
      DetailsScreenResult.Failure(
        DetailsScreenFailure.ChaptersUnavailable,
      ),
    )
    compose.onNodeWithText("Unable to load title").assertExists()
    compose.onNodeWithText("Retry").performClick()
    compose.onNodeWithText("Loading title").assertExists()
    awaitRequests(details.requests, 2)
    assertEquals(listOf(TITLE.key, TITLE.key), details.requests)

    details.complete(DetailsScreenResult.Success(EMPTY_DETAILS))
    compose.onNodeWithText("Details without chapters.").assertExists()
    compose.onNodeWithText("No chapters available").assertExists()
    compose.onNodeWithText("Like").assertExists()
    compose.onNodeWithText("Continue").assertIsNotEnabled()
    compose.onNodeWithText(
      "No chapter is available for Continue.",
    ).assertExists()
  }

  @Test
  fun rendersEmptyCatalog() {
    val emptyCatalog = ControlledBrowseScreenService(
      CatalogScreen.of(emptyList()),
    )
    installModel(emptyCatalog, ControlledTitleDetailsScreenService())

    compose.onNodeWithContentDescription("Catalog").performClick()
    compose.onNodeWithText("No sources available").assertExists()
    compose.onNodeWithText("No titles available").assertDoesNotExist()
  }

  @Test
  fun rendersEmptyBrowse() {
    val emptyBrowse = ControlledBrowseScreenService(catalog(SOURCE))
    installModel(emptyBrowse, ControlledTitleDetailsScreenService())
    compose.onNodeWithContentDescription("Catalog").performClick()
    compose.onNodeWithText(SOURCE.displayName).performClick()
    awaitRequests(emptyBrowse.requests, 1)
    emptyBrowse.complete(
      BrowseScreenResult.Success(
        BrowseScreen.of(SOURCE, emptyList()),
      ),
    )

    compose.onNodeWithText("No titles available").assertExists()
    compose.onNodeWithText("No sources available").assertDoesNotExist()
  }

  @Test
  fun rendersAccessibleFallbacksForBlankDisplayMetadata() {
    val blankSource = CatalogSourceItem(
      id = ScreenSourceId("blank.source"),
      displayName = " \t",
    )
    val blankTitle = BrowseTitleItem(
      key = ScreenTitleKey(blankSource.id, "blank-title"),
      displayName = "",
    )
    val browse = ControlledBrowseScreenService(catalog(blankSource))
    val details = ControlledTitleDetailsScreenService()
    installModel(browse, details)

    compose.onNodeWithContentDescription("Catalog").performClick()
    compose.onNodeWithText("Unnamed source").performClick()
    awaitRequests(browse.requests, 1)
    browse.complete(
      BrowseScreenResult.Success(
        BrowseScreen.of(blankSource, listOf(blankTitle)),
      ),
    )
    compose.onNodeWithText("Unnamed title").performClick()
    awaitRequests(details.requests, 1)
    details.complete(
      DetailsScreenResult.Success(
        emptyDetails(
          source = blankSource,
          title = blankTitle,
        ),
      ),
    )

    compose.onNodeWithText("Unnamed title").assertExists()
    compose.onNodeWithText("Unnamed source").assertExists()
  }

  @Test
  fun currentRejectedDetailsOffersNeutralRetry() {
    val browse = ControlledBrowseScreenService(catalog(SOURCE))
    val details = ControlledTitleDetailsScreenService()
    installModel(browse, details)

    compose.onNodeWithContentDescription("Catalog").performClick()
    compose.onNodeWithText(SOURCE.displayName).performClick()
    awaitRequests(browse.requests, 1)
    browse.complete(BrowseScreenResult.Success(BROWSE_WITH_TITLE))
    compose.onNodeWithText(TITLE.displayName).performClick()
    awaitRequests(details.requests, 1)

    details.complete(DetailsScreenResult.RejectedNotCurrent)
    compose.onNodeWithText(
      "This content is loading elsewhere",
    ).assertExists()
    compose.onNodeWithText("Unable to load title").assertDoesNotExist()
    compose.onNodeWithText("Retry").performClick()
    awaitRequests(details.requests, 2)
    details.complete(DetailsScreenResult.Success(EMPTY_DETAILS))

    compose.onNodeWithText("No chapters available").assertExists()
  }

  @Test
  fun poppedPendingBrowseCannotRestoreItsScreen() {
    val browse = ControlledBrowseScreenService(catalog(SOURCE))
    installModel(browse, ControlledTitleDetailsScreenService())

    compose.onNodeWithContentDescription("Catalog").performClick()
    compose.onNodeWithText(SOURCE.displayName).performClick()
    compose.onNodeWithText("Loading titles").assertExists()
    awaitRequests(browse.requests, 1)
    compose.onNodeWithText("Back").performClick()
    awaitRequests(browse.cancellations, 1)

    browse.complete(BrowseScreenResult.Success(BROWSE_WITH_TITLE))
    compose.onNodeWithText("Sources").assertExists()
    compose.onNodeWithText(TITLE.displayName).assertDoesNotExist()
  }

  @Test
  fun pendingBrowseContinuesAcrossDestinationSwitch() {
    val browse = ControlledBrowseScreenService(catalog(SOURCE))
    installModel(browse, ControlledTitleDetailsScreenService())

    compose.onNodeWithContentDescription("Catalog").performClick()
    compose.onNodeWithText(SOURCE.displayName).performClick()
    awaitRequests(browse.requests, 1)
    compose.onNodeWithContentDescription("Library").performClick()

    browse.complete(BrowseScreenResult.Success(BROWSE_WITH_TITLE))
    compose.onNodeWithContentDescription("Catalog").performClick()
    compose.onNodeWithText(TITLE.displayName).assertExists()
    assertEquals(1, browse.requests.size)
    assertTrue(browse.cancellations.isEmpty())
  }

  @Test
  fun recreationRetainsBrowseContentWithoutAnotherRequest() {
    val browse = ControlledBrowseScreenService(catalog(SOURCE))
    installModel(browse, ControlledTitleDetailsScreenService())

    compose.onNodeWithContentDescription("Catalog").performClick()
    compose.onNodeWithText(SOURCE.displayName).performClick()
    awaitRequests(browse.requests, 1)
    browse.complete(BrowseScreenResult.Success(BROWSE_WITH_TITLE))
    compose.onNodeWithText(TITLE.displayName).assertExists()

    compose.activityRule.scenario.recreate()

    waitForText(TITLE.displayName)
    compose.onNodeWithText("Loading titles").assertDoesNotExist()
    assertEquals(1, browse.requests.size)
  }

  @Test
  fun CatalogListViewportExtendsBehindTheFloatingIsland() {
    val sources = (1..20).map { index ->
      CatalogSourceItem(
        id = ScreenSourceId("test.source.$index"),
        displayName = "Source $index",
      )
    }
    installModel(
      ControlledBrowseScreenService(catalog(*sources.toTypedArray())),
      ControlledTitleDetailsScreenService(),
    )

    compose.onNodeWithContentDescription("Catalog").performClick()
    val list = compose.onNode(hasScrollAction())
    val listBottom = list
      .fetchSemanticsNode()
      .boundsInRoot
      .bottom
    val islandTop = compose.onNodeWithContentDescription("Catalog")
      .fetchSemanticsNode()
      .boundsInRoot
      .top

    assertTrue(listBottom > islandTop)
    list.performScrollToIndex(20)
    compose.waitForIdle()
    val finalItemBottom = compose.onNodeWithText("Source 20")
      .fetchSemanticsNode()
      .boundsInRoot
      .bottom
    assertTrue(finalItemBottom < islandTop)
  }

  private fun installModel(
    browse: BrowseScreenService,
    details: TitleDetailsScreenService,
  ) {
    compose.activityRule.scenario.onActivity { activity ->
      ViewModelProvider(
        owner = activity,
        factory = CatalogPresentationModel.factory(
          browseScreenService = { browse },
          titleDetailsScreenService = { details },
        ),
      )[CatalogPresentationModel::class.java]
    }
    compose.waitForIdle()
  }

  private fun awaitRequests(
    requests: List<*>,
    count: Int,
  ) {
    compose.waitUntil(timeoutMillis = TEST_TIMEOUT_MILLIS) {
      requests.size == count
    }
  }

  private fun waitForText(text: String) {
    compose.waitUntil(timeoutMillis = TEST_TIMEOUT_MILLIS) {
      compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
  }

  private class ControlledBrowseScreenService(
    private val catalog: CatalogScreen,
  ) : BrowseScreenService {
    private val completions =
      Channel<BrowseScreenResult>(Channel.UNLIMITED)
    val requests = CopyOnWriteArrayList<ScreenSourceId>()
    val cancellations = CopyOnWriteArrayList<ScreenSourceId>()

    override fun catalog(): CatalogScreen = catalog

    override suspend fun loadBrowse(
      sourceId: ScreenSourceId,
    ): BrowseScreenResult {
      requests += sourceId
      try {
        return completions.receive()
      } finally {
        if (!currentCoroutineContext().isActive) {
          cancellations += sourceId
        }
      }
    }

    fun complete(result: BrowseScreenResult) {
      check(completions.trySend(result).isSuccess) {
        "Unable to complete a controlled browse request."
      }
    }
  }

  private class ControlledTitleDetailsScreenService :
    TitleDetailsScreenService {
    private val completions =
      Channel<DetailsScreenResult>(Channel.UNLIMITED)
    val requests = CopyOnWriteArrayList<ScreenTitleKey>()

    override suspend fun loadDetails(
      titleKey: ScreenTitleKey,
    ): DetailsScreenResult {
      requests += titleKey
      return completions.receive()
    }

    override suspend fun addToLibrary(
      titleId: ScreenTitleId,
    ): AddToLibraryScreenResult = error(
      "This controlled Catalog test does not modify Library membership.",
    )

    override suspend fun selectContinue(
      titleId: ScreenTitleId,
    ): ContinueSelectionResult = error(
      "This controlled Catalog test does not select Continue.",
    )

    fun complete(result: DetailsScreenResult) {
      check(completions.trySend(result).isSuccess) {
        "Unable to complete a controlled details request."
      }
    }
  }

  private companion object {
    const val TEST_TIMEOUT_MILLIS = 5_000L

    val SOURCE_ID = ScreenSourceId(" test\u00a0source ")
    val SOURCE = CatalogSourceItem(SOURCE_ID, "Test source")
    val TITLE_KEY = ScreenTitleKey(SOURCE_ID, "e\u0301 title ")
    val TITLE = BrowseTitleItem(TITLE_KEY, "Test title")
    val BROWSE_WITH_TITLE = BrowseScreen.of(SOURCE, listOf(TITLE))
    val EMPTY_DETAILS = TitleDetailsScreen.of(
      id = ScreenTitleId(
        UUID.fromString("00000000-0000-7000-8000-000000000001"),
      ),
      key = TITLE_KEY,
      sourceDisplayName = SOURCE.displayName,
      displayName = TITLE.displayName,
      description = "Details without chapters.",
      chapters = emptyList(),
      isInLibrary = false,
      continueState = ContinueState.Unavailable(
        ContinueUnavailableReason.NoAvailableChapter,
      ),
    )

    fun catalog(
      vararg sources: CatalogSourceItem,
    ): CatalogScreen = CatalogScreen.of(sources.asList())

    fun emptyDetails(
      source: CatalogSourceItem,
      title: BrowseTitleItem,
    ): TitleDetailsScreen = TitleDetailsScreen.of(
      id = ScreenTitleId(
        UUID.fromString("00000000-0000-7000-8000-000000000002"),
      ),
      key = title.key,
      sourceDisplayName = source.displayName,
      displayName = title.displayName,
      description = null,
      chapters = emptyList(),
      isInLibrary = false,
      continueState = ContinueState.Unavailable(
        ContinueUnavailableReason.NoAvailableChapter,
      ),
    )
  }
}
