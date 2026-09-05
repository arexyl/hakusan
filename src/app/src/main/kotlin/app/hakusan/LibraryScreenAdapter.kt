package app.hakusan

import app.hakusan.sdk.LibraryScreen
import app.hakusan.sdk.LibraryScreenService
import app.hakusan.titles.LibraryShelfState
import app.hakusan.titles.Titles
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

@Inject
@SingleIn(AppScope::class)
internal class LibraryScreenAdapter(
  private val titles: Titles,
) : LibraryScreenService {
  override fun observeLibrary(): Flow<LibraryScreen> = channelFlow {
    titles.observeLibraryShelves().collectLatest { state ->
      state.observeProgress().collect { screen ->
        send(screen)
      }
    }
  }.distinctUntilChanged()

  private fun LibraryShelfState.observeProgress(): Flow<LibraryScreen> {
    val orderedTitles = LibraryOrder.titles(titlesById.values)
    if (orderedTitles.isEmpty()) {
      return flowOf(toLibraryScreen(emptyMap()))
    }
    val observations = orderedTitles.map { title ->
      titles.observeReadingProgress(title.id).map { progress ->
        title.id to checkNotNull(progress) {
          "A Library title must retain readable title progress."
        }
      }
    }
    return combine(observations) { values ->
      toLibraryScreen(values.toMap())
    }
  }
}
