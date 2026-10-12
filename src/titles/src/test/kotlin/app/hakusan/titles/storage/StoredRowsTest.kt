package app.hakusan.titles.storage

import app.hakusan.titles.StoredStateViolationException
import app.hakusan.titles.StoredStateViolationKind
import app.hakusan.titles.StoredStateViolationKind.ALIAS
import app.hakusan.titles.StoredStateViolationKind.CANONICAL_ORDER
import app.hakusan.titles.StoredStateViolationKind.IDENTITY
import app.hakusan.titles.StoredStateViolationKind.RESUME_STATE
import app.hakusan.titles.StoredStateViolationKind.STORAGE_KEY
import kotlin.uuid.Uuid
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows

class StoredRowsTest {
  @Test
  fun validTitleRowKeepsExactValues() {
    val stored = StoredTitle.of(TITLE)

    assertEquals(TITLE.storageId, stored.storageId)
    assertArrayEquals(TITLE.id, stored.id.toStoredBytes())
    assertEquals(TITLE.sourceIdentity, stored.alias.sourceIdentity)
    assertEquals(TITLE.sourceTitleKey, stored.alias.sourceTitleKey)
    assertEquals(TITLE.displayName, stored.displayName)
    assertEquals(TITLE.description, stored.description)
    assertNull(StoredTitle.of(TITLE.copy(description = null)).description)
  }

  @Test
  fun validChapterRowKeepsExactValues() {
    val stored = StoredChapter.of(CHAPTER)

    val omitted = StoredChapter.of(CHAPTER.copy(canonicalIndex = null))

    val first = StoredChapter.of(CHAPTER.copy(canonicalIndex = 0))

    assertEquals(CHAPTER.storageId, stored.storageId)
    assertArrayEquals(CHAPTER.id, stored.id.toStoredBytes())
    assertEquals(CHAPTER.titleStorageId, stored.titleStorageId)
    assertEquals(CHAPTER.sourceChapterKey, stored.localKey.text)
    assertEquals(CHAPTER.displayName, stored.displayName)
    assertEquals(CHAPTER.canonicalIndex, stored.canonicalIndex)
    assertNull(omitted.canonicalIndex)
    assertEquals(0, first.canonicalIndex)
  }

  @Test
  fun validLibraryAndReadingRowsKeepExactValues() {
    val category = StoredCategory.of(CATEGORY)

    val association = StoredTitleCategory.of(ASSOCIATION)

    val read = StoredReadChapter.of(READ)

    val resume = StoredResumePosition.of(RESUME)

    val segment =
      StoredResumePosition.of(
        RESUME.copy(unitKind = "PROVIDER_SEGMENT", unitIndex = 0)
      )

    assertEquals(CATEGORY.id, category.id)
    assertEquals(CATEGORY.name, category.name)
    assertEquals(ASSOCIATION.titleStorageId, association.titleStorageId)
    assertEquals(ASSOCIATION.categoryId, association.categoryId)
    assertEquals(READ.chapterStorageId, read.chapterStorageId)
    assertEquals(RESUME.titleStorageId, resume.titleStorageId)
    assertEquals(RESUME.chapterStorageId, resume.chapterStorageId)
    assertEquals(StoredContentUnitKind.PAGE, resume.unitKind)
    assertEquals(RESUME.unitIndex, resume.unitIndex)
    assertEquals(RESUME.updatedAt, resume.updatedAt)
    assertEquals(StoredContentUnitKind.PROVIDER_SEGMENT, segment.unitKind)
    assertEquals(0, segment.unitIndex)
  }

  @Test
  fun aliasesKeepWhitespaceCaseAndUnicodeComposition() {
    val composed =
      StoredTitle.of(
        TITLE.copy(sourceIdentity = " Source\t", sourceTitleKey = COMPOSED)
      )

    val decomposed = StoredTitle.of(TITLE.copy(sourceTitleKey = DECOMPOSED))

    val chapter = StoredChapter.of(CHAPTER.copy(sourceChapterKey = " Ch\n"))

    assertEquals(" Source\t", composed.alias.sourceIdentity)
    assertEquals(COMPOSED, composed.alias.sourceTitleKey)
    assertEquals(DECOMPOSED, decomposed.alias.sourceTitleKey)
    assertEquals(" Ch\n", chapter.localKey.text)
  }

  @Test
  fun blankAliasesAreAliasViolations() {
    assertAll(
      BLANKS.flatMap { blank ->
        listOf(
          raises(ALIAS) { StoredTitle.of(TITLE.copy(sourceIdentity = blank)) },
          raises(ALIAS) { StoredTitle.of(TITLE.copy(sourceTitleKey = blank)) },
          raises(ALIAS) {
            StoredChapter.of(CHAPTER.copy(sourceChapterKey = blank))
          },
        )
      }
    )
  }

