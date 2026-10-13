package app.hakusan.titles.storage

import app.hakusan.titles.PositionNotPersistedReason
import app.hakusan.titles.PositionNotPersistedReason.CHAPTER_ALREADY_READ
import app.hakusan.titles.PositionNotPersistedReason.REORDERED_EVENT
import app.hakusan.titles.PositionNotPersistedReason.TITLE_NOT_IN_LIBRARY
import app.hakusan.titles.ProgressEventRecency
import app.hakusan.titles.ProgressEventRecency.CURRENT
import app.hakusan.titles.ProgressEventRecency.REORDERED
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProgressPlansTest {
  @Test
  fun positionDecisionFollowsTheDecisionOrder() {
    val expected =
      listOf(
        Case(CURRENT, member = true, read = false) to PositionDecision.Record,
        Case(CURRENT, member = true, read = true) to skip(CHAPTER_ALREADY_READ),
        Case(CURRENT, member = false, read = false) to
          skip(TITLE_NOT_IN_LIBRARY),
        Case(CURRENT, member = false, read = true) to
          skip(CHAPTER_ALREADY_READ),
        Case(REORDERED, member = true, read = false) to skip(REORDERED_EVENT),
        Case(REORDERED, member = true, read = true) to skip(REORDERED_EVENT),
        Case(REORDERED, member = false, read = false) to skip(REORDERED_EVENT),
        Case(REORDERED, member = false, read = true) to skip(REORDERED_EVENT),
      )

    assertEquals(
      expected,
      expected.map { (case, _) ->
        case to decidePosition(case.recency, case.facts)
      },
    )
  }

  @Test
  fun adjacentBoundaryAlwaysCompletesAndDecidesTheStartedPosition() {
    val expected =
      listOf(
        Case(CURRENT, member = true, read = false) to
          BoundaryPlan.Apply(PositionDecision.Record),
        Case(CURRENT, member = true, read = true) to
          BoundaryPlan.Apply(skip(CHAPTER_ALREADY_READ)),
        Case(CURRENT, member = false, read = false) to
          BoundaryPlan.Apply(skip(TITLE_NOT_IN_LIBRARY)),
        Case(CURRENT, member = false, read = true) to
          BoundaryPlan.Apply(skip(CHAPTER_ALREADY_READ)),
        Case(REORDERED, member = true, read = false) to
          BoundaryPlan.Apply(skip(REORDERED_EVENT)),
        Case(REORDERED, member = true, read = true) to
          BoundaryPlan.Apply(skip(REORDERED_EVENT)),
        Case(REORDERED, member = false, read = false) to
          BoundaryPlan.Apply(skip(REORDERED_EVENT)),
        Case(REORDERED, member = false, read = true) to
          BoundaryPlan.Apply(skip(REORDERED_EVENT)),
      )

    assertEquals(
      expected,
      expected.map { (case, _) ->
        case to planBoundary(0, 1, case.recency, case.facts)
      },
    )

    assertEquals(
      expected.map { (_, plan) -> plan },
      expected.map { (case, _) ->
        planBoundary(6, 7, case.recency, case.facts)
      },
    )
  }

  @Test
  fun boundaryOutsideTheCanonicalAdjacencyChangesNothing() {
    val indexes =
      listOf(
        1 to 0,
        0 to 0,
        0 to 2,
        2 to 1,
        null to 0,
        0 to null,
        null to null,
        Int.MAX_VALUE to Int.MIN_VALUE,
      )

    val cases =
      ProgressEventRecency.entries.flatMap { recency ->
        listOf(true, false).flatMap { member ->
          listOf(true, false).map { read -> Case(recency, member, read) }
        }
      }

    val plans = indexes.flatMap { (completed, started) ->
      cases.map { case ->
        planBoundary(completed, started, case.recency, case.facts)
      }
    }

    assertEquals(64, plans.size)
    assertEquals(setOf(BoundaryPlan.AdjacencyChanged), plans.toSet())
  }

  private fun skip(reason: PositionNotPersistedReason): PositionDecision =
    PositionDecision.Skip(reason)

  /** One combination of the recency and the stored facts. */
  private data class Case(
    val recency: ProgressEventRecency,
    val member: Boolean,
    val read: Boolean,
  ) {
    val facts: PositionFacts
      get() = PositionFacts(isLibraryMember = member, isChapterRead = read)
  }
}
