package app.hakusan.titles.storage

import app.hakusan.titles.CanonicalChapterSnapshot
import app.hakusan.titles.Chapter
import app.hakusan.titles.ChapterId
import app.hakusan.titles.ChapterReconciliationFailure
import app.hakusan.titles.ChapterReconciliationResult
import app.hakusan.titles.ReconcileChapterSnapshot
import app.hakusan.titles.ReconcileSourceChapter
import app.hakusan.titles.SourceChapterAlias
import app.hakusan.titles.SourceTitleAlias
import app.hakusan.titles.TitleId
import androidx.room3.withWriteTransaction
import java.util.UUID

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

  private fun ChapterEntity.toChapter(
    title: TitleEntity,
    titleAlias: SourceTitleAlias,
  ): Chapter = Chapter(
    id = ChapterId(id),
    titleId = TitleId(title.id),
    alias = SourceChapterAlias(titleAlias, sourceChapterKey),
    displayName = displayName,
  )

  private companion object {
    const val MAX_UUID_GENERATION_ATTEMPTS = 16
  }
}
