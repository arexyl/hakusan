package app.hakusan.ui.preview

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
      onExit = {},
    )
  }
}
