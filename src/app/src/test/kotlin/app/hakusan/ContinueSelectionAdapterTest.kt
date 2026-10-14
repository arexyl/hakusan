package app.hakusan

import app.hakusan.sdk.ContinueSelectionResult
import app.hakusan.sdk.ContinueTarget
import app.hakusan.sdk.ContinueUnavailableReason
import app.hakusan.sdk.ScreenChapterId
import app.hakusan.sdk.ScreenChapterKey
import app.hakusan.sdk.ScreenContentUnitKind
import app.hakusan.sdk.ScreenReadingPosition
import app.hakusan.sdk.ScreenReadingStart
import app.hakusan.sdk.ScreenSourceId
import app.hakusan.sdk.ScreenTitleId
import app.hakusan.sdk.ScreenTitleKey
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
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ContinueSelectionAdapterTest {
  private val alias = SourceTitleAlias.of("source", "title")

  private val titleKey = ScreenTitleKey(ScreenSourceId("source"), "title")

  // The canonical order differs from every order of the keys and the labels.
  private val chapters =
    listOf(
      chapter(chapterId(10), "c10", "Chapter 10"),
      chapter(chapterId(1), "c1", "Chapter 1"),
      chapter(chapterId(2), "c2", "Chapter 2"),
    )

  @Test
  fun `a valid saved position resumes at its unit`() {
    val resumed = chapters[1]

    val snapshot =
      progressSnapshot(
        titleId = TITLE_ID,
        alias = alias,
        chapters = chapters,
        readChapters = setOf(chapters[0]),
        resumeChapter = resumed,
        unitKind = ContentUnitKind.PROVIDER_SEGMENT,
        unitIndex = 12,
      )

    assertInstanceOf(
      ContinueResolution.Resume::class.java,
      snapshot.resolveContinue(),
    )

    val position =
      ScreenReadingPosition(
        titleId = SCREEN_TITLE_ID,
        chapterId = screenChapterId(resumed),
        unitKind = ScreenContentUnitKind.PROVIDER_SEGMENT,
        unitIndex = 12,
      )

    val expected =
      ContinueTarget(
        titleId = SCREEN_TITLE_ID,
        chapterId = screenChapterId(resumed),
        chapterKey = ScreenChapterKey(titleKey, "c1"),
        start = ScreenReadingStart.Resume(position),
      )

    assertEquals(ContinueSelectionResult.Selected(expected), select(snapshot))
  }

  @Test
  fun `a saved position in an omitted chapter stays unavailable`() {
    val omitted = chapter(chapterId(3), "gone", "Gone")

    val snapshot =
      progressSnapshot(
        titleId = TITLE_ID,
        alias = alias,
        chapters = chapters,
        resumeChapter = omitted,
        unitKind = ContentUnitKind.PAGE,
        unitIndex = 4,
      )

    assertInstanceOf(
      ContinueResolution.SavedTargetUnavailable::class.java,
      snapshot.resolveContinue(),
    )

    val position =
      ScreenReadingPosition(
        titleId = SCREEN_TITLE_ID,
        chapterId = screenChapterId(omitted),
        unitKind = ScreenContentUnitKind.PAGE,
        unitIndex = 4,
      )

    val reason =
      ContinueUnavailableReason.SavedTargetUnavailable(
        chapterKey = ScreenChapterKey(titleKey, "gone"),
        position = position,
      )

    assertEquals(ContinueSelectionResult.Unavailable(reason), select(snapshot))
  }

  @Test
  fun `without a position the first unread chapter opens at its beginning`() {
    val snapshot =
      progressSnapshot(
        titleId = TITLE_ID,
        alias = alias,
        chapters = chapters,
        readChapters = setOf(chapters[0], chapters[2]),
      )

    val resolution =
      assertInstanceOf(
        ContinueResolution.FirstUnread::class.java,
        snapshot.resolveContinue(),
      )

    assertEquals(chapters[1], resolution.chapter)

    assertEquals(
      ContinueSelectionResult.Selected(beginningTarget(chapters[1])),
      select(snapshot),
    )
  }

  @Test
  fun `when every chapter is read the final chapter opens at its beginning`() {
    val snapshot =
      progressSnapshot(
        titleId = TITLE_ID,
        alias = alias,
        chapters = chapters,
        readChapters = chapters.toSet(),
      )

    val resolution =
      assertInstanceOf(
        ContinueResolution.AllRead::class.java,
        snapshot.resolveContinue(),
      )

    assertEquals(chapters.last(), resolution.chapter)

    assertEquals(
      ContinueSelectionResult.Selected(beginningTarget(chapters.last())),
      select(snapshot),
    )
  }

  @Test
  fun `without chapters no chapter is available`() {
    val snapshot =
      progressSnapshot(
        titleId = TITLE_ID,
        alias = alias,
        chapters = emptyList(),
      )

    assertSame(
      ContinueResolution.NoAvailableChapter,
      snapshot.resolveContinue(),
    )

    assertEquals(
      ContinueSelectionResult.Unavailable(
        ContinueUnavailableReason.NoAvailableChapter
      ),
      select(snapshot),
    )
  }

  @Test
  fun `each unit kind maps to its screen kind`() {
    val expected =
      mapOf(
        ContentUnitKind.PAGE to ScreenContentUnitKind.PAGE,
        ContentUnitKind.PROVIDER_SEGMENT to
          ScreenContentUnitKind.PROVIDER_SEGMENT,
      )

    assertEquals(ContentUnitKind.entries.toSet(), expected.keys)

    for ((kind, screenKind) in expected) {
      val snapshot =
        progressSnapshot(
          titleId = TITLE_ID,
          alias = alias,
          chapters = chapters,
          resumeChapter = chapters[0],
          unitKind = kind,
          unitIndex = 0,
        )

      val selected =
        assertInstanceOf(
          ContinueSelectionResult.Selected::class.java,
          select(snapshot),
        )

      val start =
        assertInstanceOf(
          ScreenReadingStart.Resume::class.java,
          selected.target.start,
        )

      assertEquals(screenKind, start.position.unitKind)
    }
  }

  @Test
  fun `keys keep the exact text of the alias and the chapter`() {
    // The title key ends in U+0065 U+0301, which stays decomposed.
    val decomposedCafe = "Cafe" + Char(0x0301)

    val exact = SourceTitleAlias.of(" Source ", decomposedCafe)

    val available = chapter(chapterId(1), " Chapter A ", "Chapter A")

    val omitted = chapter(chapterId(2), "chapter a", "Chapter a")

    val selected =
      progressSnapshot(
        titleId = TITLE_ID,
        alias = exact,
        chapters = listOf(available),
      )

    val unavailable =
      progressSnapshot(
        titleId = TITLE_ID,
        alias = exact,
        chapters = listOf(available),
        resumeChapter = omitted,
        unitKind = ContentUnitKind.PAGE,
        unitIndex = 0,
      )

    val exactTitleKey =
      ScreenTitleKey(ScreenSourceId(" Source "), decomposedCafe)

    val selectedResult =
      assertInstanceOf(
        ContinueSelectionResult.Selected::class.java,
        select(selected),
      )

    val unavailableResult =
      assertInstanceOf(
        ContinueSelectionResult.Unavailable::class.java,
        select(unavailable),
      )

    val saved =
      assertInstanceOf(
        ContinueUnavailableReason.SavedTargetUnavailable::class.java,
        unavailableResult.reason,
      )

    assertEquals(
      ScreenChapterKey(exactTitleKey, " Chapter A "),
      selectedResult.target.chapterKey,
    )

    assertEquals(ScreenChapterKey(exactTitleKey, "chapter a"), saved.chapterKey)
  }

  @Test
  fun `an unknown title is not found`() {
    val requested = mutableListOf<TitleId>()

    val titles =
      FakeTitles(
        onReadProgress = { id ->
          requested += id
          null
        }
      )

    val result = runBlocking {
      ContinueSelectionAdapter(titles).selectContinue(SCREEN_TITLE_ID)
    }

    assertSame(ContinueSelectionResult.TitleNotFound, result)
    assertEquals(listOf(TITLE_ID), requested)
  }

  @Test
  fun `each invocation reads the current progress again`() {
    val unread =
      progressSnapshot(titleId = TITLE_ID, alias = alias, chapters = chapters)

    val firstRead =
      progressSnapshot(
        titleId = TITLE_ID,
        alias = alias,
        chapters = chapters,
        readChapters = setOf(chapters[0]),
      )

    val snapshots = ArrayDeque(listOf(unread, firstRead))

    val titles = FakeTitles(onReadProgress = { snapshots.removeFirst() })

    val adapter = ContinueSelectionAdapter(titles)

    val results = runBlocking {
      listOf(
        adapter.selectContinue(SCREEN_TITLE_ID),
        adapter.selectContinue(SCREEN_TITLE_ID),
      )
    }

    val expected =
      listOf(
        ContinueSelectionResult.Selected(beginningTarget(chapters[0])),
        ContinueSelectionResult.Selected(beginningTarget(chapters[1])),
      )

    assertEquals(expected, results)
    assertEquals(0, snapshots.size)
  }

  @Test
  fun `cancellation and defects propagate unchanged`() {
    for (thrown in
      listOf(CancellationException("Cancelled"), IllegalStateException())) {
      val titles = FakeTitles(onReadProgress = { throw thrown })

      val propagated =
        assertThrows(Throwable::class.java) {
          runBlocking {
            ContinueSelectionAdapter(titles).selectContinue(SCREEN_TITLE_ID)
          }
        }

      assertSame(thrown, propagated)
    }
  }

  private fun beginningTarget(chapter: Chapter): ContinueTarget =
    ContinueTarget(
      titleId = SCREEN_TITLE_ID,
      chapterId = screenChapterId(chapter),
      chapterKey = ScreenChapterKey(titleKey, chapter.localKey.text),
      start = ScreenReadingStart.Beginning,
    )

  private fun select(snapshot: ProgressSnapshot): ContinueSelectionResult {
    val titles = FakeTitles(onReadProgress = { snapshot })

    return runBlocking {
      ContinueSelectionAdapter(titles).selectContinue(SCREEN_TITLE_ID)
    }
  }
}

/** Returns the screen identity of [chapter], with the same UUID. */
private fun screenChapterId(chapter: Chapter): ScreenChapterId =
  ScreenChapterId(chapter.id.uuid.toJavaUuid())

/** The UUIDv7 of the title, with a zero timestamp and zero random bits. */
private val TITLE_UUID = Uuid.fromLongs(0x7000L, Long.MIN_VALUE)

private val TITLE_ID = checkNotNull(TitleId.ofOrNull(TITLE_UUID))

private val SCREEN_TITLE_ID = ScreenTitleId(TITLE_UUID.toJavaUuid())
