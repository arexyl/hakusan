package app.hakusan.titles.storage

import android.database.sqlite.SQLiteException
import app.hakusan.titles.CategoryId
import app.hakusan.titles.ExplicitLibraryAddFailure
import app.hakusan.titles.ExplicitLibraryAddResult
import app.hakusan.titles.LibraryAddResult
import app.hakusan.titles.LibraryCategorySelection
import app.hakusan.titles.ReconcileSourceTitle
import app.hakusan.titles.SourceTitleAlias
import app.hakusan.titles.TitleId
import app.hakusan.titles.Titles
import androidx.room3.Room
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.room3.withWriteTransaction
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TitlesDatabaseAndroidTest {
  private lateinit var database: TitlesDatabase
  private lateinit var dao: TitlesDao
  private lateinit var titles: Titles

  @Before
  fun openDatabase() {
    database = Room.inMemoryDatabaseBuilder<TitlesDatabase>()
      .setDriver(AndroidSQLiteDriver())
      .build()
    dao = database.titlesDao()
    titles = database.asTitles()
  }

  @After
  fun closeDatabase() {
    database.close()
  }

  @Test
  fun reconciliationUsesExactAliasAndPreservesIdentity(): Unit = runBlocking {
    val input = title(
      source = " source/α ",
      key = " title/key ",
      displayName = "Title",
      description = "Description",
    )

    val firstId = titles.reconcileSourceTitle(input)
    val repeatedId = titles.reconcileSourceTitle(
      input.copy(
        displayName = "Renamed",
        description = null,
      ),
    )
    val otherSourceId = titles.reconcileSourceTitle(
      title(
        source = "other-source",
        key = " title/key ",
        displayName = "Renamed",
        description = null,
      ),
    )

    assertEquals(firstId, repeatedId)
    assertNotEquals(firstId, otherSourceId)
    assertEquals(7, firstId.value.version())
    assertEquals(2, firstId.value.variant())
    assertEquals(2, queryLong("SELECT COUNT(*) FROM titles"))

    val stored = dao.findTitleByAlias(
      sourceIdentity = " source/α ",
      sourceTitleKey = " title/key ",
    )
    assertEquals(firstId.value, stored?.id)
    assertEquals("Renamed", stored?.displayName)
    assertEquals(null, stored?.description)
  }

  @Test
  fun reconciliationRetriesAnIndependentUuidCollision(): Unit = runBlocking {
    val firstTitles = database.asTitles { FIRST_ID }
    val firstId = firstTitles.reconcileSourceTitle(
      title(source = "first", key = "title"),
    )
    val candidates = ArrayDeque(listOf(FIRST_ID, SECOND_ID))
    val secondTitles = database.asTitles(candidates::removeFirst)

    val secondId = secondTitles.reconcileSourceTitle(
      title(source = "second", key = "title"),
    )

    assertEquals(TitleId(FIRST_ID), firstId)
    assertEquals(TitleId(SECOND_ID), secondId)
    assertTrue(candidates.isEmpty())
    assertEquals(2, queryLong("SELECT COUNT(*) FROM titles"))
  }

  @Test
  fun concurrentReconciliationConvergesOnOneIdentity(): Unit = runBlocking {
    withTimeout(TEST_TIMEOUT_MILLIS) {
      val start = CompletableDeferred<Unit>()
      val input = title("source", "title")

      val ids = coroutineScope {
        val pending = List(16) {
          async {
            start.await()
            titles.reconcileSourceTitle(input)
          }
        }
        start.complete(Unit)
        pending.awaitAll()
      }

      assertEquals(1, ids.toSet().size)
      assertEquals(1, queryLong("SELECT COUNT(*) FROM titles"))
    }
  }

  @Test
  fun exhaustedUuidCollisionsLeaveTheNewAliasAbsent() {
    val collisionIds = (1..UUID_ATTEMPT_COUNT).map(::uuid)
    runBlocking {
      collisionIds.forEachIndexed { index, id ->
        database.asTitles { id }.reconcileSourceTitle(
          title("seed-$index", "title"),
        )
      }
    }
    val candidates = ArrayDeque(collisionIds)
    val collidingTitles = database.asTitles(candidates::removeFirst)

    assertThrows(IllegalStateException::class.java) {
      runBlocking {
        collidingTitles.reconcileSourceTitle(title("new", "title"))
      }
    }

    runBlocking {
      assertTrue(candidates.isEmpty())
      assertEquals(
        UUID_ATTEMPT_COUNT.toLong(),
        queryLong("SELECT COUNT(*) FROM titles"),
      )
      assertEquals(
        null,
        dao.findTitleByAlias("new", "title"),
      )
    }
  }

  @Test
  fun firstAddCommitsDefaultMembershipAndObservableMetadata(): Unit =
    runBlocking {
      val id = titles.reconcileSourceTitle(
        title(
          source = "source",
          key = "title",
          displayName = "Initial",
          description = "Description",
        ),
      )
      val observedAdd = async(start = CoroutineStart.UNDISPATCHED) {
        withTimeout(TEST_TIMEOUT_MILLIS) {
          titles.observeLibrary().first {
            id in it.titlesById
          }
        }
      }

      val firstResult = titles.addToLibrary(id)
      val addedState = observedAdd.await()
      val firstSuccess = firstResult as LibraryAddResult.Success
      val defaultCategory = addedState.shelves.single().category

      assertEquals(
        setOf(defaultCategory.id),
        firstSuccess.membership.categoryIds,
      )
      assertEquals("Default", defaultCategory.name)
      assertEquals(1, addedState.shelves.single().titleCount)
      assertEquals(
        SourceTitleAlias("source", "title"),
        addedState.titlesById.getValue(id).alias,
      )

      val repeatedResult = titles.addToLibrary(
        titleId = id,
        selection = LibraryCategorySelection.of(
          listOf(CategoryId(999)),
        ),
      )

      assertEquals(
        firstSuccess.membership,
        (repeatedResult as ExplicitLibraryAddResult.Success).membership,
      )
      assertEquals(1, queryLong("SELECT COUNT(*) FROM categories"))
      assertEquals(1, queryLong("SELECT COUNT(*) FROM title_categories"))

      val observedUpdate = async(start = CoroutineStart.UNDISPATCHED) {
        withTimeout(TEST_TIMEOUT_MILLIS) {
          titles.observeLibrary().first {
            it.titlesById[id]?.displayName == "Updated"
          }
        }
      }
      titles.reconcileSourceTitle(
        title(
          source = "source",
          key = "title",
          displayName = "Updated",
          description = null,
        ),
      )

      val updatedTitle = observedUpdate.await().titlesById.getValue(id)
      assertEquals("Updated", updatedTitle.displayName)
      assertEquals(null, updatedTitle.description)
    }

  @Test
  fun automaticAddUsesTheOnlyExistingCategory(): Unit = runBlocking {
    val categoryId = CategoryId(
      dao.insertCategory(CategoryEntity(name = "Want to read")),
    )
    val id = titles.reconcileSourceTitle(title("source", "title"))

    val result = titles.addToLibrary(id) as LibraryAddResult.Success

    assertEquals(setOf(categoryId), result.membership.categoryIds)
    assertEquals(
      listOf("Want to read"),
      dao.loadCategories().map(CategoryEntity::name),
    )
  }

  @Test
  fun multipleCategoriesRequireAndValidateExplicitSelection(): Unit =
    runBlocking {
      val firstCategoryId = CategoryId(
        dao.insertCategory(CategoryEntity(name = "Want to read")),
      )
      val secondCategoryId = CategoryId(
        dao.insertCategory(CategoryEntity(name = "Want to read")),
      )
      val emptyState = titles.observeLibrary().first()
      assertTrue(emptyState.titlesById.isEmpty())
      assertEquals(
        setOf(firstCategoryId, secondCategoryId),
        emptyState.shelves.mapTo(HashSet()) { it.category.id },
      )
      assertTrue(emptyState.shelves.all { it.titleIds.isEmpty() })
      val firstId = titles.reconcileSourceTitle(
        title("first-source", "title", displayName = "Same name"),
      )

      val automatic = titles.addToLibrary(firstId)
        as LibraryAddResult.CategorySelectionRequired
      assertEquals(
        setOf(firstCategoryId, secondCategoryId),
        automatic.categories.mapTo(HashSet()) { it.id },
      )
      assertEquals(0, queryLong("SELECT COUNT(*) FROM title_categories"))

      val missing = titles.addToLibrary(
        titleId = firstId,
        selection = LibraryCategorySelection.of(
          listOf(firstCategoryId, CategoryId(999)),
        ),
      ) as ExplicitLibraryAddResult.Failure
      val missingError = missing.error
        as ExplicitLibraryAddFailure.CategoriesNotFound
      assertEquals(setOf(CategoryId(999)), missingError.categoryIds)
      assertEquals(0, queryLong("SELECT COUNT(*) FROM title_categories"))

      val selection = LibraryCategorySelection.of(
        listOf(firstCategoryId, secondCategoryId),
      )
      titles.addToLibrary(firstId, selection)
      val secondId = titles.reconcileSourceTitle(
        title("second-source", "title", displayName = "Same name"),
      )
      titles.addToLibrary(secondId, selection)

      val state = titles.observeLibrary().first()
      assertEquals(2, state.titlesById.size)
      assertEquals(2, state.shelves.size)
      assertTrue(state.shelves.all { it.titleCount == 2 })
      assertTrue(state.shelves.all { it.titleIds == setOf(firstId, secondId) })
      assertEquals(2, queryLong("SELECT COUNT(*) FROM titles"))
      assertEquals(4, queryLong("SELECT COUNT(*) FROM title_categories"))
      assertEquals(0, queryLong("SELECT COUNT(*) FROM read_chapters"))
      assertEquals(
        0,
        queryLong("SELECT COUNT(*) FROM library_resume_positions"),
      )
    }

  @Test
  fun concurrentFirstAddsShareOneDefaultCategory(): Unit = runBlocking {
    withTimeout(TEST_TIMEOUT_MILLIS) {
      val firstId = titles.reconcileSourceTitle(title("source", "first"))
      val secondId = titles.reconcileSourceTitle(title("source", "second"))

      val results = coroutineScope {
        listOf(
          async { titles.addToLibrary(firstId) },
          async { titles.addToLibrary(secondId) },
        ).awaitAll()
      }

      assertTrue(results.all { it is LibraryAddResult.Success })
      assertEquals(listOf("Default"), dao.loadCategories().map { it.name })
      assertEquals(2, queryLong("SELECT COUNT(*) FROM title_categories"))
      assertEquals(2, titles.observeLibrary().first()
        .shelves.single().titleCount)
    }
  }

  @Test
  fun failedDefaultAssociationRollsBackTheWholeAdd() {
    val id = runBlocking {
      titles.reconcileSourceTitle(title("source", "title"))
    }
    runBlocking {
      database.useWriterConnection { connection ->
        connection.usePrepared(
          """
          CREATE TRIGGER fail_title_category_insert
          BEFORE INSERT ON title_categories
          BEGIN
            SELECT RAISE(ABORT, 'injected title-category failure');
          END
          """.trimIndent(),
        ) { statement ->
          statement.step()
        }
      }
    }

    assertThrows(SQLiteException::class.java) {
      runBlocking {
        titles.addToLibrary(id)
      }
    }

    runBlocking {
      assertEquals(1, queryLong("SELECT COUNT(*) FROM titles"))
      assertEquals(0, queryLong("SELECT COUNT(*) FROM categories"))
      assertEquals(0, queryLong("SELECT COUNT(*) FROM title_categories"))
      val state = titles.observeLibrary().first()
      assertTrue(state.titlesById.isEmpty())
      assertTrue(state.shelves.isEmpty())
    }
  }

  @Test
  fun unknownTitleDoesNotInitializeTheLibrary(): Unit = runBlocking {
    val titleId = TitleId(FIRST_ID)
    val automatic = titles.addToLibrary(titleId)
    val explicit = titles.addToLibrary(
      titleId = titleId,
      selection = LibraryCategorySelection.of(listOf(CategoryId(1))),
    )

    assertEquals(LibraryAddResult.TitleNotFound, automatic)
    assertEquals(
      ExplicitLibraryAddFailure.TitleNotFound,
      (explicit as ExplicitLibraryAddResult.Failure).error,
    )
    assertEquals(0, queryLong("SELECT COUNT(*) FROM categories"))
  }

  @Test
  fun canceledQueuedAddDoesNotMutateMembership(): Unit = runBlocking {
    withTimeout(TEST_TIMEOUT_MILLIS) {
      val id = titles.reconcileSourceTitle(title("source", "title"))
      val writerEntered = CompletableDeferred<Unit>()
      val releaseWriter = CompletableDeferred<Unit>()
      val writer = launch {
        database.withWriteTransaction {
          writerEntered.complete(Unit)
          releaseWriter.await()
        }
      }
      writerEntered.await()

      val add = launch(start = CoroutineStart.UNDISPATCHED) {
        titles.addToLibrary(id)
      }
      add.cancel()
      releaseWriter.complete(Unit)
      add.join()
      writer.join()

      assertTrue(add.isCancelled)
      assertEquals(0, queryLong("SELECT COUNT(*) FROM categories"))
      assertEquals(0, queryLong("SELECT COUNT(*) FROM title_categories"))
    }
  }

  private suspend fun queryLong(sql: String): Long =
    database.useReaderConnection { connection ->
      connection.usePrepared(sql) { statement ->
        check(statement.step()) {
          "Count query returned no row."
        }
        statement.getLong(0)
      }
    }

  private fun title(
    source: String,
    key: String,
    displayName: String = "Title",
    description: String? = null,
  ): ReconcileSourceTitle = ReconcileSourceTitle(
    alias = SourceTitleAlias(source, key),
    displayName = displayName,
    description = description,
  )

  private fun uuid(index: Int): UUID = UUID.fromString(
    "00000000-0000-7000-8000-${index.toString(16).padStart(12, '0')}",
  )

  private companion object {
    const val TEST_TIMEOUT_MILLIS = 5_000L
    const val UUID_ATTEMPT_COUNT = 16
    val FIRST_ID: UUID =
      UUID.fromString("00000000-0000-7000-8000-000000000001")
    val SECOND_ID: UUID =
      UUID.fromString("00000000-0000-7000-8000-000000000002")
  }
}
