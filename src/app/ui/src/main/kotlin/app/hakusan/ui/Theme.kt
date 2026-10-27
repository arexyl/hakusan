package app.hakusan.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * The Material theme of the application.
 *
 * Every color comes from the dynamic Material scheme of the system, dark while
 * the system dark mode is on and light otherwise. The minimum SDK always
 * provides dynamic color, so the theme has no fixed palette.
 */
@Composable
internal fun HakusanTheme(content: @Composable () -> Unit) {
  val context = LocalContext.current

  val dark = isSystemInDarkTheme()

  val colorScheme =
    remember(context, dark) {
      if (dark) {
        dynamicDarkColorScheme(context)
      } else {
        dynamicLightColorScheme(context)
      }
    }

  MaterialTheme(colorScheme = colorScheme, content = content)
}
