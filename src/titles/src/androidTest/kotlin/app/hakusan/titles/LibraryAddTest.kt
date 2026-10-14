package app.hakusan.titles

import android.database.sqlite.SQLiteException
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.room3.withWriteTransaction
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.hakusan.titles.storage.RoomTitles
import app.hakusan.titles.storage.TitlesDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Library Add on an in-memory Room database: Default creation, the category
 * policy, explicit selections, existing members, unknown titles, concurrency,
 * cancellation, rollback, and stored-state violations.
 */
@RunWith(AndroidJUnit4::class)
class LibraryAddTest {
  private lateinit var database: TitlesDatabase

  private lateinit var titles: Titles

  @Before
  fun openDatabase() {
    open(Room.inMemoryDatabaseBuilder<TitlesDatabase>())
  }

  @After
  fun closeDatabase() {
    database.close()
  }

  @Test
  fun firstAddCreatesDefaultAndAssignsTheTitle(): Unit = runBlocking {
    val titleId = reconcile("first")

    val result = titles.addToLibrary(titleId)

    val membership = (result as LibraryAddResult.Success).membership

    assertEquals(titleId, membership.titleId)
    assertEquals(listOf(listOf("1", "Default")), categories())
    assertEquals(setOf(1L), membership.categoryIds.map { it.key }.toSet())
    assertEquals(listOf(listOf("1", "1")), associations())
  }

  @Test
  fun failedAssociationInsertLeavesNeitherDefaultNorAnAssociation(): Unit =
    runBlocking {
      val titleId = reconcile("first")

      val before = storage()

      execute(
        "CREATE TRIGGER fail_association BEFORE INSERT ON title_categories " +
          "BEGIN SELECT RAISE(ABORT, 'injected failure'); END"
      )

      assertThrows(SQLiteException::class.java) {
        runBlocking { titles.addToLibrary(titleId) }
      }

      assertEquals(before, storage())
      assertEquals(emptyList<List<String>>(), categories())
    }

  @Test
  fun oneCategoryIsAssignedWhateverItsName(): Unit = runBlocking {
    val titleId = reconcile("first")

    execute("INSERT INTO categories (id, name) VALUES (7, 'Favorites')")

    val result = titles.addToLibrary(titleId)

    val membership = (result as LibraryAddResult.Success).membership

    assertEquals(setOf(7L), membership.categoryIds.map { it.key }.toSet())
    assertEquals(listOf(listOf("7", "Favorites")), categories())
    assertEquals(listOf(listOf("1", "7")), associations())
  }

  @Test
  fun severalCategoriesReturnTheSelectionAndChangeNothing(): Unit =
    runBlocking {
      val titleId = reconcile("first")

      seedCategories("Default", "Default", "Later")

      val before = storage()

      val result = titles.addToLibrary(titleId)

      val categories =
        (result as LibraryAddResult.CategorySelectionRequired).categories

      assertEquals(
        setOf(1L to "Default", 2L to "Default", 3L to "Later"),
        categories.map { category -> category.id.key to category.name }.toSet(),
      )

      assertEquals(before, storage())
    }

  @Test
  fun explicitAddIntoSeveralCategoriesMakesOneMember(): Unit = runBlocking {
    val titleId = reconcile("first")

    seedCategories("A", "B", "C")

    val selection = select(titleId, "A", "C")

    val result = titles.addToLibrary(titleId, selection)

    val membership = (result as ExplicitLibraryAddResult.Success).membership

    val snapshot = titles.reconcileTitle(observation("first"))

    assertEquals(
      LibraryMembership.of(titleId, selection.categoryIds),
      membership,
    )

    assertEquals(listOf(listOf("1", "1"), listOf("1", "3")), associations())
    assertEquals(1L, count("titles"))
    assertEquals(2L, count("chapters"))
    assertEquals(titleId, snapshot.titleId)
    assertTrue(snapshot.isInLibrary)
    assertEquals(2, snapshot.chapters.size)
  }

  @Test
  fun explicitSelectionWithAVanishedCategoryRejectsTheWholeAdd(): Unit =
    runBlocking {
      val titleId = reconcile("first")

      seedCategories("A", "B", "C")

      val selection = select(titleId, "A", "B", "C")

      execute("DELETE FROM categories WHERE name = 'B'")

      val before = storage()

      val result = titles.addToLibrary(titleId, selection)

      val missing =
        (result as ExplicitLibraryAddResult.CategoriesNotFound).categoryIds

      assertEquals(setOf(2L), missing.map { it.key }.toSet())
      assertEquals(before, storage())
      assertEquals(emptyList<List<String>>(), associations())
    }

