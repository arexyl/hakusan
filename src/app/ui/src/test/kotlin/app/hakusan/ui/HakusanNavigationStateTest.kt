package app.hakusan.ui

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HakusanNavigationStateTest {
  @Test
  fun `pop follows selection while an outgoing display remains composed`() {
    val libraryBackStack = NavBackStack<NavKey>(
      LibraryRoute,
      CatalogRoute,
    )
    val catalogBackStack = NavBackStack<NavKey>(CatalogRoute)
    val state = HakusanNavigationState(
      selectedDestination = mutableStateOf(PrimaryDestination.CATALOG),
      libraryBackStack = libraryBackStack,
      catalogBackStack = catalogBackStack,
    )

    assertFalse(state.pop())
    assertEquals(listOf(CatalogRoute), catalogBackStack)
    assertEquals(listOf(LibraryRoute, CatalogRoute), libraryBackStack)

    state.select(PrimaryDestination.LIBRARY)

    assertTrue(state.pop())
    assertEquals(listOf(LibraryRoute), libraryBackStack)
  }
}
