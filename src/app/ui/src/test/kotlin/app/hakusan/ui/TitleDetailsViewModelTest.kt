package app.hakusan.ui

import app.hakusan.sdk.ContinueSelectionResult
import app.hakusan.sdk.ContinueSelectionService
import app.hakusan.sdk.ContinueState
import app.hakusan.sdk.ContinueTarget
import app.hakusan.sdk.ContinueUnavailableReason
import app.hakusan.sdk.DetailsChapterItem
import app.hakusan.sdk.DetailsScreenFailure
import app.hakusan.sdk.DetailsScreenResult
import app.hakusan.sdk.ScreenChapterId
import app.hakusan.sdk.ScreenChapterKey
import app.hakusan.sdk.ScreenReadingStart
import app.hakusan.sdk.ScreenSourceId
import app.hakusan.sdk.ScreenTitleId
import app.hakusan.sdk.ScreenTitleKey
import app.hakusan.sdk.TitleDetailsScreen
import app.hakusan.sdk.TitleDetailsScreenService
import java.util.UUID
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

class TitleDetailsViewModelTest {
  @Test
  fun `details and Continue form one route entry state`(): Unit = runBlocking {
    withTimeout(TEST_TIMEOUT_MILLIS) {
      val details = ControlledDetailsService()
      val selections = ControlledContinueService()
      val model = TitleDetailsViewModel(details, selections, this)
      val entryId = entryId(PrimaryDestination.CATALOG)

      assertSame(
        TitleDetailsEntryState.Loading,
        model.state(entryId, TITLE_KEY).value,
      )
      model.selectContinue(entryId)
      assertEquals(0, selections.requestCount)

      model.ensureDetails(entryId)
      details.requests.receive().complete(SCREEN)
      awaitCondition {
        model.state(entryId, TITLE_KEY).value is
          TitleDetailsEntryState.Content
      }
      val loaded = model.state(entryId, TITLE_KEY).value
        as TitleDetailsEntryState.Content
      assertSame(ContinueActionState.Idle, loaded.continueAction)

      model.selectContinue(entryId)
      val selection = selections.requests.receive()
      val selecting = model.state(entryId, TITLE_KEY).value
        as TitleDetailsEntryState.Content
      assertSame(ContinueActionState.Selecting, selecting.continueAction)

      selection.complete(ContinueSelectionResult.Selected(TARGET))
      awaitCondition {
        val state = model.state(entryId, TITLE_KEY).value
        state is TitleDetailsEntryState.Content &&
          state.continueAction is ContinueActionState.Selected
      }
      val selected = model.state(entryId, TITLE_KEY).value
        as TitleDetailsEntryState.Content
      assertSame(
        TARGET,
        (selected.continueAction as ContinueActionState.Selected).target,
      )
    }
  }

  @Test
  fun `unavailable details cannot start Continue selection`(): Unit =
    runBlocking {
      withTimeout(TEST_TIMEOUT_MILLIS) {
        val details = ControlledDetailsService()
        val selections = ControlledContinueService()
        val model = TitleDetailsViewModel(details, selections, this)
        val entryId = entryId(PrimaryDestination.CATALOG)

        model.state(entryId, TITLE_KEY)
        model.ensureDetails(entryId)
        details.requests.receive().complete(UNAVAILABLE_SCREEN)
        awaitCondition {
          model.state(entryId, TITLE_KEY).value is
            TitleDetailsEntryState.Content
        }

        model.selectContinue(entryId)

        assertEquals(0, selections.requestCount)
        val loaded = model.state(entryId, TITLE_KEY).value
          as TitleDetailsEntryState.Content
        assertSame(ContinueActionState.Idle, loaded.continueAction)
      }
    }

