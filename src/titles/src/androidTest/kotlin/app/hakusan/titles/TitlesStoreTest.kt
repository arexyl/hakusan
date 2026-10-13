package app.hakusan.titles

import android.content.Context
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The file-backed store that [TitlesStore.open] opens: its file and schema
 * version, persistence across close and reopen, observable updates, and
 * concurrent first Adds. Each test starts and ends without a database file.
 * In-memory Room tests do not substitute for these.
 */
@RunWith(AndroidJUnit4::class)
class TitlesStoreTest {
  private val context: Context = ApplicationProvider.getApplicationContext()

  @Before
  @After
  fun deleteDatabase() {
    context.deleteDatabase(DATABASE_NAME)
  }

  @Test
  fun reopenedStoreKeepsItsFileAndSchemaVersion1(): Unit = runBlocking {
    TitlesStore.open(context).use { store ->
      assertEquals(1L, store.schemaVersion())
      store.insertCategory("Kept")
    }

    assertTrue(context.getDatabasePath(DATABASE_NAME).isFile)

    TitlesStore.open(context).use { store ->
      assertEquals(1L, store.schemaVersion())
      assertEquals(listOf("Kept"), store.categoryNames())
    }
  }

  /**
   * One scenario per operation family, through the Titles surface: reconcile,
   * Add, an actual position, a chapter boundary, and final completions in and
   * outside the Library. After close and reopen, the progress reads and the
   * first values of both observations equal those before closing.
   */
  @Test
  fun everyOperationFamilySurvivesCloseAndReopen(): Unit = runBlocking {
    val before =
      TitlesStore.open(context).use { store ->
        val titles = store.titles

        val kept = titles.reconcileTitle(observation("kept", "a", "b", "c"))

        val outside = titles.reconcileTitle(observation("outside", "x"))

        val (a, b, c) = kept.chapterIds

        val add = titles.addToLibrary(kept.titleId)

        assertTrue(add is LibraryAddResult.Success)

        assertEquals(
          ActualPositionResult.Persisted,
          titles.record(kept.titleId, a, 4),
        )

        assertEquals(
          ChapterBoundaryResult.Completed,
          titles.boundary(kept.titleId, a, b, 2),
        )

        assertEquals(
          ActualPositionResult.Persisted,
          titles.record(kept.titleId, b, 5),
        )

        assertEquals(
          FinalCompletionResult.Completed,
          titles.finish(kept.titleId, c),
        )

        assertEquals(
          FinalCompletionResult.Completed,
          titles.finish(outside.titleId, outside.chapterIds.single()),
        )

        StoreState.read(titles, kept.titleId, outside.titleId)
      }

    assertTrue(context.getDatabasePath(DATABASE_NAME).isFile)

    val after =
      TitlesStore.open(context).use { store ->
        StoreState.read(
          store.titles,
          before.kept.titleId,
          before.outside.titleId,
        )
      }

    assertEquals(before, after)
    assertEquals(listOf(true, false, true), after.kept.readFlags())
    assertEquals(listOf(true), after.outside.readFlags())
    assertTrue(after.kept.isInLibrary)
    assertFalse(after.outside.isInLibrary)

    assertEquals(
      ResumeTarget.Available(
        chapter = after.kept.chapters[1].chapter,
        unitKind = ContentUnitKind.PAGE,
        unitIndex = 5,
      ),
      after.kept.resumeTarget,
    )

    assertEquals(
      "Description kept",
      after.library.titles.getValue(after.kept.titleId).description,
    )

    assertEquals(
      CompactProgress(3, 2, ResumeAvailability.AVAILABLE),
      after.library.titles.getValue(after.kept.titleId).progress,
    )

    assertEquals(
      setOf("Default" to setOf(after.kept.titleId)),
      after.library.shelves.mapTo(HashSet()) { shelf ->
        shelf.category.name to shelf.titleIds
      },
    )

    assertEquals(setOf(after.kept.titleId), after.members)
  }

  /**
   * Active observations of the file-backed store emit what a write through the
   * same store commits. After the position write, the next membership value is
   * the later Add, so the membership observation emitted no changed set. That
   * its query never reruns for progress writes is proven by
   * LibraryObservationTest.membershipQueryNeverRerunsForProgressAndChapterWrites.
   * The collections end inside the store's lifetime, also when an assertion
   * fails.
   */
  @Test
  fun activeObservationsEmitCommittedWrites(): Unit = runBlocking {
    TitlesStore.open(context).use { store ->
      coroutineScope { observeCommittedWrites(store.titles) }
    }
  }

