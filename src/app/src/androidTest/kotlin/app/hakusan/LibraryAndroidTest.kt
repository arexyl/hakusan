package app.hakusan

import android.os.Bundle
import android.os.Parcel
import android.util.SparseArray
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.hakusan.sdk.LibraryResumeState
import app.hakusan.sdk.LibraryScreen
import app.hakusan.sdk.LibraryShelfMembers
import app.hakusan.sdk.LibraryTitleItem
import app.hakusan.sdk.LibraryTitleProgress
import app.hakusan.sdk.ScreenShelfId
import app.hakusan.sdk.ScreenSourceId
import app.hakusan.sdk.ScreenTitleId
import app.hakusan.sdk.ScreenTitleKey
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * The Library screen on the real Activity: order, counts, empty states,
 * reachability above the island, retained scroll positions, and saved state
 * without shelf keys.
 */
@RunWith(AndroidJUnit4::class)
class LibraryAndroidTest {
  private val services = FakeScreenServices()

  private val compose = createAndroidComposeRule<HakusanActivity>()

  @get:Rule
  val rules: RuleChain =
    RuleChain.outerRule(ScreenServicesRule(services)).around(compose)

  @Test
  fun rendersShelvesTitlesAndCountsInTheOrderOfTheScreen() {
    compose.waitForIdle()
    compose.onNodeWithText("Loading Library").assertExists()

    compose
      .onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
      .assertExists()

    val screen = orderedLibrary()

    services.libraryService.publish(screen)
    compose.waitForText("Reading now")

    val headerTops =
      screen.shelves.map { shelf ->
        val name = shelf.name.ifBlank { "Unnamed shelf" }

        compose.onNode(isHeading() and hasText(name)).bounds().top
      }

    val inputOrder = listOf(Shelf.READING, Shelf.LATER, Shelf.UNNAMED)

    assertEquals(headerTops.sorted(), headerTops)
    assertEquals(inputOrder.reversed(), shelfIds(screen))
    compose.onNodeWithText("2 titles").assertExists()
    compose.onNodeWithText("1 title").assertExists()
    compose.onNodeWithText("0 titles").assertExists()
    compose.onNodeWithText("No titles in this shelf.").assertExists()
    compose.onAllNodesWithText("Alpha").assertCountEquals(2)
    compose.onAllNodesWithText("1 of 3 chapters read").assertCountEquals(2)
    compose.onAllNodesWithText("Saved position available").assertCountEquals(2)
    compose.onNodeWithText("0 of 1 chapter read").assertExists()
    compose.onNodeWithText("No saved position").assertExists()
    compose.onNodeWithText("Unknown source").assertExists()

    val beta = compose.onNodeWithText("Beta").bounds()

    val alphaBesideBeta =
      compose.onAllNodesWithText("Alpha").fetchSemanticsNodes().single { node ->
        node.boundsInRoot.top == beta.top
      }

    assertTrue(alphaBesideBeta.boundsInRoot.right <= beta.left)
  }

  @Test
  fun noShelvesDiffersFromOneEmptyShelf() {
    services.libraryService.publish(library(emptyList()))
    compose.waitForText("Your Library is empty")
    compose.onNodeWithText("No titles in this shelf.").assertDoesNotExist()

    val emptyShelf = LibraryShelfMembers(ScreenShelfId(1L), "Default", setOf())

    services.libraryService.publish(library(emptyList(), emptyShelf))
    compose.waitForText("No titles in this shelf.")
    compose.onNode(isHeading() and hasText("Default")).assertExists()
    compose.onNodeWithText("0 titles").assertExists()
    compose.onNodeWithText("Your Library is empty").assertDoesNotExist()
  }

  @Test
  fun lastShelfStaysReachableAboveTheIslandAtFontScaleTwo() {
    assertLastShelfReachable(DeviceConfigurationOverride.FontScale(2f))
  }

  @Test
  fun lastShelfStaysReachableAboveTheIslandAtACompactHeight() {
    val compact = DpSize(width = 360.dp, height = 400.dp)

    assertLastShelfReachable(DeviceConfigurationOverride.ForcedSize(compact))
  }

