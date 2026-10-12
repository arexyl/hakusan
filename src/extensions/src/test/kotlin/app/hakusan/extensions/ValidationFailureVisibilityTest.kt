package app.hakusan.extensions

import kotlin.reflect.KClass
import kotlin.reflect.KVisibility
import kotlin.reflect.full.memberFunctions
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Checks that code outside this module cannot create a validation failure.
 *
 * Java reflection reports Kotlin internal constructors as public, so the test
 * reads Kotlin visibility from the class metadata instead.
 */
class ValidationFailureVisibilityTest {
  @Test
  fun `the walk reaches every known validation failure`() {
    val names = validationFailures().map { it.simpleName }

    assertTrue(names.containsAll(KNOWN_VALIDATION_FAILURES)) {
      "Found only $names."
    }
  }

  @Test
  fun `no validation failure has a public constructor or copy`() {
    for (failure in validationFailures()) {
      val constructors = failure.constructors

      val copies = failure.memberFunctions.filter { it.name == "copy" }

      for (constructor in constructors) {
        assertTrue(constructor.visibility in HIDDEN) {
          "${failure.simpleName} has a ${constructor.visibility} constructor."
        }
      }

      for (copy in copies) {
        assertTrue(copy.visibility in HIDDEN) {
          "${failure.simpleName} has a ${copy.visibility} copy()."
        }
      }
    }
  }
}

private val KNOWN_VALIDATION_FAILURES =
  listOf(
    "InvalidBrowseResult",
    "InvalidChapterContent",
    "InvalidChapterSnapshot",
    "InvalidTitleDetails",
  )

private val HIDDEN = setOf(KVisibility.INTERNAL, KVisibility.PRIVATE)

/** Every concrete failure whose name marks it as a validation failure. */
private fun validationFailures(): List<KClass<*>> =
  concreteSubtypes(SourceFailure::class).filter {
    it.simpleName.orEmpty().startsWith("Invalid")
  }

/** The concrete types below [type], following sealed interfaces. */
private fun concreteSubtypes(type: KClass<*>): Set<KClass<*>> =
  if (type.isSealed) {
    type.sealedSubclasses.flatMapTo(mutableSetOf(), ::concreteSubtypes)
  } else {
    setOf(type)
  }
