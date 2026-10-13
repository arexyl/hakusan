package app.hakusan.titles

import androidx.room3.Room
import androidx.room3.executeSQL
import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.hakusan.titles.StoredStateViolationKind.ALIAS
import app.hakusan.titles.StoredStateViolationKind.CANONICAL_ORDER
import app.hakusan.titles.StoredStateViolationKind.IDENTITY
import app.hakusan.titles.StoredStateViolationKind.MEMBERSHIP
import app.hakusan.titles.StoredStateViolationKind.OWNERSHIP
import app.hakusan.titles.StoredStateViolationKind.RESUME_STATE
import app.hakusan.titles.StoredStateViolationKind.STORAGE_KEY
import app.hakusan.titles.storage.RoomTitles
import app.hakusan.titles.storage.TitlesDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Corruption propagation on in-memory Room databases: a contradictory stored
 * row raises the typed stored-state violation from the one-shot progress read
 * and from every collector of both Library observations.
 *
 * Each case seeds a fresh database with two Library titles, `broken` and
 * `intact`, then injects one [Corruption] with raw SQL. A collector fails both
 * when the corruption precedes its collection and when a raw write introduces
 * it after a valid first emission, and it never emits a value instead. The
 * results of each path are compared as a whole, so one run reports every case.
 *
 * Existing tests prove further cases: ProgressReadTest a resume position on a
 * read chapter, a resume position outside the Library, and a canonical gap;
 * LibraryObservationTest a resume position on a read chapter and a blank source
 * title key before collection, and a canonical gap.
 */
@RunWith(AndroidJUnit4::class)
class CorruptionPropagationTest {
  /**
   * The one-shot read raises each kind it can observe for the corrupted title,
   * and the read of the other title still returns its unchanged progress.
   */
  @Test
  fun progressReadRaisesEachKindAndOtherTitlesStillRead(): Unit = runBlocking {
    assertEquals(
      StoredStateViolationKind.entries.toSet() - MEMBERSHIP,
      PROGRESS_READ_CASES.kinds(),
    )

    val kinds = PROGRESS_READ_CASES.associateWith { corruption ->
      withSeededStore {
        val intactBefore = titles.readProgress(intact)

        inject(corruption)

        val violation = violationOf { titles.readProgress(broken) }

        val intactAfter = titles.readProgress(intact)

        assertEquals(corruption.name, intactBefore, intactAfter)

        violation.kind
      }
    }

    assertEquals(PROGRESS_READ_CASES.associateWith(Corruption::kind), kinds)
  }

  /**
   * A Library collection that starts on corrupted rows fails as a whole, even
   * though the other title is intact, and emits no snapshot first.
   */
  @Test
  fun libraryCollectorFailsOnCorruptionBeforeItStarts(): Unit = runBlocking {
    assertEquals(
      StoredStateViolationKind.entries.toSet(),
      LIBRARY_CASES.kinds(),
    )

    val kinds = LIBRARY_CASES.associateWith { corruption ->
      withSeededStore {
        inject(corruption)

        ObservedValues(this, titles.observeLibrary()).failure().kind
      }
    }

    assertEquals(LIBRARY_CASES.associateWith(Corruption::kind), kinds)
  }

  /**
   * An active Library collection fails when a raw write corrupts the rows after
   * its valid first snapshot, and emits no snapshot of the corrupted rows.
   */
  @Test
  fun libraryCollectorFailsWhenAWriteCorruptsItsRows(): Unit = runBlocking {
    val kinds = LIBRARY_CASES.associateWith { corruption ->
      withSeededStore {
        relax(corruption)

        val library = ObservedValues(this, titles.observeLibrary())

        assertEquals(setOf(broken, intact), library.next().titles.keys)

        corrupt(corruption)

        library.failure().kind
      }
    }

    assertEquals(LIBRARY_CASES.associateWith(Corruption::kind), kinds)
  }

  /**
   * A membership collection that starts on corrupted rows fails as a whole and
   * emits no set first.
   */
  @Test
  fun membershipCollectorFailsOnCorruptionBeforeItStarts(): Unit = runBlocking {
    assertEquals(
      StoredStateViolationKind.entries.toSet() -
        setOf(OWNERSHIP, CANONICAL_ORDER, RESUME_STATE),
      MEMBERSHIP_CASES.kinds(),
    )

    val kinds = MEMBERSHIP_CASES.associateWith { corruption ->
      withSeededStore {
        inject(corruption)

        ObservedValues(this, titles.observeLibraryTitleIds()).failure().kind
      }
    }

    assertEquals(MEMBERSHIP_CASES.associateWith(Corruption::kind), kinds)
  }

  /**
   * An active membership collection fails when a raw write corrupts the rows
   * after its valid first set, and emits no set of the corrupted rows.
   */
  @Test
  fun membershipCollectorFailsWhenAWriteCorruptsItsRows(): Unit = runBlocking {
    val kinds = MEMBERSHIP_CASES.associateWith { corruption ->
      withSeededStore {
        relax(corruption)

        val members = ObservedValues(this, titles.observeLibraryTitleIds())

        assertEquals(setOf(broken, intact), members.next())

        corrupt(corruption)

        members.failure().kind
      }
    }

    assertEquals(MEMBERSHIP_CASES.associateWith(Corruption::kind), kinds)
  }

