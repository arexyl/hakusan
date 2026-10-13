package app.hakusan.titles

import android.database.sqlite.SQLiteException
import androidx.room3.Room
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.hakusan.titles.PositionNotPersistedReason.CHAPTER_ALREADY_READ
import app.hakusan.titles.PositionNotPersistedReason.REORDERED_EVENT
import app.hakusan.titles.PositionNotPersistedReason.TITLE_NOT_IN_LIBRARY
import app.hakusan.titles.ProgressEventRecency.CURRENT
import app.hakusan.titles.ProgressEventRecency.REORDERED
import app.hakusan.titles.storage.RoomTitles
import app.hakusan.titles.storage.TitlesDatabase
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reading-progress mutations on an in-memory Room database: actual positions,
 * chapter boundaries, final completions, their arbitration and rejections,
 * their atomicity, and the time of a position change.
 */
@RunWith(AndroidJUnit4::class)
class ReadingProgressMutationTest {
  private lateinit var database: TitlesDatabase

  private lateinit var titles: Titles

  private val clock = TestClock()

  @Before
  fun openDatabase() {
    database =
      Room.inMemoryDatabaseBuilder<TitlesDatabase>()
        .setDriver(AndroidSQLiteDriver())
        .build()

    titles = RoomTitles(database, clock = clock)
  }

  @After
  fun closeDatabase() {
    database.close()
  }

  @Test
  fun positionPersistsReplacesAndSurvivesChapterOmission(): Unit = runBlocking {
    val title = member("title", "a", "b")

    val (a, b) = title.chapterIds

    val first = record(title.id, a, 0)

    val second = record(title.id, b, 5, ContentUnitKind.PROVIDER_SEGMENT)

    assertEquals(ActualPositionResult.Persisted, first)
    assertEquals(ActualPositionResult.Persisted, second)
    assertEquals(1L, count("library_resume_positions"))

    reconcile("title", "a")

    val omitted = record(title.id, b, 6, ContentUnitKind.PROVIDER_SEGMENT)

    val progress = progress(title.id)

    val target = progress.resumeTarget as ResumeTarget.TemporarilyUnavailable

    assertEquals(ActualPositionResult.Persisted, omitted)
    assertEquals(b, target.chapter.id)
    assertEquals(ContentUnitKind.PROVIDER_SEGMENT, target.unitKind)
    assertEquals(6, target.unitIndex)
    assertEquals(listOf(a), progress.chapters.map { it.chapter.id })

    assertEquals(
      ContinueResolution.SavedTargetUnavailable(target),
      progress.resolveContinue(),
    )
  }

  @Test
  fun positionChangeTakesTheClockAndAnEqualWriteKeepsIt(): Unit = runBlocking {
    val title = member("title", "a", "b", "c")

    val (a, b, c) = title.chapterIds

    clock.set(1_000)
    record(title.id, a, 2)

    assertEquals(listOf("a", "2", "PAGE", "1000"), resumeRow())

    clock.set(2_000)

    val equal = record(title.id, a, 2)

    assertEquals(ActualPositionResult.Persisted, equal)
    assertEquals(listOf("a", "2", "PAGE", "1000"), resumeRow())

    clock.set(3_000)
    record(title.id, a, 3)

    assertEquals(listOf("a", "3", "PAGE", "3000"), resumeRow())

    clock.set(4_000)
    record(title.id, a, 3, ContentUnitKind.PROVIDER_SEGMENT)

    assertEquals(listOf("a", "3", "PROVIDER_SEGMENT", "4000"), resumeRow())

    clock.set(5_000)
    record(title.id, b, 3, ContentUnitKind.PROVIDER_SEGMENT)

    assertEquals(listOf("b", "3", "PROVIDER_SEGMENT", "5000"), resumeRow())

    clock.set(6_000)
    record(title.id, b, 0)

    assertEquals(listOf("b", "0", "PAGE", "6000"), resumeRow())

    clock.set(7_000)

    val repeated = boundary(title.id, a, b, 0)

    assertEquals(ChapterBoundaryResult.Completed, repeated)
    assertEquals(listOf("b", "0", "PAGE", "6000"), resumeRow())

    clock.set(8_000)
    record(title.id, c, 0)

    assertEquals(listOf("c", "0", "PAGE", "8000"), resumeRow())

    clock.set(9_000)

    val replacing = boundary(title.id, a, b, 0)

    assertEquals(ChapterBoundaryResult.Completed, replacing)
    assertEquals(listOf("b", "0", "PAGE", "9000"), resumeRow())
  }

