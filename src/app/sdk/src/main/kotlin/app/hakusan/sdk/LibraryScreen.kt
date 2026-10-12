package app.hakusan.sdk

import java.text.Collator
import java.util.Collections
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/** The availability of the one saved resume position of a Library title. */
public enum class LibraryResumeState {
  /** The title has no saved position. */
  NONE,

  /** The saved position names an unread chapter of the canonical list. */
  AVAILABLE,

  /** The saved position names a chapter that the canonical list omits. */
  TEMPORARILY_UNAVAILABLE,
}

/**
 * The reading progress of one Library title.
 *
 * @throws IllegalArgumentException if a count is negative, [readChapterCount]
 *   exceeds [chapterCount], or [resumeState] is [LibraryResumeState.AVAILABLE]
 *   without an unread chapter.
 */
public data class LibraryTitleProgress(
  public val chapterCount: Int,
  public val readChapterCount: Int,
  public val resumeState: LibraryResumeState,
) {
  init {
    require(chapterCount >= 0) {
      "A Library chapter count must not be negative."
    }

    require(readChapterCount in 0..chapterCount) {
      "A Library read count must lie within the chapter count."
    }

    require(
      resumeState != LibraryResumeState.AVAILABLE ||
        readChapterCount < chapterCount
    ) {
      "An available resume position requires an unread chapter."
    }
  }
}

/**
 * The shared data of one Library title, which shelves reference by [id].
 *
 * [displayName], [description], and [sourceDisplayName] are unvalidated display
 * metadata and never identity.
 */
public data class LibraryTitleItem(
  public val id: ScreenTitleId,
  public val key: ScreenTitleKey,
  public val displayName: String,
  public val description: String?,
  public val sourceDisplayName: String,
  public val progress: LibraryTitleProgress,
)

/**
 * One stored category and its member titles, without order.
 *
 * It is the input form of a shelf. [LibraryScreen.of] decides the order.
 */
public data class LibraryShelfMembers(
  public val id: ScreenShelfId,
  public val name: String,
  public val titleIds: Set<ScreenTitleId>,
)

/**
 * One ordered shelf, which references its titles by identity.
 *
 * Only [LibraryScreen.of] creates shelves.
 */
@ConsistentCopyVisibility
public data class LibraryShelfItem
internal constructor(
  public val id: ScreenShelfId,
  public val name: String,
  public val titleIds: List<ScreenTitleId>,
) {
  /** The number of titles on this shelf, derived from [titleIds]. */
  public val titleCount: Int
    get() = titleIds.size
}

/**
 * One normalized Library snapshot with an explicit presentation order.
 *
 * Each title appears once in [titlesById]. [shelves] and the title identities
 * of each shelf are in presentation order, and one title may sit on several
 * shelves. The iteration order of [titlesById] is deterministic, but it is not
 * a presentation order.
 *
 * "No shelves" and "one stored empty shelf" are distinct snapshots.
 */
@ConsistentCopyVisibility
public data class LibraryScreen
private constructor(
  public val titlesById: Map<ScreenTitleId, LibraryTitleItem>,
  public val shelves: List<LibraryShelfItem>,
) {
  public companion object {
    /**
     * Orders the unordered Library data by visible metadata only.
     *
     * Titles compare by display name, then description, then source name, and
     * shelves by name. Text compares under the locale-aware collation of
     * [collationLocale] with canonical decomposition, and an absent description
     * compares as empty text. A UUID breaks any remaining title tie, and the
     * shelf identity breaks any remaining shelf tie. These tie-breaks only keep
     * rendering stable and carry no product meaning. Input order never affects
     * the result.
     *
     * @throws IllegalArgumentException if two titles or two shelves share an
     *   identity, a shelf references a title absent from [titles], or a title
     *   sits on no shelf.
     */
    public fun of(
      titles: Iterable<LibraryTitleItem>,
      shelves: Iterable<LibraryShelfMembers>,
      collationLocale: Locale,
    ): LibraryScreen {
      val ownedTitles = titles.toOwnedList()

      val ownedShelves = shelves.toOwnedList()

      val titleIds = ownedTitles.mapTo(HashSet()) { it.id }

      val shelvedTitleIds = ownedShelves.flatMapTo(HashSet()) { it.titleIds }

      require(titleIds.size == ownedTitles.size) {
        "A Library screen must not repeat a title identity."
      }

      require(ownedShelves.distinctBy { it.id }.size == ownedShelves.size) {
        "A Library screen must not repeat a shelf identity."
      }

      require(titleIds.containsAll(shelvedTitleIds)) {
        "A Library shelf must reference only titles of the screen."
      }

      require(shelvedTitleIds.containsAll(titleIds)) {
        "Every Library title must sit on at least one shelf."
      }

      val collator = libraryCollator(collationLocale)

      val orderedTitles = ownedTitles.sortedWith(titleOrder(collator))

      val orderedShelves =
        ownedShelves.sortedWith(shelfOrder(collator)).map { members ->
          orderedShelf(members, orderedTitles)
        }

      return LibraryScreen(
        titlesById(orderedTitles),
        orderedShelves.toOwnedList(),
      )
    }
  }
}

