package app.hakusan

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import app.hakusan.titles.TitlesStore
import app.hakusan.titles.openTitlesStore
import app.hakusan.ui.BrowsingViewModel
import app.hakusan.ui.HakusanApp
import app.hakusan.ui.LibraryViewModel
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryIntegrationAndroidTest {
  private val storeRule = IsolatedTitlesStoreRule()
  private val compose = createAndroidComposeRule<HakusanActivity>()

  @get:Rule
  val ruleChain: TestRule = RuleChain
    .outerRule(storeRule)
    .around(compose)

  @Test
  fun likePersistsShelfThroughDetails() {
    installGraph()

    waitForText("Your Library is empty")
    compose.onNodeWithContentDescription("Catalog").performClick()
    compose.onNodeWithText("Deterministic source").performClick()
    waitForText("Canonical Order Fixture")
    compose.onNodeWithText("Canonical Order Fixture").performClick()
    waitForContentDescription("Chapter 10")

    compose.onNodeWithText("Like")
      .assertHasClickAction()
      .performClick()
    waitForText("Added to Library.")
    compose.onNodeWithContentDescription("Like")
      .assertIsSelected()
      .assertHasNoClickAction()

    compose.onNodeWithText("Continue").performClick()
    waitForText(
      "Continue target selected: Chapter 10. Reading has not started.",
    )

    compose.onNodeWithText("Back").performClick()
    compose.onNodeWithContentDescription("Library").performClick()
    waitForText("Default")
    compose.onNodeWithText("Canonical Order Fixture").assertExists()
    compose.onNodeWithText("0 of 3 chapters read").assertExists()
    compose.onNodeWithText("No saved position").assertExists()

    compose.onNodeWithText("Canonical Order Fixture").performClick()
    waitForContentDescription("Chapter 10")
    compose.onNodeWithContentDescription("Like")
      .assertIsSelected()
      .assertHasNoClickAction()

    compose.onNodeWithText("Back").performClick()
    compose.onNodeWithText("Default").assertExists()
    compose.onNodeWithText("Canonical Order Fixture").assertExists()
    compose.onNodeWithText("0 of 3 chapters read").assertExists()
  }

  private fun installGraph() {
    val graph = createAppGraph(
      sourceRegistry = SourceRegistry.of(sourceBackends()),
      titles = storeRule.store.titles,
    )
    compose.activityRule.scenario.onActivity { activity ->
      val browsingModel = ViewModelProvider(
        owner = activity,
        factory = BrowsingViewModel.factory(
          browseService = { graph.browseService },
          detailsService = {
            graph.detailsService
          },
          continueService = {
            graph.continueService
          },
        ),
      )[BROWSING_MODEL_KEY, BrowsingViewModel::class.java]
      val libraryModel = ViewModelProvider(
        owner = activity,
        factory = LibraryViewModel.factory(
          libraryService = { graph.libraryService },
        ),
      )[LIBRARY_MODEL_KEY, LibraryViewModel::class.java]

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

  private fun waitForText(text: String) {
    compose.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
      compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
  }

  private fun waitForContentDescription(description: String) {
    compose.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
      compose.onAllNodesWithContentDescription(description)
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
  }

  private class IsolatedTitlesStoreRule : ExternalResource() {
    lateinit var store: TitlesStore
      private set

    private lateinit var databaseContext: IsolatedDatabaseContext

    override fun before() {
      val appContext = InstrumentationRegistry.getInstrumentation()
        .targetContext
        .applicationContext
      databaseContext = IsolatedDatabaseContext(appContext)
      check(
        databaseContext.getDatabasePath(DATABASE_NAME) !=
          appContext.getDatabasePath(DATABASE_NAME),
      ) {
        "The isolated test store must not own the application database."
      }
      databaseContext.prepareDatabase(DATABASE_NAME)
      store = openTitlesStore(databaseContext)
    }

    override fun after() {
      if (::store.isInitialized) {
        store.close()
      }
      if (::databaseContext.isInitialized) {
        check(databaseContext.deleteDatabase(DATABASE_NAME)) {
          "Unable to delete the isolated test database."
        }
      }
    }
  }

  private class IsolatedDatabaseContext(
    base: Context,
  ) : ContextWrapper(base) {
    private val databaseDirectory = File(
      base.cacheDir,
      "library-integration-databases",
    )

    override fun getApplicationContext(): Context = this

    override fun getDatabasePath(name: String): File =
      File(databaseDirectory, name)

    override fun deleteDatabase(name: String): Boolean {
      val databaseFile = getDatabasePath(name)
      val databaseDeleted =
        !databaseFile.exists() || SQLiteDatabase.deleteDatabase(databaseFile)
      val lock = File("${databaseFile.path}.lck")
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

  private companion object {
    const val BROWSING_MODEL_KEY = "library-integration-browsing"
    const val LIBRARY_MODEL_KEY = "library-integration-library"
    const val DATABASE_NAME = "hakusan.db"
    const val UI_TIMEOUT_MILLIS = 30_000L
  }
}
