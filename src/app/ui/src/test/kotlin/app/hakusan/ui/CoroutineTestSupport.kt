package app.hakusan.ui

import kotlinx.coroutines.yield

internal suspend fun awaitCondition(condition: () -> Boolean) {
  while (!condition()) {
    yield()
  }
}
