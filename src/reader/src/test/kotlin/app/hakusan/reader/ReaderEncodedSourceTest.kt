package app.hakusan.reader

import java.util.concurrent.Executor
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderEncodedSourceTest {
  private val book = ReaderTestBook()

  private val descriptor =
    ReaderContentDescriptor.of(book.content[0], book.content[0].positionAt(2))

  @Test
  fun `an opened input belongs to the caller`() = readerTest {
    val source = WorkerSource(Dispatchers.Default)

    val opened = source.open(descriptor) as ReaderInputOpenResult.Opened

    val input = source.acquired.await()

    assertSame(input, opened.input)
    assertEquals(0, input.closeCount)
    opened.input.close()
    assertEquals(1, input.closeCount)

    val final = book.content[2]

    assertEquals(
      ReaderInputOpenResult.Failed(ReaderAcquisitionFailure.UNAVAILABLE),
      source.open(ReaderContentDescriptor.of(final, final.positionAt(0))),
    )
  }

  @Test
  fun `cancellation racing a completed acquisition closes the input`() =
    readerTest {
      val tasks = ArrayDeque<Runnable>()

      val worker = Executor(tasks::addLast).asCoroutineDispatcher()

      val source = WorkerSource(worker)

      val opening =
        async(start = CoroutineStart.UNDISPATCHED) { source.open(descriptor) }

      assertFalse(source.acquired.isCompleted)
      tasks.removeFirst().run()

      val input = source.acquired.await()

      assertFalse(opening.isCompleted)
      opening.cancel()
      opening.join()
      assertTrue(opening.isCancelled)
      assertTrue(tasks.isEmpty())
      assertEquals(1, input.closeCount)
    }

  /**
   * Acquires on a worker, as an I/O adapter does, and keeps the acquired input
   * reachable outside the cancellable suspension.
   */
  private class WorkerSource(private val worker: CoroutineContext) :
    ReaderEncodedSource {
    val acquired = CompletableDeferred<ControlledReaderInput>()

    override suspend fun open(
      descriptor: ReaderContentDescriptor
    ): ReaderInputOpenResult {
      if (descriptor.reference != "last") {
        return ReaderInputOpenResult.Failed(
          ReaderAcquisitionFailure.UNAVAILABLE
        )
      }

      var pending: ControlledReaderInput? = null

      try {
        withContext(worker) {
          pending = ControlledReaderInput(ByteArray(1))
          acquired.complete(checkNotNull(pending))
        }

        val input = checkNotNull(pending)

        pending = null

        return ReaderInputOpenResult.Opened(input)
      } finally {
        pending?.close()
      }
    }
  }
}