  /**
   * Opens a fresh in-memory database, seeds it, runs [block], and closes the
   * database. Collections that [block] starts in its scope end before the
   * database closes.
   */
  private suspend fun <T> withSeededStore(
    block: suspend SeededStore.() -> T
  ): T {
    val database =
      Room.inMemoryDatabaseBuilder<TitlesDatabase>()
        .setDriver(AndroidSQLiteDriver())
        .build()

    try {
      val titles = RoomTitles(database)

      val broken = seedMember(titles, "broken", "a", "b", "c")

      val intact = seedMember(titles, "intact", "x", "y")

      return coroutineScope {
        SeededStore(this, database, titles, broken, intact).block()
      }
    } finally {
      database.close()
    }
  }

  /**
   * Reconciles the title [key] with [chapters], adds it to the Library, and
   * completes the boundary from its first to its second chapter. The first
   * chapter is then read, and the second holds the resume position.
   */
  private suspend fun seedMember(
    titles: Titles,
    key: String,
    vararg chapters: String,
  ): TitleId {
    val alias = SourceTitleAlias.of("source", key)

    val accepted = chapters.map { chapter ->
      AcceptedChapter(
        SourceChapterAlias(alias, TitleLocalChapterKey.of(chapter)),
        "Chapter $chapter",
      )
    }

    val snapshot =
      titles.reconcileTitle(
        AcceptedTitleObservation.of(alias, "Title $key", null, accepted)
      )

    val (first, second) = snapshot.chapters.map { state -> state.chapter.id }

    val position =
      ReadingPosition.of(snapshot.titleId, second, ContentUnitKind.PAGE, 1)

    val completion =
      ChapterBoundaryCompletion.of(
        first,
        position,
        ProgressEventRecency.CURRENT,
      )

    val add = titles.addToLibrary(snapshot.titleId)

    assertTrue(add is LibraryAddResult.Success)

    assertEquals(
      ChapterBoundaryResult.Completed,
      titles.completeChapterBoundary(completion),
    )

    return snapshot.titleId
  }

  /** Runs [read] and returns the violation that it raises. */
  private suspend fun violationOf(
    read: suspend () -> Unit
  ): StoredStateViolationException {
    try {
      read()
    } catch (violation: StoredStateViolationException) {
      return violation
    }

    throw AssertionError("The read raised no stored-state violation.")
  }

  private fun List<Corruption>.kinds(): Set<StoredStateViolationKind> =
    mapTo(HashSet(), Corruption::kind)

  /**
   * A seeded in-memory database with the Library titles [broken] and [intact].
   * [scope] owns the collections of one case.
   */
  private class SeededStore(
    scope: CoroutineScope,
    private val database: TitlesDatabase,
    val titles: Titles,
    val broken: TitleId,
    val intact: TitleId,
  ) : CoroutineScope by scope {
    /** Relaxes the schema for [corruption], then writes it. */
    suspend fun inject(corruption: Corruption) {
      relax(corruption)
      corrupt(corruption)
    }

    /**
     * Drops the test database constraint that [corruption] needs to bypass.
     * Dropping it changes no row, so no observation reruns.
     */
    suspend fun relax(corruption: Corruption) {
      val relaxation = corruption.relaxation ?: return

      database.useWriterConnection { connection ->
        connection.executeSQL(relaxation)
      }
    }

    /**
     * Writes the rows of [corruption] in one transaction, so an active
     * observation reruns once, on the corrupted state. Foreign keys are off for
     * exactly that transaction when [corruption] needs it.
     */
    suspend fun corrupt(corruption: Corruption) {
      database.useWriterConnection { connection ->
        if (!corruption.enforcesForeignKeys) {
          connection.executeSQL("PRAGMA foreign_keys = OFF")
        }

        try {
          connection.immediateTransaction {
            corruption.statements.forEach { sql -> executeSQL(sql) }
          }
        } finally {
          if (!corruption.enforcesForeignKeys) {
            connection.executeSQL("PRAGMA foreign_keys = ON")
          }
        }
      }
    }
  }