  @Test
  fun reorderedPositionMutatesNothing(): Unit = runBlocking {
    val title = member("title", "a", "b")

    val outside = reconcile("outside", "a")

    val (a, b) = title.chapterIds

    val outsideOnly = outside.chapterIds.single()

    record(title.id, b, 5)
    finish(outside.titleId, outsideOnly)

    val before = storage()

    val results =
      listOf(
        record(title.id, a, 2, recency = REORDERED),
        record(title.id, b, 5, recency = REORDERED),
        record(outside.titleId, outsideOnly, 0, recency = REORDERED),
      )

    assertEquals(
      List(3) { ActualPositionResult.NotPersisted(REORDERED_EVENT) },
      results,
    )

    assertEquals(before, storage())
  }

  @Test
  fun positionReportsInvalidConditionsInTheDecisionOrder(): Unit = runBlocking {
    val title = member("title", "a", "b")

    val other = member("other", "a")

    val outside = reconcile("outside", "a", "b")

    val (a, b) = title.chapterIds
    val (outsideA, outsideB) = outside.chapterIds

    finish(title.id, b)
    finish(outside.titleId, outsideB)

    val before = storage()

    val unknownTitle = TitleId.generate()

    val foreign = other.chapterIds.single()

    val results =
      listOf(
        record(unknownTitle, foreign, 0, recency = REORDERED),
        record(title.id, foreign, 0, recency = REORDERED),
        record(title.id, ChapterId.generate(), 0, recency = REORDERED),
        record(title.id, b, 0, recency = REORDERED),
        record(title.id, b, 0),
        record(outside.titleId, outsideB, 0),
        record(outside.titleId, outsideA, 0),
      )

    assertEquals(
      listOf(
        ActualPositionResult.TitleNotFound,
        ActualPositionResult.ChapterNotFound,
        ActualPositionResult.ChapterNotFound,
        ActualPositionResult.NotPersisted(REORDERED_EVENT),
        ActualPositionResult.NotPersisted(CHAPTER_ALREADY_READ),
        ActualPositionResult.NotPersisted(CHAPTER_ALREADY_READ),
        ActualPositionResult.NotPersisted(TITLE_NOT_IN_LIBRARY),
      ),
      results,
    )

    assertEquals(before, storage())
    assertEquals(ActualPositionResult.Persisted, record(title.id, a, 0))
  }

  @Test
  fun readStatusPersistsWithAndWithoutTheLibrary(): Unit = runBlocking {
    val outside = reconcile("outside", "a", "b", "c")

    val (a, b, c) = outside.chapterIds

    val crossed = boundary(outside.titleId, a, b, 0)

    val finished = finish(outside.titleId, c)

    val before = progress(outside.titleId)

    assertEquals(
      ChapterBoundaryResult.CompletedWithoutStartedPosition(
        TITLE_NOT_IN_LIBRARY
      ),
      crossed,
    )

    assertEquals(FinalCompletionResult.Completed, finished)
    assertFalse(before.isInLibrary)
    assertEquals(listOf(true, false, true), before.chapters.map { it.isRead })
    assertNull(before.resumeTarget)
    assertEquals(0L, count("library_resume_positions"))

    titles.addToLibrary(outside.titleId)

    val after = progress(outside.titleId)

    assertTrue(after.isInLibrary)
    assertEquals(listOf(true, false, true), after.chapters.map { it.isRead })

    assertEquals(
      ChapterBoundaryResult.Completed,
      boundary(outside.titleId, a, b, 1),
    )

    val resumed = progress(outside.titleId).resumeTarget

    assertEquals(b, resumed?.chapter?.id)
  }

