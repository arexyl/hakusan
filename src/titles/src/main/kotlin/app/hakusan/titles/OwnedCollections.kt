package app.hakusan.titles

import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet

internal fun <Value> Iterable<Value>.toOwnedSet(): Set<Value> =
  Collections.unmodifiableSet(LinkedHashSet<Value>().also { it.addAll(this) })

internal fun <Value> Iterable<Value>.toOwnedList(): List<Value> =
  Collections.unmodifiableList(ArrayList<Value>().also { it.addAll(this) })

internal fun <Key, Value> Map<Key, Value>.toOwnedMap(): Map<Key, Value> =
  Collections.unmodifiableMap(LinkedHashMap(this))
