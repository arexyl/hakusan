package app.hakusan.titles

import java.util.Collections
import java.util.LinkedHashSet

internal fun <Value> Iterable<Value>.toOwnedSet(): Set<Value> =
  Collections.unmodifiableSet(LinkedHashSet<Value>().also { it.addAll(this) })
