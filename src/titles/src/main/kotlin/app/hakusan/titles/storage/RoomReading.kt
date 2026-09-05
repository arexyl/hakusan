package app.hakusan.titles.storage

import app.hakusan.titles.ActualPositionNotPersisted
import app.hakusan.titles.ActualPositionResult
import app.hakusan.titles.ActualPositionUpdate
import app.hakusan.titles.CanonicalChapterSnapshot
import app.hakusan.titles.Chapter
import app.hakusan.titles.ChapterBoundaryCompletion
import app.hakusan.titles.ChapterId
import app.hakusan.titles.ChapterReadingState
import app.hakusan.titles.ChapterReconciliationFailure
import app.hakusan.titles.ChapterReconciliationResult
import app.hakusan.titles.CompletionResult
import app.hakusan.titles.FinalChapterCompletion
import app.hakusan.titles.LibraryResumePosition
import app.hakusan.titles.ProgressEventRecency
import app.hakusan.titles.ReadingContentUnitKind
import app.hakusan.titles.ReadingPosition
import app.hakusan.titles.ReadingProgressFailure
import app.hakusan.titles.ReconcileChapterSnapshot
import app.hakusan.titles.ReconcileSourceChapter
import app.hakusan.titles.SourceChapterAlias
import app.hakusan.titles.SourceTitleAlias
import app.hakusan.titles.TitleId
import app.hakusan.titles.TitleReadingProgress
import androidx.room3.withWriteTransaction
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal class RoomReading(
  private val database: TitlesDatabase,
  private val createUuid: () -> UUID,
) {
  private val readingDao = database.readingDao()
  private val titlesDao = database.titlesDao()

  suspend fun reconcileChapterSnapshot(
    input: ReconcileChapterSnapshot,
  ): ChapterReconciliationResult = database.withWriteTransaction {
    val title = titlesDao.findTitleByAlias(
      sourceIdentity = input.titleAlias.sourceIdentity,
      sourceTitleKey = input.titleAlias.sourceTitleKey,
    ) ?: return@withWriteTransaction ChapterReconciliationResult.Failure(
      ChapterReconciliationFailure.TitleNotFound,
    )
    val storedChapters = readingDao.loadChapters(title.storageId)
    val chaptersByKey = storedChapters.associateBy(
      ChapterEntity::sourceChapterKey,
    )
    check(chaptersByKey.size == storedChapters.size) {
      "Stored source chapter aliases must be unique within a title."
    }
    val current = storedChapters
      .filter { it.canonicalIndex != null }
      .sortedBy { it.canonicalIndex }
    check(current.map { it.canonicalIndex } == current.indices.toList()) {
      "Stored canonical chapter indexes must be dense."
    }

    if (current.matches(input.chapters)) {
      return@withWriteTransaction successfulReconciliation(
        title = title,
        titleAlias = input.titleAlias,
        chapters = current,
      )
    }

    check(
      readingDao.clearCanonicalIndexes(title.storageId) == current.size,
    ) {
      "Canonical chapters changed while clearing their indexes."
    }
    val reconciled = input.chapters.mapIndexed { canonicalIndex, observed ->
      val stored = chaptersByKey[observed.alias.sourceChapterKey]
      if (stored == null) {
        insertChapter(
          title = title,
          observed = observed,
          canonicalIndex = canonicalIndex,
        )
      } else {
        updateChapter(
          chapter = stored,
          displayName = observed.displayName,
          canonicalIndex = canonicalIndex,
        )
      }
    }
    successfulReconciliation(
      title = title,
      titleAlias = input.titleAlias,
      chapters = reconciled,
    )
  }

  fun observeReadingProgress(
    titleId: TitleId,
  ): Flow<TitleReadingProgress?> =
    readingDao.observeReadingProgressRows(titleId.value)
      .map(::toReadingProgress)
      .distinctUntilChanged()

  suspend fun recordActualPosition(
    update: ActualPositionUpdate,
  ): ActualPositionResult = database.withWriteTransaction {
    val position = update.position
    val title = titlesDao.findTitleById(position.titleId.value)
      ?: return@withWriteTransaction actualPositionFailure(
        ReadingProgressFailure.TitleNotFound,
      )
    val chapter = readingDao.findChapterByIdForTitle(
      titleStorageId = title.storageId,
      chapterId = position.chapterId.value,
    ) ?: return@withWriteTransaction actualPositionFailure(
      ReadingProgressFailure.ChaptersNotFound.create(
        listOf(position.chapterId),
      ),
    )

    if (update.recency == ProgressEventRecency.REORDERED) {
      return@withWriteTransaction ActualPositionResult.NotPersisted(
        reason = ActualPositionNotPersisted.REORDERED_EVENT,
        progress = loadReadingProgress(title.id),
      )
    }
    if (readingDao.isChapterRead(chapter.storageId)) {
      return@withWriteTransaction ActualPositionResult.NotPersisted(
        reason = ActualPositionNotPersisted.CHAPTER_ALREADY_READ,
        progress = loadReadingProgress(title.id),
      )
    }
    if (!readingDao.hasLibraryMembership(title.storageId)) {
      return@withWriteTransaction ActualPositionResult.NotPersisted(
        reason = ActualPositionNotPersisted.TITLE_NOT_IN_LIBRARY,
        progress = loadReadingProgress(title.id),
      )
    }

    val storedPosition = position.toEntity(title, chapter)
    val currentPosition = readingDao.findLibraryResumePosition(title.storageId)
    if (currentPosition != storedPosition) {
      readingDao.upsertLibraryResumePosition(storedPosition)
    }
    ActualPositionResult.Persisted(loadReadingProgress(title.id))
  }

  suspend fun completeChapterBoundary(
    completion: ChapterBoundaryCompletion,
  ): CompletionResult = database.withWriteTransaction {
    val titleId = completion.startedPosition.titleId
    val title = titlesDao.findTitleById(titleId.value)
      ?: return@withWriteTransaction completionFailure(
        ReadingProgressFailure.TitleNotFound,
      )
    val completed = readingDao.findChapterByIdForTitle(
      titleStorageId = title.storageId,
      chapterId = completion.completedChapterId.value,
    )
    val started = readingDao.findChapterByIdForTitle(
      titleStorageId = title.storageId,
      chapterId = completion.startedPosition.chapterId.value,
    )
    val missingIds = buildSet {
      if (completed == null) {
        add(completion.completedChapterId)
      }
      if (started == null) {
        add(completion.startedPosition.chapterId)
      }
    }
    if (missingIds.isNotEmpty()) {
      return@withWriteTransaction completionFailure(
        ReadingProgressFailure.ChaptersNotFound.create(missingIds),
      )
    }
    checkNotNull(completed)
    checkNotNull(started)

    if (!completed.isImmediatelyBefore(started)) {
      return@withWriteTransaction completionFailure(
        ReadingProgressFailure.CanonicalSequenceChanged.create(
          listOf(
            completion.completedChapterId,
            completion.startedPosition.chapterId,
          ),
        ),
      )
    }

    val previousPosition = readingDao.findLibraryResumePosition(
      title.storageId,
    )
    if (!readingDao.isChapterRead(completed.storageId)) {
      readingDao.insertReadChapterOrIgnore(
        ReadChapterEntity(completed.storageId),
      )
    }
    if (previousPosition?.chapterStorageId == completed.storageId) {
      readingDao.deleteLibraryResumePosition(
        titleStorageId = title.storageId,
        chapterStorageId = completed.storageId,
      )
    }
    if (completion.recency == ProgressEventRecency.REORDERED) {
      return@withWriteTransaction completionSuccess(title.id)
    }

    val canStoreStartedPosition =
      readingDao.hasLibraryMembership(title.storageId) &&
        !readingDao.isChapterRead(started.storageId)
    if (canStoreStartedPosition) {
      readingDao.upsertLibraryResumePosition(
        completion.startedPosition.toEntity(title, started),
      )
    }
    completionSuccess(title.id)
  }

  suspend fun completeFinalChapter(
    completion: FinalChapterCompletion,
  ): CompletionResult = database.withWriteTransaction {
    val title = titlesDao.findTitleById(completion.titleId.value)
      ?: return@withWriteTransaction completionFailure(
        ReadingProgressFailure.TitleNotFound,
      )
    val chapter = readingDao.findChapterByIdForTitle(
      titleStorageId = title.storageId,
      chapterId = completion.chapterId.value,
    ) ?: return@withWriteTransaction completionFailure(
      ReadingProgressFailure.ChaptersNotFound.create(
        listOf(completion.chapterId),
      ),
    )

    if (
      chapter.canonicalIndex == null ||
      readingDao.findFinalChapterStorageId(title.storageId) !=
      chapter.storageId
    ) {
      return@withWriteTransaction completionFailure(
        ReadingProgressFailure.CanonicalSequenceChanged.create(
          listOf(completion.chapterId),
        ),
      )
    }

    if (!readingDao.isChapterRead(chapter.storageId)) {
      readingDao.insertReadChapterOrIgnore(
        ReadChapterEntity(chapter.storageId),
      )
    }
    readingDao.deleteLibraryResumePosition(
      titleStorageId = title.storageId,
      chapterStorageId = chapter.storageId,
    )
    completionSuccess(title.id)
  }

  private fun List<ChapterEntity>.matches(
    observed: List<ReconcileSourceChapter>,
  ): Boolean = size == observed.size && zip(observed).all { (stored, source) ->
    stored.sourceChapterKey == source.alias.sourceChapterKey &&
      stored.displayName == source.displayName
  }

  private suspend fun insertChapter(
    title: TitleEntity,
    observed: ReconcileSourceChapter,
    canonicalIndex: Int,
  ): ChapterEntity {
    repeat(MAX_UUID_GENERATION_ATTEMPTS) {
      val candidate = ChapterEntity(
        storageId = 0,
        id = createUuid(),
        titleStorageId = title.storageId,
        sourceChapterKey = observed.alias.sourceChapterKey,
        displayName = observed.displayName,
        canonicalIndex = null,
      )
      val storageId = readingDao.insertChapterOrIgnore(candidate)
      if (storageId > 0) {
        return updateChapter(
          chapter = candidate.copy(storageId = storageId),
          displayName = observed.displayName,
          canonicalIndex = canonicalIndex,
        )
      }
    }
    error("Unable to allocate a unique chapter UUIDv7.")
  }

  private suspend fun updateChapter(
    chapter: ChapterEntity,
    displayName: String,
    canonicalIndex: Int,
  ): ChapterEntity {
    check(
      readingDao.updateChapterSnapshotState(
        storageId = chapter.storageId,
        titleStorageId = chapter.titleStorageId,
        displayName = displayName,
        canonicalIndex = canonicalIndex,
      ) == 1,
    ) {
      "Reconciled chapter disappeared during its transaction."
    }
    return chapter.copy(
      displayName = displayName,
      canonicalIndex = canonicalIndex,
    )
  }

  private fun successfulReconciliation(
    title: TitleEntity,
    titleAlias: SourceTitleAlias,
    chapters: Iterable<ChapterEntity>,
  ): ChapterReconciliationResult.Success =
    ChapterReconciliationResult.Success(
      CanonicalChapterSnapshot.create(
        titleId = TitleId(title.id),
        titleAlias = titleAlias,
        chapters = chapters.map { chapter ->
          chapter.toChapter(title, titleAlias)
        },
      ),
    )

  private suspend fun completionSuccess(
    titleId: UUID,
  ): CompletionResult.Success = CompletionResult.Success(
    loadReadingProgress(titleId),
  )

  private fun completionFailure(
    error: ReadingProgressFailure,
  ): CompletionResult.Failure = CompletionResult.Failure(error)

  private fun actualPositionFailure(
    error: ReadingProgressFailure,
  ): ActualPositionResult.Failure = ActualPositionResult.Failure(error)

  private suspend fun loadReadingProgress(
    titleId: UUID,
  ): TitleReadingProgress = checkNotNull(
    toReadingProgress(readingDao.loadReadingProgressRows(titleId)),
  ) {
    "Known title disappeared while reading its progress."
  }

  private fun toReadingProgress(
    rows: List<ReadingProgressRow>,
  ): TitleReadingProgress? {
    if (rows.isEmpty()) {
      return null
    }

    val first = rows.first()
    check(rows.all { row ->
      row.titleId == first.titleId &&
        row.titleSourceIdentity == first.titleSourceIdentity &&
        row.titleSourceKey == first.titleSourceKey &&
        row.isLibraryMember == first.isLibraryMember
    }) {
      "One progress query returned conflicting title state."
    }
    val titleId = TitleId(first.titleId)
    val titleAlias = SourceTitleAlias(
      sourceIdentity = first.titleSourceIdentity,
      sourceTitleKey = first.titleSourceKey,
    )
    val canonical = rows.mapNotNull { row ->
      row.toCanonicalChapterState(titleId, titleAlias)
    }
    check(
      rows.mapNotNull(ReadingProgressRow::chapterCanonicalIndex) ==
        canonical.indices.toList()
    ) {
      "Observed canonical chapter indexes must be dense."
    }

    val storedResume = rows.map { it.toStoredResume() }.distinct()
    check(storedResume.size == 1) {
      "One progress query returned conflicting resume state."
    }
    val resume = storedResume.single()?.toLibraryResumePosition(
      titleId = titleId,
      titleAlias = titleAlias,
      canonical = canonical,
    )
    return TitleReadingProgress.create(
      titleId = titleId,
      titleAlias = titleAlias,
      isInLibrary = first.isLibraryMember,
      canonicalChapters = canonical,
      libraryResumePosition = resume,
    )
  }

  private fun ReadingProgressRow.toCanonicalChapterState(
    titleId: TitleId,
    titleAlias: SourceTitleAlias,
  ): ChapterReadingState? {
    val id = chapterId
    if (id == null) {
      check(
        chapterSourceKey == null &&
          chapterDisplayName == null &&
          chapterCanonicalIndex == null &&
          !chapterIsRead
      ) {
        "An empty canonical row contained partial chapter state."
      }
      return null
    }
    checkNotNull(chapterCanonicalIndex)
    return ChapterReadingState(
      chapter = Chapter(
        id = ChapterId(id),
        titleId = titleId,
        alias = SourceChapterAlias(
          titleAlias = titleAlias,
          sourceChapterKey = checkNotNull(chapterSourceKey),
        ),
        displayName = checkNotNull(chapterDisplayName),
      ),
      isRead = chapterIsRead,
    )
  }

  private fun ReadingProgressRow.toStoredResume(): StoredResume? {
    val id = resumeChapterId
    if (id == null) {
      check(
        resumeChapterSourceKey == null &&
          resumeChapterDisplayName == null &&
          resumeChapterCanonicalIndex == null &&
          resumeUnitKind == null &&
          resumeUnitIndex == null &&
          !resumeChapterIsRead
      ) {
        "An empty resume row contained partial position state."
      }
      return null
    }
    check(!resumeChapterIsRead) {
      "A read chapter retained a persistent resume position."
    }
    return StoredResume(
      chapterId = id,
      sourceChapterKey = checkNotNull(resumeChapterSourceKey),
      displayName = checkNotNull(resumeChapterDisplayName),
      canonicalIndex = resumeChapterCanonicalIndex,
      unitKind = checkNotNull(resumeUnitKind),
      unitIndex = checkNotNull(resumeUnitIndex),
    )
  }

  private fun StoredResume.toLibraryResumePosition(
    titleId: TitleId,
    titleAlias: SourceTitleAlias,
    canonical: List<ChapterReadingState>,
  ): LibraryResumePosition {
    val domainChapterId = ChapterId(chapterId)
    val canonicalState = canonical.find { it.chapter.id == domainChapterId }
    check((canonicalState != null) == (canonicalIndex != null)) {
      "Resume availability disagreed with its canonical index."
    }
    if (canonicalState != null) {
      check(canonical.indexOf(canonicalState) == canonicalIndex) {
        "Resume canonical index disagreed with the current sequence."
      }
      check(
        canonicalState.chapter.alias.sourceChapterKey == sourceChapterKey &&
          canonicalState.chapter.displayName == displayName
      ) {
        "Resume and canonical chapter metadata disagreed."
      }
    }
    val chapter = canonicalState?.chapter ?: Chapter(
      id = domainChapterId,
      titleId = titleId,
      alias = SourceChapterAlias(titleAlias, sourceChapterKey),
      displayName = displayName,
    )
    return LibraryResumePosition(
      chapter = chapter,
      position = ReadingPosition(
        titleId = titleId,
        chapterId = domainChapterId,
        unitKind = unitKind.toDomainKind(),
        unitIndex = unitIndex,
      ),
      isCurrentlyAvailable = canonicalState != null,
    )
  }

  private fun ChapterEntity.toChapter(
    title: TitleEntity,
    titleAlias: SourceTitleAlias,
  ): Chapter = Chapter(
    id = ChapterId(id),
    titleId = TitleId(title.id),
    alias = SourceChapterAlias(titleAlias, sourceChapterKey),
    displayName = displayName,
  )

  private fun ReadingPosition.toEntity(
    title: TitleEntity,
    chapter: ChapterEntity,
  ): LibraryResumePositionEntity = LibraryResumePositionEntity(
    titleStorageId = title.storageId,
    chapterStorageId = chapter.storageId,
    unitKind = unitKind.toStoredKind(),
    unitIndex = unitIndex,
  )

  private fun ChapterEntity.isImmediatelyBefore(
    next: ChapterEntity,
  ): Boolean {
    val index = canonicalIndex ?: return false
    return index < Int.MAX_VALUE && next.canonicalIndex == index + 1
  }

  private fun ReadingContentUnitKind.toStoredKind(): StoredContentUnitKind =
    when (this) {
      ReadingContentUnitKind.PAGE -> StoredContentUnitKind.PAGE
      ReadingContentUnitKind.PROVIDER_SEGMENT ->
        StoredContentUnitKind.PROVIDER_SEGMENT
    }

  private fun StoredContentUnitKind.toDomainKind(): ReadingContentUnitKind =
    when (this) {
      StoredContentUnitKind.PAGE -> ReadingContentUnitKind.PAGE
      StoredContentUnitKind.PROVIDER_SEGMENT ->
        ReadingContentUnitKind.PROVIDER_SEGMENT
    }

  private data class StoredResume(
    val chapterId: UUID,
    val sourceChapterKey: String,
    val displayName: String,
    val canonicalIndex: Int?,
    val unitKind: StoredContentUnitKind,
    val unitIndex: Int,
  )

  private companion object {
    const val MAX_UUID_GENERATION_ATTEMPTS = 16
  }
}
