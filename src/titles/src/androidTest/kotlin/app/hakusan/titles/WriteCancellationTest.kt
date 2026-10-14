package app.hakusan.titles

import androidx.room3.Room
import androidx.room3.useReaderConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.hakusan.titles.storage.RoomTitles
import app.hakusan.titles.storage.TitlesDatabase
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Writes whose caller is cancelled, on an in-memory Room database: the
 * observations after a cancellation that follows the commit, and the order of
 * the end of a transaction and the completion of its caller.
 *
 * The driver of the database records the transaction statements of each
 * operation after SQLite executes them, and each test records the completion of
 * its caller between them. The refresh that a cancelled write requests runs a
 * transaction of its own, which the log leaves out. The query dispatcher of the
 * database is a [TransactionGate].
 */
@RunWith(AndroidJUnit4::class)
class WriteCancellationTest {
  private val gate = TransactionGate()

  private val log = TransactionLog()

  private lateinit var database: TitlesDatabase

  private lateinit var titles: Titles

  @Before
  fun openDatabase() {
    database =
      Room.inMemoryDatabaseBuilder<TitlesDatabase>()
        .setQueryCoroutineContext(gate)
        .setDriver(log)
        .build()

    titles = RoomTitles(database)
  }

  @After
  fun closeDatabase() {
    database.close()
  }

  /**
   * A reconciliation whose caller is cancelled after the commit still makes an
   * active Library collector read again, without any further write.
   *
   * Room refreshes the observations only after the caller resumes normally.
   * Without the refresh that RoomTitles requests on a cancellation, the
   * collector never reads the commit, and its next value times out.
   */
  @Test
  fun reconcileCancelledAfterTheCommitStillNotifiesTheLibrary(): Unit =
    runBlocking {
      val titleId = reconcile("a", "b").titleId

      titles.addToLibrary(titleId)

      val library = ObservedValues(this, titles.observeLibrary())

      val initial = library.next().titles.getValue(titleId)

      val thrown = cancelAfterCommit {
        titles.reconcileTitle(observation("a", "b", "c"))
      }

      val notified = library.next().titles.getValue(titleId)

      val progress = titles.readProgress(titleId)

      assertEquals(2, initial.progress.chapterCount)
      assertTrue(thrown is CancellationException)
      assertEquals(3, notified.progress.chapterCount)

      assertEquals(
        listOf("a", "b", "c"),
        progress?.chapters?.map { state -> state.chapter.localKey.text },
      )

      library.cancel()
    }

  /**
   * An automatic Add whose caller is cancelled after the commit still makes an
   * active membership collector read again, without any further write.
   *
   * Without the refresh that RoomTitles requests on a cancellation, the
   * collector never receives the committed member, and its next value times
   * out.
   */
  @Test
  fun addCancelledAfterTheCommitStillNotifiesTheMembership(): Unit =
    runBlocking {
      val titleId = reconcile("a").titleId

      val members = ObservedValues(this, titles.observeLibraryTitleIds())

      val initial = members.next()

      val thrown = cancelAfterCommit { titles.addToLibrary(titleId) }

      val notified = members.next()

      val progress = titles.readProgress(titleId)

      assertEquals(emptySet<TitleId>(), initial)
      assertTrue(thrown is CancellationException)
      assertEquals(setOf(titleId), notified)
      assertEquals(true, progress?.isInLibrary)

      members.cancel()
    }

  /**
   * A reconciliation whose caller is cancelled inside its transaction rolls
   * back, and the job of the caller completes only after the rollback.
   *
   * The chapter identity generator runs inside the transaction and cancels the
   * caller there. The next statement observes the cancellation, and Room rolls
   * back. Room runs the transaction as a child of the caller, so the caller
   * cannot complete while the transaction is open.
   */
  @Test
  fun callerCancelledInsideItsTransactionCompletesAfterTheRollback(): Unit =
    runBlocking {
      reconcile("a", "b")

      val before = storage()

      val thrown = AtomicReference<Throwable?>()

      lateinit var caller: Job

      val cancelling =
        RoomTitles(
          database,
          newChapterId = {
            caller.cancel()
            ChapterId.generate()
          },
        )

      caller =
        launch(start = CoroutineStart.LAZY) {
          recordThrown(thrown) {
            cancelling.reconcileTitle(observation("z", "a", "b"))
          }
        }

      val completion = completionOf(caller)

      settle()
      caller.start()

      withTimeout(TIMEOUT_MILLIS) { completion.join() }

      val events = settledEvents()

      assertTrue(thrown.get() is CancellationException)
      assertEquals(listOf(BEGIN, ROLLBACK, COMPLETED), events)
      assertEquals(before, storage())
    }

