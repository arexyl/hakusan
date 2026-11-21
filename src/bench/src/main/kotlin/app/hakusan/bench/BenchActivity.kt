package app.hakusan.bench

import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The one activity of the harness: the viewport, and the block that the launch
 * intent names.
 *
 * It runs a block once per process and then finishes. The host restarts the
 * process for the next block. Without a plan it only shows the viewport.
 */
internal class BenchActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

    // The system bars would draw frames of their own beside the viewport.
    window.insetsController?.let { bars ->
      bars.systemBarsBehavior =
        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

      bars.hide(WindowInsets.Type.systemBars())
    }

    val conditions = Conditions(this)
    val pinned = conditions.pinDisplayMode()
    val corpus = Corpus.open(assets, File(filesDir, "corpus"))
    val consumer = DisplayConsumer()

    // A recreated activity never repeats the block of its process.
    val plan = if (savedInstanceState == null) Plan.of(intent) else null

    setContent {
      Viewport(consumer, corpus.viewport)

      if (plan != null) {
        LaunchedEffect(plan) {
          val runner = TrialRunner(plan, corpus, consumer, conditions, pinned)

          // The record is also written when the composition leaves before
          // the block ends, so the host learns how far the block came.
          try {
            runner.run()
          } finally {
            withContext(NonCancellable) { write(plan.record, runner.record) }
            finishAndRemoveTask()
          }
        }
      }
    }
  }

  /**
   * Writes [record] under its final name only once it is complete, so the host
   * never reads a partial record.
   */
  private suspend fun write(name: String, record: JSONObject) {
    withContext(Dispatchers.IO) {
      val directory = checkNotNull(getExternalFilesDir(null))
      val partial = File(directory, "$name.partial")

      partial.writeText(record.toString(2))
      check(partial.renameTo(File(directory, name))) { "No record written." }
    }
  }
}
