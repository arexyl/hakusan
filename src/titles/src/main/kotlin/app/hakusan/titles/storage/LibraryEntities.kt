package app.hakusan.titles.storage

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(tableName = "categories")
internal data class CategoryEntity(
  @PrimaryKey(
    autoGenerate = true,
    algorithm = PrimaryKey.Algorithm.ROWID,
  )
  @ColumnInfo(name = "id")
  val id: Long = 0,
  @ColumnInfo(name = "name")
  val name: String,
) {
  init {
    requireGeneratedStorageId(id, "Category id")
  }
}

/**
 * One category association. A title is in the Library exactly while at least
 * one such row exists.
 */
@Entity(
  tableName = "title_categories",
  primaryKeys = ["title_storage_id", "category_id"],
  withoutRowId = true,
  foreignKeys = [
    ForeignKey(
      entity = TitleEntity::class,
      parentColumns = ["storage_id"],
      childColumns = ["title_storage_id"],
      onDelete = ForeignKey.CASCADE,
    ),
    ForeignKey(
      entity = CategoryEntity::class,
      parentColumns = ["id"],
      childColumns = ["category_id"],
      onDelete = ForeignKey.RESTRICT,
    ),
  ],
  indices = [
    Index(
      name = "index_title_categories_category_title",
      value = ["category_id", "title_storage_id"],
    ),
  ],
)
internal data class TitleCategoryEntity(
  @ColumnInfo(name = "title_storage_id")
  val titleStorageId: Long,
  @ColumnInfo(name = "category_id")
  val categoryId: Long,
) {
  init {
    requireStoredId(titleStorageId, "Category title storage id")
    requireStoredId(categoryId, "Category association id")
  }
}
