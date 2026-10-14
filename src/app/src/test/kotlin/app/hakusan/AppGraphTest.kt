package app.hakusan

import app.hakusan.extensions.ChapterRefreshCompletion
import app.hakusan.extensions.ChapterSequenceStatus
import app.hakusan.extensions.SourceBrowseResult
import app.hakusan.extensions.SourceIdentity
import app.hakusan.extensions.SourceResult
import app.hakusan.extensions.SourceTitle
import app.hakusan.extensions.SourceTitleDetails
import app.hakusan.extensions.SourceTitleKey
import app.hakusan.sdk.BrowseScreenResult
import app.hakusan.sdk.ContinueSelectionResult
import app.hakusan.sdk.DetailsScreenResult
import app.hakusan.sdk.ScreenSourceId
import app.hakusan.sdk.ScreenTitleId
import app.hakusan.sdk.ScreenTitleKey
import app.hakusan.titles.AcceptedTitleObservation
import app.hakusan.titles.TitleId
import app.hakusan.titles.progressSnapshot
import dev.zacsweers.metro.createGraphFactory
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppGraphTest {
  private val source = SourceIdentity("source")

  private val registry =
    SourceRegistry.of(
      listOf(
        ScriptedBackend(
          identity = source,
          onBrowse = {
            SourceBrowseResult.create(
              source,
              listOf(SourceTitle(SourceTitleKey(source, "title"), "Title")),
            )
          },
        )
      )
    )

  @Test
  fun `each service is one instance per graph`() {
    val graph = graph(registry)

    assertSame(graph.browseScreenService, graph.browseScreenService)

    assertSame(
      graph.titleDetailsScreenService,
      graph.titleDetailsScreenService,
    )

    assertSame(graph.libraryScreenService, graph.libraryScreenService)
    assertSame(graph.continueSelectionService, graph.continueSelectionService)
  }

  @Test
  fun `separate graphs keep separate instances`() {
    val first = graph(registry)

    val second = graph(registry)

    assertNotSame(first.browseScreenService, second.browseScreenService)

    assertNotSame(
      first.titleDetailsScreenService,
      second.titleDetailsScreenService,
    )

    assertNotSame(first.libraryScreenService, second.libraryScreenService)

    assertNotSame(
      first.continueSelectionService,
      second.continueSelectionService,
    )
  }

  @Test
  fun `the graph serves the registry that created it`() {
    val graph = graph(registry)

    assertSame(registry.catalog, graph.browseScreenService.catalog())
  }

  @Test
  fun `browsing through the graph never reaches Titles`() {
    val graph = graph(registry)

    val result = runBlocking {
      graph.browseScreenService.loadBrowse(ScreenSourceId("source"))
    }

    val screen =
      assertInstanceOf(BrowseScreenResult.Success::class.java, result).screen

    assertEquals(listOf("title"), screen.titles.map { it.key.sourceTitleKey })
  }

  @Test
  fun `the Library service reads the Titles that created the graph`() {
    val uuid = Uuid.fromLongs(0x7000L, Long.MIN_VALUE)

    val titleId = checkNotNull(TitleId.ofOrNull(uuid))

    val titles =
      FakeTitles(onObserveLibraryTitleIds = { flowOf(setOf(titleId)) })

    val graph = graph(registry, titles)

    val ids = runBlocking {
      graph.libraryScreenService.observeLibraryTitleIds().single()
    }

    assertEquals(setOf(ScreenTitleId(uuid.toJavaUuid())), ids)
  }

  @Test
  fun `the Continue service reads the Titles that created the graph`() {
    val uuid = Uuid.fromLongs(0x7000L, Long.MIN_VALUE)

    val titleId = checkNotNull(TitleId.ofOrNull(uuid))

    val requested = mutableListOf<TitleId>()

    val titles =
      FakeTitles(
        onReadProgress = { id ->
          requested += id
          null
        }
      )

    val graph = graph(registry, titles)

    val result = runBlocking {
      graph.continueSelectionService.selectContinue(
        ScreenTitleId(uuid.toJavaUuid())
      )
    }

    assertSame(ContinueSelectionResult.TitleNotFound, result)
    assertEquals(listOf(titleId), requested)
  }

  @Test
  fun `the details service loads through the graph inputs`() {
    val uuid = Uuid.fromLongs(0x7000L, Long.MIN_VALUE)

    val titleId = checkNotNull(TitleId.ofOrNull(uuid))

    val backend =
      ScriptedBackend(
        identity = source,
        onDetails = { key ->
          SourceResult.Success(SourceTitleDetails(SourceTitle(key, "T"), null))
        },
        onRefreshChapters = { request ->
          ChapterRefreshCompletion.completed(
            request,
            ChapterSequenceStatus.COMPLETE,
            emptyList(),
          )
        },
      )

    val reconciled = mutableListOf<AcceptedTitleObservation>()

    val titles =
      FakeTitles(
        onReconcileTitle = { observation ->
          reconciled += observation

          progressSnapshot(titleId, observation.alias, emptyList())
        }
      )

    val graph = graph(SourceRegistry.of(listOf(backend)), titles)

    val result = runBlocking {
      graph.titleDetailsScreenService.loadDetails(
        ScreenTitleKey(ScreenSourceId("source"), "title")
      )
    }

    val screen =
      assertInstanceOf(DetailsScreenResult.Success::class.java, result).screen

    assertEquals(ScreenTitleId(uuid.toJavaUuid()), screen.id)
    assertEquals("Scripted", screen.sourceDisplayName)
    assertEquals(listOf("title"), reconciled.map { it.alias.sourceTitleKey })
  }

  @Test
  fun `the graph exposes only screen services`() {
    val accessors =
      AppGraph::class.java.methods.filter { it.parameterCount == 0 }

    assertTrue(accessors.isNotEmpty())

    for (accessor in accessors) {
      val type = accessor.returnType

      assertTrue(type.isInterface, accessor.name)
      assertEquals("app.hakusan.sdk", type.packageName, accessor.name)
    }
  }

  /** Creates a graph whose [titles] fail on every call by default. */
  private fun graph(
    registry: SourceRegistry,
    titles: FakeTitles = FakeTitles(),
  ): AppGraph = createGraphFactory<AppGraph.Factory>().create(registry, titles)
}
