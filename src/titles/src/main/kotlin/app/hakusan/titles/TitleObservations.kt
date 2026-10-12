package app.hakusan.titles

import java.util.Collections

/**
 * One chapter of an accepted complete chapter snapshot.
 *
 * [displayName] is unvalidated display metadata. It never defines identity or
 * canonical order.
 */
public data class AcceptedChapter(
  /** The exact alias of the chapter. */
  public val alias: SourceChapterAlias,
  /** The display name that the snapshot reports. */
  public val displayName: String,
)

/**
 * One accepted observation of a title: its details and a complete
 * first-to-final chapter snapshot.
 *
 * Only accepted details and an accepted complete snapshot form an observation.
 * A browse observation or a partial snapshot never does. The details replace
 * the stored ones, so a null [description] clears a stored description.
 * [chapters] is the canonical order. An empty list is a valid complete
 * snapshot.
 */
public class AcceptedTitleObservation
private constructor(
  /** The exact alias of the observed title. */
  public val alias: SourceTitleAlias,
  /** The display name that the details report. */
  public val displayName: String,
  /** The description that the details report, or null when they have none. */
  public val description: String?,
  /** The owned first-to-final chapter sequence. */
  public val chapters: List<AcceptedChapter>,
) {
  public companion object {
    /**
     * Returns the observation of the title [alias] with its details and an
     * owned copy of [chapters] in their order.
     *
     * @throws IllegalArgumentException if a chapter belongs to another title
     *   alias, or if two chapters share one alias.
     */
    public fun of(
      alias: SourceTitleAlias,
      displayName: String,
      description: String?,
      chapters: Iterable<AcceptedChapter>,
    ): AcceptedTitleObservation {
      val owned =
        Collections.unmodifiableList(chapters.toCollection(ArrayList()))

      require(owned.all { chapter -> chapter.alias.titleAlias == alias }) {
        "Every accepted chapter must belong to the observed title."
      }

      val localKeys =
        owned.mapTo(HashSet()) { chapter -> chapter.alias.localKey }

      require(localKeys.size == owned.size) {
        "An accepted chapter snapshot must not repeat a chapter alias."
      }

      return AcceptedTitleObservation(alias, displayName, description, owned)
    }
  }
}
