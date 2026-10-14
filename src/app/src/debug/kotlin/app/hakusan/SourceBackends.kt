package app.hakusan

import app.hakusan.debug.source.DeterministicSource
import app.hakusan.extensions.SourceBackend

/** Returns the sources that Debug registers: the deterministic source only. */
internal fun sourceBackends(): List<SourceBackend> =
  listOf(DeterministicSource(unavailable = emptySet()))
