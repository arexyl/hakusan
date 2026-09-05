package app.hakusan

import app.hakusan.extensions.SourceFailure
import app.hakusan.extensions.SourceResult
import app.hakusan.sdk.BrowseScreen
import app.hakusan.sdk.BrowseScreenFailure
import app.hakusan.sdk.BrowseScreenResult
import app.hakusan.sdk.BrowseScreenService
import app.hakusan.sdk.BrowseTitleItem
import app.hakusan.sdk.CatalogScreen
import app.hakusan.sdk.ScreenSourceId
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@Inject
@SingleIn(AppScope::class)
internal class BrowseScreenAdapter(
  private val sourceRegistry: SourceRegistry,
) : BrowseScreenService {
  override fun catalog(): CatalogScreen = sourceRegistry.catalog

  override suspend fun loadBrowse(
    sourceId: ScreenSourceId,
  ): BrowseScreenResult {
    val registration = sourceRegistry.find(sourceId)
      ?: return BrowseScreenResult.Failure(
        BrowseScreenFailure.SourceNotFound,
      )
    val backend = registration.backend
    return when (val result = backend.browse()) {
      is SourceResult.Failure -> BrowseScreenResult.Failure(
        when (result.error) {
          SourceFailure.Unavailable -> BrowseScreenFailure.SourceUnavailable
          else -> BrowseScreenFailure.InvalidObservation
        },
      )

      is SourceResult.Success -> {
        val observation = result.value
        if (observation.source != backend.identity) {
          return BrowseScreenResult.Failure(
            BrowseScreenFailure.InvalidObservation,
          )
        }
        BrowseScreenResult.Success(
          BrowseScreen.of(
            source = registration.catalogItem,
            titles = observation.titles.map { title ->
              BrowseTitleItem(
                key = title.key.toScreenKey(),
                displayName = title.displayName,
              )
            },
          ),
        )
      }
    }
  }
}
