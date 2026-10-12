package app.hakusan.reader

import java.util.Collections

/**
 * The exact source-qualified alias of a title.
 *
 * It is never an application UUID or a storage key. Components keep their
 * whitespace, case, and Unicode composition, and blank components are rejected.
 */
public data class ReaderTitleKey(
  public val source: String,
  public val title: String,
) {
  init {
    require(source.isNotBlank()) { "Source identity must not be blank." }
    require(title.isNotBlank()) { "Title key must not be blank." }
  }
}

/** The exact alias of a chapter within its complete title alias. */
public data class ReaderChapterKey(
  public val title: ReaderTitleKey,
  public val chapter: String,
) {
  init {
    require(chapter.isNotBlank()) { "Chapter key must not be blank." }
  }
}

/**
 * One chapter of the canonical sequence.
 *
 * The display name is presentation metadata. It never establishes order or
 * identity.
 */
public data class ReaderChapter(
  public val key: ReaderChapterKey,
  public val displayName: String,
)

/**
 * The canonical chapter sequence of one title, pinned when the reader opens.
 *
 * The sequence is an owned immutable copy. A later source refresh never changes
 * it; the application opens a new input instead.
 */
public class ReaderInput
private constructor(
  public val title: ReaderTitleKey,
  public val chapters: List<ReaderChapter>,
  private val indices: Map<ReaderChapterKey, Int>,
) {
  /** Returns the chapter with [key], or null when it is not in the sequence. */
  public fun find(key: ReaderChapterKey): ReaderChapter? =
    indices[key]?.let(chapters::get)

  /**
   * Returns the canonical predecessor of [key], or null for the first chapter.
   *
   * @throws IllegalArgumentException if [key] is not in the sequence. A null
   *   result would merge an unknown key with the first chapter.
   */
  public fun previous(key: ReaderChapterKey): ReaderChapter? =
    chapters.getOrNull(indexOf(key) - 1)

  /**
   * Returns the canonical successor of [key], or null for the final chapter.
   *
   * @throws IllegalArgumentException if [key] is not in the sequence. A null
   *   result would let an unknown key pass as the final chapter.
   */
  public fun next(key: ReaderChapterKey): ReaderChapter? =
    chapters.getOrNull(indexOf(key) + 1)

  private fun indexOf(key: ReaderChapterKey): Int =
    requireNotNull(indices[key]) { "The chapter is not in the sequence." }

  public companion object {
    /**
     * Pins [chapters] in their given canonical order.
     *
     * The sequence must be nonempty, belong to [title], and contain each
     * chapter key once.
     */
    public fun of(
      title: ReaderTitleKey,
      chapters: Iterable<ReaderChapter>,
    ): ReaderInput {
      val owned = Collections.unmodifiableList(chapters.toList())

      require(owned.isNotEmpty()) { "The sequence must contain a chapter." }

      val indices = HashMap<ReaderChapterKey, Int>(owned.size)

      owned.forEachIndexed { index, chapter ->
        require(chapter.key.title == title) {
          "Every chapter must belong to the reader title."
        }

        require(indices.put(chapter.key, index) == null) {
          "Every chapter key must appear once."
        }
      }

      return ReaderInput(title, owned, indices)
    }
  }
}
