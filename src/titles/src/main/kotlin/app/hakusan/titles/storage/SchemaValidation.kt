package app.hakusan.titles.storage

internal fun requireGeneratedStorageId(value: Long, field: String) {
  require(value >= 0) {
    "$field must be unset or positive."
  }
}

internal fun requireStoredId(value: Long, field: String) {
  require(value > 0) {
    "$field must be positive."
  }
}
