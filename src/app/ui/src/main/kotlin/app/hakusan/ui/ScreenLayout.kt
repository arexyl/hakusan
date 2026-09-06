package app.hakusan.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal val FloatingIslandEdgeSpacing = 16.dp

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
        modifier = Modifier.heightIn(min = 48.dp),
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
  contentBottomPadding: Dp,
  modifier: Modifier = Modifier,
) {
  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = screenContentPadding(contentBottomPadding),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    item {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(Modifier.size(40.dp))
        Text(
          text = message,
          modifier = Modifier.padding(top = 16.dp),
          style = MaterialTheme.typography.bodyLarge,
        )
      }
    }
  }
}

@Composable
internal fun FailureContent(
  title: String,
  body: String,
  onRetry: () -> Unit,
  contentBottomPadding: Dp,
  modifier: Modifier = Modifier,
) {
  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = screenContentPadding(contentBottomPadding),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    item {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
        Button(
          onClick = onRetry,
          modifier = Modifier
            .padding(top = 20.dp)
            .heightIn(min = 48.dp),
        ) {
          Text(stringResource(R.string.retry))
        }
      }
    }
  }
}

@Composable
internal fun SupersededContent(
  onRetry: () -> Unit,
  contentBottomPadding: Dp,
  modifier: Modifier = Modifier,
) {
  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = screenContentPadding(contentBottomPadding),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    item {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
          text = stringResource(R.string.load_superseded_title),
          modifier = Modifier.semantics { heading() },
          style = MaterialTheme.typography.titleLarge,
        )
        Text(
          text = stringResource(R.string.load_superseded_body),
          modifier = Modifier.padding(top = 8.dp),
          style = MaterialTheme.typography.bodyLarge,
        )
        Button(
          onClick = onRetry,
          modifier = Modifier
            .padding(top = 20.dp)
            .heightIn(min = 48.dp),
        ) {
          Text(stringResource(R.string.retry))
        }
      }
    }
  }
}

@Composable
internal fun EmptyContent(
  title: String,
  body: String,
  contentBottomPadding: Dp,
  modifier: Modifier = Modifier,
) {
  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = screenContentPadding(contentBottomPadding),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    item {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
  }
}

internal fun screenContentPadding(
  bottomContentPadding: Dp,
): PaddingValues = PaddingValues(
  start = 20.dp,
  top = 20.dp,
  end = 20.dp,
  bottom = 20.dp + bottomContentPadding,
)

@Composable
internal fun displayName(
  value: String,
  @StringRes fallback: Int,
): String = if (value.isBlank()) {
  stringResource(fallback)
} else {
  value
}
