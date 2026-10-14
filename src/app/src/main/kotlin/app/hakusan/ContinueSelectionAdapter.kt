package app.hakusan

import app.hakusan.sdk.ContinueSelectionResult
import app.hakusan.sdk.ContinueSelectionService
import app.hakusan.sdk.ScreenTitleId
import app.hakusan.titles.Titles
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Resolves Continue from the current progress in [titles].
 *
 * Each invocation reads the progress again and caches nothing. `:titles`
 * resolves the target, and the adapter maps it to screen types. A chapter
 * counts as available while the canonical order lists it. The adapter infers no
 * source availability. Failures and cancellation of the progress read propagate
 * unchanged.
 */
@Inject
@SingleIn(AppScope::class)
internal class ContinueSelectionAdapter(private val titles: Titles) :
  ContinueSelectionService {
  override suspend fun selectContinue(
    titleId: ScreenTitleId
  ): ContinueSelectionResult {
    val progress =
      titles.readProgress(titleId.toTitleId())
        ?: return ContinueSelectionResult.TitleNotFound

    return progress.toContinueSelectionResult()
  }
}