/** The outcome of one automatic Add of a title to the Library. */
public sealed interface AddToLibraryScreenResult {
  /** The title is a Library member, whether newly added or already one. */
  public data object Success : AddToLibraryScreenResult

  /**
   * Several categories exist, so the Add needs an explicit category selection.
   * Nothing changed.
   */
  public data object CategorySelectionRequired : AddToLibraryScreenResult

  /** The title is no longer known locally. */
  public data object TitleNotFound : AddToLibraryScreenResult
}

/** Screen-facing Library observation and automatic Add. */
public interface LibraryScreenService {
  /**
   * Observes the committed Library as ordered snapshots.
   *
   * Collection continues until the collector cancels. Cancellation and
   * unexpected defects propagate to the collector.
   */
  public fun observeLibrary(): Flow<LibraryScreen>

  /**
   * Observes the identities of the titles with committed Library membership.
   *
   * Each emitted set is an unordered, immutable snapshot. Progress writes never
   * cause an emission. Cancellation and unexpected defects propagate to the
   * collector.
   */
  public fun observeLibraryTitleIds(): Flow<Set<ScreenTitleId>>

  /**
   * Adds the title [titleId] to the Library under the automatic category
   * policy.
   *
   * Its result space is disjoint from that of an explicit category selection.
   * Cancellation and unexpected defects propagate and never become a result.
   */
  public suspend fun addToLibrary(
    titleId: ScreenTitleId
  ): AddToLibraryScreenResult
}

private fun libraryCollator(locale: Locale): Collator {
  val collator = Collator.getInstance(locale)

  collator.decomposition = Collator.CANONICAL_DECOMPOSITION

  return collator
}

private fun titleOrder(collator: Collator): Comparator<LibraryTitleItem> =
  compareBy<LibraryTitleItem, String>(collator) { it.displayName }
    .thenBy(collator) { it.description.orEmpty() }
    .thenBy(collator) { it.sourceDisplayName }
    .thenComparator { first, second ->
      compareUuids(first.id.value, second.id.value)
    }

private fun shelfOrder(collator: Collator): Comparator<LibraryShelfMembers> =
  compareBy<LibraryShelfMembers, String>(collator) { it.name }
    .thenComparing { first, second ->
      compareCodePoints(first.name, second.name)
    }
    .thenBy { it.id.value }

/** Compares [first] and [second] by Unicode code point, not by UTF-16 unit. */
private fun compareCodePoints(first: String, second: String): Int {
  var firstIndex = 0
  var secondIndex = 0

  while (firstIndex < first.length && secondIndex < second.length) {
    val firstPoint = first.codePointAt(firstIndex)

    val secondPoint = second.codePointAt(secondIndex)

    if (firstPoint != secondPoint) return firstPoint.compareTo(secondPoint)

    firstIndex += Character.charCount(firstPoint)
    secondIndex += Character.charCount(secondPoint)
  }

  return when {
    firstIndex < first.length -> 1
    secondIndex < second.length -> -1
    else -> 0
  }
}

private fun compareUuids(first: UUID, second: UUID): Int {
  val high =
    first.mostSignificantBits
      .toULong()
      .compareTo(second.mostSignificantBits.toULong())

  return if (high != 0) {
    high
  } else {
    first.leastSignificantBits
      .toULong()
      .compareTo(second.leastSignificantBits.toULong())
  }
}

private fun orderedShelf(
  members: LibraryShelfMembers,
  orderedTitles: List<LibraryTitleItem>,
): LibraryShelfItem {
  val titleIds =
    orderedTitles.filter { it.id in members.titleIds }.map { it.id }

  return LibraryShelfItem(members.id, members.name, titleIds.toOwnedList())
}

private fun titlesById(
  orderedTitles: List<LibraryTitleItem>
): Map<ScreenTitleId, LibraryTitleItem> {
  val titles =
    LinkedHashMap<ScreenTitleId, LibraryTitleItem>(orderedTitles.size)

  orderedTitles.associateByTo(titles) { it.id }

  return Collections.unmodifiableMap(titles)
}
