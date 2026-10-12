package app.hakusan.titles.storage

import app.hakusan.titles.AcceptedChapter
import app.hakusan.titles.AcceptedTitleObservation
import app.hakusan.titles.ChapterId
import app.hakusan.titles.SourceChapterAlias
import app.hakusan.titles.SourceTitleAlias
import app.hakusan.titles.TitleLocalChapterKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterPlanTest {
  @Test
  fun initialSnapshotOnlyInserts() {
    val plan = planAndApply(current = emptyList(), accepted = listOf("a", "b"))

    assertEquals(
      ChapterPlan(
        clearedIndexes = emptyList(),
        writes = listOf(insert("a", 0), insert("b", 1)),
      ),
      plan,
    )
  }

  @Test
  fun identicalSnapshotWritesNothing() {
    val plan =
      planAndApply(
        current = canonical("a", "b", "c"),
        accepted = listOf("a", "b", "c"),
      )

    assertEquals(ChapterPlan(emptyList(), emptyList()), plan)
  }

  @Test
  fun appendInsertsOnlyTheNewTail() {
    val plan =
      planAndApply(
        current = canonical("a", "b"),
        accepted = listOf("a", "b", "c"),
      )

    assertEquals(ChapterPlan(emptyList(), listOf(insert("c", 2))), plan)
  }

  @Test
  fun tailRemovalClearsOnlyTheRemovedTail() {
    val plan =
      planAndApply(
        current = canonical("a", "b", "c", "d"),
        accepted = listOf("a", "b"),
      )

    assertEquals(ChapterPlan(listOf(keyOf("c"), keyOf("d")), emptyList()), plan)
  }

  @Test
  fun prependClearsAndShiftsEveryLaterChapter() {
    val plan =
      planAndApply(
        current = canonical("a", "b"),
        accepted = listOf("z", "a", "b"),
      )

    assertEquals(
      ChapterPlan(
        clearedIndexes = listOf(keyOf("a"), keyOf("b")),
        writes = listOf(insert("z", 0), update("a", 1), update("b", 2)),
      ),
      plan,
    )
  }

  @Test
  fun reorderClearsOnlyMovedChaptersAfterThePrefix() {
    val plan =
      planAndApply(
        current = canonical("a", "b", "c", "d"),
        accepted = listOf("a", "c", "b", "d"),
      )

    assertEquals(
      ChapterPlan(
        clearedIndexes = listOf(keyOf("b"), keyOf("c")),
        writes = listOf(update("c", 1), update("b", 2)),
      ),
      plan,
    )
  }

  @Test
  fun reappearanceRestoresTheKnownChapter() {
    val current = canonical("a", "b") + omitted("c")

    val plan = planAndApply(current = current, accepted = listOf("a", "b", "c"))

    assertEquals(ChapterPlan(emptyList(), listOf(update("c", 2))), plan)
  }

  @Test
  fun emptySnapshotOmitsEveryCanonicalChapter() {
    val current = canonical("a", "b") + omitted("c")

    val plan = planAndApply(current = current, accepted = emptyList())

    assertEquals(ChapterPlan(listOf(keyOf("a"), keyOf("b")), emptyList()), plan)
  }

  @Test
  fun renameInThePrefixWritesOnlyThatChapter() {
    val plan =
      planAndApply(
        current = canonical("a", "b", "c"),
        accepted = listOf("a", "b", "c"),
        names = mapOf("b" to "Renamed"),
      )

    assertEquals(
      ChapterPlan(emptyList(), listOf(update("b", 1, name = "Renamed"))),
      plan,
    )
  }

  @Test
  fun mixedSnapshotAppliesEveryRuleAtOnce() {
    val current = canonical("a", "b", "c", "d", "e") + omitted("x")

    val plan =
      planAndApply(
        current = current,
        accepted = listOf("a", "x", "d", "n", "c", "e"),
        names = mapOf("a" to "Renamed", "e" to "Renamed"),
      )

    assertEquals(
      ChapterPlan(
        clearedIndexes = listOf(keyOf("b"), keyOf("c"), keyOf("d"), keyOf("e")),
        writes =
          listOf(
            update("a", 0, name = "Renamed"),
            update("x", 1),
            update("d", 2),
            insert("n", 3),
            update("c", 4),
            update("e", 5, name = "Renamed"),
          ),
      ),
      plan,
    )
  }

  @Test
  fun unmovedChapterAfterThePrefixKeepsItsIndex() {
    val plan =
      planAndApply(
        current = canonical("a", "b", "c"),
        accepted = listOf("b", "a", "c"),
      )

    assertEquals(
      ChapterPlan(
        clearedIndexes = listOf(keyOf("a"), keyOf("b")),
        writes = listOf(update("b", 0), update("a", 1)),
      ),
      plan,
    )
  }

  /**
   * Plans the reconciliation of [current] with [accepted], applies the plan to
   * a model of the chapters table, and checks every step and the result.
   */
  private fun planAndApply(
    current: List<ChapterEntity>,
    accepted: List<String>,
    names: Map<String, String> = emptyMap(),
  ): ChapterPlan {
    val known = KnownChapters.of(TITLE_KEY, current)

    val acceptedChapters = accepted.map { key ->
      AcceptedChapter(alias(key), names[key] ?: name(key))
    }

    val observation =
      AcceptedTitleObservation.of(TITLE_ALIAS, "Title", null, acceptedChapters)

    val plan = planChapters(known, observation)

    val table = ChaptersTable(current)

    table.apply(plan)
    table.assertReconciled(current, observation.chapters)

    return plan
  }

  /**
   * A model of the chapters rows of one title that enforces the unique
   * canonical index after every step, like the unique index of the schema.
   */
  private class ChaptersTable(rows: List<ChapterEntity>) {
    private val rows = rows.associateBy(ChapterEntity::storageId).toMutableMap()

    private var nextStorageId =
      (rows.maxOfOrNull { row -> row.storageId } ?: 0) + 1

    fun apply(plan: ChapterPlan) {
      plan.clearedIndexes.forEach { storageId ->
        val row = rows.getValue(storageId)

        rows[storageId] = row.copy(canonicalIndex = null)
        assertUniqueIndexes()
      }

      plan.writes.forEach { write ->
        when (write) {
          is ChapterWrite.Update -> update(write)
          is ChapterWrite.Insert -> insert(write)
        }

        assertUniqueIndexes()
      }
    }

    fun assertReconciled(
      current: List<ChapterEntity>,
      accepted: List<AcceptedChapter>,
    ) {
      val canonical =
        rows.values
          .filter { row -> row.canonicalIndex != null }
          .sortedBy { row -> row.canonicalIndex }

      val acceptedKeys = accepted.map { chapter -> chapter.alias.localKey.text }

      assertEquals(acceptedKeys, canonical.map(ChapterEntity::sourceChapterKey))

      assertEquals(
        accepted.indices.toList(),
        canonical.map { it.canonicalIndex },
      )

      assertEquals(
        accepted.map(AcceptedChapter::displayName),
        canonical.map(ChapterEntity::displayName),
      )

      current.forEach { row ->
        val stored = rows.getValue(row.storageId)

        assertTrue(stored.id contentEquals row.id)
        assertEquals(row.sourceChapterKey, stored.sourceChapterKey)

        if (row.sourceChapterKey !in acceptedKeys) {
          assertEquals(null, stored.canonicalIndex)
          assertEquals(row.displayName, stored.displayName)
        }
      }

      val knownKeys = current.map(ChapterEntity::sourceChapterKey).toSet()

      assertEquals(
        knownKeys + acceptedKeys,
        rows.values.map { it.sourceChapterKey }.toSet(),
      )

      assertEquals((knownKeys + acceptedKeys).size, rows.size)
    }

    private fun update(write: ChapterWrite.Update) {
      val row = rows.getValue(write.storageId)

      rows[write.storageId] =
        row.copy(
          displayName = write.displayName,
          canonicalIndex = write.canonicalIndex,
        )
    }

    private fun insert(write: ChapterWrite.Insert) {
      val storageId = nextStorageId++

      rows[storageId] =
        ChapterEntity(
          storageId = storageId,
          id = ChapterId.generate().toStoredBytes(),
          titleStorageId = TITLE_KEY,
          sourceChapterKey = write.localKey.text,
          displayName = write.displayName,
          canonicalIndex = write.canonicalIndex,
        )
    }

    private fun assertUniqueIndexes() {
      val indexes = rows.values.mapNotNull(ChapterEntity::canonicalIndex)

      assertEquals(
        indexes.size,
        indexes.toSet().size,
        "Two rows share an index.",
      )
    }
  }

  private fun insert(key: String, index: Int): ChapterWrite =
    ChapterWrite.Insert(TitleLocalChapterKey.of(key), name(key), index)

  private fun update(key: String, index: Int, name: String = name(key)) =
    ChapterWrite.Update(keyOf(key), name, index)

  private fun canonical(vararg keys: String): List<ChapterEntity> =
    keys.mapIndexed { index, key ->
      row(key, index)
    }

  private fun omitted(key: String): ChapterEntity = row(key, null)

  private fun row(key: String, index: Int?): ChapterEntity =
    ChapterEntity(
      storageId = keyOf(key),
      id = ChapterId.generate().toStoredBytes(),
      titleStorageId = TITLE_KEY,
      sourceChapterKey = key,
      displayName = name(key),
      canonicalIndex = index,
    )

  // Each test key is one letter, so its storage key derives from the letter.
  private fun keyOf(key: String): Long = 100L + key.single().code

  private fun name(key: String): String = "Chapter $key"

  private fun alias(key: String): SourceChapterAlias =
    SourceChapterAlias(TITLE_ALIAS, TitleLocalChapterKey.of(key))

  private companion object {
    const val TITLE_KEY = 7L

    val TITLE_ALIAS = SourceTitleAlias.of("source", "title")
  }
}
