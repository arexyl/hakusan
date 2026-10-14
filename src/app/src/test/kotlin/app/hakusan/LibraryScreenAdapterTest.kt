package app.hakusan

import app.hakusan.extensions.SourceIdentity
import app.hakusan.sdk.AddToLibraryScreenResult
import app.hakusan.sdk.LibraryResumeState
import app.hakusan.sdk.LibraryScreen
import app.hakusan.sdk.LibraryShelfMembers
import app.hakusan.sdk.LibraryTitleItem
import app.hakusan.sdk.LibraryTitleProgress
import app.hakusan.sdk.ScreenShelfId
import app.hakusan.sdk.ScreenSourceId
import app.hakusan.sdk.ScreenTitleId
import app.hakusan.sdk.ScreenTitleKey
import app.hakusan.titles.LibraryAddResult
import app.hakusan.titles.LibraryCategory
import app.hakusan.titles.LibrarySnapshot
import app.hakusan.titles.LibraryTitle
import app.hakusan.titles.ResumeAvailability
import app.hakusan.titles.SourceTitleAlias
import app.hakusan.titles.TitleId
import app.hakusan.titles.categoryId
import app.hakusan.titles.categorySelectionRequired
import app.hakusan.titles.libraryAddSuccess
import app.hakusan.titles.libraryCategory
import app.hakusan.titles.libraryMembership
import app.hakusan.titles.librarySnapshot
import app.hakusan.titles.libraryTitle
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LibraryScreenAdapterTest {
  private val registry =
    SourceRegistry.of(
      listOf(
        ScriptedBackend(
          identity = SourceIdentity("source"),
          onDisplayName = { "Source name" },
        )
      )
    )

  @Test
  fun `a Library title keeps its exact identities and metadata`() {
    val title =
      libraryTitle(
        alias = SourceTitleAlias.of("source", " Key "),
        displayName = "Title",
        description = "Description",
        chapterCount = 3,
        readChapterCount = 1,
        resumeAvailability = ResumeAvailability.AVAILABLE,
      )

    val category = libraryCategory(categoryId(7), "Shelf")

    val snapshot =
      librarySnapshot(
        titles = mapOf(titleId(1) to title),
        shelves = mapOf(category to setOf(titleId(1))),
      )

    val item =
      LibraryTitleItem(
        id = ScreenTitleId(applicationUuid(1)),
        key = ScreenTitleKey(ScreenSourceId("source"), " Key "),
        displayName = "Title",
        description = "Description",
        sourceDisplayName = "Source name",
        progress = LibraryTitleProgress(3, 1, LibraryResumeState.AVAILABLE),
      )

    val shelf = LibraryShelfMembers(ScreenShelfId(7), "Shelf", setOf(item.id))

    val expected =
      LibraryScreen.of(listOf(item), listOf(shelf), Locale.getDefault())

    assertEquals(expected, screen(snapshot))
  }

  @Test
  fun `the shelf key is the category key`() {
    val keys = listOf(1L, 42L, Long.MAX_VALUE)

    val shelves = keys.associate { key ->
      libraryCategory(categoryId(key), "Shelf") to emptySet<TitleId>()
    }

    val screen = screen(librarySnapshot(emptyMap(), shelves))

    assertEquals(
      keys.map(::ScreenShelfId).toSet(),
      screen.shelves.map { it.id }.toSet(),
    )
  }

  @Test
  fun `an empty category is kept as an empty shelf`() {
    val filled = libraryCategory(categoryId(1), "Filled")

    val empty = libraryCategory(categoryId(2), "Empty")

    val snapshot =
      librarySnapshot(
        titles = mapOf(titleId(1) to title("title", "Title")),
        shelves = mapOf(filled to setOf(titleId(1)), empty to emptySet()),
      )

    val shelves = screen(snapshot).shelves.associateBy { it.name }

    assertEquals(setOf("Filled", "Empty"), shelves.keys)
    assertEquals(1, shelves.getValue("Filled").titleCount)
    assertEquals(0, shelves.getValue("Empty").titleCount)
  }

  @Test
  fun `no categories differs from one empty category`() {
    val none = screen(librarySnapshot(emptyMap(), emptyMap()))

    val category = libraryCategory(categoryId(1), "Empty")

    val oneEmpty =
      screen(librarySnapshot(emptyMap(), mapOf(category to emptySet())))

    assertEquals(emptyList<Any>(), none.shelves)
    assertEquals(listOf(0), oneEmpty.shelves.map { it.titleCount })
    assertNotEquals(none, oneEmpty)
  }

  @Test
  fun `a title on two shelves appears once`() {
    val title =
      libraryTitle(
        alias = alias("title"),
        displayName = "Title",
        chapterCount = 4,
        readChapterCount = 2,
      )

    val first = libraryCategory(categoryId(1), "First")

    val second = libraryCategory(categoryId(2), "Second")

    val snapshot =
      librarySnapshot(
        titles = mapOf(titleId(1) to title),
        shelves =
          mapOf(first to setOf(titleId(1)), second to setOf(titleId(1))),
      )

    val screen = screen(snapshot)

    val id = ScreenTitleId(applicationUuid(1))

    assertEquals(setOf(id), screen.titlesById.keys)

    assertEquals(
      listOf(listOf(id), listOf(id)),
      screen.shelves.map { it.titleIds },
    )

    assertEquals(
      LibraryTitleProgress(4, 2, LibraryResumeState.NONE),
      screen.titlesById.getValue(id).progress,
    )
  }

  @Test
  fun `shuffled snapshot input gives an equal screen`() {
    // Sequences that are equal modulo 8 share a bucket of the hash map that
    // the snapshot copies, which then iterates in the given order. The first
    // assertions confirm that the adapter receives different orders.
    val titles =
      listOf(
        titleId(8) to title("one", "Same", "b"),
        titleId(16) to title("two", "Same", "a"),
        titleId(24) to title("three", "Same", "a"),
        titleId(32) to title("four", "alpha"),
        titleId(40) to title("five", "Beta"),
      )

    val shelves =
      listOf(
        libraryCategory(categoryId(1), "Same") to
          setOf(titleId(8), titleId(32)),
        libraryCategory(categoryId(2), "Same") to setOf(titleId(16)),
        libraryCategory(categoryId(3), "other") to
          setOf(titleId(24), titleId(40)),
        libraryCategory(categoryId(4), "Empty") to emptySet(),
      )

    val snapshots =
      listOf(
        snapshot(titles, shelves),
        snapshot(titles.reversed(), shelves.reversed()),
        snapshot(titles.shuffled(Random(1)), shelves.shuffled(Random(2))),
        snapshot(titles.shuffled(Random(3)), shelves.shuffled(Random(4))),
      )

    val titleOrders = snapshots.map { it.titles.keys.toList() }

    val shelfOrders = snapshots.map { snapshot ->
      snapshot.shelves.map { it.category.id }
    }

    assertTrue(titleOrders.toSet().size > 1, "Title orders must differ.")
    assertTrue(shelfOrders.toSet().size > 1, "Shelf orders must differ.")

    val screens = snapshots.map(::screen)

    for (screen in screens.drop(1)) {
      assertEquals(screens.first(), screen)
    }
  }

  @Test
  fun `each resume availability maps to its screen state`() {
    val expected =
      mapOf(
        ResumeAvailability.NONE to LibraryResumeState.NONE,
        ResumeAvailability.AVAILABLE to LibraryResumeState.AVAILABLE,
        ResumeAvailability.TEMPORARILY_UNAVAILABLE to
          LibraryResumeState.TEMPORARILY_UNAVAILABLE,
      )

    assertEquals(ResumeAvailability.entries.toSet(), expected.keys)

    for ((availability, state) in expected) {
      val title =
        libraryTitle(
          alias = alias("title"),
          displayName = "Title",
          chapterCount = 2,
          readChapterCount = 1,
          resumeAvailability = availability,
        )

      val category = libraryCategory(categoryId(1), "Shelf")

      val snapshot =
        librarySnapshot(
          titles = mapOf(titleId(1) to title),
          shelves = mapOf(category to setOf(titleId(1))),
        )

      val item = screen(snapshot).titlesById.values.single()

      assertEquals(LibraryTitleProgress(2, 1, state), item.progress)
    }
  }

  @Test
  fun `a title of an unregistered source has an empty source name`() {
    val registered =
      libraryTitle(alias = alias("title"), displayName = "Registered")

    val unregistered =
      libraryTitle(
        alias = SourceTitleAlias.of("other-source", "title"),
        displayName = "Unregistered",
      )

    val category = libraryCategory(categoryId(1), "Shelf")

    val snapshot =
      librarySnapshot(
        titles = mapOf(titleId(1) to registered, titleId(2) to unregistered),
        shelves = mapOf(category to setOf(titleId(1), titleId(2))),
      )

    val names =
      screen(snapshot).titlesById.values.associate {
        it.displayName to it.sourceDisplayName
      }

    assertEquals(
      mapOf("Registered" to "Source name", "Unregistered" to ""),
      names,
    )
  }

  @Test
  fun `each emission collates under the default locale of that moment`() {
    val category = libraryCategory(categoryId(1), "Shelf")

    val snapshot =
      librarySnapshot(
        titles =
          mapOf(
            titleId(1) to title("zebra", "zebra"),
            titleId(2) to title("öl", "öl"),
          ),
        shelves = mapOf(category to setOf(titleId(1), titleId(2))),
      )

    val german = Locale.forLanguageTag("de")

    val swedish = Locale.forLanguageTag("sv")

    val titles =
      FakeTitles(
        onObserveLibrary = {
          flow {
            Locale.setDefault(german)
            emit(snapshot)
            Locale.setDefault(swedish)
            emit(snapshot)
          }
        }
      )

    val original = Locale.getDefault()

    val screens =
      try {
        runBlocking { adapter(titles).observeLibrary().toList() }
      } finally {
        Locale.setDefault(original)
      }

    val orders = screens.map { screen ->
      screen.shelves.single().titleIds.map {
        screen.titlesById.getValue(it).displayName
      }
    }

    assertEquals(listOf(listOf("öl", "zebra"), listOf("zebra", "öl")), orders)
  }

  @Test
  fun `each Library screen is built and ordered on the worker`() {
    val category = libraryCategory(categoryId(1), "Shelf")

    val snapshot =
      librarySnapshot(
        titles =
          mapOf(
            titleId(1) to title("zebra", "zebra"),
            titleId(2) to title("öl", "öl"),
          ),
        shelves = mapOf(category to setOf(titleId(1), titleId(2))),
      )

    val german = Locale.forLanguageTag("de")

    val swedish = Locale.forLanguageTag("sv")

    val emissionThreads = CopyOnWriteArrayList<Thread>()

    val emitted = CountDownLatch(1)

    val titles =
      FakeTitles(
        onObserveLibrary = {
          flow {
            emissionThreads += Thread.currentThread()
            Locale.setDefault(german)
            emit(snapshot)
            emissionThreads += Thread.currentThread()
            Locale.setDefault(swedish)
            emit(snapshot)
            emitted.countDown()
          }
        }
      )

    // runBlocking runs its coroutines on the calling thread.
    val collectingThread = Thread.currentThread()

    val original = Locale.getDefault()

    val screens =
      try {
        runBlocking {
          withTimeout(5.seconds) {
            val collected =
              async(start = CoroutineStart.UNDISPATCHED) {
                adapter(titles).observeLibrary().toList()
              }

            // Blocks the collecting thread until the observation has emitted
            // twice and left the Swedish locale in place. A mapping on this
            // thread would then order both screens under that locale.
            assertTrue(emitted.await(5, TimeUnit.SECONDS))

            collected.await()
          }
        }
      } finally {
        Locale.setDefault(original)
      }

    assertEquals(2, emissionThreads.size)

    for (thread in emissionThreads) {
      assertNotSame(collectingThread, thread)
    }

    val items = listOf(libraryItem(1, "zebra"), libraryItem(2, "öl"))

    val shelf =
      LibraryShelfMembers(
        ScreenShelfId(1),
        "Shelf",
        items.mapTo(HashSet()) { it.id },
      )

    val expected =
      listOf(german, swedish).map { locale ->
        LibraryScreen.of(items, listOf(shelf), locale)
      }

    assertEquals(expected, screens)
  }

  @Test
  fun `membership is mapped on the worker in emission order`() {
    val emissionThreads = CopyOnWriteArrayList<Thread>()

    val mappingThreads = CopyOnWriteArrayList<Thread>()

    val sequences = listOf(setOf(1, 2), emptySet(), setOf(3))

    val titles =
      FakeTitles(
        onObserveLibraryTitleIds = {
          flow {
            for (ids in sequences) {
              val titleIds = ids.mapTo(HashSet(), ::titleId)

              emissionThreads += Thread.currentThread()
              emit(ThreadRecordingSet(titleIds, mappingThreads))
            }
          }
        }
      )

    // runBlocking runs its coroutines on the calling thread.
    val collectingThread = Thread.currentThread()

    val emitted = runBlocking {
      withTimeout(5.seconds) {
        adapter(titles).observeLibraryTitleIds().toList()
      }
    }

    assertEquals(emissionThreads, mappingThreads)

    for (thread in mappingThreads) {
      assertNotSame(collectingThread, thread)
    }

    val expected = sequences.map { ids ->
      ids.mapTo(HashSet()) { ScreenTitleId(applicationUuid(it)) }
    }

    assertEquals(expected, emitted)
  }

  @Test
  fun `membership comes from its narrow observation as unmodifiable sets`() {
    val titles =
      FakeTitles(
        onObserveLibraryTitleIds = {
          flowOf(setOf(titleId(1), titleId(2)), emptySet())
        }
      )

    val emitted = runBlocking {
      adapter(titles).observeLibraryTitleIds().toList()
    }

    val expected =
      listOf(
        setOf(
          ScreenTitleId(applicationUuid(1)),
          ScreenTitleId(applicationUuid(2)),
        ),
        emptySet(),
      )

    assertEquals(expected, emitted)

    for (ids in emitted) {
      assertThrows(UnsupportedOperationException::class.java) {
        (ids as MutableSet<ScreenTitleId>).add(
          ScreenTitleId(applicationUuid(3))
        )
      }
    }
  }

  @Test
  fun `automatic Add maps each outcome and passes the same identity`() {
    val membership = libraryMembership(titleId(1), setOf(categoryId(1)))

    val categories =
      setOf(
        libraryCategory(categoryId(1), "First"),
        libraryCategory(categoryId(2), "Second"),
      )

    val outcomes =
      listOf(
        libraryAddSuccess(membership) to AddToLibraryScreenResult.Success,
        categorySelectionRequired(categories) to
          AddToLibraryScreenResult.CategorySelectionRequired,
        LibraryAddResult.TitleNotFound to
          AddToLibraryScreenResult.TitleNotFound,
      )

    for ((outcome, expected) in outcomes) {
      val received = mutableListOf<TitleId>()

      val titles =
        FakeTitles(
          onAutomaticAdd = { id ->
            received += id
            outcome
          }
        )

      val result = runBlocking {
        adapter(titles).addToLibrary(ScreenTitleId(applicationUuid(1)))
      }

      assertEquals(expected, result)
      assertEquals(listOf(titleId(1)), received)
    }
  }

  @Test
  fun `cancellation and defects propagate without conversion`() {
    for (thrown in
      listOf(CancellationException("Cancelled"), IllegalStateException())) {
      val titles =
        FakeTitles(
          onObserveLibrary = { flow<LibrarySnapshot> { throw thrown } },
          onObserveLibraryTitleIds = { flow<Set<TitleId>> { throw thrown } },
          onAutomaticAdd = { throw thrown },
        )

      val adapter = adapter(titles)

      val observations: List<suspend () -> Unit> =
        listOf(
          { adapter.observeLibrary().toList() },
          { adapter.observeLibraryTitleIds().toList() },
        )

      for (observation in observations) {
        val propagated =
          assertThrows(Throwable::class.java) { runBlocking { observation() } }

        assertPropagatedAcrossWorker(thrown, propagated)
      }

      val propagated =
        assertThrows(Throwable::class.java) {
          runBlocking {
            adapter.addToLibrary(ScreenTitleId(applicationUuid(1)))
          }
        }

      assertSame(thrown, propagated)
    }
  }

  private fun adapter(titles: FakeTitles): LibraryScreenAdapter =
    LibraryScreenAdapter(registry, titles)

  private fun screen(snapshot: LibrarySnapshot): LibraryScreen {
    val titles = FakeTitles(onObserveLibrary = { flowOf(snapshot) })

    return runBlocking { adapter(titles).observeLibrary().single() }
  }
}