  @Test
  fun boundaryMarksTheChapterReadAndRecordsTheStartedUnit(): Unit =
    runBlocking {
      val title = member("title", "a", "b", "c")

      val (a, b) = title.chapterIds

      record(title.id, a, 9)

      val result = boundary(title.id, a, b, 0)

      val progress = progress(title.id)

      val target = progress.resumeTarget as ResumeTarget.Available

      assertEquals(ChapterBoundaryResult.Completed, result)

      assertEquals(
        listOf(true, false, false),
        progress.chapters.map { it.isRead },
      )

      assertEquals(b, target.chapter.id)
      assertEquals(ContentUnitKind.PAGE, target.unitKind)
      assertEquals(0, target.unitIndex)

      assertEquals(
        ContinueResolution.Resume(target),
        progress.resolveContinue(),
      )
    }

  @Test
  fun repeatedCompletionsChangeNothingAndStillAttemptTheReadInsert(): Unit =
    runBlocking {
      val title = member("title", "a", "b", "c")

      val (a, b, c) = title.chapterIds

      boundary(title.id, a, b, 0)
      finish(title.id, c)

      val before = storage()

      execute("CREATE TABLE read_attempts (chapter_storage_id INTEGER)")

      execute(
        "CREATE TRIGGER audit_read BEFORE INSERT ON read_chapters BEGIN " +
          "INSERT INTO read_attempts VALUES (NEW.chapter_storage_id); END"
      )

      val crossed = boundary(title.id, a, b, 0)

      val finished = finish(title.id, c)

      assertEquals(ChapterBoundaryResult.Completed, crossed)
      assertEquals(FinalCompletionResult.Completed, finished)
      assertEquals(before, storage())

      assertEquals(
        listOf(listOf("1"), listOf("3")),
        query("SELECT chapter_storage_id FROM read_attempts ORDER BY rowid"),
      )
    }

  @Test
  fun reorderedBoundaryAddsReadAndKeepsTheNewerPosition(): Unit = runBlocking {
    val title = member("title", "a", "b", "c")

    val (a, b, c) = title.chapterIds

    record(title.id, c, 4)

    val result = boundary(title.id, a, b, 0, recency = REORDERED)

    val progress = progress(title.id)

    assertEquals(
      ChapterBoundaryResult.CompletedWithoutStartedPosition(REORDERED_EVENT),
      result,
    )

    assertEquals(
      listOf(true, false, false),
      progress.chapters.map { it.isRead },
    )

    assertEquals(c, progress.resumeTarget?.chapter?.id)
    assertEquals(4, progress.resumeTarget?.unitIndex)
  }

  @Test
  fun reorderedBoundaryDeletesThePositionOnItsCompletedChapter(): Unit =
    runBlocking {
      val title = member("title", "a", "b", "c")

      val (a, b, c) = title.chapterIds

      record(title.id, a, 4)
      boundary(title.id, a, b, 0, recency = REORDERED)

      val deleted = progress(title.id)

      assertNull(deleted.resumeTarget)

      assertEquals(
        listOf(true, false, false),
        deleted.chapters.map { it.isRead },
      )

      boundary(title.id, b, c, 0)
      boundary(title.id, a, b, 0, recency = REORDERED)

      val merged = progress(title.id)

      assertEquals(listOf(true, true, false), merged.chapters.map { it.isRead })
      assertEquals(c, merged.resumeTarget?.chapter?.id)
    }

