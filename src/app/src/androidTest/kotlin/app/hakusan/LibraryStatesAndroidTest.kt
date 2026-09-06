package app.hakusan

import app.hakusan.sdk.AddToLibraryScreenFailure
import app.hakusan.sdk.AddToLibraryScreenResult
import app.hakusan.sdk.BrowseScreen
import app.hakusan.sdk.BrowseScreenResult
import app.hakusan.sdk.BrowseScreenService
import app.hakusan.sdk.BrowseTitleItem
import app.hakusan.sdk.CatalogScreen
import app.hakusan.sdk.CatalogSourceItem
import app.hakusan.sdk.ContinueSelectionResult
import app.hakusan.sdk.ContinueSelectionFailure
import app.hakusan.sdk.ContinueState
import app.hakusan.sdk.ContinueTarget
import app.hakusan.sdk.ContinueUnavailableReason
import app.hakusan.sdk.DetailsChapterItem
import app.hakusan.sdk.DetailsScreenResult
import app.hakusan.sdk.LibraryResumeState
import app.hakusan.sdk.LibraryScreen
import app.hakusan.sdk.LibraryScreenService
import app.hakusan.sdk.LibraryShelfItem
import app.hakusan.sdk.LibraryTitleItem
import app.hakusan.sdk.LibraryTitleProgress
import app.hakusan.sdk.ScreenChapterId
import app.hakusan.sdk.ScreenChapterKey
import app.hakusan.sdk.ScreenContentUnitKind
import app.hakusan.sdk.ScreenReadingPosition
import app.hakusan.sdk.ScreenReadingStart
import app.hakusan.sdk.ScreenShelfId
import app.hakusan.sdk.ScreenSourceId
import app.hakusan.sdk.ScreenTitleId
import app.hakusan.sdk.ScreenTitleKey
import app.hakusan.sdk.TitleDetailsScreen
import app.hakusan.sdk.TitleDetailsScreenService
import app.hakusan.ui.BrowsingViewModel
import app.hakusan.ui.HakusanApp
import app.hakusan.ui.LibraryViewModel
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryStatesAndroidTest {
  @get:Rule
  val compose = createAndroidComposeRule<HakusanActivity>()

  @Test
  fun rendersOrderedStatesAndReturns() {
    val library = ControlledLibraryService()
    val details = ControlledDetailsService()
    installHost(library, details)

    compose.onNodeWithText("Loading Library").assertExists()
    compose.onNode(
      hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate),
    ).assertExists()

    library.emit(EMPTY_LIBRARY)
    compose.onNodeWithText("Your Library is empty").assertExists()
    compose.onNodeWithText("Reading now").assertDoesNotExist()

    library.emit(ORDERED_LIBRARY)
    waitForText("Second title")
    compose.onNodeWithText("Your Library is empty").assertDoesNotExist()
    compose.onNodeWithText("Reading now").assertExists()
    compose.onNodeWithText("2 titles").assertExists()
    compose.onNodeWithText("1 of 3 chapters read").assertExists()
    compose.onNodeWithText("Saved position available").assertExists()
    compose.onNodeWithText("Later").assertExists()
    compose.onNodeWithText("0 titles").assertExists()
    compose.onNodeWithText("No titles in this shelf.").assertExists()

    val readingShelfTop = compose.onNodeWithText("Reading now")
      .fetchSemanticsNode()
      .boundsInRoot
      .top
    val laterShelfTop = compose.onNodeWithText("Later")
      .fetchSemanticsNode()
      .boundsInRoot
      .top
    assertTrue(readingShelfTop < laterShelfTop)

    val secondTitleLeft = compose.onNodeWithText("Second title")
      .fetchSemanticsNode()
      .boundsInRoot
      .left
    val firstTitleLeft = compose.onNodeWithText("First title")
      .fetchSemanticsNode()
      .boundsInRoot
      .left
    assertTrue(secondTitleLeft < firstTitleLeft)

    compose.onNodeWithText("Second title").performClick()
    awaitCount(details.detailsRequests, 1)
    assertEquals(listOf(TITLE_B_KEY), details.detailsRequests)
    details.completeDetails(DetailsScreenResult.Success(DETAILS_B_READY))
    compose.onNodeWithText("Second title details.").assertExists()
    compose.onNodeWithContentDescription("Library").assertDoesNotExist()

    compose.onNodeWithText("Back").performClick()
    compose.onNodeWithText("Reading now").assertExists()
    compose.onNodeWithText("Second title").assertExists()
    assertEquals(1, library.observations.get())
  }

  @Test
  fun likeWaitsForCommittedLibraryFlow() {
    val library = ControlledLibraryService()
    val details = ControlledDetailsService()
    installHost(library, details)
    library.emit(EMPTY_LIBRARY)
    openCatalogDetails(details, DETAILS_A_READY)

    compose.onNodeWithText("Like").performClick()
    awaitCount(details.addRequests, 1)
    assertEquals(listOf(TITLE_A_ID), details.addRequests)
    compose.onNodeWithText("Like").assertIsNotEnabled()
    compose.onNodeWithText("Adding to Library").assertExists()

    details.completeAdd(AddToLibraryScreenResult.Success)
    compose.onNodeWithText("Added to Library.").assertExists()
    compose.onNodeWithText("Like")
      .assertIsSelected()
      .assertHasNoClickAction()

    compose.onNodeWithText("Back").performClick()
    compose.onNodeWithContentDescription("Library").performClick()
    compose.onNodeWithText("Your Library is empty").assertExists()
    compose.onNodeWithText("First title").assertDoesNotExist()

    library.emit(DEFAULT_LIBRARY)
    compose.onNodeWithText("Default").assertExists()
    compose.onNodeWithText("1 title").assertExists()
    compose.onNodeWithText("First title").assertExists()
  }

  @Test
  fun pendingLikeCompletesAfterDetailsBack() {
    val library = ControlledLibraryService()
    val details = ControlledDetailsService()
    installHost(library, details)
    library.emit(EMPTY_LIBRARY)
    openCatalogDetails(details, DETAILS_A_READY)

    compose.onNodeWithText("Like").performClick()
    awaitCount(details.addRequests, 1)
    compose.onNodeWithText("Back").performClick()

    details.completeAdd(AddToLibraryScreenResult.Success)
    compose.onNodeWithText(TITLE_A.displayName).performClick()
    awaitCount(details.detailsRequests, 2)
    details.completeDetails(DetailsScreenResult.Success(DETAILS_A_READY))

    compose.onNodeWithText("Added to Library.").assertExists()
    compose.onNodeWithContentDescription("Like")
      .assertIsSelected()
      .assertHasNoClickAction()
    assertEquals(listOf(TITLE_A_ID), details.addRequests)
  }

  @Test
  fun likeOutcomesStayDistinctAndRetryable() {
    val library = ControlledLibraryService()
    val details = ControlledDetailsService()
    installHost(library, details)
    library.emit(EMPTY_LIBRARY)
    openCatalogDetails(details, DETAILS_A_READY)

    compose.onNodeWithText("Like").performClick()
    awaitCount(details.addRequests, 1)
    details.completeAdd(
      AddToLibraryScreenResult.CategorySelectionRequired,
    )
    compose.onNodeWithText(
      "This title needs a category choice. " +
        "Category selection is unavailable.",
    ).assertExists()
    compose.onNodeWithText("Like")
      .assertIsEnabled()
      .assertHasClickAction()

    compose.onNodeWithText("Like").performClick()
    awaitCount(details.addRequests, 2)
    details.completeAdd(
      AddToLibraryScreenResult.Failure(
        AddToLibraryScreenFailure.TitleNotFound,
      ),
    )
    compose.onNodeWithText(
      "Hakusan could not find this title. Tap Like to try again.",
    ).assertExists()
    compose.onNodeWithText("Like")
      .assertIsEnabled()
      .assertHasClickAction()
    assertEquals(listOf(TITLE_A_ID, TITLE_A_ID), details.addRequests)
  }

  @Test
  fun noChapterDisablesVisibleContinue() {
    val library = ControlledLibraryService()
    val details = ControlledDetailsService()
    installHost(library, details)
    library.emit(EMPTY_LIBRARY)
    openCatalogDetails(details, DETAILS_A_EMPTY)

    compose.onNodeWithText("Continue").assertIsNotEnabled()
    compose.onNodeWithText(
      "No chapter is available for Continue.",
    ).assertExists()
    assertTrue(details.continueRequests.isEmpty())
  }

  @Test
  fun continueUsesFreshTargetAndOffersRetry() {
    val library = ControlledLibraryService()
    val details = ControlledDetailsService()
    installHost(library, details)
    library.emit(EMPTY_LIBRARY)
    openCatalogDetails(details, DETAILS_A_READY)

    compose.onNodeWithText("Continue").performClick()
    awaitCount(details.continueRequests, 1)
    assertEquals(listOf(TITLE_A_ID), details.continueRequests)
    compose.onNodeWithText("Continue").assertIsNotEnabled()
    compose.onNodeWithText(
      "Selecting the current Continue target.",
    ).assertExists()

    details.completeContinue(
      ContinueSelectionResult.Selected(SECOND_CONTINUE_TARGET),
    )
    compose.onNodeWithText(
      "Continue target selected: Chapter two. " +
        "Reading has not started.",
    ).assertExists()
    compose.onNodeWithText("Continue").assertIsEnabled()

    compose.onNodeWithText("Continue").performClick()
    awaitCount(details.continueRequests, 2)
    details.completeContinue(
      ContinueSelectionResult.Unavailable(SAVED_TARGET_UNAVAILABLE),
    )
    compose.onNodeWithText("Continue").assertIsNotEnabled()
    compose.onNodeWithText(
      "The saved reading position is not in the current chapter list. " +
        "Retry the title details.",
    ).assertExists()
    compose.onNodeWithText("Retry title details").performClick()
    awaitCount(details.detailsRequests, 2)
    compose.onNodeWithText("Loading title").assertExists()

    details.completeDetails(DetailsScreenResult.Success(DETAILS_A_READY))
    compose.onNodeWithText("First title details.").assertExists()
    compose.onNodeWithText("Continue").performClick()
    awaitCount(details.continueRequests, 3)
    details.completeContinue(
      ContinueSelectionResult.Selected(FIRST_CONTINUE_TARGET),
    )
    compose.onNodeWithText(
      "Continue target selected: Chapter one. Reading has not started.",
    ).assertExists()

    compose.onNodeWithText("Continue").performClick()
    awaitCount(details.continueRequests, 4)
    details.completeContinue(
      ContinueSelectionResult.Failure(
        ContinueSelectionFailure.TitleNotFound,
      ),
    )
    compose.onNodeWithText("Continue").assertIsNotEnabled()
    compose.onNodeWithText(
      "Hakusan could not find this title for Continue. " +
        "Retry the title details.",
    ).assertExists()
    compose.onNodeWithText("Retry title details").assertExists()
  }

  @Test
  fun lastShelfStaysReachableBehindIsland() {
    val library = ControlledLibraryService()
    installHost(library, ControlledDetailsService())
    library.emit(
      LibraryScreen.of(
        titlesById = emptyMap(),
        shelves = (1..20).map { index ->
          LibraryShelfItem.of(
            id = ScreenShelfId(index.toLong()),
            name = "Shelf $index",
            titleIds = emptyList(),
          )
        },
      ),
    )
    waitForText("Shelf 1")

    val list = compose.onNode(hasScrollAction())
    val listBottom = list.fetchSemanticsNode().boundsInRoot.bottom
    val islandBounds = compose.onNodeWithContentDescription("Library")
      .fetchSemanticsNode()
      .boundsInRoot
    val islandTop = islandBounds.top
    assertTrue(listBottom > islandTop)

    list.performScrollToIndex(19)
    list.performTouchInput { swipeUp() }
    compose.waitForIdle()
    val emptyShelfBounds = compose.onAllNodesWithText(
      "No titles in this shelf.",
    ).fetchSemanticsNodes().map { it.boundsInRoot }
    val finalContentBottom = emptyShelfBounds.maxOf { bounds ->
      bounds.bottom
    }
    assertTrue(
      "Final shelf content bottom $finalContentBottom must be above " +
        "island top " +
        "$islandTop; list bottom=$listBottom; island=$islandBounds; " +
        "visible empty shelves=$emptyShelfBounds",
      finalContentBottom < islandTop,
    )
  }

  @Test
  fun lastChapterStaysAboveExpandedActionStatus() {
    val library = ControlledLibraryService()
    val details = ControlledDetailsService()
    val longDetails = detailsWithChapterCount(20)
    installHost(library, details)
    library.emit(EMPTY_LIBRARY)
    openCatalogDetails(details, longDetails)

    compose.onNodeWithText("Continue").performClick()
    awaitCount(details.continueRequests, 1)
    details.completeContinue(
      ContinueSelectionResult.Unavailable(SAVED_TARGET_UNAVAILABLE),
    )
    val unavailableMessage =
      "The saved reading position is not in the current chapter list. " +
        "Retry the title details."
    compose.onNodeWithText(unavailableMessage).assertExists()

    val list = compose.onNode(hasScrollAction())
    val listBottom = list.fetchSemanticsNode().boundsInRoot.bottom
    val statusTop = compose.onNodeWithText(unavailableMessage)
      .fetchSemanticsNode()
      .boundsInRoot
      .top
    assertTrue(listBottom > statusTop)

    list.performScrollToIndex(22)
    list.performTouchInput { swipeUp() }
    compose.waitForIdle()
    val finalChapterBottom = compose.onNodeWithContentDescription(
      "Chapter 20",
    ).fetchSemanticsNode().boundsInRoot.bottom
    assertTrue(
      "Final chapter bottom $finalChapterBottom must be above " +
        "expanded status top $statusTop",
      finalChapterBottom < statusTop,
    )
  }

  private fun installHost(
    library: LibraryScreenService,
    details: TitleDetailsScreenService,
    browse: BrowseScreenService = FixedBrowseService,
  ) {
    val modelId = MODEL_ID.incrementAndGet()
    compose.activityRule.scenario.onActivity { activity ->
      val browsingModel = ViewModelProvider(
        owner = activity,
        factory = BrowsingViewModel.factory(
          browseService = { browse },
          detailsService = { details },
        ),
      ).get(
        "library-states-browsing-$modelId",
        BrowsingViewModel::class.java,
      )
      val libraryModel = ViewModelProvider(
        owner = activity,
        factory = LibraryViewModel.factory(
          libraryService = { library },
          detailsService = { details },
        ),
      ).get(
        "library-states-library-$modelId",
        LibraryViewModel::class.java,
      )
      activity.setContent {
        HakusanApp(
          browsingModel = { browsingModel },
          libraryModel = { libraryModel },
          onExit = activity::finish,
        )
      }
    }
    compose.waitForIdle()
  }

  private fun openCatalogDetails(
    details: ControlledDetailsService,
    screen: TitleDetailsScreen,
  ) {
    compose.onNodeWithContentDescription("Catalog").performClick()
    compose.onNodeWithText(SOURCE.displayName).performClick()
    waitForText(TITLE_A.displayName)
    compose.onNodeWithText(TITLE_A.displayName).performClick()
    awaitCount(details.detailsRequests, 1)
    details.completeDetails(DetailsScreenResult.Success(screen))
    compose.onNodeWithText(screen.description.orEmpty()).assertExists()
  }

  private fun waitForText(text: String) {
    compose.waitUntil(timeoutMillis = TEST_TIMEOUT_MILLIS) {
      compose.onAllNodesWithText(text)
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
  }

  private fun awaitCount(
    values: List<*>,
    count: Int,
  ) {
    compose.waitUntil(timeoutMillis = TEST_TIMEOUT_MILLIS) {
      values.size == count
    }
  }

  private class ControlledLibraryService : LibraryScreenService {
    private val screens = Channel<LibraryScreen>(Channel.UNLIMITED)
    val observations = AtomicInteger()

    override fun observeLibrary(): Flow<LibraryScreen> {
      observations.incrementAndGet()
      return screens.receiveAsFlow()
    }

    fun emit(screen: LibraryScreen) {
      check(screens.trySend(screen).isSuccess) {
        "Unable to emit a controlled Library snapshot."
      }
    }
  }

  private class ControlledDetailsService :
    TitleDetailsScreenService {
    private val detailsCompletions =
      Channel<DetailsScreenResult>(Channel.UNLIMITED)
    private val addCompletions =
      Channel<AddToLibraryScreenResult>(Channel.UNLIMITED)
    private val continueCompletions =
      Channel<ContinueSelectionResult>(Channel.UNLIMITED)

    val detailsRequests = CopyOnWriteArrayList<ScreenTitleKey>()
    val addRequests = CopyOnWriteArrayList<ScreenTitleId>()
    val continueRequests = CopyOnWriteArrayList<ScreenTitleId>()

    override suspend fun loadDetails(
      titleKey: ScreenTitleKey,
    ): DetailsScreenResult {
      detailsRequests += titleKey
      return detailsCompletions.receive()
    }

    override suspend fun addToLibrary(
      titleId: ScreenTitleId,
    ): AddToLibraryScreenResult {
      addRequests += titleId
      return addCompletions.receive()
    }

    override suspend fun selectContinue(
      titleId: ScreenTitleId,
    ): ContinueSelectionResult {
      continueRequests += titleId
      return continueCompletions.receive()
    }

    fun completeDetails(result: DetailsScreenResult) {
      check(detailsCompletions.trySend(result).isSuccess) {
        "Unable to complete a controlled details request."
      }
    }

    fun completeAdd(result: AddToLibraryScreenResult) {
      check(addCompletions.trySend(result).isSuccess) {
        "Unable to complete a controlled Library Add."
      }
    }

    fun completeContinue(result: ContinueSelectionResult) {
      check(continueCompletions.trySend(result).isSuccess) {
        "Unable to complete a controlled Continue selection."
      }
    }
  }

  private data object FixedBrowseService : BrowseScreenService {
    override fun catalog(): CatalogScreen = CATALOG

    override suspend fun loadBrowse(
      sourceId: ScreenSourceId,
    ): BrowseScreenResult {
      require(sourceId == SOURCE.id)
      return BrowseScreenResult.Success(BROWSE)
    }
  }

  private companion object {
    const val TEST_TIMEOUT_MILLIS = 5_000L
    val MODEL_ID = AtomicInteger()

    val SOURCE = CatalogSourceItem(
      id = ScreenSourceId("library-states.source"),
      displayName = "Library states source",
    )
    val TITLE_A_KEY = ScreenTitleKey(SOURCE.id, "title-a")
    val TITLE_B_KEY = ScreenTitleKey(SOURCE.id, "title-b")
    val TITLE_A_ID = titleId(1)
    val TITLE_B_ID = titleId(2)
    val CHAPTER_A_ID = chapterId(1)
    val CHAPTER_B_ID = chapterId(2)
    val MISSING_CHAPTER_ID = chapterId(3)
    val TITLE_A = BrowseTitleItem(TITLE_A_KEY, "First title")
    val TITLE_B = BrowseTitleItem(TITLE_B_KEY, "Second title")
    val CATALOG = CatalogScreen.of(listOf(SOURCE))
    val BROWSE = BrowseScreen.of(SOURCE, listOf(TITLE_A))

    val CHAPTER_A = DetailsChapterItem(
      id = CHAPTER_A_ID,
      titleId = TITLE_A_ID,
      key = ScreenChapterKey(TITLE_A_KEY, "chapter-a"),
      displayName = "Chapter one",
      isRead = false,
    )
    val CHAPTER_B = DetailsChapterItem(
      id = CHAPTER_B_ID,
      titleId = TITLE_A_ID,
      key = ScreenChapterKey(TITLE_A_KEY, "chapter-b"),
      displayName = "Chapter two",
      isRead = false,
    )
    val FIRST_CONTINUE_TARGET = ContinueTarget(
      titleId = TITLE_A_ID,
      chapterId = CHAPTER_A_ID,
      chapterKey = CHAPTER_A.key,
      start = ScreenReadingStart.Beginning,
    )
    val SECOND_CONTINUE_TARGET = ContinueTarget(
      titleId = TITLE_A_ID,
      chapterId = CHAPTER_B_ID,
      chapterKey = CHAPTER_B.key,
      start = ScreenReadingStart.Beginning,
    )
    val SAVED_TARGET_UNAVAILABLE =
      ContinueUnavailableReason.SavedTargetUnavailable(
        chapterKey = ScreenChapterKey(TITLE_A_KEY, "missing-chapter"),
        position = ScreenReadingPosition(
          titleId = TITLE_A_ID,
          chapterId = MISSING_CHAPTER_ID,
          unitKind = ScreenContentUnitKind.PAGE,
          unitIndex = 4,
        ),
      )

    val DETAILS_A_READY = TitleDetailsScreen.of(
      id = TITLE_A_ID,
      key = TITLE_A_KEY,
      sourceDisplayName = SOURCE.displayName,
      displayName = TITLE_A.displayName,
      description = "First title details.",
      chapters = listOf(CHAPTER_A, CHAPTER_B),
      isInLibrary = false,
      continueState = ContinueState.Ready(FIRST_CONTINUE_TARGET),
    )
    val DETAILS_A_EMPTY = TitleDetailsScreen.of(
      id = TITLE_A_ID,
      key = TITLE_A_KEY,
      sourceDisplayName = SOURCE.displayName,
      displayName = TITLE_A.displayName,
      description = "Chapterless title details.",
      chapters = emptyList(),
      isInLibrary = false,
      continueState = ContinueState.Unavailable(
        ContinueUnavailableReason.NoAvailableChapter,
      ),
    )
    val DETAILS_B_READY = TitleDetailsScreen.of(
      id = TITLE_B_ID,
      key = TITLE_B_KEY,
      sourceDisplayName = SOURCE.displayName,
      displayName = TITLE_B.displayName,
      description = "Second title details.",
      chapters = listOf(
        DetailsChapterItem(
          id = chapterId(4),
          titleId = TITLE_B_ID,
          key = ScreenChapterKey(TITLE_B_KEY, "chapter-b-1"),
          displayName = "Second title chapter",
          isRead = false,
        ),
      ),
      isInLibrary = true,
      continueState = ContinueState.Ready(
        ContinueTarget(
          titleId = TITLE_B_ID,
          chapterId = chapterId(4),
          chapterKey = ScreenChapterKey(TITLE_B_KEY, "chapter-b-1"),
          start = ScreenReadingStart.Beginning,
        ),
      ),
    )

    val EMPTY_LIBRARY = LibraryScreen.of(
      titlesById = emptyMap(),
      shelves = emptyList(),
    )
    val DEFAULT_LIBRARY = LibraryScreen.of(
      titlesById = mapOf(
        TITLE_A_ID to libraryTitle(
          id = TITLE_A_ID,
          key = TITLE_A_KEY,
          name = TITLE_A.displayName,
          chapterCount = 2,
          readChapterCount = 0,
          resumeState = LibraryResumeState.NONE,
        ),
      ),
      shelves = listOf(
        LibraryShelfItem.of(
          id = ScreenShelfId(3),
          name = "Default",
          titleIds = listOf(TITLE_A_ID),
        ),
      ),
    )
    val ORDERED_LIBRARY = LibraryScreen.of(
      titlesById = linkedMapOf(
        TITLE_A_ID to libraryTitle(
          id = TITLE_A_ID,
          key = TITLE_A_KEY,
          name = TITLE_A.displayName,
          chapterCount = 2,
          readChapterCount = 2,
          resumeState = LibraryResumeState.NONE,
        ),
        TITLE_B_ID to libraryTitle(
          id = TITLE_B_ID,
          key = TITLE_B_KEY,
          name = TITLE_B.displayName,
          chapterCount = 3,
          readChapterCount = 1,
          resumeState = LibraryResumeState.AVAILABLE,
        ),
      ),
      shelves = listOf(
        LibraryShelfItem.of(
          id = ScreenShelfId(1),
          name = "Reading now",
          titleIds = listOf(TITLE_B_ID, TITLE_A_ID),
        ),
        LibraryShelfItem.of(
          id = ScreenShelfId(2),
          name = "Later",
          titleIds = emptyList(),
        ),
      ),
    )

    fun titleId(value: Int): ScreenTitleId = ScreenTitleId(
      UUID.fromString(
        "00000000-0000-7000-8000-${value.toString().padStart(12, '0')}",
      ),
    )

    fun chapterId(value: Int): ScreenChapterId = ScreenChapterId(
      UUID.fromString(
        "00000000-0000-7001-8000-${value.toString().padStart(12, '0')}",
      ),
    )

    fun libraryTitle(
      id: ScreenTitleId,
      key: ScreenTitleKey,
      name: String,
      chapterCount: Int,
      readChapterCount: Int,
      resumeState: LibraryResumeState,
    ): LibraryTitleItem = LibraryTitleItem(
      id = id,
      key = key,
      displayName = name,
      description = null,
      progress = LibraryTitleProgress(
        chapterCount = chapterCount,
        readChapterCount = readChapterCount,
        resumeState = resumeState,
      ),
    )

    fun detailsWithChapterCount(count: Int): TitleDetailsScreen {
      require(count > 0)
      val chapters = (1..count).map { index ->
        DetailsChapterItem(
          id = chapterId(100 + index),
          titleId = TITLE_A_ID,
          key = ScreenChapterKey(
            titleKey = TITLE_A_KEY,
            sourceChapterKey = "long-chapter-$index",
          ),
          displayName = "Chapter $index",
          isRead = false,
        )
      }
      val first = chapters.first()
      return TitleDetailsScreen.of(
        id = TITLE_A_ID,
        key = TITLE_A_KEY,
        sourceDisplayName = SOURCE.displayName,
        displayName = TITLE_A.displayName,
        description = "Scrollable title details.",
        chapters = chapters,
        isInLibrary = false,
        continueState = ContinueState.Ready(
          ContinueTarget(
            titleId = TITLE_A_ID,
            chapterId = first.id,
            chapterKey = first.key,
            start = ScreenReadingStart.Beginning,
          ),
        ),
      )
    }
  }
}
