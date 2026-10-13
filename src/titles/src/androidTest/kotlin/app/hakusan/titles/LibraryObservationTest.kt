package app.hakusan.titles

import androidx.room3.Room
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.hakusan.titles.storage.RoomTitles
import app.hakusan.titles.storage.TitlesDatabase
import app.hakusan.titles.storage.mapLibraryTitleIds
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Library observation and the membership observation on an in-memory Room
 * database: counts, membership, empty categories, resume availability,
 * suppression of equal values, the worker that builds them, and stored-state
 * violations.
 */
@RunWith(AndroidJUnit4::class)
class LibraryObservationTest {
  private lateinit var database: TitlesDatabase

  private lateinit var titles: Titles

  @Before
  fun openDatabase() {
    database =
      Room.inMemoryDatabaseBuilder<TitlesDatabase>()
        .setDriver(AndroidSQLiteDriver())
        .build()

    titles = RoomTitles(database)
  }

  @After
  fun closeDatabase() {
    database.close()
  }

  @Test
  fun titleInTwoCategoriesCountsOnceAndNonmembersAreExcluded(): Unit =
    runBlocking {
      seedCategories("First", "Second", "Empty")

      val both = reconcile("both", "a", "b", "c")

      val outside = reconcile("outside", "x", "y")

      val (a, b) = both.chapterIds
      val (x, y) = outside.chapterIds

      titles.addToLibrary(both.titleId, selection(1, 2))
      boundary(both.titleId, a, b, 2)
      boundary(outside.titleId, x, y, 0)
      finish(outside.titleId, y)

      val snapshot =
        withTimeout(TIMEOUT_MILLIS) { titles.observeLibrary().first() }

      assertEquals(3L, count("read_chapters"))
      assertEquals(setOf(both.titleId), snapshot.titles.keys)

      assertEquals(
        LibraryTitle(
          alias = SourceTitleAlias.of("source", "both"),
          displayName = "Title both",
          description = null,
          progress = CompactProgress(3, 1, ResumeAvailability.AVAILABLE),
        ),
        snapshot.titles.getValue(both.titleId),
      )

      assertEquals(
        mapOf(
          "First" to setOf(both.titleId),
          "Second" to setOf(both.titleId),
          "Empty" to emptySet(),
        ),
        snapshot.shelves.associate { shelf ->
          shelf.category.name to shelf.titleIds
        },
      )
    }

  @Test
  fun resumeAvailabilityFollowsPositionsOmissionsAndCompletion(): Unit =
    runBlocking {
      val title = member("title", "a", "b")

      val (a, b) = title.chapterIds

      val library = ObservedValues(this, titles.observeLibrary())

      assertEquals(progress(2, 0, NONE), library.next().progressOf(title))

      record(title.titleId, a, 0)

      assertEquals(progress(2, 0, AVAILABLE), library.next().progressOf(title))

      reconcile("title", "b")

      assertEquals(
        progress(1, 0, TEMPORARILY_UNAVAILABLE),
        library.next().progressOf(title),
      )

      reconcile("title", "a", "b")

      assertEquals(progress(2, 0, AVAILABLE), library.next().progressOf(title))

      boundary(title.titleId, a, b, 0)

      assertEquals(progress(2, 1, AVAILABLE), library.next().progressOf(title))

      finish(title.titleId, b)

      assertEquals(progress(2, 2, NONE), library.next().progressOf(title))

      library.cancel()
    }

  /**
   * Writes that leave the snapshot equal emit nothing before the next change.
   *
   * Room may fold the equal writes and the later boundary into one rerun, so
   * this test proves only that no extra emission precedes the next change.
   * LibraryObservationsTest owns the deterministic proof that equal snapshots
   * are suppressed after mapping.
   */
  @Test
  fun writeThatLeavesTheSnapshotEqualEmitsNothing(): Unit = runBlocking {
    val title = member("title", "a", "b")

    val (a, b) = title.chapterIds

    record(title.titleId, a, 0)

    val library = ObservedValues(this, titles.observeLibrary())

    assertEquals(progress(2, 0, AVAILABLE), library.next().progressOf(title))

    record(title.titleId, a, 5)
    titles.reconcileTitle(observation("title", listOf("a", "b"), "Episode"))
    boundary(title.titleId, a, b, 0)

    assertEquals(progress(2, 1, AVAILABLE), library.next().progressOf(title))

    library.cancel()
  }

