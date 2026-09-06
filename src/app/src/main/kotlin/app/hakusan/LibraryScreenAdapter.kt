package app.hakusan

import app.hakusan.sdk.AddToLibraryScreenResult
import app.hakusan.sdk.LibraryScreen
import app.hakusan.sdk.LibraryScreenService
import app.hakusan.sdk.ScreenTitleId
import app.hakusan.titles.LibraryAddResult
import app.hakusan.titles.TitleId
import app.hakusan.titles.Titles
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.util.Collections
import java.util.LinkedHashSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Adapts title persistence to the screen-facing Library contract. */
@Inject
@SingleIn(AppScope::class)
internal class LibraryScreenAdapter(
  private val titles: Titles,
) : LibraryScreenService {
  override fun observeLibrary(): Flow<LibraryScreen> =
    titles.observeLibrary()
      .map { state -> state.toLibraryScreen() }

  override fun observeLibraryTitleIds(): Flow<Set<ScreenTitleId>> =
    titles.observeLibraryTitleIds()
      .map(::toOwnedScreenTitleIds)

  override suspend fun addToLibrary(
    titleId: ScreenTitleId,
  ): AddToLibraryScreenResult = when (
    val result = titles.addToLibrary(TitleId(titleId.value))
  ) {
    is LibraryAddResult.Success -> AddToLibraryScreenResult.Success
    is LibraryAddResult.CategorySelectionRequired ->
      AddToLibraryScreenResult.CategorySelectionRequired

    LibraryAddResult.TitleNotFound -> AddToLibraryScreenResult.TitleNotFound
  }

  private fun toOwnedScreenTitleIds(
    titleIds: Set<TitleId>,
  ): Set<ScreenTitleId> {
    val result = LinkedHashSet<ScreenTitleId>(titleIds.size)
    titleIds.forEach { titleId ->
      result += ScreenTitleId(titleId.value)
    }
    return Collections.unmodifiableSet(result)
  }
}
