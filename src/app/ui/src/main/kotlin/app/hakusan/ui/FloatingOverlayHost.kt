package app.hakusan.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** The margin between full-height content and the edges of its screen. */
private val CONTENT_MARGIN: Dp = 20.dp

/**
 * The measured overlay of the shell.
 *
 * [FloatingOverlayHost] records the height of the whole visible overlay here
 * while it measures the overlay, and [contentPadding] derives the padding of
 * the content from it. Together they are the single owner of the overlay
 * geometry. Read and write it on the main thread only.
 */
internal class FloatingOverlayState {
  /** The height of the whole visible overlay, zero without one. */
  var overlayHeight: Dp by mutableStateOf(0.dp)
}

/** Remembers the overlay state of one shell. */
@Composable
internal fun rememberFloatingOverlayState(): FloatingOverlayState = remember {
  FloatingOverlayState()
}

/**
 * Returns the padding of full-height content under the overlay.
 *
 * Content keeps a margin on every side. At the bottom it also clears the safe
 * bottom inset and the overlay with its Material screen offset, so its last
 * items scroll above the overlay. Without an overlay, the overlay clearance is
 * zero.
 *
 * The padding reads the overlay height and the inset only when a layout asks
 * for its bottom value. [FloatingOverlayHost] measures the overlay before the
 * content, so content that reads the padding while it measures clears the
 * overlay of the same frame, including the first frame after recreation. The
 * inset comes from [rememberSafeDrawingInsets], which holds the window insets
 * from that first frame as well.
 */
@Composable
internal fun FloatingOverlayState.contentPadding(): PaddingValues {
  val safeInsets = rememberSafeDrawingInsets().only(WindowInsetsSides.Bottom)

  val density = LocalDensity.current

  return remember(this, safeInsets, density) {
    OverlayContentPadding(this, safeInsets.asPaddingValues(density))
  }
}

/**
 * Places [overlay] centered near the bottom, above full-height [content].
 *
 * The overlay keeps clear of the safe horizontal and bottom insets by the
 * Material screen offset. The content fills the whole host and scrolls under
 * the overlay; it takes its padding from [FloatingOverlayState.contentPadding].
 * An overlay that shows nothing measures zero, so it reserves no space.
 *
 * The host measures the overlay before the content. Otherwise the content would
 * measure its first frame without the overlay's clearance, and a list restored
 * near its end would clamp its saved position. The host places the content
 * before the overlay, so the overlay draws above the content, receives touches
 * first, and follows the content in traversal.
 */
@Composable
internal fun FloatingOverlayHost(
  state: FloatingOverlayState,
  overlay: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val overlayInsets =
    rememberSafeDrawingInsets()
      .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)

  val placedOverlay: @Composable () -> Unit = {
    Box(
      modifier =
        Modifier.windowInsetsPadding(overlayInsets)
          .padding(FloatingToolbarDefaults.ScreenOffset)
          .recordOverlayHeight(state),
      contentAlignment = Alignment.Center,
    ) {
      overlay()
    }
  }

  Layout(
    contents = listOf(content, placedOverlay),
    modifier = modifier.fillMaxSize(),
  ) { (contentMeasurables, overlayMeasurables), constraints ->
    val loose = constraints.copy(minWidth = 0, minHeight = 0)

    val overlayPlaceables = overlayMeasurables.map { measurable ->
      measurable.measure(loose)
    }

    val contentPlaceables = contentMeasurables.map { measurable ->
      measurable.measure(loose)
    }

    val space = IntSize(constraints.maxWidth, constraints.maxHeight)

    layout(space.width, space.height) {
      for (placeable in contentPlaceables) {
        placeable.place(0, 0)
      }

      for (placeable in overlayPlaceables) {
        val size = IntSize(placeable.width, placeable.height)

        placeable.place(
          Alignment.BottomCenter.align(size, space, layoutDirection)
        )
      }
    }
  }
}

/**
 * The padding of [contentPadding] over [state], above the safe bottom inset
 * [safeBottom].
 */
private class OverlayContentPadding(
  private val state: FloatingOverlayState,
  private val safeBottom: PaddingValues,
) : PaddingValues {
  override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp =
    CONTENT_MARGIN

  override fun calculateTopPadding(): Dp = CONTENT_MARGIN

  override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp =
    CONTENT_MARGIN

  override fun calculateBottomPadding(): Dp {
    val height = state.overlayHeight

    val clearance =
      if (height == 0.dp) {
        0.dp
      } else {
        height + FloatingToolbarDefaults.ScreenOffset
      }

    return CONTENT_MARGIN + safeBottom.calculateBottomPadding() + clearance
  }
}

/** Records the height of this element in [state] while it measures. */
private fun Modifier.recordOverlayHeight(
  state: FloatingOverlayState
): Modifier = layout { measurable, constraints ->
  val placeable = measurable.measure(constraints)

  state.overlayHeight = placeable.height.toDp()

  layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}
