package app.hakusan.bench

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

internal class ResidentTest {
  private val status =
    """
    Name:	hakusan.bench
    VmPeak:	19023456 kB
    VmHWM:	  262144 kB
    VmRSS:	  131072 kB
    RssAnon:	   65536 kB
    RssFile:	   61440 kB
    RssShmem:	    4096 kB
    VmSwap:	       4 kB
    Threads:	31
    """
      .trimIndent()

  @Test
  fun `a status gives its four values in bytes`() {
    assertEquals(
      Resident(
        rss = 131072L * 1024,
        peak = 262144L * 1024,
        anon = 65536L * 1024,
        swap = 4096,
      ),
      Resident.of(status),
    )
  }

  @Test
  fun `the order of the lines changes no value`() {
    val shuffled = status.lines().reversed().joinToString("\n")

    assertEquals(Resident.of(status), Resident.of(shuffled))
  }

  @Test
  fun `a status without a value is refused`() {
    for (name in listOf("VmRSS", "VmHWM", "RssAnon", "VmSwap")) {
      val without = status.lines().filterNot { it.startsWith("$name:") }

      assertThrows(IllegalArgumentException::class.java) {
        Resident.of(without.joinToString("\n"))
      }
    }
  }

  @Test
  fun `a value in another unit or without a number is refused`() {
    assertThrows(IllegalArgumentException::class.java) {
      Resident.of(status.replace("131072 kB", "131072 pages"))
    }

    assertThrows(IllegalArgumentException::class.java) {
      Resident.of(status.replace("131072 kB", "many kB"))
    }
  }
}
