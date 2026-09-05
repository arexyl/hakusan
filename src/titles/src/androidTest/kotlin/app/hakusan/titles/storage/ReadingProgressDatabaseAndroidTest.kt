package app.hakusan.titles.storage

import android.database.sqlite.SQLiteException
import app.hakusan.titles.ActualPositionNotPersisted
import app.hakusan.titles.ActualPositionResult
import app.hakusan.titles.ActualPositionUpdate
import app.hakusan.titles.CanonicalChapterSnapshot
import app.hakusan.titles.Chapter
import app.hakusan.titles.ChapterBoundaryCompletion
import app.hakusan.titles.ChapterId
import app.hakusan.titles.ChapterReconciliationFailure
import app.hakusan.titles.ChapterReconciliationResult
import app.hakusan.titles.CompletionResult
import app.hakusan.titles.FinalChapterCompletion
import app.hakusan.titles.LibraryAddResult
import app.hakusan.titles.ProgressEventRecency
import app.hakusan.titles.ReadingContentUnitKind
import app.hakusan.titles.ReadingPosition
import app.hakusan.titles.ReadingProgressFailure
import app.hakusan.titles.ReconcileChapterSnapshot
import app.hakusan.titles.ReconcileSourceChapter
import app.hakusan.titles.ReconcileSourceTitle
import app.hakusan.titles.SourceChapterAlias
import app.hakusan.titles.SourceTitleAlias
import app.hakusan.titles.TitleId
import app.hakusan.titles.TitleReadingProgress
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
  fun initialSnapshotAssignsCanonicalIndexesWithoutUpdates(): Unit =
    runBlocking {
      val alias = SourceTitleAlias("source", "title")
      createTitle(alias)
      executeSql(
        """
        CREATE TRIGGER reject_chapter_update
        BEFORE UPDATE ON chapters
        BEGIN
          SELECT RAISE(ABORT, 'initial snapshot updated a chapter');
        END
        """.trimIndent(),
      )

      val snapshot = reconcile(
        alias,
        "first" to "First",
        "second" to "Second",
      )

      assertEquals(
        listOf("first", "second"),
        snapshot.chapters.map { it.alias.sourceChapterKey },
      )
      assertCanonicalSnapshotPersisted(snapshot)
    }

  @Test
  fun appendedChapterDoesNotRewriteStablePrefix(): Unit = runBlocking {
    val alias = SourceTitleAlias("source", "title")
    createTitle(alias)
    val initial = reconcile(alias, "first" to "First", "second" to "Second")
    executeSql(
      """
      CREATE TRIGGER reject_stable_prefix_update
      BEFORE UPDATE ON chapters
      BEGIN
        SELECT RAISE(ABORT, 'append rewrote a stable chapter');
      END
      """.trimIndent(),
    )

    val appended = reconcile(
      alias,
      "first" to "First",
      "second" to "Second",
      "third" to "Third",
    )

    assertEquals(
      initial.chapters.map(Chapter::id),
      appended.chapters.take(2).map(Chapter::id),
    )
    assertEquals("third", appended.chapters.last().alias.sourceChapterKey)
    assertCanonicalSnapshotPersisted(appended)
  }

  @Test
  fun metadataChangeUpdatesOnlyTheChangedChapter(): Unit = runBlocking {
    val alias = SourceTitleAlias("source", "title")
    createTitle(alias)
    val initial = reconcile(alias, "first" to "First", "second" to "Second")
    executeSql(
      """
      CREATE TRIGGER reject_canonical_index_update
      BEFORE UPDATE OF canonical_index ON chapters
      BEGIN
        SELECT RAISE(ABORT, 'metadata change rewrote canonical order');
      END
      """.trimIndent(),
    )
    executeSql(
      """
      CREATE TRIGGER reject_unrelated_metadata_update
      BEFORE UPDATE ON chapters
      WHEN OLD.source_chapter_key != 'second'
      BEGIN
        SELECT RAISE(ABORT, 'metadata change rewrote another chapter');
      END
      """.trimIndent(),
    )

    val renamed = reconcile(
      alias,
      "first" to "First",
      "second" to "Second renamed",
    )

    assertEquals(
      initial.chapters.map(Chapter::id),
      renamed.chapters.map(Chapter::id),
    )
    assertEquals("Second renamed", renamed.chapters.last().displayName)
    assertCanonicalSnapshotPersisted(renamed)
  }

  @Test
  fun returningTailChapterUpdatesOnlyThatKnownChapter(): Unit = runBlocking {
    val alias = SourceTitleAlias("source", "title")
    createTitle(alias)
    val initial = reconcile(
      alias,
      "first" to "First",
      "second" to "Second",
      "third" to "Third",
    )
    reconcile(alias, "first" to "First", "second" to "Second")
    executeSql(
      """
      CREATE TRIGGER reject_return_insert
      BEFORE INSERT ON chapters
      BEGIN
        SELECT RAISE(ABORT, 'returning chapter was inserted again');
      END
      """.trimIndent(),
    )
    executeSql(
      """
      CREATE TRIGGER reject_stable_return_prefix_update
      BEFORE UPDATE ON chapters
      WHEN OLD.source_chapter_key != 'third'
      BEGIN
        SELECT RAISE(ABORT, 'return rewrote the stable prefix');
      END
      """.trimIndent(),
    )

    val returned = reconcile(
      alias,
      "first" to "First",
      "second" to "Second",
      "third" to "Third renamed",
    )

    assertEquals(initial.chapters.last().id, returned.chapters.last().id)
    assertEquals("Third renamed", returned.chapters.last().displayName)
    assertCanonicalSnapshotPersisted(returned)
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
      val progress = progress(original.titleId)
      assertEquals(
        original.chapters.map(Chapter::id),
        progress.canonicalChapters.map { it.chapter.id },
      )
      assertEquals(16, queryLong("SELECT COUNT(*) FROM chapters"))
    }
  }

  @Test
  fun batchedChapterLookupAndLibraryFactsMapByIdentity(): Unit = runBlocking {
    val alias = SourceTitleAlias("source", "title")
    val titleId = createTitle(alias, addToLibrary = true)
    val candidates = ArrayDeque(
      listOf(SECOND_CHAPTER_ID, FIRST_CHAPTER_ID),
    )
    val snapshot = reconcile(
      titles = database.asTitles(candidates::removeFirst),
      alias = alias,
      chapters = arrayOf("first" to "First", "second" to "Second"),
    )
    assertEquals(ChapterId(SECOND_CHAPTER_ID), snapshot.chapters[0].id)
    assertEquals(ChapterId(FIRST_CHAPTER_ID), snapshot.chapters[1].id)
    assertTrue(candidates.isEmpty())
    val storedTitle = checkNotNull(
      database.titlesDao().findTitleById(titleId.value),
    )

    val stored = readingDao.findChaptersByIdsForTitle(
      titleStorageId = storedTitle.storageId,
      firstChapterId = snapshot.chapters[0].id.value,
      secondChapterId = snapshot.chapters[1].id.value,
    )
    val storedById = stored.associateBy { ChapterId(it.id) }
    val first = checkNotNull(storedById[snapshot.chapters[0].id])
    val second = checkNotNull(storedById[snapshot.chapters[1].id])
    assertEquals(2, storedById.size)
    assertEquals(0, first.canonicalIndex)
    assertEquals(1, second.canonicalIndex)

    readingDao.insertReadChapterOrIgnore(ReadChapterEntity(first.storageId))
    val firstFacts = readingDao.loadLibraryChapterFacts(
      titleStorageId = storedTitle.storageId,
      chapterStorageId = first.storageId,
    )
    val secondFacts = readingDao.loadLibraryChapterFacts(
      titleStorageId = storedTitle.storageId,
      chapterStorageId = second.storageId,
    )
    assertTrue(firstFacts.isLibraryMember)
    assertTrue(firstFacts.isChapterRead)
    assertTrue(secondFacts.isLibraryMember)
    assertFalse(secondFacts.isChapterRead)
  }

  @Test
  fun actualPositionPersistsReplacesAndSurvivesChapterOmission(): Unit =
    runBlocking {
      val alias = SourceTitleAlias("source", "title")
      val titleId = createTitle(alias, addToLibrary = true)
      val chapters = reconcile(alias, "first" to "First", "second" to "Second")
      val first = position(titleId, chapters.chapters[0], unitIndex = 0)
      val second = position(
        titleId = titleId,
        chapter = chapters.chapters[1],
        unitKind = ReadingContentUnitKind.PROVIDER_SEGMENT,
        unitIndex = 5,
      )

      assertTrue(
        record(first) is ActualPositionResult.Persisted,
      )
      assertTrue(
        record(second) is ActualPositionResult.Persisted,
      )
      assertTrue(
        record(second) is ActualPositionResult.Persisted,
      )
      assertEquals(
        1,
        queryLong("SELECT COUNT(*) FROM library_resume_positions"),
      )

      reconcile(alias, "first" to "First")
      val omittedUpdate = second.copy(unitIndex = 6)
      val result = record(omittedUpdate)
        as ActualPositionResult.Persisted

      assertEquals(
        omittedUpdate,
        result.progress.libraryResumePosition?.position,
      )
      assertFalse(
        result.progress.libraryResumePosition?.isCurrentlyAvailable ?: true,
      )
      assertEquals(
        listOf(chapters.chapters[0].id),
        result.progress.canonicalChapters.map { it.chapter.id },
      )
    }

  @Test
  fun reorderedActualPositionDoesNotReplaceNewerPosition(): Unit =
    runBlocking {
      val alias = SourceTitleAlias("source", "title")
      val titleId = createTitle(alias, addToLibrary = true)
      val snapshot = reconcile(alias, "first" to "First", "final" to "Final")
      val older = position(titleId, snapshot.chapters[0], unitIndex = 2)
      val newer = position(titleId, snapshot.chapters[1], unitIndex = 5)
      record(newer)

      val result = record(older, ProgressEventRecency.REORDERED)
        as ActualPositionResult.NotPersisted

      assertEquals(ActualPositionNotPersisted.REORDERED_EVENT, result.reason)
      assertEquals(newer, result.progress.libraryResumePosition?.position)
    }

  @Test
  fun actualPositionExplainsNonLibraryAndCompletedChapters(): Unit =
    runBlocking {
      val alias = SourceTitleAlias("source", "title")
      val titleId = createTitle(alias)
      val chapter = reconcile(alias, "only" to "Only").chapters.single()
      val position = position(titleId, chapter, unitIndex = 0)

      val nonLibrary = record(position)
        as ActualPositionResult.NotPersisted
      assertEquals(
        ActualPositionNotPersisted.TITLE_NOT_IN_LIBRARY,
        nonLibrary.reason,
      )

      assertTrue(
        titles.completeFinalChapter(
          FinalChapterCompletion(
            titleId,
            chapter.id,
          ),
        ) is CompletionResult.Success,
      )
      val completed = record(position)
        as ActualPositionResult.NotPersisted
      assertEquals(
        ActualPositionNotPersisted.CHAPTER_ALREADY_READ,
        completed.reason,
      )
      assertEquals(1, queryLong("SELECT COUNT(*) FROM read_chapters"))
      assertEquals(
        0,
        queryLong("SELECT COUNT(*) FROM library_resume_positions"),
      )
    }

  @Test
  fun repeatedBoundaryIsIdempotentBeforeNewerEvents(): Unit =
    runBlocking {
      val alias = SourceTitleAlias("source", "title")
      val titleId = createTitle(alias, addToLibrary = true)
      val snapshot = reconcile(
        alias,
        "first" to "First",
        "second" to "Second",
        "final" to "Final",
      )
      val first = snapshot.chapters[0]
      val second = snapshot.chapters[1]
      val final = snapshot.chapters[2]
      record(position(titleId, first, unitIndex = 9))

      val firstBoundary = boundary(
        first,
        position(titleId, second, unitIndex = 0),
      )
      assertTrue(
        titles.completeChapterBoundary(firstBoundary) is
          CompletionResult.Success,
      )
      titles.completeChapterBoundary(firstBoundary)
      assertEquals(
        firstBoundary.startedPosition,
        progress(titleId).libraryResumePosition?.position,
      )

      val newerSecond = position(titleId, second, unitIndex = 5)
      record(newerSecond)
      assertEquals(
        newerSecond,
        progress(titleId).libraryResumePosition?.position,
      )

      val finalPosition = position(titleId, final, unitIndex = 0)
      titles.completeChapterBoundary(boundary(second, finalPosition))

      val progress = progress(titleId)
      assertEquals(finalPosition, progress.libraryResumePosition?.position)
      assertEquals(
        setOf(first.id, second.id),
        progress.canonicalChapters.filter { it.isRead }
          .mapTo(HashSet()) { it.chapter.id },
      )
    }

  @Test
  fun repeatedCompletionsAttemptIdempotentReadInserts(): Unit = runBlocking {
    val alias = SourceTitleAlias("source", "title")
    val titleId = createTitle(alias, addToLibrary = true)
    val snapshot = reconcile(
      alias,
      "first" to "First",
      "second" to "Second",
      "final" to "Final",
    )
    val firstBoundary = boundary(
      snapshot.chapters[0],
      position(titleId, snapshot.chapters[1], unitIndex = 0),
    )
    val finalCompletion = FinalChapterCompletion(
      titleId,
      snapshot.chapters[2].id,
    )
    assertTrue(
      titles.completeChapterBoundary(firstBoundary) is CompletionResult.Success,
    )
    assertTrue(
      titles.completeFinalChapter(finalCompletion) is CompletionResult.Success,
    )
    assertEquals(2, queryLong("SELECT COUNT(*) FROM read_chapters"))
    val before = progress(titleId)
    executeSql(
      """
      CREATE TABLE read_insert_attempts (
        chapter_storage_id INTEGER NOT NULL
      )
      """.trimIndent(),
    )
    executeSql(
      """
      CREATE TRIGGER audit_read_insert_attempt
      BEFORE INSERT ON read_chapters
      BEGIN
        INSERT INTO read_insert_attempts VALUES (NEW.chapter_storage_id);
      END
      """.trimIndent(),
    )

    assertTrue(
      titles.completeChapterBoundary(firstBoundary) is CompletionResult.Success,
    )
    assertTrue(
      titles.completeFinalChapter(finalCompletion) is CompletionResult.Success,
    )

    assertEquals(2, queryLong("SELECT COUNT(*) FROM read_insert_attempts"))
    assertEquals(
      2,
      queryLong(
        "SELECT COUNT(DISTINCT chapter_storage_id) " +
          "FROM read_insert_attempts",
      ),
    )
    assertEquals(2, queryLong("SELECT COUNT(*) FROM read_chapters"))
    assertEquals(before, progress(titleId))
  }

  @Test
  fun reorderedBoundaryPreservesPositionForAnotherUnreadChapter(): Unit =
    runBlocking {
      val alias = SourceTitleAlias("source", "title")
      val titleId = createTitle(alias, addToLibrary = true)
      val snapshot = reconcile(
        alias,
        "first" to "First",
        "second" to "Second",
        "final" to "Final",
      )
      val first = snapshot.chapters[0]
      val second = snapshot.chapters[1]
      val finalPosition = position(
        titleId,
        snapshot.chapters[2],
        unitIndex = 4,
      )
      record(finalPosition)

      val result = titles.completeChapterBoundary(
        boundary(
          completed = first,
          started = position(titleId, second, unitIndex = 0),
          recency = ProgressEventRecency.REORDERED,
        ),
      ) as CompletionResult.Success

      assertEquals(
        finalPosition,
        result.progress.libraryResumePosition?.position,
      )
      assertTrue(
        result.progress.canonicalChapters
          .single { it.chapter.id == first.id }
          .isRead,
      )
      assertFalse(
        result.progress.canonicalChapters
          .single { it.chapter.id == second.id }
          .isRead,
      )
    }

  @Test
  fun reorderedBoundaryCleansPositionForItsCompletedChapter(): Unit =
    runBlocking {
      val alias = SourceTitleAlias("source", "title")
      val titleId = createTitle(alias, addToLibrary = true)
      val snapshot = reconcile(alias, "first" to "First", "final" to "Final")
      val completedPosition = position(
        titleId,
        snapshot.chapters[0],
        unitIndex = 4,
      )
      record(completedPosition)

      val result = titles.completeChapterBoundary(
        boundary(
          completed = snapshot.chapters[0],
          started = position(titleId, snapshot.chapters[1], unitIndex = 0),
          recency = ProgressEventRecency.REORDERED,
        ),
      ) as CompletionResult.Success

      assertNull(result.progress.libraryResumePosition)
      assertTrue(result.progress.canonicalChapters.first().isRead)
      assertFalse(result.progress.canonicalChapters.last().isRead)
    }

  @Test
  fun reorderedBoundariesMergeReadAndKeepNewestPosition(): Unit =
    runBlocking {
      val alias = SourceTitleAlias("source", "title")
      val titleId = createTitle(alias, addToLibrary = true)
      val snapshot = reconcile(
        alias,
        "first" to "First",
        "second" to "Second",
        "final" to "Final",
      )
      val first = snapshot.chapters[0]
      val second = snapshot.chapters[1]
      val finalPosition = position(
        titleId,
        snapshot.chapters[2],
        unitIndex = 0,
      )
      titles.completeChapterBoundary(boundary(second, finalPosition))

      val result = titles.completeChapterBoundary(
        boundary(
          completed = first,
          started = position(titleId, second, unitIndex = 0),
          recency = ProgressEventRecency.REORDERED,
        ),
      ) as CompletionResult.Success

      assertEquals(
        setOf(first.id, second.id),
        result.progress.canonicalChapters.filter { it.isRead }
          .mapTo(HashSet()) { it.chapter.id },
      )
      assertEquals(
        finalPosition,
        result.progress.libraryResumePosition?.position,
      )
    }

  @Test
  fun rereadBoundaryStoresTheActuallyStartedUnreadChapter(): Unit =
    runBlocking {
      val alias = SourceTitleAlias("source", "title")
      val titleId = createTitle(alias, addToLibrary = true)
      val originalFinal = reconcile(alias, "first" to "First").chapters.single()
      assertTrue(
        titles.completeFinalChapter(
          FinalChapterCompletion(
            titleId,
            originalFinal.id,
          ),
        ) is CompletionResult.Success,
      )
      val expanded = reconcile(
        alias,
        "first" to "First",
        "second" to "Second",
        "third" to "Third",
      )
      val previousPosition = position(
        titleId,
        expanded.chapters[2],
        unitIndex = 3,
      )
      record(previousPosition)
      val before = progress(titleId)
      assertTrue(before.canonicalChapters.first().isRead)
      assertEquals(
        previousPosition,
        before.libraryResumePosition?.position,
      )
      val startedPosition = position(
        titleId,
        expanded.chapters[1],
        unitIndex = 0,
      )

      val result = titles.completeChapterBoundary(
        boundary(expanded.chapters[0], startedPosition),
      )

      assertTrue(result is CompletionResult.Success)
      assertEquals(
        startedPosition,
        progress(titleId).libraryResumePosition?.position,
      )
    }

  @Test
  fun boundaryIntoReadChapterLeavesNoResumePosition(): Unit = runBlocking {
    val alias = SourceTitleAlias("source", "title")
    val titleId = createTitle(alias, addToLibrary = true)
    val snapshot = reconcile(alias, "first" to "First", "final" to "Final")
    val first = snapshot.chapters[0]
    val final = snapshot.chapters[1]
    assertTrue(
      titles.completeFinalChapter(
        FinalChapterCompletion(titleId, final.id),
      ) is CompletionResult.Success,
    )
    val firstPosition = position(titleId, first, unitIndex = 3)
    record(firstPosition)

    val result = titles.completeChapterBoundary(
      boundary(
        completed = first,
        started = position(titleId, final, unitIndex = 0),
      ),
    ) as CompletionResult.Success

    assertNull(result.progress.libraryResumePosition)
    assertTrue(result.progress.canonicalChapters.all { it.isRead })
  }

  @Test
  fun changedSequenceRejectsBoundaryButNonLibraryReadStillPersists(): Unit =
    runBlocking {
      val alias = SourceTitleAlias("source", "title")
      val titleId = createTitle(alias, addToLibrary = true)
      val initial = reconcile(
        alias,
        "first" to "First",
        "second" to "Second",
        "final" to "Final",
      )
      val initialPosition = position(
        titleId,
        initial.chapters[0],
        unitIndex = 6,
      )
      record(initialPosition)
      reconcile(
        alias,
        "first" to "First",
        "final" to "Final",
        "second" to "Second",
      )

      val rejected = titles.completeChapterBoundary(
        boundary(
          initial.chapters[0],
          position(titleId, initial.chapters[1], unitIndex = 0),
        ),
      ) as CompletionResult.Failure
      val sequenceError = rejected.error
        as ReadingProgressFailure.CanonicalSequenceChanged
      assertEquals(
        setOf(initial.chapters[0].id, initial.chapters[1].id),
        sequenceError.chapterIds,
      )
      assertEquals(0, queryLong("SELECT COUNT(*) FROM read_chapters"))
      assertEquals(
        initialPosition,
        progress(titleId).libraryResumePosition?.position,
      )

      val otherAlias = SourceTitleAlias("source", "other")
      val otherTitleId = createTitle(otherAlias)
      val other = reconcile(
        otherAlias,
        "first" to "First",
        "second" to "Second",
      )
      val success = titles.completeChapterBoundary(
        boundary(
          other.chapters[0],
          position(otherTitleId, other.chapters[1], unitIndex = 0),
        ),
      )
      assertTrue(success is CompletionResult.Success)
      assertEquals(1, queryLong("SELECT COUNT(*) FROM read_chapters"))
      assertNull(progress(otherTitleId).libraryResumePosition)
      assertEquals(
        1,
        queryLong("SELECT COUNT(*) FROM library_resume_positions"),
      )
    }

  @Test
  fun finalCompletionValidatesFinalityAndPreservesAnotherPosition(): Unit =
    runBlocking {
      val alias = SourceTitleAlias("source", "title")
      val titleId = createTitle(alias, addToLibrary = true)
      val snapshot = reconcile(alias, "first" to "First", "final" to "Final")
      val first = snapshot.chapters[0]
      val final = snapshot.chapters[1]
      val firstPosition = position(titleId, first, unitIndex = 1)
      record(firstPosition)

      val rejected = titles.completeFinalChapter(
        FinalChapterCompletion(
          titleId,
          first.id,
        ),
      ) as CompletionResult.Failure
      val sequenceError = rejected.error
        as ReadingProgressFailure.CanonicalSequenceChanged
      assertEquals(
        setOf(first.id),
        sequenceError.chapterIds,
      )
      assertEquals(
        firstPosition,
        progress(titleId).libraryResumePosition?.position,
      )

      val finalPosition = position(titleId, final, unitIndex = 2)
      record(finalPosition)
      titles.completeFinalChapter(
        FinalChapterCompletion(
          titleId,
          final.id,
        ),
      )
      assertNull(progress(titleId).libraryResumePosition)

      record(firstPosition)
      titles.completeFinalChapter(
        FinalChapterCompletion(
          titleId,
          final.id,
        ),
      )
      val repeated = progress(titleId)
      assertEquals(firstPosition, repeated.libraryResumePosition?.position)
      assertTrue(
        repeated.canonicalChapters.single { it.chapter.id == final.id }.isRead,
      )
    }

  @Test
  fun finalCompletionCleansOnlyItsOwnPosition(): Unit = runBlocking {
    val alias = SourceTitleAlias("source", "title")
    val titleId = createTitle(alias, addToLibrary = true)
    val snapshot = reconcile(alias, "first" to "First", "final" to "Final")
    val first = snapshot.chapters[0]
    val final = snapshot.chapters[1]
    record(position(titleId, final, unitIndex = 2))

    val completed = titles.completeFinalChapter(
      FinalChapterCompletion(
        titleId,
        final.id,
      ),
    ) as CompletionResult.Success
    assertNull(completed.progress.libraryResumePosition)
    assertTrue(completed.progress.canonicalChapters.last().isRead)

    val newerPosition = position(titleId, first, unitIndex = 3)
    record(newerPosition)
    val repeated = titles.completeFinalChapter(
      FinalChapterCompletion(
        titleId,
        final.id,
      ),
    ) as CompletionResult.Success

    assertEquals(
      newerPosition,
      repeated.progress.libraryResumePosition?.position,
    )
  }

  @Test
  fun injectedBoundaryFailureRollsBackReadAndPositionDeletion() {
    val alias = SourceTitleAlias("source", "title")
    val prepared = runBlocking {
      val titleId = createTitle(alias, addToLibrary = true)
      val chapters = reconcile(alias, "first" to "First", "second" to "Second")
      val firstPosition = position(titleId, chapters.chapters[0], unitIndex = 1)
      record(firstPosition)
      Triple(titleId, chapters, firstPosition)
    }
    runBlocking {
      executeSql(
        """
        CREATE TRIGGER fail_resume_insert
        BEFORE INSERT ON library_resume_positions
        BEGIN
          SELECT RAISE(ABORT, 'injected resume insertion failure');
        END
        """.trimIndent(),
      )
    }

    assertThrows(SQLiteException::class.java) {
      runBlocking {
        titles.completeChapterBoundary(
          boundary(
            prepared.second.chapters[0],
            position(
              prepared.first,
              prepared.second.chapters[1],
              unitIndex = 0,
            ),
          ),
        )
      }
    }

    runBlocking {
      assertEquals(0, queryLong("SELECT COUNT(*) FROM read_chapters"))
      assertEquals(
        prepared.third,
        progress(prepared.first).libraryResumePosition?.position,
      )
    }
  }

  @Test
  fun canceledQueuedBoundaryDoesNotMutateProgress(): Unit = runBlocking {
    val alias = SourceTitleAlias("source", "title")
    val titleId = createTitle(alias, addToLibrary = true)
    val snapshot = reconcile(alias, "first" to "First", "final" to "Final")
    val initialPosition = position(
      titleId,
      snapshot.chapters[0],
      unitIndex = 2,
    )
    record(initialPosition)
    val writerEntered = CompletableDeferred<Unit>()
    val releaseWriter = CompletableDeferred<Unit>()
    val writer = launch {
      database.withWriteTransaction {
        writerEntered.complete(Unit)
        releaseWriter.await()
      }
    }
    writerEntered.await()

    val boundary = launch(start = CoroutineStart.UNDISPATCHED) {
      titles.completeChapterBoundary(
        boundary(
          completed = snapshot.chapters[0],
          started = position(titleId, snapshot.chapters[1], unitIndex = 0),
        ),
      )
    }
    boundary.cancel()
    releaseWriter.complete(Unit)
    boundary.join()
    writer.join()

    assertTrue(boundary.isCancelled)
    assertEquals(0, queryLong("SELECT COUNT(*) FROM read_chapters"))
    assertEquals(
      initialPosition,
      progress(titleId).libraryResumePosition?.position,
    )
  }

  @Test
  fun injectedFinalFailureRollsBackReadAndPositionDeletion() {
    val alias = SourceTitleAlias("source", "title")
    val prepared = runBlocking {
      val titleId = createTitle(alias, addToLibrary = true)
      val chapter = reconcile(alias, "final" to "Final").chapters.single()
      val position = position(titleId, chapter, unitIndex = 2)
      record(position)
      Triple(titleId, chapter, position)
    }
    runBlocking {
      executeSql(
        """
        CREATE TRIGGER fail_final_resume_delete
        AFTER DELETE ON library_resume_positions
        BEGIN
          SELECT RAISE(ABORT, 'injected final resume deletion failure');
        END
        """.trimIndent(),
      )
    }

    assertThrows(SQLiteException::class.java) {
      runBlocking {
        titles.completeFinalChapter(
          FinalChapterCompletion(
            prepared.first,
            prepared.second.id,
          ),
        )
      }
    }

    runBlocking {
      assertEquals(0, queryLong("SELECT COUNT(*) FROM read_chapters"))
      assertEquals(
        prepared.third,
        progress(prepared.first).libraryResumePosition?.position,
      )
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
        progress(prepared.first).canonicalChapters.map { it.chapter.id },
      )
    }
  }

  @Test
  fun observationDistinguishesUnknownEmptyAndUnavailableResume(): Unit =
    runBlocking {
      assertNull(
        titles.observeReadingProgress(TitleId(UNKNOWN_TITLE_ID)).first(),
      )

      val alias = SourceTitleAlias("source", "title")
      val titleId = createTitle(alias, addToLibrary = true)
      assertTrue(progress(titleId).canonicalChapters.isEmpty())
      val snapshot = reconcile(
        alias,
        "opening" to "Chapter 10",
        "middle" to "Chapter 2",
        "final" to "Chapter 1",
      )
      val middlePosition = position(
        titleId,
        snapshot.chapters[1],
        unitIndex = 4,
      )
      record(middlePosition)
      val observedOmission = async(start = CoroutineStart.UNDISPATCHED) {
        withTimeout(FLOW_TIMEOUT_MILLIS) {
          titles.observeReadingProgress(titleId).first {
            it?.libraryResumePosition?.isCurrentlyAvailable == false
          }
        }
      }
      reconcile(
        alias,
        "opening" to "Chapter 10",
        "final" to "Chapter 1",
      )

      val progress = checkNotNull(observedOmission.await())
      assertEquals(
        listOf("opening", "final"),
        progress.canonicalChapters.map {
          it.chapter.alias.sourceChapterKey
        },
      )
      assertEquals(middlePosition, progress.libraryResumePosition?.position)
      assertFalse(progress.libraryResumePosition?.isCurrentlyAvailable ?: true)
    }

  @Test
  fun boundaryObservationPublishesOnlyCoherentCommittedProgress(): Unit =
    runBlocking {
      val alias = SourceTitleAlias("source", "title")
      val titleId = createTitle(alias, addToLibrary = true)
      val snapshot = reconcile(alias, "first" to "First", "final" to "Final")
      val firstPosition = position(
        titleId,
        snapshot.chapters[0],
        unitIndex = 2,
      )
      val finalPosition = position(
        titleId,
        snapshot.chapters[1],
        unitIndex = 0,
      )
      record(firstPosition)
      val initialProgress = progress(titleId)
      val firstObserved = CompletableDeferred<Unit>()
      val observations = mutableListOf<TitleReadingProgress>()
      val updated = async(start = CoroutineStart.UNDISPATCHED) {
        withTimeout(FLOW_TIMEOUT_MILLIS) {
          titles.observeReadingProgress(titleId).first { progress ->
            val current = checkNotNull(progress)
            observations += current
            firstObserved.complete(Unit)
            current.canonicalChapters.first().isRead &&
              current.libraryResumePosition?.position == finalPosition
          }
        }
      }
      firstObserved.await()

      titles.completeChapterBoundary(
        boundary(snapshot.chapters[0], finalPosition),
      )
      val finalProgress = checkNotNull(updated.await())

      assertEquals(
        listOf(initialProgress, finalProgress),
        observations,
      )
    }

  @Test
  fun observationTracksMembershipResumeAndReadTables(): Unit = runBlocking {
    val libraryAlias = SourceTitleAlias("source", "library-title")
    val libraryTitleId = createTitle(libraryAlias)
    val libraryChapter = reconcile(libraryAlias, "only" to "Only")
      .chapters.single()
    val membershipInitial = CompletableDeferred<Unit>()
    val membershipUpdate = async(start = CoroutineStart.UNDISPATCHED) {
      withTimeout(FLOW_TIMEOUT_MILLIS) {
        titles.observeReadingProgress(libraryTitleId).first { progress ->
          membershipInitial.complete(Unit)
          progress?.isInLibrary == true
        }
      }
    }
    membershipInitial.await()
    titles.addToLibrary(libraryTitleId)
    assertTrue(checkNotNull(membershipUpdate.await()).isInLibrary)

    val resumePosition = position(libraryTitleId, libraryChapter, unitIndex = 4)
    val resumeInitial = CompletableDeferred<Unit>()
    val resumeUpdate = async(start = CoroutineStart.UNDISPATCHED) {
      withTimeout(FLOW_TIMEOUT_MILLIS) {
        titles.observeReadingProgress(libraryTitleId).first { progress ->
          resumeInitial.complete(Unit)
          progress?.libraryResumePosition?.position == resumePosition
        }
      }
    }
    resumeInitial.await()
    record(resumePosition)
    assertEquals(
      resumePosition,
      checkNotNull(resumeUpdate.await()).libraryResumePosition?.position,
    )

    val readAlias = SourceTitleAlias("source", "read-title")
    val readTitleId = createTitle(readAlias)
    val readChapter = reconcile(readAlias, "only" to "Only").chapters.single()
    val readInitial = CompletableDeferred<Unit>()
    val readUpdate = async(start = CoroutineStart.UNDISPATCHED) {
      withTimeout(FLOW_TIMEOUT_MILLIS) {
        titles.observeReadingProgress(readTitleId).first { progress ->
          readInitial.complete(Unit)
          progress?.canonicalChapters?.single()?.isRead == true
        }
      }
    }
    readInitial.await()
    titles.completeFinalChapter(
      FinalChapterCompletion(readTitleId, readChapter.id),
    )
    assertTrue(
      checkNotNull(readUpdate.await()).canonicalChapters.single().isRead,
    )
  }

  @Test
  fun omittedReadChapterRemainsReadWhenItReappears(): Unit = runBlocking {
    val alias = SourceTitleAlias("source", "title")
    val titleId = createTitle(alias)
    val snapshot = reconcile(alias, "first" to "First", "final" to "Final")
    val final = snapshot.chapters[1]
    titles.completeFinalChapter(
      FinalChapterCompletion(
        titleId,
        final.id,
      ),
    )

    reconcile(alias, "first" to "First")
    assertEquals(1, queryLong("SELECT COUNT(*) FROM read_chapters"))
    reconcile(alias, "first" to "First", "final" to "Final")

    val reappeared = progress(titleId).canonicalChapters
      .single { it.chapter.id == final.id }
    assertTrue(reappeared.isRead)
  }

  @Test
  fun unknownTitleAndWrongOwnerChapterFailWithoutMutation(): Unit =
    runBlocking {
      val unknownSnapshot = titles.reconcileChapterSnapshot(
        snapshot(SourceTitleAlias("missing", "title"), "chapter" to "Chapter"),
      ) as ChapterReconciliationResult.Failure
      assertEquals(
        ChapterReconciliationFailure.TitleNotFound,
        unknownSnapshot.error,
      )

      val firstAlias = SourceTitleAlias("source", "first")
      val secondAlias = SourceTitleAlias("source", "second")
      val firstTitleId = createTitle(firstAlias, addToLibrary = true)
      createTitle(secondAlias)
      val firstChapter = reconcile(firstAlias, "chapter" to "First")
        .chapters.single()
      val secondChapter = reconcile(secondAlias, "chapter" to "Second")
        .chapters.single()
      val result = record(
        position(firstTitleId, secondChapter, unitIndex = 0),
      ) as ActualPositionResult.Failure

      assertEquals(
        setOf(secondChapter.id),
        (result.error as ReadingProgressFailure.ChaptersNotFound).chapterIds,
      )
      assertEquals(
        0,
        queryLong("SELECT COUNT(*) FROM library_resume_positions"),
      )
      assertNotEquals(firstChapter.id, secondChapter.id)

      val unknownTitleId = TitleId(UNKNOWN_TITLE_ID)
      val unknownChapterId = ChapterId(uuid(998))
      val unknownPosition = ReadingPosition(
        titleId = unknownTitleId,
        chapterId = unknownChapterId,
        unitKind = ReadingContentUnitKind.PAGE,
        unitIndex = 0,
      )
      assertEquals(
        ReadingProgressFailure.TitleNotFound,
        (record(unknownPosition)
          as ActualPositionResult.Failure).error,
      )
      assertEquals(
        ReadingProgressFailure.TitleNotFound,
        (titles.completeFinalChapter(
          FinalChapterCompletion(
            unknownTitleId,
            unknownChapterId,
          ),
        ) as CompletionResult.Failure).error,
      )
      assertEquals(
        ReadingProgressFailure.TitleNotFound,
        (titles.completeChapterBoundary(
          ChapterBoundaryCompletion(
            completedChapterId = ChapterId(uuid(997)),
            startedPosition = unknownPosition,
            recency = ProgressEventRecency.CURRENT,
          ),
        ) as CompletionResult.Failure).error,
      )

      val missingFinal = titles.completeFinalChapter(
        FinalChapterCompletion(
          firstTitleId,
          secondChapter.id,
        ),
      ) as CompletionResult.Failure
      assertEquals(
        setOf(secondChapter.id),
        (missingFinal.error as ReadingProgressFailure.ChaptersNotFound)
          .chapterIds,
      )
      val missingBoundary = titles.completeChapterBoundary(
        ChapterBoundaryCompletion(
          completedChapterId = secondChapter.id,
          startedPosition = position(firstTitleId, firstChapter, unitIndex = 0),
          recency = ProgressEventRecency.CURRENT,
        ),
      ) as CompletionResult.Failure
      assertEquals(
        setOf(secondChapter.id),
        (missingBoundary.error as ReadingProgressFailure.ChaptersNotFound)
          .chapterIds,
      )

      val foreignStarted = titles.completeChapterBoundary(
        ChapterBoundaryCompletion(
          completedChapterId = firstChapter.id,
          startedPosition = position(
            firstTitleId,
            secondChapter,
            unitIndex = 0,
          ),
          recency = ProgressEventRecency.CURRENT,
        ),
      ) as CompletionResult.Failure
      assertEquals(
        setOf(secondChapter.id),
        (foreignStarted.error as ReadingProgressFailure.ChaptersNotFound)
          .chapterIds,
      )

      val missingStartedId = ChapterId(uuid(996))
      val missingStarted = titles.completeChapterBoundary(
        ChapterBoundaryCompletion(
          completedChapterId = firstChapter.id,
          startedPosition = ReadingPosition(
            titleId = firstTitleId,
            chapterId = missingStartedId,
            unitKind = ReadingContentUnitKind.PAGE,
            unitIndex = 0,
          ),
          recency = ProgressEventRecency.CURRENT,
        ),
      ) as CompletionResult.Failure
      assertEquals(
        setOf(missingStartedId),
        (missingStarted.error as ReadingProgressFailure.ChaptersNotFound)
          .chapterIds,
      )

      val missingCompletedId = ChapterId(uuid(995))
      val bothMissing = titles.completeChapterBoundary(
        ChapterBoundaryCompletion(
          completedChapterId = missingCompletedId,
          startedPosition = ReadingPosition(
            titleId = firstTitleId,
            chapterId = missingStartedId,
            unitKind = ReadingContentUnitKind.PAGE,
            unitIndex = 0,
          ),
          recency = ProgressEventRecency.CURRENT,
        ),
      ) as CompletionResult.Failure
      assertEquals(
        setOf(missingCompletedId, missingStartedId),
        (bothMissing.error as ReadingProgressFailure.ChaptersNotFound)
          .chapterIds,
      )
      assertEquals(0, queryLong("SELECT COUNT(*) FROM read_chapters"))
    }

  private suspend fun createTitle(
    alias: SourceTitleAlias,
    addToLibrary: Boolean = false,
  ): TitleId {
    val titleId = titles.reconcileSourceTitle(
      ReconcileSourceTitle(
        alias = alias,
        displayName = "Title",
        description = null,
      ),
    )
    if (addToLibrary) {
      check(titles.addToLibrary(titleId) is LibraryAddResult.Success)
    }
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

  private fun position(
    titleId: TitleId,
    chapter: Chapter,
    unitKind: ReadingContentUnitKind = ReadingContentUnitKind.PAGE,
    unitIndex: Int,
  ): ReadingPosition = ReadingPosition(
    titleId = titleId,
    chapterId = chapter.id,
    unitKind = unitKind,
    unitIndex = unitIndex,
  )

  private suspend fun record(
    position: ReadingPosition,
    recency: ProgressEventRecency = ProgressEventRecency.CURRENT,
  ): ActualPositionResult = titles.recordActualPosition(
    ActualPositionUpdate(
      position = position,
      recency = recency,
    ),
  )

  private fun boundary(
    completed: Chapter,
    started: ReadingPosition,
    recency: ProgressEventRecency = ProgressEventRecency.CURRENT,
  ): ChapterBoundaryCompletion = ChapterBoundaryCompletion(
    completedChapterId = completed.id,
    startedPosition = started,
    recency = recency,
  )

  private suspend fun progress(titleId: TitleId): TitleReadingProgress =
    checkNotNull(titles.observeReadingProgress(titleId).first())

  private suspend fun assertCanonicalSnapshotPersisted(
    snapshot: CanonicalChapterSnapshot,
  ) {
    assertEquals(
      snapshot.chapters,
      progress(snapshot.titleId).canonicalChapters.map { it.chapter },
    )
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
    const val FLOW_TIMEOUT_MILLIS = 5_000L
    val FIRST_CHAPTER_ID: UUID = uuid(1)
    val SECOND_CHAPTER_ID: UUID = uuid(2)
    val UNKNOWN_TITLE_ID: UUID = uuid(999)

    private fun uuid(index: Int): UUID = UUID.fromString(
      "00000000-0000-7000-8000-${index.toString(16).padStart(12, '0')}",
    )
  }
}
