package app.hakusan.titles

import android.content.Context
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TitlesStoreTest {
  private val context: Context = ApplicationProvider.getApplicationContext()

  @Before
  @After
  fun deleteDatabase() {
    context.deleteDatabase(DATABASE_NAME)
  }

  @Test
  fun reopenedStoreKeepsItsFileAndSchemaVersion1(): Unit = runBlocking {
    TitlesStore.open(context).use { store ->
      assertEquals(1L, store.schemaVersion())
      store.insertCategory("Kept")
    }

    assertTrue(context.getDatabasePath(DATABASE_NAME).isFile)

    TitlesStore.open(context).use { store ->
      assertEquals(1L, store.schemaVersion())
      assertEquals(listOf("Kept"), store.categoryNames())
    }
  }

  private suspend fun TitlesStore.schemaVersion(): Long =
    database.useReaderConnection { connection ->
      connection.usePrepared("PRAGMA user_version") { statement ->
        statement.step()

        statement.getLong(0)
      }
    }

  private suspend fun TitlesStore.insertCategory(name: String) {
    database.useWriterConnection { connection ->
      connection.usePrepared("INSERT INTO categories (name) VALUES (?)") {
        statement ->
        statement.bindText(1, name)
        statement.step()
      }
    }
  }

  private suspend fun TitlesStore.categoryNames(): List<String> =
    database.useReaderConnection { connection ->
      connection.usePrepared("SELECT name FROM categories") { statement ->
        buildList {
          while (statement.step()) {
            add(statement.getText(0))
          }
        }
      }
    }
}