  @Test
  fun uuidsOtherThanVersion7Variant2AreIdentityViolations() {
    val uuids = (OTHER_VERSIONS + OTHER_VARIANTS).map(::uuidBytes)

    assertAll(uuids.flatMap(::identityViolations))
  }

  @Test
  fun uuidBlobsOtherThanSixteenBytesAreIdentityViolations() {
    val lengths =
      listOf(
        ByteArray(0),
        TITLE.id.copyOf(15),
        TITLE.id.copyOf(17),
        TITLE.id + CHAPTER.id,
      )

    assertAll(lengths.flatMap(::identityViolations))
  }

  @Test
  fun nonpositiveStorageKeysAreStorageKeyViolations() {
    assertAll(NONPOSITIVE_KEYS.flatMap(::storageKeyViolations))
  }

  @Test
  fun negativeIndexesAreViolations() {
    assertAll(
      NEGATIVE_INDEXES.flatMap { index ->
        listOf(
          raises(CANONICAL_ORDER) {
            StoredChapter.of(CHAPTER.copy(canonicalIndex = index))
          },
          raises(RESUME_STATE) {
            StoredResumePosition.of(RESUME.copy(unitIndex = index))
          },
        )
      }
    )
  }

  @Test
  fun unknownUnitKindsAreResumeStateViolations() {
    assertAll(
      UNKNOWN_UNIT_KINDS.map { text ->
        raises(RESUME_STATE) {
          StoredResumePosition.of(RESUME.copy(unitKind = text))
        }
      }
    )
  }

  @Test
  fun titleRowReportsItsFirstViolatedCheck() {
    val row = TITLE.copy(storageId = 0, id = NON_V7, sourceIdentity = " ")

    val identified = row.copy(storageId = 1)

    val aliased = identified.copy(id = TITLE.id)

    assertAll(
      raises(STORAGE_KEY) { StoredTitle.of(row) },
      raises(IDENTITY) { StoredTitle.of(identified) },
      raises(ALIAS) { StoredTitle.of(aliased) },
    )
  }

  @Test
  fun chapterRowReportsItsFirstViolatedCheck() {
    val row =
      CHAPTER.copy(
        storageId = 0,
        id = NON_V7,
        titleStorageId = 0,
        sourceChapterKey = "",
        canonicalIndex = -1,
      )

    val identified = row.copy(storageId = 1)

    val owned = identified.copy(id = CHAPTER.id)

    val keyed = owned.copy(titleStorageId = 1)

    val aliased = keyed.copy(sourceChapterKey = "1")

    assertAll(
      raises(STORAGE_KEY) { StoredChapter.of(row) },
      raises(IDENTITY) { StoredChapter.of(identified) },
      raises(STORAGE_KEY) { StoredChapter.of(owned) },
      raises(ALIAS) { StoredChapter.of(keyed) },
      raises(CANONICAL_ORDER) { StoredChapter.of(aliased) },
    )
  }

  @Test
  fun resumeRowReportsItsFirstViolatedCheck() {
    val row =
      RESUME.copy(
        titleStorageId = 0,
        chapterStorageId = 0,
        unitKind = "",
        unitIndex = -1,
      )

    val titled = row.copy(titleStorageId = 1)

    val positioned = titled.copy(chapterStorageId = 1)

    val unitKind = violationOf { StoredResumePosition.of(positioned) }

    assertAll(
      raises(STORAGE_KEY) { StoredResumePosition.of(row) },
      raises(STORAGE_KEY) { StoredResumePosition.of(titled) },
    )

    assertEquals(RESUME_STATE, unitKind.kind)
    assertTrue(unitKind.message.orEmpty().contains("unit_kind"))
    assertFalse(unitKind.message.orEmpty().contains("unit_index"))
  }

  @Test
  fun violationMessagesCarryNoStoredValue() {
    val violations =
      listOf(
        violationOf { StoredTitle.of(TITLE.copy(storageId = -STORED_KEY)) },
        violationOf { StoredTitle.of(TITLE.copy(id = NON_V7)) },
        violationOf { StoredTitle.of(TITLE.copy(sourceTitleKey = " ")) },
        violationOf { StoredChapter.of(CHAPTER.copy(id = NON_V7)) },
        violationOf { StoredChapter.of(CHAPTER.copy(sourceChapterKey = "")) },
        violationOf {
          StoredChapter.of(CHAPTER.copy(canonicalIndex = -STORED_INDEX))
        },
        violationOf { StoredCategory.of(CATEGORY.copy(id = -STORED_KEY)) },
        violationOf {
          StoredResumePosition.of(RESUME.copy(unitKind = "page-4242"))
        },
        violationOf {
          StoredResumePosition.of(RESUME.copy(unitIndex = -STORED_INDEX))
        },
      )

    assertAll(
      violations.flatMap { violation ->
        val message = violation.message.orEmpty()

        STORED_TEXTS.map { text ->
          { assertFalse(message.contains(text), message) }
        }
      }
    )
  }

