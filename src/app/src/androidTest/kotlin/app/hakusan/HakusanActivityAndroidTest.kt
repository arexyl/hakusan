package app.hakusan

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
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
  fun catalogDetailsBackReturnsToBrowse() {
    openDetails()

    compose.onNodeWithText(
      "Deterministic debug content with canonical chapter ordering.",
    ).assertExists()
    compose.onNodeWithContentDescription("Catalog").assertDoesNotExist()
    compose.onNodeWithContentDescription("Library").assertDoesNotExist()
    compose.onNodeWithText("Continue").assertExists()
    compose.onNodeWithText("Like").assertExists()

    val openingTop = compose.onNodeWithContentDescription("Chapter 10")
      .fetchSemanticsNode()
      .boundsInRoot
      .top
    val middleTop = compose.onNodeWithContentDescription("Chapter 2")
      .fetchSemanticsNode()
      .boundsInRoot
      .top
    val finalTop = compose.onNodeWithContentDescription("Chapter 1")
      .fetchSemanticsNode()
      .boundsInRoot
      .top
    assertTrue(openingTop < middleTop)
    assertTrue(middleTop < finalTop)

    compose.onNodeWithText("Back").performClick()
    compose.onNodeWithText("Canonical Order Fixture").assertExists()
    compose.onNodeWithContentDescription("Catalog").assertIsSelected()

    compose.onNodeWithText("Back").performClick()
    compose.onNodeWithText("Sources").assertExists()
    compose.onNodeWithText("Deterministic source").assertExists()
  }

  @Test
  fun detailsRouteSurvivesActivityRecreation() {
    openDetails()

    compose.activityRule.scenario.recreate()

    compose.onNodeWithText(
      "Deterministic debug content with canonical chapter ordering.",
    ).assertExists()
    waitForContentDescription("Chapter 10")
    compose.activityRule.scenario.onActivity { activity ->
      activity.onBackPressedDispatcher.onBackPressed()
    }
    compose.onNodeWithText("Canonical Order Fixture").assertExists()
    compose.onNodeWithText("Back").performClick()
    compose.onNodeWithText("Sources").assertExists()
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

  private fun openDetails() {
    compose.onNodeWithContentDescription("Catalog").performClick()
    compose.onNodeWithText("Deterministic source").performClick()
    waitForText("Canonical Order Fixture")
    compose.onNodeWithText("Canonical Order Fixture").performClick()
    waitForContentDescription("Chapter 10")
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

  private companion object {
    const val UI_TIMEOUT_MILLIS = 30_000L
  }
}
