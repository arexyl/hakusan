package app.hakusan

import app.hakusan.extensions.ChapterRefreshCompletion
import app.hakusan.extensions.ChapterRefreshRequest
import app.hakusan.extensions.ChapterSequenceStatus
import app.hakusan.extensions.SourceChapter
import app.hakusan.extensions.SourceChapterKey
import app.hakusan.extensions.SourceDetailsFailure
import app.hakusan.extensions.SourceFailure
import app.hakusan.extensions.SourceIdentity
import app.hakusan.extensions.SourceResult
import app.hakusan.extensions.SourceTitle
import app.hakusan.extensions.SourceTitleDetails
import app.hakusan.extensions.SourceTitleKey
import app.hakusan.sdk.ContinueSelectionResult
import app.hakusan.sdk.ContinueState
import app.hakusan.sdk.ContinueTarget
import app.hakusan.sdk.ContinueUnavailableReason
import app.hakusan.sdk.DetailsChapterItem
import app.hakusan.sdk.DetailsScreenFailure
import app.hakusan.sdk.DetailsScreenResult
import app.hakusan.sdk.ScreenChapterId
import app.hakusan.sdk.ScreenChapterKey
import app.hakusan.sdk.ScreenContentUnitKind
import app.hakusan.sdk.ScreenReadingPosition
import app.hakusan.sdk.ScreenReadingStart
import app.hakusan.sdk.ScreenSourceId
import app.hakusan.sdk.ScreenTitleId
import app.hakusan.sdk.ScreenTitleKey
import app.hakusan.sdk.TitleDetailsScreen
import app.hakusan.titles.Chapter
import app.hakusan.titles.ContentUnitKind
import app.hakusan.titles.ContinueResolution
import app.hakusan.titles.ProgressSnapshot
import app.hakusan.titles.SourceTitleAlias
import app.hakusan.titles.TitleId
import app.hakusan.titles.chapter
import app.hakusan.titles.chapterId
import app.hakusan.titles.progressSnapshot
import app.hakusan.titles.resolveContinue
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TitleDetailsScreenAdapterTest {
  // The title key ends in U+0065 U+0301, which stays decomposed.
  private val decomposedCafe = "Cafe" + Char(0x0301)

  private val source = SourceIdentity(" Source ")

  private val title = SourceTitleKey(source, decomposedCafe)

  private val alias = SourceTitleAlias.of(" Source ", decomposedCafe)

  private val screenKey =
    ScreenTitleKey(ScreenSourceId(" Source "), decomposedCafe)

  // The canonical order differs from every order of the keys and the labels.
  private val chapters =
    listOf(
      chapter(chapterId(10), "c10", "Chapter 10"),
      chapter(chapterId(1), " C1 ", "Chapter 1"),
      chapter(chapterId(2), "c1", "Chapter 2"),
    )

  @Test
  fun `details keep the canonical order, read flags, and exact keys`() {
    val progress =
      progressSnapshot(
        titleId = TITLE_ID,
        alias = alias,
        chapters = chapters,
        readChapters = setOf(chapters[0], chapters[2]),
      )

    val screen = successScreen(progress)

    val items =
      listOf(
        DetailsChapterItem(
          id = screenChapterId(chapters[0]),
          titleId = SCREEN_TITLE_ID,
          key = ScreenChapterKey(screenKey, "c10"),
          displayName = "Chapter 10",
          isRead = true,
        ),
        DetailsChapterItem(
          id = screenChapterId(chapters[1]),
          titleId = SCREEN_TITLE_ID,
          key = ScreenChapterKey(screenKey, " C1 "),
          displayName = "Chapter 1",
          isRead = false,
        ),
        DetailsChapterItem(
          id = screenChapterId(chapters[2]),
          titleId = SCREEN_TITLE_ID,
          key = ScreenChapterKey(screenKey, "c1"),
          displayName = "Chapter 2",
          isRead = true,
        ),
      )

    val expected =
      TitleDetailsScreen.of(
        id = SCREEN_TITLE_ID,
        key = screenKey,
        sourceDisplayName = "Captured",
        displayName = " Title ",
        description = " About ",
        chapters = items,
        continueState = ContinueState.Ready(beginningTarget(chapters[1])),
      )

    assertEquals(expected, screen)
  }

  @Test
  fun `the source name is the one that the registry captured`() {
    val names = ArrayDeque(listOf("Captured", "Changed"))

    val progress =
      progressSnapshot(titleId = TITLE_ID, alias = alias, chapters = chapters)

    val screen = successScreen(progress) { names.removeFirst() }

    assertEquals("Captured", screen.sourceDisplayName)
    assertEquals(listOf("Changed"), names.toList())
  }

  @Test
  fun `each Continue case becomes the state that Continue selects`() {
    val omitted = chapter(chapterId(3), "gone", "Gone")

    val position =
      ScreenReadingPosition(
        titleId = SCREEN_TITLE_ID,
        chapterId = screenChapterId(chapters[1]),
        unitKind = ScreenContentUnitKind.PAGE,
        unitIndex = 7,
      )

    val resumed =
      ContinueTarget(
        titleId = SCREEN_TITLE_ID,
        chapterId = screenChapterId(chapters[1]),
        chapterKey = ScreenChapterKey(screenKey, " C1 "),
        start = ScreenReadingStart.Resume(position),
      )

    val savedPosition =
      ScreenReadingPosition(
        titleId = SCREEN_TITLE_ID,
        chapterId = screenChapterId(omitted),
        unitKind = ScreenContentUnitKind.PROVIDER_SEGMENT,
        unitIndex = 2,
      )

    val saved =
      ContinueUnavailableReason.SavedTargetUnavailable(
        chapterKey = ScreenChapterKey(screenKey, "gone"),
        position = savedPosition,
      )

    val cases =
      listOf(
        progressSnapshot(
          titleId = TITLE_ID,
          alias = alias,
          chapters = chapters,
          readChapters = setOf(chapters[0]),
          resumeChapter = chapters[1],
          unitKind = ContentUnitKind.PAGE,
          unitIndex = 7,
        ) to ContinueState.Ready(resumed),
        progressSnapshot(
          titleId = TITLE_ID,
          alias = alias,
          chapters = chapters,
          resumeChapter = omitted,
          unitKind = ContentUnitKind.PROVIDER_SEGMENT,
          unitIndex = 2,
        ) to ContinueState.Unavailable(saved),
        progressSnapshot(
          titleId = TITLE_ID,
          alias = alias,
          chapters = chapters,
          readChapters = setOf(chapters[0], chapters[2]),
        ) to ContinueState.Ready(beginningTarget(chapters[1])),
        progressSnapshot(
          titleId = TITLE_ID,
          alias = alias,
          chapters = chapters,
          readChapters = chapters.toSet(),
        ) to ContinueState.Ready(beginningTarget(chapters[2])),
        progressSnapshot(
          titleId = TITLE_ID,
          alias = alias,
          chapters = emptyList(),
        ) to
          ContinueState.Unavailable(
            ContinueUnavailableReason.NoAvailableChapter
          ),
      )

    val resolutions = cases.map { (progress, _) ->
      progress.resolveContinue()::class
    }

    assertEquals(
      listOf(
        ContinueResolution.Resume::class,
        ContinueResolution.SavedTargetUnavailable::class,
        ContinueResolution.FirstUnread::class,
        ContinueResolution.AllRead::class,
        ContinueResolution.NoAvailableChapter::class,
      ),
      resolutions,
    )

    for ((progress, state) in cases) {
      val selection = runBlocking {
        ContinueSelectionAdapter(FakeTitles(onReadProgress = { progress }))
          .selectContinue(SCREEN_TITLE_ID)
      }

      assertEquals(state, successScreen(progress).continueState)
      assertEquals(selectionOf(state), selection)
    }
  }

  @Test
  fun `each load failure becomes the same screen failure`() {
    // An unscripted refresh fails the test.
    val unavailableDetails =
      ScriptedBackend(
        identity = source,
        onDetails = { SourceResult.Failure(SourceFailure.Unavailable) },
      )

    val foreignDetails =
      ScriptedBackend(
        identity = source,
        onDetails = { details(SourceTitleKey(source, "other")) },
      )

    val unavailableChapters =
      ScriptedBackend(
        identity = source,
        onDetails = { key -> details(key) },
        onRefreshChapters = { request ->
          ChapterRefreshCompletion.failed(request, SourceFailure.Unavailable)
        },
      )

    val partialChapters =
      ScriptedBackend(
        identity = source,
        onDetails = { key -> details(key) },
        onRefreshChapters = { request ->
          ChapterRefreshCompletion.completed(
            request,
            ChapterSequenceStatus.PARTIAL,
            emptyList(),
          )
        },
      )

    val cases =
      listOf(
        unavailableDetails to DetailsScreenFailure.DetailsUnavailable,
        foreignDetails to DetailsScreenFailure.InvalidTitleObservation,
        unavailableChapters to DetailsScreenFailure.ChaptersUnavailable,
        partialChapters to DetailsScreenFailure.InvalidChapterSnapshot,
      )

    for ((backend, failure) in cases) {
      // FakeTitles fails the test on any call.
      val result = runBlocking {
        adapter(FakeTitles(), backend).loadDetails(screenKey)
      }

      assertEquals(DetailsScreenResult.Failure(failure), result)
    }

    val unregistered = ScreenTitleKey(ScreenSourceId("other"), decomposedCafe)

    val missing = runBlocking {
      adapter(FakeTitles(), ScriptedBackend(source)).loadDetails(unregistered)
    }

    assertEquals(
      DetailsScreenResult.Failure(DetailsScreenFailure.SourceNotFound),
      missing,
    )
  }

  @Test
  fun `a superseded load stays arbitration`() {
    val olderDetails =
      CompletableDeferred<
        SourceResult<SourceTitleDetails, SourceDetailsFailure>
      >()

    var detailsCalls = 0

    val backend =
      ScriptedBackend(
        identity = source,
        onDetails = {
          detailsCalls += 1

          if (detailsCalls == 1) {
            olderDetails.await()
          } else {
            SourceResult.Failure(SourceFailure.Unavailable)
          }
        },
      )

    // FakeTitles fails the test on any call.
    val adapter = adapter(FakeTitles(), backend)

    runBlocking {
      withTimeout(5.seconds) {
        val older =
          async(start = CoroutineStart.UNDISPATCHED) {
            adapter.loadDetails(screenKey)
          }

        assertEquals(
          DetailsScreenResult.Failure(DetailsScreenFailure.DetailsUnavailable),
          adapter.loadDetails(screenKey),
        )

        olderDetails.complete(details(title))
        assertSame(DetailsScreenResult.RejectedNotCurrent, older.await())
      }
    }
  }

  @Test
  fun `cancellation and defects propagate unchanged`() {
    for (thrown in
      listOf(CancellationException("Cancelled"), IllegalStateException())) {
      val backend =
        ScriptedBackend(identity = source, onDetails = { throw thrown })

      val propagated =
        assertThrows(Throwable::class.java) {
          runBlocking { adapter(FakeTitles(), backend).loadDetails(screenKey) }
        }

      assertSame(thrown, propagated)
    }
  }

  /**
   * Loads the details of [title] from a source whose name reads [displayName]
   * and whose chapters are those of [progress], which the reconciliation
   * returns.
   */
  private fun successScreen(
    progress: ProgressSnapshot,
    displayName: () -> String = { "Captured" },
  ): TitleDetailsScreen {
    val backend =
      ScriptedBackend(
        identity = source,
        onDisplayName = displayName,
        onDetails = { key -> details(key) },
        onRefreshChapters = { request -> completed(request, progress) },
      )

    val titles = FakeTitles(onReconcileTitle = { progress })

    val result = runBlocking {
      adapter(titles, backend).loadDetails(screenKey)
    }

    return assertInstanceOf(DetailsScreenResult.Success::class.java, result)
      .screen
  }

  private fun adapter(
    titles: FakeTitles,
    backend: ScriptedBackend,
  ): TitleDetailsScreenAdapter {
    val registry = SourceRegistry.of(listOf(backend))

    return TitleDetailsScreenAdapter(WholeTitleLoad(registry, titles))
  }

  private fun beginningTarget(chapter: Chapter): ContinueTarget =
    ContinueTarget(
      titleId = SCREEN_TITLE_ID,
      chapterId = screenChapterId(chapter),
      chapterKey = ScreenChapterKey(screenKey, chapter.localKey.text),
      start = ScreenReadingStart.Beginning,
    )
}

