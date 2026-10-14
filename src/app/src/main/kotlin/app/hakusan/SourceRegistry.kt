package app.hakusan

import app.hakusan.extensions.CorrelatedSource
import app.hakusan.extensions.SourceBackend
import app.hakusan.extensions.SourceIdentity
import app.hakusan.sdk.CatalogScreen
import app.hakusan.sdk.CatalogSourceItem

/**
 * The sources registered in this process, in explicit Catalog order.
 *
 * One backend serves each source identity. A registration reaches its backend
 * only through [CorrelatedSource], so no consumer receives an unchecked result.
 * The display name of each source is read once, at registration, and [catalog]
 * is a static snapshot of the registrations.
 */
internal class SourceRegistry
private constructor(
  val catalog: CatalogScreen,
  private val registrations: Map<SourceIdentity, SourceRegistration>,
) {
  /** Returns the registration of exactly [identity], or null. */
  fun find(identity: SourceIdentity): SourceRegistration? =
    registrations[identity]

  companion object {
    /**
     * Registers each of [backends] once, in iteration order, which becomes the
     * Catalog order.
     *
     * @throws IllegalArgumentException if two backends share an identity.
     */
    fun of(backends: Iterable<SourceBackend>): SourceRegistry {
      val registrations = HashMap<SourceIdentity, SourceRegistration>()

      val items = ArrayList<CatalogSourceItem>()

      for (backend in backends) {
        val source = CorrelatedSource(backend)

        require(source.identity !in registrations) {
          "A source identity must have exactly one registered backend."
        }

        val item =
          CatalogSourceItem(
            id = source.identity.toScreenSourceId(),
            displayName = source.displayName,
          )

        registrations[source.identity] = SourceRegistration(source, item)
        items += item
      }

      val catalog = CatalogScreen.of(items)

      return SourceRegistry(catalog, registrations)
    }
  }
}

/**
 * One registered source.
 *
 * [catalogItem] is its Catalog entry, with the display name read at
 * registration.
 */
internal class SourceRegistration(
  val source: CorrelatedSource,
  val catalogItem: CatalogSourceItem,
)
