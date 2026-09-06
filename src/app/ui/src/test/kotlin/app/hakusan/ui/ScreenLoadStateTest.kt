package app.hakusan.ui

import app.hakusan.sdk.BrowseScreenResult
import app.hakusan.sdk.BrowseScreenService
import app.hakusan.sdk.CatalogScreen
import app.hakusan.sdk.ContinueSelectionResult
import app.hakusan.sdk.ContinueSelectionService
import app.hakusan.sdk.ContinueTarget
import app.hakusan.sdk.ContinueUnavailableReason
import app.hakusan.sdk.DetailsScreenResult
import app.hakusan.sdk.ScreenChapterId
import app.hakusan.sdk.ScreenChapterKey
import app.hakusan.sdk.ScreenReadingStart
import app.hakusan.sdk.ScreenSourceId
import app.hakusan.sdk.ScreenTitleId
import app.hakusan.sdk.ScreenTitleKey
import app.hakusan.sdk.TitleDetailsScreenService
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScreenLoadStateTest {
  @Test
  fun `discarding details in one destination keeps the other owner`() {
    val model = BrowsingViewModel(
      browseService = EmptyBrowseService,
      detailsService = UnusedDetailsService,
      continueService = UnusedContinueService,
    )
    val route = TitleDetailsRoute("source", "title")
    val libraryKey = DetailsOwnerKey(PrimaryDestination.LIBRARY, route)
    val catalogKey = DetailsOwnerKey(PrimaryDestination.CATALOG, route)
    val libraryOwner = model.details(libraryKey)
    val catalogOwner = model.details(catalogKey)

    assertNotSame(libraryOwner, catalogOwner)

    model.discard(PrimaryDestination.LIBRARY, route)

    assertNotSame(libraryOwner, model.details(libraryKey))
    assertSame(catalogOwner, model.details(catalogKey))
  }

  @Test
  fun `continue selection retains the exact current target`() {
    val owner = ContinueActionOwner()
    val target = continueTarget()
    val revision = owner.startSelection()

    assertSame(ContinueActionState.Selecting, owner.state)
    assertTrue(owner.publishSelected(revision, target))
    val selected = owner.state as ContinueActionState.Selected
    assertSame(target, selected.target)
    assertFalse(
      owner.publishUnavailable(
        revision,
        ContinueUnavailableReason.NoAvailableChapter,
      ),
    )
  }

  @Test
  fun `clearing Continue invalidates a late completion`() {
    val owner = ContinueActionOwner()
    val revision = owner.startSelection()

    owner.clear()

    assertSame(ContinueActionState.Idle, owner.state)
    assertFalse(owner.publishTitleNotFound(revision))
    assertEquals(2L, owner.revision)
  }

  @Test
  fun `retry invalidates an older completion`() {
    val owner = ScreenLoadOwner<String, String>()
    val firstRevision = owner.revision

    owner.retry()
    val retryRevision = owner.revision

    assertFalse(owner.publishContent(firstRevision, "stale"))
    assertSame(ScreenLoadState.Loading, owner.state)
    assertTrue(owner.publishContent(retryRevision, "current"))
    assertFalse(owner.publishFailure(retryRevision, "duplicate"))
    assertEquals(ScreenLoadState.Loaded("current"), owner.state)
  }

  @Test
  fun `failure can begin a newer loading attempt`() {
    val owner = ScreenLoadOwner<String, String>()

    assertTrue(owner.publishFailure(owner.revision, "unavailable"))
    assertEquals(ScreenLoadState.Failed("unavailable"), owner.state)

    owner.retry()

    assertSame(ScreenLoadState.Loading, owner.state)
    assertEquals(1L, owner.revision)
  }

  @Test
  fun `superseded completion is retryable but not a failure`() {
    val owner = ScreenLoadOwner<String, String>()

    assertTrue(owner.publishSuperseded(owner.revision))
    assertSame(ScreenLoadState.Superseded, owner.state)

    owner.retry()

    assertSame(ScreenLoadState.Loading, owner.state)
  }

  private fun continueTarget(): ContinueTarget {
    val titleKey = ScreenTitleKey(
      sourceId = ScreenSourceId("source"),
      sourceTitleKey = "title",
    )
    return ContinueTarget(
      titleId = ScreenTitleId(
        UUID.fromString("00000000-0000-7000-8000-000000000001"),
      ),
      chapterId = ScreenChapterId(
        UUID.fromString("00000000-0000-7000-8000-000000000002"),
      ),
      chapterKey = ScreenChapterKey(
        titleKey = titleKey,
        sourceChapterKey = "chapter",
      ),
      start = ScreenReadingStart.Beginning,
    )
  }

  private object EmptyBrowseService : BrowseScreenService {
    override fun catalog(): CatalogScreen = CatalogScreen.of(emptyList())

    override suspend fun loadBrowse(
      sourceId: ScreenSourceId,
    ): BrowseScreenResult = error("The owner test does not load browse data.")
  }

  private object UnusedDetailsService :
    TitleDetailsScreenService {
    override suspend fun loadDetails(
      titleKey: ScreenTitleKey,
    ): DetailsScreenResult = error("The owner test does not load details.")
  }

  private object UnusedContinueService : ContinueSelectionService {
    override suspend fun selectContinue(
      titleId: ScreenTitleId,
    ): ContinueSelectionResult = error("The owner test does not continue.")
  }
}
