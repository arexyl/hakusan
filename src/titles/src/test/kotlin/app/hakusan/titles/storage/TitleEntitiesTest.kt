package app.hakusan.titles.storage

import app.hakusan.titles.ChapterId
import app.hakusan.titles.TitleId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

class TitleEntitiesTest {
  @Test
  fun titleRowsCompareStoredBytesByContent() {
    val row = title(TitleId.generate().toStoredBytes())

    val same = row.copy(id = row.id.copyOf())

    assertEquals(row, same)
    assertEquals(row.hashCode(), same.hashCode())
  }

  @Test
  fun titleRowsDifferInEveryProperty() {
    val row = title(TitleId.generate().toStoredBytes())

    val changed =
      mapOf(
        "storageId" to row.copy(storageId = 2),
        "id" to row.copy(id = flipLastBit(row.id)),
        "sourceIdentity" to row.copy(sourceIdentity = "other"),
        "sourceTitleKey" to row.copy(sourceTitleKey = "other"),
        "displayName" to row.copy(displayName = "other"),
        "description" to row.copy(description = "other"),
      )

    assertAll(
      changed.map { (property, other) ->
        { assertNotEquals(row, other, property) }
      }
    )
  }

  @Test
  fun chapterRowsCompareStoredBytesByContent() {
    val row = chapter(ChapterId.generate().toStoredBytes())

    val same = row.copy(id = row.id.copyOf())

    assertEquals(row, same)
    assertEquals(row.hashCode(), same.hashCode())
  }

  @Test
  fun chapterRowsDifferInEveryProperty() {
    val row = chapter(ChapterId.generate().toStoredBytes())

    val changed =
      mapOf(
        "storageId" to row.copy(storageId = 2),
        "id" to row.copy(id = flipLastBit(row.id)),
        "titleStorageId" to row.copy(titleStorageId = 2),
        "sourceChapterKey" to row.copy(sourceChapterKey = "other"),
        "displayName" to row.copy(displayName = "other"),
        "canonicalIndex" to row.copy(canonicalIndex = null),
      )

    assertAll(
      changed.map { (property, other) ->
        { assertNotEquals(row, other, property) }
      }
    )
  }

  private fun flipLastBit(bytes: ByteArray): ByteArray =
    bytes.copyOf().also { copy ->
      copy[copy.lastIndex] = (copy.last().toInt() xor 1).toByte()
    }

  private fun title(id: ByteArray): TitleEntity =
    TitleEntity(
      storageId = 1,
      id = id,
      sourceIdentity = "source",
      sourceTitleKey = "title",
      displayName = "Title",
      description = null,
    )

  private fun chapter(id: ByteArray): ChapterEntity =
    ChapterEntity(
      storageId = 1,
      id = id,
      titleStorageId = 1,
      sourceChapterKey = "chapter",
      displayName = "Chapter",
      canonicalIndex = 0,
    )
}
