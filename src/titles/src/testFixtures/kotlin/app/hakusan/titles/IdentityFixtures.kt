package app.hakusan.titles

import kotlin.uuid.Uuid

/**
 * Returns the chapter identity number [sequence], a fixed UUIDv7. Equal
 * sequences return equal identities, and different sequences return different
 * identities.
 *
 * @throws IllegalArgumentException if [sequence] is negative.
 */
public fun chapterId(sequence: Int): ChapterId {
  require(sequence >= 0) { "A chapter identity sequence must not be negative." }

  val uuid =
    Uuid.fromLongs(VERSION_7_HIGH_BITS, VARIANT_2_LOW_BITS or sequence.toLong())

  return checkNotNull(ChapterId.ofOrNull(uuid)) {
    "The fixture built a value other than a UUIDv7."
  }
}

/**
 * Returns the category identity with the database key [key].
 *
 * @throws IllegalArgumentException if [key] is not positive.
 */
public fun categoryId(key: Long): CategoryId =
  requireNotNull(CategoryId.ofOrNull(key)) {
    "A category key must be positive."
  }

/** The high bits of a UUIDv7 with a zero timestamp and zero random bits. */
private const val VERSION_7_HIGH_BITS = 0x7000L

/** The low bits of the RFC 9562 variant with zero random bits. */
private const val VARIANT_2_LOW_BITS = Long.MIN_VALUE
