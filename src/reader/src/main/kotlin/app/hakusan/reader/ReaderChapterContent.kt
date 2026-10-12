package app.hakusan.reader

import java.util.Collections

/**
 * The granularity of a provider unit.
 *
 * It records provider boundaries. It is independent of layout, tiles, and the
 * reading mode.
 */
public enum class ReaderUnitKind {
  PAGE,
  PROVIDER_SEGMENT,
}

/**
 * One provider unit of a chapter.
 *
 * The [reference] is opaque and source-owned. Only the application content
 * adapter interprets it. Equal references remain separate units.
 */
public data class ReaderContentUnit(
  public val kind: ReaderUnitKind,
  public val reference: String,
) {
  init {
    require(reference.isNotBlank()) { "Content reference must not be blank." }
  }
}

/** A provider unit identified by its chapter, kind, and index. */
public data class ReaderPosition(
  public val chapter: ReaderChapterKey,
  public val unitKind: ReaderUnitKind,
  public val unitIndex: Int,
) {
  init {
    require(unitIndex >= 0) { "Unit index must not be negative." }
  }
}

/** Where reading of a chapter starts. */
public sealed interface ReaderStart {
  /** The first provider unit. */
  public data object Beginning : ReaderStart

  /** A previously recorded provider unit. */
  public data class Resume(
    public val unitKind: ReaderUnitKind,
    public val unitIndex: Int,
  ) : ReaderStart {
    init {
      require(unitIndex >= 0) { "Resume index must not be negative." }
    }
  }
}

/** The target that a [ReaderStart] selects in the current chapter content. */
public sealed interface ReaderStartTarget {
  /**
   * The start names a unit of the current content. Only
   * [ReaderChapterContent.resolve] creates it.
   */
  @ConsistentCopyVisibility
  public data class Available
  internal constructor(public val position: ReaderPosition) : ReaderStartTarget

  /**
   * The start names no unit of the current content, because its index or kind
   * is incompatible. It is never clamped or replaced by the beginning.
   */
  public data object Unavailable : ReaderStartTarget
}

/**
 * The provider units of one chapter, in provider order.
 *
 * The unit list is an owned immutable copy that keeps every unit kind and
 * duplicate reference.
 */
public class ReaderChapterContent
private constructor(
  public val chapter: ReaderChapter,
  public val units: List<ReaderContentUnit>,
) {
  /** Returns the position of the unit at [index], which must exist. */
  public fun positionAt(index: Int): ReaderPosition {
    require(index in units.indices) { "The index names no unit." }

    return ReaderPosition(chapter.key, units[index].kind, index)
  }

  /** Resolves [start] exactly against these units. */
  public fun resolve(start: ReaderStart): ReaderStartTarget =
    when (start) {
      ReaderStart.Beginning -> ReaderStartTarget.Available(positionAt(0))

      is ReaderStart.Resume -> {
        val position =
          ReaderPosition(chapter.key, start.unitKind, start.unitIndex)

        if (contains(position)) {
          ReaderStartTarget.Available(position)
        } else {
          ReaderStartTarget.Unavailable
        }
      }
    }

  internal fun contains(position: ReaderPosition): Boolean =
    position.chapter == chapter.key &&
      units.getOrNull(position.unitIndex)?.kind == position.unitKind

  public companion object {
    /** Owns [units] of [chapter] in provider order. They must be nonempty. */
    public fun of(
      chapter: ReaderChapter,
      units: Iterable<ReaderContentUnit>,
    ): ReaderChapterContent {
      val owned = Collections.unmodifiableList(units.toList())

      require(owned.isNotEmpty()) { "Chapter content must contain a unit." }

      return ReaderChapterContent(chapter, owned)
    }
  }
}
