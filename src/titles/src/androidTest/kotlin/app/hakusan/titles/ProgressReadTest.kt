package app.hakusan.titles

import androidx.room3.Room
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.hakusan.titles.storage.RoomTitles
import app.hakusan.titles.storage.TitlesDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The explicit one-shot progress read on an in-memory Room database: its
 * agreement with reconciliation, the exact title alias, a missing title, and
 * stored-state violations that no mutation touched.
 */
@RunWith(AndroidJUnit4::class)
class ProgressReadTest {
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
  fun readMatchesReconciliationOfTheSameState(): Unit = runBlocking {
    val member = titles.reconcileTitle(observation("member", "a", "b", "c"))

    val outside = titles.reconcileTitle(observation("outside", "a", "b"))

    val (a, b, c) = member.chapters.map { it.chapter.id }

    val outsideFinal = outside.chapters.last().chapter.id

    titles.addToLibrary(member.titleId)
    complete(member.titleId, a, b)

    titles.completeFinalChapter(
      FinalChapterCompletion(outside.titleId, outsideFinal)
    )

    val position =
      ReadingPosition.of(
        member.titleId,
        c,
        ContentUnitKind.PROVIDER_SEGMENT,
        7,
      )

    titles.recordActualPosition(
      ActualPositionUpdate(position, ProgressEventRecency.CURRENT)
    )

    val memberRead = titles.readProgress(member.titleId)

    val outsideRead = titles.readProgress(outside.titleId)

    val memberReconciled =
      titles.reconcileTitle(observation("member", "a", "b", "c"))

    val outsideReconciled =
      titles.reconcileTitle(observation("outside", "a", "b"))

    assertEquals(memberReconciled, memberRead)
    assertEquals(outsideReconciled, outsideRead)
    assertEquals(SourceTitleAlias.of("source", "member"), memberRead?.alias)
    assertEquals(SourceTitleAlias.of("source", "outside"), outsideRead?.alias)
    assertEquals(c, memberRead?.resumeTarget?.chapter?.id)
  }

  @Test
  fun progressCarriesTheExactObservedAlias(): Unit = runBlocking {
    val aliases =
      listOf(
        SourceTitleAlias.of(" Source\t", "Caf\u00e9 "),
        SourceTitleAlias.of(" Source\t", "Cafe\u0301 "),
        SourceTitleAlias.of(" SOURCE\t", "Caf\u00e9 "),
        SourceTitleAlias.of("other", "Caf\u00e9 "),
      )

    val inserted = aliases.map { alias ->
      titles.reconcileTitle(observation(alias, "1"))
    }

    val known = aliases.map { alias ->
      titles.reconcileTitle(observation(alias, "1")).alias
    }

    val read = inserted.map { snapshot ->
      titles.readProgress(snapshot.titleId)?.alias
    }

    assertEquals(aliases, inserted.map(ProgressSnapshot::alias))
    assertEquals(aliases, known)
    assertEquals(aliases, read)

    assertEquals(
      aliases.size,
      inserted.map(ProgressSnapshot::titleId).toSet().size,
    )
  }

  @Test
  fun unknownTitleReadsAsAbsent(): Unit = runBlocking {
    titles.reconcileTitle(observation("title", "a"))

    assertNull(titles.readProgress(TitleId.generate()))
  }

  @Test
  fun contradictoryStoredRowsRaiseTheirViolation(): Unit = runBlocking {
    val readResume = titles.reconcileTitle(observation("read", "a", "b"))

    val outsideResume = titles.reconcileTitle(observation("outside", "a"))

    val gap = titles.reconcileTitle(observation("gap", "a", "b"))

    val readA = readResume.chapters.first().chapter.id

    titles.addToLibrary(readResume.titleId)

    titles.recordActualPosition(
      ActualPositionUpdate(
        ReadingPosition.of(readResume.titleId, readA, ContentUnitKind.PAGE, 1),
        ProgressEventRecency.CURRENT,
      )
    )

    execute(
      "INSERT INTO read_chapters (chapter_storage_id) " +
        "SELECT chapter_storage_id FROM library_resume_positions"
    )

    execute(
      "INSERT INTO library_resume_positions (title_storage_id, " +
        "chapter_storage_id, unit_kind, unit_index, updated_at) " +
        "SELECT title_storage_id, storage_id, 'PAGE', 0, 0 FROM chapters " +
        "WHERE title_storage_id = (SELECT storage_id FROM titles " +
        "WHERE source_title_key = 'outside')"
    )

    execute(
      "UPDATE chapters SET canonical_index = 5 " +
        "WHERE source_chapter_key = 'b' AND title_storage_id = " +
        "(SELECT storage_id FROM titles WHERE source_title_key = 'gap')"
    )

    val kinds =
      listOf(readResume, outsideResume, gap).map { snapshot ->
        val error =
          assertThrows(StoredStateViolationException::class.java) {
            runBlocking { titles.readProgress(snapshot.titleId) }
          }

        error.kind
      }

    assertEquals(
      listOf(
        StoredStateViolationKind.RESUME_STATE,
        StoredStateViolationKind.RESUME_STATE,
        StoredStateViolationKind.CANONICAL_ORDER,
      ),
      kinds,
    )
  }

  private suspend fun complete(
    titleId: TitleId,
    completed: ChapterId,
    started: ChapterId,
  ) {
    val position = ReadingPosition.of(titleId, started, ContentUnitKind.PAGE, 0)

    titles.completeChapterBoundary(
      ChapterBoundaryCompletion.of(
        completed,
        position,
        ProgressEventRecency.CURRENT,
      )
    )
  }

  private fun observation(
    key: String,
    vararg chapters: String,
  ): AcceptedTitleObservation =
    observation(SourceTitleAlias.of("source", key), *chapters)

  private fun observation(
    alias: SourceTitleAlias,
    vararg chapters: String,
  ): AcceptedTitleObservation {
    val accepted = chapters.map { chapter ->
      AcceptedChapter(
        SourceChapterAlias(alias, TitleLocalChapterKey.of(chapter)),
        "Chapter $chapter",
      )
    }

    return AcceptedTitleObservation.of(alias, "Title", null, accepted)
  }

  private suspend fun execute(sql: String) {
    database.useWriterConnection { connection ->
      connection.usePrepared(sql) { statement -> statement.step() }
    }
  }
}