  /**
   * A reconciliation whose caller is cancelled after the last cancellation
   * check of its transaction commits, and the job of the caller completes only
   * after the commit, with the cancellation.
   *
   * The log cancels the caller just before END TRANSACTION executes. Room ends
   * a transaction without a cancellation check, so the transaction commits, and
   * the cancellation reaches the caller afterward.
   */
  @Test
  fun callerCancelledAtTheCommitCompletesAfterTheCommit(): Unit = runBlocking {
    val titleId = reconcile("a", "b").titleId

    val thrown = AtomicReference<Throwable?>()

    val caller =
      launch(start = CoroutineStart.LAZY) {
        recordThrown(thrown) {
          titles.reconcileTitle(observation("a", "b", "c"))
        }
      }

    val completion = completionOf(caller)

    settle()
    log.beforeNextCommit { caller.cancel() }
    caller.start()

    withTimeout(TIMEOUT_MILLIS) { completion.join() }

    val events = settledEvents()

    val progress = titles.readProgress(titleId)

    assertTrue(thrown.get() is CancellationException)
    assertEquals(listOf(BEGIN, COMMIT, COMPLETED), events)
    assertEquals(3, progress?.chapters?.size)
  }

  /**
   * A reconciliation whose caller is cancelled while its transaction waits at
   * the transaction executor of Room never begins the transaction.
   *
   * The [TransactionGate] holds the transaction while the caller is cancelled
   * and completes. The held transaction then starts under its cancelled caller,
   * so it ends before BEGIN, and nothing persists.
   */
  @Test
  fun transactionQueuedForACancelledCallerNeverBegins(): Unit = runBlocking {
    reconcile("a", "b")

    val before = storage()

    val thrown = AtomicReference<Throwable?>()

    val caller =
      launch(start = CoroutineStart.LAZY) {
        recordThrown(thrown) {
          titles.reconcileTitle(observation("a", "b", "c"))
        }
      }

    val completion = completionOf(caller)

    settle()

    val queued = gate.holdNextTransaction()

    caller.start()

    val transaction = withTimeout(TIMEOUT_MILLIS) { queued.await() }

    caller.cancel()

    try {
      withTimeout(TIMEOUT_MILLIS) { completion.join() }
    } finally {
      withContext(Dispatchers.IO) { transaction.run() }
    }

    val events = settledEvents()

    assertTrue(thrown.get() is CancellationException)
    assertEquals(listOf(COMPLETED), events)
    assertEquals(before, storage())
  }

  /**
   * Runs [operation] for a caller that is cancelled after the commit, and
   * returns what [operation] threw.
   *
   * The caller runs on a [QueuedDispatcher]. Its first task enters Room, which
   * runs the transaction on its own threads and queues the next task, the
   * resumption of the caller, only after END TRANSACTION. The caller is
   * cancelled before that task runs, so the task resumes it with the
   * cancellation instead of the result. The task runs even when a check fails,
   * because a caller that never resumes could not complete.
   */
  private suspend fun cancelAfterCommit(
    operation: suspend () -> Unit
  ): Throwable? {
    settle()

    val dispatcher = QueuedDispatcher()

    val thrown = AtomicReference<Throwable?>()

    coroutineScope {
      val caller = launch(dispatcher) { recordThrown(thrown, operation) }

      try {
        dispatcher.next().run()

        val resumption = dispatcher.next()

        try {
          assertEquals(listOf(BEGIN, COMMIT), log.recorded())

          caller.cancel()
        } finally {
          resumption.run()
        }
      } finally {
        dispatcher.release()
      }

      withTimeout(TIMEOUT_MILLIS) { caller.join() }
    }

    return thrown.get()
  }

  /**
   * Runs [operation], records in [thrown] what it throws, and rethrows that
   * exception.
   */
  private suspend fun recordThrown(
    thrown: AtomicReference<Throwable?>,
    operation: suspend () -> Unit,
  ) {
    try {
      operation()
    } catch (failure: Throwable) {
      thrown.set(failure)

      throw failure
    }
  }