  @Test
  fun primaryListPositionSurvivesATabSwitchAndRecreation() {
    services.libraryService.publish(shelvesWithOneTitle(count = 12))
    compose.waitForText("Shelf 00")
    compose.onNode(isPrimaryList()).performScrollToIndex(8)
    compose.waitForIdle()

    val top = shelfHeaderTop("Shelf 08")

    compose.selectDestination("Catalog")
    compose.selectDestination("Library")
    assertEquals(top, shelfHeaderTop("Shelf 08"))
    compose.activityRule.scenario.recreate()
    compose.waitForIdle()
    assertEquals(top, shelfHeaderTop("Shelf 08"))
  }

  /**
   * At the end of the list, a first frame after recreation measured without the
   * overlay's clearance or the window insets would clamp the restored position
   * by the missing amount, on every screen size and inset. After process death
   * the list first measures after the Library loads, so that half checks that
   * the saved index and offset survive restoration.
   */
  @Test
  fun primaryListEndSurvivesRecreationAndProcessDeath() {
    val screen = shelvesWithOneTitle(count = 12)

    services.libraryService.publish(screen)
    compose.waitForText("Shelf 00")

    val list = compose.onNode(isPrimaryList())

    list.performScrollToIndex(screen.shelves.lastIndex)

    list.performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy ->
      scrollBy(0f, BEYOND_THE_END)
    }

    compose.waitForIdle()

    val top = shelfHeaderTop("Shelf 11")

