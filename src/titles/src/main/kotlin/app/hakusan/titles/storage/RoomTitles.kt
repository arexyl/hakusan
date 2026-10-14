package app.hakusan.titles.storage

import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import app.hakusan.titles.AcceptedTitleObservation
import app.hakusan.titles.ActualPositionResult
import app.hakusan.titles.ActualPositionUpdate
import app.hakusan.titles.CategoryId
import app.hakusan.titles.ChapterBoundaryCompletion
import app.hakusan.titles.ChapterBoundaryResult
import app.hakusan.titles.ChapterId
import app.hakusan.titles.ExplicitLibraryAddResult
import app.hakusan.titles.FinalChapterCompletion
import app.hakusan.titles.FinalCompletionResult
import app.hakusan.titles.LibraryAddResult
import app.hakusan.titles.LibraryCategorySelection
import app.hakusan.titles.LibraryMembership
import app.hakusan.titles.LibrarySnapshot
import app.hakusan.titles.ProgressSnapshot
import app.hakusan.titles.ReadingPosition
import app.hakusan.titles.SourceTitleAlias
import app.hakusan.titles.StoredStateViolationKind.ALIAS
import app.hakusan.titles.StoredStateViolationKind.IDENTITY
import app.hakusan.titles.StoredStateViolationKind.RESUME_STATE
import app.hakusan.titles.TitleId
import app.hakusan.titles.Titles
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

/**
 * The Room implementation of [Titles].
 *
 * Room write transactions are serialized and immediate, so a lookup inside a
 * transaction stays current until it commits. [newTitleId] and [newChapterId]
 * mint identities. Only tests replace them, to force identity collisions.
 * [clock] times every change of a stored resume position. Only tests replace
 * it, to control that time.
 *
 * [observationDispatcher] builds the values of both observations, so a
 * subscriber on another dispatcher never builds them. Only tests replace it, to
 * observe where the work runs. The default is [Dispatchers.Default], because
 * the work is CPU-bound mapping and comparison of materialized rows. Room runs
 * each query on its own query context, so no blocking call runs on this
 * dispatcher.
 */
