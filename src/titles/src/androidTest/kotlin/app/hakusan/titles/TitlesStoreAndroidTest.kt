package app.hakusan.titles

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TitlesStoreAndroidTest {
  private val context: Context
    get() = ApplicationProvider.getApplicationContext()

  @Before
  fun removePreviousDatabase() {
    context.deleteDatabase(DATABASE_NAME)
  }

  @After
  fun removeTestDatabase() {
    context.deleteDatabase(DATABASE_NAME)
  }

  @Test
  fun fileBackedStoreRetainsLibraryAcrossCloseAndReopen(): Unit = runBlocking {
    val expectedId = openTitlesStore(context).use { store ->
      val id = store.titles.reconcileSourceTitle(
        ReconcileSourceTitle(
          alias = SourceTitleAlias("source", "title"),
          displayName = "Title",
          description = "Description",
        ),
      )
      assertTrue(store.titles.addToLibrary(id) is LibraryAddResult.Success)
      id
    }

    assertTrue(context.getDatabasePath(DATABASE_NAME).isFile)

    openTitlesStore(context).use { reopened ->
      val library = reopened.titles.observeLibrary().first()
      val title = library.titlesById.getValue(expectedId)

      assertEquals("Title", title.displayName)
      assertEquals("Description", title.description)
      assertEquals("Default", library.shelves.single().category.name)
      assertEquals(setOf(expectedId), library.shelves.single().titleIds)
    }
  }
}
