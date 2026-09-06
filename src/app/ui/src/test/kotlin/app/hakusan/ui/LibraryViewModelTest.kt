package app.hakusan.ui

import app.hakusan.sdk.AddToLibraryScreenResult
import app.hakusan.sdk.LibraryScreen
import app.hakusan.sdk.LibraryScreenService
import app.hakusan.sdk.ScreenTitleId
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LibraryViewModelTest {
  @Test
  fun `successful Add keeps bounded feedback under Flow authority`(): Unit =
    runBlocking {
      withTimeout(TEST_TIMEOUT_MILLIS) {
        withModel { model, service ->
          service.membershipStarted.await()
          assertSame(LibraryMembership.Loading, model.membership(TITLE_ID))
          service.emitMembership(emptySet())
          awaitCondition {
            model.membership(TITLE_ID) == LibraryMembership.NotMember
          }

          model.addToLibrary(TITLE_ID)
          model.addToLibrary(TITLE_ID)
          val addition = service.addRequests.receive()
          assertTrue(service.addRequests.tryReceive().isFailure)
          assertSame(LibraryAddState.Adding, model.addState(TITLE_ID))

          addition.complete(AddToLibraryScreenResult.Success)
          awaitCondition {
            model.addState(TITLE_ID) == LibraryAddState.Committed
          }
          assertSame(LibraryMembership.Member, model.membership(TITLE_ID))
          model.addToLibrary(TITLE_ID)
          assertTrue(service.addRequests.tryReceive().isFailure)

          service.emitMembership(setOf(TITLE_ID))
          awaitCondition {
            model.membership(TITLE_ID) == LibraryMembership.Member
          }
          assertSame(
            LibraryAddState.Committed,
            model.addState(TITLE_ID),
          )
          assertSame(LibraryMembership.Member, model.membership(TITLE_ID))

          service.emitMembership(emptySet())
          awaitCondition {
            model.membership(TITLE_ID) == LibraryMembership.NotMember
          }
          assertSame(LibraryAddState.Idle, model.addState(TITLE_ID))
        }
      }
    }

  @Test
  fun `later negative membership retires a committed Add bridge`(): Unit =
    runBlocking {
      withTimeout(TEST_TIMEOUT_MILLIS) {
        withModel { model, service ->
          service.membershipStarted.await()
          service.emitMembership(setOf(SECOND_TITLE_ID))
          awaitCondition {
            model.membership(TITLE_ID) == LibraryMembership.NotMember
          }

          model.addToLibrary(TITLE_ID)
          service.addRequests.receive().complete(
            AddToLibraryScreenResult.Success,
          )
          awaitCondition {
            model.addState(TITLE_ID) == LibraryAddState.Committed
          }
          assertSame(LibraryMembership.Member, model.membership(TITLE_ID))

          service.emitMembership(emptySet())
          awaitCondition {
            model.addState(TITLE_ID) == LibraryAddState.Idle
          }
          assertSame(
            LibraryMembership.NotMember,
            model.membership(TITLE_ID),
          )
        }
      }
    }

  @Test
  fun `membership before Add completion cannot supersede its success`(): Unit =
    runBlocking {
      withTimeout(TEST_TIMEOUT_MILLIS) {
        withModel { model, service ->
          service.membershipStarted.await()
          service.emitMembership(setOf(SECOND_TITLE_ID))
          awaitCondition {
            model.membership(TITLE_ID) == LibraryMembership.NotMember
          }

          model.addToLibrary(TITLE_ID)
          val addition = service.addRequests.receive()
          service.emitMembership(emptySet())
          service.awaitMembershipEmissions(2)
          addition.complete(AddToLibraryScreenResult.Success)

          awaitCondition {
            model.addState(TITLE_ID) == LibraryAddState.Committed
          }
          assertSame(LibraryMembership.Member, model.membership(TITLE_ID))

          service.emitMembership(setOf(SECOND_TITLE_ID))
          awaitCondition {
            model.addState(TITLE_ID) == LibraryAddState.Idle
          }
          assertSame(
            LibraryMembership.NotMember,
            model.membership(TITLE_ID),
          )
        }
      }
    }

  @Test
  fun `only the latest completed Add retains feedback`(): Unit = runBlocking {
    withTimeout(TEST_TIMEOUT_MILLIS) {
      withModel { model, service ->
        service.membershipStarted.await()
        service.emitMembership(setOf(UNRELATED_TITLE_ID))
        awaitCondition {
          model.membership(TITLE_ID) == LibraryMembership.NotMember
        }

        model.addToLibrary(TITLE_ID)
        service.addRequests.receive().complete(
          AddToLibraryScreenResult.Success,
        )
        awaitCondition {
          model.addState(TITLE_ID) == LibraryAddState.Committed
        }

        model.addToLibrary(SECOND_TITLE_ID)
        val secondAddition = service.addRequests.receive()
        assertEquals(SECOND_TITLE_ID, secondAddition.titleId)
        secondAddition.complete(AddToLibraryScreenResult.Success)
        awaitCondition {
          model.addState(SECOND_TITLE_ID) == LibraryAddState.Committed
        }

        assertSame(LibraryAddState.Idle, model.addState(TITLE_ID))
        assertSame(LibraryMembership.Member, model.membership(TITLE_ID))
        assertSame(
          LibraryMembership.Member,
          model.membership(SECOND_TITLE_ID),
        )

        service.emitMembership(emptySet())
        awaitCondition {
          model.membership(TITLE_ID) == LibraryMembership.NotMember &&
            model.membership(SECOND_TITLE_ID) ==
            LibraryMembership.NotMember
        }
        assertSame(
          LibraryAddState.Idle,
          model.addState(SECOND_TITLE_ID),
        )
      }
    }
  }

  @Test
  fun `membership confirmation overrides a later Add outcome`(): Unit =
    runBlocking {
      withTimeout(TEST_TIMEOUT_MILLIS) {
        withModel { model, service ->
          service.membershipStarted.await()
          service.emitMembership(emptySet())
          awaitCondition {
            model.membership(TITLE_ID) == LibraryMembership.NotMember
          }
          model.addToLibrary(TITLE_ID)
          val addition = service.addRequests.receive()

          service.emitMembership(setOf(TITLE_ID))
          awaitCondition {
            model.membership(TITLE_ID) == LibraryMembership.Member
          }
          assertSame(LibraryAddState.Idle, model.addState(TITLE_ID))

          addition.complete(AddToLibraryScreenResult.TitleNotFound)
          yield()
          assertSame(LibraryAddState.Idle, model.addState(TITLE_ID))
          assertSame(LibraryMembership.Member, model.membership(TITLE_ID))
        }
      }
    }

  @Test
  fun `membership before Add success retains committed feedback`(): Unit =
    runBlocking {
      withTimeout(TEST_TIMEOUT_MILLIS) {
        withModel { model, service ->
          service.membershipStarted.await()
          service.emitMembership(emptySet())
          awaitCondition {
            model.membership(TITLE_ID) == LibraryMembership.NotMember
          }
          model.addToLibrary(TITLE_ID)
          val addition = service.addRequests.receive()

          service.emitMembership(setOf(TITLE_ID))
          awaitCondition {
            model.membership(TITLE_ID) == LibraryMembership.Member
          }
          assertSame(LibraryAddState.Idle, model.addState(TITLE_ID))

          addition.complete(AddToLibraryScreenResult.Success)
          awaitCondition {
            model.addState(TITLE_ID) == LibraryAddState.Committed
          }
          assertSame(LibraryMembership.Member, model.membership(TITLE_ID))

          service.emitMembership(emptySet())
          awaitCondition {
            model.addState(TITLE_ID) == LibraryAddState.Idle
          }
          assertSame(
            LibraryMembership.NotMember,
            model.membership(TITLE_ID),
          )
        }
      }
    }

  @Test
  fun `Add outcomes remain distinct and retryable`(): Unit = runBlocking {
    withTimeout(TEST_TIMEOUT_MILLIS) {
      withModel { model, service ->
        service.membershipStarted.await()
        service.emitMembership(emptySet())
        awaitCondition {
          model.membership(TITLE_ID) == LibraryMembership.NotMember
        }

        model.addToLibrary(TITLE_ID)
        service.addRequests.receive().complete(
          AddToLibraryScreenResult.CategorySelectionRequired,
        )
        awaitCondition {
          model.addState(TITLE_ID) ==
            LibraryAddState.CategorySelectionRequired
        }

        model.addToLibrary(TITLE_ID)
        service.addRequests.receive().complete(
          AddToLibraryScreenResult.TitleNotFound,
        )
        awaitCondition {
          model.addState(TITLE_ID) == LibraryAddState.TitleNotFound
        }
        assertSame(
          LibraryMembership.NotMember,
          model.membership(TITLE_ID),
        )
      }
    }
  }

  @Test
  fun `full Library is while-subscribed and retains its last state`(): Unit =
    runBlocking {
      withTimeout(TEST_TIMEOUT_MILLIS) {
        withModel { model, service ->
          service.membershipStarted.await()
          assertTrue(service.fullStarted.tryReceive().isFailure)
          assertSame(LibraryLoadState.Loading, model.libraryState.value)

          val firstObserved = CompletableDeferred<LibraryLoadState.Loaded>()
          val firstCollector = collectLibrary(model, firstObserved)
          service.fullStarted.receive()
          service.emitLibrary(EMPTY_LIBRARY)
          val loaded = firstObserved.await()
          firstCollector.cancelAndJoin()
          service.fullStopped.receive()

          assertEquals(EMPTY_LIBRARY, loaded.screen)
          assertEquals(loaded, model.libraryState.value)

          val replayed = CompletableDeferred<LibraryLoadState.Loaded>()
          val secondCollector = collectLibrary(model, replayed)
          assertEquals(loaded, replayed.await())
          service.fullStarted.receive()
          secondCollector.cancelAndJoin()
          service.fullStopped.receive()
          assertEquals(loaded, model.libraryState.value)
        }
      }
    }

  private suspend fun withModel(
    block: suspend (
      LibraryViewModel,
      ControlledLibraryService,
    ) -> Unit,
  ) {
    val owner = SupervisorJob(coroutineContext[Job])
    val scope = CoroutineScope(coroutineContext + owner)
    val service = ControlledLibraryService()
    val model = LibraryViewModel(service, scope)
    try {
      block(model, service)
    } finally {
      scope.cancel()
    }
  }

  private fun CoroutineScope.collectLibrary(
    model: LibraryViewModel,
    loaded: CompletableDeferred<LibraryLoadState.Loaded>,
  ): Job = launch(start = CoroutineStart.UNDISPATCHED) {
    model.libraryState.collect { state ->
      if (state is LibraryLoadState.Loaded) {
        loaded.complete(state)
      }
    }
  }

  private class ControlledLibraryService : LibraryScreenService {
    private val libraries = Channel<LibraryScreen>(Channel.UNLIMITED)
    private val memberships =
      Channel<Set<ScreenTitleId>>(Channel.UNLIMITED)
    val addRequests = Channel<PendingAdd>(Channel.UNLIMITED)
    val fullStarted = Channel<Unit>(Channel.UNLIMITED)
    val fullStopped = Channel<Unit>(Channel.UNLIMITED)
    val membershipStarted = CompletableDeferred<Unit>()
    private var membershipEmissionCount = 0
    private var membershipEmissionWaiter =
      CompletableDeferred<Int>()

    override fun observeLibrary(): Flow<LibraryScreen> = flow {
      fullStarted.send(Unit)
      try {
        for (screen in libraries) {
          emit(screen)
        }
      } finally {
        fullStopped.trySend(Unit)
      }
    }

    override fun observeLibraryTitleIds(): Flow<Set<ScreenTitleId>> = flow {
      membershipStarted.complete(Unit)
      for (titleIds in memberships) {
        emit(titleIds)
        membershipEmissionCount += 1
        membershipEmissionWaiter.complete(membershipEmissionCount)
        membershipEmissionWaiter = CompletableDeferred()
      }
    }

    override suspend fun addToLibrary(
      titleId: ScreenTitleId,
    ): AddToLibraryScreenResult {
      val pending = PendingAdd(titleId)
      addRequests.send(pending)
      return pending.completion.await()
    }

    fun emitLibrary(screen: LibraryScreen) {
      check(libraries.trySend(screen).isSuccess)
    }

    fun emitMembership(titleIds: Set<ScreenTitleId>) {
      check(memberships.trySend(titleIds).isSuccess)
    }

    suspend fun awaitMembershipEmissions(count: Int) {
      while (membershipEmissionCount < count) {
        membershipEmissionWaiter.await()
      }
    }
  }

  private class PendingAdd(
    val titleId: ScreenTitleId,
  ) {
    val completion = CompletableDeferred<AddToLibraryScreenResult>()

    fun complete(result: AddToLibraryScreenResult) {
      completion.complete(result)
    }
  }

  private companion object {
    const val TEST_TIMEOUT_MILLIS = 5_000L
    val TITLE_ID = ScreenTitleId(
      UUID.fromString("00000000-0000-7000-8000-000000000001"),
    )
    val SECOND_TITLE_ID = ScreenTitleId(
      UUID.fromString("00000000-0000-7000-8000-000000000002"),
    )
    val UNRELATED_TITLE_ID = ScreenTitleId(
      UUID.fromString("00000000-0000-7000-8000-000000000003"),
    )
    val EMPTY_LIBRARY = LibraryScreen.of(
      titlesById = emptyMap(),
      shelves = emptyList(),
    )
  }
}
