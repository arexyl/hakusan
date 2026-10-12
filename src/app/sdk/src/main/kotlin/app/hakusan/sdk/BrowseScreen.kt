package app.hakusan.sdk

/**
 * One locally available source in the Catalog.
 *
 * [displayName] is unvalidated display metadata and never identity.
 */
public data class CatalogSourceItem(
  public val id: ScreenSourceId,
  public val displayName: String,
)

/** The locally available sources in their explicit Catalog order. */
@ConsistentCopyVisibility
public data class CatalogScreen
private constructor(public val sources: List<CatalogSourceItem>) {
  public companion object {
    /**
     * Keeps an owned copy of [sources] in the given order.
     *
     * An empty Catalog is valid.
     *
     * @throws IllegalArgumentException if two sources share an identity.
     */
    public fun of(sources: Iterable<CatalogSourceItem>): CatalogScreen {
      val ownedSources = sources.toOwnedList()

      require(ownedSources.distinctBy { it.id }.size == ownedSources.size) {
        "A Catalog screen must not repeat a source identity."
      }

      return CatalogScreen(ownedSources)
    }
  }
}

/**
 * One title observed while browsing a source.
 *
 * [displayName] is unvalidated display metadata and never identity.
 */
public data class BrowseTitleItem(
  public val key: ScreenTitleKey,
  public val displayName: String,
)

/** One browse observation of [source], with its titles in source order. */
@ConsistentCopyVisibility
public data class BrowseScreen
private constructor(
  public val source: CatalogSourceItem,
  public val titles: List<BrowseTitleItem>,
) {
  public companion object {
    /**
     * Keeps an owned copy of [titles] in the given order.
     *
     * An empty result is valid.
     *
     * @throws IllegalArgumentException if a title belongs to another source or
     *   two titles share a key.
     */
    public fun of(
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

/** The expected completion of one browse load. */
public sealed interface BrowseScreenResult {
  /** The source returned a valid, possibly empty, observation. */
  public data class Success(public val screen: BrowseScreen) :
    BrowseScreenResult

  /** The load failed in a way that decides the screen's recovery. */
  public data class Failure(public val error: BrowseScreenFailure) :
    BrowseScreenResult
}

/** A browse failure that callers can handle without parsing diagnostics. */
public sealed interface BrowseScreenFailure {
  /** No source with the requested identity is registered. */
  public data object SourceNotFound : BrowseScreenFailure

  /** The registered source cannot provide its browse content right now. */
  public data object SourceUnavailable : BrowseScreenFailure

  /**
   * The source returned a contradictory observation, such as a foreign or a
   * repeated title identity.
   */
  public data object InvalidObservation : BrowseScreenFailure
}

/** Screen-facing access to the Catalog and to source browse loads. */
public interface BrowseScreenService {
  /** Returns the locally available Catalog. */
  public fun catalog(): CatalogScreen

  /**
   * Loads one browse observation of the source [sourceId].
   *
   * The call owns no task or dispatcher of the caller. Cancellation and
   * unexpected defects propagate and never become a [BrowseScreenFailure].
   */
  public suspend fun loadBrowse(sourceId: ScreenSourceId): BrowseScreenResult
}
