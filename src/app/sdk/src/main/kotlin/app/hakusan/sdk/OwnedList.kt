package app.hakusan.sdk

/**
 * Copies the caller's elements in order into a list that no caller can mutate.
 */
internal fun <Element : Any> Iterable<Element>.toOwnedList(): List<Element> =
  java.util.List.copyOf(if (this is Collection<Element>) this else toList())
