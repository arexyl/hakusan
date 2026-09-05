package app.hakusan

import app.hakusan.extensions.SourceBackend
import app.hakusan.sdk.CatalogScreen
import app.hakusan.sdk.CatalogSourceItem
import app.hakusan.sdk.ScreenSourceId
import java.util.Collections
import java.util.LinkedHashMap

/** Process-local source registrations in explicit Catalog order. */
internal class SourceRegistry private constructor(
  val catalog: CatalogScreen,
  private val registrationsById: Map<ScreenSourceId, SourceRegistration>,
) {
  fun find(sourceId: ScreenSourceId): SourceRegistration? =
    registrationsById[sourceId]

  companion object {
    fun of(backends: Iterable<SourceBackend>): SourceRegistry {
      val ownedBackends = Collections.unmodifiableList(backends.toMutableList())
      val registrationsById =
        LinkedHashMap<ScreenSourceId, SourceRegistration>()
      val catalogItems = ArrayList<CatalogSourceItem>(ownedBackends.size)
      ownedBackends.forEach { backend ->
        val id = ScreenSourceId(backend.identity.value)
        val catalogItem = CatalogSourceItem(
          id = id,
          displayName = backend.displayName,
        )
        require(
          registrationsById.put(
            id,
            SourceRegistration(catalogItem, backend),
          ) == null,
        ) {
          "A source identity must have exactly one registered backend."
        }
        catalogItems += catalogItem
      }
      return SourceRegistry(
        catalog = CatalogScreen.of(catalogItems),
        registrationsById = Collections.unmodifiableMap(registrationsById),
      )
    }
  }
}

internal data class SourceRegistration(
  val catalogItem: CatalogSourceItem,
  val backend: SourceBackend,
)
