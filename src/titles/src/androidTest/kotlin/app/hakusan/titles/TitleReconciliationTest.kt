package app.hakusan.titles

import android.database.sqlite.SQLiteException
import androidx.room3.Room
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.hakusan.titles.storage.RoomTitles
import app.hakusan.titles.storage.TitlesDatabase
import java.util.ArrayDeque
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

/**
 * Whole-title reconciliation on an in-memory Room database: atomicity,
 * stored-state violations, identity and its collisions, concurrent
 * reconciliation, the canonical index, and the stored UUID layout.
 */
@RunWith(AndroidJUnit4::class)
class TitleReconciliationTest {
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
  fun exactAliasKeepsItsIdentityAndTakesTheNewDetails(): Unit = runBlocking {
    val first = titles.reconcileTitle(observation(chapters = listOf("a", "b")))

    val repeated =
      titles.reconcileTitle(observation(chapters = listOf("a", "b")))

    val renamed =
      titles.reconcileTitle(
        observation(
          chapters = listOf("a", "b"),
          displayName = "Renamed",
          description = null,
        )
      )

    assertEquals(first, repeated)
    assertEquals(first.titleId, renamed.titleId)
    assertEquals(chapterIds(first), chapterIds(renamed))
    assertEquals(listOf(listOf("Renamed", "NULL")), titleDetails())
  }

  @Test
  fun sameNamedWorksAndEqualLocalKeysStayDistinct(): Unit = runBlocking {
    val first =
      titles.reconcileTitle(
        observation(source = "first", chapters = listOf("1"))
      )

    val second =
      titles.reconcileTitle(
        observation(source = "second", chapters = listOf("1"))
      )

    assertNotEquals(first.titleId, second.titleId)
    assertNotEquals(chapterIds(first), chapterIds(second))
    assertEquals(2L, count("titles"))
    assertEquals(2L, count("chapters"))
  }

  @Test
  fun prependAndReorderPassTheUniqueCanonicalIndex(): Unit = runBlocking {
    val initial =
      titles.reconcileTitle(observation(chapters = listOf("a", "b", "c")))

    val prepended =
      titles.reconcileTitle(observation(chapters = listOf("z", "a", "b", "c")))

    val reordered =
      titles.reconcileTitle(observation(chapters = listOf("c", "z", "b", "a")))

    val ids = idsByKey(prepended)

    assertEquals(chapterIds(initial), chapterIds(prepended).drop(1))

    assertEquals(
      listOf("c", "z", "b", "a").map(ids::getValue),
      chapterIds(reordered),
    )

    assertEquals(
      listOf(
        listOf("a", "3"),
        listOf("b", "2"),
        listOf("c", "0"),
        listOf("z", "1"),
      ),
      canonicalIndexes(),
    )
  }

  @Test
  fun omittedChapterKeepsItsProgressAndReturnsWithItsIdentity(): Unit =
    runBlocking {
      val initial =
        titles.reconcileTitle(observation(chapters = listOf("a", "b", "c")))

      seedProgress(read = "a", resume = "b")

      val omitted =
        titles.reconcileTitle(observation(chapters = listOf("a", "c")))

      val target = omitted.resumeTarget

      assertTrue(omitted.isInLibrary)
      assertEquals(listOf(true, false), omitted.chapters.map { it.isRead })
      assertTrue(target is ResumeTarget.TemporarilyUnavailable)
      assertEquals(idsByKey(initial).getValue("b"), target?.chapter?.id)
      assertEquals(1L, count("read_chapters"))
      assertEquals(1L, count("library_resume_positions"))
      assertEquals(3L, count("chapters"))

      assertEquals(
        listOf(listOf("a", "0"), listOf("b", "NULL"), listOf("c", "1")),
        canonicalIndexes(),
      )

      val returned =
        titles.reconcileTitle(observation(chapters = listOf("a", "b", "c")))

      assertEquals(chapterIds(initial), chapterIds(returned))
      assertTrue(returned.resumeTarget is ResumeTarget.Available)

      assertEquals(
        listOf(true, false, false),
        returned.chapters.map { it.isRead },
      )
    }

