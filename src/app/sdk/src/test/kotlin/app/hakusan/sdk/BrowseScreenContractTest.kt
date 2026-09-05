package app.hakusan.sdk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class BrowseScreenContractTest {
  private val source = CatalogSourceItem(
    id = ScreenSourceId("source"),
    displayName = "Source",
  )

  @Test
  fun `Catalog and browse preserve explicit order and own their lists`() {
    val secondSource = CatalogSourceItem(
      id = ScreenSourceId("second"),
      displayName = "Second",
    )
    val mutableSources = mutableListOf(secondSource, source)
    val catalog = CatalogScreen.of(mutableSources)
    val firstTitle = title("first", "Shared name")
    val secondTitle = title("second", "Shared name")
    val mutableTitles = mutableListOf(firstTitle, secondTitle)
    val browse = BrowseScreen.of(source, mutableTitles)

    mutableSources.reverse()
    mutableTitles.clear()

    assertEquals(listOf(secondSource, source), catalog.sources)
    assertEquals(listOf(firstTitle, secondTitle), browse.titles)
    assertThrows(UnsupportedOperationException::class.java) {
      (catalog.sources as MutableList).clear()
    }
    assertThrows(UnsupportedOperationException::class.java) {
      (browse.titles as MutableList).clear()
    }
  }

  @Test
  fun `empty Catalog and browse snapshots are valid`() {
    assertEquals(
      emptyList<CatalogSourceItem>(),
      CatalogScreen.of(emptyList()).sources,
    )
    assertEquals(
      emptyList<BrowseTitleItem>(),
      BrowseScreen.of(source, emptyList()).titles,
    )
  }

  @Test
  fun `Catalog rejects duplicate source identities`() {
    assertThrows(IllegalArgumentException::class.java) {
      CatalogScreen.of(
        listOf(
          source,
          source.copy(displayName = "Conflicting name"),
        ),
      )
    }
  }

  @Test
  fun `browse rejects duplicate and foreign title keys`() {
    val title = title("title", "Title")
    assertThrows(IllegalArgumentException::class.java) {
      BrowseScreen.of(
        source,
        listOf(title, title.copy(displayName = "Conflicting name")),
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      BrowseScreen.of(
        source,
        listOf(
          BrowseTitleItem(
            key = ScreenTitleKey(ScreenSourceId("other"), "title"),
            displayName = "Title",
          ),
        ),
      )
    }
  }

  private fun title(
    key: String,
    displayName: String,
  ): BrowseTitleItem = BrowseTitleItem(
    key = ScreenTitleKey(source.id, key),
    displayName = displayName,
  )
}
