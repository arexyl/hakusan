package app.hakusan.titles.storage

import app.hakusan.titles.ActualPositionResult
import app.hakusan.titles.ActualPositionUpdate
import app.hakusan.titles.ApplicationUuidFactory
import app.hakusan.titles.AutomaticCategoryResolution
import app.hakusan.titles.CategoryAssignment
import app.hakusan.titles.CategoryId
import app.hakusan.titles.ChapterBoundaryCompletion
import app.hakusan.titles.ChapterReconciliationResult
import app.hakusan.titles.CompletionResult
import app.hakusan.titles.ExplicitCategoryResolution
import app.hakusan.titles.ExplicitLibraryAddFailure
import app.hakusan.titles.ExplicitLibraryAddResult
import app.hakusan.titles.FinalChapterCompletion
import app.hakusan.titles.LibraryAddPolicy
import app.hakusan.titles.LibraryAddResult
import app.hakusan.titles.LibraryCategory
import app.hakusan.titles.LibraryCategorySelection
import app.hakusan.titles.LibraryMembership
import app.hakusan.titles.LibraryResumeAvailability
import app.hakusan.titles.LibraryShelf
import app.hakusan.titles.LibraryShelfState
import app.hakusan.titles.LibrarySummaryState
import app.hakusan.titles.LibraryTitle
import app.hakusan.titles.LibraryTitleProgressSummary
import app.hakusan.titles.ReconcileChapterSnapshot
import app.hakusan.titles.ReconcileSourceTitle
import app.hakusan.titles.SourceTitleAlias
import app.hakusan.titles.TitleId
import app.hakusan.titles.TitleReadingProgress
import app.hakusan.titles.Titles
import androidx.room3.withWriteTransaction
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal class RoomTitles(
  private val database: TitlesDatabase,
  private val createUuid: () -> UUID = ApplicationUuidFactory::create,
) : Titles {
  private val dao = database.titlesDao()
  private val reading = RoomReading(database, createUuid)

  override suspend fun reconcileSourceTitle(
    input: ReconcileSourceTitle,
  ): TitleId = database.withWriteTransaction {
    val current = dao.findTitleByAlias(
      sourceIdentity = input.alias.sourceIdentity,
      sourceTitleKey = input.alias.sourceTitleKey,
    )
    if (current != null) {
      if (
        current.displayName != input.displayName ||
        current.description != input.description
      ) {
        check(
          dao.updateTitleMetadata(
            storageId = current.storageId,
            displayName = input.displayName,
            description = input.description,
          ) == 1,
        ) {
          "Reconciled title disappeared during its transaction."
        }
      }
      return@withWriteTransaction TitleId(current.id)
    }

    // The IMMEDIATE write transaction prevents another alias insert after the
    // miss above. An ignored valid candidate can only collide on its UUID.
    repeat(MAX_UUID_GENERATION_ATTEMPTS) {
      val id = createUuid()
      val storageId = dao.insertTitleOrIgnore(
        TitleEntity(
          storageId = 0,
          id = id,
          sourceIdentity = input.alias.sourceIdentity,
          sourceTitleKey = input.alias.sourceTitleKey,
          displayName = input.displayName,
          description = input.description,
        ),
      )
      if (storageId > 0) {
        return@withWriteTransaction TitleId(id)
      }
    }
    error("Unable to allocate a unique title UUIDv7.")
  }

  override suspend fun addToLibrary(
    titleId: TitleId,
  ): LibraryAddResult = database.withWriteTransaction {
    when (val context = loadLibraryAddContext(titleId)) {
      LibraryAddContext.TitleNotFound -> LibraryAddResult.TitleNotFound

      is LibraryAddContext.ExistingMembership ->
        LibraryAddResult.Success(context.membership)

      is LibraryAddContext.Nonmember -> when (
        val resolution = LibraryAddPolicy.resolveAutomatic(context.categories)
      ) {
        AutomaticCategoryResolution.CreateDefault -> {
          val categoryIds = setOf(
            CategoryId(
              dao.insertCategory(CategoryEntity(name = DEFAULT_CATEGORY_NAME)),
            ),
          )
          LibraryAddResult.Success(
            persistMembership(context.title, titleId, categoryIds),
          )
        }

        is CategoryAssignment -> LibraryAddResult.Success(
          persistMembership(context.title, titleId, resolution.categoryIds),
        )

        is AutomaticCategoryResolution.SelectionRequired ->
          LibraryAddResult.CategorySelectionRequired(resolution.categories)
      }
    }
  }

  override suspend fun addToLibrary(
    titleId: TitleId,
    selection: LibraryCategorySelection,
  ): ExplicitLibraryAddResult = database.withWriteTransaction {
    when (val context = loadLibraryAddContext(titleId)) {
      LibraryAddContext.TitleNotFound -> ExplicitLibraryAddResult.Failure(
        ExplicitLibraryAddFailure.TitleNotFound,
      )

      is LibraryAddContext.ExistingMembership ->
        ExplicitLibraryAddResult.Success(context.membership)

      is LibraryAddContext.Nonmember -> when (
        val resolution = LibraryAddPolicy.resolveExplicit(
          categories = context.categories,
          selection = selection,
        )
      ) {
        is CategoryAssignment ->
          ExplicitLibraryAddResult.Success(
            persistMembership(context.title, titleId, resolution.categoryIds),
          )

        is ExplicitCategoryResolution.CategoriesNotFound ->
          ExplicitLibraryAddResult.Failure(
            ExplicitLibraryAddFailure.CategoriesNotFound.create(
              resolution.categoryIds,
            ),
          )
      }
    }
  }

  private suspend fun loadLibraryAddContext(
    titleId: TitleId,
  ): LibraryAddContext {
    val title = dao.findTitleById(titleId.value)
      ?: return LibraryAddContext.TitleNotFound
    val currentCategoryIds = dao.findTitleCategoryIds(title.storageId)
    if (currentCategoryIds.isNotEmpty()) {
      return LibraryAddContext.ExistingMembership(
        LibraryMembership.create(
          titleId = titleId,
          categoryIds = currentCategoryIds.map(::CategoryId),
        ),
      )
    }
    return LibraryAddContext.Nonmember(
      title = title,
      categories = dao.loadCategories().map { it.toLibraryCategory() },
    )
  }

  private suspend fun persistMembership(
    title: TitleEntity,
    titleId: TitleId,
    categoryIds: Set<CategoryId>,
  ): LibraryMembership {
    dao.insertTitleCategories(
      categoryIds.map { categoryId ->
        TitleCategoryEntity(
          titleStorageId = title.storageId,
          categoryId = categoryId.value,
        )
      },
    )
    return LibraryMembership.create(titleId, categoryIds)
  }

  override suspend fun reconcileChapterSnapshot(
    input: ReconcileChapterSnapshot,
  ): ChapterReconciliationResult = reading.reconcileChapterSnapshot(input)

  override fun observeLibraryShelves(): Flow<LibraryShelfState> =
    dao.observeLibraryShelfRows()
      .map { rows -> toShelfState(rows) }
      .distinctUntilChanged()

  override fun observeLibrarySummary(): Flow<LibrarySummaryState> =
    dao.observeLibrarySummaryRows()
      .map(::toLibrarySummary)
      .distinctUntilChanged()

  override fun observeReadingProgress(
    titleId: TitleId,
  ): Flow<TitleReadingProgress?> = reading.observeReadingProgress(titleId)

  override suspend fun recordActualPosition(
    update: ActualPositionUpdate,
  ): ActualPositionResult = reading.recordActualPosition(update)

  override suspend fun completeChapterBoundary(
    completion: ChapterBoundaryCompletion,
  ): CompletionResult = reading.completeChapterBoundary(completion)

  override suspend fun completeFinalChapter(
    completion: FinalChapterCompletion,
  ): CompletionResult = reading.completeFinalChapter(completion)

  private fun toShelfState(
    rows: List<LibraryShelfProjection>,
  ): LibraryShelfState {
    val titlesById = LinkedHashMap<TitleId, LibraryTitle>()
    val shelvesByCategoryId = LinkedHashMap<Long, ShelfAccumulator>()

    rows.forEach { row ->
      val shelf = shelvesByCategoryId.getOrPut(row.categoryId) {
        ShelfAccumulator(
          LibraryCategory(
            id = CategoryId(row.categoryId),
            name = row.categoryName,
          ),
        )
      }
      check(shelf.category.name == row.categoryName) {
        "One category identity produced conflicting shelf metadata."
      }

      val storedTitleId = row.titleId
      if (storedTitleId == null) {
        check(
          row.sourceIdentity == null &&
            row.sourceTitleKey == null &&
            row.titleDisplayName == null &&
            row.titleDescription == null
        ) {
          "An empty shelf row contained partial title metadata."
        }
        return@forEach
      }

      val titleId = TitleId(storedTitleId)
      val existing = titlesById[titleId]
      val title = when {
        existing == null -> row.toLibraryTitle(titleId).also { created ->
          titlesById[titleId] = created
        }

        existing.hasSameMetadataAs(row) -> existing
        else -> {
          // Preserve field validation on corrupted projection rows.
          row.toLibraryTitle(titleId)
          error("One title identity produced conflicting shelf metadata.")
        }
      }
      check(shelf.titleIds.add(title.id)) {
        "One shelf contained a duplicate title identity."
      }
    }

    return LibraryShelfState.create(
      titlesById = titlesById,
      shelves = shelvesByCategoryId.values.map { shelf ->
        LibraryShelf.create(
          category = shelf.category,
          titleIds = shelf.titleIds,
        )
      },
    )
  }

  private fun toLibrarySummary(
    rows: List<LibrarySummaryRow>,
  ): LibrarySummaryState {
    val shelfState = toShelfState(rows)
    val progressByTitleId =
      LinkedHashMap<TitleId, LibraryTitleProgressSummary>()
    rows.forEach { row ->
      val storedTitleId = row.titleId
      if (storedTitleId == null) {
        check(
          row.chapterCount == 0L &&
            row.readChapterCount == 0L &&
            !row.hasResume &&
            !row.resumeIsAvailable &&
            !row.resumeIsRead,
        ) {
          "An empty shelf row contained reading progress."
        }
        return@forEach
      }

      val titleId = TitleId(storedTitleId)
      val progress = row.toProgress()
      val existing = progressByTitleId[titleId]
      if (existing == null) {
        progressByTitleId[titleId] = progress
      } else {
        check(existing == progress) {
          "One title identity produced conflicting Library progress."
        }
      }
    }
    return LibrarySummaryState.create(
      shelfState = shelfState,
      progressByTitleId = progressByTitleId,
    )
  }

  private fun LibraryShelfProjection.toLibraryTitle(
    titleId: TitleId,
  ): LibraryTitle = LibraryTitle(
    id = titleId,
    alias = SourceTitleAlias(
      sourceIdentity = checkNotNull(sourceIdentity),
      sourceTitleKey = checkNotNull(sourceTitleKey),
    ),
    displayName = checkNotNull(titleDisplayName),
    description = titleDescription,
  )

  private fun LibraryTitle.hasSameMetadataAs(
    row: LibraryShelfProjection,
  ): Boolean =
    alias.sourceIdentity == row.sourceIdentity &&
      alias.sourceTitleKey == row.sourceTitleKey &&
      displayName == row.titleDisplayName &&
      description == row.titleDescription

  private fun LibrarySummaryRow.toProgress(): LibraryTitleProgressSummary {
    check(hasResume || (!resumeIsAvailable && !resumeIsRead)) {
      "Library resume facts require a retained position."
    }
    check(!resumeIsRead) {
      "A read chapter must not retain a Library resume position."
    }
    return LibraryTitleProgressSummary(
      chapterCount = chapterCount.toLibraryCount("chapter"),
      readChapterCount = readChapterCount.toLibraryCount("read chapter"),
      resumeAvailability = when {
        !hasResume -> LibraryResumeAvailability.NONE
        resumeIsAvailable -> LibraryResumeAvailability.AVAILABLE
        else -> LibraryResumeAvailability.TEMPORARILY_UNAVAILABLE
      },
    )
  }

  private fun Long.toLibraryCount(name: String): Int {
    check(this in 0L..Int.MAX_VALUE.toLong()) {
      "Library $name count is outside the supported range."
    }
    return toInt()
  }

  private fun CategoryEntity.toLibraryCategory(): LibraryCategory =
    LibraryCategory(
      id = CategoryId(id),
      name = name,
    )

  private sealed interface LibraryAddContext {
    data object TitleNotFound : LibraryAddContext

    data class ExistingMembership(
      val membership: LibraryMembership,
    ) : LibraryAddContext

    data class Nonmember(
      val title: TitleEntity,
      val categories: List<LibraryCategory>,
    ) : LibraryAddContext
  }

  private class ShelfAccumulator(
    val category: LibraryCategory,
    val titleIds: MutableSet<TitleId> = LinkedHashSet(),
  )

  private companion object {
    const val DEFAULT_CATEGORY_NAME = "Default"
    const val MAX_UUID_GENERATION_ATTEMPTS = 16
  }
}

internal fun TitlesDatabase.asTitles(
  createUuid: () -> UUID = ApplicationUuidFactory::create,
): Titles = RoomTitles(
  database = this,
  createUuid = createUuid,
)
