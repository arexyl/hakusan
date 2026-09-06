package app.hakusan

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import app.hakusan.debug.source.DeterministicSource
import app.hakusan.debug.source.UnavailableOperation
import app.hakusan.extensions.ChapterRefreshCompletion
import app.hakusan.extensions.ChapterRefreshRequest
import app.hakusan.extensions.ChapterSequenceStatus
import app.hakusan.extensions.SourceBackend
import app.hakusan.extensions.SourceBrowseResult
import app.hakusan.extensions.SourceChapter
import app.hakusan.extensions.SourceChapterKey
import app.hakusan.extensions.SourceIdentity
import app.hakusan.extensions.SourceResult
import app.hakusan.extensions.SourceTitle
import app.hakusan.extensions.SourceTitleDetails
import app.hakusan.extensions.SourceTitleKey
import app.hakusan.sdk.AddToLibraryScreenResult
import app.hakusan.sdk.BrowseScreenFailure
import app.hakusan.sdk.BrowseScreenResult
import app.hakusan.sdk.ContinueSelectionResult
import app.hakusan.sdk.ContinueState
import app.hakusan.sdk.DetailsScreenFailure
import app.hakusan.sdk.DetailsScreenResult
import app.hakusan.sdk.ScreenReadingStart
import app.hakusan.sdk.ScreenSourceId
import app.hakusan.titles.ChapterReconciliationResult
import app.hakusan.titles.ReconcileChapterSnapshot
import app.hakusan.titles.Titles
import app.hakusan.titles.TitlesStore
import app.hakusan.titles.openTitlesStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenAdaptersAndroidTest {
  private lateinit var store: TitlesStore
  private lateinit var databaseContext: IsolatedDatabaseContext

  @Before
  fun openStore() {
    val appContext = InstrumentationRegistry.getInstrumentation()
      .targetContext
      .applicationContext
    databaseContext = IsolatedDatabaseContext(appContext)
    check(
      databaseContext.getDatabasePath(DATABASE_NAME) !=
        appContext.getDatabasePath(DATABASE_NAME),
    ) {
      "Screen adapter tests must not own the target database."
    }
    databaseContext.prepareDatabase(DATABASE_NAME)
    store = openTitlesStore(databaseContext)
  }

  @After
  fun closeStore() {
    if (::store.isInitialized) {
      store.close()
    }
    if (::databaseContext.isInitialized) {
      databaseContext.deleteDatabase(DATABASE_NAME)
    }
  }

  @Test
  fun deterministicSourceCompletesScreenFlows(): Unit =
    runBlocking {
      withTimeout(TEST_TIMEOUT_MILLIS) {
        val graph = graph(DeterministicSource())
        assertTrue(
          graph.libraryService.observeLibrary().first().shelves.isEmpty(),
        )

        val source = graph.browseService.catalog().sources.single()
        val browse = graph.browseService
          .loadBrowse(source.id)
          .successScreen()
        val browseTitle = browse.titles.single()
        val details = graph.detailsService
          .loadDetails(browseTitle.key)
          .successScreen()

        assertEquals(
          listOf("Chapter 10", "Chapter 2", "Chapter 1"),
          details.chapters.map { it.displayName },
        )
        assertFalse(details.isInLibrary)
        val initialTarget =
          (details.continueState as ContinueState.Ready).target
        assertEquals(details.chapters.first().id, initialTarget.chapterId)
        assertSame(ScreenReadingStart.Beginning, initialTarget.start)

        assertSame(
          AddToLibraryScreenResult.Success,
          graph.detailsService.addToLibrary(details.id),
        )
        val library = graph.libraryService.observeLibrary().first {
          details.id in it.titlesById
        }
        assertEquals("Default", library.shelves.single().name)
        assertEquals(listOf(details.id), library.shelves.single().titleIds)
        assertEquals(
          3,
          library.titlesById.getValue(details.id).progress.chapterCount,
        )

        val memberDetails = graph.detailsService
          .loadDetails(browseTitle.key)
          .successScreen()
        assertTrue(memberDetails.isInLibrary)
        val selected = graph.detailsService
          .selectContinue(details.id) as ContinueSelectionResult.Selected
        assertEquals(details.chapters.first().id, selected.target.chapterId)
      }
    }

  @Test
  fun sourceFailuresStayScreenSpecific(): Unit = runBlocking {
    withTimeout(TEST_TIMEOUT_MILLIS) {
      val missing = graph(DeterministicSource()).browseService
        .loadBrowse(ScreenSourceId("missing"))
      assertEquals(
        BrowseScreenResult.Failure(BrowseScreenFailure.SourceNotFound),
        missing,
      )

      val browseFailure = graph(
        DeterministicSource(UnavailableOperation.BROWSE),
      ).browseService.loadBrowse(SOURCE_ID)
      assertEquals(
        BrowseScreenResult.Failure(BrowseScreenFailure.SourceUnavailable),
        browseFailure,
      )

      val titleKey = graph(DeterministicSource()).browseService
        .loadBrowse(SOURCE_ID)
        .successScreen()
        .titles
        .single()
        .key
      val detailsFailure = graph(
        DeterministicSource(UnavailableOperation.DETAILS),
      ).detailsService.loadDetails(titleKey)
      assertEquals(
        DetailsScreenResult.Failure(
          DetailsScreenFailure.DetailsUnavailable,
        ),
        detailsFailure,
      )

      val chapterFailure = graph(
        DeterministicSource(UnavailableOperation.CHAPTERS),
      ).detailsService.loadDetails(titleKey)
      assertEquals(
        DetailsScreenResult.Failure(
          DetailsScreenFailure.ChaptersUnavailable,
        ),
        chapterFailure,
      )
      val invalidBrowse = graph(ForeignBrowseSource())
        .browseService
        .loadBrowse(SOURCE_ID)
      assertEquals(
        BrowseScreenResult.Failure(BrowseScreenFailure.InvalidObservation),
        invalidBrowse,
      )
      val invalidDetails = graph(ForeignDetailsSource())
        .detailsService
        .loadDetails(titleKey)
      assertEquals(
        DetailsScreenResult.Failure(
          DetailsScreenFailure.InvalidTitleObservation,
        ),
        invalidDetails,
      )
      val invalidChapters = graph(InvalidChapterSource())
        .detailsService
        .loadDetails(titleKey)
      assertEquals(
        DetailsScreenResult.Failure(
          DetailsScreenFailure.InvalidChapterSnapshot,
        ),
        invalidChapters,
      )
    }
  }

  @Test
  fun newerRefreshRejectsOlderCompletion(): Unit = runBlocking {
    withTimeout(TEST_TIMEOUT_MILLIS) {
      val source = ControlledRefreshSource()
      val service = graph(source).detailsService

      val firstLoad = async(start = CoroutineStart.UNDISPATCHED) {
        service.loadDetails(TITLE_KEY.toScreenKey())
      }
      val firstRefresh = source.awaitRefresh()
      val secondLoad = async(start = CoroutineStart.UNDISPATCHED) {
        service.loadDetails(TITLE_KEY.toScreenKey())
      }
      val secondRefresh = source.awaitRefresh()

      secondRefresh.complete(listOf(chapter("new", "New")))
      assertTrue(secondLoad.await() is DetailsScreenResult.Success)
      firstRefresh.complete(listOf(chapter("old", "Old")))
      assertTrue(firstLoad.await() === DetailsScreenResult.RejectedNotCurrent)
    }
  }

  @Test
  fun rejectsStaleDetailsBeforePersistence(): Unit =
    runBlocking {
      withTimeout(TEST_TIMEOUT_MILLIS) {
        val source = ControlledDetailsSource()
        val graph = graph(source)
        val service = graph.detailsService

        val firstLoad = async(start = CoroutineStart.UNDISPATCHED) {
          service.loadDetails(TITLE_KEY.toScreenKey())
        }
        val firstDetails = source.awaitDetails()
        val secondLoad = async(start = CoroutineStart.UNDISPATCHED) {
          service.loadDetails(TITLE_KEY.toScreenKey())
        }
        val secondDetails = source.awaitDetails()

        secondDetails.complete("Current title")
        val current = secondLoad.await().successScreen()
        assertEquals("Current title", current.displayName)
        assertSame(
          AddToLibraryScreenResult.Success,
          service.addToLibrary(current.id),
        )

        firstDetails.complete("Stale title")
        assertSame(DetailsScreenResult.RejectedNotCurrent, firstLoad.await())
        val library = graph.libraryService.observeLibrary().first {
          current.id in it.titlesById
        }
        assertEquals(
          "Current title",
          library.titlesById.getValue(current.id).displayName,
        )
      }
    }

  @Test
  fun serializesAcceptedRefreshes(): Unit = runBlocking {
    withTimeout(TEST_TIMEOUT_MILLIS) {
      val source = ControlledRefreshSource()
      val blockingTitles = BlockingFirstReconciliation(store.titles)
      val service = graph(source, blockingTitles).detailsService

      val firstLoad = async(start = CoroutineStart.UNDISPATCHED) {
        service.loadDetails(TITLE_KEY.toScreenKey())
      }
      source.awaitRefresh().complete(listOf(chapter("first", "First")))
      blockingTitles.firstEntered.await()

      val secondLoad = async(start = CoroutineStart.UNDISPATCHED) {
        service.loadDetails(TITLE_KEY.toScreenKey())
      }
      source.awaitRefresh().complete(
        listOf(
          chapter("first", "First"),
          chapter("second", "Second"),
        ),
      )
      yield()
      assertFalse(secondLoad.isCompleted)

      blockingTitles.releaseFirst.complete(Unit)
      assertTrue(firstLoad.await() is DetailsScreenResult.Success)
      val finalScreen = secondLoad.await().successScreen()
      assertEquals(listOf("First", "Second"), finalScreen.chapters.map {
        it.displayName
      })
    }
  }

  private fun graph(
    source: SourceBackend,
    titles: Titles = store.titles,
  ): AppGraph = createAppGraph(
    sourceRegistry = SourceRegistry.of(listOf(source)),
    titles = titles,
  )

  private class ForeignBrowseSource(
    private val delegate: SourceBackend = DeterministicSource(),
  ) : SourceBackend by delegate {
    override suspend fun browse(): SourceResult<SourceBrowseResult> =
      SourceBrowseResult.create(
        source = SourceIdentity("foreign"),
        titles = emptyList(),
      )
  }

  private class ForeignDetailsSource(
    private val delegate: SourceBackend = DeterministicSource(),
  ) : SourceBackend by delegate {
    override suspend fun details(
      title: SourceTitleKey,
    ): SourceResult<SourceTitleDetails> = SourceResult.Success(
      SourceTitleDetails(
        title = SourceTitle(
          key = SourceTitleKey(identity, "foreign"),
          displayName = "Foreign",
        ),
        description = null,
      ),
    )
  }

  private class InvalidChapterSource(
    private val delegate: SourceBackend = DeterministicSource(),
  ) : SourceBackend by delegate {
    override suspend fun refreshChapters(
      request: ChapterRefreshRequest,
    ): ChapterRefreshCompletion = ChapterRefreshCompletion.completed(
      request = request,
      status = ChapterSequenceStatus.PARTIAL,
      chapters = emptyList(),
    )
  }

  private class ControlledRefreshSource(
    private val delegate: SourceBackend = DeterministicSource(),
  ) : SourceBackend by delegate {
    private val refreshes = Channel<PendingRefresh>(Channel.UNLIMITED)

    override suspend fun refreshChapters(
      request: ChapterRefreshRequest,
    ): ChapterRefreshCompletion {
      val pending = PendingRefresh(request)
      refreshes.send(pending)
      return pending.completion.await()
    }

    suspend fun awaitRefresh(): PendingRefresh = refreshes.receive()
  }

  private class ControlledDetailsSource(
    private val delegate: SourceBackend = DeterministicSource(),
  ) : SourceBackend by delegate {
    private val pendingDetails = Channel<PendingDetails>(Channel.UNLIMITED)

    override suspend fun details(
      title: SourceTitleKey,
    ): SourceResult<SourceTitleDetails> {
      val pending = PendingDetails(title)
      pendingDetails.send(pending)
      return pending.completion.await()
    }

    suspend fun awaitDetails(): PendingDetails = pendingDetails.receive()
  }

  private class PendingDetails(
    private val titleKey: SourceTitleKey,
  ) {
    val completion = CompletableDeferred<SourceResult<SourceTitleDetails>>()

    fun complete(displayName: String) {
      completion.complete(
        SourceResult.Success(
          SourceTitleDetails(
            title = SourceTitle(titleKey, displayName),
            description = null,
          ),
        ),
      )
    }
  }

  private class PendingRefresh(
    private val request: ChapterRefreshRequest,
  ) {
    val completion = CompletableDeferred<ChapterRefreshCompletion>()

    fun complete(chapters: List<SourceChapter>) {
      completion.complete(
        ChapterRefreshCompletion.completed(
          request = request,
          status = ChapterSequenceStatus.COMPLETE,
          chapters = chapters,
        ),
      )
    }
  }

  private class BlockingFirstReconciliation(
    private val delegate: Titles,
  ) : Titles by delegate {
    private val calls = AtomicInteger()
    val firstEntered = CompletableDeferred<Unit>()
    val releaseFirst = CompletableDeferred<Unit>()

    override suspend fun reconcileChapterSnapshot(
      input: ReconcileChapterSnapshot,
    ): ChapterReconciliationResult {
      if (calls.getAndIncrement() == 0) {
        firstEntered.complete(Unit)
        releaseFirst.await()
      }
      return delegate.reconcileChapterSnapshot(input)
    }
  }

  private companion object {
    const val DATABASE_NAME = "hakusan.db"
    const val TEST_TIMEOUT_MILLIS = 10_000L
    val SOURCE_ID = ScreenSourceId("app.hakusan.debug.source")
    val TITLE_KEY = SourceTitleKey(
      source = SourceIdentity(SOURCE_ID.value),
      key = "canonical-order-fixture",
    )

    fun chapter(
      key: String,
      displayName: String,
    ): SourceChapter = SourceChapter(
      key = SourceChapterKey(TITLE_KEY, key),
      displayName = displayName,
    )
  }
}

