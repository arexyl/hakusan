package app.hakusan.sdk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BrowseScreenTest {
  private val catalogSource = CatalogSourceItem(source, "Source")

  @Test
  fun catalogKeepsAnOwnedCopyInTheGivenOrder() {
    val sources =
      mutableListOf(
        CatalogSourceItem(otherSource, "Zeta"),
        CatalogSourceItem(source, "Alpha"),
      )

    val expected = sources.toList()

    val catalog = CatalogScreen.of(sources)

    sources.reverse()
    assertEquals(expected, catalog.sources)

    assertThrows<UnsupportedOperationException> {
      (catalog.sources as MutableList).clear()
    }
  }

  @Test
  fun emptyCatalogIsValid() {
    assertTrue(CatalogScreen.of(emptyList()).sources.isEmpty())
  }

  @Test
  fun catalogRejectsARepeatedSource() {
    assertThrows<IllegalArgumentException> {
      CatalogScreen.of(
        listOf(
          CatalogSourceItem(source, "First"),
          CatalogSourceItem(source, "Second"),
        )
      )
    }
  }

  @Test
  fun browseKeepsAnOwnedCopyInSourceOrder() {
    val titles =
      mutableListOf(
        BrowseTitleItem(titleKey("z"), "Zeta"),
        BrowseTitleItem(titleKey("a"), "Alpha"),
      )

    val expected = titles.toList()

    val screen = BrowseScreen.of(catalogSource, titles)

    titles.clear()
    assertEquals(catalogSource, screen.source)
    assertEquals(expected, screen.titles)

    assertThrows<UnsupportedOperationException> {
      (screen.titles as MutableList).clear()
    }
  }

  @Test
  fun emptyBrowseResultIsValid() {
    assertTrue(BrowseScreen.of(catalogSource, emptyList()).titles.isEmpty())
  }

  @Test
  fun browseRejectsAForeignTitle() {
    val foreign = BrowseTitleItem(titleKey("a", otherSource), "Foreign")

    assertThrows<IllegalArgumentException> {
      BrowseScreen.of(catalogSource, listOf(foreign))
    }
  }

  @Test
  fun browseRejectsARepeatedTitleKey() {
    val first = BrowseTitleItem(titleKey("a"), "First")

    val second = BrowseTitleItem(titleKey("a"), "Second")

    assertThrows<IllegalArgumentException> {
      BrowseScreen.of(catalogSource, listOf(first, second))
    }
  }
}
