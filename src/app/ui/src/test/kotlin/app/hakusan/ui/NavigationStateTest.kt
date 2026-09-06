package app.hakusan.ui

import app.hakusan.sdk.ScreenSourceId
import app.hakusan.sdk.ScreenTitleKey
import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
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

    assertSame(CurrentPopResult.AtRoot, state.popCurrent())
    assertEquals(listOf(CatalogRoute), catalogBackStack)
    assertEquals(
      listOf(LibraryRoute, retainedLibraryRoute),
      libraryBackStack,
    )

    state.select(PrimaryDestination.LIBRARY)

    val expectedEntry = state.entry(
      destination = PrimaryDestination.LIBRARY,
      route = retainedLibraryRoute,
    )
    val popped = state.popCurrent() as CurrentPopResult.Popped
    assertEquals(retainedLibraryRoute, popped.entry.route)
    assertEquals(
      expectedEntry.presentationId,
      popped.entry.presentationId,
    )
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

    val detailsRoute = libraryBackStack.last() as TitleDetailsRoute
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
    val browseRoute = catalogBackStack[1] as SourceBrowseRoute
    val browseEntry = state.entry(
      destination = PrimaryDestination.CATALOG,
      route = browseRoute,
    )
    state.openCatalogTitle(browseEntry, titleKey)
    val detailsRoute = catalogBackStack[2] as TitleDetailsRoute
    assertEquals(
      listOf(CatalogRoute, browseRoute, detailsRoute),
      catalogBackStack,
    )
    assertEquals(titleKey, detailsRoute.toScreenTitleKey())
    assertFalse(state.showsBrowsingIsland)

    state.select(PrimaryDestination.LIBRARY)
    assertEquals(listOf(LibraryRoute), state.currentBackStack)
    assertTrue(state.showsBrowsingIsland)
    assertSame(CurrentPopResult.AtRoot, state.popCurrent())
    assertSame(
      ExpectedPopResult.Rejected,
      state.popExpected(
        state.entry(
          destination = PrimaryDestination.CATALOG,
          route = detailsRoute,
        ),
      ),
    )
    assertEquals(
      listOf(CatalogRoute, browseRoute, detailsRoute),
      catalogBackStack,
    )

    state.select(PrimaryDestination.CATALOG)
    val expectedDetailsEntry = state.entry(
      destination = PrimaryDestination.CATALOG,
      route = detailsRoute,
    )
    val poppedDetails = state.popCurrent() as CurrentPopResult.Popped
    assertEquals(
      detailsRoute,
      poppedDetails.entry.route,
    )
    assertEquals(
      expectedDetailsEntry.presentationId,
      poppedDetails.entry.presentationId,
    )
    assertTrue(state.showsBrowsingIsland)
    val expectedBrowseEntry = state.entry(
      destination = PrimaryDestination.CATALOG,
      route = browseRoute,
    )
    val poppedBrowse = state.popCurrent() as CurrentPopResult.Popped
    assertEquals(
      browseRoute,
      poppedBrowse.entry.route,
    )
    assertEquals(
      expectedBrowseEntry.presentationId,
      poppedBrowse.entry.presentationId,
    )
    assertEquals(listOf(CatalogRoute), catalogBackStack)
  }

  @Test
  fun `guarded Back keeps the same title independent in both stacks`() {
    val sourceId = ScreenSourceId("shared-source")
    val titleKey = ScreenTitleKey(
      sourceId = sourceId,
      sourceTitleKey = "shared-title",
    )
    val libraryBackStack = NavBackStack<NavKey>(LibraryRoute)
    val catalogBackStack = NavBackStack<NavKey>(CatalogRoute)
    val state = NavigationState(
      selectedDestination = mutableStateOf(PrimaryDestination.LIBRARY),
      libraryBackStack = libraryBackStack,
      catalogBackStack = catalogBackStack,
    )

    state.openLibraryTitle(titleKey)
    val libraryDetailsRoute = libraryBackStack.last() as TitleDetailsRoute
    state.select(PrimaryDestination.CATALOG)
    state.openCatalogSource(sourceId)
    val browseRoute = catalogBackStack.last() as SourceBrowseRoute
    val browseEntry = state.entry(
      destination = PrimaryDestination.CATALOG,
      route = browseRoute,
    )
    state.openCatalogTitle(browseEntry, titleKey)
    val catalogDetailsRoute = catalogBackStack.last() as TitleDetailsRoute

    assertEquals(
      listOf(LibraryRoute, libraryDetailsRoute),
      libraryBackStack,
    )
    assertEquals(
      listOf(CatalogRoute, browseRoute, catalogDetailsRoute),
      catalogBackStack,
    )
    assertEquals(titleKey, libraryDetailsRoute.toScreenTitleKey())
    assertEquals(titleKey, catalogDetailsRoute.toScreenTitleKey())
    assertNotEquals(libraryDetailsRoute, catalogDetailsRoute)
    val libraryDetails = state.entry(
      destination = PrimaryDestination.LIBRARY,
      route = libraryDetailsRoute,
    )
    val catalogDetails = state.entry(
      destination = PrimaryDestination.CATALOG,
      route = catalogDetailsRoute,
    )
    assertNotEquals(
      libraryDetails.presentationId,
      catalogDetails.presentationId,
    )
    assertNotEquals(
      catalogDetails.presentationId,
      state.entry(
        destination = PrimaryDestination.CATALOG,
        route = browseRoute,
      ).presentationId,
    )
    assertEquals(
      catalogDetails.presentationId,
      state.entry(
        destination = PrimaryDestination.CATALOG,
        route = catalogDetailsRoute,
      ).presentationId,
    )

    assertSame(
      ExpectedPopResult.Rejected,
      state.popExpected(libraryDetails),
    )
    assertSame(
      ExpectedPopResult.Rejected,
      state.popExpected(
        state.entry(
          destination = PrimaryDestination.CATALOG,
          route = browseRoute,
        ),
      ),
    )
    assertEquals(
      ExpectedPopResult.Popped(catalogDetails),
      state.popExpected(catalogDetails),
    )
    assertEquals(
      listOf(LibraryRoute, libraryDetailsRoute),
      libraryBackStack,
    )
    assertEquals(listOf(CatalogRoute, browseRoute), catalogBackStack)

    state.select(PrimaryDestination.LIBRARY)
    assertSame(
      ExpectedPopResult.Rejected,
      state.popExpected(
        state.entry(
          destination = PrimaryDestination.CATALOG,
          route = browseRoute,
        ),
      ),
    )
    assertEquals(
      ExpectedPopResult.Popped(libraryDetails),
      state.popExpected(libraryDetails),
    )
    assertEquals(listOf(LibraryRoute), libraryBackStack)
    assertEquals(listOf(CatalogRoute, browseRoute), catalogBackStack)
    assertSame(CurrentPopResult.AtRoot, state.popCurrent())
    assertEquals(listOf(CatalogRoute, browseRoute), catalogBackStack)
  }

  @Test
  fun `stale handles cannot affect reopened subjects`() {
    val sourceId = ScreenSourceId("reopened-source")
    val titleKey = ScreenTitleKey(sourceId, "reopened-title")
    val catalogBackStack = NavBackStack<NavKey>(CatalogRoute)
    val state = NavigationState(
      selectedDestination = mutableStateOf(PrimaryDestination.CATALOG),
      libraryBackStack = NavBackStack<NavKey>(LibraryRoute),
      catalogBackStack = catalogBackStack,
    )

    state.openCatalogSource(sourceId)
    val oldBrowseRoute = catalogBackStack.last() as SourceBrowseRoute
    val oldBrowse = state.entry(
      destination = PrimaryDestination.CATALOG,
      route = oldBrowseRoute,
    )
    assertEquals(
      ExpectedPopResult.Popped(oldBrowse),
      state.popExpected(oldBrowse),
    )

    state.openCatalogSource(sourceId)
    val newBrowseRoute = catalogBackStack.last() as SourceBrowseRoute
    val newBrowse = state.entry(
      destination = PrimaryDestination.CATALOG,
      route = newBrowseRoute,
    )
    assertEquals(sourceId, newBrowseRoute.toScreenSourceId())
    assertNotEquals(oldBrowse.presentationId, newBrowse.presentationId)
    assertSame(ExpectedPopResult.Rejected, state.popExpected(oldBrowse))

    state.openCatalogTitle(oldBrowse, titleKey)
    assertEquals(newBrowseRoute, catalogBackStack.last())
    state.openCatalogTitle(newBrowse, titleKey)
    val oldDetailsRoute = catalogBackStack.last() as TitleDetailsRoute
    val oldDetails = state.entry(
      destination = PrimaryDestination.CATALOG,
      route = oldDetailsRoute,
    )
    assertEquals(
      ExpectedPopResult.Popped(oldDetails),
      state.popExpected(oldDetails),
    )

    state.openCatalogTitle(newBrowse, titleKey)
    val newDetailsRoute = catalogBackStack.last() as TitleDetailsRoute
    val newDetails = state.entry(
      destination = PrimaryDestination.CATALOG,
      route = newDetailsRoute,
    )
    assertEquals(titleKey, newDetailsRoute.toScreenTitleKey())
    assertNotEquals(oldDetails.presentationId, newDetails.presentationId)
    assertSame(ExpectedPopResult.Rejected, state.popExpected(oldDetails))
    assertEquals(newDetailsRoute, catalogBackStack.last())

    assertEquals(
      ExpectedPopResult.Popped(newDetails),
      state.popExpected(newDetails),
    )
    assertEquals(
      ExpectedPopResult.Popped(newBrowse),
      state.popExpected(newBrowse),
    )
    val otherSourceId = ScreenSourceId("other-source")
    state.openCatalogSource(otherSourceId)
    val otherBrowseRoute = catalogBackStack.last() as SourceBrowseRoute
    val otherBrowse = state.entry(
      destination = PrimaryDestination.CATALOG,
      route = otherBrowseRoute,
    )
    state.openCatalogTitle(newBrowse, titleKey)
    assertEquals(otherBrowseRoute, catalogBackStack.last())

    val otherTitleKey = ScreenTitleKey(otherSourceId, "other-title")
    state.openCatalogTitle(otherBrowse, otherTitleKey)
    assertEquals(
      otherTitleKey,
      (catalogBackStack.last() as TitleDetailsRoute).toScreenTitleKey(),
    )
  }
}
