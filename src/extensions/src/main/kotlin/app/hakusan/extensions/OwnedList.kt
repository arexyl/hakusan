package app.hakusan.extensions

import java.util.Collections

/**
 * Copies the caller's elements in order into a list that no caller can mutate.
 */
internal fun <Element> Iterable<Element>.toOwnedList(): List<Element> =
  Collections.unmodifiableList(toMutableList())
