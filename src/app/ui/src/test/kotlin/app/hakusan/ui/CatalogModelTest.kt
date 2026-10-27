package app.hakusan.ui

import androidx.lifecycle.viewmodel.CreationExtras
import app.hakusan.sdk.BrowseScreenResult
import app.hakusan.sdk.BrowseScreenService
import app.hakusan.sdk.CatalogScreen
import app.hakusan.sdk.CatalogSourceItem
import app.hakusan.sdk.ScreenSourceId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CatalogModelTest {
  private val sources =
    listOf(
      CatalogSourceItem(ScreenSourceId("app.hakusan.test.zeta"), "Zeta"),
      CatalogSourceItem(ScreenSourceId("app.hakusan.test.alpha"), "Alpha"),
      CatalogSourceItem(ScreenSourceId("app.hakusan.test.mu"), ""),
    )

  private val browse = CountingBrowseService(CatalogScreen.of(sources))

  @Test
  fun `the Catalog is read once in its own order`() {
    val model = CatalogModel(browse)

    assertEquals(sources, model.catalog.sources)
    assertEquals(sources, model.catalog.sources)
    assertEquals(1, browse.catalogReads)
  }

  @Test
  fun `the factory reads the service only when it creates an owner`() {
    var providerCalls = 0

    val factory = CatalogModel.factory {
      providerCalls += 1

      browse
    }

    assertEquals(0, providerCalls)

    val model = factory.create(CatalogModel::class, CreationExtras.Empty)

    assertEquals(sources, model.catalog.sources)
    assertEquals(1, providerCalls)
    assertEquals(1, browse.catalogReads)
  }
}

private class CountingBrowseService(private val catalog: CatalogScreen) :
  BrowseScreenService {
  var catalogReads = 0
    private set

  override fun catalog(): CatalogScreen {
    catalogReads += 1

    return catalog
  }

  override suspend fun loadBrowse(
    sourceId: ScreenSourceId
  ): BrowseScreenResult = throw AssertionError("The overview loads no source.")
}
