package app.hakusan

import app.hakusan.sdk.DetailsScreenResult
import app.hakusan.sdk.ScreenTitleKey
import app.hakusan.sdk.TitleDetailsScreenService
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/** Adapts coordinated whole-title loads to the details screen contract. */
@Inject
@SingleIn(AppScope::class)
internal class TitleDetailsScreenAdapter(
  private val loadCoordinator: WholeTitleLoadCoordinator,
) : TitleDetailsScreenService {
  override suspend fun loadDetails(
    titleKey: ScreenTitleKey,
  ): DetailsScreenResult =
    when (val result = loadCoordinator.load(titleKey)) {
      is WholeTitleLoadResult.Success -> DetailsScreenResult.Success(
        result.progress.toDetailsScreen(
          sourceDisplayName = result.sourceDisplayName,
          details = result.details,
        ),
      )

      is WholeTitleLoadResult.Failure ->
        DetailsScreenResult.Failure(result.error)

      WholeTitleLoadResult.RejectedNotCurrent ->
        DetailsScreenResult.RejectedNotCurrent
    }
}
