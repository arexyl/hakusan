package app.hakusan

import app.hakusan.extensions.SourceBrowseResult
import app.hakusan.extensions.SourceFailure
import app.hakusan.extensions.SourceIdentity
import app.hakusan.extensions.SourceResult
import app.hakusan.extensions.SourceTitle
import app.hakusan.extensions.SourceTitleKey
import app.hakusan.sdk.BrowseScreen
import app.hakusan.sdk.BrowseScreenFailure
import app.hakusan.sdk.BrowseScreenResult
import app.hakusan.sdk.BrowseTitleItem
import app.hakusan.sdk.ScreenSourceId
import app.hakusan.sdk.ScreenTitleKey
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class BrowseScreenAdapterTest {
  private val source = SourceIdentity("source")
  private val otherSource = SourceIdentity("other-source")

  @Test
  fun `the Catalog is the snapshot of the registry`() {
    val registry = SourceRegistry.of(listOf(ScriptedBackend(source)))

    assertSame(registry.catalog, BrowseScreenAdapter(registry).catalog())
  }

  @Test
  fun `an unregistered source is not found`() {
    val adapter = adapter(ScriptedBackend(source))

    for (id in listOf("other-source", "Source", " source")) {
      assertEquals(
        BrowseScreenResult.Failure(BrowseScreenFailure.SourceNotFound),
        runBlocking { adapter.loadBrowse(ScreenSourceId(id)) },
        id,
      )
    }

    val empty = BrowseScreenAdapter(SourceRegistry.of(emptyList()))

    assertEquals(
      BrowseScreenResult.Failure(BrowseScreenFailure.SourceNotFound),
      runBlocking { empty.loadBrowse(ScreenSourceId("source")) },
    )
  }

  @Test
  fun `an unavailable source is a current unavailability`() {
    val adapter =
      adapter(
        ScriptedBackend(
          identity = source,
          onBrowse = { SourceResult.Failure(SourceFailure.Unavailable) },
        )
      )

    assertEquals(
      BrowseScreenResult.Failure(BrowseScreenFailure.SourceUnavailable),
      runBlocking { adapter.loadBrowse(ScreenSourceId("source")) },
    )
  }

  @Test
  fun `every invalid browse result is an invalid observation`() {
    val foreignTitle = SourceTitle(SourceTitleKey(otherSource, "title"), "T")

    val ownTitle = SourceTitle(SourceTitleKey(source, "title"), "T")

    val invalidResults =
      listOf(
        SourceBrowseResult.create(otherSource, listOf()),
        SourceBrowseResult.create(source, listOf(foreignTitle)),
        SourceBrowseResult.create(source, listOf(ownTitle, ownTitle)),
      )

    for (invalid in invalidResults) {
      val adapter =
        adapter(ScriptedBackend(identity = source, onBrowse = { invalid }))

      assertEquals(
        BrowseScreenResult.Failure(BrowseScreenFailure.InvalidObservation),
        runBlocking { adapter.loadBrowse(ScreenSourceId("source")) },
      )
    }
  }

  @Test
  fun `an empty observation is a valid empty screen`() {
    val registry =
      SourceRegistry.of(
        listOf(
          ScriptedBackend(
            identity = source,
            onBrowse = { SourceBrowseResult.create(source, listOf()) },
          )
        )
      )

    val result = runBlocking {
      BrowseScreenAdapter(registry).loadBrowse(ScreenSourceId("source"))
    }

    val screen = BrowseScreen.of(registry.catalog.sources.single(), listOf())

    assertEquals(BrowseScreenResult.Success(screen), result)
  }

  @Test
  fun `titles keep the source order and their exact keys`() {
    val keys =
      listOf(
        "title-b",
        " padded ",
        "Case",
        "case",
        // One visible text, composed and decomposed.
        Char(0x00E9).toString(),
        "e" + Char(0x0301),
        "https://example.test/a?b=c#d",
        "title-a",
      )

    val titles = keys.map { key ->
      SourceTitle(SourceTitleKey(source, key), "Name $key")
    }

    val adapter =
      adapter(
        ScriptedBackend(
          identity = source,
          onBrowse = { SourceBrowseResult.create(source, titles) },
        )
      )

    val screen = successScreen(adapter, ScreenSourceId("source"))

    val expected = keys.map { key ->
      BrowseTitleItem(
        ScreenTitleKey(ScreenSourceId("source"), key),
        "Name $key",
      )
    }

    assertEquals(expected, screen.titles)
  }

  @Test
  fun `equal local keys under two sources stay distinct`() {
    val adapter =
      adapter(
        ScriptedBackend(
          identity = source,
          onBrowse = {
            SourceBrowseResult.create(
              source,
              listOf(SourceTitle(SourceTitleKey(source, "title"), "Same")),
            )
          },
        ),
        ScriptedBackend(
          identity = otherSource,
          onBrowse = {
            SourceBrowseResult.create(
              otherSource,
              listOf(SourceTitle(SourceTitleKey(otherSource, "title"), "Same")),
            )
          },
        ),
      )

    val first = successScreen(adapter, ScreenSourceId("source")).titles.single()

    val second =
      successScreen(adapter, ScreenSourceId("other-source")).titles.single()

    assertEquals(ScreenTitleKey(ScreenSourceId("source"), "title"), first.key)

    assertEquals(
      ScreenTitleKey(ScreenSourceId("other-source"), "title"),
      second.key,
    )

    assertNotEquals(first, second)
  }

  @Test
  fun `the screen source is the Catalog entry read at registration`() {
    var reads = 0

    val adapter =
      adapter(
        ScriptedBackend(
          identity = source,
          onDisplayName = {
            reads += 1
            "Name $reads"
          },
          onBrowse = { SourceBrowseResult.create(source, listOf()) },
        )
      )

    val screen = successScreen(adapter, ScreenSourceId("source"))

    assertSame(adapter.catalog().sources.single(), screen.source)
    assertEquals("Name 1", screen.source.displayName)
    assertEquals(1, reads)
  }

  @Test
  fun `cancellation and defects propagate unchanged`() {
    for (thrown in
      listOf(CancellationException("Cancelled"), IllegalStateException())) {
      val adapter =
        adapter(ScriptedBackend(identity = source, onBrowse = { throw thrown }))

      val propagated =
        assertThrows(Throwable::class.java) {
          runBlocking { adapter.loadBrowse(ScreenSourceId("source")) }
        }

      assertSame(thrown, propagated)
    }
  }

  private fun adapter(vararg backends: ScriptedBackend): BrowseScreenAdapter =
    BrowseScreenAdapter(SourceRegistry.of(backends.asList()))

  private fun successScreen(
    adapter: BrowseScreenAdapter,
    sourceId: ScreenSourceId,
  ): BrowseScreen {
    val result = runBlocking { adapter.loadBrowse(sourceId) }

    return assertInstanceOf(BrowseScreenResult.Success::class.java, result)
      .screen
  }
}
