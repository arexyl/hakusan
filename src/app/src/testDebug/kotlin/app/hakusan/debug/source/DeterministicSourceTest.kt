package app.hakusan.debug.source

import app.hakusan.extensions.ChapterRefreshGate
import app.hakusan.extensions.ChapterSequenceStatus
import app.hakusan.extensions.ChapterSnapshot
import app.hakusan.extensions.SourceBrowseResult
import app.hakusan.extensions.SourceChapterContent
import app.hakusan.extensions.SourceChapterKey
import app.hakusan.extensions.SourceContentUnitKind
import app.hakusan.extensions.SourceFailure
import app.hakusan.extensions.SourceIdentity
import app.hakusan.extensions.SourceResult
import app.hakusan.extensions.SourceTitleDetails
import app.hakusan.extensions.SourceTitleKey
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DeterministicSourceTest {
  private val fixture = DeterministicFixture
  private val titleKey = fixture.title.key
  private val unavailable = SourceResult.Failure(SourceFailure.Unavailable)

  @Test
  fun `the source has the Debug identity`() {
    val source = DeterministicSource(emptySet())

    assertEquals(SourceIdentity("app.hakusan.debug.source"), source.identity)
    assertEquals(fixture.identity, source.identity)
    assertEquals(fixture.displayName, source.displayName)
  }

  @Test
  fun `browse and details are stable across calls and instances`() {
    val sources =
      listOf(DeterministicSource(emptySet()), DeterministicSource(emptySet()))

    val browse =
      SourceBrowseResult.create(fixture.identity, listOf(fixture.title))

    val details =
      SourceResult.Success(
        SourceTitleDetails(fixture.title, fixture.description)
      )

    for (source in sources) {
      repeat(2) {
        assertEquals(browse, runBlocking { source.browse() })
        assertEquals(details, runBlocking { source.details(titleKey) })
      }
    }
  }

  @Test
  fun `the chapter refresh answers its request in canonical order`() {
    val source = DeterministicSource(emptySet())

    val request = ChapterRefreshGate(titleKey).issue()

    val completion = runBlocking { source.refreshChapters(request) }

    val snapshot =
      ChapterSnapshot.create(
        titleKey,
        ChapterSequenceStatus.COMPLETE,
        fixture.chapters.map { it.chapter },
      )

    assertSame(request, completion.request)
    assertEquals(snapshot, completion.result)
  }

  @Test
  fun `no sort of the chapter labels reconstructs the canonical order`() {
    val labels = fixture.chapters.map { it.chapter.displayName }

    val number = { label: String -> label.filter(Char::isDigit).toInt() }

    val sorts =
      listOf(
        labels.sorted(),
        labels.sortedDescending(),
        labels.sortedBy(number),
        labels.sortedByDescending(number),
      )

    assertEquals(3, labels.size)

    for (sorted in sorts) {
      assertNotEquals(labels, sorted)
    }
  }

  @Test
  fun `the content covers both granularities`() {
    val source = DeterministicSource(emptySet())

    for (fixtureChapter in fixture.chapters) {
      val key = fixtureChapter.chapter.key

      assertEquals(
        SourceChapterContent.create(key, fixtureChapter.units),
        runBlocking { source.content(key) },
      )
    }

    val kinds = fixture.chapters.flatMap { it.units }.map { it.kind }.toSet()

    assertEquals(SourceContentUnitKind.entries.toSet(), kinds)
  }

  @Test
  fun `each operation can be made unavailable on its own`() {
    for (operation in DeterministicOperation.entries) {
      val source = DeterministicSource(setOf(operation))

      val outcomes = outcomes(source)

      for ((other, outcome) in outcomes) {
        if (other == operation) {
          assertEquals(unavailable, outcome, other.name)
        } else {
          assertInstanceOf(
            SourceResult.Success::class.java,
            outcome,
            other.name,
          )
        }
      }
    }
  }

  @Test
  fun `an unknown key of this source is unavailable`() {
    val source = DeterministicSource(emptySet())

    val unknownTitle = SourceTitleKey(fixture.identity, "unknown-title")

    val unknownChapters =
      listOf(
        SourceChapterKey(unknownTitle, "opening"),
        SourceChapterKey(titleKey, "unknown-chapter"),
      )

    val request = ChapterRefreshGate(unknownTitle).issue()

    val completion = runBlocking { source.refreshChapters(request) }

    assertEquals(unavailable, runBlocking { source.details(unknownTitle) })
    assertSame(request, completion.request)
    assertEquals(unavailable, completion.result)

    for (chapter in unknownChapters) {
      assertEquals(unavailable, runBlocking { source.content(chapter) })
    }
  }

  @Test
  fun `a key of another source fails before availability`() {
    val source = DeterministicSource(DeterministicOperation.entries.toSet())

    val foreignTitle =
      SourceTitleKey(SourceIdentity("other-source"), titleKey.key)

    val foreignChapter =
      SourceChapterKey(foreignTitle, fixture.chapters.first().chapter.key.key)

    val foreignRequest = ChapterRefreshGate(foreignTitle).issue()

    assertThrows(IllegalArgumentException::class.java) {
      runBlocking { source.details(foreignTitle) }
    }

    assertThrows(IllegalArgumentException::class.java) {
      runBlocking { source.refreshChapters(foreignRequest) }
    }

    assertThrows(IllegalArgumentException::class.java) {
      runBlocking { source.content(foreignChapter) }
    }
  }

  private fun outcomes(
    source: DeterministicSource
  ): Map<DeterministicOperation, SourceResult<*, *>> {
    val request = ChapterRefreshGate(titleKey).issue()

    val chapter = fixture.chapters.first().chapter.key

    return runBlocking {
      mapOf(
        DeterministicOperation.BROWSE to source.browse(),
        DeterministicOperation.DETAILS to source.details(titleKey),
        DeterministicOperation.REFRESH_CHAPTERS to
          source.refreshChapters(request).result,
        DeterministicOperation.CONTENT to source.content(chapter),
      )
    }
  }
}
