package app.hakusan.titles.storage

import app.hakusan.titles.ReconcileSourceTitle
import app.hakusan.titles.SourceTitleAlias
import app.hakusan.titles.TitleId
import app.hakusan.titles.Titles
import androidx.room3.Room
import androidx.room3.useReaderConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
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
    const val UUID_ATTEMPT_COUNT = 16
    val FIRST_ID: UUID =
      UUID.fromString("00000000-0000-7000-8000-000000000001")
    val SECOND_ID: UUID =
      UUID.fromString("00000000-0000-7000-8000-000000000002")
  }
}
