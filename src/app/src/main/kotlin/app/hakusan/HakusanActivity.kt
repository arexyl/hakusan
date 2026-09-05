package app.hakusan

import android.os.Bundle
import app.hakusan.ui.HakusanApp
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class HakusanActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      HakusanApp(onExit = ::finish)
    }
  }
}
