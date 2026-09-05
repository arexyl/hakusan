package app.hakusan.sdk

import java.util.Collections
import java.util.LinkedHashMap

internal fun <Value> Iterable<Value>.toOwnedList(): List<Value> =
  Collections.unmodifiableList(toMutableList())

internal fun <Key, Value> Map<Key, Value>.toOwnedMap(): Map<Key, Value> =
  Collections.unmodifiableMap(LinkedHashMap(this))
