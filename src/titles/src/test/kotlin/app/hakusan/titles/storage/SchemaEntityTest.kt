package app.hakusan.titles.storage

import app.hakusan.titles.ApplicationUuidFactory
import java.nio.ByteBuffer
import java.util.Arrays
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SchemaEntityTest {
  @Test
  fun `valid rows preserve exact values and unavailable chapter state`() {
    val title = title(
      sourceIdentity = " source/α ",
      sourceTitleKey = " title/key ",
    )
    val chapter = chapter(canonicalIndex = null)
    val category = CategoryEntity(id = 1, name = " Default ")
    val association = TitleCategoryEntity(title.storageId, category.id)
    val read = ReadChapterEntity(chapter.storageId)
    val position = position()

    assertEquals(" source/α ", title.sourceIdentity)
    assertEquals(" title/key ", title.sourceTitleKey)
    assertNull(chapter.canonicalIndex)
    assertEquals(" Default ", category.name)
    assertEquals(title.storageId, association.titleStorageId)
    assertEquals(chapter.storageId, read.chapterStorageId)
    assertEquals(StoredContentUnitKind.PROVIDER_SEGMENT, position.unitKind)
    assertEquals(2, position.unitIndex)
  }

  @Test
  fun `application UUID factory produces ordered RFC UUIDv7 values`() {
    val values = List(10_000) {
      ApplicationUuidFactory.create()
    }

    assertEquals(values.size, values.toSet().size)
    assertTrue(values.all { it.version() == 7 && it.variant() == 2 })
    assertTrue(
      values.zipWithNext().all { (first, second) ->
        Arrays.compareUnsigned(
          first.toNetworkBytes(),
          second.toNetworkBytes(),
        ) < 0
      },
    )
  }

  @Test
  fun `stored domain identities require RFC UUIDv7`() {
    listOf<() -> Unit>(
      { title(id = versionFourUuid()) },
      { title(id = invalidVariantUuid()) },
      { chapter(id = versionFourUuid()) },
      { chapter(id = invalidVariantUuid()) },
    ).forEach(::assertInvalidRow)
  }

  @Test
  fun `source aliases reject blank values`() {
    listOf<() -> Unit>(
      { title(sourceIdentity = " ") },
      { title(sourceTitleKey = "\t") },
      { chapter(sourceChapterKey = "\n") },
    ).forEach(::assertInvalidRow)
  }

  @Test
  fun `generated and referenced storage keys enforce their local bounds`() {
    assertEquals(0, title(storageId = 0).storageId)
    assertEquals(0, chapter(storageId = 0).storageId)
    assertEquals(0, CategoryEntity(name = "New").id)

    listOf<() -> Unit>(
      { title(storageId = -1) },
      { chapter(storageId = -1) },
      { chapter(titleStorageId = 0) },
      { CategoryEntity(id = -1, name = "Invalid") },
      { TitleCategoryEntity(titleStorageId = 0, categoryId = 1) },
      { TitleCategoryEntity(titleStorageId = 1, categoryId = 0) },
      { ReadChapterEntity(chapterStorageId = 0) },
      { position(titleStorageId = 0) },
      { position(chapterStorageId = 0) },
    ).forEach(::assertInvalidRow)
  }

  @Test
  fun `stored sequence positions reject negative values`() {
    assertInvalidRow {
      chapter(canonicalIndex = -1)
    }
    assertInvalidRow {
      position(unitIndex = -1)
    }
  }

  @Test
  fun `stored content kinds match provider granularity`() {
    assertEquals(
      listOf("PAGE", "PROVIDER_SEGMENT"),
      StoredContentUnitKind.entries.map { it.name },
    )
  }

  private fun title(
    storageId: Long = 1,
    id: UUID = titleId(),
    sourceIdentity: String = "source",
    sourceTitleKey: String = "title",
  ): TitleEntity = TitleEntity(
    storageId = storageId,
    id = id,
    sourceIdentity = sourceIdentity,
    sourceTitleKey = sourceTitleKey,
    displayName = "Title",
    description = null,
  )

  private fun chapter(
    storageId: Long = 2,
    id: UUID = chapterId(),
    titleStorageId: Long = 1,
    sourceChapterKey: String = "chapter",
    canonicalIndex: Int? = 0,
  ): ChapterEntity = ChapterEntity(
    storageId = storageId,
    id = id,
    titleStorageId = titleStorageId,
    sourceChapterKey = sourceChapterKey,
    displayName = "Chapter",
    canonicalIndex = canonicalIndex,
  )

  private fun position(
    titleStorageId: Long = 1,
    chapterStorageId: Long = 2,
    unitIndex: Int = 2,
  ): LibraryResumePositionEntity = LibraryResumePositionEntity(
    titleStorageId = titleStorageId,
    chapterStorageId = chapterStorageId,
    unitKind = StoredContentUnitKind.PROVIDER_SEGMENT,
    unitIndex = unitIndex,
  )

  private fun assertInvalidRow(block: () -> Unit) {
    assertThrows(IllegalArgumentException::class.java, block)
  }

  private fun UUID.toNetworkBytes(): ByteArray =
    ByteBuffer.allocate(16)
      .putLong(mostSignificantBits)
      .putLong(leastSignificantBits)
      .array()

  private fun titleId(): UUID =
    UUID.fromString("00000000-0000-7000-8000-000000000001")

  private fun chapterId(): UUID =
    UUID.fromString("00000000-0000-7000-8000-000000000002")

  private fun versionFourUuid(): UUID =
    UUID.fromString("00000000-0000-4000-8000-000000000001")

  private fun invalidVariantUuid(): UUID =
    UUID.fromString("00000000-0000-7000-0000-000000000001")
}
