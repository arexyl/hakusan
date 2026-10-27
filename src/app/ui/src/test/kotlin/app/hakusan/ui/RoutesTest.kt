package app.hakusan.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RoutesTest {
  private val json = Json {
    serializersModule = routeConfiguration.serializersModule
  }

  private val serializer = PolymorphicSerializer(NavKey::class)

  @Test
  fun `each root saves under its explicit serial name`() {
    assertEquals(
      """{"type":"library"}""",
      json.encodeToString(serializer, LibraryRoute),
    )

    assertEquals(
      """{"type":"catalog"}""",
      json.encodeToString(serializer, CatalogRoute),
    )
  }

  @Test
  fun `each root survives a round trip`() {
    for (route in listOf(LibraryRoute, CatalogRoute)) {
      val saved = json.encodeToString(serializer, route)

      assertEquals(route, json.decodeFromString(serializer, saved))
    }
  }

  @Test
  fun `roots have fixed and distinct content keys`() {
    assertEquals("library", LibraryRoute.contentKey)
    assertEquals("catalog", CatalogRoute.contentKey)
  }
}
