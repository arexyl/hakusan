package app.hakusan.titles

import kotlin.uuid.Uuid

/**
 * The durable application identity of one title.
 *
 * It carries an RFC 9562 UUIDv7. The UUID is opaque: its timestamp and byte
 * order carry no product meaning, and it is no authorization secret. Only
 * `:titles` mints it. [ofOrNull] validates the format only, so an identity may
 * name no stored title; operations report that through their results.
 */
@JvmInline
public value class TitleId
private constructor(
  /**
   * The UUIDv7 of the title. Keep it out of user-facing diagnostics and
   * telemetry.
   */
  public val uuid: Uuid
) {
  public companion object {
    /** Mints a new title identity. */
    internal fun generate(): TitleId =
      checkNotNull(ofOrNull(ApplicationUuids.generate())) {
        "The UUID generator produced a value other than a UUIDv7."
      }

    /**
     * Returns the title identity that [uuid] carries, or null when [uuid] is
     * not a UUIDv7. This is the one validation of the value. It does not check
     * that a stored title has the identity.
     */
    public fun ofOrNull(uuid: Uuid): TitleId? =
      if (uuid.isApplicationUuid()) TitleId(uuid) else null
  }
}

/**
 * The durable application identity of one chapter.
 *
 * It carries an RFC 9562 UUIDv7. The UUID is opaque: its timestamp and byte
 * order carry no product meaning, and it is no authorization secret. Only
 * `:titles` mints or decodes it.
 */
@JvmInline
public value class ChapterId
private constructor(
  /**
   * The UUIDv7 of the chapter. Keep it out of user-facing diagnostics and
   * telemetry.
   */
  public val uuid: Uuid
) {
  internal companion object {
    /** Mints a new chapter identity. */
    fun generate(): ChapterId =
      checkNotNull(ofOrNull(ApplicationUuids.generate())) {
        "The UUID generator produced a value other than a UUIDv7."
      }

    /**
     * Returns the chapter identity that [uuid] carries, or null when [uuid] is
     * not a UUIDv7. This is the one validation of the value.
     */
    fun ofOrNull(uuid: Uuid): ChapterId? =
      if (uuid.isApplicationUuid()) ChapterId(uuid) else null
  }
}

/**
 * The identity of one Library category.
 *
 * It is opaque and local to one database: the database generates it, and it
 * keeps no meaning in another database, a backup, or after a restore. Only
 * `:titles` mints it, so a consumer obtains it from a Library observation or an
 * Add result. Its value carries no order. The database may reuse an identity
 * after its category is deleted, so a held identity may then name a different
 * category. Read [key] only to form a screen key. Do not persist one or retain
 * it across a restore.
 */
@JvmInline
public value class CategoryId
private constructor(
  /** The positive key that the database generated. It carries no order. */
  public val key: Long
) {
  internal companion object {
    /**
     * Returns the category identity that the generated [key] carries, or null
     * when [key] is not positive. This is the one validation of the value.
     */
    fun ofOrNull(key: Long): CategoryId? =
      if (key > 0) CategoryId(key) else null
  }
}
