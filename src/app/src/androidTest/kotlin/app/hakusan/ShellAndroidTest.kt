package app.hakusan

import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DarkMode
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.hakusan.sdk.LibraryScreen
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * The shell on the real Activity: the destinations, their owners, recreation,
 * simulated process death, root Back, and the light and dark themes.
 */
@RunWith(AndroidJUnit4::class)
class ShellAndroidTest {
  private val services = FakeScreenServices()

  private val compose = createAndroidComposeRule<HakusanActivity>()

  @get:Rule
  val rules: RuleChain =
    RuleChain.outerRule(ScreenServicesRule(services)).around(compose)

  @Test
  fun startsInLibraryWithTwoDestinationsAndNoDeferredActions() {
    compose.waitForIdle()
    compose.onNodeWithContentDescription("Library").assertIsSelected()
    compose.onNodeWithContentDescription("Catalog").assertIsNotSelected()
    compose.onAllNodes(isTab()).assertCountEquals(2)
    assertLabeledOnlyWhenSelected(selected = "Library", other = "Catalog")

    for (deferred in listOf("Settings", "Search")) {
      compose.onAllNodesWithContentDescription(deferred).assertCountEquals(0)
      compose.onAllNodesWithText(deferred).assertCountEquals(0)
    }
  }

  @Test
  fun switchingKeepsBothDestinationsAndTheirOwners() {
    compose.waitForIdle()
    compose.selectDestination("Catalog")
    assertLabeledOnlyWhenSelected(selected = "Catalog", other = "Library")
    compose.selectDestination("Library")
    compose.selectDestination("Catalog")
    compose.onNodeWithContentDescription("Catalog").assertIsSelected()
    compose.onNode(isHeading() and hasText("Catalog")).assertExists()
    assertEquals(1, services.browseProviderCalls)
    assertEquals(1, services.libraryProviderCalls)
    assertEquals(1, services.browseService.catalogReads)
  }

  @Test
  fun recreationKeepsTheSelectedDestination() {
    compose.waitForIdle()
    compose.selectDestination("Catalog")
    compose.activityRule.scenario.recreate()
    compose.waitForIdle()
    compose.onNodeWithContentDescription("Catalog").assertIsSelected()
    compose.onNode(isHeading() and hasText("Catalog")).assertExists()
    assertEquals(1, services.browseProviderCalls)
    assertEquals(1, services.libraryProviderCalls)
  }

  @Test
  fun providersRunOnlyWhenTheirOwnersAreCreated() {
    compose.waitForIdle()
    assertEquals(0, services.browseProviderCalls)
    assertEquals(1, services.libraryProviderCalls)
    services.libraryService.publish(emptyLibrary())
    compose.waitForText("Your Library is empty")
    compose.activityRule.scenario.recreate()
    compose.waitForIdle()
    compose.waitForText("Your Library is empty")
    assertEquals(0, services.browseProviderCalls)
    assertEquals(1, services.libraryProviderCalls)
  }

  /**
   * The stop timeout of the Library owner outlasts a recreation, so the
   * observation keeps running while no screen collects it.
   */
  @Test
  fun recreationKeepsTheLibraryObservation() {
    services.libraryService.publish(emptyLibrary())
    compose.waitForText("Your Library is empty")
    compose.activityRule.scenario.recreate()
    compose.waitForIdle()
    compose.waitForText("Your Library is empty")
    assertEquals(1, services.libraryService.collections)
    assertEquals(0, services.libraryService.stops)
  }

  @Test
  fun simulatedProcessDeathKeepsTheDestinationAndResubscribesTheLibrary() {
    services.libraryService.publish(emptyLibrary())
    compose.waitForText("Your Library is empty")
    assertEquals(1, services.libraryService.collections)
    compose.selectDestination("Catalog")
    compose.simulateProcessDeath(services)
    compose.onNodeWithContentDescription("Catalog").assertIsSelected()
    compose.onNode(isHeading() and hasText("Catalog")).assertExists()
    assertEquals(2, services.browseProviderCalls)
    compose.selectDestination("Library")
    compose.waitForText("Your Library is empty")
    assertEquals(2, services.libraryProviderCalls)
    assertEquals(2, services.libraryService.collections)
  }

  @Test
  fun rootsRegisterNoBackCallback() {
    compose.waitForIdle()
    assertFalse(compose.onBackDispatcher { hasEnabledCallbacks() })
    compose.selectDestination("Catalog")
    assertFalse(compose.onBackDispatcher { hasEnabledCallbacks() })
  }

  /**
   * Whether the platform then moves the task back or finishes the Activity
   * depends on the API level and the launch source: API 33 moves the task of a
   * launcher Activity back, and later levels only a task the home process
   * launched. Instrumentation does not launch from home, so the move to the
   * back stays a manual check from the launcher.
   */
  @Test
  fun backAtARootReachesThePlatform() {
    compose.waitForIdle()

    val activity = compose.activity

    assertFalse(compose.onBackDispatcher { hasEnabledCallbacks() })
    compose.onBackDispatcher { onBackPressed() }

    compose.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
      !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }
  }

  @Test
  fun backgroundFollowsTheLightAndDarkModes() {
    services.libraryService.publish(emptyLibrary())

    for (dark in listOf(false, true)) {
      val override = DeviceConfigurationOverride.DarkMode(dark)

      compose.setShellContent(services, override)
      compose.onNode(isHeading() and hasText("Library")).assertExists()
      compose.waitForText("Your Library is empty")

      val luminance = backgroundLuminance()

      assertEquals("Dark mode: $dark", dark, luminance < 0.5f)
    }
  }

  private fun assertLabeledOnlyWhenSelected(selected: String, other: String) {
    val selectedWithLabel =
      hasContentDescription(selected) and hasText(selected)

    val otherWithLabel = hasContentDescription(other) and hasText(other)

    compose.onNode(selectedWithLabel).assertExists()
    compose.onNode(otherWithLabel).assertDoesNotExist()
    compose.onNodeWithContentDescription(other).assertExists()
  }

  /** Returns the luminance of the shell's background in the top corner. */
  private fun backgroundLuminance(): Float {
    val pixels = compose.onRoot().captureToImage().toPixelMap()

    return pixels[1, 1].luminance()
  }
}

/** Matches a destination tab of the island. */
private fun isTab(): SemanticsMatcher =
  SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

/** Returns the committed Library without shelves. */
private fun emptyLibrary(): LibraryScreen =
  LibraryScreen.of(emptyList(), emptyList(), Locale.ENGLISH)
