package app.hakusan.titles.storage

import app.hakusan.titles.ChapterId
import app.hakusan.titles.TitleId
import java.nio.ByteBuffer
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class StoredIdentitiesTest {
  @Test
  fun storedUuidsRoundTripIdentities() {
    val title = TitleId.generate()

    val chapter = ChapterId.generate()

    assertEquals(title, title.toStoredUuid().toTitleIdOrNull())
    assertEquals(chapter, chapter.toStoredUuid().toChapterIdOrNull())
  }

  // This covers only the conversion to java.util.UUID. Room's own BLOB
  // encoding needs a device test through a Room write.
  @Test
  fun javaUuidConversionKeepsTheByteOrder() {
    val title = TitleId.generate()

    val stored = title.toStoredUuid()

    val bytes =
      ByteBuffer.allocate(16)
        .putLong(stored.mostSignificantBits)
        .putLong(stored.leastSignificantBits)
        .array()

    assertArrayEquals(title.uuid.toByteArray(), bytes)
  }

  @Test
  fun storedUuidsOtherThanVersion7HaveNoIdentity() {
    val stored = UUID.fromString("0192a6f0-7c3e-4d4a-9f12-3456789abcde")

    assertNull(stored.toTitleIdOrNull())
    assertNull(stored.toChapterIdOrNull())
  }
}
