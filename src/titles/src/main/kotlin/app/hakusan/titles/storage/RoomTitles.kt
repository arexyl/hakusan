package app.hakusan.titles.storage

import app.hakusan.titles.ActualPositionResult
import app.hakusan.titles.ActualPositionUpdate
import app.hakusan.titles.ApplicationUuidFactory
import app.hakusan.titles.CategoryId
import app.hakusan.titles.ChapterBoundaryCompletion
import app.hakusan.titles.ChapterReconciliationResult
import app.hakusan.titles.CompletionResult
import app.hakusan.titles.FinalChapterCompletion
import app.hakusan.titles.InitialCategoryResolution
import app.hakusan.titles.LibraryAddFailure
import app.hakusan.titles.LibraryAddPolicy
import app.hakusan.titles.LibraryAddResult
import app.hakusan.titles.LibraryCategory
import app.hakusan.titles.LibraryCategorySelection
import app.hakusan.titles.LibraryMembership
import app.hakusan.titles.LibraryShelf
import app.hakusan.titles.LibraryShelfState
import app.hakusan.titles.LibraryTitle
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
    selection: LibraryCategorySelection,
  ): LibraryAddResult = database.withWriteTransaction {
    val title = dao.findTitleById(titleId.value)
      ?: return@withWriteTransaction LibraryAddResult.Failure(
        LibraryAddFailure.TitleNotFound,
      )

    val currentCategoryIds = dao.findTitleCategoryIds(title.storageId)
    if (currentCategoryIds.isNotEmpty()) {
      return@withWriteTransaction successfulMembership(
        titleId = titleId,
        categoryIds = currentCategoryIds.map(::CategoryId),
      )
    }

    val resolution = LibraryAddPolicy.resolve(
      categories = dao.loadCategories().map { it.toLibraryCategory() },
      selection = selection,
    )
    val categoryIds = when (resolution) {
      InitialCategoryResolution.CreateDefault -> setOf(
        CategoryId(
          dao.insertCategory(CategoryEntity(name = DEFAULT_CATEGORY_NAME)),
        ),
      )

      is InitialCategoryResolution.Assign -> resolution.categoryIds
      is InitialCategoryResolution.SelectionRequired -> {
        val result = LibraryAddResult.CategorySelectionRequired(
          resolution.categories,
        )
        return@withWriteTransaction result
      }

      is InitialCategoryResolution.CategoriesNotFound -> {
        return@withWriteTransaction LibraryAddResult.Failure(
          LibraryAddFailure.CategoriesNotFound.create(
            resolution.categoryIds,
          ),
        )
      }
    }

    dao.insertTitleCategories(
      categoryIds.map { categoryId ->
        TitleCategoryEntity(
          titleStorageId = title.storageId,
          categoryId = categoryId.value,
        )
      },
    )
    successfulMembership(titleId, categoryIds)
  }

  override suspend fun reconcileChapterSnapshot(
    input: ReconcileChapterSnapshot,
  ): ChapterReconciliationResult = reading.reconcileChapterSnapshot(input)

  override fun observeLibraryShelves(): Flow<LibraryShelfState> =
    dao.observeLibraryShelfRows()
      .map(::toShelfState)
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
    rows: List<LibraryShelfRow>,
  ): LibraryShelfState {
    val titlesById = LinkedHashMap<TitleId, LibraryTitle>()
    val shelvesByCategory = LinkedHashMap<CategoryId, ShelfAccumulator>()

    rows.forEach { row ->
      val category = LibraryCategory(
        id = CategoryId(row.categoryId),
        name = row.categoryName,
      )
      val shelf = shelvesByCategory.getOrPut(category.id) {
        ShelfAccumulator(category)
      }
      check(shelf.category == category) {
        "One category identity produced conflicting shelf metadata."
      }

      row.toLibraryTitle()?.let { title ->
        val previous = titlesById.putIfAbsent(title.id, title)
        check(previous == null || previous == title) {
          "One title identity produced conflicting shelf metadata."
        }
        check(shelf.titleIds.add(title.id)) {
          "One shelf contained a duplicate title identity."
        }
      }
    }

    return LibraryShelfState.create(
      titlesById = titlesById,
      shelves = shelvesByCategory.values.map { shelf ->
        LibraryShelf.create(
          category = shelf.category,
          titleIds = shelf.titleIds,
        )
      },
    )
  }

  private fun LibraryShelfRow.toLibraryTitle(): LibraryTitle? {
    val storedTitleId = titleId
    if (storedTitleId == null) {
      check(
        sourceIdentity == null &&
          sourceTitleKey == null &&
          titleDisplayName == null &&
          titleDescription == null
      ) {
        "An empty shelf row contained partial title metadata."
      }
      return null
    }
    return LibraryTitle(
      id = TitleId(storedTitleId),
      alias = SourceTitleAlias(
        sourceIdentity = checkNotNull(sourceIdentity),
        sourceTitleKey = checkNotNull(sourceTitleKey),
      ),
      displayName = checkNotNull(titleDisplayName),
      description = titleDescription,
    )
  }

  private fun successfulMembership(
    titleId: TitleId,
    categoryIds: Iterable<CategoryId>,
  ): LibraryAddResult.Success = LibraryAddResult.Success(
    LibraryMembership.create(
      titleId = titleId,
      categoryIds = categoryIds,
    ),
  )

  private fun CategoryEntity.toLibraryCategory(): LibraryCategory =
    LibraryCategory(
      id = CategoryId(id),
      name = name,
    )

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
