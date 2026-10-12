package app.hakusan.reader

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

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
