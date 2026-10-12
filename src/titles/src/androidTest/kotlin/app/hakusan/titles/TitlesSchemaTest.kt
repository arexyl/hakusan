package app.hakusan.titles

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room3.useReaderConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.hakusan.titles.storage.toStoredBytes
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Inspects the schema that Room creates in the file-backed store. */
@RunWith(AndroidJUnit4::class)
class TitlesSchemaTest {
  private val context: Context = ApplicationProvider.getApplicationContext()

  private lateinit var connection: SQLiteConnection

  @Before
  fun createSchema(): Unit = runBlocking {
    context.deleteDatabase(DATABASE_NAME)

    TitlesStore.open(context).use { store ->
      store.database.useReaderConnection { reader ->
        reader.usePrepared("SELECT 1") { statement -> statement.step() }
      }
    }

    val path = context.getDatabasePath(DATABASE_NAME).path

    connection = AndroidSQLiteDriver().open(path)

    connection.execSQL("PRAGMA foreign_keys = ON")
  }

  @After
  fun deleteSchema() {
    connection.close()
    context.deleteDatabase(DATABASE_NAME)
  }

  @Test
  fun schemaHasTheSixTablesWithoutAutoincrement() {
    val tables = tableSql()

    assertEquals(TABLES, tables.keys)
    assertEquals(0L, count("sqlite_master WHERE name = 'sqlite_sequence'"))

    tables.forEach { (table, sql) ->
      assertTrue(table, !sql.contains("AUTOINCREMENT", ignoreCase = true))
    }
  }

  @Test
  fun onlyTitleCategoriesIsWithoutRowid() {
    val withoutRowid =
      tableSql().filterValues { sql ->
        sql.trimEnd().endsWith("WITHOUT ROWID", ignoreCase = true)
      }

    assertEquals(setOf("title_categories"), withoutRowid.keys)
  }

  @Test
  fun uuidColumnsAreUniqueNonNullBlobs() {
    for (table in listOf("titles", "chapters")) {
      val id = columns(table).getValue("id")

      assertEquals(table, "BLOB", id.type)
      assertTrue(table, id.notNull)
      assertTrue(table, listOf("id") in uniqueIndexes(table))
    }
  }

  @Test
  fun aliasesAndCanonicalIndexesAreUnique() {
    val titleAlias = listOf("source_identity", "source_title_key")

    val chapterAlias = listOf("title_storage_id", "source_chapter_key")

    val canonical = listOf("title_storage_id", "canonical_index")

    assertTrue(titleAlias in uniqueIndexes("titles"))
    assertTrue(chapterAlias in uniqueIndexes("chapters"))
    assertTrue(canonical in uniqueIndexes("chapters"))
    assertTrue(!columns("chapters").getValue("canonical_index").notNull)
  }

  @Test
  fun foreignKeysUseIntegerStorageKeysAndEnforceOwnership() {
    val foreignKeys = TABLES.flatMap(::foreignKeys).toSet()

    assertEquals(FOREIGN_KEYS, foreignKeys)

    foreignKeys.forEach { key ->
      val columns = columns(key.table)

      val parentColumns = columns(key.parent)

      key.columns.forEach { column ->
        assertEquals(key.toString(), "INTEGER", columns.getValue(column).type)
      }

      key.parentColumns.forEach { column ->
        assertEquals(
          key.toString(),
          "INTEGER",
          parentColumns.getValue(column).type,
        )
      }
    }
  }

  @Test
  fun categoryTitleIndexServesShelves() {
    val reverse = listOf("category_id", "title_storage_id")

    assertTrue(reverse in indexes("title_categories").values)
  }

  @Test
  fun resumePositionRejectsAChapterOfAnotherTitle() {
    val title = insertTitle("source", "title")

    val other = insertTitle("source", "other")

    val foreignChapter = insertChapter(other, "chapter")

    assertThrows(SQLiteConstraintException::class.java) {
      insertResumePosition(title, foreignChapter)
    }
  }

  @Test
  fun referencedCategoryCannotBeDeleted() {
    val title = insertTitle("source", "title")

    val category = insertCategory("Default")

    connection.execSQL(
      "INSERT INTO title_categories (title_storage_id, category_id) " +
        "VALUES ($title, $category)"
    )

    assertThrows(SQLiteConstraintException::class.java) {
      connection.execSQL("DELETE FROM categories WHERE id = $category")
    }
  }

  @Test
  fun duplicateAliasesAndUuidsAreRejected() {
    val titleUuid = TitleId.generate().toStoredBytes()

    val title = insertTitle("source", "title", titleUuid)

    val chapterUuid = ChapterId.generate().toStoredBytes()

    insertChapter(title, "chapter", chapterUuid)

    assertThrows(SQLiteConstraintException::class.java) {
      insertTitle("source", "title")
    }

    assertThrows(SQLiteConstraintException::class.java) {
      insertTitle("source", "another", titleUuid)
    }

    assertThrows(SQLiteConstraintException::class.java) {
      insertChapter(title, "chapter")
    }

    assertThrows(SQLiteConstraintException::class.java) {
      insertChapter(title, "another", chapterUuid)
    }
  }

  private fun tableSql(): Map<String, String> =
    query(
        "SELECT name, sql FROM sqlite_master WHERE type = 'table' " +
          "AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\' " +
          "AND name NOT IN ('android_metadata', 'room_master_table')"
      ) { statement ->
        statement.getText(0) to statement.getText(1)
      }
      .toMap()

