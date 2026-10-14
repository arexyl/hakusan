package app.hakusan.titles

/**
 * Returns the chapter [id] with the title-local key [localKey] and
 * [displayName], as the read boundary reads it from a stored row.
 *
 * @throws IllegalArgumentException if [localKey] is blank.
 */
public fun chapter(
  id: ChapterId,
  localKey: String,
  displayName: String,
): Chapter =
  Chapter(
    id = id,
    localKey = TitleLocalChapterKey.of(localKey),
    displayName = displayName,
  )

/**
 * Returns the progress snapshot of [titleId], whose stored source alias is
 * [alias], without a resume target.
 *
 * [chapters] is the canonical sequence in order, and [readChapters] are the
 * read chapters among them. [isInLibrary] states the Library membership.
 *
 * @throws IllegalArgumentException if [chapters] repeat an identity or a local
 *   key, or if [readChapters] has a chapter outside [chapters].
 */
public fun progressSnapshot(
  titleId: TitleId,
  alias: SourceTitleAlias,
  chapters: List<Chapter>,
  readChapters: Set<Chapter> = emptySet(),
  isInLibrary: Boolean = false,
): ProgressSnapshot {
  requireUniqueChapters(chapters)

  val states = readingStates(chapters, readChapters)

  return ProgressSnapshot.create(
    titleId = titleId,
    alias = alias,
    isInLibrary = isInLibrary,
    chapters = states,
    resumeTarget = null,
  )
}

/**
 * Returns the progress snapshot of the Library title [titleId], whose stored
 * source alias is [alias] and whose resume target is the unit [unitIndex] of
 * the kind [unitKind] in [resumeChapter].
 *
 * [chapters] is the canonical sequence in order, and [readChapters] are the
 * read chapters among them. The target is available when [chapters] lists
 * [resumeChapter], and temporarily unavailable when the canonical sequence
 * omits it. Only a Library title has a resume target, so the snapshot is in the
 * Library.
 *
 * @throws IllegalArgumentException if [chapters] and [resumeChapter] repeat an
 *   identity or a local key, if [readChapters] has a chapter outside
 *   [chapters], if [resumeChapter] is read, or if [unitIndex] is negative.
 */
public fun progressSnapshot(
  titleId: TitleId,
  alias: SourceTitleAlias,
  chapters: List<Chapter>,
  readChapters: Set<Chapter> = emptySet(),
  resumeChapter: Chapter,
  unitKind: ContentUnitKind,
  unitIndex: Int,
): ProgressSnapshot {
  val isAvailable = resumeChapter in chapters
  val knownChapters = if (isAvailable) chapters else chapters + resumeChapter

  requireUniqueChapters(knownChapters)

  val states = readingStates(chapters, readChapters)

  require(resumeChapter !in readChapters) {
    "A resume target must not be on a read chapter."
  }

  require(unitIndex >= 0) { "A resume unit index must not be negative." }

  val target =
    if (isAvailable) {
      ResumeTarget.Available(resumeChapter, unitKind, unitIndex)
    } else {
      ResumeTarget.TemporarilyUnavailable(resumeChapter, unitKind, unitIndex)
    }

  return ProgressSnapshot.create(
    titleId = titleId,
    alias = alias,
    isInLibrary = true,
    chapters = states,
    resumeTarget = target,
  )
}

/** Rejects [chapters] of one title that repeat an identity or a local key. */
private fun requireUniqueChapters(chapters: List<Chapter>) {
  val ids = chapters.mapTo(HashSet(), Chapter::id)

  val localKeys = chapters.mapTo(HashSet(), Chapter::localKey)

  require(ids.size == chapters.size) {
    "Known chapters must not repeat an identity."
  }

  require(localKeys.size == chapters.size) {
    "Known chapters must not repeat a local key."
  }
}

/**
 * Returns [chapters] with their read status, after rejecting [readChapters]
 * outside [chapters].
 */
private fun readingStates(
  chapters: List<Chapter>,
  readChapters: Set<Chapter>,
): List<ChapterReadingState> {
  require(chapters.containsAll(readChapters)) {
    "Read chapters must be in the canonical sequence."
  }

  return chapters.map { chapter ->
    ChapterReadingState(chapter, chapter in readChapters)
  }
}
