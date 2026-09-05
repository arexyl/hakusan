package app.hakusan.sdk

import java.util.UUID

/** Exact opaque identity of one source visible to application screens. */
data class ScreenSourceId(
  val value: String,
) {
  init {
    requireIdentityComponent("Screen source id", value)
  }
}

/** Exact source-qualified title key used before local reconciliation. */
data class ScreenTitleKey(
  val sourceId: ScreenSourceId,
  val sourceTitleKey: String,
) {
  init {
    requireIdentityComponent("Screen title key", sourceTitleKey)
  }
}

/** Exact source-qualified chapter key under one title. */
data class ScreenChapterKey(
  val titleKey: ScreenTitleKey,
  val sourceChapterKey: String,
) {
  init {
    requireIdentityComponent("Screen chapter key", sourceChapterKey)
  }
}

/** Durable application identity of one title exposed to screens. */
@JvmInline
value class ScreenTitleId(
  val value: UUID,
) {
  init {
    requireUuidV7(value, "Screen title id")
  }
}

/** Durable application identity of one chapter exposed to screens. */
@JvmInline
value class ScreenChapterId(
  val value: UUID,
) {
  init {
    requireUuidV7(value, "Screen chapter id")
  }
}

/** Database-local category identity used only as an opaque screen key. */
@JvmInline
value class ScreenShelfId(
  val value: Long,
) {
  init {
    require(value > 0L) {
      "Screen shelf id must be positive."
    }
  }
}

private fun requireIdentityComponent(
  name: String,
  value: String,
) {
  require(value.isNotBlank()) {
    "$name must not be blank."
  }
}

private fun requireUuidV7(
  value: UUID,
  field: String,
) {
  require(value.version() == 7 && value.variant() == 2) {
    "$field must be an RFC 9562 UUIDv7."
  }
}
