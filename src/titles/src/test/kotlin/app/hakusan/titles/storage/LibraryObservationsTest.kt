package app.hakusan.titles.storage

import app.hakusan.titles.CategoryId
import app.hakusan.titles.CompactProgress
import app.hakusan.titles.LibraryCategory
import app.hakusan.titles.LibraryTitle
import app.hakusan.titles.ResumeAvailability
import app.hakusan.titles.SourceTitleAlias
import app.hakusan.titles.StoredStateViolationException
import app.hakusan.titles.StoredStateViolationKind
import app.hakusan.titles.StoredStateViolationKind.ALIAS
import app.hakusan.titles.StoredStateViolationKind.CANONICAL_ORDER
import app.hakusan.titles.StoredStateViolationKind.IDENTITY
import app.hakusan.titles.StoredStateViolationKind.MEMBERSHIP
import app.hakusan.titles.StoredStateViolationKind.OWNERSHIP
import app.hakusan.titles.StoredStateViolationKind.RESUME_STATE
import app.hakusan.titles.StoredStateViolationKind.STORAGE_KEY
import app.hakusan.titles.TitleId
import java.util.Collections
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LibraryObservationsTest {
  @Test
  fun titleInSeveralCategoriesAppearsOnceWithUnmultipliedProgress() {
    val available = position(chapter = 7, index = 3)

    val snapshot =
      mapLibrarySnapshot(
        listOf(
          member(TITLE_A, category = 1, chapters = 3, reads = 1, available),
          member(TITLE_B, category = 1),
          empty(category = 3),
          member(TITLE_A, category = 2, chapters = 3, reads = 1, available),
        )
      )

    assertEquals(setOf(A_ID, B_ID), snapshot.titles.keys)

    assertEquals(
      LibraryTitle(
        alias = SourceTitleAlias.of("source", "key-$A_KEY"),
        displayName = "Title $A_KEY",
        description = "Description $A_KEY",
        progress = CompactProgress(3, 1, ResumeAvailability.AVAILABLE),
      ),
      snapshot.titles.getValue(A_ID),
    )

    assertEquals(
      CompactProgress(0, 0, ResumeAvailability.NONE),
      snapshot.titles.getValue(B_ID).progress,
    )

    assertEquals(
      mapOf(
        category(1) to setOf(A_ID, B_ID),
        category(2) to setOf(A_ID),
        category(3) to emptySet(),
      ),
      snapshot.shelves.associate { shelf -> shelf.category to shelf.titleIds },
    )
  }

  @Test
  fun everyCategoryWithoutTitlesIsAnEmptyShelf() {
    val snapshot = mapLibrarySnapshot(listOf(empty(4), empty(5)))

    assertTrue(snapshot.titles.isEmpty())

    assertEquals(
      setOf(category(4), category(5)),
      snapshot.shelves.map { shelf -> shelf.category }.toSet(),
    )

    assertTrue(snapshot.shelves.all { shelf -> shelf.titleIds.isEmpty() })

    val none = mapLibrarySnapshot(emptyList())

    assertTrue(none.titles.isEmpty())
    assertTrue(none.shelves.isEmpty())
  }

  @Test
  fun resumeAvailabilityFollowsThePositionAndItsChapter() {
    val omitted = position(C_KEY, 8)

    val rows =
      listOf(
        member(TITLE_A, chapters = 2),
        member(TITLE_B, chapters = 2, position = position(B_KEY, 7)),
        member(
          TITLE_C,
          chapters = 2,
          position = omitted,
          canonicalIndex = null,
        ),
      )

    val snapshot = mapLibrarySnapshot(rows)

    assertEquals(
      listOf(
        ResumeAvailability.NONE,
        ResumeAvailability.AVAILABLE,
        ResumeAvailability.TEMPORARILY_UNAVAILABLE,
      ),
      listOf(A_ID, B_ID, C_ID).map { id ->
        snapshot.titles.getValue(id).progress.resumeAvailability
      },
    )
  }

  @Test
  fun snapshotCollectionsAreUnmodifiable() {
    val snapshot = mapLibrarySnapshot(listOf(member(TITLE_A), empty(2)))

    val memberShelf = snapshot.shelves.first { shelf -> shelf.titleIds.any() }

    assertThrows<UnsupportedOperationException> {
      (snapshot.titles as MutableMap<TitleId, LibraryTitle>).clear()
    }

    assertThrows<UnsupportedOperationException> {
      (snapshot.shelves as MutableSet<*>).clear()
    }

    assertThrows<UnsupportedOperationException> {
      (memberShelf.titleIds as MutableSet<TitleId>).clear()
    }
  }

  @Test
  fun columnViolationsMapToTheirKinds() {
    val cases =
      listOf(
        member(TITLE_A).copy(category = CategoryEntity(0, "Zero")) to
          STORAGE_KEY,
        member(TITLE_A).copy(association = TitleCategoryEntity(A_KEY, -1)) to
          STORAGE_KEY,
        member(TITLE_A.copy(id = ByteArray(15))) to IDENTITY,
        member(TITLE_A.copy(sourceTitleKey = " ")) to ALIAS,
        member(TITLE_A, chapters = -1) to MEMBERSHIP,
        member(TITLE_A, chapters = Int.MAX_VALUE + 1L) to MEMBERSHIP,
        member(TITLE_A, chapters = 1, reads = Int.MAX_VALUE + 1L) to MEMBERSHIP,
        member(TITLE_A, position = position(kind = "SCROLL")) to RESUME_STATE,
        member(TITLE_A, position = position(index = -1)) to RESUME_STATE,
        member(
          TITLE_A,
          chapters = 1,
          position = position(),
          canonicalIndex = -1,
        ) to CANONICAL_ORDER,
      )

    assertEquals(
      cases.map { (_, kind) -> kind },
      cases.map { (row, _) -> kindOf(listOf(row)) },
    )
  }

  @Test
  fun rowRelationViolationsMapToTheirKinds() {
    val cases =
      listOf(
        empty(1).copy(title = TITLE_A) to MEMBERSHIP,
        empty(1).copy(chapterCount = 1) to MEMBERSHIP,
        empty(1).copy(position = position()) to MEMBERSHIP,
        empty(1).copy(resumeChapterReadStorageId = 7) to MEMBERSHIP,
        empty(1).copy(lastCanonicalIndex = 0) to MEMBERSHIP,
        member(TITLE_A).copy(title = null) to MEMBERSHIP,
        member(TITLE_A).copy(association = TitleCategoryEntity(A_KEY, 2)) to
          OWNERSHIP,
        member(TITLE_A).copy(association = TitleCategoryEntity(B_KEY, 1)) to
          OWNERSHIP,
        member(TITLE_A, chapters = 3).copy(firstCanonicalIndex = 1) to
          CANONICAL_ORDER,
        member(TITLE_A, chapters = 3).copy(lastCanonicalIndex = 5) to
          CANONICAL_ORDER,
        member(TITLE_A, chapters = 3).copy(distinctCanonicalIndexCount = 2) to
          CANONICAL_ORDER,
        member(TITLE_A).copy(firstCanonicalIndex = 0) to CANONICAL_ORDER,
        member(TITLE_A, chapters = 1, reads = 2) to MEMBERSHIP,
        member(TITLE_A, chapters = 1, position = position(title = B_KEY)) to
          OWNERSHIP,
        member(TITLE_A, chapters = 1, position = position())
          .copy(resumeChapterStorageId = null) to OWNERSHIP,
        member(TITLE_A, chapters = 1, position = position())
          .copy(resumeChapterReadStorageId = 7) to RESUME_STATE,
        member(TITLE_A).copy(resumeChapterStorageId = 7) to RESUME_STATE,
        member(TITLE_A, chapters = 1, reads = 1, position = position()) to
          RESUME_STATE,
      )

    assertEquals(
      cases.map { (_, kind) -> kind },
      cases.map { (row, _) -> kindOf(listOf(row)) },
    )
  }

  @Test
  fun crossRowViolationsMapToTheirKinds() {
    val renamed = empty(1).copy(category = CategoryEntity(1, "Renamed"))

    val duplicateIdentity = TITLE_B.copy(id = TITLE_A.id)

    val cases =
      listOf(
        listOf(empty(1), renamed) to IDENTITY,
        listOf(member(TITLE_A, category = 1), renamed) to IDENTITY,
        listOf(empty(1), empty(1)) to IDENTITY,
        listOf(empty(1), member(TITLE_A, category = 1)) to MEMBERSHIP,
        listOf(member(TITLE_A, category = 1), empty(1)) to MEMBERSHIP,
        listOf(member(TITLE_A, 1), member(duplicateIdentity, 2)) to IDENTITY,
        listOf(
          member(TITLE_A, 1),
          member(TITLE_A.copy(sourceTitleKey = "other"), 2),
        ) to ALIAS,
        listOf(
          member(TITLE_A, 1),
          member(TITLE_A.copy(displayName = "Other"), 2),
        ) to IDENTITY,
        listOf(
          member(TITLE_A, 1),
          member(TITLE_A.copy(description = null), 2),
        ) to IDENTITY,
        listOf(
          member(TITLE_A, 1, chapters = 2),
          member(TITLE_A, 2, chapters = 3),
        ) to MEMBERSHIP,
        listOf(
          member(TITLE_A, 1, chapters = 2, reads = 1),
          member(TITLE_A, 2, chapters = 2, reads = 0),
        ) to MEMBERSHIP,
        listOf(
          member(TITLE_A, 1, chapters = 2, position = position()),
          member(TITLE_A, 2, chapters = 2),
        ) to RESUME_STATE,
        listOf(member(TITLE_A, 1), member(TITLE_A, 1)) to MEMBERSHIP,
      )

    assertEquals(
      cases.map { (_, kind) -> kind },
      cases.map { (rows, _) -> kindOf(rows) },
    )
  }

  @Test
  fun checksRunInPhaseOrder() {
    val repeated = listOf(empty(1), empty(1))

    val unowned = member(TITLE_B).copy(title = null)

    val malformed = member(TITLE_C.copy(storageId = 0))

    assertEquals(STORAGE_KEY, kindOf(repeated + unowned + malformed))
    assertEquals(MEMBERSHIP, kindOf(repeated + unowned))

    val firstOwnership =
      member(TITLE_A).copy(association = TitleCategoryEntity(A_KEY, 2))

    val laterAlias = member(TITLE_B.copy(sourceIdentity = ""))

    assertEquals(ALIAS, kindOf(listOf(firstOwnership, laterAlias)))
  }

  @Test
  fun violationMessagesCarryNoStoredValue() {
    val named = CategoryEntity(4_242, "name-category")

    val rows =
      listOf(
        listOf(empty(1), empty(1)),
        listOf(member(TITLE_A), member(TITLE_A)),
        listOf(
          member(TITLE_A).copy(category = named),
          member(TITLE_A).copy(category = named.copy(name = "other-name")),
        ),
        listOf(member(TITLE_A, chapters = Int.MAX_VALUE + 9L)),
        listOf(
          member(TITLE_A.copy(displayName = "name-title"), 1),
          member(TITLE_A, 2),
        ),
      )

    val messages = rows.map { read ->
      violationOf { mapLibrarySnapshot(read) }.message.orEmpty()
    }

    val forbidden =
      listOf("4242", "name-", "other-name", "$A_KEY", "2147483656", "key-")

    assertTrue(
      messages.none { message -> forbidden.any(message::contains) },
      messages.joinToString(),
    )
  }

  @Test
  fun libraryTitleIdsAreTheMemberIdentitiesOnce() {
    val rows =
      listOf(association(TITLE_A, 1), association(TITLE_B, 1)) +
        association(TITLE_A, 2)

    assertEquals(setOf(A_ID, B_ID), mapLibraryTitleIds(rows))

    assertTrue(mapLibraryTitleIds(emptyList()).isEmpty())
  }

  @Test
  fun libraryTitleIdViolationsMapToTheirKinds() {
    val cases =
      listOf(
        association(TITLE_A).copy(association = TitleCategoryEntity(0, 1)) to
          STORAGE_KEY,
        association(TITLE_A)
          .copy(association = TitleCategoryEntity(A_KEY, -1)) to STORAGE_KEY,
        association(TITLE_A.copy(id = ByteArray(17))) to IDENTITY,
        association(TITLE_A.copy(sourceTitleKey = " ")) to ALIAS,
        association(TITLE_A).copy(title = null) to MEMBERSHIP,
        association(TITLE_A)
          .copy(association = TitleCategoryEntity(B_KEY, 1)) to OWNERSHIP,
      )

    assertEquals(
      cases.map { (_, kind) -> kind },
      cases.map { (row, _) -> titleIdKindOf(listOf(row)) },
    )

    val duplicate = TITLE_B.copy(id = TITLE_A.id)

    val repeated = List(2) { association(TITLE_A, 1) }

    assertEquals(
      listOf(IDENTITY, MEMBERSHIP),
      listOf(
          listOf(association(TITLE_A), association(duplicate)),
          repeated,
        )
        .map(::titleIdKindOf),
    )
  }

  @Test
  fun libraryTitleIdChecksRunInPhaseOrder() {
    val duplicate =
      listOf(association(TITLE_A), association(TITLE_B.copy(id = TITLE_A.id)))

    val unowned = association(TITLE_B).copy(title = null)

    val blank = association(TITLE_C.copy(sourceIdentity = " "))

    val nonpositive =
      association(TITLE_C).copy(association = TitleCategoryEntity(-4, 1))

    assertEquals(ALIAS, titleIdKindOf(duplicate + unowned + blank))
    assertEquals(STORAGE_KEY, titleIdKindOf(duplicate + unowned + nonpositive))
    assertEquals(MEMBERSHIP, titleIdKindOf(duplicate + unowned))
  }

  @Test
  fun snapshotMappingRunsOnTheWorkerAndNotOnTheCollector() {
    val rows = RecordingList(listOf(member(TITLE_A), empty(2)))

    val collectors = Collections.synchronizedSet(HashSet<Thread>())

    val snapshots = onWorker { worker ->
      flowOf<List<LibraryRow>>(rows).toLibrarySnapshots(worker).toList().also {
        collectors += Thread.currentThread()
      }
    }

    assertEquals(1, snapshots.size)
    assertEquals(setOf(WORKER_NAME), rows.threads.map(Thread::getName).toSet())
    assertFalse(rows.threads.any(collectors::contains))
  }

  @Test
  fun titleIdMappingRunsOnTheWorkerAndNotOnTheCollector() {
    val rows = RecordingList(listOf(association(TITLE_A)))

    val collectors = Collections.synchronizedSet(HashSet<Thread>())

    val sets = onWorker { worker ->
      flowOf<List<LibraryTitleRow>>(rows)
        .toLibraryTitleIds(worker)
        .toList()
        .also {
          collectors += Thread.currentThread()
        }
    }

    assertEquals(listOf(setOf(A_ID)), sets)
    assertEquals(setOf(WORKER_NAME), rows.threads.map(Thread::getName).toSet())
    assertFalse(rows.threads.any(collectors::contains))
  }

  @Test
  fun equalConsecutiveSnapshotsAreSuppressedAfterMapping() {
    val first = listOf(member(TITLE_A, chapters = 2, position = position()))

    val moved =
      listOf(
        member(
          TITLE_A,
          chapters = 2,
          position = position(index = 9, updatedAt = 5),
        )
      )

    val read = listOf(member(TITLE_A, chapters = 2, reads = 1))

    val snapshots = onWorker { worker ->
      flowOf(first, moved, read, read).toLibrarySnapshots(worker).toList()
    }

    assertEquals(
      listOf(ResumeAvailability.AVAILABLE, ResumeAvailability.NONE),
      snapshots.map { snapshot ->
        snapshot.titles.getValue(A_ID).progress.resumeAvailability
      },
    )
  }

  @Test
  fun equalConsecutiveTitleIdSetsAreSuppressed() {
    val first = listOf(association(TITLE_A))

    val renamed = listOf(association(TITLE_A.copy(displayName = "Renamed")))

    val second = first + association(TITLE_A, 2)

    val added = second + association(TITLE_B)

    val sets = onWorker { worker ->
      flowOf(first, renamed, second, added).toLibraryTitleIds(worker).toList()
    }

    assertEquals(listOf(setOf(A_ID), setOf(A_ID, B_ID)), sets)
  }

  @Test
  fun violationsReachTheCollectorWithTheirKind() {
    val repeated = listOf(empty(1), empty(1))

    val blank = listOf(association(TITLE_A.copy(sourceTitleKey = "")))

    val snapshotViolation = violationOf {
      onWorker { worker ->
        flowOf(repeated).toLibrarySnapshots(worker).first()
      }
    }

    val titleIdViolation = violationOf {
      onWorker { worker -> flowOf(blank).toLibraryTitleIds(worker).first() }
    }

    assertEquals(IDENTITY, snapshotViolation.kind)
    assertEquals(ALIAS, titleIdViolation.kind)
  }

  private fun kindOf(rows: List<LibraryRow>): StoredStateViolationKind {
    val violation = violationOf { mapLibrarySnapshot(rows) }

    return violation.kind
  }

  private fun titleIdKindOf(
    rows: List<LibraryTitleRow>
  ): StoredStateViolationKind {
    val violation = violationOf { mapLibraryTitleIds(rows) }

    return violation.kind
  }

  private fun violationOf(read: () -> Unit): StoredStateViolationException =
    assertThrows<StoredStateViolationException> { read() }

  /**
   * Runs [block] with a single-thread worker named [WORKER_NAME], collecting on
   * the calling thread, and bounds it with a timeout.
   */
  private fun <T> onWorker(block: suspend (CoroutineDispatcher) -> T): T =
    Executors.newSingleThreadExecutor { task -> Thread(task, WORKER_NAME) }
      .asCoroutineDispatcher()
      .use { worker ->
        runBlocking { withTimeout(TIMEOUT_MILLIS) { block(worker) } }
      }

  /** A list that records every thread that reads one of its elements. */
  private class RecordingList<T>(private val elements: List<T>) :
    AbstractList<T>() {
    val threads: MutableSet<Thread> = Collections.synchronizedSet(HashSet())

    override val size: Int
      get() = elements.size

    override fun get(index: Int): T {
      threads += Thread.currentThread()

      return elements[index]
    }
  }

  private companion object {
    const val A_KEY = 11L
    const val B_KEY = 12L
    const val C_KEY = 13L
    const val WORKER_NAME = "library-worker"
    const val TIMEOUT_MILLIS = 10_000L

    val A_ID = TitleId.generate()
    val B_ID = TitleId.generate()
    val C_ID = TitleId.generate()

    val TITLE_A = title(A_KEY, A_ID)
    val TITLE_B = title(B_KEY, B_ID)
    val TITLE_C = title(C_KEY, C_ID)

    fun title(storageId: Long, id: TitleId): TitleEntity =
      TitleEntity(
        storageId = storageId,
        id = id.toStoredBytes(),
        sourceIdentity = "source",
        sourceTitleKey = "key-$storageId",
        displayName = "Title $storageId",
        description = "Description $storageId",
      )

    fun category(key: Long): LibraryCategory =
      LibraryCategory(checkNotNull(CategoryId.ofOrNull(key)), "Category $key")

    /** The row of [title] in [category] with its counts and position. */
    fun member(
      title: TitleEntity,
      category: Long = 1,
      chapters: Long = 0,
      reads: Long = 0,
      position: LibraryResumePositionEntity? = null,
      canonicalIndex: Int? = 0,
    ): LibraryRow =
      LibraryRow(
        category = CategoryEntity(category, "Category $category"),
        association = TitleCategoryEntity(title.storageId, category),
        title = title,
        chapterCount = chapters,
        readChapterCount = reads,
        distinctCanonicalIndexCount = chapters,
        firstCanonicalIndex = 0L.takeIf { chapters > 0 },
        lastCanonicalIndex = (chapters - 1).takeIf { chapters > 0 },
        position = position,
        resumeChapterStorageId = position?.chapterStorageId,
        resumeChapterCanonicalIndex =
          canonicalIndex.takeIf { position != null },
        resumeChapterReadStorageId = null,
      )

    /** The row of [category] without titles. */
    fun empty(category: Long): LibraryRow =
      LibraryRow(
        category = CategoryEntity(category, "Category $category"),
        association = null,
        title = null,
        chapterCount = 0,
        readChapterCount = 0,
        distinctCanonicalIndexCount = 0,
        firstCanonicalIndex = null,
        lastCanonicalIndex = null,
        position = null,
        resumeChapterStorageId = null,
        resumeChapterCanonicalIndex = null,
        resumeChapterReadStorageId = null,
      )

    /** The membership row of [title] in [category]. */
    fun association(title: TitleEntity, category: Long = 1): LibraryTitleRow =
      LibraryTitleRow(TitleCategoryEntity(title.storageId, category), title)

    /** A position of the title [title] on [chapter]. */
    fun position(
      title: Long = A_KEY,
      chapter: Long = 7,
      kind: String = "PAGE",
      index: Int = 0,
      updatedAt: Long = 0,
    ): LibraryResumePositionEntity =
      LibraryResumePositionEntity(
        titleStorageId = title,
        chapterStorageId = chapter,
        unitKind = kind,
        unitIndex = index,
        updatedAt = updatedAt,
      )
  }
}
