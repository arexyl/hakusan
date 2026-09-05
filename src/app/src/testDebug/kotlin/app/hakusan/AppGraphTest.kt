package app.hakusan

import app.hakusan.debug.source.DeterministicSource
import app.hakusan.titles.Titles
import java.lang.reflect.Proxy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AppGraphTest {
  @Test
  fun `graph scopes each screen responsibility to one graph`() {
    val registry = SourceRegistry.of(listOf(DeterministicSource()))
    val first = createAppGraph(registry, unusedTitles())
    val second = createAppGraph(registry, unusedTitles())

    assertSame(first.browseService, first.browseService)
    assertSame(first.libraryService, first.libraryService)
    assertSame(
      first.detailsService,
      first.detailsService,
    )
    assertNotSame(first.browseService, second.browseService)
    assertNotSame(first.libraryService, second.libraryService)
    assertNotSame(
      first.detailsService,
      second.detailsService,
    )
    assertEquals(
      "app.hakusan.debug.source",
      first.browseService.catalog().sources.single().id.value,
    )
  }

  @Test
  fun `registry rejects competing backends for one source identity`() {
    assertThrows(IllegalArgumentException::class.java) {
      SourceRegistry.of(
        listOf(
          DeterministicSource(),
          DeterministicSource(),
        ),
      )
    }
  }

  @Suppress("UNCHECKED_CAST")
  private fun unusedTitles(): Titles = Proxy.newProxyInstance(
    Titles::class.java.classLoader,
    arrayOf(Titles::class.java),
  ) { _, method, _ ->
    error("Unexpected Titles call: ${method.name}")
  } as Titles
}