private fun details(
  title: SourceTitleKey
): SourceResult<SourceTitleDetails, SourceDetailsFailure> =
  SourceResult.Success(
    SourceTitleDetails(SourceTitle(title, " Title "), " About ")
  )

/** Completes [request] with the canonical chapters of [progress]. */
private fun completed(
  request: ChapterRefreshRequest,
  progress: ProgressSnapshot,
): ChapterRefreshCompletion {
  val chapters =
    progress.chapters.map { state ->
      val key = SourceChapterKey(request.title, state.chapter.localKey.text)

      SourceChapter(key, state.chapter.displayName)
    }

  return ChapterRefreshCompletion.completed(
    request,
    ChapterSequenceStatus.COMPLETE,
    chapters,
  )
}

/** Returns the Continue selection that corresponds to [state]. */
private fun selectionOf(state: ContinueState): ContinueSelectionResult =
  when (state) {
    is ContinueState.Ready -> ContinueSelectionResult.Selected(state.target)
    is ContinueState.Unavailable ->
      ContinueSelectionResult.Unavailable(state.reason)
  }

/** Returns the screen identity of [chapter], with the same UUID. */
private fun screenChapterId(chapter: Chapter): ScreenChapterId =
  ScreenChapterId(chapter.id.uuid.toJavaUuid())

/** The UUIDv7 of the title, with a zero timestamp and zero random bits. */
private val TITLE_UUID = Uuid.fromLongs(0x7000L, Long.MIN_VALUE)

private val TITLE_ID = checkNotNull(TitleId.ofOrNull(TITLE_UUID))

private val SCREEN_TITLE_ID = ScreenTitleId(TITLE_UUID.toJavaUuid())
