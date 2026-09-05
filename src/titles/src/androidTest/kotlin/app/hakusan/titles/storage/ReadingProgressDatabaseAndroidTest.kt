package app.hakusan.titles.storage

import android.database.sqlite.SQLiteException
import app.hakusan.titles.CanonicalChapterSnapshot
import app.hakusan.titles.Chapter
import app.hakusan.titles.ChapterId
import app.hakusan.titles.ChapterReconciliationFailure
import app.hakusan.titles.ChapterReconciliationResult
import app.hakusan.titles.ReconcileChapterSnapshot
import app.hakusan.titles.ReconcileSourceChapter
import app.hakusan.titles.ReconcileSourceTitle
import app.hakusan.titles.SourceChapterAlias
import app.hakusan.titles.SourceTitleAlias
import app.hakusan.titles.TitleId
import app.hakusan.titles.Titles
import androidx.room3.Room
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadingProgressDatabaseAndroidTest {
  private lateinit var database: TitlesDatabase
  private lateinit var readingDao: ReadingDao
  private lateinit var titles: Titles

  @Before
  fun openDatabase() {
    database = Room.inMemoryDatabaseBuilder<TitlesDatabase>()
      .setDriver(AndroidSQLiteDriver())
      .build()
    readingDao = database.readingDao()
    titles = database.asTitles()
  }

  @After
  fun closeDatabase() {
    database.close()
  }

  @Test
  fun chapterReconciliationPreservesIdentityAcrossSnapshotChanges(): Unit =
    runBlocking {
      val alias = SourceTitleAlias("source", "title")
      createTitle(alias)
      val initial = reconcile(
        alias,
        "opening" to "Chapter 10",
        "middle" to "Chapter 2",
        "final" to "Chapter 1",
      )
      val repeated = reconcile(
        alias,
        "opening" to "Chapter 10",
        "middle" to "Chapter 2",
        "final" to "Chapter 1",
      )

      assertEquals(initial, repeated)
      assertEquals(
        listOf("opening", "middle", "final"),
        initial.chapters.map { it.alias.sourceChapterKey },
      )

      val reordered = reconcile(
        alias,
        "final" to "Final renamed",
        "opening" to "Chapter 10",
      )
      assertEquals(
        listOf(initial.chapters[2].id, initial.chapters[0].id),
        reordered.chapters.map(Chapter::id),
      )
      val storedTitle = checkNotNull(
        database.titlesDao().findTitleById(initial.titleId.value),
      )
      val storedMiddle = readingDao.loadChapters(storedTitle.storageId)
        .single { it.sourceChapterKey == "middle" }
      assertNull(storedMiddle.canonicalIndex)

      val reappeared = reconcile(
        alias,
        "middle" to "Middle renamed",
        "final" to "Final renamed",
        "opening" to "Chapter 10",
      )
      assertEquals(initial.chapters[1].id, reappeared.chapters[0].id)
      assertEquals("Middle renamed", reappeared.chapters[0].displayName)
      assertTrue(reconcile(alias).chapters.isEmpty())

      val otherAlias = SourceTitleAlias("other-source", "title")
      createTitle(otherAlias)
      val other = reconcile(otherAlias, "opening" to "Chapter 10")
      assertNotEquals(initial.chapters[0].id, other.chapters[0].id)
    }

  @Test
  fun chapterUuidCollisionRetriesWithoutChangingExistingIdentity(): Unit =
    runBlocking {
      val alias = SourceTitleAlias("source", "title")
      createTitle(alias)
      val firstTitles = database.asTitles { FIRST_CHAPTER_ID }
      val first = reconcile(
        titles = firstTitles,
        alias = alias,
        chapters = arrayOf("first" to "First"),
      )
      val candidates = ArrayDeque(
        listOf(FIRST_CHAPTER_ID, SECOND_CHAPTER_ID),
      )
      val collisionTitles = database.asTitles(candidates::removeFirst)

      val result = reconcile(
        titles = collisionTitles,
        alias = alias,
        chapters = arrayOf("first" to "First", "second" to "Second"),
      )

      assertEquals(ChapterId(FIRST_CHAPTER_ID), first.chapters[0].id)
      assertEquals(ChapterId(SECOND_CHAPTER_ID), result.chapters[1].id)
      assertTrue(candidates.isEmpty())
    }

  @Test
  fun exhaustedChapterUuidCollisionsRollBackCanonicalClear() {
    val alias = SourceTitleAlias("source", "title")
    runBlocking { createTitle(alias) }
    val collisionIds = (100 until 116).map(::uuid)
    val seedCandidates = ArrayDeque(collisionIds)
    val seedTitles = database.asTitles(seedCandidates::removeFirst)
    val originalItems = Array(16) { index ->
      "chapter-$index" to "Chapter $index"
    }
    val original = runBlocking {
      reconcile(seedTitles, alias, originalItems)
    }
    val retryCandidates = ArrayDeque(collisionIds)
    val collisionTitles = database.asTitles(retryCandidates::removeFirst)
    val changed = arrayOf("new" to "New", *originalItems)

    assertThrows(IllegalStateException::class.java) {
      runBlocking {
        reconcile(collisionTitles, alias, changed)
      }
    }

    runBlocking {
      assertTrue(retryCandidates.isEmpty())
      assertEquals(
        original.chapters.map(Chapter::id),
        canonicalChapterIds(original.titleId),
      )
      assertEquals(16, queryLong("SELECT COUNT(*) FROM chapters"))
    }
  }

  @Test
  fun injectedReorderFailureRestoresOriginalCanonicalSequence() {
    val alias = SourceTitleAlias("source", "title")
    val prepared = runBlocking {
      val titleId = createTitle(alias)
      val snapshot = reconcile(alias, "first" to "First", "second" to "Second")
      titleId to snapshot
    }
    runBlocking {
      executeSql(
        """
        CREATE TRIGGER fail_canonical_assignment
        BEFORE UPDATE OF canonical_index ON chapters
        WHEN OLD.canonical_index IS NULL AND NEW.canonical_index IS NOT NULL
        BEGIN
          SELECT RAISE(ABORT, 'injected canonical assignment failure');
        END
        """.trimIndent(),
      )
    }

    assertThrows(SQLiteException::class.java) {
      runBlocking {
        reconcile(alias, "second" to "Second", "first" to "First")
      }
    }

    runBlocking {
      assertEquals(
        prepared.second.chapters.map(Chapter::id),
        canonicalChapterIds(prepared.first),
      )
    }
  }

  @Test
  fun unknownTitleChapterReconciliationFailsWithoutMutation(): Unit =
    runBlocking {
      val result = titles.reconcileChapterSnapshot(
        snapshot(SourceTitleAlias("missing", "title"), "chapter" to "Chapter"),
      ) as ChapterReconciliationResult.Failure

      assertEquals(ChapterReconciliationFailure.TitleNotFound, result.error)
      assertEquals(0, queryLong("SELECT COUNT(*) FROM chapters"))
    }

  private suspend fun createTitle(
    alias: SourceTitleAlias,
  ): TitleId {
    val titleId = titles.reconcileSourceTitle(
      ReconcileSourceTitle(
        alias = alias,
        displayName = "Title",
        description = null,
      ),
    )
    return titleId
  }

  private suspend fun reconcile(
    alias: SourceTitleAlias,
    vararg chapters: Pair<String, String>,
  ): CanonicalChapterSnapshot = reconcile(titles, alias, chapters)

  private suspend fun reconcile(
    titles: Titles,
    alias: SourceTitleAlias,
    chapters: Array<out Pair<String, String>>,
  ): CanonicalChapterSnapshot {
    val result = titles.reconcileChapterSnapshot(snapshot(alias, *chapters))
    return (result as ChapterReconciliationResult.Success).snapshot
  }

  private fun snapshot(
    alias: SourceTitleAlias,
    vararg chapters: Pair<String, String>,
  ): ReconcileChapterSnapshot = ReconcileChapterSnapshot.of(
    titleAlias = alias,
    chapters = chapters.map { (key, displayName) ->
      ReconcileSourceChapter(
        alias = SourceChapterAlias(alias, key),
        displayName = displayName,
      )
    },
  )

  private suspend fun canonicalChapterIds(
    titleId: TitleId,
  ): List<ChapterId> {
    val title = checkNotNull(database.titlesDao().findTitleById(titleId.value))
    return readingDao.loadChapters(title.storageId)
      .filter { it.canonicalIndex != null }
      .sortedBy { it.canonicalIndex }
      .map { ChapterId(it.id) }
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

  private suspend fun executeSql(sql: String) {
    database.useWriterConnection { connection ->
      connection.usePrepared(sql) { statement ->
        statement.step()
      }
    }
  }

  private fun uuid(index: Int): UUID = UUID.fromString(
    "00000000-0000-7000-8000-${index.toString(16).padStart(12, '0')}",
  )

  private companion object {
    val FIRST_CHAPTER_ID: UUID = uuid(1)
    val SECOND_CHAPTER_ID: UUID = uuid(2)

    private fun uuid(index: Int): UUID = UUID.fromString(
      "00000000-0000-7000-8000-${index.toString(16).padStart(12, '0')}",
    )
  }
}
