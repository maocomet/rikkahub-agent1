package me.rerere.rikkahub.sticker

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Shared sticker library persistence.
 *
 * Unlike the Space DAO there is no cursor here, and that is deliberate rather than an omission.
 * A sticker library is a collection a person curates by hand — bounded by how many pictures
 * somebody is willing to file, and rendered as the page's whole content rather than paged
 * through. Nothing here is fed into model context in this phase; when a later phase adds
 * retrieval, the query that does so is where a cap belongs, not on the library listing.
 *
 * Every write that changes user-visible metadata also stamps `updated_at_ms` in the same
 * statement, so a caller cannot update a row and forget the timestamp.
 */
@Dao
interface StickerDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: StickerEntity)

    @Query("SELECT * FROM stickers ORDER BY created_at_ms DESC, sticker_id DESC")
    fun observeAll(): Flow<List<StickerEntity>>

    @Query("SELECT * FROM stickers WHERE sticker_id = :stickerId LIMIT 1")
    suspend fun get(stickerId: String): StickerEntity?

    /**
     * The duplicate-import lookup. Returns the row whose bytes are already stored so the importer
     * can name it in the "已存在" message instead of just refusing.
     */
    @Query("SELECT * FROM stickers WHERE checksum = :checksum LIMIT 1")
    suspend fun findByChecksum(checksum: String): StickerEntity?

    /** Every stored path — the input to the orphan sweep, which is why it selects one column. */
    @Query("SELECT relative_path FROM stickers")
    suspend fun listRelativePaths(): List<String>

    /**
     * Every sticker the library currently offers — the candidate set for retrieval.
     *
     * `enabled = 1` is filtered in SQL rather than in Kotlin because it is the one condition the
     * database can answer. Whether each candidate's *file* still exists cannot be, so that half of
     * the filter lives in the repository; see [me.rerere.rikkahub.sticker.StickerRepository.search].
     *
     * Ordered the same way as [observeAll] so an unranked read of this list is still deterministic.
     */
    @Query("SELECT * FROM stickers WHERE enabled = 1 ORDER BY created_at_ms DESC, sticker_id DESC")
    suspend fun listEnabled(): List<StickerEntity>

    /** Replaces both the metadata and the recognition outcome in one statement. */
    @Query(
        "UPDATE stickers SET description = :description, tags_json = :tagsJson, " +
            "vision_state = :visionState, vision_error_code = :visionErrorCode, " +
            "updated_at_ms = :updatedAtMs WHERE sticker_id = :stickerId",
    )
    suspend fun updateMetadata(
        stickerId: String,
        description: String,
        tagsJson: String,
        visionState: String,
        visionErrorCode: String?,
        updatedAtMs: Long,
    ): Int

    @Query(
        "UPDATE stickers SET enabled = :enabled, updated_at_ms = :updatedAtMs " +
            "WHERE sticker_id = :stickerId",
    )
    suspend fun updateEnabled(
        stickerId: String,
        enabled: Boolean,
        updatedAtMs: Long,
    ): Int

    /**
     * Deletes exactly one row. Returns the count so the caller can tell a real delete from a
     * no-op — the delete path must not report success for a row that was already gone, because
     * its whole job is to then remove that row's file.
     */
    @Query("DELETE FROM stickers WHERE sticker_id = :stickerId")
    suspend fun delete(stickerId: String): Int
}
