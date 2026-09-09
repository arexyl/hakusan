package app.hakusan.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Places an overlay above full-height content without shrinking its viewport.
 */
@Composable
internal fun FloatingOverlayHost(
  overlay: (@Composable () -> Unit)?,
  modifier: Modifier = Modifier,
  content: @Composable (PaddingValues) -> Unit,
) {
  val density = LocalDensity.current
  val overlayVisible = overlay != null
  var overlayHeightPx by remember {
    mutableIntStateOf(0)
  }
  val overlayHeight = with(density) {
    overlayHeightPx.toDp()
  }
  val safeBottom = WindowInsets.safeDrawing
    .only(WindowInsetsSides.Bottom)
    .asPaddingValues()
    .calculateBottomPadding()
  val overlayClearance = if (overlayVisible) {
    FloatingToolbarDefaults.ScreenOffset + overlayHeight
  } else {
    0.dp
  }
  val contentPadding = remember(safeBottom, overlayClearance) {
    PaddingValues(
      start = 20.dp,
      top = 20.dp,
      end = 20.dp,
      bottom = 20.dp + safeBottom + overlayClearance,
    )
  }

  Box(modifier = modifier.fillMaxSize()) {
    content(contentPadding)
    if (overlay != null) {
      Box(
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .windowInsetsPadding(
            WindowInsets.safeDrawing.only(
              WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
            ),
          )
          .padding(FloatingToolbarDefaults.ScreenOffset),
        contentAlignment = Alignment.Center,
      ) {
        Box(
          modifier = Modifier.onSizeChanged { size ->
            overlayHeightPx = size.height
          },
          contentAlignment = Alignment.Center,
        ) {
          overlay()
        }
      }
    }
  }
}
