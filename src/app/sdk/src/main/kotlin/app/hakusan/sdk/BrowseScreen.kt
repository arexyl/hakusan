package app.hakusan.sdk

/** One locally available source entry in the Catalog. */
data class CatalogSourceItem(
  val id: ScreenSourceId,
  val displayName: String,
)

/** The ordered source entries currently exposed by the application. */
@ConsistentCopyVisibility
data class CatalogScreen private constructor(
  val sources: List<CatalogSourceItem>,
) {
  companion object {
    fun of(sources: Iterable<CatalogSourceItem>): CatalogScreen {
      val ownedSources = sources.toOwnedList()
      require(ownedSources.distinctBy { it.id }.size == ownedSources.size) {
        "A Catalog screen must not repeat a source identity."
      }
      return CatalogScreen(ownedSources)
    }
  }
}

/** One source-owned title entry on a browse screen. */
data class BrowseTitleItem(
  val key: ScreenTitleKey,
  val displayName: String,
)

/** One ordered browse observation belonging to [source]. */
@ConsistentCopyVisibility
data class BrowseScreen private constructor(
  val source: CatalogSourceItem,
  val titles: List<BrowseTitleItem>,
) {
  companion object {
    fun of(
      source: CatalogSourceItem,
      titles: Iterable<BrowseTitleItem>,
    ): BrowseScreen {
      val ownedTitles = titles.toOwnedList()
      require(ownedTitles.all { it.key.sourceId == source.id }) {
        "Every browse title must belong to the screen source."
      }
      require(ownedTitles.distinctBy { it.key }.size == ownedTitles.size) {
        "A browse screen must not repeat a title key."
      }
      return BrowseScreen(source, ownedTitles)
    }
  }
}

/** Expected completion of one source browse load. */
sealed interface BrowseScreenResult {
  data class Success(
    val screen: BrowseScreen,
  ) : BrowseScreenResult

  data class Failure(
    val error: BrowseScreenFailure,
  ) : BrowseScreenResult
}

/** A browse failure that changes the screen's available recovery action. */
sealed interface BrowseScreenFailure {
  /** The requested source is not registered in the current application. */
  data object SourceNotFound : BrowseScreenFailure

  /** The registered source cannot currently provide its browse content. */
  data object SourceUnavailable : BrowseScreenFailure

  /** The source returned a contradictory browse observation. */
  data object InvalidObservation : BrowseScreenFailure
}

/** Screen-facing source discovery and browse operations. */
interface BrowseScreenService {
  /** Returns the immutable ordered Catalog known to this service. */
  fun catalog(): CatalogScreen

  /** Loads one source without owning the caller's task or dispatcher. */
  suspend fun loadBrowse(
    sourceId: ScreenSourceId,
  ): BrowseScreenResult
}