    compose.activityRule.scenario.recreate()
    compose.waitForIdle()
    assertEquals(top, shelfHeaderTop("Shelf 11"))
    compose.simulateProcessDeath(services)
    compose.waitForText("Shelf 11")
    assertEquals(top, shelfHeaderTop("Shelf 11"))
  }

  @Test
  fun carouselPositionSurvivesRecreationAndResetsAfterProcessDeath() {
    services.libraryService.publish(oneShelfWithTitles(count = 30))
    compose.waitForText("Title 00")

    val start = compose.onNodeWithText("Title 00").bounds().left

    compose.onNode(isCarousel()).performScrollToIndex(7)
    compose.waitForIdle()
    assertEquals(start, compose.onNodeWithText("Title 07").bounds().left)
    compose.activityRule.scenario.recreate()
    compose.waitForIdle()
    assertEquals(start, compose.onNodeWithText("Title 07").bounds().left)
    compose.simulateProcessDeath(services)
    compose.waitForText("Title 00")
    assertEquals(start, compose.onNodeWithText("Title 00").bounds().left)
  }

  @Test
  fun carouselPositionsSurviveADestinationSwitch() {
    services.libraryService.publish(carouselShelves(count = 2))
    compose.waitForText(carouselTitle(shelf = 0, index = 0))

    val start = carouselItemLeft(shelf = 0, index = 0)

    scrollCarousel(shelf = 0, index = 7, start = start)
    scrollCarousel(shelf = 1, index = 3, start = start)
    compose.selectDestination("Catalog")
    compose.selectDestination("Library")
    assertEquals(start, carouselItemLeft(shelf = 0, index = 7))
    assertEquals(start, carouselItemLeft(shelf = 1, index = 3))
  }

  @Test
  fun carouselPositionSurvivesScrollingAwayFromItsShelf() {
    val screen = carouselShelves(count = 12)

    services.libraryService.publish(screen)
    compose.waitForText(carouselTitle(shelf = 0, index = 0))

    val start = carouselItemLeft(shelf = 0, index = 0)

    val list = compose.onNode(isPrimaryList())

    scrollCarousel(shelf = 0, index = 7, start = start)
    // The shelf must leave composition, which disposes its carousel.
    list.performScrollToIndex(screen.shelves.lastIndex)
    compose.waitForIdle()
    compose.onNode(isHeading() and hasText(shelfName(0))).assertDoesNotExist()
    compose.onAllNodesWithText(carouselTitle(0, 7)).assertCountEquals(0)
    list.performScrollToIndex(0)
    compose.waitForIdle()
    assertEquals(start, carouselItemLeft(shelf = 0, index = 7))
  }

  /**
   * Scrolled carousels, shelves that left composition, and a destination switch
   * put no shelf key into the state that the Activity saves.
   */
  @Test
  fun savedStateHoldsNoShelfKey() {
    val screen = carouselShelves(count = 12)

    services.libraryService.publish(screen)
    compose.waitForText(carouselTitle(shelf = 0, index = 0))

    val start = carouselItemLeft(shelf = 0, index = 0)

    val list = compose.onNode(isPrimaryList())

    scrollCarousel(shelf = 0, index = 7, start = start)
    scrollCarousel(shelf = 1, index = 3, start = start)
    list.performScrollToIndex(screen.shelves.lastIndex)
    compose.waitForIdle()
    compose.selectDestination("Catalog")
    compose.selectDestination("Library")

    val state = compose.savedState()

    val stackPaths =
      findInSavedState(state) { value -> value == CATALOG_SERIAL_NAME }

    assertTrue(
      "The scan must reach the saved back stacks.",
      stackPaths.isNotEmpty() &&
        parcelHoldsAny(state, textEncodings(CATALOG_SERIAL_NAME)),
    )

    for (shelf in screen.shelves) {
      val key = shelf.id.value

      val leaks = findInSavedState(state) { value -> value.holdsShelfKey(key) }

      assertEquals("A shelf key leaked at $leaks.", emptyList<String>(), leaks)

      assertFalse(
        "A shelf key leaked inside a saved value.",
        parcelHoldsAny(state, shelfKeyEncodings(key)),
      )
    }
  }

  private fun assertLastShelfReachable(override: DeviceConfigurationOverride) {
    val screen = shelvesWithOneTitle(count = 8)

    services.libraryService.publish(screen)
    compose.setShellContent(services, override)
    compose.waitForText("Shelf 00")

    val list = compose.onNode(isPrimaryList())

    list.performScrollToIndex(screen.shelves.lastIndex)

    list.performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy ->
      scrollBy(0f, BEYOND_THE_END)
    }

    compose.waitForIdle()

    val lastTitle = compose.onNodeWithText("Title 07")

    lastTitle.assertIsDisplayed()

    val island = compose.onNode(isBrowsingIsland()).bounds()

    assertTrue(lastTitle.bounds().bottom <= island.top)
  }

  private fun shelfHeaderTop(name: String): Float =
    compose.onNode(isHeading() and hasText(name)).bounds().top

  /**
   * Scrolls the carousel of [shelf], while it shows its first title, to
   * [index], and checks that the title at [index] then sits at [start].
   */
  private fun scrollCarousel(shelf: Int, index: Int, start: Float) {
    val firstTitle = hasText(carouselTitle(shelf, index = 0))

    val carousel = compose.onNode(isCarousel() and hasAnyDescendant(firstTitle))

    carousel.performScrollToIndex(index)
    compose.waitForIdle()
    assertEquals(start, carouselItemLeft(shelf, index))
  }

  private fun carouselItemLeft(shelf: Int, index: Int): Float =
    compose.onNodeWithText(carouselTitle(shelf, index)).bounds().left

  /**
   * Returns the state that the Activity saves now.
   *
   * Saving moves the lifecycle of a ComponentActivity to CREATED, which stops
   * lifecycle-aware collection. Call it as the last step of a test.
   */
  private fun ShellRule.savedState(): Bundle {
    val bundle = Bundle()

    val instrumentation = InstrumentationRegistry.getInstrumentation()

    runOnUiThread {
      instrumentation.callActivityOnSaveInstanceState(activity, bundle)
    }

    return bundle
  }
}

/** The shelf identities of [orderedLibrary]. */
private object Shelf {
  val READING = ScreenShelfId(1L)

  val UNNAMED = ScreenShelfId(2L)

  val LATER = ScreenShelfId(3L)
}

private fun shelfIds(screen: LibraryScreen): List<ScreenShelfId> =
  screen.shelves.map { shelf -> shelf.id }