  private suspend fun CoroutineScope.observeCommittedWrites(titles: Titles) {
    val first = titles.reconcileTitle(observation("first", "a", "b"))

    val second = titles.reconcileTitle(observation("second", "x"))

    val library = ObservedValues(this, titles.observeLibrary())

    val members = ObservedValues(this, titles.observeLibraryTitleIds())

    assertEquals(emptyMap<TitleId, LibraryTitle>(), library.next().titles)
    assertEquals(emptySet<TitleId>(), members.next())

    assertTrue(titles.addToLibrary(first.titleId) is LibraryAddResult.Success)

    assertEquals(
      mapOf(first.titleId to CompactProgress(2, 0, ResumeAvailability.NONE)),
      library.next().progress(),
    )

    assertEquals(setOf(first.titleId), members.next())

    assertEquals(
      ActualPositionResult.Persisted,
      titles.record(first.titleId, first.chapterIds.first(), 1),
    )

    assertEquals(
      mapOf(
        first.titleId to CompactProgress(2, 0, ResumeAvailability.AVAILABLE)
      ),
      library.next().progress(),
    )

    assertTrue(titles.addToLibrary(second.titleId) is LibraryAddResult.Success)

    assertEquals(
      setOf(first.titleId, second.titleId),
      library.next().titles.keys,
    )

    assertEquals(setOf(first.titleId, second.titleId), members.next())

    library.cancel()
    members.cancel()
  }

  /**
   * Concurrent first Adds of two titles on the file-backed store share one
   * Default.
   */
  @Test
  fun concurrentFirstAddsShareOneDefault(): Unit = runBlocking {
    TitlesStore.open(context).use { store ->
      val titles = store.titles

      val titleIds =
        listOf("first", "second").map { key ->
          titles.reconcileTitle(observation(key, "a")).titleId
        }

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

      val categoryIds = results.map { result ->
        (result as LibraryAddResult.Success).membership.categoryIds
      }

      val library =
        withTimeout(TIMEOUT_MILLIS) { titles.observeLibrary().first() }

      val shelf = library.shelves.single()

      assertEquals(1, categoryIds.toSet().size)
      assertEquals(setOf(shelf.category.id), categoryIds.first())
      assertEquals("Default", shelf.category.name)
      assertEquals(titleIds.toSet(), shelf.titleIds)
    }
  }

  private fun observation(
    key: String,
    vararg chapters: String,
  ): AcceptedTitleObservation {
    val alias = SourceTitleAlias.of("source", key)

    val accepted = chapters.map { chapter ->
      AcceptedChapter(
        SourceChapterAlias(alias, TitleLocalChapterKey.of(chapter)),
        "Chapter $chapter",
      )
    }

    return AcceptedTitleObservation.of(
      alias,
      "Title $key",
      "Description $key",
      accepted,
    )
  }

  private suspend fun Titles.record(
    titleId: TitleId,
    chapterId: ChapterId,
    unitIndex: Int,
  ): ActualPositionResult {
    val position =
      ReadingPosition.of(titleId, chapterId, ContentUnitKind.PAGE, unitIndex)

    return recordActualPosition(
      ActualPositionUpdate(position, ProgressEventRecency.CURRENT)
    )
  }

  private suspend fun Titles.boundary(
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

    return completeChapterBoundary(completion)
  }

  private suspend fun Titles.finish(
    titleId: TitleId,
    chapterId: ChapterId,
  ): FinalCompletionResult =
    completeFinalChapter(FinalChapterCompletion(titleId, chapterId))

  private suspend fun TitlesStore.schemaVersion(): Long =
    database.useReaderConnection { connection ->
      connection.usePrepared("PRAGMA user_version") { statement ->
        statement.step()

        statement.getLong(0)
      }
    }

  private suspend fun TitlesStore.insertCategory(name: String) {
    database.useWriterConnection { connection ->
      connection.usePrepared("INSERT INTO categories (name) VALUES (?)") {
        statement ->
        statement.bindText(1, name)
        statement.step()
      }
    }
  }

  private suspend fun TitlesStore.categoryNames(): List<String> =
    database.useReaderConnection { connection ->
      connection.usePrepared("SELECT name FROM categories") { statement ->
        buildList {
          while (statement.step()) {
            add(statement.getText(0))
          }
        }
      }
    }

  private val ProgressSnapshot.chapterIds: List<ChapterId>
    get() = chapters.map { state -> state.chapter.id }

  private fun ProgressSnapshot.readFlags(): List<Boolean> =
    chapters.map(ChapterReadingState::isRead)

  private fun LibrarySnapshot.progress(): Map<TitleId, CompactProgress> =
    titles.mapValues { (_, title) ->
      title.progress
    }

  /**
   * What the Titles surface reports about the titles [kept] and [outside]:
   * their progress reads and the first values of both observations.
   */
  private data class StoreState(
    val kept: ProgressSnapshot,
    val outside: ProgressSnapshot,
    val library: LibrarySnapshot,
    val members: Set<TitleId>,
  ) {
    companion object {
      suspend fun read(
        titles: Titles,
        kept: TitleId,
        outside: TitleId,
      ): StoreState =
        withTimeout(TIMEOUT_MILLIS) {
          StoreState(
            kept = checkNotNull(titles.readProgress(kept)),
            outside = checkNotNull(titles.readProgress(outside)),
            library = titles.observeLibrary().first(),
            members = titles.observeLibraryTitleIds().first(),
          )
        }
    }
  }

  private companion object {
    const val TIMEOUT_MILLIS = 10_000L
  }
}