  @Test
  fun `details retry rejects a non-cooperative Continue completion`(): Unit =
    runBlocking {
      withTimeout(TEST_TIMEOUT_MILLIS) {
        val details = ControlledDetailsService()
        val selections = ControlledContinueService()
        val model = TitleDetailsViewModel(details, selections, this)
        val entryId = entryId(PrimaryDestination.CATALOG)
        model.state(entryId, TITLE_KEY)
        model.ensureDetails(entryId)
        details.requests.receive().complete(SCREEN)
        awaitCondition {
          model.state(entryId, TITLE_KEY).value is
            TitleDetailsEntryState.Content
        }

        model.selectContinue(entryId)
        val staleSelection = selections.requests.receive()
        staleSelection.ignoreCancellation = true
        model.retryDetails(entryId)
        val replacementDetails = details.requests.receive()
        assertSame(
          TitleDetailsEntryState.Loading,
          model.state(entryId, TITLE_KEY).value,
        )

        staleSelection.complete(ContinueSelectionResult.Selected(TARGET))
        staleSelection.returned.await()
        yield()
        assertSame(
          TitleDetailsEntryState.Loading,
          model.state(entryId, TITLE_KEY).value,
        )

        replacementDetails.complete(SCREEN)
        awaitCondition {
          model.state(entryId, TITLE_KEY).value is
            TitleDetailsEntryState.Content
        }
        val reloaded = model.state(entryId, TITLE_KEY).value
          as TitleDetailsEntryState.Content
        assertSame(ContinueActionState.Idle, reloaded.continueAction)
      }
    }

  @Test
  fun `superseded details can retry into a typed failure`(): Unit =
    runBlocking {
      withTimeout(TEST_TIMEOUT_MILLIS) {
        val details = ControlledDetailsService()
        val model = TitleDetailsViewModel(
          detailsService = details,
          continueService = ControlledContinueService(),
          taskScope = this,
        )
        val entryId = entryId(PrimaryDestination.CATALOG)
        model.state(entryId, TITLE_KEY)
        model.ensureDetails(entryId)
        details.requests.receive().complete(
          DetailsScreenResult.RejectedNotCurrent,
        )
        awaitCondition {
          model.state(entryId, TITLE_KEY).value ==
            TitleDetailsEntryState.Superseded
        }

        model.retryDetails(entryId)
        assertSame(
          TitleDetailsEntryState.Loading,
          model.state(entryId, TITLE_KEY).value,
        )
        details.requests.receive().complete(
          DetailsScreenResult.Failure(
            DetailsScreenFailure.ChaptersUnavailable,
          ),
        )
        awaitCondition {
          model.state(entryId, TITLE_KEY).value is
            TitleDetailsEntryState.Failed
        }

        assertEquals(
          DetailsScreenFailure.ChaptersUnavailable,
          (model.state(entryId, TITLE_KEY).value
            as TitleDetailsEntryState.Failed).failure,
        )
      }
    }

  @Test
  fun `equal title routes in different entries remain independent`(): Unit =
    runBlocking {
      val details = ControlledDetailsService()
      val model = TitleDetailsViewModel(
        detailsService = details,
        continueService = ControlledContinueService(),
        taskScope = this,
      )
      val libraryEntry = entryId(PrimaryDestination.LIBRARY)
      val catalogEntry = entryId(PrimaryDestination.CATALOG)

      val libraryState = model.state(libraryEntry, TITLE_KEY)
      val catalogState = model.state(catalogEntry, TITLE_KEY)
      model.ensureDetails(catalogEntry)
      details.requests.receive().complete(SCREEN)
      awaitCondition {
        catalogState.value is TitleDetailsEntryState.Content
      }

      model.discard(libraryEntry)
      val reopenedLibraryState = model.state(libraryEntry, TITLE_KEY)

      assertNotSame(libraryState, reopenedLibraryState)
      assertSame(
        TitleDetailsEntryState.Loading,
        libraryState.value,
      )
      assertSame(
        TitleDetailsEntryState.Loading,
        reopenedLibraryState.value,
      )
      assertEquals(
        SCREEN,
        (catalogState.value
          as TitleDetailsEntryState.Content).screen,
      )
    }

