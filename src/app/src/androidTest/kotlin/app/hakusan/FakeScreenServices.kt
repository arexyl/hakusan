package app.hakusan

import androidx.test.core.app.ApplicationProvider
import app.hakusan.sdk.AddToLibraryScreenResult
import app.hakusan.sdk.BrowseScreenResult
import app.hakusan.sdk.BrowseScreenService
import app.hakusan.sdk.CatalogScreen
import app.hakusan.sdk.LibraryScreen
import app.hakusan.sdk.LibraryScreenService
import app.hakusan.sdk.ScreenSourceId
import app.hakusan.sdk.ScreenTitleId
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * A browse service with a Catalog that the test sets.
 *
 * It counts the Catalog reads. No suite of this unit loads a source.
 */
internal class FakeBrowseScreenService : BrowseScreenService {
  private val reads = AtomicInteger()

  /** The Catalog that the next read returns; empty until a test sets it. */
  @Volatile var catalog: CatalogScreen = CatalogScreen.of(emptyList())

  /** The number of Catalog reads. */
  val catalogReads: Int
    get() = reads.get()

  override fun catalog(): CatalogScreen {
    reads.incrementAndGet()

    return catalog
  }

  override suspend fun loadBrowse(
    sourceId: ScreenSourceId
  ): BrowseScreenResult = throw AssertionError("No suite loads a source yet.")
}

/**
 * A Library service with a committed Library that the test publishes.
 *
 * Each collection of the Library starts with the published screen, if any, and
 * receives every later one. It counts the collections that started and those
 * that ended.
 */
internal class FakeLibraryScreenService : LibraryScreenService {
  private val screen = MutableStateFlow<LibraryScreen?>(null)
  private val started = AtomicInteger()
  private val stopped = AtomicInteger()

  /** The number of Library collections that started. */
  val collections: Int
    get() = started.get()

  /** The number of Library collections that ended. */
  val stops: Int
    get() = stopped.get()

  /** Publishes [library] as the committed Library. */
  fun publish(library: LibraryScreen) {
    screen.value = library
  }

  override fun observeLibrary(): Flow<LibraryScreen> =
    screen
      .filterNotNull()
      .onStart { started.incrementAndGet() }
      .onCompletion { stopped.incrementAndGet() }

  override fun observeLibraryTitleIds(): Flow<Set<ScreenTitleId>> =
    throw AssertionError("No suite observes the membership yet.")

  override suspend fun addToLibrary(
    titleId: ScreenTitleId
  ): AddToLibraryScreenResult =
    throw AssertionError("No suite adds a title yet.")
}

/**
 * The screen-service source of the device suites.
 *
 * It counts the calls of each screen-service provider. Each presentation owner
 * calls its provider once, in its factory, so the counts are also the numbers
 * of owners created.
 */
internal class FakeScreenServices : ScreenServiceSource {
  private val browseReads = AtomicInteger()
  private val libraryReads = AtomicInteger()

  /** The fake behind [browse]. */
  val browseService = FakeBrowseScreenService()

  /** The fake behind [library]. */
  val libraryService = FakeLibraryScreenService()

  /** The number of browse provider calls: the Catalog owners created. */
  val browseProviderCalls: Int
    get() = browseReads.get()

  /** The number of Library provider calls: the Library owners created. */
  val libraryProviderCalls: Int
    get() = libraryReads.get()

  /** The number of presentation owners created. */
  val ownerCreations: Int
    get() = browseProviderCalls + libraryProviderCalls

  override val browse: BrowseScreenService
    get() {
      browseReads.incrementAndGet()

      return browseService
    }

  override val library: LibraryScreenService
    get() {
      libraryReads.incrementAndGet()

      return libraryService
    }
}

/**
 * Installs [services] as the application's screen-service source before the
 * Activity starts, and restores the previous source after the test.
 *
 * Order it outside the Compose rule, which launches the Activity. The fakes
 * reach the presentation owners through their factories, and they survive
 * recreation and simulated process death, because the process keeps its
 * Application.
 */
internal class ScreenServicesRule(private val services: FakeScreenServices) :
  TestRule {
  override fun apply(base: Statement, description: Description): Statement =
    object : Statement() {
      override fun evaluate() {
        val application =
          ApplicationProvider.getApplicationContext<HakusanApplication>()

        val previous = application.screenServices

        application.screenServices = services

        try {
          base.evaluate()
        } finally {
          application.screenServices = previous
        }
      }
    }
}
