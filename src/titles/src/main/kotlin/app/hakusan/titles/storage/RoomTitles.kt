package app.hakusan.titles.storage

import app.hakusan.titles.ApplicationUuidFactory
import app.hakusan.titles.ReconcileSourceTitle
import app.hakusan.titles.TitleId
import app.hakusan.titles.Titles
import androidx.room3.withWriteTransaction
import java.util.UUID

internal class RoomTitles(
  private val database: TitlesDatabase,
  private val createUuid: () -> UUID = ApplicationUuidFactory::create,
) : Titles {
  private val dao = database.titlesDao()

  override suspend fun reconcileSourceTitle(
    input: ReconcileSourceTitle,
  ): TitleId = database.withWriteTransaction {
    val current = dao.findTitleByAlias(
      sourceIdentity = input.alias.sourceIdentity,
      sourceTitleKey = input.alias.sourceTitleKey,
    )
    if (current != null) {
      if (
        current.displayName != input.displayName ||
        current.description != input.description
      ) {
        check(
          dao.updateTitleMetadata(
            storageId = current.storageId,
            displayName = input.displayName,
            description = input.description,
          ) == 1,
        ) {
          "Reconciled title disappeared during its transaction."
        }
      }
      return@withWriteTransaction TitleId(current.id)
    }

    // The IMMEDIATE write transaction prevents another alias insert after the
    // miss above. An ignored valid candidate can only collide on its UUID.
    repeat(MAX_UUID_GENERATION_ATTEMPTS) {
      val id = createUuid()
      val storageId = dao.insertTitleOrIgnore(
        TitleEntity(
          storageId = 0,
          id = id,
          sourceIdentity = input.alias.sourceIdentity,
          sourceTitleKey = input.alias.sourceTitleKey,
          displayName = input.displayName,
          description = input.description,
        ),
      )
      if (storageId > 0) {
        return@withWriteTransaction TitleId(id)
      }
    }
    error("Unable to allocate a unique title UUIDv7.")
  }

  private companion object {
    const val MAX_UUID_GENERATION_ATTEMPTS = 16
  }
}

internal fun TitlesDatabase.asTitles(
  createUuid: () -> UUID = ApplicationUuidFactory::create,
): Titles = RoomTitles(
  database = this,
  createUuid = createUuid,
)
