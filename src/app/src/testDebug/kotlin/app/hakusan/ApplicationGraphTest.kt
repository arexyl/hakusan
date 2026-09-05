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
  fun `graph shares one screen service across its three contracts`() {
    val registry = SourceRegistry.of(listOf(DeterministicSource()))
    val first = createApplicationGraph(registry, unusedTitles())
    val second = createApplicationGraph(registry, unusedTitles())

    assertSame(first.browseScreenService, first.libraryScreenService)
    assertSame(first.browseScreenService, first.titleDetailsScreenService)
    assertNotSame(first.browseScreenService, second.browseScreenService)
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
