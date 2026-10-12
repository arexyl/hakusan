package app.hakusan.titles

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The one generator of application UUIDv7 values.
 *
 * Generation is monotonic within one process lifetime, which serves index
 * locality only. The values promise no order across restarts, clock changes,
 * devices, restores, or processes.
 */
internal object ApplicationUuids {
  @OptIn(ExperimentalUuidApi::class) fun generate(): Uuid = Uuid.generateV7()
}

/** Whether this value is an RFC 9562 UUID of variant 2 and version 7. */
internal fun Uuid.isApplicationUuid(): Boolean = toLongs { high, low ->
  val version = high ushr 12 and 0xF
  val variant = low ushr 62

  version == 7L && variant == 2L
}