/**
 * A title identity set that records the thread of each iteration. The adapter
 * maps a membership set by iterating it.
 */
private class ThreadRecordingSet(
  private val titleIds: Set<TitleId>,
  private val threads: MutableList<Thread>,
) : AbstractSet<TitleId>() {
  override val size: Int
    get() = titleIds.size

  override fun iterator(): Iterator<TitleId> {
    threads += Thread.currentThread()

    return titleIds.iterator()
  }
}

/**
 * Asserts that [propagated] is [thrown] itself, or the copy of it that
 * kotlinx.coroutines stack trace recovery makes when [thrown] crosses the
 * worker of an observation. Recovery runs in kotlinx.coroutines debug mode,
 * which host tests turn on through assertions. The copy keeps the class and the
 * message, has the original as its cause, and carries the coroutine boundary
 * frame in its stack trace. A wrapper made by the adapter fails.
 */
private fun assertPropagatedAcrossWorker(
  thrown: Throwable,
  propagated: Throwable,
) {
  if (propagated === thrown) {
    return
  }

  assertSame(thrown.javaClass, propagated.javaClass)
  assertEquals(thrown.message, propagated.message)
  assertSame(thrown, propagated.cause)
  assertTrue(
    propagated.stackTrace.any { it.className == COROUTINE_BOUNDARY_FRAME }
  )
}

