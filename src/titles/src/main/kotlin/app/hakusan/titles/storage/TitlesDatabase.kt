package app.hakusan.titles.storage

import androidx.room3.Database
import androidx.room3.RoomDatabase

/**
 * The Room database of `:titles`, schema version 1.
 *
 * Version 1 may change freely until the first published Nightly build. After
 * that build, every schema change needs a new version and a migration.
 */
@Database(
  entities =
    [
      CategoryEntity::class,
      ChapterEntity::class,
      LibraryResumePositionEntity::class,
      ReadChapterEntity::class,
      TitleCategoryEntity::class,
      TitleEntity::class,
    ],
  version = 1,
  exportSchema = true,
)
internal abstract class TitlesDatabase : RoomDatabase() {
  /** Returns the persistence primitives of `:titles`. */
  abstract fun titlesDao(): TitlesDao
}
