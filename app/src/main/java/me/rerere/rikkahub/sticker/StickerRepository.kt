package me.rerere.rikkahub.sticker

import java.io.File
import java.io.InputStream
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The sticker library's storage boundary: rows and their images, owned together.
 *
 * The two live apart — metadata in Room, bytes under `filesDir` — but they are never *managed*
 * apart, and that is the whole reason this class exists rather than letting callers hold a DAO and
 * a [StickerFileStore] each. Every operation that touches both has a required order and a defined
 * failure outcome, and there is exactly one place to look for it:
 *
 *  - **Delete removes the row first, then the file.** The reverse can leave a live row whose image
 *    is gone, which renders as a broken thumbnail — a visible defect with no recovery. This order
 *    can only leave bytes with no row, which nothing displays and [sweepOrphans] reclaims on the
 *    next library open. Neither order is atomic across the two stores; only one of them fails
 *    invisibly.
 *  - **A failed file delete is not an error.** It is the expected crash case, and it is already
 *    handled by the sweep, so the delete still reports success once the row is gone. Reporting
 *    failure would push callers into retrying a delete whose subject no longer exists.
 *  - **Commit is a rename, then a row.** Bytes are placed under their final name before the row
 *    that names them is written, so the window where a row could reference a missing file does not
 *    exist. If the row insert throws, the caller's cleanup is a single [deleteFileFor] — the image
 *    has a stable name by then.
 *
 * Time and id generation are seams with production defaults, matching the convention the Space
 * repository sets, so the JVM tests can drive both deterministically.
 */
class StickerRepository(
    private val dao: StickerDao,
    private val fileStore: StickerFileStore,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { Uuid.random().toString() },
    /**
     * Runs [block] as one atomic unit. Production passes the database's own transaction; the JVM
     * tests pass nothing, which runs the block directly. Only [deleteSticker] needs it today, and
     * it needs it for the same reason the Space sweeps do: the check and the delete must not have
     * another write land between them.
     */
    private val inTransaction: suspend (suspend () -> Unit) -> Unit = { block -> block() },
) {

    // ── Library reads ────────────────────────────────────────────────────────────────────────

    fun observeLibrary(): Flow<List<Sticker>> = dao.observeAll().map { rows -> rows.map { it.toSticker() } }

    suspend fun getSticker(stickerId: String): Sticker? = dao.get(stickerId)?.toSticker()

    /**
     * The row whose bytes are already stored, if any — the duplicate-import lookup.
     *
     * Only an optimisation for the *message*: [insertSticker] would still refuse a second copy of
     * the same bytes through the unique index. This is what lets the importer say "这张表情包已存在"
     * and name the existing sticker instead of reporting a generic constraint failure.
     */
    suspend fun findDuplicate(checksum: String): Sticker? =
        dao.findByChecksum(checksum)?.toSticker()

    // ── Library writes ───────────────────────────────────────────────────────────────────────

    /**
     * Writes a new row. Throws whatever the DAO throws — in particular a constraint violation,
     * which the importer treats as "lost a race to another import of the same bytes" rather than
     * as a storage failure.
     */
    suspend fun insertSticker(
        relativePath: String,
        mimeType: String,
        width: Int,
        height: Int,
        description: String,
        tags: List<String>,
        checksum: String,
        enabled: Boolean = true,
        visionState: StickerVisionState = StickerVisionState.NONE,
        visionFailure: StickerVisionFailure? = null,
        stickerId: String = newId(),
    ): Sticker {
        val now = nowMs()
        val sticker = Sticker(
            id = stickerId,
            relativePath = relativePath,
            mimeType = mimeType,
            width = width,
            height = height,
            description = description,
            tags = StickerTags.normalize(tags),
            enabled = enabled,
            createdAtMs = now,
            updatedAtMs = now,
            checksum = checksum,
            visionState = visionState,
            visionFailure = visionFailure,
        )
        dao.insert(sticker.toEntity())
        return sticker
    }

    /**
     * Replaces description, tags and the recognition outcome together.
     *
     * They travel as one write because they are one event: a recognition run produces a description
     * and tags at the same instant it produces a success/failure verdict, and splitting them would
     * allow a row that claims [StickerVisionState.OK] with the previous run's text.
     */
    suspend fun updateMetadata(
        stickerId: String,
        description: String,
        tags: List<String>,
        visionState: StickerVisionState,
        visionFailure: StickerVisionFailure? = null,
    ): Boolean = dao.updateMetadata(
        stickerId = stickerId,
        description = description,
        tagsJson = StickerTags.encode(tags),
        visionState = visionState.name,
        visionErrorCode = visionFailure?.name,
        updatedAtMs = nowMs(),
    ) > 0

    suspend fun setEnabled(stickerId: String, enabled: Boolean): Boolean =
        dao.updateEnabled(stickerId, enabled, nowMs()) > 0

    /**
     * Removes one sticker: row first, then image.
     *
     * Returns false only when there was no row to delete — a caller that gets false has nothing to
     * clean up and nothing to report, because the sticker it meant to remove is already gone. A
     * failure to delete the *file* is deliberately not surfaced: [sweepOrphans] owns that case.
     */
    suspend fun deleteSticker(stickerId: String): Boolean {
        var removed = false
        var relativePath: String? = null
        inTransaction {
            val row = dao.get(stickerId) ?: return@inTransaction
            relativePath = row.relativePath
            removed = dao.delete(stickerId) > 0
        }
        // Outside the transaction on purpose: file I/O is not transactional, and holding a write
        // transaction open across it would block every other write for the duration of a syscall
        // that cannot be rolled back anyway.
        if (removed) relativePath?.let { fileStore.delete(it) }
        return removed
    }

    // ── File lifecycle ───────────────────────────────────────────────────────────────────────

    suspend fun stageImport(source: InputStream): StagedStickerFile = fileStore.stage(source)

    suspend fun discardStaged(staged: StagedStickerFile): Boolean = fileStore.discardStaged(staged)

    /** Places staged bytes under their final name. Null means the placement failed and the staged copy is gone. */
    suspend fun commitStaged(staged: StagedStickerFile, stickerId: String, extension: String): String? =
        fileStore.commit(staged, stickerId, extension)

    /** Removes a committed image by its stored path. Used to undo a commit whose row insert failed. */
    suspend fun deleteFileFor(relativePath: String): Boolean = fileStore.delete(relativePath)

    /**
     * The stored image as a file, or null when the row points at nothing.
     *
     * Existence is part of the contract rather than an afterthought, and [StickerFileStore.resolve]
     * deliberately does not check it: path resolution and "is the image actually here" are
     * different questions, and conflating them would make the delete path unable to name a file it
     * has just removed.
     *
     * Every caller needs the second question answered. The grid has to tell a missing image from a
     * present one so it can say so instead of drawing a blank cell, and the vision client must not
     * be handed a path that resolves to nothing.
     */
    fun absolutePathOf(relativePath: String): File? =
        fileStore.resolve(relativePath)?.takeIf { it.isFile }

    /**
     * Reclaims images no row points at. Runs when the library opens; see the class comment for why
     * the delete ordering makes this necessary rather than merely tidy.
     */
    suspend fun sweepOrphans(): Int = fileStore.sweepOrphans(dao.listRelativePaths().toSet())

    /** Reclaims imports abandoned by a process that died mid-import. */
    suspend fun sweepStaging(): Int =
        fileStore.sweepStaging(nowMs(), StickerFileStore.STAGING_GRACE_MS)
}
