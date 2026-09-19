package me.rerere.rikkahub.sticker

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FileUtils

/**
 * A copied-but-not-yet-committed import.
 *
 * [checksum] is computed while the bytes are being written, so de-duplication costs one pass over
 * the stream rather than a second read of the file afterwards.
 */
data class StagedStickerFile(
    val file: File,
    val checksum: String,
    val sizeBytes: Long,
)

/**
 * The sticker library's slice of the app's private storage: `filesDir/stickers/`.
 *
 * Takes a plain [filesDir] rather than a `Context` so the whole file lifecycle — staging, commit,
 * delete, sweep — is testable on the JVM against a temp directory, with no Android runtime in the
 * way. That matters more than usual here: the failure modes this class guards (orphaned bytes, a
 * half-finished import, a delete that removed the row but not the file) are exactly the ones that
 * are tedious to reproduce on a device and trivial to reproduce in a temp dir.
 *
 * **Import is two-phase.** Bytes land in `stickers/.staging/` first and only reach their final
 * `stickers/<id>.<ext>` name when the person saves. A cancelled import therefore has something
 * concrete to delete, and — more importantly — the library can never contain a row for a picture
 * nobody confirmed. Committing is a rename within one directory, so a save either places the file
 * or does not; it cannot leave a half-written image under the real name.
 *
 * Nothing here trusts a stored path. [resolve] re-derives the target from [filesDir] and refuses
 * anything that is not a direct child of the sticker directory, so a corrupted `relative_path`
 * column cannot be turned into a read or a delete somewhere else in app storage.
 */
class StickerFileStore(private val filesDir: File) {

    /** `filesDir/stickers` — where committed images live. */
    val root: File get() = File(filesDir, FileFolders.STICKERS)

    /** `filesDir/stickers/.staging` — imports awaiting a save. */
    val stagingRoot: File get() = File(root, STAGING_DIR_NAME)

    fun ensureDirectories() {
        root.mkdirs()
        stagingRoot.mkdirs()
    }

    /**
     * Copies [source] into staging, hashing as it goes.
     *
     * Throws whatever the stream throws: the caller owns the source and is the only one that can
     * report a failed read. Nothing is left behind on failure — the partial file is removed here
     * rather than left for the sweep, because the sweep is a safety net for process death, not the
     * routine path.
     */
    fun stage(source: InputStream): StagedStickerFile {
        ensureDirectories()
        val target = File(stagingRoot, "${Uuid.random()}$STAGING_SUFFIX")
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            source.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        size += read
                    }
                }
            }
        } catch (t: Throwable) {
            target.delete()
            throw t
        }
        return StagedStickerFile(
            file = target,
            checksum = digest.digest().toHex(),
            sizeBytes = size,
        )
    }

    /**
     * Moves a staged import to its final name.
     *
     * Returns the `filesDir`-relative path, or null if the rename could not be done — in which case
     * the staged file is deleted, because the caller's only alternatives are to retry with the same
     * bytes or to give up, and leaving it would strand a copy nothing will ever reference.
     *
     * The extension comes from the detected format, not from the picked file's name: the name is
     * attacker-adjacent (it can contain path separators and NUL) and its extension can disagree
     * with the bytes, which is exactly the mismatch the format detection exists to catch.
     */
    fun commit(staged: StagedStickerFile, stickerId: String, extension: String): String? {
        ensureDirectories()
        val target = File(root, "$stickerId.$extension")
        val moved = runCatching { staged.file.renameTo(target) }.getOrDefault(false)
        if (!moved) {
            staged.file.delete()
            return null
        }
        return FileUtils.getRelativePathInFilesDir(filesDir, target)
            ?: run {
                // Unreachable while `root` really is under `filesDir`, but if it ever stopped being
                // true the row would carry a path the sweeper could not match against — so refuse
                // and take the bytes back out rather than persist something unresolvable.
                target.delete()
                null
            }
    }

    /** Drops a staged file — the cancel path and the duplicate path both land here. */
    fun discardStaged(staged: StagedStickerFile): Boolean = staged.file.delete()

    /** Deletes one committed image. False when it was already gone or the path is not ours. */
    fun delete(relativePath: String): Boolean {
        val file = resolve(relativePath) ?: return false
        return file.delete()
    }

    /**
     * Resolves a stored `relative_path` to a real file, or null if it does not name a direct child
     * of the sticker directory.
     *
     * The segment checks are not redundant with the canonical-prefix check that follows them: a
     * prefix check alone would accept `stickers/sub/dir.png`, and a delete is not the place to
     * discover that the store's own invariant was violated.
     */
    fun resolve(relativePath: String): File? {
        val prefix = "${FileFolders.STICKERS}/"
        if (!relativePath.startsWith(prefix)) return null
        val name = relativePath.removePrefix(prefix)
        if (name.isBlank() || name == "." || name == "..") return null
        if (name.contains('/') || name.contains('\\')) return null
        val candidate = File(root, name)
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val canonicalFile = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        if (canonicalFile.parentFile != canonicalRoot) return null
        return canonicalFile
    }

    /** Absolute path for a stored row, for handing the image to Coil or the vision model. */
    fun absolutize(relativePath: String): File? = resolve(relativePath)

    /**
     * Removes staged imports older than [olderThanMs].
     *
     * This is the crash net, not the routine path: an import that is cancelled or fails cleans up
     * after itself, so a file still sitting here after the grace period belongs to a process that
     * died mid-import. The age check is what keeps it from deleting the staging file of an import
     * happening right now.
     */
    fun sweepStaging(nowMs: Long, olderThanMs: Long): Int {
        if (!stagingRoot.isDirectory) return 0
        val cutoff = nowMs - olderThanMs
        var removed = 0
        stagingRoot.listFiles()?.forEach { child ->
            if (!child.isFile || isSymbolicLink(child)) return@forEach
            if (child.lastModified() < cutoff && child.delete()) removed++
        }
        return removed
    }

    /**
     * Deletes committed images that no row points at.
     *
     * The delete path removes the row first and the file second, deliberately: the reverse order
     * can leave a live row whose image is gone, which the library renders as a broken thumbnail,
     * whereas this order can only leave bytes nobody can see. Those bytes are what this sweep
     * reclaims — it runs when the library opens, and it is also what makes a crash between the two
     * deletes self-healing instead of permanent.
     */
    fun sweepOrphans(knownRelativePaths: Set<String>): Int {
        if (!root.isDirectory) return 0
        var removed = 0
        root.listFiles()?.forEach { child ->
            if (!child.isFile || isSymbolicLink(child)) return@forEach
            val relativePath = FileUtils.getRelativePathInFilesDir(filesDir, child) ?: return@forEach
            if (relativePath in knownRelativePaths) return@forEach
            if (child.delete()) removed++
        }
        return removed
    }

    /**
     * A symlink is never followed, deleted or swept: it is the one entry in the directory that can
     * point outside it, and every operation here is defined in terms of "a direct child of the
     * sticker directory".
     */
    private fun isSymbolicLink(file: File): Boolean =
        runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(true)

    private fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        for (byte in this) {
            val value = byte.toInt() and 0xFF
            out.append(HEX[value ushr 4])
            out.append(HEX[value and 0x0F])
        }
        return out.toString()
    }

    companion object {
        const val STAGING_DIR_NAME: String = ".staging"
        const val STAGING_SUFFIX: String = ".part"

        /** A staged import older than this belonged to a process that died before it finished. */
        const val STAGING_GRACE_MS: Long = 24L * 60L * 60L * 1000L

        private const val HEX = "0123456789abcdef"
    }
}