  @Test
  fun progressAndChapterWritesNeverMakeTheMembershipEmit(): Unit = runBlocking {
    val title = member("title", "a", "b")

    val outside = reconcile("outside", "x")

    val (a, b) = title.chapterIds

    val members = ObservedValues(this, titles.observeLibraryTitleIds())

    assertEquals(setOf(title.titleId), members.next())

    record(title.titleId, a, 3)
    boundary(title.titleId, a, b, 0)
    finish(title.titleId, b)
    reconcile("title", "a", "b", "c")

    titles.reconcileTitle(
      observation("title", listOf("a"), "Episode", displayName = "Renamed")
    )

    finish(outside.titleId, outside.chapterIds.single())
    titles.addToLibrary(outside.titleId)

    assertEquals(setOf(title.titleId, outside.titleId), members.next())

    members.cancel()
  }

  /**
   * The membership query never reruns for progress writes or for chapter writes
   * that leave the title row unchanged. The raw DAO flow has no suppression, so
   * its next emission must come from the later Add.
   */
  @Test
  fun membershipQueryNeverRerunsForProgressAndChapterWrites(): Unit =
    runBlocking {
      val title = member("title", "a", "b")

      val outside = reconcile("outside", "x")

      val (a, b) = title.chapterIds

      val rows =
        ObservedValues(this, database.titlesDao().observeLibraryTitles())

      assertEquals(setOf(title.titleId), mapLibraryTitleIds(rows.next()))

      record(title.titleId, a, 3)
      boundary(title.titleId, a, b, 0)
      finish(title.titleId, b)
      reconcile("title", "a", "b", "c")
      reconcile("outside", "x", "y")
      titles.addToLibrary(outside.titleId)

      assertEquals(
        setOf(title.titleId, outside.titleId),
        mapLibraryTitleIds(rows.next()),
      )

      rows.cancel()
    }

  /**
   * The observations collect on the injected worker and deliver on the
   * subscriber. LibraryObservationsTest owns the proof that the mapping itself
   * runs on the worker.
   */
  @Test
  fun observationsUseTheInjectedWorkerAndDeliverOnTheSubscriber(): Unit =
    runBlocking {
      member("title", "a")

      val worker = RecordingDispatcher(singleThread(WORKER_NAME))

      val subscriber = singleThread(SUBSCRIBER_NAME)

      val observed = RoomTitles(database, observationDispatcher = worker)

      val deliveries = Collections.synchronizedSet(HashSet<Thread>())

      try {
        withContext(subscriber) {
          withTimeout(TIMEOUT_MILLIS) {
            observed.observeLibrary().first()
            deliveries += Thread.currentThread()
            observed.observeLibraryTitleIds().first()
            deliveries += Thread.currentThread()
          }
        }
      } finally {
        subscriber.close()
        worker.close()
      }

      assertEquals(setOf(SUBSCRIBER_NAME), deliveries.names())
      assertEquals(setOf(WORKER_NAME), worker.threads.names())
      assertFalse(worker.threads.any(deliveries::contains))
    }

  @Test
  fun contradictoryStoredRowRaisesInACollectorOfEachObservation(): Unit =
    runBlocking {
      val title = member("title", "a", "b")

      record(title.titleId, title.chapterIds.first(), 1)

      execute(
        "INSERT INTO read_chapters (chapter_storage_id) " +
          "SELECT chapter_storage_id FROM library_resume_positions"
      )

      val readResume = violationOf(titles.observeLibrary())

      val unaffected =
        withTimeout(TIMEOUT_MILLIS) {
          titles.observeLibraryTitleIds().first()
        }

      execute(
        "UPDATE titles SET source_title_key = ' ' " +
          "WHERE source_title_key = 'title'"
      )

      val libraryAlias = violationOf(titles.observeLibrary())

      val membersAlias = violationOf(titles.observeLibraryTitleIds())

      assertEquals(StoredStateViolationKind.RESUME_STATE, readResume.kind)
      assertEquals(setOf(title.titleId), unaffected)
      assertEquals(StoredStateViolationKind.ALIAS, libraryAlias.kind)
      assertEquals(StoredStateViolationKind.ALIAS, membersAlias.kind)
    }

  @Test
  fun canonicalOrderGapRaisesInALibraryCollector(): Unit = runBlocking {
    member("title", "a", "b")

    execute("UPDATE chapters SET canonical_index = 5 WHERE canonical_index = 1")

    val gap = violationOf(titles.observeLibrary())

    assertEquals(StoredStateViolationKind.CANONICAL_ORDER, gap.kind)
  }

  /** Reconciles the title [key] with [chapters] and adds it to the Library. */
  private suspend fun member(
    key: String,
    vararg chapters: String,
  ): ProgressSnapshot {
    val snapshot = reconcile(key, *chapters)

    titles.addToLibrary(snapshot.titleId)

    return snapshot
  }

  private suspend fun reconcile(
    key: String,
    vararg chapters: String,
  ): ProgressSnapshot =
    titles.reconcileTitle(observation(key, chapters.toList(), "Chapter"))

