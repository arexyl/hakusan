package app.hakusan

import app.hakusan.extensions.SourceBrowseResult
import app.hakusan.extensions.SourceIdentity
import app.hakusan.extensions.SourceTitle
import app.hakusan.extensions.SourceTitleKey
import app.hakusan.sdk.BrowseScreenResult
import app.hakusan.sdk.ScreenSourceId
import dev.zacsweers.metro.createGraphFactory
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppGraphTest {
  private val source = SourceIdentity("source")

  private val registry =
    SourceRegistry.of(
      listOf(
        ScriptedBackend(
          identity = source,
          onBrowse = {
            SourceBrowseResult.create(
              source,
              listOf(SourceTitle(SourceTitleKey(source, "title"), "Title")),
            )
          },
        )
      )
    )

  @Test
  fun `each service is one instance per graph`() {
    val graph = graph(registry)

    assertSame(graph.browseScreenService, graph.browseScreenService)
  }

  @Test
  fun `separate graphs keep separate instances`() {
    val first = graph(registry)

    val second = graph(registry)

    assertNotSame(first.browseScreenService, second.browseScreenService)
  }

  @Test
  fun `the graph serves the registry that created it`() {
    val graph = graph(registry)

    assertSame(registry.catalog, graph.browseScreenService.catalog())
  }

  @Test
  fun `browsing through the graph never reaches Titles`() {
    val graph = graph(registry)

    val result = runBlocking {
      graph.browseScreenService.loadBrowse(ScreenSourceId("source"))
    }

    val screen =
      assertInstanceOf(BrowseScreenResult.Success::class.java, result).screen

    assertEquals(listOf("title"), screen.titles.map { it.key.sourceTitleKey })
  }

  @Test
  fun `the graph exposes only screen services`() {
    val accessors =
      AppGraph::class.java.methods.filter { it.parameterCount == 0 }

    assertTrue(accessors.isNotEmpty())

    for (accessor in accessors) {
      val type = accessor.returnType

      assertTrue(type.isInterface, accessor.name)
      assertEquals("app.hakusan.sdk", type.packageName, accessor.name)
    }
  }

  /** Creates a graph whose Titles fail on every call. */
  private fun graph(registry: SourceRegistry): AppGraph =
    createGraphFactory<AppGraph.Factory>().create(registry, FakeTitles())
}
