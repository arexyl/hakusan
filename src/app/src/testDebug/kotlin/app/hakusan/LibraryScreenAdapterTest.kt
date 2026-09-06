package app.hakusan

import app.hakusan.sdk.ScreenTitleId
import app.hakusan.titles.TitleId
import app.hakusan.titles.Titles
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class LibraryScreenAdapterTest {
  @Test
  fun `membership snapshots retain exact owned identities`() = runBlocking {
    val first = TitleId(uuid(1))
    val second = TitleId(uuid(2))
    val mutableIds = linkedSetOf(first, second)
    val source = flow {
      emit(mutableIds)
      mutableIds.clear()
      mutableIds += second
      emit(mutableIds)
    }
    val adapter = LibraryScreenAdapter(titlesWithMemberships(source))

    val snapshots = adapter.observeLibraryTitleIds().take(2).toList()

    assertEquals(
      listOf(
        setOf(ScreenTitleId(first.value), ScreenTitleId(second.value)),
        setOf(ScreenTitleId(second.value)),
      ),
      snapshots,
    )
    assertThrows(UnsupportedOperationException::class.java) {
      (snapshots.first() as MutableSet).clear()
    }
  }

  @Suppress("UNCHECKED_CAST")
  private fun titlesWithMemberships(
    memberships: Flow<Set<TitleId>>,
  ): Titles = Proxy.newProxyInstance(
    Titles::class.java.classLoader,
    arrayOf(Titles::class.java),
  ) { _, method, _ ->
    when (method.name) {
      "observeLibraryTitleIds" -> memberships
      else -> error("Unexpected Titles call: ${method.name}")
    }
  } as Titles

  private fun uuid(value: Int): UUID = UUID.fromString(
    "00000000-0000-7000-8000-${value.toString().padStart(12, '0')}",
  )
}
