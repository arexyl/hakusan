package app.hakusan

import app.hakusan.extensions.BrowseResultRejection
import app.hakusan.extensions.SourceBrowseResult
import app.hakusan.extensions.SourceFailure
import app.hakusan.extensions.SourceIdentity
import app.hakusan.extensions.SourceResult
import app.hakusan.sdk.CatalogSourceItem
import app.hakusan.sdk.ScreenSourceId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourceRegistryTest {
  @Test
  fun `the Catalog keeps the registration order`() {
    val identities = listOf("source-b", "source-a", "source-c")

    val registry =
      SourceRegistry.of(
        identities.map { identity ->
          ScriptedBackend(
            identity = SourceIdentity(identity),
            onDisplayName = { "Name of $identity" },
          )
        }
      )

    val expected = identities.map { identity ->
      CatalogSourceItem(ScreenSourceId(identity), "Name of $identity")
    }

    assertEquals(expected, registry.catalog.sources)
  }

  @Test
  fun `no backends make an empty Catalog`() {
    val registry = SourceRegistry.of(emptyList())

    assertTrue(registry.catalog.sources.isEmpty())
    assertNull(registry.find(SourceIdentity("source")))
  }

  @Test
  fun `a second backend for one identity is rejected without naming it`() {
    val duplicated = SourceIdentity("duplicated-source")

    val backends =
      listOf(
        ScriptedBackend(duplicated, onDisplayName = { "First name" }),
        ScriptedBackend(SourceIdentity("other-source")),
        ScriptedBackend(duplicated, onDisplayName = { "Second name" }),
      )

    val rejection =
      assertThrows(IllegalArgumentException::class.java) {
        SourceRegistry.of(backends)
      }

    val message = rejection.message.orEmpty()

    for (text in listOf("duplicated-source", "other-source", "name")) {
      assertFalse(message.contains(text, ignoreCase = true), message)
    }
  }

  @Test
  fun `lookup matches the exact identity only`() {
    val registry =
      SourceRegistry.of(listOf(ScriptedBackend(SourceIdentity("Source"))))

    assertNotNull(registry.find(SourceIdentity("Source")))

    for (other in listOf("source", "SOURCE", " Source", "Source ", "Other")) {
      assertNull(registry.find(SourceIdentity(other)), other)
    }
  }

  @Test
  fun `each display name is read once at registration`() {
    val identity = SourceIdentity("source")

    var reads = 0

    val backend =
      ScriptedBackend(
        identity = identity,
        onDisplayName = {
          reads += 1
          "Name $reads"
        },
      )

    val registry = SourceRegistry.of(listOf(backend))

    val item = registry.catalog.sources.single()

    val registration = registry.find(identity)

    assertEquals(1, reads)
    assertEquals(CatalogSourceItem(ScreenSourceId("source"), "Name 1"), item)
    assertSame(item, registration?.catalogItem)
  }

  @Test
  fun `a registration reaches its backend only through correlation`() {
    val identity = SourceIdentity("source")

    val foreign = SourceBrowseResult.create(SourceIdentity("other"), listOf())

    val registry =
      SourceRegistry.of(
        listOf(ScriptedBackend(identity, onBrowse = { foreign }))
      )

    val registration = checkNotNull(registry.find(identity))

    val result = runBlocking { registration.source.browse() }

    val failure = assertInstanceOf(SourceResult.Failure::class.java, result)

    val invalid =
      assertInstanceOf(
        SourceFailure.InvalidBrowseResult::class.java,
        failure.error,
      )

    assertEquals(BrowseResultRejection.FOREIGN_OWNER, invalid.reason)
  }
}
