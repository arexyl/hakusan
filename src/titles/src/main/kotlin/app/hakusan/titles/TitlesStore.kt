package app.hakusan.titles

import android.content.Context
import app.hakusan.titles.storage.TitlesDatabase
import app.hakusan.titles.storage.asTitles
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver

/**
 * Process-owned access to Hakusan title persistence and its explicit release.
 */
interface TitlesStore : AutoCloseable {
  val titles: Titles

  /** Ends this store's useful lifetime and releases its database resources. */
  override fun close()
}

/** Opens the one file-backed title store owned by the application process. */
fun openTitlesStore(context: Context): TitlesStore {
  val database = Room.databaseBuilder<TitlesDatabase>(
    context = context.applicationContext,
    name = DATABASE_NAME,
  )
    .setDriver(AndroidSQLiteDriver())
    .build()
  return RoomTitlesStore(database)
}

private class RoomTitlesStore(
  private val database: TitlesDatabase,
) : TitlesStore {
  override val titles: Titles = database.asTitles()

  override fun close() {
    database.close()
  }
}

internal const val DATABASE_NAME = "hakusan.db"
