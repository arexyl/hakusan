package app.hakusan.titles.storage

import androidx.room3.withWriteTransaction
import app.hakusan.titles.AcceptedTitleObservation
import app.hakusan.titles.CategoryId
import app.hakusan.titles.ChapterId
import app.hakusan.titles.ExplicitLibraryAddResult
import app.hakusan.titles.LibraryAddResult
import app.hakusan.titles.LibraryCategorySelection
import app.hakusan.titles.LibraryMembership
import app.hakusan.titles.ProgressSnapshot
import app.hakusan.titles.StoredStateViolationKind.ALIAS
import app.hakusan.titles.StoredStateViolationKind.IDENTITY
import app.hakusan.titles.StoredStateViolationKind.RESUME_STATE
import app.hakusan.titles.TitleId
import app.hakusan.titles.Titles

/**
 * The Room implementation of [Titles].
 *
 * Room write transactions are serialized and immediate, so a lookup inside a
 * transaction stays current until it commits. [newTitleId] and [newChapterId]
 * mint identities. Only tests replace them, to force identity collisions.
 */
internal class RoomTitles(
  private val database: TitlesDatabase,
  private val newTitleId: () -> TitleId = { TitleId.generate() },
  private val newChapterId: () -> ChapterId = { ChapterId.generate() },
) : Titles {
  private val dao = database.titlesDao()

  override suspend fun reconcileTitle(
    observation: AcceptedTitleObservation
  ): ProgressSnapshot = database.withWriteTransaction {
    val title = reconcileDetails(observation)

    val rows = dao.loadChapters(title.storageId)

    val known = KnownChapters.of(title.storageId, rows)

    val plan = planChapters(known, observation)

    applyPlan(title.storageId, plan)

    dao.readProgressSnapshot(title.storageId, title.id)
  }

  /**
   * Resolves the title of [observation] by its exact alias, stores its details,
   * and returns its keys. An unknown alias inserts a new title.
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

    return TitleKeys(current.storageId, current.id)
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
        return TitleKeys(storageId, id)
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
    database.withWriteTransaction {
      addAutomatically(titleId)
    }

  override suspend fun addToLibrary(
    titleId: TitleId,
    selection: LibraryCategorySelection,
  ): ExplicitLibraryAddResult = database.withWriteTransaction {
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

  /** The storage key and the identity of one reconciled title. */
  private data class TitleKeys(val storageId: Long, val id: TitleId)

  internal companion object {
    /** The number of generated identities tried before a collision fails. */
    const val MAX_IDENTITY_ATTEMPTS = 16

    /** The initial name of the category that the first Add creates. */
    private const val DEFAULT_CATEGORY_NAME = "Default"
  }
}
