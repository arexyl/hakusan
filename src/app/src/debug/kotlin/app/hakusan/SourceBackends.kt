package app.hakusan

import app.hakusan.debug.source.DeterministicSource
import app.hakusan.extensions.SourceBackend

internal fun sourceBackends(): List<SourceBackend> = listOf(
  DeterministicSource(),
)
