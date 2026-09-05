package app.hakusan.sdk

import kotlinx.coroutines.flow.Flow

/** Availability of the one retained Library resume position. */
enum class LibraryResumeState {
  NONE,
  AVAILABLE,
  TEMPORARILY_UNAVAILABLE,
}

/** Reading progress presented for one Library title. */
data class LibraryTitleProgress(
  val chapterCount: Int,
  val readChapterCount: Int,
  val resumeState: LibraryResumeState,
) {
  init {
    require(chapterCount >= 0) {
      "Library chapter count must not be negative."
    }
    require(readChapterCount in 0..chapterCount) {
      "Library read chapter count must be within the chapter count."
    }
    require(
      resumeState != LibraryResumeState.AVAILABLE ||
        (chapterCount > 0 && readChapterCount < chapterCount),
    ) {
      "An available resume requires one unread canonical chapter."
    }
  }
}

/** Shared presentation data for one title referenced by Library shelves. */
data class LibraryTitleItem(
  val id: ScreenTitleId,
  val key: ScreenTitleKey,
  val displayName: String,
  val description: String?,
  val progress: LibraryTitleProgress,
)

/** One explicitly ordered category shelf referencing shared title data. */
@ConsistentCopyVisibility
data class LibraryShelfItem private constructor(
  val id: ScreenShelfId,
  val name: String,
  val titleIds: List<ScreenTitleId>,
) {
  val titleCount: Int
    get() = titleIds.size

  companion object {
    fun of(
      id: ScreenShelfId,
      name: String,
      titleIds: Iterable<ScreenTitleId>,
    ): LibraryShelfItem {
      val ownedTitleIds = titleIds.toOwnedList()
      require(ownedTitleIds.distinct().size == ownedTitleIds.size) {
        "A Library shelf must not repeat a title identity."
      }
      return LibraryShelfItem(id, name, ownedTitleIds)
    }
  }
}

/**
 * One normalized Library screen snapshot with explicit presentation order.
 *
 * [shelves] and each shelf's title identities retain application-selected
 * order. Consumers must not infer another order from the identifiers or map.
 */
@ConsistentCopyVisibility
data class LibraryScreen private constructor(
  val titlesById: Map<ScreenTitleId, LibraryTitleItem>,
  val shelves: List<LibraryShelfItem>,
) {
  companion object {
    fun of(
      titlesById: Map<ScreenTitleId, LibraryTitleItem>,
      shelves: Iterable<LibraryShelfItem>,
    ): LibraryScreen {
      val ownedTitles = titlesById.toOwnedMap()
      val ownedShelves = shelves.toOwnedList()
      require(ownedTitles.all { (id, title) -> id == title.id }) {
        "Each Library title map key must match its title identity."
      }
      require(
        ownedShelves.distinctBy { it.id }.size == ownedShelves.size,
      ) {
        "A Library screen must not repeat a shelf identity."
      }
      val referencedTitleIds = ownedShelves
        .flatMapTo(HashSet()) { it.titleIds }
      require(referencedTitleIds == ownedTitles.keys) {
        "Library shelf membership and shared title data must agree."
      }
      return LibraryScreen(ownedTitles, ownedShelves)
    }
  }
}

/** Screen-facing observation of committed Library state. */
interface LibraryScreenService {
  /**
   * Observes immutable snapshots until the collector cancels.
   *
   * Unexpected failures and cancellation propagate to the collector.
   */
  fun observeLibrary(): Flow<LibraryScreen>
}
