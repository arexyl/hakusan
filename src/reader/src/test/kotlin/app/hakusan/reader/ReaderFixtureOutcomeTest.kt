package app.hakusan.reader

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrowsExactly
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Maps the expected outcomes of the reviewed fixture manifest onto reader-level
 * terminals. The native mapping replaces the table when the binding exists.
 */
class ReaderFixtureOutcomeTest {
  @Test
  fun `every manifest outcome maps onto a reader terminal`() {
    val outcomes = expectedOutcomes(Files.readString(manifestPath()))

    val unmapped = (outcomes.keys - TERMINALS.keys).sorted()

    val unused = (TERMINALS.keys - outcomes.keys).sorted()

    assertTrue(unmapped.isEmpty()) {
      unmapped.joinToString("\n", "Outcomes without a reader terminal:\n") {
        "$it, as in ${outcomes.getValue(it).sorted().first()}"
      }
    }

    assertTrue(unused.isEmpty()) {
      unused.joinToString(", ", "Outcomes that the manifest no longer uses: ")
    }

    val canceled =
      TERMINALS.filterValues { it == ReaderContentTerminal.Canceled }.keys

    assertTrue(canceled.isEmpty()) {
      canceled.sorted().joinToString(", ", "Outcomes mapped to cancellation: ")
    }
  }

  @Test
  fun `the table maps each outcome once`() {
    val repeated =
      OUTCOME_TABLE.groupingBy { it.first }.eachCount().filterValues { it > 1 }

    assertTrue(repeated.isEmpty()) {
      repeated.keys.sorted().joinToString(", ", "Outcomes mapped twice: ")
    }
  }

  @Test
  fun `the manifest reader rejects another schema and unlabeled artifacts`() {
    val artifact = """{"path": "a.png", "expected": "success"}"""

    assertEquals(
      mapOf("success" to listOf("a.png")),
      expectedOutcomes("""{"schema_version": 1, "artifacts": [$artifact]}"""),
    )

    val rejected =
      listOf(
        """{"schema_version": 2, "artifacts": [$artifact]}""",
        """{"schema_version": "1", "artifacts": [$artifact]}""",
        """{"artifacts": [$artifact]}""",
        """{"schema_version": 1}""",
        """{"schema_version": 1, "artifacts": [{"path": "a.png"}]}""",
        """{"schema_version": 1, "artifacts": [{"expected": "success"}]}""",
      )

    for (manifest in rejected) {
      assertThrowsExactly(IllegalArgumentException::class.java) {
        expectedOutcomes(manifest)
      }
    }
  }

  /** Returns the path of the reviewed manifest that Gradle passes. */
  private fun manifestPath(): Path {
    val path =
      System.getProperty(MANIFEST_PROPERTY)
        ?: error("Run the test through Gradle, which sets $MANIFEST_PROPERTY.")

    return Path.of(path)
  }

  /** Returns the artifact paths of each expected outcome in [manifest]. */
  private fun expectedOutcomes(manifest: String): Map<String, List<String>> {
    val root = Json.parseToJsonElement(manifest) as? JsonObject

    requireNotNull(root) { "The fixture manifest is not an object." }

    val version = root["schema_version"] as? JsonPrimitive

    require(version?.isString == false && version.intOrNull == 1) {
      "Unknown fixture manifest schema."
    }

    val artifacts = root["artifacts"] as? JsonArray

    requireNotNull(artifacts) { "The fixture manifest lists no artifacts." }

    return artifacts
      .map(::expectedOutcome)
      .groupBy(keySelector = { it.second }, valueTransform = { it.first })
  }

  /** Returns the path and the expected outcome of one manifest artifact. */
  private fun expectedOutcome(element: JsonElement): Pair<String, String> {
    val artifact = element as? JsonObject

    requireNotNull(artifact) { "A fixture artifact is not an object." }

    val path = artifact.text("path")

    requireNotNull(path) { "A fixture artifact has no path." }

    val outcome = artifact.text("expected")

    requireNotNull(outcome) { "The fixture $path has no expected outcome." }

    return path to outcome
  }

  private fun JsonObject.text(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

  private companion object {
    const val MANIFEST_PROPERTY = "app.hakusan.reader.fixtureManifest"

    /**
     * One row per manifest outcome, checked against the color, admission, and
     * integrity rules of the reader qualification profile.
     */
    val OUTCOME_TABLE: List<Pair<String, ReaderContentTerminal>> =
      listOf(
        "success" to ReaderContentTerminal.Completed.of(),
        "success-no-transform" to ReaderContentTerminal.Completed.of(),
        "color-convert" to ReaderContentTerminal.Completed.of(),
        "supported-color-profile" to ReaderContentTerminal.Completed.of(),
        "warned-srgb-fallback" to
          ReaderContentTerminal.Completed.of(
            listOf(ReaderContentWarning.SRGB_FALLBACK)
          ),
        "invalid-data" to failed(ReaderContentFailure.INVALID_CONTENT),
        "truncated" to failed(ReaderContentFailure.TRUNCATED_CONTENT),
        "header-admitted-not-complete" to
          failed(ReaderContentFailure.TRUNCATED_CONTENT),
        "budget-exceeded" to failed(ReaderContentFailure.BUDGET_EXCEEDED),
        "unsupported-variant" to
          failed(ReaderContentFailure.UNSUPPORTED_FORMAT),
        "unsupported-color-model" to
          failed(ReaderContentFailure.UNSUPPORTED_FORMAT),
        "unsupported-hdr" to failed(ReaderContentFailure.UNSUPPORTED_FORMAT),
      )

    val TERMINALS: Map<String, ReaderContentTerminal> = OUTCOME_TABLE.toMap()

    fun failed(failure: ReaderContentFailure): ReaderContentTerminal =
      ReaderContentTerminal.Failed(failure)
  }
}