  /**
   * Records the completion of [job] after the transaction statements executed
   * before it, and returns a job that completes after that record.
   */
  private fun completionOf(job: Job): Job {
    val completion = Job()

    job.invokeOnCompletion {
      log.record(COMPLETED)
      completion.complete()
    }

    return completion
  }

  /**
   * Waits until Room has finished the work of earlier writes, including their
   * refreshes, then forgets the events recorded so far.
   */
  private suspend fun settle() {
    gate.awaitIdle()
    log.clear()
  }

  /**
   * Waits until Room has finished the work it started, including the refresh
   * that a cancelled write requests, then returns the recorded events.
   */
  private suspend fun settledEvents(): List<String> {
    gate.awaitIdle()

    return log.recorded()
  }

  private suspend fun reconcile(vararg chapters: String): ProgressSnapshot =
    titles.reconcileTitle(observation(*chapters))

  /** The observation of one title with [chapters] in canonical order. */
  private fun observation(vararg chapters: String): AcceptedTitleObservation {
    val alias = SourceTitleAlias.of("source", "title")

    val accepted = chapters.map { chapter ->
      AcceptedChapter(
        SourceChapterAlias(alias, TitleLocalChapterKey.of(chapter)),
        "Chapter $chapter",
      )
    }

    return AcceptedTitleObservation.of(alias, "Title", null, accepted)
  }

  /** Every stored row that reconciliation could change, as text. */
  private suspend fun storage(): List<List<String>> =
    STORAGE_QUERIES.flatMap { sql ->
      query(sql)
    }

  private suspend fun query(sql: String): List<List<String>> =
    database.useReaderConnection { connection ->
      connection.usePrepared(sql) { statement ->
        buildList {
          while (statement.step()) {
            add(statement.columns())
          }
        }
      }
    }

  private fun SQLiteStatement.columns(): List<String> =
    List(getColumnCount()) { column -> getText(column) }

  /**
   * A dispatcher that queues each task until the test takes it, so the test
   * decides when the caller of an operation resumes.
   */
  private class QueuedDispatcher : CoroutineDispatcher() {
    private val tasks = Channel<Runnable>(Channel.UNLIMITED)

    /** Returns the next queued task, waiting a bounded time for it. */
    suspend fun next(): Runnable =
      withTimeout(TIMEOUT_MILLIS) { tasks.receive() }

    /**
     * Ends the control of the test. The queued tasks and every later one run on
     * [Dispatchers.Default], so a caller that a failed test no longer drives
     * still completes.
     */
    fun release() {
      tasks.close()

      while (true) {
        val task = tasks.tryReceive().getOrNull() ?: return

        Dispatchers.Default.dispatch(EmptyCoroutineContext, task)
      }
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
      if (tasks.trySend(block).isFailure) {
        Dispatchers.Default.dispatch(context, block)
      }
    }
  }

  /**
   * A driver that opens the connections of [AndroidSQLiteDriver] and records
   * the transaction statements of each operation after SQLite executes them.
   *
   * Room begins, commits, and rolls back each transaction with one statement on
   * its connection. A test records other events between them. The framework
   * binds a transaction to the thread that began it, so every statement of a
   * transaction runs on that thread, and the log tags each statement with the
   * open transaction of its thread. An insert inside an operation runs in a
   * transaction that Room nests in the transaction of the operation, so the log
   * records only the statements that begin and end the outermost transaction.
   *
   * The invalidation tracker of Room also runs transactions of its own. A
   * refresh, including the one that RoomTitles requests after a cancelled
   * write, and a trigger sync that starts tracking a table name its
   * modification log, so the log leaves their events out. A sync that only
   * stops tracking drops triggers without naming the log. No test removes an
   * observer inside its assertion window, so none records such a sync.
   */
  private class TransactionLog : SQLiteDriver {
    private val driver = AndroidSQLiteDriver()

    private val events = ArrayList<Event>()

    /** The open transaction of the current thread. */
    private val transaction = ThreadLocal<Transaction>()

    private val beforeCommit = AtomicReference<(() -> Unit)?>()

    override val hasConnectionPool: Boolean
      get() = driver.hasConnectionPool

