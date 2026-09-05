package app.hakusan.titles.storage

import androidx.room3.Database
import androidx.room3.RoomDatabase

@Database(
  entities = [
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
  abstract fun readingDao(): ReadingDao

  abstract fun titlesDao(): TitlesDao
}
