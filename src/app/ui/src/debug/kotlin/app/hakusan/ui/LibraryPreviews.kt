package app.hakusan.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.hakusan.sdk.CatalogScreen
import app.hakusan.sdk.CatalogSourceItem
import app.hakusan.sdk.LibraryResumeState
import app.hakusan.sdk.LibraryScreen
import app.hakusan.sdk.LibraryShelfMembers
import app.hakusan.sdk.LibraryTitleItem
import app.hakusan.sdk.LibraryTitleProgress
import app.hakusan.sdk.ScreenShelfId
import app.hakusan.sdk.ScreenSourceId
import app.hakusan.sdk.ScreenTitleId
import app.hakusan.sdk.ScreenTitleKey
import java.util.Locale
import java.util.UUID

/** The preview configurations of a screen state. They are not evidence. */
@Preview(name = "Light")
@Preview(
  name = "Dark",
  uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
)
@Preview(name = "Font scale 2.0", fontScale = 2f)
@Preview(name = "Compact height", heightDp = 360)
private annotation class StatePreviews

@StatePreviews
@Composable
private fun LibraryLoadingPreview() {
  LibraryPreview(LibraryState.Loading)
}

@StatePreviews
@Composable
private fun LibraryWithoutShelvesPreview() {
  LibraryPreview(LibraryState.Loaded(previewLibrary(shelves = emptyList())))
}

@StatePreviews
@Composable
private fun LibraryEmptyShelfPreview() {
  val shelf = LibraryShelfMembers(ScreenShelfId(1L), "Default", emptySet())

  LibraryPreview(LibraryState.Loaded(previewLibrary(listOf(shelf))))
}

@StatePreviews
@Composable
private fun LibraryShelvesPreview() {
  val titleIds = PREVIEW_TITLES.mapTo(HashSet()) { title -> title.id }

  val shelves =
    listOf(
      LibraryShelfMembers(ScreenShelfId(1L), "Reading now", titleIds),
      LibraryShelfMembers(ScreenShelfId(2L), "", setOf(PREVIEW_TITLES[0].id)),
      LibraryShelfMembers(ScreenShelfId(3L), "Later", emptySet()),
    )

  LibraryPreview(LibraryState.Loaded(previewLibrary(shelves)))
}

@StatePreviews
@Composable
private fun CatalogEmptyPreview() {
  CatalogPreview(CatalogScreen.of(emptyList()))
}

@StatePreviews
@Composable
private fun CatalogSourcesPreview() {
  val sources =
    listOf(
      CatalogSourceItem(ScreenSourceId("preview.source"), "Preview source"),
      CatalogSourceItem(ScreenSourceId("preview.unnamed"), ""),
    )

  CatalogPreview(CatalogScreen.of(sources))
}

@Composable
private fun LibraryPreview(state: LibraryState) {
  HakusanTheme {
    LibraryContent(
      state = state,
      contentPadding = PREVIEW_PADDING,
      carouselPosition = { CarouselPosition.START },
      onCarouselLeave = { _, _ -> },
    )
  }
}

@Composable
private fun CatalogPreview(catalog: CatalogScreen) {
  HakusanTheme { CatalogContent(catalog, PREVIEW_PADDING) }
}

private fun previewLibrary(shelves: List<LibraryShelfMembers>): LibraryScreen {
  val shelved = shelves.flatMapTo(HashSet()) { shelf -> shelf.titleIds }

  val titles = PREVIEW_TITLES.filter { title -> title.id in shelved }

  return LibraryScreen.of(titles, shelves, Locale.ROOT)
}

private val PREVIEW_PADDING = PaddingValues(all = 20.dp)

private val PREVIEW_TITLES: List<LibraryTitleItem> =
  listOf(
    previewTitle(1, "A title with a long name that wraps", "Preview source"),
    previewTitle(2, "", ""),
  )

private fun previewTitle(
  suffix: Int,
  name: String,
  source: String,
): LibraryTitleItem {
  val id = ScreenTitleId(UUID(0x0192a6f07c3e7d4aL, Long.MIN_VALUE + suffix))

  val key = ScreenTitleKey(ScreenSourceId("preview.source"), "title-$suffix")

  val progress = LibraryTitleProgress(12, 3, LibraryResumeState.AVAILABLE)

  return LibraryTitleItem(id, key, name, null, source, progress)
}