  private fun columns(table: String): Map<String, Column> =
    query("PRAGMA table_info(`$table`)") { statement ->
        statement.getText(1) to
          Column(statement.getText(2), statement.getLong(3) != 0L)
      }
      .toMap()

  private fun indexes(table: String): Map<String, List<String>> =
    query("PRAGMA index_list(`$table`)") { statement -> statement.getText(1) }
      .associateWith { index ->
        query("PRAGMA index_info(`$index`)") { statement ->
          statement.getText(2)
        }
      }

  private fun uniqueIndexes(table: String): List<List<String>> {
    val indexes = indexes(table)

    val unique =
      query("PRAGMA index_list(`$table`)") { statement ->
          statement.getText(1) to (statement.getLong(2) != 0L)
        }
        .filter { (_, isUnique) -> isUnique }
        .map { (index, _) -> index }

    return unique.map(indexes::getValue)
  }

  private fun foreignKeys(table: String): List<ForeignKeyShape> {
    val rows =
      query("PRAGMA foreign_key_list(`$table`)") { statement ->
        ForeignKeyRow(
          id = statement.getLong(0),
          parent = statement.getText(2),
          column = statement.getText(3),
          parentColumn = statement.getText(4),
          onDelete = statement.getText(6),
        )
      }

    return rows.groupBy(ForeignKeyRow::id).values.map { key ->
      ForeignKeyShape(
        table = table,
        columns = key.map(ForeignKeyRow::column),
        parent = key.first().parent,
        parentColumns = key.map(ForeignKeyRow::parentColumn),
        onDelete = key.first().onDelete,
      )
    }
  }

  private fun insertTitle(
    source: String,
    key: String,
    uuid: ByteArray = TitleId.generate().toStoredBytes(),
  ): Long =
    insert(
      "INSERT INTO titles (id, source_identity, source_title_key, " +
        "display_name) VALUES (?, ?, ?, 'Title')"
    ) { statement ->
      statement.bindBlob(1, uuid)
      statement.bindText(2, source)
      statement.bindText(3, key)
    }

  private fun insertChapter(
    title: Long,
    key: String,
    uuid: ByteArray = ChapterId.generate().toStoredBytes(),
  ): Long =
    insert(
      "INSERT INTO chapters (id, title_storage_id, source_chapter_key, " +
        "display_name) VALUES (?, ?, ?, 'Chapter')"
    ) { statement ->
      statement.bindBlob(1, uuid)
      statement.bindLong(2, title)
      statement.bindText(3, key)
    }

  private fun insertCategory(name: String): Long =
    insert("INSERT INTO categories (name) VALUES (?)") { statement ->
      statement.bindText(1, name)
    }

  private fun insertResumePosition(title: Long, chapter: Long): Long =
    insert(
      "INSERT INTO library_resume_positions (title_storage_id, " +
        "chapter_storage_id, unit_kind, unit_index, updated_at) " +
        "VALUES (?, ?, 'PAGE', 0, 0)"
    ) { statement ->
      statement.bindLong(1, title)
      statement.bindLong(2, chapter)
    }

  private fun insert(sql: String, bind: (SQLiteStatement) -> Unit): Long {
    connection.prepare(sql).use { statement ->
      bind(statement)
      statement.step()
    }

    return query("SELECT last_insert_rowid()") { statement ->
        statement.getLong(0)
      }
      .single()
  }

  private fun count(from: String): Long =
    query("SELECT count(*) FROM $from") { statement -> statement.getLong(0) }
      .single()

  private fun <T> query(sql: String, row: (SQLiteStatement) -> T): List<T> =
    connection.prepare(sql).use { statement ->
      buildList {
        while (statement.step()) {
          add(row(statement))
        }
      }
    }

  private data class Column(val type: String, val notNull: Boolean)

  private data class ForeignKeyRow(
    val id: Long,
    val parent: String,
    val column: String,
    val parentColumn: String,
    val onDelete: String,
  )

  private data class ForeignKeyShape(
    val table: String,
    val columns: List<String>,
    val parent: String,
    val parentColumns: List<String>,
    val onDelete: String,
  )

  private companion object {
    val TABLES =
      setOf(
        "categories",
        "chapters",
        "library_resume_positions",
        "read_chapters",
        "title_categories",
        "titles",
      )

    val FOREIGN_KEYS =
      setOf(
        ForeignKeyShape(
          table = "chapters",
          columns = listOf("title_storage_id"),
          parent = "titles",
          parentColumns = listOf("storage_id"),
          onDelete = "CASCADE",
        ),
        ForeignKeyShape(
          table = "library_resume_positions",
          columns = listOf("chapter_storage_id", "title_storage_id"),
          parent = "chapters",
          parentColumns = listOf("storage_id", "title_storage_id"),
          onDelete = "CASCADE",
        ),
        ForeignKeyShape(
          table = "read_chapters",
          columns = listOf("chapter_storage_id"),
          parent = "chapters",
          parentColumns = listOf("storage_id"),
          onDelete = "CASCADE",
        ),
        ForeignKeyShape(
          table = "title_categories",
          columns = listOf("title_storage_id"),
          parent = "titles",
          parentColumns = listOf("storage_id"),
          onDelete = "CASCADE",
        ),
        ForeignKeyShape(
          table = "title_categories",
          columns = listOf("category_id"),
          parent = "categories",
          parentColumns = listOf("id"),
          onDelete = "RESTRICT",
        ),
      )
  }
}