  @Test
  fun injectedFailureLeavesStorageUnchanged(): Unit = runBlocking {
    titles.reconcileTitle(observation(chapters = listOf("a", "b", "c")))

    seedProgress(read = "a", resume = "b")

    val before = storage()

    execute(
      "CREATE TRIGGER fail_chapter BEFORE INSERT ON chapters " +
        "WHEN NEW.source_chapter_key = 'n' " +
        "BEGIN SELECT RAISE(ABORT, 'injected failure'); END"
    )

    val changed =
      observation(
        chapters = listOf("c", "a", "n"),
        displayName = "Renamed",
        description = null,
      )

    assertThrows(SQLiteException::class.java) {
      runBlocking { titles.reconcileTitle(changed) }
    }

    assertEquals(before, storage())
  }

  @Test
  fun injectedFailureLeavesANewTitleAbsent(): Unit = runBlocking {
    execute(
      "CREATE TRIGGER fail_chapter BEFORE INSERT ON chapters " +
        "WHEN NEW.source_chapter_key = 'b' " +
        "BEGIN SELECT RAISE(ABORT, 'injected failure'); END"
    )

    assertThrows(SQLiteException::class.java) {
      runBlocking {
        titles.reconcileTitle(observation(chapters = listOf("a", "b")))
      }
    }

    assertEquals(0L, count("titles"))
    assertEquals(0L, count("chapters"))
  }

  @Test
  fun contradictoryResumeStateRaisesAndLeavesStorageUnchanged(): Unit =
    runBlocking {
      titles.reconcileTitle(observation(chapters = listOf("a", "b", "c")))

      seedProgress(read = "b", resume = "b")

      val before = storage()

      val changed =
        observation(
          chapters = listOf("z", "b", "a"),
          displayName = "Renamed",
          description = null,
        )

      val violation =
        assertThrows(StoredStateViolationException::class.java) {
          runBlocking { titles.reconcileTitle(changed) }
        }

      assertEquals(StoredStateViolationKind.RESUME_STATE, violation.kind)
      assertEquals(before, storage())
    }

  @Test
  fun malformedCanonicalOrderRaisesBeforeAnyChapterWrite(): Unit = runBlocking {
    titles.reconcileTitle(observation(chapters = listOf("a", "b")))

    execute(
      "UPDATE chapters SET canonical_index = 5 " +
        "WHERE source_chapter_key = 'b'"
    )

    val before = storage()

    val changed =
      observation(
        chapters = listOf("a", "b", "c"),
        displayName = "Renamed",
        description = null,
      )

    val violation =
      assertThrows(StoredStateViolationException::class.java) {
        runBlocking { titles.reconcileTitle(changed) }
      }

    assertEquals(StoredStateViolationKind.CANONICAL_ORDER, violation.kind)
    assertEquals(before, storage())
  }

  @Test
  fun concurrentReconciliationsOfOneAliasConvergeOnOneIdentity(): Unit =
    runBlocking {
      val gate = CompletableDeferred<Unit>()

      val accepted = observation(chapters = listOf("a", "b"))

      val snapshots =
        withTimeout(TIMEOUT_MILLIS) {
          val pending =
            List(CONCURRENT_RECONCILIATIONS) {
              async(Dispatchers.Default) {
                gate.await()

                titles.reconcileTitle(accepted)
              }
            }

          gate.complete(Unit)

          pending.awaitAll()
        }

      assertEquals(1, snapshots.map(ProgressSnapshot::titleId).toSet().size)
      assertEquals(1, snapshots.toSet().size)
      assertEquals(1L, count("titles"))
      assertEquals(2L, count("chapters"))
    }

  @Test
  fun cancellationInsideTheTransactionLeavesStorageUnchanged(): Unit =
    runBlocking {
      titles.reconcileTitle(observation(chapters = listOf("a", "b")))

      val before = storage()

      var generated = 0

      lateinit var reconciliation: Job

      val cancelling =
        RoomTitles(
          database,
          newChapterId = {
            generated++
            reconciliation.cancel()
            ChapterId.generate()
          },
        )

      val changed =
        observation(chapters = listOf("z", "b", "a"), displayName = "Renamed")

      reconciliation = launch { cancelling.reconcileTitle(changed) }

      withTimeout(TIMEOUT_MILLIS) { reconciliation.join() }

      assertTrue(reconciliation.isCancelled)
      assertEquals(1, generated)
      assertEquals(before, storage())
    }