  @Test
  fun existingMemberKeepsItsMembershipInBothForms(): Unit = runBlocking {
    val titleId = reconcile("first")

    seedCategories("A", "B")

    val first = select(titleId, "A")

    val second = select(titleId, "B")

    val added = titles.addToLibrary(titleId, first)

    val membership = (added as ExplicitLibraryAddResult.Success).membership

    val before = storage()

    val automatic = titles.addToLibrary(titleId)

    val reselected = titles.addToLibrary(titleId, second)

    assertEquals(LibraryAddResult.Success(membership), automatic)
    assertEquals(ExplicitLibraryAddResult.Success(membership), reselected)
    assertEquals(before, storage())

    execute("DELETE FROM categories WHERE name = 'B'")

    val vanished = titles.addToLibrary(titleId, second)

    assertEquals(ExplicitLibraryAddResult.Success(membership), vanished)
    assertEquals(listOf(listOf("1", "1")), associations())
  }

  @Test
  fun unknownTitleIsNotFoundInBothForms(): Unit = runBlocking {
    reconcile("first")

    seedCategories("A")

    val before = storage()

    val unknown = TitleId.generate()

    val selection =
      LibraryCategorySelection.of(listOf(checkNotNull(CategoryId.ofOrNull(1))))

    assertEquals(LibraryAddResult.TitleNotFound, titles.addToLibrary(unknown))

    assertEquals(
      ExplicitLibraryAddResult.TitleNotFound,
      titles.addToLibrary(unknown, selection),
    )

    assertEquals(before, storage())
  }

  @Test
  fun concurrentFirstAddsOfTwoTitlesShareOneDefault(): Unit = runBlocking {
    val titleIds = listOf(reconcile("first"), reconcile("second"))

    val gate = CompletableDeferred<Unit>()

    val results =
      withTimeout(TIMEOUT_MILLIS) {
        val pending = titleIds.map { titleId ->
          async(Dispatchers.Default) {
            gate.await()

            titles.addToLibrary(titleId)
          }
        }

        gate.complete(Unit)

        pending.awaitAll()
      }

    val memberships = results.map { result ->
      (result as LibraryAddResult.Success).membership
    }

    assertEquals(titleIds, memberships.map(LibraryMembership::titleId))

    assertEquals(
      1,
      memberships.map(LibraryMembership::categoryIds).toSet().size,
    )

    assertEquals(listOf(listOf("1", "Default")), categories())
    assertEquals(listOf(listOf("1", "1"), listOf("2", "1")), associations())
  }

  /**
   * Cancels an Add whose write transaction is queued behind another open write
   * transaction.
   *
   * Room hands each write transaction to its query dispatcher as a runnable.
   * The [TransactionGate] holds the runnable of the Add, so the test cancels
   * the Add exactly while its transaction waits there. The held transaction
   * then runs on another thread, before or after the writer commits, and the
   * test awaits it. Room runs a transaction as a child of its caller, so the
   * cancelled Add must never begin its transaction or produce a result.
   */
  @Test
  fun addCancelledWhileQueuedChangesNothing(): Unit = runBlocking {
    val gate = TransactionGate()

    database.close()

    open(
      Room.inMemoryDatabaseBuilder<TitlesDatabase>()
        .setQueryCoroutineContext(gate)
    )

    val titleId = reconcile("first")

    val before = storage()

    val entered = CompletableDeferred<Unit>()

    val release = CompletableDeferred<Unit>()

    val result = CompletableDeferred<LibraryAddResult>()

    withTimeout(TIMEOUT_MILLIS) {
      val writer = launch {
        database.withWriteTransaction {
          entered.complete(Unit)
          release.await()
        }
      }

      entered.await()

      val queued = gate.holdNextTransaction()

      val add = launch { result.complete(titles.addToLibrary(titleId)) }

      val transaction = queued.await()

      add.cancel()
      add.join()

      val held = async(Dispatchers.IO) { transaction.run() }

      release.complete(Unit)
      held.await()
      writer.join()
    }

    assertFalse(result.isCompleted)
    assertEquals(before, storage())
  }

  @Test
  fun contradictoryCategoryRowRaisesAndChangesNothing(): Unit = runBlocking {
    val titleId = reconcile("first")

    execute("INSERT INTO categories (id, name) VALUES (0, 'Zero')")
    execute("INSERT INTO categories (id, name) VALUES (1, 'One')")

    val before = storage()

    val automatic =
      assertThrows(StoredStateViolationException::class.java) {
        runBlocking { titles.addToLibrary(titleId) }
      }

    val explicit =
      assertThrows(StoredStateViolationException::class.java) {
        runBlocking { titles.addToLibrary(titleId, oneSelection()) }
      }

    assertEquals(StoredStateViolationKind.STORAGE_KEY, automatic.kind)
    assertEquals(StoredStateViolationKind.STORAGE_KEY, explicit.kind)
    assertEquals(before, storage())
  }