  private fun identityViolations(id: ByteArray): List<() -> Unit> =
    listOf(
      raises(IDENTITY) { StoredTitle.of(TITLE.copy(id = id)) },
      raises(IDENTITY) { StoredChapter.of(CHAPTER.copy(id = id)) },
    )

  private fun storageKeyViolations(key: Long): List<() -> Unit> =
    listOf(
      raises(STORAGE_KEY) { StoredTitle.of(TITLE.copy(storageId = key)) },
      raises(STORAGE_KEY) { StoredChapter.of(CHAPTER.copy(storageId = key)) },
      raises(STORAGE_KEY) {
        StoredChapter.of(CHAPTER.copy(titleStorageId = key))
      },
      raises(STORAGE_KEY) { StoredCategory.of(CATEGORY.copy(id = key)) },
      raises(STORAGE_KEY) {
        StoredTitleCategory.of(ASSOCIATION.copy(titleStorageId = key))
      },
      raises(STORAGE_KEY) {
        StoredTitleCategory.of(ASSOCIATION.copy(categoryId = key))
      },
      raises(STORAGE_KEY) {
        StoredReadChapter.of(READ.copy(chapterStorageId = key))
      },
      raises(STORAGE_KEY) {
        StoredResumePosition.of(RESUME.copy(titleStorageId = key))
      },
      raises(STORAGE_KEY) {
        StoredResumePosition.of(RESUME.copy(chapterStorageId = key))
      },
    )

  private fun raises(
    kind: StoredStateViolationKind,
    read: () -> Unit,
  ): () -> Unit = { assertEquals(kind, violationOf(read).kind) }

  private fun violationOf(read: () -> Unit): StoredStateViolationException =
    assertThrows<StoredStateViolationException> { read() }

  private companion object {
    const val COMPOSED = "\u00e9"
    const val DECOMPOSED = "e\u0301"
    const val STORED_KEY = 4_242L
    const val STORED_INDEX = 4_242

    val NON_V7: ByteArray = uuidBytes("0192a6f0-7c3e-4d4a-9f12-3456789abcde")

    val TITLE =
      TitleEntity(
        storageId = STORED_KEY,
        id = uuidBytes("0192a6f0-7c3e-7d4a-9f12-3456789abcde"),
        sourceIdentity = "src-\u00c5",
        sourceTitleKey = " key-title ",
        displayName = "name-title",
        description = "text-description",
      )

    val CHAPTER =
      ChapterEntity(
        storageId = STORED_KEY,
        id = uuidBytes("0192a6f0-7c3e-7e5b-8f12-3456789abcde"),
        titleStorageId = STORED_KEY,
        sourceChapterKey = "\tkey-chapter",
        displayName = "name-chapter",
        canonicalIndex = STORED_INDEX,
      )

    val CATEGORY = CategoryEntity(id = STORED_KEY, name = "name-category")

    val ASSOCIATION =
      TitleCategoryEntity(titleStorageId = STORED_KEY, categoryId = STORED_KEY)

    val READ = ReadChapterEntity(chapterStorageId = STORED_KEY)

    val RESUME =
      LibraryResumePositionEntity(
        titleStorageId = STORED_KEY,
        chapterStorageId = STORED_KEY,
        unitKind = "PAGE",
        unitIndex = STORED_INDEX,
        updatedAt = 1_760_000_000_000L,
      )

    val STORED_TEXTS =
      listOf(
        "4242",
        "0192a6f0",
        "3456789abcde",
        "3456789ABCDE",
        "src-",
        "key-",
        "name-",
        "text-",
        "page-",
      )

    val BLANKS = listOf("", " ", "\t", "\n", "\u00a0", "\u2003", "\u3000 ")

    val NONPOSITIVE_KEYS = listOf(0L, -1L, Long.MIN_VALUE)

    val NEGATIVE_INDEXES = listOf(-1, Int.MIN_VALUE)

    val UNKNOWN_UNIT_KINDS =
      listOf("", "page", "Page", " PAGE", "PAGE ", "SEGMENT", "0")

    val OTHER_VERSIONS =
      listOf(
        "00000000-0000-0000-0000-000000000000",
        "0192a6f0-7c3e-1d4a-9f12-3456789abcde",
        "0192a6f0-7c3e-4d4a-9f12-3456789abcde",
        "0192a6f0-7c3e-6d4a-9f12-3456789abcde",
        "0192a6f0-7c3e-8d4a-9f12-3456789abcde",
        "ffffffff-ffff-ffff-ffff-ffffffffffff",
      )

    val OTHER_VARIANTS =
      listOf(
        "0192a6f0-7c3e-7d4a-1f12-3456789abcde",
        "0192a6f0-7c3e-7d4a-cf12-3456789abcde",
        "0192a6f0-7c3e-7d4a-ef12-3456789abcde",
      )

    fun uuidBytes(text: String): ByteArray = Uuid.parse(text).toByteArray()
  }
}
