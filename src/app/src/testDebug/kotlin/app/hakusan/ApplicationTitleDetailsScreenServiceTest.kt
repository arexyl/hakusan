package app.hakusan

import app.hakusan.debug.source.DeterministicSource
import app.hakusan.extensions.ChapterRefreshCompletion
import app.hakusan.extensions.ChapterRefreshRequest
import app.hakusan.extensions.SourceBackend
import app.hakusan.extensions.SourceFailure
import app.hakusan.extensions.SourceIdentity
import app.hakusan.extensions.SourceResult
import app.hakusan.extensions.SourceTitle
import app.hakusan.extensions.SourceTitleDetails
import app.hakusan.extensions.SourceTitleKey
import app.hakusan.sdk.DetailsScreenFailure
import app.hakusan.sdk.DetailsScreenResult
import app.hakusan.titles.Titles
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ApplicationTitleDetailsScreenServiceTest {
  @Test
  fun `older details completion is rejected before persistence`() =
    runBlocking {
      withTimeout(TEST_TIMEOUT_MILLIS) {
        val source = ControlledDetailsSource()
        val service = createApplicationGraph(
          sourceRegistry = SourceRegistry.of(listOf(source)),
          titles = unusedTitles(),
        ).titleDetailsScreenService

        val firstLoad = async(start = CoroutineStart.UNDISPATCHED) {
          service.loadDetails(TITLE_KEY.toScreenKey())
        }
        val firstDetails = source.awaitDetails()
        val secondLoad = async(start = CoroutineStart.UNDISPATCHED) {
          service.loadDetails(TITLE_KEY.toScreenKey())
        }
        val secondDetails = source.awaitDetails()

        secondDetails.fail(SourceFailure.Unavailable)
        assertEquals(
          DetailsScreenResult.Failure(
            DetailsScreenFailure.DetailsUnavailable,
          ),
          secondLoad.await(),
        )

        firstDetails.succeed("Stale title")
        assertSame(
          DetailsScreenResult.RejectedNotCurrent,
          firstLoad.await(),
        )
        assertEquals(0, source.chapterRefreshCount.get())
      }
    }

  private class ControlledDetailsSource(
    private val delegate: SourceBackend = DeterministicSource(),
  ) : SourceBackend by delegate {
    private val pendingDetails = Channel<PendingDetails>(Channel.UNLIMITED)
    val chapterRefreshCount = AtomicInteger()

    override suspend fun details(
      title: SourceTitleKey,
    ): SourceResult<SourceTitleDetails> {
      require(title.source == identity)
      return PendingDetails(title).also { pendingDetails.send(it) }.result
        .await()
    }

    override suspend fun refreshChapters(
      request: ChapterRefreshRequest,
    ): ChapterRefreshCompletion {
      chapterRefreshCount.incrementAndGet()
      return delegate.refreshChapters(request)
    }

    suspend fun awaitDetails(): PendingDetails = pendingDetails.receive()
  }

  private class PendingDetails(
    private val titleKey: SourceTitleKey,
  ) {
    val result = CompletableDeferred<SourceResult<SourceTitleDetails>>()

    fun succeed(displayName: String) {
      result.complete(
        SourceResult.Success(
          SourceTitleDetails(
            title = SourceTitle(titleKey, displayName),
            description = null,
          ),
        ),
      )
    }

    fun fail(failure: SourceFailure) {
      result.complete(SourceResult.Failure(failure))
    }
  }

  @Suppress("UNCHECKED_CAST")
  private fun unusedTitles(): Titles = Proxy.newProxyInstance(
    Titles::class.java.classLoader,
    arrayOf(Titles::class.java),
  ) { _, method, _ ->
    error("Unexpected Titles call: ${method.name}")
  } as Titles

  private companion object {
    const val TEST_TIMEOUT_MILLIS = 5_000L
    val TITLE_KEY = SourceTitleKey(
      source = SourceIdentity("app.hakusan.debug.source"),
      key = "canonical-order-fixture",
    )
  }
}
