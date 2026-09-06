package app.hakusan.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScreenLoadStateTest {
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
}
