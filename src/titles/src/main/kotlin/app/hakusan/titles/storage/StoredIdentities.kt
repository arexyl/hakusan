package app.hakusan.titles.storage

import app.hakusan.titles.ChapterId
import app.hakusan.titles.TitleId
import kotlin.uuid.Uuid

// A stored identity is the 16-byte network-order value of java.util.UUID BLOB
// conversion: the most significant bits, then the least significant bits, both
// big-endian. Entities hold these bytes raw, because Room's UUID conversion
// does not check their length. The functions below are the only crossing
// between identities and stored bytes, in either direction.

/** Returns the stored bytes of this title identity. */
internal fun TitleId.toStoredBytes(): ByteArray = uuid.toByteArray()

/** Returns the stored bytes of this chapter identity. */
internal fun ChapterId.toStoredBytes(): ByteArray = uuid.toByteArray()

/**
 * Returns the title identity of stored bytes, or null when they are not a
 * 16-byte UUIDv7. The read boundary reports null as a stored-state violation.
 */
internal fun ByteArray.toTitleIdOrNull(): TitleId? =
  toStoredUuidOrNull()?.let(TitleId::ofOrNull)

/**
 * Returns the chapter identity of stored bytes, or null when they are not a
 * 16-byte UUIDv7. The read boundary reports null as a stored-state violation.
 */
internal fun ByteArray.toChapterIdOrNull(): ChapterId? =
  toStoredUuidOrNull()?.let(ChapterId::ofOrNull)

/** Returns the UUID of stored bytes, or null when they are not 16 bytes. */
private fun ByteArray.toStoredUuidOrNull(): Uuid? =
  if (size == Uuid.SIZE_BYTES) Uuid.fromByteArray(this) else null
