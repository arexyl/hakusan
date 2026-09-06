package app.hakusan.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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

@Composable
internal fun ScreenFrame(
  title: String,
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  content: @Composable BoxScope.() -> Unit,
) {
  Surface(
    modifier = modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.background,
    contentColor = MaterialTheme.colorScheme.onBackground,
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .windowInsetsPadding(
          WindowInsets.safeDrawing.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
          ),
        ),
    ) {
      ScreenHeader(
        title = title,
        onBack = onBack,
      )
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f),
        content = content,
      )
    }
  }
}

@Composable
private fun ScreenHeader(
  title: String,
  onBack: (() -> Unit)?,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 72.dp)
      .padding(horizontal = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (onBack != null) {
      TextButton(
        onClick = onBack,
      ) {
        Text(stringResource(R.string.navigate_back))
      }
      Spacer(Modifier.width(4.dp))
    } else {
      Spacer(Modifier.width(12.dp))
    }
    Text(
      text = title,
      modifier = Modifier
        .weight(1f)
        .semantics {
          heading()
          paneTitle = title
        },
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      style = MaterialTheme.typography.headlineMedium,
    )
  }
}

@Composable
internal fun SectionHeading(text: String) {
  Text(
    text = text,
    modifier = Modifier.semantics { heading() },
    style = MaterialTheme.typography.titleLarge,
  )
}

@Composable
internal fun LoadingContent(
  message: String,
  contentPadding: PaddingValues,
  modifier: Modifier = Modifier,
) {
  CenteredScreenContent(
    contentPadding = contentPadding,
    modifier = modifier,
  ) {
    CircularProgressIndicator()
    Text(
      text = message,
      modifier = Modifier.padding(top = 16.dp),
      style = MaterialTheme.typography.bodyLarge,
    )
  }
}

@Composable
private fun CenteredScreenContent(
  contentPadding: PaddingValues,
  modifier: Modifier = Modifier,
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

@Composable
internal fun FailureContent(
  title: String,
  body: String,
  onRetry: () -> Unit,
  contentPadding: PaddingValues,
  modifier: Modifier = Modifier,
) {
  MessageContent(
    title = title,
    body = body,
    onRetry = onRetry,
    contentPadding = contentPadding,
    modifier = modifier,
  )
}

@Composable
internal fun EmptyContent(
  title: String,
  body: String,
  contentPadding: PaddingValues,
  modifier: Modifier = Modifier,
) {
  MessageContent(
    title = title,
    body = body,
    onRetry = null,
    contentPadding = contentPadding,
    modifier = modifier,
  )
}

@Composable
private fun MessageContent(
  title: String,
  body: String,
  onRetry: (() -> Unit)?,
  contentPadding: PaddingValues,
  modifier: Modifier,
) {
  CenteredScreenContent(
    contentPadding = contentPadding,
    modifier = modifier,
  ) {
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
    if (onRetry != null) {
      Button(
        onClick = onRetry,
        modifier = Modifier.padding(top = 20.dp),
      ) {
        Text(stringResource(R.string.retry))
      }
    }
  }
}

@Composable
internal fun displayName(
  value: String,
  @StringRes fallback: Int,
): String = if (value.isBlank()) {
  stringResource(fallback)
} else {
  value
}
