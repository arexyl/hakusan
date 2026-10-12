package app.hakusan.titles

/**
 * Stored titles state violates an invariant of `:titles`.
 *
 * A read raises it after the database returned a row that breaks a storage
 * invariant, or rows that contradict each other. It is a defect, not an
 * expected outcome: `:titles` never converts it into a result, an empty value,
 * or a missing title, and it never repairs the stored state. Physical
 * corruption, schema incompatibility, I/O failures, and cancellation are not
 * stored-state violations.
 *
 * Catch this type by name, never through [IllegalStateException]. A coroutine's
 * `CancellationException` is also an [IllegalStateException], so a broad
 * handler would swallow cancellation as well.
 *
 * The message names the violated rule only. It carries no stored value,
 * application identity, storage key, alias, or other user content.
 */
public class StoredStateViolationException
internal constructor(
  /** The invariant family that the stored state violates. */
  public val kind: StoredStateViolationKind,
  message: String,
) : IllegalStateException(message)

/**
 * The closed set of invariant families that stored titles state can violate.
 *
 * Each check of the read boundary maps to exactly one kind. When a read returns
 * one title, chapter, or category in several rows, such as one row per
 * category, and those rows disagree, the kind of the disagreeing field applies.
 * A disagreement in a field without its own kind, such as display metadata or a
 * category name, is an [IDENTITY] violation. The declaration order carries no
 * meaning.
 */
public enum class StoredStateViolationKind {
  /**
   * An identity is malformed, duplicated, or ambiguous: a stored title or
   * chapter UUID that is not 16 bytes long or not an RFC 9562 UUIDv7 of variant
   * 2; an identity that occurs in several rows of a read that requires one row
   * per identity, such as a category identity listed twice; or an identity
   * whose rows in one read disagree in a field without its own kind, such as a
   * title display name or description, or a category name.
   */
  IDENTITY,

  /**
   * A storage key is outside its bounds: a stored key that is not positive.
   * This includes a category identity, which is a database-generated key.
   */
  STORAGE_KEY,

  /**
   * A source alias is not exact: a blank source identity, source title key, or
   * title-local chapter key, or rows of one title that carry conflicting
   * aliases in one read.
   */
  ALIAS,

  /**
   * A row belongs to another owner than the one it is read under, such as a
   * chapter or a resume position that belongs to another title.
   */
  OWNERSHIP,

  /**
   * The canonical chapter order is malformed: a negative canonical index, or
   * canonical indexes of one title that are not dense from zero.
   */
  CANONICAL_ORDER,

  /**
   * A resume position is malformed or contradicts reading state: an unknown
   * unit kind, a negative unit index, a partial position, a position on a read
   * chapter, a persistent position of a title outside the Library, a position
   * whose availability disagrees with the canonical order, or rows of one title
   * that carry conflicting resume state in one read.
   */
  RESUME_STATE,

  /**
   * Library membership is not exact: a title that a read reports in the Library
   * without a category association, a title listed twice in one category, an
   * empty category that carries title state, or rows of one title that carry
   * conflicting Library state in one read.
   */
  MEMBERSHIP,
}
