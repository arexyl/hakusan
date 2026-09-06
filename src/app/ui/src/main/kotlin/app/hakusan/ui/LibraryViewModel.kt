package app.hakusan.ui

import app.hakusan.sdk.LibraryScreen
import app.hakusan.sdk.LibraryScreenService
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class LibraryViewModel(
  private val libraryService: LibraryScreenService,
) : ViewModel() {
  internal var libraryState: LibraryLoadState by mutableStateOf(
    LibraryLoadState.Loading,
  )
    private set

  init {
    viewModelScope.launch {
      libraryService.observeLibrary().collect { screen ->
        libraryState = LibraryLoadState.Loaded(screen)
      }
    }
  }

  companion object {
    fun factory(
      libraryService: () -> LibraryScreenService,
    ): ViewModelProvider.Factory = viewModelFactory {
      initializer {
        LibraryViewModel(
          libraryService = libraryService(),
        )
      }
    }
  }
}

internal sealed interface LibraryLoadState {
  data object Loading : LibraryLoadState

  data class Loaded(
    val screen: LibraryScreen,
  ) : LibraryLoadState
}
