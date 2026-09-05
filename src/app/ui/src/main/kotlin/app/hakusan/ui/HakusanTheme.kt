package app.hakusan.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

private val HakusanTypography = Typography()

@Composable
fun HakusanTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  dynamicColor: Boolean = true,
  content: @Composable () -> Unit,
) {
  val context = LocalContext.current
  val colorScheme = remember(context, darkTheme, dynamicColor) {
    context.hakusanColorScheme(darkTheme, dynamicColor)
  }
  MaterialTheme(
    colorScheme = colorScheme,
    typography = HakusanTypography,
    content = content,
  )
}

private fun Context.hakusanColorScheme(
  darkTheme: Boolean,
  dynamicColor: Boolean,
) = when {
  dynamicColor && darkTheme -> dynamicDarkColorScheme(this)
  dynamicColor -> dynamicLightColorScheme(this)
  darkTheme -> darkColorScheme()
  else -> lightColorScheme()
}
