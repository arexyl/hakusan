package app.hakusan

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HakusanActivityAndroidTest {
  @get:Rule
  val compose = createAndroidComposeRule<HakusanActivity>()

  @Test
  fun startsInLibraryWithoutDeferredActions() {
    compose.onNodeWithContentDescription("Library").assertIsSelected()
    compose.onNodeWithContentDescription("Catalog").assertIsNotSelected()
    compose.onNodeWithContentDescription("Settings").assertDoesNotExist()
    compose.onNodeWithContentDescription("Search").assertDoesNotExist()
  }

  @Test
  fun destinationSurvivesActivityRecreation() {
    compose.onNodeWithContentDescription("Catalog").performClick()
    compose.waitForIdle()
    compose.onNodeWithContentDescription("Catalog").assertIsSelected()
    compose.onNodeWithContentDescription("Library").assertIsNotSelected()

    compose.activityRule.scenario.recreate()
    compose.waitForIdle()

    compose.onNodeWithContentDescription("Catalog").assertIsSelected()
    compose.onAllNodesWithText("Catalog").assertCountEquals(2)
  }

  @Test
  fun backAtDestinationRootFinishesActivity() {
    lateinit var launchedActivity: HakusanActivity
    compose.activityRule.scenario.onActivity { activity ->
      launchedActivity = activity
      activity.onBackPressedDispatcher.onBackPressed()
    }
    compose.waitUntil(timeoutMillis = 5_000L) {
      launchedActivity.isFinishing
    }

    assertTrue(launchedActivity.isFinishing)
  }
}
