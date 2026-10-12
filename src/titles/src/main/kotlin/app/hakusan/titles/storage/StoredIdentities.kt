package app.hakusan.titles.storage

import app.hakusan.titles.ChapterId
import app.hakusan.titles.TitleId
import java.util.UUID
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

// Room stores java.util.UUID as its 16-byte network-order value. These
// conversions are the only crossing between identities and stored UUIDs.

internal fun TitleId.toStoredUuid(): UUID = uuid.toJavaUuid()

internal fun ChapterId.toStoredUuid(): UUID = uuid.toJavaUuid()

/**
 * Returns the title identity of a stored UUID, or null when the stored value is
 * not a UUIDv7. The read boundary reports null as a stored-state violation.
 */
internal fun UUID.toTitleIdOrNull(): TitleId? = TitleId.ofOrNull(toKotlinUuid())

/**
 * Returns the chapter identity of a stored UUID, or null when the stored value
 * is not a UUIDv7. The read boundary reports null as a stored-state violation.
 */
internal fun UUID.toChapterIdOrNull(): ChapterId? =
  ChapterId.ofOrNull(toKotlinUuid())