    override fun open(fileName: String): SQLiteConnection =
      LoggedConnection(driver.open(fileName))

    /** Records [event] after every event recorded so far. */
    fun record(event: String) {
      append(event, null)
    }

    /**
     * Returns the events recorded since the last [clear], without those of the
     * transactions of the invalidation tracker.
     */
    fun recorded(): List<String> =
      synchronized(events) {
        events
          .filterNot { event -> event.transaction?.isTracker == true }
          .map(Event::text)
      }

    /** Forgets the events recorded so far. */
    fun clear() {
      synchronized(events) { events.clear() }
    }

    /**
     * Runs [action] once, just before the next commit statement of an outermost
     * transaction executes. The transaction has then passed its last
     * cancellation check.
     */
    fun beforeNextCommit(action: () -> Unit) {
      check(beforeCommit.compareAndSet(null, action))
    }

    /**
     * Records [sql], a transaction statement that the current thread executed,
     * with the transaction that it begins or ends, when it begins or ends the
     * outermost transaction of the thread.
     */
    private fun recordStatement(sql: String) {
      val open: Transaction? = transaction.get()

      if (sql.startsWith(BEGIN_KEYWORD)) {
        if (open == null) {
          val begun = Transaction()

          transaction.set(begun)
          append(sql, begun)
        } else {
          open.depth++
        }
      } else if (open == null) {
        append(sql, null)
      } else if (--open.depth == 0) {
        transaction.remove()
        append(sql, open)
      }
    }

    private fun append(text: String, owner: Transaction?) {
      synchronized(events) { events += Event(text, owner) }
    }

    /**
     * Marks the open transaction of the current thread, if any, as one of the
     * invalidation tracker.
     */
    private fun markTrackerTransaction() {
      val owner = transaction.get() ?: return

      synchronized(events) { owner.isTracker = true }
    }

    /** A transaction of one thread, from its begin to its end. */
    private class Transaction {
      /** Whether the invalidation tracker runs it. Guarded by the events. */
      var isTracker = false

      /** The open transactions of its thread, itself included. */
      var depth = 1
    }

    /** A recorded event, and the transaction of the statement it records. */
    private class Event(val text: String, val transaction: Transaction?)

    private inner class LoggedConnection(
      private val connection: SQLiteConnection
    ) : SQLiteConnection {
      override fun inTransaction(): Boolean = connection.inTransaction()

      override fun prepare(sql: String): SQLiteStatement {
        if (TRACKER_TABLE in sql) {
          markTrackerTransaction()
        }

        val statement = connection.prepare(sql)

        val isLogged = LOGGED_KEYWORDS.any(sql::startsWith)

        return if (isLogged) LoggedStatement(sql, statement) else statement
      }

      override fun close() {
        connection.close()
      }
    }

    private inner class LoggedStatement(
      private val sql: String,
      private val statement: SQLiteStatement,
    ) : SQLiteStatement by statement {
      override fun step(): Boolean {
        if (sql == COMMIT && transaction.get()?.depth == 1) {
          beforeCommit.getAndSet(null)?.invoke()
        }

        val hasRow = statement.step()

        recordStatement(sql)

        return hasRow
      }
    }
  }

  private companion object {
    const val TIMEOUT_MILLIS = 10_000L
    const val BEGIN = "BEGIN IMMEDIATE TRANSACTION"
    const val COMMIT = "END TRANSACTION"
    const val ROLLBACK = "ROLLBACK TRANSACTION"
    const val COMPLETED = "caller completed"
    const val BEGIN_KEYWORD = "BEGIN"

    /** The keywords that start the transaction statements of Room. */
    val LOGGED_KEYWORDS = listOf(BEGIN_KEYWORD, "END", "ROLLBACK")

    /** The modification log that the invalidation tracker of Room keeps. */
    const val TRACKER_TABLE = "room_table_modification_log"

    val STORAGE_QUERIES =
      listOf(
        "SELECT storage_id, hex(id), source_identity, source_title_key, " +
          "display_name, quote(description) FROM titles ORDER BY storage_id",
        "SELECT storage_id, hex(id), title_storage_id, source_chapter_key, " +
          "display_name, quote(canonical_index) FROM chapters " +
          "ORDER BY storage_id",
      )
  }
}
