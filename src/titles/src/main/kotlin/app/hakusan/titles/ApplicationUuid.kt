package app.hakusan.titles

import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

internal object ApplicationUuidFactory {
  @OptIn(ExperimentalUuidApi::class)
  fun create(): UUID = Uuid.generateV7().toJavaUuid()
}

internal fun requireUuidV7(value: UUID, field: String) {
  require(value.version() == 7 && value.variant() == 2) {
    "$field must be an RFC 9562 UUIDv7."
  }
}
