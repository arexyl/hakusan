package app.hakusan

import app.hakusan.debug.source.DeterministicFixture
import app.hakusan.sdk.BrowseScreenResult
import app.hakusan.sdk.BrowseTitleItem
import app.hakusan.sdk.CatalogSourceItem
import app.hakusan.sdk.ScreenSourceId
import app.hakusan.sdk.ScreenTitleKey
import dev.zacsweers.metro.createGraphFactory
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class DebugRegistrationTest {
  private val debugSource = ScreenSourceId("app.hakusan.debug.source")

  @Test
  fun `Debug registers exactly the deterministic source`() {
    val registry = SourceRegistry.of(sourceBackends())

    val expected =
      CatalogSourceItem(debugSource, DeterministicFixture.displayName)

    assertEquals(listOf(expected), registry.catalog.sources)
  }

  @Test
  fun `the graph lists and browses the deterministic source`() {
    val graph =
      createGraphFactory<AppGraph.Factory>()
        .create(SourceRegistry.of(sourceBackends()), FakeTitles())

    val service = graph.browseScreenService

    val result = runBlocking { service.loadBrowse(debugSource) }

    val screen =
      assertInstanceOf(BrowseScreenResult.Success::class.java, result).screen

    val title = DeterministicFixture.title

    val expected =
      BrowseTitleItem(
        ScreenTitleKey(debugSource, title.key.key),
        title.displayName,
      )

    assertEquals(listOf(debugSource), service.catalog().sources.map { it.id })
    assertEquals(listOf(expected), screen.titles)
  }
}
