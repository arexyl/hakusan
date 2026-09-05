package app.hakusan

import app.hakusan.sdk.LibraryScreen
import app.hakusan.sdk.LibraryScreenService
import app.hakusan.titles.Titles
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Inject
@SingleIn(ApplicationScope::class)
internal class ApplicationLibraryScreenService(
  private val titles: Titles,
) : LibraryScreenService {
  override fun observeLibrary(): Flow<LibraryScreen> =
    titles.observeLibrarySummary()
      .map { state -> state.toLibraryScreen() }
}