/**
 * Returns a Library whose order differs from its input order: a shelf with two
 * titles, an unnamed shelf that shares one of them, and an empty shelf.
 */
private fun orderedLibrary(): LibraryScreen {
  val alpha = title(1, "Alpha", "Source", LibraryTitleProgress(3, 1, AVAILABLE))

  val beta = title(2, "Beta", "", LibraryTitleProgress(1, 0, NONE))

  return library(
    listOf(beta, alpha),
    LibraryShelfMembers(Shelf.READING, "Reading now", setOf(beta.id, alpha.id)),
    LibraryShelfMembers(Shelf.LATER, "Later", setOf()),
    LibraryShelfMembers(Shelf.UNNAMED, "", setOf(alpha.id)),
  )
}

/** Returns a Library of [count] shelves, each with its own title. */
private fun shelvesWithOneTitle(count: Int): LibraryScreen {
  val titles = (0 until count).map { index -> title(index, "Title %02d") }

  val shelves = titles.mapIndexed { index, title ->
    val id = ScreenShelfId(index + 1L)

    LibraryShelfMembers(id, shelfName(index), setOf(title.id))
  }

  return library(titles, *shelves.toTypedArray())
}

/** Returns a Library of one shelf with [count] titles. */
private fun oneShelfWithTitles(count: Int): LibraryScreen {
  val titles = (0 until count).map { index -> title(index, "Title %02d") }

  val ids = titles.mapTo(HashSet()) { title -> title.id }

  return library(titles, LibraryShelfMembers(ScreenShelfId(1L), "Shelf", ids))
}

/**
 * Returns a Library of [count] shelves, each with [CAROUSEL_LENGTH] titles of
 * its own. Its shelf keys start at [SHELF_KEY_BASE].
 */
private fun carouselShelves(count: Int): LibraryScreen {
  val titles =
    (0 until count).map { shelf ->
      (0 until CAROUSEL_LENGTH).map { index ->
        val suffix = shelf * CAROUSEL_LENGTH + index

        val progress = LibraryTitleProgress(1, 0, NONE)

        title(suffix, carouselTitle(shelf, index), "Source", progress)
      }
    }

  val shelves = titles.mapIndexed { shelf, members ->
    val id = ScreenShelfId(SHELF_KEY_BASE + shelf)

    val ids = members.mapTo(HashSet()) { title -> title.id }

    LibraryShelfMembers(id, shelfName(shelf), ids)
  }

  return library(titles.flatten(), *shelves.toTypedArray())
}

private fun shelfName(shelf: Int): String =
  "Shelf %02d".format(Locale.ROOT, shelf)

private fun carouselTitle(shelf: Int, index: Int): String =
  "Title %02d.%02d".format(Locale.ROOT, shelf, index)

private fun library(
  titles: List<LibraryTitleItem>,
  vararg shelves: LibraryShelfMembers,
): LibraryScreen = LibraryScreen.of(titles, shelves.asList(), Locale.ENGLISH)

/** Returns the title [index], named by formatting [pattern] with the index. */
private fun title(index: Int, pattern: String): LibraryTitleItem =
  title(
    index,
    pattern.format(Locale.ROOT, index),
    "Source",
    LibraryTitleProgress(1, 0, NONE),
  )

private fun title(
  suffix: Int,
  name: String,
  source: String,
  progress: LibraryTitleProgress,
): LibraryTitleItem {
  val id = ScreenTitleId(UUID(TITLE_ID_HIGH_BITS, Long.MIN_VALUE + suffix))

  val key = ScreenTitleKey(ScreenSourceId("app.hakusan.test.source"), name)

  return LibraryTitleItem(id, key, name, null, source, progress)
}

private val AVAILABLE = LibraryResumeState.AVAILABLE

private val NONE = LibraryResumeState.NONE

/** The high bits of a test title UUID: version 7. */
private const val TITLE_ID_HIGH_BITS = 0x0192a6f07c3e7d4aL

/** A scroll distance in pixels beyond the end of every test list. */
private const val BEYOND_THE_END = 100_000f