  @Test
  fun rereadingKeepsReadStatusAndRecordsNoPositionOnReadChapters(): Unit =
    runBlocking {
      val title = member("title", "a", "b")

      val (a, b) = title.chapterIds

      finish(title.id, b)
      record(title.id, a, 3)

      val intoRead = boundary(title.id, a, b, 0)

      val progress = progress(title.id)

      assertEquals(
        ChapterBoundaryResult.CompletedWithoutStartedPosition(
          CHAPTER_ALREADY_READ
        ),
        intoRead,
      )

      assertNull(progress.resumeTarget)
      assertEquals(listOf(true, true), progress.chapters.map { it.isRead })

      assertEquals(
        ContinueResolution.AllRead(progress.chapters.last().chapter),
        progress.resolveContinue(),
      )

      val reread = record(title.id, a, 1)

      assertEquals(
        ActualPositionResult.NotPersisted(CHAPTER_ALREADY_READ),
        reread,
      )

      assertEquals(
        listOf(true, true),
        progress(title.id).chapters.map { it.isRead },
      )
    }

  @Test
  fun changedAdjacencyAndFinalityRejectWithoutMutation(): Unit = runBlocking {
    val title = member("title", "a", "b", "c")

    val (a, b, c) = title.chapterIds

    record(title.id, a, 6)
    reconcile("title", "a", "c", "b")

    val reordered = storage()

    val reorderedResults =
      listOf(
        boundary(title.id, a, b, 0),
        boundary(title.id, c, a, 0),
        boundary(title.id, b, c, 0),
      )

    val notFinal = finish(title.id, c)

    assertEquals(
      List(3) { ChapterBoundaryResult.CanonicalAdjacencyChanged },
      reorderedResults,
    )

    assertEquals(FinalCompletionResult.CanonicalFinalityChanged, notFinal)
    assertEquals(reordered, storage())

    reconcile("title", "a", "c")

    val omitted = storage()

    val omittedResults =
      listOf(boundary(title.id, a, b, 0), boundary(title.id, b, c, 0))

    val omittedFinal = finish(title.id, b)

    assertEquals(
      List(2) { ChapterBoundaryResult.CanonicalAdjacencyChanged },
      omittedResults,
    )

    assertEquals(FinalCompletionResult.CanonicalFinalityChanged, omittedFinal)
    assertEquals(omitted, storage())
  }

  @Test
  fun canonicalOrderIsTheSnapshotOrderNotTheNames(): Unit = runBlocking {
    val title =
      member(
        "title",
        "z" to "Chapter 10",
        "m" to "Chapter 2",
        "a" to "Chapter 1",
      )

    val (z, m, a) = title.chapterIds

    val before = storage()

    val byName = boundary(title.id, a, m, 0)

    val nameFinal = finish(title.id, z)

    assertEquals(ChapterBoundaryResult.CanonicalAdjacencyChanged, byName)
    assertEquals(FinalCompletionResult.CanonicalFinalityChanged, nameFinal)
    assertEquals(before, storage())
    assertEquals(ChapterBoundaryResult.Completed, boundary(title.id, z, m, 0))
    assertEquals(FinalCompletionResult.Completed, finish(title.id, a))

    val progress = progress(title.id)

    assertEquals(listOf(z, m, a), progress.chapters.map { it.chapter.id })
    assertEquals(listOf(true, false, true), progress.chapters.map { it.isRead })
    assertEquals(m, progress.resumeTarget?.chapter?.id)
    assertEquals(0, progress.resumeTarget?.unitIndex)
  }

