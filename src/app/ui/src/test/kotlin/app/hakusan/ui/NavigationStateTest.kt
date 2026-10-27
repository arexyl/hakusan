package app.hakusan.ui

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class NavigationStateTest {
  private val library = NavBackStack<NavKey>(LibraryRoute)
  private val catalog = NavBackStack<NavKey>(CatalogRoute)

  private val state =
    NavigationState(mutableStateOf(Destination.Library), library, catalog)

  @Test
  fun `switching destinations keeps both stacks`() {
    val inLibrary = TestRoute("subject")

    val inCatalog = TestRoute("subject")

    assertTrue(state.open(LibraryRoute, inLibrary))
    state.select(Destination.Catalog)
    assertTrue(state.open(CatalogRoute, inCatalog))
    state.select(Destination.Library)
    assertEquals(Destination.Library, state.selected)
    assertEquals(listOf(LibraryRoute, inLibrary), libraryStack())
    assertEquals(listOf(CatalogRoute, inCatalog), catalogStack())
  }

  @Test
  fun `two pushes of equal identities are distinct entries`() {
    val first = TestRoute("subject")

    val second = TestRoute("subject")

    assertTrue(state.open(LibraryRoute, first))
    assertTrue(state.open(first, second))
    assertFalse(state.open(first, TestRoute("subject")))
    assertEquals(listOf(LibraryRoute, first, second), libraryStack())
    assertTrue(state.pop())
    assertEquals(listOf(LibraryRoute, first), libraryStack())
  }

  @Test
  fun `each stack holds its own root first and each nested route once`() {
    val route = TestRoute("subject")

    val invalidLibraryStacks =
      listOf(
        NavBackStack<NavKey>(),
        NavBackStack<NavKey>(CatalogRoute),
        NavBackStack<NavKey>(LibraryRoute, LibraryRoute),
        NavBackStack<NavKey>(LibraryRoute, CatalogRoute),
        NavBackStack<NavKey>(LibraryRoute, route, route),
      )

    for (invalid in invalidLibraryStacks) {
      assertThrows<IllegalArgumentException> {
        NavigationState(mutableStateOf(Destination.Library), invalid, catalog)
      }
    }
  }

  @Test
  fun `open accepts only a new nested route`() {
    val route = TestRoute("subject")

    assertTrue(state.open(LibraryRoute, route))
    assertThrows<IllegalArgumentException> { state.open(route, route) }
    assertEquals(listOf(LibraryRoute, route), libraryStack())
  }

  @Test
  fun `open binds to the top entry of the selected stack`() {
    val first = TestRoute("first")

    val second = TestRoute("second")

    assertTrue(state.open(LibraryRoute, first))
    assertFalse(state.open(LibraryRoute, second))
    assertFalse(state.open(CatalogRoute, second))
    assertEquals(listOf(LibraryRoute, first), libraryStack())
  }

  @Test
  fun `open never reaches the non-selected stack`() {
    val route = TestRoute("subject")

    assertFalse(state.open(CatalogRoute, route))
    assertEquals(listOf(CatalogRoute), catalogStack())
    assertEquals(listOf(LibraryRoute), libraryStack())
  }

  @Test
  fun `back at a root pops nothing`() {
    assertFalse(state.pop())
    assertEquals(listOf(LibraryRoute), libraryStack())
  }

  @Test
  fun `back pops the selected stack only`() {
    val inLibrary = TestRoute("subject")

    val inCatalog = TestRoute("subject")

    state.open(LibraryRoute, inLibrary)
    state.select(Destination.Catalog)
    state.open(CatalogRoute, inCatalog)
    assertTrue(state.pop())
    assertEquals(listOf(CatalogRoute), catalogStack())
    assertEquals(listOf(LibraryRoute, inLibrary), libraryStack())
  }

  @Test
  fun `a stale owner never reaches a reopened equal route`() {
    val popped = TestRoute("subject")

    val reopened = TestRoute("subject")

    state.open(LibraryRoute, popped)
    assertTrue(state.pop())
    assertTrue(state.open(LibraryRoute, reopened))
    assertFalse(state.open(popped, TestRoute("subject")))
    assertEquals(listOf(LibraryRoute, reopened), libraryStack())
  }

  private fun libraryStack(): List<NavKey> =
    state.backStack(Destination.Library)

  private fun catalogStack(): List<NavKey> =
    state.backStack(Destination.Catalog)
}

private data class TestRoute(val name: String, val token: String) :
  NestedRoute {
  constructor(name: String) : this(name, UUID.randomUUID().toString())
}