  @Test
  fun titleUuidCollisionRetriesWithoutReplacingAnotherTitle(): Unit =
    runBlocking {
      val first =
        RoomTitles(database, newTitleId = { KNOWN_TITLE })
          .reconcileTitle(observation(source = "first"))

      val candidates = ArrayDeque(listOf(KNOWN_TITLE, OTHER_TITLE))

      val second =
        RoomTitles(database, newTitleId = candidates::removeFirst)
          .reconcileTitle(observation(source = "second"))

      assertEquals(KNOWN_TITLE, first.titleId)
      assertEquals(OTHER_TITLE, second.titleId)
      assertTrue(candidates.isEmpty())
      assertEquals(listOf("first", "second"), sources())
    }

  @Test
  fun titleUuidCollisionExhaustionPersistsNothing(): Unit = runBlocking {
    RoomTitles(database, newTitleId = { KNOWN_TITLE })
      .reconcileTitle(observation(source = "first"))

    val before = storage()

    val generator = CollidingGenerator(KNOWN_TITLE)

    val colliding = RoomTitles(database, newTitleId = generator)

    val error =
      assertThrows(IllegalStateException::class.java) {
        runBlocking {
          withTimeout(TIMEOUT_MILLIS) {
            colliding.reconcileTitle(
              observation(source = "second", chapters = listOf("a"))
            )
          }
        }
      }

    assertEquals(IllegalStateException::class.java, error.javaClass)
    assertEquals(RoomTitles.MAX_IDENTITY_ATTEMPTS, generator.attempts)
    assertEquals(before, storage())
  }

  @Test
  fun chapterUuidCollisionRetriesWithoutReplacingAnotherChapter(): Unit =
    runBlocking {
      val first =
        RoomTitles(database, newChapterId = { KNOWN_CHAPTER })
          .reconcileTitle(observation(source = "first", chapters = listOf("a")))

      val candidates = ArrayDeque(listOf(KNOWN_CHAPTER, OTHER_CHAPTER))

      val second =
        RoomTitles(database, newChapterId = candidates::removeFirst)
          .reconcileTitle(
            observation(source = "second", chapters = listOf("a"))
          )

      assertEquals(listOf(KNOWN_CHAPTER), chapterIds(first))
      assertEquals(listOf(OTHER_CHAPTER), chapterIds(second))
      assertTrue(candidates.isEmpty())
      assertEquals(2L, count("chapters"))
    }

  @Test
  fun chapterUuidCollisionExhaustionLeavesStorageUnchanged(): Unit =
    runBlocking {
      RoomTitles(database, newChapterId = { KNOWN_CHAPTER })
        .reconcileTitle(observation(chapters = listOf("a")))

      val before = storage()

      val generator = CollidingGenerator(KNOWN_CHAPTER)

      val colliding = RoomTitles(database, newChapterId = generator)

      val changed =
        observation(chapters = listOf("z", "a"), displayName = "Renamed")

      val error =
        assertThrows(IllegalStateException::class.java) {
          runBlocking {
            withTimeout(TIMEOUT_MILLIS) { colliding.reconcileTitle(changed) }
          }
        }

      assertEquals(IllegalStateException::class.java, error.javaClass)
      assertEquals(RoomTitles.MAX_IDENTITY_ATTEMPTS, generator.attempts)
      assertEquals(before, storage())
    }

  @Test
  fun uuidColumnsHoldTheSixteenNetworkOrderBytes(): Unit = runBlocking {
    RoomTitles(
        database,
        newTitleId = { KNOWN_TITLE },
        newChapterId = { KNOWN_CHAPTER },
      )
      .reconcileTitle(observation(chapters = listOf("a")))

    assertEquals(
      listOf(listOf("blob", "16", KNOWN_TITLE_HEX)),
      query("SELECT typeof(id), length(id), hex(id) FROM titles"),
    )

    assertEquals(
      listOf(listOf("blob", "16", KNOWN_CHAPTER_HEX)),
      query("SELECT typeof(id), length(id), hex(id) FROM chapters"),
    )
  }

