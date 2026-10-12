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
