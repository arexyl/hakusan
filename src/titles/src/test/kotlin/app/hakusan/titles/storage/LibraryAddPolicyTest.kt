package app.hakusan.titles.storage

import app.hakusan.titles.CategoryId
import app.hakusan.titles.LibraryCategory
import app.hakusan.titles.LibraryCategorySelection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LibraryAddPolicyTest {
  @Test
  fun automaticAddWithoutACategoryCreatesDefault() {
    assertEquals(
      AutomaticAddDecision.CreateDefault,
      decideAutomaticAdd(categories()),
    )
  }

  @Test
  fun automaticAddAssignsTheOnlyCategoryWhateverItsName() {
    val names = listOf("Default", "Favorites", "", " ")

    assertEquals(
      names.map { AutomaticAddDecision.Assign(id(3)) },
      names.map { name -> decideAutomaticAdd(categories(3L to name)) },
    )
  }

  @Test
  fun automaticAddAmongSeveralCategoriesRequiresASelection() {
    val two = categories(1L to "Default", 2L to "Default")

    val three = categories(1L to "Default", 2L to "Later", 7L to "Done")

    assertEquals(
      AutomaticAddDecision.SelectionRequired(
        setOf(category(1, "Default"), category(2, "Default"))
      ),
      decideAutomaticAdd(two),
    )

    assertEquals(
      AutomaticAddDecision.SelectionRequired(
        setOf(category(1, "Default"), category(2, "Later"), category(7, "Done"))
      ),
      decideAutomaticAdd(three),
    )
  }

  @Test
  fun explicitAddAssignsASelectionOfCurrentCategories() {
    val current = categories(1L to "A", 2L to "B", 3L to "C")

    val cases =
      listOf(setOf(id(1)), setOf(id(1), id(3)), setOf(id(1), id(2), id(3)))

    assertEquals(
      cases.map(ExplicitAddDecision::Assign),
      cases.map { ids -> decideExplicitAdd(current, selection(ids)) },
    )
  }

  @Test
  fun explicitAddWithAMissingCategoryRejectsTheWholeSelection() {
    val current = categories(1L to "A", 2L to "B")

    val cases =
      listOf(
        setOf(id(1), id(4)) to setOf(id(4)),
        setOf(id(4), id(5)) to setOf(id(4), id(5)),
        setOf(id(2), id(1), id(9)) to setOf(id(9)),
      )

    assertEquals(
      cases.map { (_, missing) ->
        ExplicitAddDecision.CategoriesNotFound(missing)
      },
      cases.map { (ids, _) -> decideExplicitAdd(current, selection(ids)) },
    )
  }

  @Test
  fun explicitAddWithoutACurrentCategoryRejectsEverySelectedOne() {
    assertEquals(
      ExplicitAddDecision.CategoriesNotFound(setOf(id(1), id(2))),
      decideExplicitAdd(categories(), selection(setOf(id(1), id(2)))),
    )
  }

  private fun categories(vararg rows: Pair<Long, String>): CurrentCategories =
    CurrentCategories.of(
      rows.map { (key, name) -> CategoryEntity(id = key, name = name) }
    )

  private fun category(key: Long, name: String): LibraryCategory =
    LibraryCategory(id(key), name)

  private fun selection(ids: Set<CategoryId>): LibraryCategorySelection =
    LibraryCategorySelection.of(ids)

  private fun id(key: Long): CategoryId = checkNotNull(CategoryId.ofOrNull(key))
}
