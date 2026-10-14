package app.hakusan

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.hakusan.debug.source.DeterministicFixture
import app.hakusan.sdk.AddToLibraryScreenResult
import app.hakusan.sdk.BrowseScreen
import app.hakusan.sdk.BrowseScreenResult
import app.hakusan.sdk.BrowseTitleItem
import app.hakusan.sdk.CatalogSourceItem
import app.hakusan.sdk.ContinueSelectionResult
import app.hakusan.sdk.ContinueState
import app.hakusan.sdk.ContinueTarget
import app.hakusan.sdk.DetailsScreenResult
import app.hakusan.sdk.ScreenChapterKey
import app.hakusan.sdk.ScreenReadingStart
import app.hakusan.sdk.ScreenSourceId
import app.hakusan.sdk.ScreenTitleId
import app.hakusan.sdk.ScreenTitleKey
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The composition of the Debug application through the graph of its
 * [HakusanApplication], with the real titles store of the process.
 *
 * The store is the installed application's `hakusan.db`, so every assertion
 * holds whatever the database already holds.
 */
@RunWith(AndroidJUnit4::class)
class ApplicationCompositionTest {
  private val application: HakusanApplication =
    ApplicationProvider.getApplicationContext()

  private val debugSource = ScreenSourceId("app.hakusan.debug.source")

  @Test
  fun processKeepsOneGraph() {
    assertSame(application.graph, application.graph)
  }

  @Test
  fun catalogListsTheDebugSource() {
    val catalog = application.graph.browseScreenService.catalog()

    assertEquals(listOf(debugSource), catalog.sources.map { it.id })
  }

  @Test
  fun browseReturnsTheFixtureTitle(): Unit = runBlocking {
    val service = application.graph.browseScreenService

    val result = service.loadBrowse(debugSource)

    val source =
      CatalogSourceItem(debugSource, DeterministicFixture.displayName)

    val title = DeterministicFixture.title

    val item =
      BrowseTitleItem(
        ScreenTitleKey(debugSource, title.key.key),
        title.displayName,
      )

    val screen = BrowseScreen.of(source, listOf(item))

    assertEquals(BrowseScreenResult.Success(screen), result)
  }

  @Test
  fun libraryEmitsFromTheStore(): Unit = runBlocking {
    val service = application.graph.libraryScreenService

    val screen = service.observeLibrary().first()

    val fixtureSource = DeterministicFixture.identity.toScreenSourceId()

    // A fresh install stores no Library title, so the loop checks source names
    // only once a test of the same run has added a title.
    for (title in screen.titlesById.values) {
      val expected =
        if (title.key.sourceId == fixtureSource) {
          DeterministicFixture.displayName
        } else {
          ""
        }

      assertEquals(expected, title.sourceDisplayName)
    }
  }

  @Test
  fun detailsReachTheLibraryAndContinue(): Unit = runBlocking {
    val graph = application.graph
    val title = DeterministicFixture.title

    val titleKey = title.key.toScreenTitleKey()

    val result = graph.titleDetailsScreenService.loadDetails(titleKey)

    val details =
      (result as? DetailsScreenResult.Success)?.screen
        ?: throw AssertionError("Expected details, got $result")

    val chapterKeys =
      DeterministicFixture.chapters.map { fixture ->
        ScreenChapterKey(titleKey, fixture.chapter.key.key)
      }

    val chapterNames =
      DeterministicFixture.chapters.map { fixture ->
        fixture.chapter.displayName
      }

    assertEquals(titleKey, details.key)
    assertEquals(DeterministicFixture.displayName, details.sourceDisplayName)
    assertEquals(title.displayName, details.displayName)
    assertEquals(DeterministicFixture.description, details.description)
    assertEquals(chapterKeys, details.chapters.map { it.key })
    assertEquals(chapterNames, details.chapters.map { it.displayName })

    // No flow of this application writes read status or a position yet.
    val firstUnread = details.chapters.first { !it.isRead }

    val target =
      ContinueTarget(
        titleId = details.id,
        chapterId = firstUnread.id,
        chapterKey = firstUnread.key,
        start = ScreenReadingStart.Beginning,
      )

    assertEquals(ContinueState.Ready(target), details.continueState)

    val library = graph.libraryScreenService

    // A title that an earlier run added stays in the Library, and the Add
    // succeeds with its current membership.
    assertEquals(
      AddToLibraryScreenResult.Success,
      library.addToLibrary(details.id),
    )

    val screen = library.observeLibrary().first()

    val item = screen.titlesById.getValue(details.id)

    assertEquals(titleKey, item.key)
    assertEquals(DeterministicFixture.displayName, item.sourceDisplayName)
    assertTrue(details.id in library.observeLibraryTitleIds().first())

    val selection = graph.continueSelectionService.selectContinue(details.id)

    assertEquals(ContinueSelectionResult.Selected(target), selection)
  }

  @Test
  fun addingAnUnknownTitleFindsNoTitle(): Unit = runBlocking {
    val service = application.graph.libraryScreenService

    // A UUIDv7 with a zero timestamp, which the store never mints.
    val unknown = ScreenTitleId(UUID(0x7000L, Long.MIN_VALUE))

    val result = service.addToLibrary(unknown)

    assertEquals(AddToLibraryScreenResult.TitleNotFound, result)
  }
}