/** Returns the snapshot that [titles] and [shelves] build in their order. */
private fun snapshot(
  titles: List<Pair<TitleId, LibraryTitle>>,
  shelves: List<Pair<LibraryCategory, Set<TitleId>>>,
): LibrarySnapshot =
  librarySnapshot(titles.toMap(LinkedHashMap()), shelves.toMap(LinkedHashMap()))

/** Returns the title [key] of the registered source with its details. */
private fun title(
  key: String,
  displayName: String,
  description: String? = null,
): LibraryTitle =
  libraryTitle(
    alias = alias(key),
    displayName = displayName,
    description = description,
  )

/**
 * Returns the screen item that the adapter maps from title number [sequence]
 * when [title] builds it with [key] as its key and name.
 */
private fun libraryItem(sequence: Int, key: String): LibraryTitleItem =
  LibraryTitleItem(
    id = ScreenTitleId(applicationUuid(sequence)),
    key = ScreenTitleKey(ScreenSourceId("source"), key),
    displayName = key,
    description = null,
    sourceDisplayName = "Source name",
    progress = LibraryTitleProgress(0, 0, LibraryResumeState.NONE),
  )

private fun alias(key: String): SourceTitleAlias =
  SourceTitleAlias.of("source", key)

/** Returns the title identity number [sequence]. */
private fun titleId(sequence: Int): TitleId {
  val uuid = Uuid.fromLongs(VERSION_7_HIGH_BITS, variantLowBits(sequence))

  return checkNotNull(TitleId.ofOrNull(uuid))
}

/** Returns the UUID of the title identity number [sequence]. */
private fun applicationUuid(sequence: Int): UUID =
  UUID(VERSION_7_HIGH_BITS, variantLowBits(sequence))

private fun variantLowBits(sequence: Int): Long =
  Long.MIN_VALUE or sequence.toLong()

/** The high bits of a UUIDv7 with a zero timestamp and zero random bits. */
private const val VERSION_7_HIGH_BITS = 0x7000L

/** The class of the frame that marks a recovered coroutine boundary. */
private const val COROUTINE_BOUNDARY_FRAME = "_COROUTINE._BOUNDARY"