  private fun observation(
    source: String = "source",
    chapters: List<String> = emptyList(),
    displayName: String = "Title",
    description: String? = "About",
  ): AcceptedTitleObservation {
    val alias = SourceTitleAlias.of(source, "title")

    val accepted = chapters.map { key ->
      AcceptedChapter(
        SourceChapterAlias(alias, TitleLocalChapterKey.of(key)),
        "Chapter $key",
      )
    }

    return AcceptedTitleObservation.of(
      alias,
      displayName,
      description,
      accepted,
    )
  }

  private fun chapterIds(snapshot: ProgressSnapshot): List<ChapterId> =
    snapshot.chapters.map { state -> state.chapter.id }

  private fun idsByKey(snapshot: ProgressSnapshot): Map<String, ChapterId> =
    snapshot.chapters.associate { state ->
      state.chapter.localKey.text to state.chapter.id
    }

  /**
   * Adds the title to a category, marks [read] read, and resumes in [resume].
   */
  private suspend fun seedProgress(read: String, resume: String) {
    execute("INSERT INTO categories (name) VALUES ('Default')")

    execute(
      "INSERT INTO title_categories (title_storage_id, category_id) " +
        "SELECT storage_id, 1 FROM titles"
    )

    execute(
      "INSERT INTO read_chapters (chapter_storage_id) " +
        "SELECT storage_id FROM chapters WHERE source_chapter_key = '$read'"
    )

    execute(
      "INSERT INTO library_resume_positions (title_storage_id, " +
        "chapter_storage_id, unit_kind, unit_index, updated_at) " +
        "SELECT title_storage_id, storage_id, 'PAGE', 2, 0 FROM chapters " +
        "WHERE source_chapter_key = '$resume'"
    )
  }

  private suspend fun titleDetails(): List<List<String>> =
    query("SELECT display_name, quote(description) FROM titles")

  private suspend fun sources(): List<String> =
    query("SELECT source_identity FROM titles ORDER BY source_identity")
      .map(List<String>::single)

  private suspend fun canonicalIndexes(): List<List<String>> =
    query(
      "SELECT source_chapter_key, quote(canonical_index) FROM chapters " +
        "ORDER BY source_chapter_key"
    )

  private suspend fun count(table: String): Long =
    query("SELECT count(*) FROM $table").single().single().toLong()

  /** Every stored row that reconciliation could change, as text. */
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

  /**
   * Returns [id] on every call and counts the calls. A call past the attempt
   * bound fails the test, so an unbounded retry cannot hang it.
   */
  private class CollidingGenerator<T>(private val id: T) : () -> T {
    var attempts = 0
      private set

    override fun invoke(): T {
      attempts++

      if (attempts > RoomTitles.MAX_IDENTITY_ATTEMPTS) {
        throw AssertionError("Identity generation passed its bound.")
      }

      return id
    }
  }

  private companion object {
    const val TIMEOUT_MILLIS = 10_000L
    const val CONCURRENT_RECONCILIATIONS = 16
    const val KNOWN_TITLE_HEX = "0192A6F07C3E7D4A9F123456789ABCDE"
    const val KNOWN_CHAPTER_HEX = "0192A6F07C3E7D4AAF123456789ABCDF"

    val KNOWN_TITLE =
      checkNotNull(TitleId.ofOrNull(Uuid.parseHex(KNOWN_TITLE_HEX.lowercase())))

    val OTHER_TITLE =
      checkNotNull(
        TitleId.ofOrNull(Uuid.parse("0192a6f0-7c3e-7d4a-bf12-3456789abcd0"))
      )

    val KNOWN_CHAPTER =
      checkNotNull(
        ChapterId.ofOrNull(Uuid.parseHex(KNOWN_CHAPTER_HEX.lowercase()))
      )

    val OTHER_CHAPTER =
      checkNotNull(
        ChapterId.ofOrNull(Uuid.parse("0192a6f0-7c3e-7d4a-af12-3456789abce0"))
      )

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