internal class RoomTitles(
  private val database: TitlesDatabase,
  private val newTitleId: () -> TitleId = { TitleId.generate() },
  private val newChapterId: () -> ChapterId = { ChapterId.generate() },
  private val clock: Clock = Clock.System,
  private val observationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : Titles {
  private val dao = database.titlesDao()

  /**
   * Runs [block] in one write transaction.
   *
   * Room refreshes the observations only after a write transaction returns
   * normally, so a cancellation that reaches the caller after the commit skips
   * that refresh. This function requests it before it rethrows the
   * cancellation. After a rollback the refresh finds no change.
   */
  private suspend fun <R> writeTransaction(block: suspend () -> R): R =
    try {
      database.withWriteTransaction { block() }
    } catch (cancellation: CancellationException) {
      database.invalidationTracker.refreshAsync()

      throw cancellation
    }

  override suspend fun reconcileTitle(
    observation: AcceptedTitleObservation
  ): ProgressSnapshot = writeTransaction {
    val title = reconcileDetails(observation)

    val rows = dao.loadChapters(title.storageId)

    val known = KnownChapters.of(title.storageId, rows)

    val plan = planChapters(known, observation)

    applyPlan(title.storageId, plan)

    dao.readProgressSnapshot(title.storageId, title.id, title.alias)
  }

  /**
   * Resolves the title of [observation] by its exact alias, stores its details,
   * and returns its keys with the stored alias. An unknown alias inserts a new
   * title.
   */
  private suspend fun reconcileDetails(
    observation: AcceptedTitleObservation
  ): TitleKeys {
    val alias = observation.alias

    val rows = dao.findTitlesByAlias(alias.sourceIdentity, alias.sourceTitleKey)

    val titles = rows.map(StoredTitle::of)

    if (titles.size > 1) {
      violated(ALIAS, "Several stored titles rows share one source alias.")
    }

    val current = titles.singleOrNull() ?: return insertTitle(observation)

    if (current.alias != alias) {
      violated(ALIAS, "A stored titles row differs from its selecting alias.")
    }

    val isChanged =
      current.displayName != observation.displayName ||
        current.description != observation.description

    if (isChanged) {
      val count =
        dao.updateTitleDetails(
          storageId = current.storageId,
          displayName = observation.displayName,
          description = observation.description,
        )

      check(count == 1) { "A reconciled title disappeared in its transaction." }
    }

    return TitleKeys(current.storageId, current.id, current.alias)
  }

  /**
   * Inserts the title of [observation] with a new identity. A generated
   * identity that collides with a stored one is retried, and a bounded number
   * of collisions is a defect.
   */
  private suspend fun insertTitle(
    observation: AcceptedTitleObservation
  ): TitleKeys {
    repeat(MAX_IDENTITY_ATTEMPTS) {
      val id = newTitleId()

      val row =
        TitleEntity(
          storageId = 0,
          id = id.toStoredBytes(),
          sourceIdentity = observation.alias.sourceIdentity,
          sourceTitleKey = observation.alias.sourceTitleKey,
          displayName = observation.displayName,
          description = observation.description,
        )

      val storageId = dao.insertTitleOrIgnore(row)

      if (storageId > 0) {
        return TitleKeys(storageId, id, observation.alias)
      }
    }

    error("No generated title identity could be stored.")
  }

  /** Applies [plan] to the chapters of the title [titleStorageId]. */
  private suspend fun applyPlan(titleStorageId: Long, plan: ChapterPlan) {
    plan.clearedIndexes.forEach { storageId ->
      val count = dao.clearCanonicalIndex(storageId, titleStorageId)

      check(count == 1) {
        "A reconciled chapter disappeared in its transaction."
      }
    }

    plan.writes.forEach { write ->
      when (write) {
        is ChapterWrite.Update -> updateChapter(titleStorageId, write)
        is ChapterWrite.Insert -> insertChapter(titleStorageId, write)
      }
    }
  }

  private suspend fun updateChapter(
    titleStorageId: Long,
    write: ChapterWrite.Update,
  ) {
    val count =
      dao.updateChapter(
        storageId = write.storageId,
        titleStorageId = titleStorageId,
        displayName = write.displayName,
        canonicalIndex = write.canonicalIndex,
      )

    check(count == 1) { "A reconciled chapter disappeared in its transaction." }
  }

  /**
   * Inserts a new chapter with a new identity. A generated identity that
   * collides with a stored one is retried, and a bounded number of collisions
   * is a defect. A row ignored for another unique constraint is a planner
   * defect and fails at once.
   */
  private suspend fun insertChapter(
    titleStorageId: Long,
    write: ChapterWrite.Insert,
  ) {
    repeat(MAX_IDENTITY_ATTEMPTS) {
      val row =
        ChapterEntity(
          storageId = 0,
          id = newChapterId().toStoredBytes(),
          titleStorageId = titleStorageId,
          sourceChapterKey = write.localKey.text,
          displayName = write.displayName,
          canonicalIndex = write.canonicalIndex,
        )

      val storageId = dao.insertChapterOrIgnore(row)

      if (storageId > 0) {
        return
      }

      check(dao.hasChapterWithId(row.id)) {
        "A planned chapter conflicts with a stored chapter of its title."
      }
    }

    error("No generated chapter identity could be stored.")
  }

  override suspend fun addToLibrary(titleId: TitleId): LibraryAddResult =
    writeTransaction {
      addAutomatically(titleId)
    }

  override suspend fun addToLibrary(
    titleId: TitleId,
    selection: LibraryCategorySelection,
  ): ExplicitLibraryAddResult = writeTransaction {
    addExplicitly(titleId, selection)
  }

  /** Applies the automatic Add of [titleId]. Call it inside the transaction. */
  private suspend fun addAutomatically(titleId: TitleId): LibraryAddResult {
    val title = findTitle(titleId) ?: return LibraryAddResult.TitleNotFound

    val membership = readMembership(title)

    if (membership != null) {
      return LibraryAddResult.Success(membership)
    }

    val categories = CurrentCategories.of(dao.loadCategories())

    return when (val decision = decideAutomaticAdd(categories)) {
      AutomaticAddDecision.CreateDefault -> {
        val defaultId = insertDefault()

        LibraryAddResult.Success(enterLibrary(title, setOf(defaultId)))
      }

      is AutomaticAddDecision.Assign ->
        LibraryAddResult.Success(
          enterLibrary(title, setOf(decision.categoryId))
        )

      is AutomaticAddDecision.SelectionRequired ->
        LibraryAddResult.CategorySelectionRequired.of(decision.categories)
    }
  }

  /**
   * Applies the explicit Add of [titleId] in [selection]. Call it inside the
   * transaction.
   */
  private suspend fun addExplicitly(
    titleId: TitleId,
    selection: LibraryCategorySelection,
  ): ExplicitLibraryAddResult {
    val title =
      findTitle(titleId) ?: return ExplicitLibraryAddResult.TitleNotFound

    val membership = readMembership(title)

    if (membership != null) {
      return ExplicitLibraryAddResult.Success(membership)
    }

    val categories = CurrentCategories.of(dao.loadCategories())

    return when (val decision = decideExplicitAdd(categories, selection)) {
      is ExplicitAddDecision.Assign ->
        ExplicitLibraryAddResult.Success(
          enterLibrary(title, decision.categoryIds)
        )

      is ExplicitAddDecision.CategoriesNotFound ->
        ExplicitLibraryAddResult.CategoriesNotFound.of(decision.categoryIds)
    }
  }

  /** Returns the stored title with [titleId], or null when none has it. */
  private suspend fun findTitle(titleId: TitleId): StoredTitle? {
    val rows = dao.findTitlesById(titleId.toStoredBytes())

    val titles = rows.map(StoredTitle::of)

    if (titles.size > 1) {
      violated(IDENTITY, "Several stored titles rows share one identity.")
    }

    val title = titles.singleOrNull() ?: return null

    if (title.id != titleId) {
      violated(IDENTITY, "A stored titles row differs from its identity.")
    }

    return title
  }

  /** Returns the membership of [title], or null when it is not a member. */
  private suspend fun readMembership(title: StoredTitle): LibraryMembership? {
    val rows = dao.loadTitleCategories(title.storageId)

    return mapMembership(title.storageId, title.id, rows)
  }

  /** Inserts the category Default and returns its generated identity. */
  private suspend fun insertDefault(): CategoryId {
    val row = CategoryEntity(id = 0, name = DEFAULT_CATEGORY_NAME)

    val key = dao.insertCategory(row)

    return checkNotNull(CategoryId.ofOrNull(key)) {
      "The database generated a category identity that is not positive."
    }
  }

  /**
   * Makes [title], which has no category association, a member of the existing
   * [categoryIds] and returns its membership.
   *
   * This is the one mutator that brings a title into the Library. Persistent
   * resume state exists only for a Library title, so the title must have none
   * yet: a stored one is a violation that membership would otherwise
   * legitimize. The mutator writes no resume position.
   */
  private suspend fun enterLibrary(
    title: StoredTitle,
    categoryIds: Set<CategoryId>,
  ): LibraryMembership {
    val positions =
      dao.loadResumePositions(title.storageId).map(StoredResumePosition::of)

    if (positions.isNotEmpty()) {
      violated(
        RESUME_STATE,
        "A title outside the Library has a persistent resume position.",
      )
    }

    val associations = categoryIds.map { categoryId ->
      TitleCategoryEntity(
        titleStorageId = title.storageId,
        categoryId = categoryId.key,
      )
    }

    dao.insertTitleCategories(associations)

    return LibraryMembership.of(title.id, categoryIds)
  }

  override fun observeLibrary(): Flow<LibrarySnapshot> =
    dao.observeLibraryRows().toLibrarySnapshots(observationDispatcher)

  override fun observeLibraryTitleIds(): Flow<Set<TitleId>> =
    dao.observeLibraryTitles().toLibraryTitleIds(observationDispatcher)

  override suspend fun readProgress(titleId: TitleId): ProgressSnapshot? =
    database.withReadTransaction {
      readProgressOf(titleId)
    }

  /**
   * Reads the progress of [titleId], or returns null when no stored title has
   * it. Call it inside a transaction.
   */
  private suspend fun readProgressOf(titleId: TitleId): ProgressSnapshot? {
    val title = findTitle(titleId) ?: return null

    return dao.readProgressSnapshot(title.storageId, title.id, title.alias)
  }

  override suspend fun recordActualPosition(
    update: ActualPositionUpdate
  ): ActualPositionResult = writeTransaction {
    applyActualPosition(update)
  }

  /** Applies [update]. Call it inside the transaction. */
  private suspend fun applyActualPosition(
    update: ActualPositionUpdate
  ): ActualPositionResult {
    val position = update.position

    val title =
      findTitle(position.titleId) ?: return ActualPositionResult.TitleNotFound

    val chapter =
      findChapter(title, position.chapterId)
        ?: return ActualPositionResult.ChapterNotFound

    val facts = readPositionFacts(title, chapter)

    return when (val decision = decidePosition(update.recency, facts)) {
      PositionDecision.Record -> {
        writeLibraryPosition(title, chapter, position, facts)

        ActualPositionResult.Persisted
      }

      is PositionDecision.Skip ->
        ActualPositionResult.NotPersisted(decision.reason)
    }
  }

  override suspend fun completeChapterBoundary(
    completion: ChapterBoundaryCompletion
  ): ChapterBoundaryResult = writeTransaction {
    applyBoundary(completion)
  }

  /**
   * Applies [completion]. Call it inside the transaction.
   *
   * Each chapter role resolves by its own identity, never by row order.
   */
  private suspend fun applyBoundary(
    completion: ChapterBoundaryCompletion
  ): ChapterBoundaryResult {
    val position = completion.startedPosition

    val title =
      findTitle(position.titleId) ?: return ChapterBoundaryResult.TitleNotFound

    val completed = findChapter(title, completion.completedChapterId)

    val started = findChapter(title, position.chapterId)

    if (completed == null || started == null) {
      return ChapterBoundaryResult.ChapterNotFound
    }

    val facts = readPositionFacts(title, started)

    val plan =
      planBoundary(
        completedIndex = completed.canonicalIndex,
        startedIndex = started.canonicalIndex,
        recency = completion.recency,
        startedFacts = facts,
      )

    return when (plan) {
      BoundaryPlan.AdjacencyChanged ->
        ChapterBoundaryResult.CanonicalAdjacencyChanged

      is BoundaryPlan.Apply -> {
        markChapterRead(title, completed)

        applyStartedPosition(
          title,
          started,
          position,
          facts,
          plan.startedPosition,
        )
      }
    }
  }

  /**
   * Applies [decision] to the started [position] of a boundary, in the chapter
   * [started] with the stored [facts], and returns the boundary result.
   */
  private suspend fun applyStartedPosition(
    title: StoredTitle,
    started: StoredChapter,
    position: ReadingPosition,
    facts: PositionFacts,
    decision: PositionDecision,
  ): ChapterBoundaryResult =
    when (decision) {
      PositionDecision.Record -> {
        writeLibraryPosition(title, started, position, facts)

        ChapterBoundaryResult.Completed
      }

      is PositionDecision.Skip ->
        ChapterBoundaryResult.CompletedWithoutStartedPosition(decision.reason)
    }

  override suspend fun completeFinalChapter(
    completion: FinalChapterCompletion
  ): FinalCompletionResult = writeTransaction {
    applyFinalCompletion(completion)
  }

  /** Applies [completion]. Call it inside the transaction. */
  private suspend fun applyFinalCompletion(
    completion: FinalChapterCompletion
  ): FinalCompletionResult {
    val title =
      findTitle(completion.titleId)
        ?: return FinalCompletionResult.TitleNotFound

    val chapter =
      findChapter(title, completion.chapterId)
        ?: return FinalCompletionResult.ChapterNotFound

    val index = chapter.canonicalIndex

    val isFinal = index != null && !dao.hasChapterAfter(title.storageId, index)

    if (!isFinal) {
      return FinalCompletionResult.CanonicalFinalityChanged
    }

    markChapterRead(title, chapter)

    return FinalCompletionResult.Completed
  }

  /**
   * Returns the chapter of [title] with [chapterId], or null when no stored
   * chapter has it or when it belongs to another title.
   */
  private suspend fun findChapter(
    title: StoredTitle,
    chapterId: ChapterId,
  ): StoredChapter? {
    val rows = dao.findChaptersById(chapterId.toStoredBytes())

    val chapters = rows.map(StoredChapter::of)

    if (chapters.size > 1) {
      violated(IDENTITY, "Several stored chapters rows share one identity.")
    }

    val chapter = chapters.singleOrNull() ?: return null

    if (chapter.id != chapterId) {
      violated(IDENTITY, "A stored chapters row differs from its identity.")
    }

    return chapter.takeIf { chapter.titleStorageId == title.storageId }
  }

  /** Reads the facts that decide a position of [title] in [chapter]. */
  private suspend fun readPositionFacts(
    title: StoredTitle,
    chapter: StoredChapter,
  ): PositionFacts {
    val isLibraryMember = dao.isLibraryMember(title.storageId)

    val isChapterRead = dao.isChapterRead(chapter.storageId)

    return PositionFacts(
      isLibraryMember = isLibraryMember,
      isChapterRead = isChapterRead,
    )
  }

  /**
   * Adds [chapter] of [title] to the read set and deletes a position of the
   * title on it.
   *
   * This is the one mutator that marks a chapter read. A read chapter keeps no
   * resume position, so both effects belong together. The insertion is
   * attempted even when the chapter is already read, which keeps a repeated
   * completion idempotent. The deleted row is not retained, so it is not read.
   */
  private suspend fun markChapterRead(
    title: StoredTitle,
    chapter: StoredChapter,
  ) {
    dao.insertReadChapterOrIgnore(ReadChapterEntity(chapter.storageId))
    dao.deleteResumePosition(title.storageId, chapter.storageId)
  }

  /**
   * Makes [position] in [chapter] the persistent resume position of [title],
   * whose stored [facts] the transaction read.
   *
   * This is the one mutator that writes a resume position, and it keeps two
   * rules: persistent resume state exists only for a Library title, and a read
   * chapter keeps no position. [facts] are the membership and read state that
   * this write transaction read for [chapter], so they are current here and are
   * not read again. The rules are checked against them as a defect assertion: a
   * caller writes only on [PositionDecision.Record] from [decidePosition] over
   * the same facts, which already excludes both cases.
   *
   * A position equal in chapter, unit kind, and unit index to the stored one
   * changes nothing, so its stored time stays. Otherwise the position takes the
   * current time of [clock].
   *
   * @throws IllegalStateException if [facts] break either rule. It is a defect
   *   of the caller, and nothing persists.
   */
  private suspend fun writeLibraryPosition(
    title: StoredTitle,
    chapter: StoredChapter,
    position: ReadingPosition,
    facts: PositionFacts,
  ) {
    check(facts.isLibraryMember) {
      "A persistent resume position requires Library membership."
    }

    check(!facts.isChapterRead) { "A read chapter keeps no resume position." }

    val unitKind = StoredContentUnitKind.of(position.unitKind)

    val updatedAt = clock.now().toEpochMilliseconds()

    dao.writeResumePosition(
      titleStorageId = title.storageId,
      chapterStorageId = chapter.storageId,
      unitKind = unitKind.storedText,
      unitIndex = position.unitIndex,
      updatedAt = updatedAt,
    )
  }

  /**
   * The storage key, the identity, and the exact stored source alias of one
   * reconciled title.
   */
  private data class TitleKeys(
    val storageId: Long,
    val id: TitleId,
    val alias: SourceTitleAlias,
  )

  internal companion object {
    /** The number of generated identities tried before a collision fails. */
    const val MAX_IDENTITY_ATTEMPTS = 16

    /** The initial name of the category that the first Add creates. */
    private const val DEFAULT_CATEGORY_NAME = "Default"
  }
}
