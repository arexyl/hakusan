package app.hakusan.titles

import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

class IdentitiesTest {
  @Test
  fun identitiesKeepAVersion7Variant2Uuid() {
    val uuid = Uuid.parse(VERSION_7)

    assertEquals(uuid, TitleId.ofOrNull(uuid)?.uuid)
    assertEquals(uuid, ChapterId.ofOrNull(uuid)?.uuid)
  }

  @Test
  fun identitiesRejectOtherVersions() {
    val uuids = OTHER_VERSIONS.map(Uuid::parse)

    assertAll(uuids.flatMap(::rejectionsOf))
  }

  @Test
  fun identitiesRejectNonIetfVariants() {
    val uuids = OTHER_VARIANTS.map(Uuid::parse)

    assertAll(uuids.flatMap(::rejectionsOf))
  }

  @Test
  fun generatedSampleIsDistinctVersion7Variant2AndMonotonic() {
    val sample = List(SAMPLE_SIZE, ::generate)

    assertEquals(SAMPLE_SIZE, sample.toSet().size)

    assertAll(
      sample.map { uuid ->
        {
          assertEquals(7, uuid.toJavaUuid().version())
          assertEquals(2, uuid.toJavaUuid().variant())
        }
      }
    )

    assertAll(
      sample.zipWithNext().map { (earlier, later) ->
        { assertTrue(earlier.toHexString() < later.toHexString()) }
      }
    )
  }

  private fun rejectionsOf(uuid: Uuid): List<() -> Unit> =
    listOf(
      { assertNull(TitleId.ofOrNull(uuid), uuid.toString()) },
      { assertNull(ChapterId.ofOrNull(uuid), uuid.toString()) },
    )

  // Title and chapter identities share one process-wide generator.
  private fun generate(index: Int): Uuid =
    if (index % 2 == 0) {
      TitleId.generate().uuid
    } else {
      ChapterId.generate().uuid
    }

  private companion object {
    const val SAMPLE_SIZE = 10_000
    const val VERSION_7 = "0192a6f0-7c3e-7d4a-9f12-3456789abcde"

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
  }
}