  @Test
  fun missingTitleAndForeignChapterChangeNothing(): Unit = runBlocking {
    val title = member("title", "a", "b")

    val other = member("other", "a", "b")

    val (a, b) = title.chapterIds
    val (otherA, otherB) = other.chapterIds

    record(title.id, a, 1)

    val before = storage()

    val unknown = TitleId.generate()

    assertEquals(
      listOf(
        ChapterBoundaryResult.TitleNotFound,
        ChapterBoundaryResult.ChapterNotFound,
        ChapterBoundaryResult.ChapterNotFound,
        ChapterBoundaryResult.ChapterNotFound,
      ),
      listOf(
        boundary(unknown, a, b, 0, recency = REORDERED),
        boundary(title.id, otherA, b, 0),
        boundary(title.id, a, otherB, 0),
        boundary(title.id, ChapterId.generate(), b, 0),
      ),
    )

    assertEquals(
      listOf(
        FinalCompletionResult.TitleNotFound,
        FinalCompletionResult.ChapterNotFound,
        FinalCompletionResult.ChapterNotFound,
      ),
      listOf(
        finish(unknown, b),
        finish(title.id, otherB),
        finish(title.id, ChapterId.generate()),
      ),
    )

    assertEquals(before, storage())
  }

  @Test
  fun failedPositionWriteChangesNothing(): Unit = runBlocking {
    val title = member("title", "a", "b")

    val (a, b) = title.chapterIds

    record(title.id, a, 1)
    failResumeWrites()

    val before = storage()

    assertThrows(SQLiteException::class.java) {
      runBlocking { record(title.id, b, 0) }
    }

    assertEquals(before, storage())
  }

  @Test
  fun failedBoundaryRollsBackEveryEffect(): Unit = runBlocking {
    val title = member("title", "a", "b")

    val (a, b) = title.chapterIds

    record(title.id, a, 1)
    failResumeWrites()

    val before = storage()

    assertThrows(SQLiteException::class.java) {
      runBlocking { boundary(title.id, a, b, 0) }
    }

    assertEquals(before, storage())
    assertEquals(0L, count("read_chapters"))
  }

  @Test
  fun failedFinalCompletionRollsBackEveryEffect(): Unit = runBlocking {
    val title = member("title", "a")

    val a = title.chapterIds.single()

    record(title.id, a, 2)

    execute(
      "CREATE TRIGGER fail_delete AFTER DELETE ON library_resume_positions " +
        "BEGIN SELECT RAISE(ABORT, 'injected failure'); END"
    )

    val before = storage()

    assertThrows(SQLiteException::class.java) {
      runBlocking { finish(title.id, a) }
    }

    assertEquals(before, storage())
    assertEquals(0L, count("read_chapters"))
  }

  @Test
  fun malformedConsumedChapterRowRaisesAndChangesNothing(): Unit = runBlocking {
    val title = member("title", "a", "b")

    val (a, b) = title.chapterIds

    execute(
      "UPDATE chapters SET source_chapter_key = ' ' " +
        "WHERE source_chapter_key = 'b'"
    )

    val before = storage()

    val errors =
      listOf(
        assertThrows(StoredStateViolationException::class.java) {
          runBlocking { record(title.id, b, 0) }
        },
        assertThrows(StoredStateViolationException::class.java) {
          runBlocking { boundary(title.id, a, b, 0) }
        },
        assertThrows(StoredStateViolationException::class.java) {
          runBlocking { finish(title.id, b) }
        },
      )

    assertEquals(
      List(3) { StoredStateViolationKind.ALIAS },
      errors.map { it.kind },
    )

    assertEquals(before, storage())
  }

  private suspend fun member(key: String, vararg chapters: String): Member =
    member(key, *chapters.map { it to "Chapter $it" }.toTypedArray())

  /**
   * Reconciles the title [key] with [chapters], pairs of a local key and a
   * display name, and adds it to the Library.
   */
  private suspend fun member(
    key: String,
    vararg chapters: Pair<String, String>,
  ): Member {
    val snapshot = titles.reconcileTitle(observation(key, chapters.toList()))

    titles.addToLibrary(snapshot.titleId)

    return Member(snapshot.titleId, snapshot.chapterIds)
  }

  private suspend fun reconcile(
    key: String,
    vararg chapters: String,
  ): ProgressSnapshot =
    titles.reconcileTitle(
      observation(key, chapters.map { it to "Chapter $it" })
    )

