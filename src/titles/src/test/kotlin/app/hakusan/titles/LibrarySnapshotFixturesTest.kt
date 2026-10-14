package app.hakusan.titles

import app.hakusan.titles.ResumeAvailability.AVAILABLE
import app.hakusan.titles.ResumeAvailability.NONE
import app.hakusan.titles.ResumeAvailability.TEMPORARILY_UNAVAILABLE
import app.hakusan.titles.storage.CategoryEntity
import app.hakusan.titles.storage.LibraryResumePositionEntity
import app.hakusan.titles.storage.LibraryRow
import app.hakusan.titles.storage.TitleCategoryEntity
import app.hakusan.titles.storage.TitleEntity
import app.hakusan.titles.storage.mapLibrarySnapshot
import app.hakusan.titles.storage.toStoredBytes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows

class LibrarySnapshotFixturesTest {
  @Test
  fun snapshotMatchesTheReadBoundary() {
    val reading =
      libraryTitle(
        alias = alias(A_KEY),
        displayName = "Title A",
        description = "Description A",
        chapterCount = 3,
        readChapterCount = 1,
        resumeAvailability = AVAILABLE,
      )

    val paused =
      libraryTitle(
        alias = alias(B_KEY),
        displayName = "Title B",
        chapterCount = 2,
        readChapterCount = 2,
        resumeAvailability = TEMPORARILY_UNAVAILABLE,
      )

    val fresh = libraryTitle(alias = alias(C_KEY), displayName = "Title C")

    val snapshot =
      librarySnapshot(
        titles = mapOf(A_ID to reading, B_ID to paused, C_ID to fresh),
        shelves =
          mapOf(
            FIRST to setOf(A_ID, C_ID),
            SECOND to setOf(A_ID, B_ID),
            EMPTY to emptySet(),
          ),
      )

    val rows =
      listOf(
        member(FIRST, TITLE_A, chapters = 3, reads = 1, AVAILABLE),
        member(FIRST, TITLE_C),
        member(SECOND, TITLE_A, chapters = 3, reads = 1, AVAILABLE),
        member(
          SECOND,
          TITLE_B,
          chapters = 2,
          reads = 2,
          TEMPORARILY_UNAVAILABLE,
        ),
        empty(EMPTY),
      )

    assertEquals(mapLibrarySnapshot(rows), snapshot)
  }

  @Test
  fun emptyLibraryMatchesTheReadBoundary() {
    assertAll(
      {
        assertEquals(
          mapLibrarySnapshot(emptyList()),
          librarySnapshot(emptyMap(), emptyMap()),
        )
      },
      {
        assertEquals(
          mapLibrarySnapshot(listOf(empty(FIRST))),
          librarySnapshot(emptyMap(), mapOf(FIRST to emptySet())),
        )
      },
    )
  }

  @Test
  fun snapshotDoesNotFollowLaterChangesOfItsArguments() {
    val title = libraryTitle(alias = alias(A_KEY), displayName = "Title A")

    val titles = mutableMapOf(A_ID to title)

    val titleIds = mutableSetOf(A_ID)

    val snapshot = librarySnapshot(titles, mapOf(FIRST to titleIds))

    titles[B_ID] = title
    titleIds += B_ID

    assertEquals(setOf(A_ID), snapshot.titles.keys)
    assertEquals(setOf(A_ID), snapshot.shelves.single().titleIds)
  }

  @Test
  fun impossibleCompactProgressIsRejected() {
    val titleAlias = alias(A_KEY)

    assertAll(
      rejects { libraryTitle(titleAlias, "Title", chapterCount = -1) },
      rejects { libraryTitle(titleAlias, "Title", readChapterCount = -1) },
      rejects {
        libraryTitle(
          titleAlias,
          "Title",
          chapterCount = 1,
          readChapterCount = 2,
        )
      },
      rejects {
        libraryTitle(
          alias = titleAlias,
          displayName = "Title",
          chapterCount = 2,
          readChapterCount = 2,
          resumeAvailability = AVAILABLE,
        )
      },
    )
  }

  @Test
  fun impossibleShelvesAreRejected() {
    val title = libraryTitle(alias = alias(A_KEY), displayName = "Title A")

    val renamed = libraryCategory(FIRST.id, "Renamed")

    assertAll(
      rejects {
        librarySnapshot(
          titles = mapOf(A_ID to title),
          shelves = mapOf(FIRST to setOf(A_ID), renamed to setOf(A_ID)),
        )
      },
      rejects {
        librarySnapshot(
          titles = mapOf(A_ID to title),
          shelves = mapOf(FIRST to setOf(A_ID, B_ID)),
        )
      },
      rejects {
        librarySnapshot(
          titles = mapOf(A_ID to title, B_ID to title),
          shelves = mapOf(FIRST to setOf(A_ID), EMPTY to emptySet()),
        )
      },
    )
  }

  private fun rejects(build: () -> Unit): () -> Unit = {
    assertThrows<IllegalArgumentException> { build() }
  }

  private fun alias(storageId: Long): SourceTitleAlias =
    SourceTitleAlias.of("source", "key-$storageId")

  /**
   * The Library row of [title] in [category], with [chapters] current canonical
   * chapters, [reads] of them read, and a position of [availability].
   */
  private fun member(
    category: LibraryCategory,
    title: TitleEntity,
    chapters: Long = 0,
    reads: Long = 0,
    availability: ResumeAvailability = NONE,
  ): LibraryRow {
    val position =
      LibraryResumePositionEntity(
          titleStorageId = title.storageId,
          chapterStorageId = title.storageId * 10,
          unitKind = "PAGE",
          unitIndex = 0,
          updatedAt = 0,
        )
        .takeIf { availability != NONE }

    return LibraryRow(
      category = entity(category),
      association = TitleCategoryEntity(title.storageId, category.id.key),
      title = title,
      chapterCount = chapters,
      readChapterCount = reads,
      distinctCanonicalIndexCount = chapters,
      firstCanonicalIndex = 0L.takeIf { chapters > 0 },
      lastCanonicalIndex = (chapters - 1).takeIf { chapters > 0 },
      position = position,
      resumeChapterStorageId = position?.chapterStorageId,
      resumeChapterCanonicalIndex = 0.takeIf { availability == AVAILABLE },
      resumeChapterReadStorageId = null,
    )
  }

  /** The Library row of [category] without titles. */
  private fun empty(category: LibraryCategory): LibraryRow =
    LibraryRow(
      category = entity(category),
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

  private fun entity(category: LibraryCategory): CategoryEntity =
    CategoryEntity(category.id.key, category.name)

  private companion object {
    const val A_KEY = 11L
    const val B_KEY = 12L
    const val C_KEY = 13L

    val A_ID = TitleId.generate()
    val B_ID = TitleId.generate()
    val C_ID = TitleId.generate()

    val TITLE_A = title(A_KEY, A_ID, "A", "Description A")
    val TITLE_B = title(B_KEY, B_ID, "B", null)
    val TITLE_C = title(C_KEY, C_ID, "C", null)

    val FIRST = libraryCategory(categoryId(1), "First")
    val SECOND = libraryCategory(categoryId(2), "Second")
    val EMPTY = libraryCategory(categoryId(3), "Empty")

    fun title(
      storageId: Long,
      id: TitleId,
      name: String,
      description: String?,
    ): TitleEntity =
      TitleEntity(
        storageId = storageId,
        id = id.toStoredBytes(),
        sourceIdentity = "source",
        sourceTitleKey = "key-$storageId",
        displayName = "Title $name",
        description = description,
      )
  }
}
