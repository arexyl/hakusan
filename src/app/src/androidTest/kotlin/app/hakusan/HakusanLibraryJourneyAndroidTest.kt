package app.hakusan

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import app.hakusan.titles.TitlesStore
import app.hakusan.titles.openTitlesStore
import app.hakusan.ui.CatalogPresentationModel
import app.hakusan.ui.HakusanApp
import app.hakusan.ui.LibraryPresentationModel
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
class HakusanLibraryJourneyAndroidTest {
  private val database = IsolatedTitlesStoreRule()
  private val compose = createAndroidComposeRule<HakusanActivity>()

  @get:Rule
  val rules: TestRule = RuleChain
    .outerRule(database)
    .around(compose)

  @Test
  fun LikeBuildsTheDefaultShelfAndLibraryDetailsReturnToIt() {
    installIsolatedApplicationGraph()

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

  private fun installIsolatedApplicationGraph() {
    val graph = createApplicationGraph(
      sourceRegistry = SourceRegistry.of(applicationSourceBackends()),
      titles = database.store.titles,
    )
    compose.activityRule.scenario.onActivity { activity ->
      val catalogModel = ViewModelProvider(
        owner = activity,
        factory = CatalogPresentationModel.factory(
          browseScreenService = { graph.browseScreenService },
          titleDetailsScreenService = {
            graph.titleDetailsScreenService
          },
        ),
      )[CATALOG_MODEL_KEY, CatalogPresentationModel::class.java]
      val libraryModel = ViewModelProvider(
        owner = activity,
        factory = LibraryPresentationModel.factory(
          libraryScreenService = { graph.libraryScreenService },
          titleDetailsScreenService = {
            graph.titleDetailsScreenService
          },
        ),
      )[LIBRARY_MODEL_KEY, LibraryPresentationModel::class.java]

      activity.setContent {
        HakusanApp(
          catalogPresentationModel = { catalogModel },
          libraryPresentationModel = { libraryModel },
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

    private lateinit var context: JourneyDatabaseContext

    override fun before() {
      val targetContext = InstrumentationRegistry.getInstrumentation()
        .targetContext
        .applicationContext
      context = JourneyDatabaseContext(targetContext)
      check(
        context.getDatabasePath(DATABASE_NAME) !=
          targetContext.getDatabasePath(DATABASE_NAME),
      ) {
        "The Library journey must not own the application database."
      }
      context.prepareDatabase(DATABASE_NAME)
      store = openTitlesStore(context)
    }

    override fun after() {
      if (::store.isInitialized) {
        store.close()
      }
      if (::context.isInitialized) {
        check(context.deleteDatabase(DATABASE_NAME)) {
          "Unable to delete the isolated Library journey database."
        }
      }
    }
  }

  private class JourneyDatabaseContext(
    base: Context,
  ) : ContextWrapper(base) {
    private val databaseDirectory = File(
      base.cacheDir,
      "hakusan-library-journey-databases",
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
        "Unable to create the isolated Library journey database directory."
      }
      check(deleteDatabase(name)) {
        "Unable to reset the isolated Library journey database."
      }
    }
  }

  private companion object {
    const val CATALOG_MODEL_KEY = "library-journey-catalog"
    const val LIBRARY_MODEL_KEY = "library-journey-library"
    const val DATABASE_NAME = "hakusan.db"
    const val UI_TIMEOUT_MILLIS = 30_000L
  }
}
