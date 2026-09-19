package me.rerere.rikkahub.sticker

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory [StickerDao] that reproduces the constraints the real schema enforces.
 *
 * Both UNIQUE indices are enforced here as well as in Room, because the tests must fail if the
 * repository starts relying on something the database does not actually guarantee:
 *  - a second row with the same `checksum` throws, exactly as the unique index does. Without this,
 *    a test asserting "the same picture is only stored once" would pass against a fake that
 *    silently allowed the duplicate the real database rejects;
 *  - a second row with the same `relative_path` throws, which is what keeps the orphan sweep from
 *    ever having to choose between two rows naming one file.
 *
 * The update statements return the number of rows they touched, mirroring Room, so a repository
 * that reports success for a row that was already gone is caught here rather than on a device.
 */
class FakeStickerDao : StickerDao {

    val rows = LinkedHashMap<String, StickerEntity>()

    private val signal = MutableStateFlow<List<StickerEntity>>(emptyList())

    /** Set to make the next insert fail, for the commit-rollback paths. */
    var failNextInsert: Boolean = false

    override suspend fun insert(entity: StickerEntity) {
        if (failNextInsert) {
            failNextInsert = false
            error("insert failed")
        }
        check(rows[entity.stickerId] == null) { "duplicate sticker_id" }
        check(rows.values.none { it.checksum == entity.checksum }) { "duplicate checksum" }
        check(rows.values.none { it.relativePath == entity.relativePath }) {
            "duplicate relative_path"
        }
        rows[entity.stickerId] = entity
        publish()
    }

    override fun observeAll(): Flow<List<StickerEntity>> = signal.asStateFlow()

    override suspend fun get(stickerId: String): StickerEntity? = rows[stickerId]

    override suspend fun findByChecksum(checksum: String): StickerEntity? =
        rows.values.firstOrNull { it.checksum == checksum }

    override suspend fun listRelativePaths(): List<String> = rows.values.map { it.relativePath }

    override suspend fun listEnabled(): List<StickerEntity> =
        rows.values.filter { it.enabled }.sortedWith(
            compareByDescending<StickerEntity> { it.createdAtMs }.thenByDescending { it.stickerId },
        )

    override suspend fun updateMetadata(
        stickerId: String,
        description: String,
        tagsJson: String,
        visionState: String,
        visionErrorCode: String?,
        updatedAtMs: Long,
    ): Int {
        val existing = rows[stickerId] ?: return 0
        rows[stickerId] = existing.copy(
            description = description,
            tagsJson = tagsJson,
            visionState = visionState,
            visionErrorCode = visionErrorCode,
            updatedAtMs = updatedAtMs,
        )
        publish()
        return 1
    }

    override suspend fun updateEnabled(
        stickerId: String,
        enabled: Boolean,
        updatedAtMs: Long,
    ): Int {
        val existing = rows[stickerId] ?: return 0
        rows[stickerId] = existing.copy(enabled = enabled, updatedAtMs = updatedAtMs)
        publish()
        return 1
    }

    override suspend fun delete(stickerId: String): Int {
        if (rows.remove(stickerId) == null) return 0
        publish()
        return 1
    }

    private fun publish() {
        signal.value = rows.values.toList()
    }
}
