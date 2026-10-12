package app.hakusan.reader

import app.hakusan.reader.ReaderAcquisitionFailure.FAILED
import app.hakusan.reader.ReaderAcquisitionFailure.REPRESENTATION_CHANGED
import app.hakusan.reader.ReaderInputRead.AwaitInput
import app.hakusan.reader.ReaderInputRead.Canceled
import app.hakusan.reader.ReaderInputRead.Data
import app.hakusan.reader.ReaderInputRead.EndOfInput
import app.hakusan.reader.ReaderInputRead.InputFailure
import app.hakusan.reader.ReaderInputRead.NoOp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrowsExactly
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderByteInputTest {
  private val script = ByteArray(10) { (it + 1).toByte() }

  @Test
  fun `short reads deliver every byte once before an exact end`() = readerTest {
    val input = ControlledReaderInput(script, length = null)

    val buffer = ByteArray(12)

    assertEquals(AwaitInput, input.read(buffer, 0, 12))

    val waiting =
      async(start = CoroutineStart.UNDISPATCHED) { input.awaitInput() }

    assertFalse(waiting.isCompleted)
    input.receiveThrough(4)
    waiting.await()
    assertEquals(Data(3), input.read(buffer, 1, 11))
    assertEquals(Data(1), input.read(buffer, 4, 8))
    assertEquals(AwaitInput, input.read(buffer, 5, 7))
    input.receiveThrough(10)
    input.end()
    input.awaitInput()
    assertEquals(Data(2), input.read(buffer, 5, 2))
    assertEquals(Data(3), input.read(buffer, 7, 5))
    assertEquals(Data(1), input.read(buffer, 10, 2))
    assertEquals(EndOfInput, input.read(buffer, 0, 12))
    assertEquals(EndOfInput, input.read(buffer, 0, 12))
    assertArrayEquals(byteArrayOf(0) + script + 0, buffer)
    assertEquals(10L, input.position)
    input.close()
    input.close()
    assertEquals(1, input.closeCount)
  }

  @Test
  fun `a zero-capacity read is a no-op in every state`() = readerTest {
    val input = ControlledReaderInput(script, maxTransfer = 10)

    val buffer = ByteArray(10)

    assertEquals(NoOp, input.read(buffer, 0, 0))
    assertEquals(NoOp, input.read(buffer, 10, 0))
    assertEquals(0L, input.position)
    input.receiveThrough(4)
    assertEquals(NoOp, input.read(buffer, 0, 0))
    assertEquals(0L, input.position)
    assertEquals(Data(4), input.read(buffer, 0, 10))
    assertEquals(AwaitInput, input.read(buffer, 0, 10))
    assertEquals(NoOp, input.read(buffer, 0, 0))
    input.fail(FAILED)
    assertEquals(NoOp, input.read(buffer, 0, 0))
    assertEquals(InputFailure(FAILED), input.read(buffer, 0, 10))
    assertEquals(NoOp, input.read(buffer, 0, 0))
    assertEquals(4L, input.position)
    input.close()
  }

  @Test
  fun `an unknown length is distinct from an empty input`() = readerTest {
    val empty = ControlledReaderInput(ByteArray(0))

    val unknown = ControlledReaderInput(ByteArray(0), length = null)

    assertEquals(0L, empty.capabilities.length)
    assertNull(unknown.capabilities.length)
    assertNotEquals(empty.capabilities, unknown.capabilities)
    empty.end()
    unknown.end()
    assertEquals(EndOfInput, empty.read(ByteArray(1), 0, 1))
    assertEquals(EndOfInput, unknown.read(ByteArray(1), 0, 1))

    assertThrowsExactly(IllegalArgumentException::class.java) {
      ReaderInputCapabilities(-1, rewind = false, seek = false)
    }

    empty.close()
    unknown.close()
  }

  @Test
  fun `a failure follows the valid prefix once and stays terminal`() =
    readerTest {
      val input = ControlledReaderInput(script, length = null)

      val buffer = ByteArray(10)

      val failure = InputFailure(REPRESENTATION_CHANGED)

      input.receiveThrough(5)
      input.fail(REPRESENTATION_CHANGED)
      assertEquals(Data(3), input.read(buffer, 0, 10))
      assertEquals(Data(2), input.read(buffer, 3, 7))
      assertEquals(failure, input.read(buffer, 0, 10))
      assertEquals(failure, input.read(buffer, 0, 10))
      assertArrayEquals(script.copyOf(5), buffer.copyOf(5))
      assertEquals(ReaderInputSeek.Terminal, input.seek(0))
      assertEquals(5L, input.position)

      assertThrowsExactly(IllegalStateException::class.java) { input.end() }

      assertThrowsExactly(IllegalStateException::class.java) {
        input.receiveThrough(10)
      }

      input.close()
    }

  @Test
  fun `a declared length mismatch is a failure, never an end`() = readerTest {
    val short = ControlledReaderInput(script.copyOf(4), length = 5)

    val long = ControlledReaderInput(script, length = 4, maxTransfer = 10)

    val buffer = ByteArray(10)

    short.receiveThrough(4)
    short.end()
    long.receiveThrough(10)
    assertEquals(Data(3), short.read(buffer, 0, 10))
    assertEquals(Data(1), short.read(buffer, 0, 10))
    assertEquals(InputFailure(FAILED), short.read(buffer, 0, 10))
    assertEquals(Data(4), long.read(buffer, 0, 10))
    assertEquals(InputFailure(FAILED), long.read(buffer, 0, 10))
    short.close()
    long.close()
  }

  @Test
  fun `a read range outside the destination is rejected before access`() =
    readerTest {
      val input = ControlledReaderInput(script)

      val buffer = ByteArray(4)

      input.receiveThrough(10)

      for ((offset, length) in listOf(-1 to 1, 0 to -1, 3 to 2, 5 to 0)) {
        assertThrowsExactly(IndexOutOfBoundsException::class.java) {
          readerTest { input.read(buffer, offset, length) }
        }
      }

      assertEquals(0L, input.position)
      assertArrayEquals(ByteArray(4), buffer)
      input.close()
    }

  @Test
  fun `cancellation while awaiting input leaves the input canceled`() =
    readerTest {
      val input = ControlledReaderInput(script)

      val waiting =
        launch(start = CoroutineStart.UNDISPATCHED) { input.awaitInput() }

      waiting.cancelAndJoin()
      input.receiveThrough(10)
      input.end()
      assertEquals(Canceled, input.read(ByteArray(10), 0, 10))
      assertEquals(ReaderInputSeek.Terminal, input.seek(0))
      assertEquals(0L, input.position)
      input.close()
      assertEquals(1, input.closeCount)
    }

  @Test
  fun `a cancelled read never lets a later read skip its bytes`() = readerTest {
    val input = ControlledReaderInput(script, seek = true)

    val buffer = ByteArray(10)

    input.receiveThrough(10)

    val transfer = input.holdNextTransfer()

    val reading =
      async(start = CoroutineStart.UNDISPATCHED) { input.read(buffer, 0, 10) }

    assertFalse(reading.isCompleted)
    transfer.complete(Unit)
    reading.cancel()
    reading.join()
    assertTrue(reading.isCancelled)
    assertEquals(Canceled, input.read(buffer, 0, 10))
    assertEquals(Canceled, input.read(buffer, 0, 10))
    assertEquals(ReaderInputSeek.Terminal, input.seek(0))
    input.close()
  }

  @Test
  fun `positioning follows the declared capabilities`() = readerTest {
    val sequential = ControlledReaderInput(script)

    val rewind = ControlledReaderInput(script, length = null, retainedFrom = 2)

    val seekable = ControlledReaderInput(script, seek = true)

    sequential.receiveThrough(10)
    rewind.receiveThrough(6)
    seekable.receiveThrough(6)
    assertEquals(ReaderInputSeek.Unsupported, sequential.seek(0))
    assertEquals(ReaderInputSeek.Positioned, rewind.seek(6))
    assertEquals(ReaderInputSeek.Positioned, rewind.seek(2))
    assertEquals(ReaderInputSeek.HistoryUnavailable, rewind.seek(1))
    assertEquals(2L, rewind.position)
    assertEquals(ReaderInputSeek.NotYetAvailable, rewind.seek(7))
    assertEquals(ReaderInputSeek.NotYetAvailable, seekable.seek(8))
    assertEquals(ReaderInputSeek.BeyondEnd, seekable.seek(11))
    assertEquals(0L, seekable.position)
    assertEquals(ReaderInputSeek.Positioned, seekable.seek(6))
    assertEquals(AwaitInput, seekable.read(ByteArray(4), 0, 4))
    rewind.end()
    assertEquals(ReaderInputSeek.BeyondEnd, rewind.seek(7))
    assertEquals(ReaderInputSeek.Positioned, rewind.seek(4))
    assertEquals(Data(2), rewind.read(ByteArray(4), 0, 4))
    assertEquals(EndOfInput, rewind.read(ByteArray(4), 0, 4))
    sequential.close()
    rewind.close()
    seekable.close()
  }

  @Test
  fun `a seek past the bytes of a failed input reports the failure`() =
    readerTest {
      val input = ControlledReaderInput(script, seek = true, maxTransfer = 10)

      val buffer = ByteArray(10)

      input.receiveThrough(5)
      input.fail(FAILED)
      assertEquals(ReaderInputSeek.Failed(FAILED), input.seek(8))
      assertEquals(0L, input.position)
      assertEquals(Data(5), input.read(buffer, 0, 10))
      assertEquals(InputFailure(FAILED), input.read(buffer, 0, 10))
      input.close()
    }

  @Test
  fun `a positioning failure keeps the cursor or invalidates the input`() =
    readerTest {
      val kept = ControlledReaderInput(script, seek = true, maxTransfer = 10)

      val invalidated = ControlledReaderInput(script, seek = true)

      val buffer = ByteArray(10)

      kept.receiveThrough(10)
      invalidated.receiveThrough(10)
      kept.failNextPositioning(FAILED, invalidates = false)
      invalidated.failNextPositioning(FAILED, invalidates = true)
      assertEquals(ReaderInputSeek.Failed(FAILED), kept.seek(4))
      assertEquals(0L, kept.position)
      assertEquals(ReaderInputSeek.Positioned, kept.seek(4))
      assertEquals(Data(6), kept.read(buffer, 0, 10))
      assertEquals(ReaderInputSeek.Invalidated(FAILED), invalidated.seek(4))
      assertEquals(InputFailure(FAILED), invalidated.read(buffer, 0, 10))
      assertEquals(ReaderInputSeek.Terminal, invalidated.seek(0))
      kept.close()
      invalidated.close()
    }

  @Test
  fun `operations after close and concurrent operations are defects`() =
    readerTest {
      val input = ControlledReaderInput(script)

      val waiting =
        launch(start = CoroutineStart.UNDISPATCHED) { input.awaitInput() }

      assertThrowsExactly(IllegalStateException::class.java) {
        readerTest { input.read(ByteArray(1), 0, 1) }
      }

      assertThrowsExactly(IllegalStateException::class.java) { input.close() }

      waiting.cancelAndJoin()
      input.close()

      assertThrowsExactly(IllegalStateException::class.java) {
        readerTest { input.read(ByteArray(1), 0, 0) }
      }

      assertThrowsExactly(IllegalStateException::class.java) {
        readerTest { input.seek(0) }
      }
    }

  @Test
  fun `representation tokens compare by identity`() {
    val token = ReaderRepresentation()

    assertEquals(token, token)
    assertNotEquals(token, ReaderRepresentation())
  }
}
