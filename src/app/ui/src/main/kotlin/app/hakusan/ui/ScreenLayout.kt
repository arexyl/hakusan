package app.hakusan.ui

import android.graphics.Insets
import android.view.View
import android.view.WindowInsets as PlatformWindowInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * The frame of one screen: a heading that also names the pane, above the
 * screen's content.
 *
 * The frame keeps clear of the safe horizontal and top insets. The content
 * fills the rest of the frame down to the bottom edge, and keeps its last items
 * reachable through the content padding of the shell.
 */
@Composable
internal fun ScreenFrame(
  title: String,
  modifier: Modifier = Modifier,
  content: @Composable BoxScope.() -> Unit,
) {
  val frameInsets =
    rememberSafeDrawingInsets()
      .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)

  Surface(
    modifier = modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.background,
    contentColor = MaterialTheme.colorScheme.onBackground,
  ) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(frameInsets)) {
      Box(
        modifier =
          Modifier.fillMaxWidth()
            .heightIn(min = 72.dp)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.CenterStart,
      ) {
        Text(
          text = title,
          modifier =
            Modifier.semantics {
              heading()
              paneTitle = title
            },
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          style = MaterialTheme.typography.headlineMedium,
        )
      }

      Box(Modifier.fillMaxWidth().weight(1f), content = content)
    }
  }
}

/** The heading of one section of a screen. */
@Composable
internal fun SectionHeading(text: String, modifier: Modifier = Modifier) {
  Text(
    text = text,
    modifier = modifier.semantics { heading() },
    style = MaterialTheme.typography.titleLarge,
  )
}

/** The loading state of a screen, with [message] below a progress indicator. */
@Composable
internal fun LoadingContent(
  message: String,
  contentPadding: PaddingValues,
  modifier: Modifier = Modifier,
) {
  CenteredContent(contentPadding, modifier) {
    CircularProgressIndicator()

    Text(
      text = message,
      modifier = Modifier.padding(top = 16.dp),
      style = MaterialTheme.typography.bodyLarge,
    )
  }
}

/** The successful empty state of a screen, with its [title] and [body]. */
@Composable
internal fun EmptyContent(
  title: String,
  body: String,
  contentPadding: PaddingValues,
  modifier: Modifier = Modifier,
) {
  CenteredContent(contentPadding, modifier) {
    Text(
      text = title,
      modifier = Modifier.semantics { heading() },
      style = MaterialTheme.typography.titleLarge,
    )

    Text(
      text = body,
      modifier = Modifier.padding(top = 8.dp),
      style = MaterialTheme.typography.bodyLarge,
    )
  }
}

/**
 * Returns [name], or [fallback] when [name] is blank.
 *
 * A fallback changes only the shown text. The identity of the named item never
 * depends on it.
 */
internal fun displayName(name: String, fallback: String): String =
  if (name.isBlank()) fallback else name

/**
 * Centers [content] in a scrollable column, so that a large font or a compact
 * height never cuts it off.
 */
@Composable
private fun CenteredContent(
  contentPadding: PaddingValues,
  modifier: Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = contentPadding,
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    item {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
      )
    }
  }
}

/**
 * Returns the safe drawing insets of the window, with the system bar and
 * display cutout parts correct from the first measure of a composition.
 *
 * Compose fills its window insets on the first inset dispatch after a
 * composition starts. When a composition starts in a window that has already
 * dispatched its insets, such as an Activity recreated with its window kept,
 * the window measures once before that dispatch, and a list restored near its
 * end clamps its saved position. The platform view already holds the insets of
 * that window. On each side these insets take the larger of the two values.
 * Compose still drives the keyboard and insets that shrink. A system bar or
 * display cutout inset that grows takes its end value at once, without the
 * Compose animation.
 *
 * Use these insets only in a window whose ancestors pass the window insets to
 * the composition unchanged, as the edge-to-edge Activity does. An ancestor
 * that consumes or rewrites them would double them, and an override such as
 * `DeviceConfigurationOverride.WindowInsets` cannot lower them.
 */
@Composable
internal fun rememberSafeDrawingInsets(): WindowInsets {
  val composeInsets = WindowInsets.safeDrawing

  val view = LocalView.current

  return remember(composeInsets, view) {
    SafeDrawingInsets(composeInsets, view)
  }
}

/** The insets of [rememberSafeDrawingInsets]. */
private class SafeDrawingInsets(
  private val composeInsets: WindowInsets,
  private val view: View,
) : WindowInsets {
  override fun getLeft(
    density: Density,
    layoutDirection: LayoutDirection,
  ): Int =
    maxOf(composeInsets.getLeft(density, layoutDirection), platform().left)

  override fun getTop(density: Density): Int =
    maxOf(composeInsets.getTop(density), platform().top)

  override fun getRight(
    density: Density,
    layoutDirection: LayoutDirection,
  ): Int =
    maxOf(composeInsets.getRight(density, layoutDirection), platform().right)

  override fun getBottom(density: Density): Int =
    maxOf(composeInsets.getBottom(density), platform().bottom)

  /** Reads the system bar and display cutout insets of the view's window. */
  private fun platform(): Insets {
    val types =
      PlatformWindowInsets.Type.systemBars() or
        PlatformWindowInsets.Type.displayCutout()

    return view.rootWindowInsets?.getInsets(types) ?: Insets.NONE
  }
}
