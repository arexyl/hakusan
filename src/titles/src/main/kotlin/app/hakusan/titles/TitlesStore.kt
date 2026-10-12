package app.hakusan.titles

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import app.hakusan.titles.storage.RoomTitles
import app.hakusan.titles.storage.TitlesDatabase

/**
 * The file-backed store of titles, chapters, the Library, and reading progress.
 *
 * The application keeps one store for the process lifetime and closes it
 * explicitly. No other process opens its database. The store exposes only the
 * [Titles] domain surface; its Room database stays internal.
 */
public class TitlesStore
private constructor(internal val database: TitlesDatabase) : AutoCloseable {
  /** The domain operations on this store. */
  public val titles: Titles = RoomTitles(database)

  /** Releases the database resources of this store. */
  override fun close() {
    database.close()
  }

  public companion object {
    /**
     * Opens the store of the application that owns [context].
     *
     * The database file opens, and its schema is created, on first use.
     *
     * @throws IllegalArgumentException if [context] has no application context.
     */
    public fun open(context: Context): TitlesStore {
      val applicationContext =
        requireNotNull(context.applicationContext) {
          "The titles store requires an application context."
        }

      val database =
        Room.databaseBuilder<TitlesDatabase>(
            context = applicationContext,
            name = DATABASE_NAME,
          )
          .setDriver(AndroidSQLiteDriver())
          .build()

      return TitlesStore(database)
    }
  }
}

/** The file name of the one application database. */
internal const val DATABASE_NAME: String = "hakusan.db"
