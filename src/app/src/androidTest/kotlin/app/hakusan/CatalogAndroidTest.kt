package app.hakusan

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.hakusan.sdk.CatalogScreen
import app.hakusan.sdk.CatalogSourceItem
import app.hakusan.sdk.ScreenSourceId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** The Catalog overview on the real Activity: its order and its empty state. */
@RunWith(AndroidJUnit4::class)
class CatalogAndroidTest {
  private val services = FakeScreenServices()

  private val compose = createAndroidComposeRule<HakusanActivity>()

  @get:Rule
  val rules: RuleChain =
    RuleChain.outerRule(ScreenServicesRule(services)).around(compose)

  @Test
  fun overviewKeepsTheOrderOfTheCatalog() {
    val sources =
      listOf(
        CatalogSourceItem(ScreenSourceId("app.hakusan.test.zeta"), "Zeta"),
        CatalogSourceItem(ScreenSourceId("app.hakusan.test.alpha"), "Alpha"),
        CatalogSourceItem(ScreenSourceId("app.hakusan.test.blank"), " "),
        CatalogSourceItem(ScreenSourceId("app.hakusan.test.mu"), "Mu"),
      )

    services.browseService.catalog = CatalogScreen.of(sources)
    compose.waitForIdle()
    compose.selectDestination("Catalog")
    compose.onNode(isHeading() and hasText("Sources")).assertExists()

    val tops =
      listOf("Zeta", "Alpha", "Unnamed source", "Mu").map { name ->
        compose.onNodeWithText(name).bounds().top
      }

    assertEquals(tops.sorted(), tops)
    assertEquals(1, services.browseService.catalogReads)
  }

  @Test
  fun emptyCatalogShowsItsEmptyState() {
    val body = "No sources are available in this build."

    compose.waitForIdle()
    compose.selectDestination("Catalog")
    compose.onNodeWithText("No sources available").assertExists()
    compose.onNodeWithText(body).assertExists()
    compose.onNode(isHeading() and hasText("Sources")).assertDoesNotExist()
  }
}