  /**
   * The observation of the title [key], named [displayName], with [chapters],
   * each named by [chapterName] and its key.
   */
  private fun observation(
    key: String,
    chapters: List<String>,
    chapterName: String,
    displayName: String = "Title $key",
  ): AcceptedTitleObservation {
    val alias = SourceTitleAlias.of("source", key)

    val accepted = chapters.map { chapter ->
      AcceptedChapter(
        SourceChapterAlias(alias, TitleLocalChapterKey.of(chapter)),
        "$chapterName $chapter",
      )
    }

    return AcceptedTitleObservation.of(alias, displayName, null, accepted)
  }

  private suspend fun record(
    titleId: TitleId,
    chapterId: ChapterId,
    unitIndex: Int,
  ): ActualPositionResult {
    val position =
      ReadingPosition.of(titleId, chapterId, ContentUnitKind.PAGE, unitIndex)

    return titles.recordActualPosition(
      ActualPositionUpdate(position, ProgressEventRecency.CURRENT)
    )
  }

  private suspend fun boundary(
    titleId: TitleId,
    completed: ChapterId,
    started: ChapterId,
    startedIndex: Int,
  ): ChapterBoundaryResult {
    val position =
      ReadingPosition.of(titleId, started, ContentUnitKind.PAGE, startedIndex)

    val completion =
      ChapterBoundaryCompletion.of(
        completed,
        position,
        ProgressEventRecency.CURRENT,
      )

    return titles.completeChapterBoundary(completion)
  }

  private suspend fun finish(
    titleId: TitleId,
    chapterId: ChapterId,
  ): FinalCompletionResult =
    titles.completeFinalChapter(FinalChapterCompletion(titleId, chapterId))

  /** Inserts one category per name, with identities 1, 2, and so on. */
  private suspend fun seedCategories(vararg names: String) {
    names.forEachIndexed { index, name ->
      execute(
        "INSERT INTO categories (id, name) VALUES (${index + 1}, '$name')"
      )
    }
  }

  private fun selection(vararg keys: Long): LibraryCategorySelection =
    LibraryCategorySelection.of(
      keys.map { key -> checkNotNull(CategoryId.ofOrNull(key)) }
    )

  /** Collects the first value of [flow] and returns the violation it raises. */
  private fun violationOf(flow: Flow<*>): StoredStateViolationException =
    assertThrows(StoredStateViolationException::class.java) {
      runBlocking { withTimeout(TIMEOUT_MILLIS) { flow.first() } }
    }

  private suspend fun count(table: String): Long =
    database.useReaderConnection { connection ->
      connection.usePrepared("SELECT count(*) FROM $table") { statement ->
        statement.step()

        statement.getLong(0)
      }
    }

  private suspend fun execute(sql: String) {
    database.useWriterConnection { connection ->
      connection.usePrepared(sql) { statement -> statement.step() }
    }
  }

  private fun progress(
    chapters: Int,
    reads: Int,
    availability: ResumeAvailability,
  ): CompactProgress = CompactProgress(chapters, reads, availability)

  private fun LibrarySnapshot.progressOf(
    title: ProgressSnapshot
  ): CompactProgress = this.titles.getValue(title.titleId).progress

  private val ProgressSnapshot.chapterIds: List<ChapterId>
    get() = chapters.map { state -> state.chapter.id }

  private fun Set<Thread>.names(): Set<String> =
    synchronized(this) { mapTo(HashSet(), Thread::getName) }

  private fun singleThread(name: String): ExecutorCoroutineDispatcher =
    Executors.newSingleThreadExecutor { task -> Thread(task, name) }
      .asCoroutineDispatcher()

  /** A dispatcher that records every thread on which it runs a block. */
  private class RecordingDispatcher(
    private val delegate: ExecutorCoroutineDispatcher
  ) : CoroutineDispatcher(), AutoCloseable {
    val threads: MutableSet<Thread> = Collections.synchronizedSet(HashSet())

    override fun dispatch(context: CoroutineContext, block: Runnable) {
      delegate.dispatch(context) {
        threads += Thread.currentThread()
        block.run()
      }
    }

    override fun close() {
      delegate.close()
    }
  }

  private companion object {
    const val TIMEOUT_MILLIS = 10_000L
    const val WORKER_NAME = "observation-worker"
    const val SUBSCRIBER_NAME = "observation-subscriber"

    val NONE = ResumeAvailability.NONE
    val AVAILABLE = ResumeAvailability.AVAILABLE
    val TEMPORARILY_UNAVAILABLE = ResumeAvailability.TEMPORARILY_UNAVAILABLE
  }
}
