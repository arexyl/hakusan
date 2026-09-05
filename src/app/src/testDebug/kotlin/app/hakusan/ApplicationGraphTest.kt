package app.hakusan

import app.hakusan.debug.source.DeterministicSource
import app.hakusan.titles.Titles
import java.lang.reflect.Proxy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ApplicationGraphTest {
  @Test
  fun `graph scopes each screen responsibility to one graph`() {
    val registry = SourceRegistry.of(listOf(DeterministicSource()))
    val first = createApplicationGraph(registry, unusedTitles())
    val second = createApplicationGraph(registry, unusedTitles())

    assertSame(first.browseScreenService, first.browseScreenService)
    assertSame(first.libraryScreenService, first.libraryScreenService)
    assertSame(
      first.titleDetailsScreenService,
      first.titleDetailsScreenService,
    )
    assertNotSame(first.browseScreenService, second.browseScreenService)
    assertNotSame(first.libraryScreenService, second.libraryScreenService)
    assertNotSame(
      first.titleDetailsScreenService,
      second.titleDetailsScreenService,
    )
    assertEquals(
      "app.hakusan.debug.source",
      first.browseScreenService.catalog().sources.single().id.value,
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