  @Test
  fun contradictoryAssociationRowRaisesAndChangesNothing(): Unit = runBlocking {
    val titleId = reconcile("first")

    execute("INSERT INTO categories (id, name) VALUES (0, 'Zero')")

    execute(
      "INSERT INTO title_categories (title_storage_id, category_id) " +
        "SELECT storage_id, 0 FROM titles"
    )

    val before = storage()

    val automatic =
      assertThrows(StoredStateViolationException::class.java) {
        runBlocking { titles.addToLibrary(titleId) }
      }

    val explicit =
      assertThrows(StoredStateViolationException::class.java) {
        runBlocking { titles.addToLibrary(titleId, oneSelection()) }
      }

    assertEquals(StoredStateViolationKind.STORAGE_KEY, automatic.kind)
    assertEquals(StoredStateViolationKind.STORAGE_KEY, explicit.kind)
    assertEquals(before, storage())
  }

  @Test
  fun resumePositionOutsideTheLibraryRaisesAndRollsBackDefault(): Unit =
    runBlocking {
      val titleId = reconcile("first")

      execute(
        "INSERT INTO library_resume_positions (title_storage_id, " +
          "chapter_storage_id, unit_kind, unit_index, updated_at) " +
          "SELECT title_storage_id, storage_id, 'PAGE', 2, 0 FROM chapters " +
          "WHERE source_chapter_key = 'b'"
      )

      val before = storage()

      val violation =
        assertThrows(StoredStateViolationException::class.java) {
          runBlocking { titles.addToLibrary(titleId) }
        }

      assertEquals(StoredStateViolationKind.RESUME_STATE, violation.kind)
      assertEquals(before, storage())
      assertEquals(emptyList<List<String>>(), categories())
    }

  /** Opens the database that [builder] configures and its [Titles]. */
  private fun open(builder: RoomDatabase.Builder<TitlesDatabase>) {
    database = builder.setDriver(AndroidSQLiteDriver()).build()
    titles = RoomTitles(database)
  }

  /** Reconciles the title [key] with two chapters and returns its identity. */
  private suspend fun reconcile(key: String): TitleId =
    titles.reconcileTitle(observation(key)).titleId

  private fun observation(key: String): AcceptedTitleObservation {
    val alias = SourceTitleAlias.of("source", key)

    val chapters =
      listOf("a", "b").map { chapter ->
        AcceptedChapter(
          SourceChapterAlias(alias, TitleLocalChapterKey.of(chapter)),
          "Chapter $chapter",
        )
      }

    return AcceptedTitleObservation.of(alias, "Title", null, chapters)
  }

  /** Inserts one category per name, with identities 1, 2, and so on. */
  private suspend fun seedCategories(vararg names: String) {
    names.forEachIndexed { index, name ->
      execute(
        "INSERT INTO categories (id, name) VALUES (${index + 1}, '$name')"
      )
    }
  }

  /**
   * Returns the selection of the categories [names], whose identities the
   * automatic Add of [titleId] reports, as a caller would obtain them.
   */
  private suspend fun select(
    titleId: TitleId,
    vararg names: String,
  ): LibraryCategorySelection {
    val result = titles.addToLibrary(titleId)

    val categories =
      (result as LibraryAddResult.CategorySelectionRequired).categories

    val selected = categories.filter { category -> category.name in names }

    assertEquals(names.size, selected.size)

    return LibraryCategorySelection.of(selected.map(LibraryCategory::id))
  }

  private fun oneSelection(): LibraryCategorySelection =
    LibraryCategorySelection.of(listOf(checkNotNull(CategoryId.ofOrNull(1))))

  private suspend fun categories(): List<List<String>> =
    query("SELECT id, name FROM categories ORDER BY id")

  private suspend fun associations(): List<List<String>> =
    query(
      "SELECT title_storage_id, category_id FROM title_categories " +
        "ORDER BY 1, 2"
    )

  private suspend fun count(table: String): Long =
    query("SELECT count(*) FROM $table").single().single().toLong()

  /** Every stored row that Add could change, as text. */
  private suspend fun storage(): List<List<String>> =
    STORAGE_QUERIES.flatMap { sql ->
      query(sql)
    }

  private suspend fun execute(sql: String) {
    database.useWriterConnection { connection ->
      connection.usePrepared(sql) { statement -> statement.step() }
    }
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

  private companion object {
    const val TIMEOUT_MILLIS = 10_000L

    val STORAGE_QUERIES =
      listOf(
        "SELECT storage_id, hex(id), source_identity, source_title_key, " +
          "display_name, quote(description) FROM titles ORDER BY storage_id",
        "SELECT storage_id, hex(id), title_storage_id, source_chapter_key, " +
          "display_name, quote(canonical_index) FROM chapters " +
          "ORDER BY storage_id",
        "SELECT chapter_storage_id FROM read_chapters ORDER BY 1",
        "SELECT title_storage_id, chapter_storage_id, unit_kind, unit_index, " +
          "updated_at FROM library_resume_positions ORDER BY 1",
        "SELECT title_storage_id, category_id FROM title_categories " +
          "ORDER BY 1, 2",
        "SELECT id, name FROM categories ORDER BY id",
      )
  }
}
