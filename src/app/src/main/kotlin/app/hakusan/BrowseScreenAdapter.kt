package app.hakusan

import app.hakusan.extensions.SourceBrowseFailure
import app.hakusan.extensions.SourceFailure
import app.hakusan.extensions.SourceResult
import app.hakusan.extensions.SourceTitle
import app.hakusan.sdk.BrowseScreen
import app.hakusan.sdk.BrowseScreenFailure
import app.hakusan.sdk.BrowseScreenResult
import app.hakusan.sdk.BrowseScreenService
import app.hakusan.sdk.CatalogScreen
import app.hakusan.sdk.ScreenSourceId
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Serves the Catalog and the browse loads of the registered sources.
 *
 * The Catalog is the static snapshot of the [registry]. A browse load keeps the
 * source order and never reaches `:titles`, because source keys never create
 * application entities.
 */
@Inject
@SingleIn(AppScope::class)
internal class BrowseScreenAdapter(private val registry: SourceRegistry) :
  BrowseScreenService {
  override fun catalog(): CatalogScreen = registry.catalog

  override suspend fun loadBrowse(
    sourceId: ScreenSourceId
  ): BrowseScreenResult {
    val registration =
      registry.find(sourceId.toSourceIdentity())
        ?: return BrowseScreenResult.Failure(BrowseScreenFailure.SourceNotFound)

    return when (val result = registration.source.browse()) {
      is SourceResult.Success -> {
        val titles = result.value.titles.map(SourceTitle::toBrowseTitleItem)

        val screen = BrowseScreen.of(registration.catalogItem, titles)

        BrowseScreenResult.Success(screen)
      }

      is SourceResult.Failure ->
        BrowseScreenResult.Failure(result.error.toBrowseScreenFailure())
    }
  }
}

private fun SourceBrowseFailure.toBrowseScreenFailure(): BrowseScreenFailure =
  when (this) {
    SourceFailure.Unavailable -> BrowseScreenFailure.SourceUnavailable
    is SourceFailure.InvalidBrowseResult ->
      BrowseScreenFailure.InvalidObservation
  }
