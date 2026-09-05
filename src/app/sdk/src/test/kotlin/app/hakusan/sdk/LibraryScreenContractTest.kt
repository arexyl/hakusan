package app.hakusan.sdk

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class LibraryScreenContractTest {
  private val first = title(TITLE_ID_1, "first", "First")
  private val second = title(TITLE_ID_2, "second", "Second")

  @Test
  fun `Library owns normalized data and preserves explicit shelf order`() {
    val firstShelfIds = mutableListOf(second.id, first.id)
    val firstShelf = LibraryShelfItem.of(
      id = ScreenShelfId(2),
      name = "Second shelf",
      titleIds = firstShelfIds,
    )
    val secondShelf = LibraryShelfItem.of(
      id = ScreenShelfId(1),
      name = "First shelf",
      titleIds = listOf(first.id),
    )
    val mutableTitles = linkedMapOf(
      first.id to first,
      second.id to second,
    )
    val mutableShelves = mutableListOf(firstShelf, secondShelf)
    val screen = LibraryScreen.of(mutableTitles, mutableShelves)

    firstShelfIds.clear()
    mutableTitles.clear()
    mutableShelves.reverse()

    assertEquals(listOf(firstShelf, secondShelf), screen.shelves)
    assertEquals(listOf(second.id, first.id), firstShelf.titleIds)
    assertEquals(2, firstShelf.titleCount)
    assertEquals(
      mapOf(first.id to first, second.id to second),
      screen.titlesById,
    )
    assertThrows(UnsupportedOperationException::class.java) {
      (screen.shelves as MutableList).clear()
    }
    assertThrows(UnsupportedOperationException::class.java) {
      (firstShelf.titleIds as MutableList).clear()
    }
    assertThrows(UnsupportedOperationException::class.java) {
      (screen.titlesById as MutableMap).clear()
    }
  }

  @Test
  fun `empty Library and empty stored shelf are valid`() {
    assertEquals(
      LibraryScreen.of(emptyMap(), emptyList()),
      LibraryScreen.of(emptyMap(), emptyList()),
    )
    val emptyShelf = LibraryShelfItem.of(
      id = ScreenShelfId(1),
      name = "Empty",
      titleIds = emptyList(),
    )

    assertEquals(
      listOf(emptyShelf),
      LibraryScreen.of(emptyMap(), listOf(emptyShelf)).shelves,
    )
  }

  @Test
  fun `Library permits shared titles but rejects identity contradictions`() {
    val firstShelf = shelf(1, listOf(first.id))
    val secondShelf = shelf(2, listOf(first.id))
    val valid = LibraryScreen.of(
      titlesById = mapOf(first.id to first),
      shelves = listOf(firstShelf, secondShelf),
    )

    assertEquals(2, valid.shelves.size)
    assertThrows(IllegalArgumentException::class.java) {
      LibraryShelfItem.of(
        id = ScreenShelfId(1),
        name = "Duplicate title",
        titleIds = listOf(first.id, first.id),
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      LibraryScreen.of(
        titlesById = mapOf(first.id to first),
        shelves = listOf(
          firstShelf,
          LibraryShelfItem.of(
            id = firstShelf.id,
            name = "Renamed",
            titleIds = firstShelf.titleIds,
          ),
        ),
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      LibraryScreen.of(
        titlesById = mapOf(first.id to first),
        shelves = listOf(shelf(1, listOf(second.id))),
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      LibraryScreen.of(
        titlesById = mapOf(second.id to first),
        shelves = listOf(shelf(1, listOf(second.id))),
      )
    }
  }

  @Test
  fun `Library progress validates counts and resume availability`() {
    val progress = LibraryTitleProgress(
      chapterCount = 3,
      readChapterCount = 2,
      resumeState = LibraryResumeState.AVAILABLE,
    )

    assertEquals(2, progress.readChapterCount)
    assertThrows(IllegalArgumentException::class.java) {
      LibraryTitleProgress(
        chapterCount = -1,
        readChapterCount = 0,
        resumeState = LibraryResumeState.NONE,
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      LibraryTitleProgress(
        chapterCount = 1,
        readChapterCount = 2,
        resumeState = LibraryResumeState.TEMPORARILY_UNAVAILABLE,
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      LibraryTitleProgress(
        chapterCount = 1,
        readChapterCount = 1,
        resumeState = LibraryResumeState.AVAILABLE,
      )
    }
  }

  private fun title(
    id: UUID,
    sourceKey: String,
    displayName: String,
  ): LibraryTitleItem = LibraryTitleItem(
    id = ScreenTitleId(id),
    key = ScreenTitleKey(ScreenSourceId("source"), sourceKey),
    displayName = displayName,
    description = null,
    progress = LibraryTitleProgress(
      chapterCount = 3,
      readChapterCount = 1,
      resumeState = LibraryResumeState.NONE,
    ),
  )

  private fun shelf(
    id: Long,
    titleIds: List<ScreenTitleId>,
  ): LibraryShelfItem = LibraryShelfItem.of(
    id = ScreenShelfId(id),
    name = "Shelf $id",
    titleIds = titleIds,
  )

  private companion object {
    val TITLE_ID_1: UUID =
      UUID.fromString("00000000-0000-7000-8000-000000000001")
    val TITLE_ID_2: UUID =
      UUID.fromString("00000000-0000-7000-8000-000000000002")
  }
}
