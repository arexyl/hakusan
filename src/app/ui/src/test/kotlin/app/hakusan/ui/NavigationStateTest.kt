package app.hakusan.ui

import app.hakusan.sdk.ScreenSourceId
import app.hakusan.sdk.ScreenTitleKey
import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NavigationStateTest {
  @Test
  fun `pop follows selection while an outgoing display remains composed`() {
    val retainedLibraryRoute = TitleDetailsRoute("library", "title")
    val libraryBackStack = NavBackStack<NavKey>(
      LibraryRoute,
      retainedLibraryRoute,
    )
    val catalogBackStack = NavBackStack<NavKey>(CatalogRoute)
    val state = NavigationState(
      selectedDestination = mutableStateOf(PrimaryDestination.CATALOG),
      libraryBackStack = libraryBackStack,
      catalogBackStack = catalogBackStack,
    )

    assertNull(state.pop())
    assertEquals(listOf(CatalogRoute), catalogBackStack)
    assertEquals(
      listOf(LibraryRoute, retainedLibraryRoute),
      libraryBackStack,
    )

    state.select(PrimaryDestination.LIBRARY)

    assertEquals(retainedLibraryRoute, state.pop())
    assertEquals(listOf(LibraryRoute), libraryBackStack)
  }

  @Test
  fun `Library title opens only from its selected root with exact identity`() {
    val titleKey = ScreenTitleKey(
      sourceId = ScreenSourceId(" source\u00a0"),
      sourceTitleKey = "e\u0301 title ",
    )
    val otherTitleKey = ScreenTitleKey(
      sourceId = titleKey.sourceId,
      sourceTitleKey = "other",
    )
    val libraryBackStack = NavBackStack<NavKey>(LibraryRoute)
    val state = NavigationState(
      selectedDestination = mutableStateOf(PrimaryDestination.CATALOG),
      libraryBackStack = libraryBackStack,
      catalogBackStack = NavBackStack<NavKey>(CatalogRoute),
    )

    state.openLibraryTitle(titleKey)
    assertEquals(listOf(LibraryRoute), libraryBackStack)

    state.select(PrimaryDestination.LIBRARY)
    state.openLibraryTitle(titleKey)

    val detailsRoute = TitleDetailsRoute(
      sourceId = titleKey.sourceId.value,
      sourceTitleKey = titleKey.sourceTitleKey,
    )
    assertEquals(listOf(LibraryRoute, detailsRoute), libraryBackStack)
    assertEquals(titleKey, detailsRoute.toScreenTitleKey())
    assertFalse(state.showsBrowsingIsland)

    state.openLibraryTitle(otherTitleKey)
    assertEquals(listOf(LibraryRoute, detailsRoute), libraryBackStack)
  }

  @Test
  fun `Catalog navigation retains exact identities and nested order`() {
    val sourceId = ScreenSourceId(" source\u00a0")
    val titleKey = ScreenTitleKey(
      sourceId = sourceId,
      sourceTitleKey = "e\u0301 title ",
    )
    val libraryBackStack = NavBackStack<NavKey>(LibraryRoute)
    val catalogBackStack = NavBackStack<NavKey>(CatalogRoute)
    val state = NavigationState(
      selectedDestination = mutableStateOf(PrimaryDestination.CATALOG),
      libraryBackStack = libraryBackStack,
      catalogBackStack = catalogBackStack,
    )

    state.openCatalogSource(sourceId)
    state.openCatalogTitle(titleKey)

    val browseRoute = SourceBrowseRoute(sourceId.value)
    val detailsRoute = TitleDetailsRoute(
      sourceId = sourceId.value,
      sourceTitleKey = titleKey.sourceTitleKey,
    )
    assertEquals(
      listOf(CatalogRoute, browseRoute, detailsRoute),
      catalogBackStack,
    )
    assertEquals(titleKey, detailsRoute.toScreenTitleKey())
    assertFalse(state.showsBrowsingIsland)

    state.select(PrimaryDestination.LIBRARY)
    assertEquals(listOf(LibraryRoute), state.currentBackStack)
    assertTrue(state.showsBrowsingIsland)
    assertNull(state.pop())
    assertNull(
      state.pop(
        destination = PrimaryDestination.CATALOG,
        expectedRoute = detailsRoute,
      ),
    )
    assertEquals(
      listOf(CatalogRoute, browseRoute, detailsRoute),
      catalogBackStack,
    )

    state.select(PrimaryDestination.CATALOG)
    assertEquals(detailsRoute, state.pop())
    assertTrue(state.showsBrowsingIsland)
    assertEquals(browseRoute, state.pop())
    assertEquals(listOf(CatalogRoute), catalogBackStack)
  }

  @Test
  fun `guarded Back keeps the same title independent in both stacks`() {
    val sourceId = ScreenSourceId("shared-source")
    val titleKey = ScreenTitleKey(
      sourceId = sourceId,
      sourceTitleKey = "shared-title",
    )
    val detailsRoute = TitleDetailsRoute(
      sourceId = sourceId.value,
      sourceTitleKey = titleKey.sourceTitleKey,
    )
    val browseRoute = SourceBrowseRoute(sourceId.value)
    val libraryBackStack = NavBackStack<NavKey>(LibraryRoute)
    val catalogBackStack = NavBackStack<NavKey>(CatalogRoute)
    val state = NavigationState(
      selectedDestination = mutableStateOf(PrimaryDestination.LIBRARY),
      libraryBackStack = libraryBackStack,
      catalogBackStack = catalogBackStack,
    )

    state.openLibraryTitle(titleKey)
    state.select(PrimaryDestination.CATALOG)
    state.openCatalogSource(sourceId)
    state.openCatalogTitle(titleKey)

    assertEquals(listOf(LibraryRoute, detailsRoute), libraryBackStack)
    assertEquals(
      listOf(CatalogRoute, browseRoute, detailsRoute),
      catalogBackStack,
    )

    assertNull(
      state.pop(
        destination = PrimaryDestination.LIBRARY,
        expectedRoute = detailsRoute,
      ),
    )
    assertNull(
      state.pop(
        destination = PrimaryDestination.CATALOG,
        expectedRoute = browseRoute,
      ),
    )
    assertEquals(
      detailsRoute,
      state.pop(
        destination = PrimaryDestination.CATALOG,
        expectedRoute = detailsRoute,
      ),
    )
    assertEquals(listOf(LibraryRoute, detailsRoute), libraryBackStack)
    assertEquals(listOf(CatalogRoute, browseRoute), catalogBackStack)

    state.select(PrimaryDestination.LIBRARY)
    assertNull(
      state.pop(
        destination = PrimaryDestination.CATALOG,
        expectedRoute = browseRoute,
      ),
    )
    assertEquals(
      detailsRoute,
      state.pop(
        destination = PrimaryDestination.LIBRARY,
        expectedRoute = detailsRoute,
      ),
    )
    assertEquals(listOf(LibraryRoute), libraryBackStack)
    assertEquals(listOf(CatalogRoute, browseRoute), catalogBackStack)
    assertNull(
      state.pop(
        destination = PrimaryDestination.LIBRARY,
        expectedRoute = LibraryRoute,
      ),
    )
    assertEquals(listOf(CatalogRoute, browseRoute), catalogBackStack)
  }
}
