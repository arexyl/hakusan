package app.hakusan.ui

import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic

/**
 * A route of the application shell.
 *
 * A route saves exact identity components as primitive strings, under an
 * explicit serial name, so moving its declaration never changes its saved form.
 */
@Serializable
internal sealed interface HakusanRoute : NavKey {
  /**
   * The key of the entry state of this route.
   *
   * A root has a fixed key. A [NestedRoute] uses the private token of its push.
   */
  val contentKey: String
}

/** The root of the Library stack. */
@Serializable
@SerialName("library")
internal data object LibraryRoute : HakusanRoute {
  override val contentKey: String
    get() = "library"
}

/** The root of the Catalog stack. */
@Serializable
@SerialName("catalog")
internal data object CatalogRoute : HakusanRoute {
  override val contentKey: String
    get() = "catalog"
}

/**
 * A route that a stack holds above its root. A root never implements it.
 *
 * The marker carries nothing itself. Each implementation must also be a
 * [HakusanRoute] that [routeConfiguration] registers, so that the shell can
 * show and save it. Each push must create a new instance with a random token of
 * its own, and that token is its [HakusanRoute.contentKey]. Two pushes of equal
 * identities therefore stay distinct entries, and a route restored from saved
 * state never equals a later push. The token identifies a push only, never a
 * title or a source.
 */
internal interface NestedRoute : NavKey

/**
 * The saved-state configuration of the back stacks of the shell.
 *
 * It registers every [HakusanRoute] under its serial name. The serializers are
 * registered explicitly, so an optimized build keeps them without reflection.
 */
internal val routeConfiguration: SavedStateConfiguration =
  SavedStateConfiguration {
    serializersModule = SerializersModule {
      polymorphic(NavKey::class) {
        subclass(LibraryRoute::class, LibraryRoute.serializer())
        subclass(CatalogRoute::class, CatalogRoute.serializer())
      }
    }
  }
