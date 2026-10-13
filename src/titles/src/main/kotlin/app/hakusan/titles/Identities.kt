package app.hakusan.titles

import kotlin.uuid.Uuid

/**
 * The durable application identity of one title.
 *
 * It carries an RFC 9562 UUIDv7. The UUID is opaque: its timestamp and byte
 * order carry no product meaning, and it is no authorization secret. Only
 * `:titles` mints or decodes it.
 */
@JvmInline
public value class TitleId private constructor(internal val uuid: Uuid) {
  internal companion object {
    /** Mints a new title identity. */
    fun generate(): TitleId =
      checkNotNull(ofOrNull(ApplicationUuids.generate())) {
        "The UUID generator produced a value other than a UUIDv7."
      }

    /**
     * Returns the title identity that [uuid] carries, or null when [uuid] is
     * not a UUIDv7. This is the one validation of the value.
     */
    fun ofOrNull(uuid: Uuid): TitleId? =
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
public value class ChapterId private constructor(internal val uuid: Uuid) {
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
 * keeps no meaning in another database, a backup, or after a restore. Only the
 * storage of `:titles` mints it, so a consumer obtains it from a result of an
 * Add operation. Its value carries no order. The database may reuse an identity
 * after its category is deleted, so a held identity may then name a different
 * category. Do not persist one or retain it across a restore.
 */
@JvmInline
public value class CategoryId private constructor(internal val key: Long) {
  internal companion object {
    /**
     * Returns the category identity that the generated [key] carries, or null
     * when [key] is not positive. This is the one validation of the value.
     */
    fun ofOrNull(key: Long): CategoryId? =
      if (key > 0) CategoryId(key) else null
  }
}
