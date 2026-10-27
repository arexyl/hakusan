package app.hakusan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.hakusan.ui.HakusanApp

/**
 * The launcher Activity.
 *
 * It composes the user interface with one provider for each screen service it
 * uses, browse and Library. A provider reads the application's screen-service
 * source only when the factory of a presentation owner calls it, so a service
 * and the graph behind it start with the first owner that needs them. The
 * Activity registers no Back callback: at a stack root the platform handles
 * Back.
 */
internal class HakusanActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    val hakusan = application as HakusanApplication

    setContent {
      HakusanApp(
        browse = { hakusan.screenServices.browse },
        library = { hakusan.screenServices.library },
      )
    }
  }
}
