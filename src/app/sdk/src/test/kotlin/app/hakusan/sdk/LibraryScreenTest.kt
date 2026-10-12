package app.hakusan.sdk

import java.util.Locale
import java.util.UUID
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class LibraryScreenTest {
  @Test
  fun screenOwnsNormalizedData() {
    val first = title(1, "First")

    val second = title(2, "Second")

    val titles = mutableListOf(first, second)

    val members = mutableSetOf(first.id, second.id)

    val shelves =
      mutableListOf(
        LibraryShelfMembers(ScreenShelfId(1), "All", members),
        LibraryShelfMembers(ScreenShelfId(2), "Some", setOf(second.id)),
      )

    val screen = LibraryScreen.of(titles, shelves, Locale.ENGLISH)

    titles.clear()
    members.clear()
    shelves.clear()
    assertEquals(listOf(first.id, second.id), screen.titlesById.keys.toList())
    assertEquals(listOf("All", "Some"), screen.shelves.map { it.name })
    assertEquals(listOf(first.id, second.id), screen.shelves[0].titleIds)
    assertEquals(listOf(second.id), screen.shelves[1].titleIds)
    assertEquals(listOf(2, 1), screen.shelves.map { it.titleCount })

    assertThrows<UnsupportedOperationException> {
      (screen.shelves as MutableList).clear()
    }

    assertThrows<UnsupportedOperationException> {
      (screen.shelves[0].titleIds as MutableList).clear()
    }

    assertThrows<UnsupportedOperationException> {
      (screen.titlesById as MutableMap).clear()
    }
  }

  @Test
  fun noShelvesAndOneEmptyShelfAreDistinct() {
    val none = LibraryScreen.of(emptyList(), emptyList(), Locale.ENGLISH)

    val emptyShelf =
      LibraryScreen.of(
        emptyList(),
        listOf(LibraryShelfMembers(ScreenShelfId(1), "Default", emptySet())),
        Locale.ENGLISH,
      )

    assertTrue(none.shelves.isEmpty())
    assertEquals(listOf(0), emptyShelf.shelves.map { it.titleCount })
    assertNotEquals(none, emptyShelf)
  }

  @Test
  fun screenRejectsRepeatedIdentities() {
    val first = title(1, "First")

    val renamed = first.copy(displayName = "Renamed")

    assertThrows<IllegalArgumentException> {
      LibraryScreen.of(
        listOf(first, renamed),
        listOf(shelf(1, "All", first)),
        Locale.ENGLISH,
      )
    }

    assertThrows<IllegalArgumentException> {
      LibraryScreen.of(
        listOf(first),
        listOf(shelf(1, "All", first), shelf(1, "Again", first)),
        Locale.ENGLISH,
      )
    }
  }

  @Test
  fun shelvesAndTitlesMustAgree() {
    val first = title(1, "First")

    val second = title(2, "Second")

    assertThrows<IllegalArgumentException> {
      LibraryScreen.of(
        listOf(first),
        listOf(shelf(1, "All", first, second)),
        Locale.ENGLISH,
      )
    }

    assertThrows<IllegalArgumentException> {
      LibraryScreen.of(
        listOf(first, second),
        listOf(shelf(1, "All", first)),
        Locale.ENGLISH,
      )
    }
  }

  @Test
  fun progressKeepsConsistentCounts() {
    val valid =
      listOf(
        { LibraryTitleProgress(0, 0, LibraryResumeState.NONE) },
        { LibraryTitleProgress(3, 2, LibraryResumeState.AVAILABLE) },
        {
          LibraryTitleProgress(3, 3, LibraryResumeState.TEMPORARILY_UNAVAILABLE)
        },
      )

    valid.forEach { create -> assertDoesNotThrow { create() } }

    val invalid =
      listOf(
        { LibraryTitleProgress(-1, 0, LibraryResumeState.NONE) },
        { LibraryTitleProgress(1, -1, LibraryResumeState.NONE) },
        { LibraryTitleProgress(1, 2, LibraryResumeState.NONE) },
        { LibraryTitleProgress(0, 0, LibraryResumeState.AVAILABLE) },
        { LibraryTitleProgress(2, 2, LibraryResumeState.AVAILABLE) },
      )

    invalid.forEach { create ->
      assertThrows<IllegalArgumentException> { create() }
    }
  }

  @Test
  fun titlesFollowLocaleAwareCollationOfDisplayNames() {
    val titles =
      listOf("Zeta", "apple", "Échec", "dinner", "fig").mapIndexed { index, name
        ->
        title(index + 1, name)
      }

    val names = orderedNames(titles, Locale.ENGLISH)

    assertEquals(listOf("apple", "dinner", "Échec", "fig", "Zeta"), names)
  }

  @Test
  fun collationLocaleDecidesTheOrder() {
    val titles = listOf(title(1, "zebra"), title(2, "öl"))

    assertEquals(
      listOf("zebra", "öl"),
      orderedNames(titles, Locale.forLanguageTag("sv")),
    )

    assertEquals(
      listOf("öl", "zebra"),
      orderedNames(titles, Locale.forLanguageTag("de")),
    )
  }

  @Test
  fun descriptionThenSourceNameBreakNameTies() {
    val absent = title(1, "Same", description = null, sourceName = "Zeta")

    val alpha = title(2, "Same", description = "alpha", sourceName = "Zeta")

    val betaZeta = title(3, "Same", description = "Beta", sourceName = "Zeta")

    val betaAlpha = title(4, "Same", description = "Beta", sourceName = "alpha")

    val titles = listOf(betaZeta, alpha, betaAlpha, absent)

    assertEquals(
      listOf(absent.id, alpha.id, betaAlpha.id, betaZeta.id),
      orderedIds(titles, Locale.ENGLISH),
    )
  }

  @Test
  fun canonicallyEquivalentTextCollatesEqual() {
    val belowFirst = title(1, marksBelowFirst, description = "a")

    val aboveFirst = title(2, marksAboveFirst, description = "b")

    // Without canonical decomposition, the reordered marks would sort
    // aboveFirst before belowFirst, against the description order.
    assertEquals(
      listOf(belowFirst.id, aboveFirst.id),
      orderedIds(listOf(aboveFirst, belowFirst), Locale.ENGLISH),
    )
  }

  @Test
  fun uuidBreaksRemainingTitleTiesInUnsignedOrder() {
    val low = title(1, "Same", id = titleId(1))

    val middle = title(2, "Same", id = titleId(2))

    val high =
      title(
        3,
        "Same",
        id =
          ScreenTitleId(
            UUID.fromString("f192a6f0-7c3e-7d4a-9f12-345678900001")
          ),
      )

    val expected = listOf(low.id, middle.id, high.id)

    shuffles(listOf(high, low, middle)).forEach { titles ->
      assertEquals(expected, orderedIds(titles, Locale.ENGLISH))
    }
  }

  @Test
  fun shelvesCollateByNameThenCodePointsThenIdentity() {
    val member = title(1, "Title")

    // The two Café names collate equal, and code points put the decomposed
    // one first. Only the byte-identical apple names reach the identity.
    val shelves =
      listOf(
        shelf(4, "Zeta", member),
        shelf(7, "apple", member),
        shelf(3, "apple", member),
        shelf(5, "Été", member),
        shelf(8, composedCafe, member),
        shelf(9, decomposedCafe, member),
        shelf(2, "dinner", member),
      )

    shuffles(shelves).forEach { input ->
      val screen = LibraryScreen.of(listOf(member), input, Locale.ENGLISH)

      assertEquals(
        listOf(3L, 7L, 9L, 8L, 2L, 5L, 4L),
        screen.shelves.map { it.id.value },
      )
    }
  }

  @Test
  fun everyShelfUsesTheTitleOrderAndInputOrderNeverMatters() {
    val titles =
      listOf(
        title(1, "delta"),
        title(2, "Alpha"),
        title(3, "charlie"),
        title(4, "Bravo"),
        title(5, "Alpha", description = "second"),
      )

    val shelves =
      listOf(
        shelf(1, "Everything", *titles.toTypedArray()),
        shelf(2, "Odd", titles[0], titles[2], titles[4]),
        shelf(3, "Even", titles[3], titles[1]),
      )

    val expected = LibraryScreen.of(titles, shelves, Locale.ENGLISH)

    assertEquals(
      listOf(2, 5, 4, 3, 1).map(::titleId),
      expected.shelves.first { it.name == "Everything" }.titleIds,
    )

    assertEquals(
      listOf(5, 3, 1).map(::titleId),
      expected.shelves.first { it.name == "Odd" }.titleIds,
    )

    shuffles(titles).zip(shuffles(shelves).reversed()).forEachIndexed {
      seed,
      (titleInput, shelfInput) ->
      val shuffledShelves = shelfInput.map { members ->
        members.copy(titleIds = members.titleIds.shuffled(Random(seed)).toSet())
      }

      assertEquals(
        expected,
        LibraryScreen.of(titleInput, shuffledShelves, Locale.ENGLISH),
      )
    }
  }

  private fun title(
    suffix: Int,
    name: String,
    description: String? = null,
    sourceName: String = "Source",
    id: ScreenTitleId = titleId(suffix),
  ) =
    LibraryTitleItem(
      id = id,
      key = titleKey("title-$suffix"),
      displayName = name,
      description = description,
      sourceDisplayName = sourceName,
      progress = LibraryTitleProgress(0, 0, LibraryResumeState.NONE),
    )

  private fun shelf(id: Long, name: String, vararg titles: LibraryTitleItem) =
    LibraryShelfMembers(
      ScreenShelfId(id),
      name,
      titles.mapTo(HashSet()) { it.id },
    )

  private fun orderedIds(
    titles: List<LibraryTitleItem>,
    locale: Locale,
  ): List<ScreenTitleId> {
    val screen =
      LibraryScreen.of(
        titles,
        listOf(shelf(1, "All", *titles.toTypedArray())),
        locale,
      )

    return screen.shelves.single().titleIds
  }

  private fun orderedNames(
    titles: List<LibraryTitleItem>,
    locale: Locale,
  ): List<String> {
    val names = titles.associate { it.id to it.displayName }

    return orderedIds(titles, locale).map(names::getValue)
  }

  private fun <Element> shuffles(values: List<Element>): List<List<Element>> =
    (0 until SHUFFLES).map { seed -> values.shuffled(Random(seed)) }

  private companion object {
    const val SHUFFLES = 24
  }
}
