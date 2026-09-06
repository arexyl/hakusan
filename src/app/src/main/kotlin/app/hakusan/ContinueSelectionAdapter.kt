package app.hakusan

import app.hakusan.sdk.ContinueSelectionResult
import app.hakusan.sdk.ContinueSelectionService
import app.hakusan.sdk.ScreenTitleId
import app.hakusan.titles.TitleId
import app.hakusan.titles.Titles
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.first

/** Adapts current title progress to the screen-facing Continue contract. */
@Inject
@SingleIn(AppScope::class)
internal class ContinueSelectionAdapter(
  private val titles: Titles,
) : ContinueSelectionService {
  override suspend fun selectContinue(
    titleId: ScreenTitleId,
  ): ContinueSelectionResult {
    val progress = titles.observeReadingProgress(TitleId(titleId.value)).first()
      ?: return ContinueSelectionResult.TitleNotFound
    return progress.toContinueState().toSelectionResult()
  }
}
