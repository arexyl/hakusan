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

class HakusanNavigationStateTest {
  @Test
  fun `pop follows selection while an outgoing display remains composed`() {
    val retainedLibraryRoute = TitleDetailsRoute("library", "title")
    val libraryBackStack = NavBackStack<NavKey>(
      LibraryRoute,
      retainedLibraryRoute,
    )
    val catalogBackStack = NavBackStack<NavKey>(CatalogRoute)
    val state = HakusanNavigationState(
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
  fun `Catalog navigation retains exact identities and nested order`() {
    val sourceId = ScreenSourceId(" source\u00a0")
    val titleKey = ScreenTitleKey(
      sourceId = sourceId,
      sourceTitleKey = "e\u0301 title ",
    )
    val libraryBackStack = NavBackStack<NavKey>(LibraryRoute)
    val catalogBackStack = NavBackStack<NavKey>(CatalogRoute)
    val state = HakusanNavigationState(
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
    assertNull(state.popCatalog(detailsRoute))
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
}