  private fun observation(
    key: String,
    chapters: List<Pair<String, String>>,
  ): AcceptedTitleObservation {
    val alias = SourceTitleAlias.of("source", key)

    val accepted = chapters.map { (chapter, displayName) ->
      AcceptedChapter(
        SourceChapterAlias(alias, TitleLocalChapterKey.of(chapter)),
        displayName,
      )
    }

    return AcceptedTitleObservation.of(alias, "Title", null, accepted)
  }

  private suspend fun record(
    titleId: TitleId,
    chapterId: ChapterId,
    unitIndex: Int,
    unitKind: ContentUnitKind = ContentUnitKind.PAGE,
    recency: ProgressEventRecency = CURRENT,
  ): ActualPositionResult {
    val position = ReadingPosition.of(titleId, chapterId, unitKind, unitIndex)

    return titles.recordActualPosition(ActualPositionUpdate(position, recency))
  }

  private suspend fun boundary(
    titleId: TitleId,
    completed: ChapterId,
    started: ChapterId,
    startedIndex: Int,
    recency: ProgressEventRecency = CURRENT,
  ): ChapterBoundaryResult {
    val position =
      ReadingPosition.of(titleId, started, ContentUnitKind.PAGE, startedIndex)

    val completion = ChapterBoundaryCompletion.of(completed, position, recency)

    return titles.completeChapterBoundary(completion)
  }

  private suspend fun finish(
    titleId: TitleId,
    chapterId: ChapterId,
  ): FinalCompletionResult =
    titles.completeFinalChapter(FinalChapterCompletion(titleId, chapterId))

  private suspend fun progress(titleId: TitleId): ProgressSnapshot =
    checkNotNull(titles.readProgress(titleId))

  /** Makes every insert and update of a resume row fail. */
  private suspend fun failResumeWrites() {
    listOf("INSERT", "UPDATE").forEach { event ->
      execute(
        "CREATE TRIGGER fail_$event BEFORE $event " +
          "ON library_resume_positions " +
          "BEGIN SELECT RAISE(ABORT, 'injected failure'); END"
      )
    }
  }

  /**
   * The local chapter key, unit index, unit kind, and time of the one stored
   * resume row.
   */
  private suspend fun resumeRow(): List<String> =
    query(
        "SELECT c.source_chapter_key, r.unit_index, r.unit_kind, " +
          "r.updated_at FROM library_resume_positions r " +
          "JOIN chapters c ON c.storage_id = r.chapter_storage_id"
      )
      .single()

  private suspend fun count(table: String): Long =
    query("SELECT count(*) FROM $table").single().single().toLong()

  /** Every stored row that a progress mutation could change, as text. */
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

  private val ProgressSnapshot.chapterIds: List<ChapterId>
    get() = chapters.map { state -> state.chapter.id }

  /** A Library title and its chapters in canonical order. */
  private data class Member(val id: TitleId, val chapterIds: List<ChapterId>)

  /** A clock whose time the test sets. */
  private class TestClock : Clock {
    private val millis = AtomicLong(0)

    fun set(epochMillis: Long) {
      millis.set(epochMillis)
    }

    override fun now(): Instant = Instant.fromEpochMilliseconds(millis.get())
  }

  private companion object {
    val STORAGE_QUERIES =
      listOf(
        "SELECT storage_id, hex(id), title_storage_id, source_chapter_key, " +
          "display_name, quote(canonical_index) FROM chapters " +
          "ORDER BY storage_id",
        "SELECT chapter_storage_id FROM read_chapters ORDER BY 1",
        "SELECT title_storage_id, chapter_storage_id, unit_kind, unit_index, " +
          "updated_at FROM library_resume_positions ORDER BY 1",
        "SELECT title_storage_id, category_id FROM title_categories " +
          "ORDER BY 1, 2",
      )
  }
}
