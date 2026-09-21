package me.rerere.rikkahub.data.claudep

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import me.rerere.ai.provider.claudep.ClaudePCleanupTombstone
import me.rerere.ai.provider.claudep.ClaudePCleanupTombstoneCodec
import me.rerere.ai.provider.claudep.ClaudePCleanupTombstoneStore
import me.rerere.ai.provider.claudep.ClaudePTombstoneRead

/**
 * Durable cleanup tombstone, in a Claude P private directory under `noBackupFilesDir`.
 *
 * ### What it holds
 *
 * A format version and a device key alias, encoded and validated by
 * [ClaudePCleanupTombstoneCodec]. Nothing else — no ticket, credential, key material, gateway URL,
 * token or device identity.
 *
 * `noBackupFilesDir` is the platform-level guarantee that it never enters cloud backup or device
 * transfer, which matters because a restored device must not believe it has a pending cleanup that
 * belongs to another installation.
 *
 * ### This class makes no judgement of its own
 *
 * It supplies bytes to the codec and returns the codec's verdict. The rule that a record which
 * *exists but cannot be parsed* is **not** the same as *no record* — and the alias shape, length and
 * version limits — live in `ai` with the tests that cover them, rather than being re-derived here
 * where a JVM test could never reach them.
 */
class FileClaudePCleanupTombstoneStore(
    context: Context,
) : ClaudePCleanupTombstoneStore {

    private val directory = File(context.noBackupFilesDir, DIRECTORY_NAME)
    private val file = File(directory, FILE_NAME)
    private val temporaryFile = File(directory, "$FILE_NAME.tmp")

    override suspend fun read(): ClaudePTombstoneRead {
        // A missing file is the *only* thing that means "nothing is pending".
        if (!file.exists()) return ClaudePTombstoneRead.Absent

        val raw = try {
            file.readBytes().decodeToString()
        } catch (t: Throwable) {
            // The record exists but cannot even be read. Reported as unusable rather than absent:
            // the codec's rule is that unreadable evidence is still evidence.
            Log.w(TAG, "Claude P cleanup tombstone unreadable: ${t::class.java.simpleName}")
            return ClaudePTombstoneRead.Unusable(
                me.rerere.ai.provider.claudep.ClaudePTombstoneRejection.MALFORMED,
            )
        }

        return ClaudePCleanupTombstoneCodec.decode(raw)
    }

    /** Stages beside the target and replaces atomically. Returns `false` when it could not. */
    override suspend fun write(tombstone: ClaudePCleanupTombstone): Boolean {
        val payload = ClaudePCleanupTombstoneCodec.encode(tombstone) ?: return false

        return try {
            if (!directory.exists() && !directory.mkdirs()) return false

            temporaryFile.writeBytes(payload.encodeToByteArray())
            Files.move(
                temporaryFile.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            true
        } catch (t: Throwable) {
            // Includes AtomicMoveNotSupportedException. There is deliberately no destructive
            // fallback: the coordinator treats a failed write as "do not start deleting", so a
            // half-written record is worse than none.
            Log.w(TAG, "Claude P cleanup tombstone write failed: ${t::class.java.simpleName}")
            clearTemporaryFile()
            false
        }
    }

    /** Removes the tombstone and confirms it is gone. */
    override suspend fun clear(): Boolean {
        clearTemporaryFile()
        return try {
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Claude P cleanup tombstone could not be deleted")
                return false
            }
            !file.exists()
        } catch (t: Throwable) {
            Log.w(TAG, "Claude P cleanup tombstone deletion failed: ${t::class.java.simpleName}")
            false
        }
    }

    private fun clearTemporaryFile() {
        try {
            if (temporaryFile.exists()) temporaryFile.delete()
        } catch (t: Throwable) {
            Log.w(TAG, "Claude P tombstone temporary file cleanup failed: ${t::class.java.simpleName}")
        }
    }

    private companion object {
        const val TAG = "ClaudePTombstone"
        const val DIRECTORY_NAME = "claude_p"
        const val FILE_NAME = "cleanup.tombstone"
    }
}
