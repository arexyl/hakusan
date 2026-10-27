package app.hakusan

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.hakusan.ui.HakusanApp
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals

/** The Compose rule of the device suites, hosted by the real Activity. */
internal typealias ShellRule =
  AndroidComposeTestRule<ActivityScenarioRule<HakusanActivity>, HakusanActivity>

/** The bound of every wait for the user interface. */
internal const val UI_TIMEOUT_MILLIS = 5_000L

/** The owner count of a recreation that was not observed. */
private const val NOT_OBSERVED = -1

/** Matches the browsing island, the one selectable group of the shell. */
internal fun isBrowsingIsland(): SemanticsMatcher =
  SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)

/** Matches the vertically scrolling primary list of a screen. */
internal fun isPrimaryList(): SemanticsMatcher =
  SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

/** Matches the horizontally scrolling carousel of a Library shelf. */
internal fun isCarousel(): SemanticsMatcher =
  SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)

/** Selects the destination with [label] in the island and waits until idle. */
internal fun ShellRule.selectDestination(label: String) {
  onNodeWithContentDescription(label).performClick()
  waitForIdle()
}

/** Waits until a node with [text] is in the semantics tree. */
internal fun ShellRule.waitForText(text: String) {
  waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
    onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
  }
}

/** Returns the bounds of this node in the root. */
internal fun SemanticsNodeInteraction.bounds(): Rect =
  fetchSemanticsNode().boundsInRoot

/** Runs [action] on the Activity's Back dispatcher on the main thread. */
internal fun <T> ShellRule.onBackDispatcher(
  action: OnBackPressedDispatcher.() -> T
): T = runOnUiThread { activity.onBackPressedDispatcher.action() }

/**
 * Replaces the content of the real Activity with the shell under [override],
 * through the public shell entry and the providers of [services], and waits
 * until idle.
 *
 * The Activity sets its content itself, because the Compose rule refuses an
 * Activity that already has content. The override changes the configuration of
 * the composition only; the window theme stays a manual check.
 */
internal fun ShellRule.setShellContent(
  services: FakeScreenServices,
  override: DeviceConfigurationOverride,
) {
  runOnUiThread {
    activity.setContent {
      DeviceConfigurationOverride(override) {
        HakusanApp(
          browse = { services.browse },
          library = { services.library },
        )
      }
    }
  }

  waitForIdle()
}

/**
 * Simulates process death, and waits until the recreated Activity is resumed
 * and idle.
 *
 * It moves the Activity to CREATED, which saves its state, clears its ViewModel
 * store on the main thread, and recreates the Activity from the saved state.
 * ActivityScenario.recreate first resumes the old Activity, so the old
 * composition may run again over the cleared store, and the new Activity takes
 * over that store object. An Activity-scoped owner created in that window, such
 * as the Library owner, would then serve the new Activity as if it had
 * survived, and no later count would reveal it. Therefore the helper reads the
 * owners that [services] created when the new Activity is about to be created,
 * and fails unless no owner was created since the clear.
 *
 * Classify that failure as an invalid simulation, not as a restoration defect:
 * real process death leaves no owner behind, so the run proves nothing about
 * restoration. Fix the procedure before judging the suite.
 *
 * The process and its Application-scoped state survive: the screen-service
 * source, the graph, and the fakes. End-to-end restoration needs a real process
 * restart.
 */
internal fun ShellRule.simulateProcessDeath(services: FakeScreenServices) {
  val scenario = activityRule.scenario

  scenario.moveToState(Lifecycle.State.CREATED)

  val creationsAtClear = AtomicInteger()

  scenario.onActivity { activity ->
    activity.viewModelStore.clear()
    creationsAtClear.set(services.ownerCreations)
  }

  val creationsAtRecreation = AtomicInteger(NOT_OBSERVED)

  // The monitor holds the callback weakly; the finally block keeps it alive.
  val recreation = ActivityLifecycleCallback { activity, stage ->
    if (stage == Stage.PRE_ON_CREATE && activity is HakusanActivity) {
      creationsAtRecreation.compareAndSet(NOT_OBSERVED, services.ownerCreations)
    }
  }

  val monitor = ActivityLifecycleMonitorRegistry.getInstance()

  monitor.addLifecycleCallback(recreation)

  try {
    scenario.recreate()
  } finally {
    monitor.removeLifecycleCallback(recreation)
  }

  assertNotEquals(
    "The creation of the new Activity must be observed.",
    NOT_OBSERVED,
    creationsAtRecreation.get(),
  )

  assertEquals(
    "No owner may be created between the clear and the recreation.",
    creationsAtClear.get(),
    creationsAtRecreation.get(),
  )

  scenario.moveToState(Lifecycle.State.RESUMED)
  waitForIdle()
}