/** The number of titles on each shelf of [carouselShelves]. */
private const val CAROUSEL_LENGTH = 30

/**
 * The first shelf key of [carouselShelves]. Its high bytes spell "SHELF" in
 * ASCII, so no other saved number or text matches a shelf key by chance.
 */
private const val SHELF_KEY_BASE = 0x5348454C46000000L

/** The serial name of the Catalog root, held by every saved Catalog stack. */
private const val CATALOG_SERIAL_NAME = "catalog"

/** Returns whether this saved value is [key] or a text that contains it. */
private fun Any.holdsShelfKey(key: Long): Boolean {
  val decimal = key.toString()

  val hexadecimal = java.lang.Long.toHexString(key)

  return this == key ||
    this is String &&
      (contains(decimal) || contains(hexadecimal, ignoreCase = true))
}

/**
 * Returns the encodings of [key] that a parcel can hold: both byte orders of
 * the binary value, and its decimal and hexadecimal text.
 */
private fun shelfKeyEncodings(key: Long): List<ByteArray> {
  val binary =
    listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN).map { order ->
      ByteBuffer.allocate(Long.SIZE_BYTES).order(order).putLong(key).array()
    }

  val hexadecimal = java.lang.Long.toHexString(key)

  val texts = listOf(key.toString(), hexadecimal, hexadecimal.uppercase())

  return binary + texts.flatMap(::textEncodings)
}

/** Returns [text] in the UTF-8 and UTF-16 forms that a parcel writes. */
private fun textEncodings(text: String): List<ByteArray> =
  listOf(text.toByteArray(Charsets.UTF_8), text.toByteArray(Charsets.UTF_16LE))

/**
 * Returns whether [state], written to a parcel, holds any of [patterns].
 *
 * Writing the state also writes out the Parcelable and Serializable values that
 * [findInSavedState] does not open, such as a saved MutableState.
 */
private fun parcelHoldsAny(state: Bundle, patterns: List<ByteArray>): Boolean {
  val parcel = Parcel.obtain()

  val bytes =
    try {
      parcel.writeBundle(state)

      parcel.marshall()
    } finally {
      parcel.recycle()
    }

  return patterns.any { pattern -> bytes.holdsBytes(pattern) }
}

private fun ByteArray.holdsBytes(pattern: ByteArray): Boolean =
  (0..size - pattern.size).any { start ->
    pattern.indices.all { index -> this[start + index] == pattern[index] }
  }

/**
 * Returns the paths of every value in [state] that [matches] accepts, in nested
 * bundles, maps, collections, arrays, and sparse arrays, map keys included.
 *
 * It opens no Parcelable or Serializable value; [parcelHoldsAny] covers those.
 */
private fun findInSavedState(
  state: Bundle,
  matches: (Any) -> Boolean,
): List<String> {
  val found = ArrayList<String>()

  collectMatches(state, "", matches, found)

  return found
}

@Suppress("DEPRECATION")
private fun collectMatches(
  value: Any?,
  path: String,
  matches: (Any) -> Boolean,
  found: MutableList<String>,
) {
  if (value == null) {
    return
  }

  if (matches(value)) {
    found += path
  }

  when (value) {
    is Bundle ->
      for (key in value.keySet()) {
        collectMatches(key, "$path/key", matches, found)
        collectMatches(value.get(key), "$path/$key", matches, found)
      }

    is Map<*, *> ->
      for ((key, element) in value) {
        collectMatches(key, "$path{key}", matches, found)
        collectMatches(element, "$path{$key}", matches, found)
      }

    is Iterable<*> ->
      value.forEachIndexed { index, element ->
        collectMatches(element, "$path[$index]", matches, found)
      }

    is Array<*> ->
      value.forEachIndexed { index, element ->
        collectMatches(element, "$path[$index]", matches, found)
      }

    is LongArray ->
      value.forEachIndexed { index, element ->
        collectMatches(element, "$path[$index]", matches, found)
      }

    is SparseArray<*> ->
      for (index in 0 until value.size()) {
        collectMatches(value.valueAt(index), "$path[$index]", matches, found)
      }
  }
}
