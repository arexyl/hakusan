package app.hakusan.titles

/**
 * Returns the Library title with [alias] and the details [displayName] and
 * [description], whose compact progress has [chapterCount] current canonical
 * chapters, [readChapterCount] of them read, and [resumeAvailability].
 *
 * @throws IllegalArgumentException if a count is negative, if
 *   [readChapterCount] exceeds [chapterCount], or if [resumeAvailability] is
 *   available without an unread chapter.
 */
public fun libraryTitle(
  alias: SourceTitleAlias,
  displayName: String,
  description: String? = null,
  chapterCount: Int = 0,
  readChapterCount: Int = 0,
  resumeAvailability: ResumeAvailability = ResumeAvailability.NONE,
): LibraryTitle {
  require(chapterCount >= 0 && readChapterCount >= 0) {
    "Chapter counts must not be negative."
  }

  require(readChapterCount <= chapterCount) {
    "A read chapter count must not exceed the chapter count."
  }

  val hasUnreadChapter = readChapterCount < chapterCount

  require(
    resumeAvailability != ResumeAvailability.AVAILABLE || hasUnreadChapter
  ) {
    "An available resume position needs an unread chapter."
  }

  val progress =
    CompactProgress(
      chapterCount = chapterCount,
      readChapterCount = readChapterCount,
      resumeAvailability = resumeAvailability,
    )

  return LibraryTitle(
    alias = alias,
    displayName = displayName,
    description = description,
    progress = progress,
  )
}

/**
 * Returns the Library snapshot of [titles] with one shelf per entry of
 * [shelves], each the category and the identities of its titles.
 *
 * The snapshot owns copies of the collections, so later changes to the
 * arguments never change it.
 *
 * @throws IllegalArgumentException if [shelves] repeat a category identity, if
 *   a shelf has a title outside [titles], or if a title of [titles] is on no
 *   shelf.
 */
public fun librarySnapshot(
  titles: Map<TitleId, LibraryTitle>,
  shelves: Map<LibraryCategory, Set<TitleId>>,
): LibrarySnapshot {
  val categoryIds = shelves.keys.mapTo(HashSet(), LibraryCategory::id)

  val shelvedTitleIds = shelves.values.flatMapTo(HashSet()) { ids -> ids }

  require(categoryIds.size == shelves.size) {
    "Shelves must not repeat a category identity."
  }

  require(titles.keys.containsAll(shelvedTitleIds)) {
    "A shelf must have only titles of the snapshot."
  }

  require(shelvedTitleIds.containsAll(titles.keys)) {
    "Every title of the snapshot must be on a shelf."
  }

  val built =
    shelves.mapTo(HashSet(shelves.size)) { (category, titleIds) ->
      LibraryShelf.create(category, HashSet(titleIds))
    }

  return LibrarySnapshot.create(HashMap(titles), built)
}
