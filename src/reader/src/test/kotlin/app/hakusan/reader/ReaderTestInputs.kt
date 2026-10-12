package app.hakusan.reader

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * A three-chapter title whose display names contradict canonical order.
 *
 * The first chapter repeats a reference, the second holds provider segments,
 * and the final one holds a single page.
 */
internal class ReaderTestBook {
  val title = ReaderTitleKey("source", "title")

  val chapters =
    listOf("10", "2", "1").mapIndexed { index, label ->
      ReaderChapter(ReaderChapterKey(title, "chapter-$index"), label)
    }

  val input = ReaderInput.of(title, chapters)

  val content =
    listOf(
      ReaderChapterContent.of(
        chapters[0],
        listOf(
          ReaderContentUnit(ReaderUnitKind.PAGE, "repeated"),
          ReaderContentUnit(ReaderUnitKind.PAGE, "repeated"),
          ReaderContentUnit(ReaderUnitKind.PAGE, "last"),
        ),
      ),
      ReaderChapterContent.of(
        chapters[1],
        List(3) { ReaderContentUnit(ReaderUnitKind.PROVIDER_SEGMENT, "s$it") },
      ),
      ReaderChapterContent.of(
        chapters[2],
        listOf(ReaderContentUnit(ReaderUnitKind.PAGE, "final")),
      ),
    )
}

/** Runs [block] on one thread, failing a hung test instead of blocking. */
internal fun readerTest(block: suspend CoroutineScope.() -> Unit) {
  runBlocking { withTimeout(5.seconds) { block() } }
}

/**
 * Runs [block] on the multi-threaded default dispatcher, failing a hung test
 * instead of blocking.
 */
internal fun readerStressTest(block: suspend CoroutineScope.() -> Unit) {
  runBlocking(Dispatchers.Default) { withTimeout(60.seconds) { block() } }
}

/** Issues the next event token of [session] if it is current. */
internal suspend fun ReaderSessionGate.issue(
  session: ReaderSession
): ReaderSessionAcceptance<ReaderSessionEvent> =
  withCurrentSession(session) { issue -> issue() }

/** Returns the value of work that the gate accepted. */
internal fun <Value> ReaderSessionAcceptance<Value>.accepted(): Value =
  (this as ReaderSessionAcceptance.Accepted).value

/** Returns the value of a navigation operation that applied. */
internal fun <Value> ReaderNavigationResult<Value>.accepted(): Value =
  (this as ReaderNavigationResult.Accepted).value

/** Returns the reason of a rejected navigation operation. */
internal fun ReaderNavigationResult<*>.rejection(): ReaderNavigationRejection =
  (this as ReaderNavigationResult.Rejected).reason

/** Asserts that the suspending [block] fails with exactly [Failure]. */
internal suspend inline fun <reified Failure : Throwable> assertFailsExactly(
  block: suspend () -> Unit
) {
  val failure = runCatching { block() }.exceptionOrNull()

  assertEquals(Failure::class.java, failure?.javaClass)
}
