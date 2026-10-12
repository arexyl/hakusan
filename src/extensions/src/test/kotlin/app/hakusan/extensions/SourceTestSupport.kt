package app.hakusan.extensions

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.jupiter.api.Assertions.fail

/** Blank texts, including one with a non-ASCII space (U+2003). */
internal val blankTexts: List<String> =
  listOf("", " ", "\t\n", Char(0x2003).toString())

internal fun <Value> SourceResult<Value, *>.successValue(): Value =
  when (this) {
    is SourceResult.Success -> value
    is SourceResult.Failure -> fail("Expected success, got $error.")
  }

internal fun <Error : SourceFailure> SourceResult<*, Error>.failureValue():
  Error =
  when (this) {
    is SourceResult.Success -> fail("Expected failure, got $value.")
    is SourceResult.Failure -> error
  }

/**
 * Runs [block] on the calling thread and returns its outcome.
 *
 * The test backends never suspend, so the block completes before
 * `startCoroutine` returns. A block that suspends fails the test.
 */
internal fun <Value> completeWithoutSuspension(
  block: suspend () -> Value
): Value {
  var outcome: Result<Value>? = null

  block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })

  val completed = outcome ?: fail("The block suspended.")

  return completed.getOrThrow()
}