  /**
   * One contradictory stored state of the title `broken`, and the [kind] that
   * every path that reads it must raise.
   *
   * [relaxation] drops a unique index of the test database when the state needs
   * one, and [enforcesForeignKeys] is false when the state breaks a foreign
   * key. The production schema never changes.
   */
  private enum class Corruption(
    val kind: StoredStateViolationKind,
    val statements: List<String>,
    val relaxation: String? = null,
    val enforcesForeignKeys: Boolean = true,
  ) {
    /** A Library title shares the identity of `broken`. */
    DUPLICATE_TITLE_IDENTITY(
      kind = IDENTITY,
      relaxation = "DROP INDEX index_titles_id",
      statements =
        listOf(
          "INSERT INTO titles (id, source_identity, source_title_key, " +
            "display_name, description) SELECT id, 'source', 'copy', " +
            "'Copy', NULL FROM titles WHERE source_title_key = 'broken'",
          "INSERT INTO title_categories (title_storage_id, category_id) " +
            "SELECT storage_id, $DEFAULT_CATEGORY FROM titles " +
            "WHERE source_title_key = 'copy'",
        ),
    ),

    /** The final chapter of `broken` has the storage key 0. */
    ZERO_CHAPTER_KEY(
      kind = STORAGE_KEY,
      statements =
        listOf(
          "UPDATE chapters SET storage_id = 0 WHERE source_chapter_key = 'c' " +
            "AND title_storage_id = $BROKEN"
        ),
    ),

    /** `broken` is also associated with a category whose identity is 0. */
    ZERO_CATEGORY_ASSOCIATION(
      kind = STORAGE_KEY,
      statements =
        listOf(
          "INSERT INTO categories (id, name) VALUES (0, 'Zero')",
          "INSERT INTO title_categories (title_storage_id, category_id) " +
            "VALUES ($BROKEN, 0)",
        ),
    ),

    /** The source identity of `broken` is blank. */
    BLANK_SOURCE_IDENTITY(
      kind = ALIAS,
      statements =
        listOf(
          "UPDATE titles SET source_identity = ' ' " +
            "WHERE source_title_key = 'broken'"
        ),
    ),

    /** The resume position of `broken` names a chapter of `intact`. */
    FOREIGN_RESUME_CHAPTER(
      kind = OWNERSHIP,
      enforcesForeignKeys = false,
      statements =
        listOf(
          "UPDATE library_resume_positions SET chapter_storage_id = " +
            "(SELECT storage_id FROM chapters " +
            "WHERE source_chapter_key = 'x') " +
            "WHERE title_storage_id = $BROKEN"
        ),
    ),

    /** Two chapters of `broken` share one canonical index. */
    REPEATED_CANONICAL_INDEX(
      kind = CANONICAL_ORDER,
      relaxation = "DROP INDEX index_chapters_title_canonical",
      statements =
        listOf(
          "UPDATE chapters SET canonical_index = 1 " +
            "WHERE source_chapter_key = 'c' AND title_storage_id = $BROKEN"
        ),
    ),

    /** The resume position of `broken` has an unknown unit kind. */
    UNKNOWN_UNIT_KIND(
      kind = RESUME_STATE,
      statements =
        listOf(
          "UPDATE library_resume_positions SET unit_kind = 'SCROLL' " +
            "WHERE title_storage_id = $BROKEN"
        ),
    ),

    /** A category association names a title that does not exist. */
    DANGLING_ASSOCIATION(
      kind = MEMBERSHIP,
      enforcesForeignKeys = false,
      statements =
        listOf(
          "INSERT INTO title_categories (title_storage_id, category_id) " +
            "SELECT max(storage_id) + 1, $DEFAULT_CATEGORY FROM titles"
        ),
    ),
  }

  private companion object {
    /**
     * The progress read cannot observe [MEMBERSHIP]: it tests membership by
     * association existence and reads no association row.
     */
    val PROGRESS_READ_CASES =
      listOf(
        Corruption.DUPLICATE_TITLE_IDENTITY,
        Corruption.ZERO_CHAPTER_KEY,
        Corruption.BLANK_SOURCE_IDENTITY,
        Corruption.FOREIGN_RESUME_CHAPTER,
        Corruption.REPEATED_CANONICAL_INDEX,
        Corruption.UNKNOWN_UNIT_KIND,
      )

    /** The Library observation can observe every kind. */
    val LIBRARY_CASES =
      listOf(
        Corruption.DUPLICATE_TITLE_IDENTITY,
        Corruption.ZERO_CATEGORY_ASSOCIATION,
        Corruption.BLANK_SOURCE_IDENTITY,
        Corruption.FOREIGN_RESUME_CHAPTER,
        Corruption.REPEATED_CANONICAL_INDEX,
        Corruption.UNKNOWN_UNIT_KIND,
        Corruption.DANGLING_ASSOCIATION,
      )

    /**
     * The membership observation reads only titles and their associations. It
     * cannot observe [CANONICAL_ORDER] or [RESUME_STATE], and its join makes
     * the association and its title agree, so it cannot observe [OWNERSHIP].
     */
    val MEMBERSHIP_CASES =
      listOf(
        Corruption.DUPLICATE_TITLE_IDENTITY,
        Corruption.ZERO_CATEGORY_ASSOCIATION,
        Corruption.BLANK_SOURCE_IDENTITY,
        Corruption.DANGLING_ASSOCIATION,
      )
  }
}

/** The storage key of the title `broken`, as an SQL expression. */
private const val BROKEN =
  "(SELECT storage_id FROM titles WHERE source_title_key = 'broken')"

/** The identity of the category Default, as an SQL expression. */
private const val DEFAULT_CATEGORY =
  "(SELECT id FROM categories WHERE name = 'Default')"