  @Test
  fun `one presentation identity cannot change title`(): Unit = runBlocking {
    val model = TitleDetailsViewModel(
      detailsService = ControlledDetailsService(),
      continueService = ControlledContinueService(),
      taskScope = this,
    )
    val entryId = entryId(PrimaryDestination.CATALOG)
    model.state(entryId, TITLE_KEY)

    assertThrows(IllegalArgumentException::class.java) {
      model.state(
        entryId,
        ScreenTitleKey(TITLE_KEY.sourceId, "another-title"),
      )
    }
  }

  private class ControlledDetailsService : TitleDetailsScreenService {
    val requests = Channel<PendingDetails>(Channel.UNLIMITED)

    override suspend fun loadDetails(
      titleKey: ScreenTitleKey,
    ): DetailsScreenResult {
      val pending = PendingDetails(titleKey)
      requests.send(pending)
      return pending.completion.await()
    }
  }

  private class PendingDetails(
    private val titleKey: ScreenTitleKey,
  ) {
    val completion = CompletableDeferred<DetailsScreenResult>()

    fun complete(screen: TitleDetailsScreen) {
      check(screen.key == titleKey)
      completion.complete(DetailsScreenResult.Success(screen))
    }

    fun complete(result: DetailsScreenResult) {
      completion.complete(result)
    }
  }

  private class ControlledContinueService : ContinueSelectionService {
    val requests = Channel<PendingContinue>(Channel.UNLIMITED)
    var requestCount: Int = 0
      private set

    override suspend fun selectContinue(
      titleId: ScreenTitleId,
    ): ContinueSelectionResult {
      requestCount += 1
      val pending = PendingContinue(titleId)
      requests.send(pending)
      return pending.await()
    }
  }

  private class PendingContinue(
    private val titleId: ScreenTitleId,
  ) {
    private val completion = CompletableDeferred<ContinueSelectionResult>()
    val returned = CompletableDeferred<Unit>()
    var ignoreCancellation: Boolean = false

    suspend fun await(): ContinueSelectionResult {
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

    fun complete(result: ContinueSelectionResult) {
      if (result is ContinueSelectionResult.Selected) {
        check(result.target.titleId == titleId)
      }
      completion.complete(result)
    }
  }

  private companion object {
    const val TEST_TIMEOUT_MILLIS = 5_000L
    val TITLE_KEY = ScreenTitleKey(
      sourceId = ScreenSourceId("source"),
      sourceTitleKey = "title",
    )
    val TITLE_ID = ScreenTitleId(
      UUID.fromString("00000000-0000-7000-8000-000000000001"),
    )
    val CHAPTER_ID = ScreenChapterId(
      UUID.fromString("00000000-0000-7000-8000-000000000002"),
    )
    val CHAPTER_KEY = ScreenChapterKey(TITLE_KEY, "chapter")
    val TARGET = ContinueTarget(
      titleId = TITLE_ID,
      chapterId = CHAPTER_ID,
      chapterKey = CHAPTER_KEY,
      start = ScreenReadingStart.Beginning,
    )
    val SCREEN = TitleDetailsScreen.of(
      id = TITLE_ID,
      key = TITLE_KEY,
      sourceDisplayName = "Source",
      displayName = "Title",
      description = null,
      chapters = listOf(
        DetailsChapterItem(
          id = CHAPTER_ID,
          titleId = TITLE_ID,
          key = CHAPTER_KEY,
          displayName = "Chapter",
          isRead = false,
        ),
      ),
      continueState = ContinueState.Ready(TARGET),
    )
    val UNAVAILABLE_SCREEN = TitleDetailsScreen.of(
      id = TITLE_ID,
      key = TITLE_KEY,
      sourceDisplayName = "Source",
      displayName = "Title",
      description = null,
      chapters = emptyList(),
      continueState = ContinueState.Unavailable(
        ContinueUnavailableReason.NoAvailableChapter,
      ),
    )

    fun entryId(destination: PrimaryDestination): PresentationEntryId =
      PresentationEntryId.create(
        destination = destination,
        route = TitleDetailsRoute(
          sourceId = TITLE_KEY.sourceId.value,
          sourceTitleKey = TITLE_KEY.sourceTitleKey,
        ),
      )
  }
}
