package app.hakusan.titles.storage

import app.hakusan.titles.ChapterId
import app.hakusan.titles.TitleId
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.uuid.Uuid
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

class StoredIdentitiesTest {
  @Test
  fun storedBytesRoundTripIdentities() {
    val title = TitleId.generate()

    val chapter = ChapterId.generate()

    assertEquals(title, title.toStoredBytes().toTitleIdOrNull())
    assertEquals(chapter, chapter.toStoredBytes().toChapterIdOrNull())
  }

  @Test
  fun storedBytesAreTheNetworkOrderUuid() {
    val title = checkNotNull(TitleId.ofOrNull(Uuid.parse(KNOWN)))

    val chapter = checkNotNull(ChapterId.ofOrNull(Uuid.parse(KNOWN)))

    assertEquals(KNOWN_HEX, hex(title.toStoredBytes()))
    assertEquals(KNOWN_HEX, hex(chapter.toStoredBytes()))
    assertArrayEquals(javaUuidBlob(KNOWN), title.toStoredBytes())
  }

  @Test
  fun storedBytesOtherThanSixteenHaveNoIdentity() {
    val valid = TitleId.generate().toStoredBytes()

    val lengths =
      listOf(
        ByteArray(0),
        valid.copyOf(15),
        valid.copyOf(17),
        valid + valid,
      )

    assertAll(
      lengths.flatMap { bytes ->
        listOf(
          { assertNull(bytes.toTitleIdOrNull(), "${bytes.size}") },
          { assertNull(bytes.toChapterIdOrNull(), "${bytes.size}") },
        )
      }
    )
  }

  @Test
  fun storedBytesOtherThanVersion7HaveNoIdentity() {
    val stored = javaUuidBlob("0192a6f0-7c3e-4d4a-9f12-3456789abcde")

    assertNull(stored.toTitleIdOrNull())
    assertNull(stored.toChapterIdOrNull())
  }

  private fun hex(bytes: ByteArray): String =
    bytes.joinToString("") { byte -> "%02x".format(byte) }

  // The layout of Room's java.util.UUID BLOB conversion, written as Room's
  // convertUUIDToByte writes it.
  private fun javaUuidBlob(text: String): ByteArray {
    val uuid = UUID.fromString(text)

    return ByteBuffer.allocate(16)
      .putLong(uuid.mostSignificantBits)
      .putLong(uuid.leastSignificantBits)
      .array()
  }

  private companion object {
    const val KNOWN = "0192a6f0-7c3e-7d4a-9f12-3456789abcde"
    const val KNOWN_HEX = "0192a6f07c3e7d4a9f123456789abcde"
  }
}
