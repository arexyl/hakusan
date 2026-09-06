package app.hakusan.ui.preview

import app.hakusan.sdk.AddToLibraryScreenResult
import app.hakusan.sdk.BrowseScreenResult
import app.hakusan.sdk.BrowseScreenService
import app.hakusan.sdk.CatalogScreen
import app.hakusan.sdk.CatalogSourceItem
import app.hakusan.sdk.ContinueSelectionResult
import app.hakusan.sdk.DetailsScreenResult
import app.hakusan.sdk.ScreenSourceId
import app.hakusan.sdk.ScreenTitleId
import app.hakusan.sdk.ScreenTitleKey
import app.hakusan.sdk.TitleDetailsScreenService
import app.hakusan.ui.CatalogPresentationModel
import app.hakusan.ui.HakusanShell
import app.hakusan.ui.HakusanTheme
import app.hakusan.ui.PrimaryDestination
import app.hakusan.ui.rememberHakusanNavigationState
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes

@PreviewLightDark
@PreviewScreenSizes
@PreviewFontScale
@Composable
private fun LibraryShellPreview() {
  PreviewShell(PrimaryDestination.LIBRARY)
}

@Preview(name = "Catalog destination")
@Composable
private fun CatalogShellPreview() {
  PreviewShell(PrimaryDestination.CATALOG)
}

@Composable
private fun PreviewShell(destination: PrimaryDestination) {
  HakusanTheme(dynamicColor = false) {
    HakusanShell(
      navigationState = rememberHakusanNavigationState(destination),
      catalogPresentationModel = { PreviewCatalogPresentationModel },
      onExit = {},
    )
  }
}

private val PreviewCatalogPresentationModel = CatalogPresentationModel(
  browseScreenService = PreviewBrowseScreenService,
  titleDetailsScreenService = PreviewTitleDetailsScreenService,
)

private object PreviewBrowseScreenService : BrowseScreenService {
  override fun catalog(): CatalogScreen = CatalogScreen.of(
    listOf(
      CatalogSourceItem(
        id = ScreenSourceId("app.hakusan.preview.source"),
        displayName = "Preview source",
      ),
    ),
  )

  override suspend fun loadBrowse(
    sourceId: ScreenSourceId,
  ): BrowseScreenResult = error("The root preview does not load a source.")
}

private object PreviewTitleDetailsScreenService : TitleDetailsScreenService {
  override suspend fun loadDetails(
    titleKey: ScreenTitleKey,
  ): DetailsScreenResult = error("The root preview does not load a title.")

  override suspend fun addToLibrary(
    titleId: ScreenTitleId,
  ): AddToLibraryScreenResult = error(
    "The C4 preview does not modify Library membership.",
  )

  override suspend fun selectContinue(
    titleId: ScreenTitleId,
  ): ContinueSelectionResult = error(
    "The C4 preview does not select Continue.",
  )
}
