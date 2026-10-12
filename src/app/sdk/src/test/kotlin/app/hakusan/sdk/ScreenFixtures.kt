package app.hakusan.sdk

import java.util.UUID

/**
 * UUID vectors shared with the identity tests of the other modules that keep
 * their own validation copy.
 */
internal object UuidVectors {
  const val VERSION_7 = "0192a6f0-7c3e-7d4a-9f12-3456789abcde"

  val OTHER_VERSIONS =
    listOf(
      "00000000-0000-0000-0000-000000000000",
      "0192a6f0-7c3e-1d4a-9f12-3456789abcde",
      "0192a6f0-7c3e-4d4a-9f12-3456789abcde",
      "0192a6f0-7c3e-6d4a-9f12-3456789abcde",
      "0192a6f0-7c3e-8d4a-9f12-3456789abcde",
    )

  val OTHER_VARIANTS =
    listOf(
      "0192a6f0-7c3e-7d4a-1f12-3456789abcde",
      "0192a6f0-7c3e-7d4a-cf12-3456789abcde",
      "0192a6f0-7c3e-7d4a-ef12-3456789abcde",
    )
}

/** A word that ends in the precomposed U+00E9. */
internal val composedCafe = "Caf" + Char(0x00e9)

/** The canonical equivalent of [composedCafe], with U+0065 U+0301. */
internal val decomposedCafe = "Cafe" + Char(0x0301)

/** "A" with a dot below, then a circumflex: the canonical mark order. */
internal val marksBelowFirst = "A" + Char(0x0323) + Char(0x0302)

/** The canonical equivalent of [marksBelowFirst] with its marks reordered. */
internal val marksAboveFirst = "A" + Char(0x0302) + Char(0x0323)

internal val source = ScreenSourceId("app.hakusan.test.source")

internal val otherSource = ScreenSourceId("app.hakusan.test.other")

/** Returns a valid title id whose final hexadecimal digits are [suffix]. */
internal fun titleId(suffix: Int): ScreenTitleId =
  ScreenTitleId(applicationUuid(0x0192a6f07c3e7d4aL, suffix))

/** Returns a valid chapter id whose final hexadecimal digits are [suffix]. */
internal fun chapterId(suffix: Int): ScreenChapterId =
  ScreenChapterId(applicationUuid(0x0192a6f07c3e7d4bL, suffix))

internal fun titleKey(key: String, sourceId: ScreenSourceId = source) =
  ScreenTitleKey(sourceId, key)

private val variantBits =
  java.lang.Long.parseUnsignedLong("9f12345678900000", 16)

private fun applicationUuid(high: Long, suffix: Int): UUID =
  UUID(high, variantBits or suffix.toLong())
