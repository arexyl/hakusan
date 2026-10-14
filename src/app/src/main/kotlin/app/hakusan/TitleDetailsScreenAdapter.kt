package app.hakusan

import app.hakusan.sdk.DetailsScreenResult
import app.hakusan.sdk.ScreenTitleKey
import app.hakusan.sdk.TitleDetailsScreenService
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Serves title details through the whole-title [load].
 *
 * Each call runs one load and maps its outcome one to one. A superseded load
 * stays arbitration, and the adapter never retries. Cancellation and unexpected
 * defects of the load propagate unchanged.
 */
@Inject
@SingleIn(AppScope::class)
internal class TitleDetailsScreenAdapter(private val load: WholeTitleLoad) :
  TitleDetailsScreenService {
  override suspend fun loadDetails(
    titleKey: ScreenTitleKey
  ): DetailsScreenResult =
    when (val result = load.load(titleKey.toSourceTitleKey())) {
      is TitleLoadResult.Reconciled -> {
        val screen =
          result.progress.toTitleDetailsScreen(
            observation = result.observation,
            sourceDisplayName = result.sourceDisplayName,
          )

        DetailsScreenResult.Success(screen)
      }

      is TitleLoadResult.Failed -> DetailsScreenResult.Failure(result.error)

      TitleLoadResult.RejectedNotCurrent ->
        DetailsScreenResult.RejectedNotCurrent
    }
}
