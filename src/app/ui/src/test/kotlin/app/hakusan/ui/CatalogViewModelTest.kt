package app.hakusan.ui

import app.hakusan.sdk.BrowseScreen
import app.hakusan.sdk.BrowseScreenResult
import app.hakusan.sdk.BrowseScreenService
import app.hakusan.sdk.BrowseTitleItem
import app.hakusan.sdk.CatalogScreen
import app.hakusan.sdk.CatalogSourceItem
import app.hakusan.sdk.ScreenSourceId
import app.hakusan.sdk.ScreenTitleKey
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CatalogViewModelTest {
  @Test
  fun `retry keeps only the replacement browse completion`(): Unit =
    runBlocking {
      withTimeout(TEST_TIMEOUT_MILLIS) {
        val service = ControlledBrowseService()
        val model = CatalogViewModel(service, this)
        val entryId = entryId()

        assertSame(
          SourceBrowseState.Loading,
          model.browseState(entryId, SOURCE_ID).value,
        )
        model.ensureBrowse(entryId)
        val stale = service.requests.receive()
        stale.ignoreCancellation = true

        model.retryBrowse(entryId)
        val current = service.requests.receive()
        current.complete(screen("current"))
        awaitCondition {
          model.browseState(entryId, SOURCE_ID).value is
            SourceBrowseState.Content
        }

        stale.complete(screen("stale"))
        stale.returned.await()
        yield()

        val loaded = model.browseState(entryId, SOURCE_ID).value
          as SourceBrowseState.Content
        assertEquals("current", loaded.screen.titles.single().displayName)
      }
    }

  @Test
  fun `discard isolates a reopened equal presentation identity`(): Unit =
    runBlocking {
      withTimeout(TEST_TIMEOUT_MILLIS) {
        val service = ControlledBrowseService()
        val model = CatalogViewModel(service, this)
        val entryId = entryId()
        val removedState = model.browseState(entryId, SOURCE_ID)
        model.ensureBrowse(entryId)
        val removed = service.requests.receive()
        removed.ignoreCancellation = true

        model.discard(entryId)
        val reopenedState = model.browseState(entryId, SOURCE_ID)
        assertNotSame(removedState, reopenedState)
        assertSame(
          SourceBrowseState.Loading,
          reopenedState.value,
        )
        model.ensureBrowse(entryId)
        val reopened = service.requests.receive()

        removed.complete(screen("removed"))
        removed.returned.await()
        yield()
        assertSame(
          SourceBrowseState.Loading,
          removedState.value,
        )
        assertSame(
          SourceBrowseState.Loading,
          reopenedState.value,
        )

        reopened.complete(screen("reopened"))
        awaitCondition {
          reopenedState.value is SourceBrowseState.Content
        }
        val loaded = reopenedState.value
          as SourceBrowseState.Content
        assertEquals("reopened", loaded.screen.titles.single().displayName)
      }
    }

  @Test
  fun `one presentation identity cannot change source`() {
    val model = CatalogViewModel(ControlledBrowseService())
    val entryId = entryId()
    model.browseState(entryId, SOURCE_ID)

    assertThrows(IllegalArgumentException::class.java) {
      model.browseState(entryId, ScreenSourceId("another.source"))
    }
  }

  private class ControlledBrowseService : BrowseScreenService {
    val requests = Channel<PendingBrowse>(Channel.UNLIMITED)

    override fun catalog(): CatalogScreen = CatalogScreen.of(listOf(SOURCE))

    override suspend fun loadBrowse(
      sourceId: ScreenSourceId,
    ): BrowseScreenResult {
      val pending = PendingBrowse(sourceId)
      requests.send(pending)
      return pending.await()
    }
  }

  private class PendingBrowse(
    private val sourceId: ScreenSourceId,
  ) {
    private val completion = CompletableDeferred<BrowseScreenResult>()
    val returned = CompletableDeferred<Unit>()
    var ignoreCancellation: Boolean = false

    suspend fun await(): BrowseScreenResult {
      val result = try {
        completion.await()
      } catch (cancellation: CancellationException) {
        if (!ignoreCancellation) {
          throw cancellation
        }
        withContext(NonCancellable) { completion.await() }
      }
      returned.complete(Unit)
      return result
    }

    fun complete(screen: BrowseScreen) {
      check(screen.source.id == sourceId)
      completion.complete(BrowseScreenResult.Success(screen))
    }
  }

  private companion object {
    const val TEST_TIMEOUT_MILLIS = 5_000L
    val SOURCE_ID = ScreenSourceId("source")
    val SOURCE = CatalogSourceItem(SOURCE_ID, "Source")

    fun entryId(): PresentationEntryId = PresentationEntryId.create(
      destination = PrimaryDestination.CATALOG,
      route = SourceBrowseRoute(SOURCE_ID.value),
    )

    fun screen(name: String): BrowseScreen = BrowseScreen.of(
      source = SOURCE,
      titles = listOf(
        BrowseTitleItem(
          key = ScreenTitleKey(SOURCE_ID, "title-$name"),
          displayName = name,
        ),
      ),
    )
  }
}