private class IsolatedDatabaseContext(
  base: Context,
) : ContextWrapper(base) {
  private val databaseDirectory = File(
    base.cacheDir,
    "screen-adapter-databases",
  )

  override fun getApplicationContext(): Context = this

  override fun getDatabasePath(name: String): File =
    File(databaseDirectory, name)

  override fun deleteDatabase(name: String): Boolean {
    val database = getDatabasePath(name)
    val databaseDeleted =
      !database.exists() || SQLiteDatabase.deleteDatabase(database)
    val lock = File("${database.path}.lck")
    val lockDeleted = !lock.exists() || lock.delete()
    return databaseDeleted && lockDeleted
  }

  fun prepareDatabase(name: String) {
    check(databaseDirectory.isDirectory || databaseDirectory.mkdirs()) {
      "Unable to create the isolated test database directory."
    }
    check(deleteDatabase(name)) {
      "Unable to reset the isolated test database."
    }
  }
}

private fun BrowseScreenResult.successScreen() = when (this) {
  is BrowseScreenResult.Success -> screen
  is BrowseScreenResult.Failure -> error("Expected browse success: $error")
}

private fun DetailsScreenResult.successScreen() = when (this) {
  is DetailsScreenResult.Success -> screen
  is DetailsScreenResult.Failure -> error("Expected details success: $error")
  DetailsScreenResult.RejectedNotCurrent -> error("Details load was rejected")
}
