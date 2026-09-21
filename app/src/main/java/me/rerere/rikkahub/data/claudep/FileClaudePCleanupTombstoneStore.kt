package me.rerere.rikkahub.data.claudep

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.claudep.ClaudePCleanupTombstone
import me.rerere.ai.provider.claudep.ClaudePCleanupTombstoneStore

/**
 * Durable cleanup tombstone, in a Claude P private directory under `noBackupFilesDir`.
 *
 * ### What it holds, and what it must never hold
 *
 * A format version and a device key alias. Nothing else — no ticket, credential, key material,
 * gateway URL, token or device identity. The alias is a *name*, meaningless without Keystore access
 * on this device.
 *
 * `noBackupFilesDir` is the platform-level guarantee that it never enters cloud backup or device
 * transfer, which matters because a restored device must not believe it has a pending cleanup that
 * belongs to a different installation.
 *
 * ### Strictness
 *
 * Reads are deliberately unforgiving. A malformed record, an unknown format version or an alias that
 * does not look like one of ours is **not** treated as "no tombstone" — that would silently discard
 * the only record of a key that may still exist. It is treated as a *present but unusable* pending
 * cleanup, which keeps the device non-dispatchable and surfaces the problem instead of hiding it.
 *
 * Nothing about the alias, the path or an exception message reaches a log or the UI; failures are
 * reported only as booleans to the coordinator, which turns them into bounded enum values.
 */
class FileClaudePCleanupTombstoneStore(
    context: Context,
    private val json: Json,
) : ClaudePCleanupTombstoneStore {

    private val directory = File(context.noBackupFilesDir, DIRECTORY_NAME)
    private val file = File(directory, FILE_NAME)
    private val temporaryFile = File(directory, "$FILE_NAME.tmp")

    /**
     * Reads the pending cleanup.
     *
     * Returns `null` **only** when there is genuinely no record. A record that exists but cannot be
     * parsed yields a tombstone with an empty alias sentinel, which the coordinator treats as
     * "something is pending and we cannot name it" — never as "nothing to do".
     */
    override suspend fun read(): ClaudePCleanupTombstone? {
        if (!file.exists()) return null

        val record = try {
            val raw = file.readBytes().decodeToString()
            json.decodeFromString<StoredTombstone>(raw)
        } catch (t: Throwable) {
            // Class name only. The decode error can quote the file contents.
            Log.w(TAG, "Claude P cleanup tombstone unreadable: ${t::class.java.simpleName}")
            return UNREADABLE_TOMBSTONE
        }

        if (record.version != ClaudePCleanupTombstone.CURRENT_VERSION) {
            // An unknown version cannot be interpreted, and guessing could delete the wrong key.
            Log.w(TAG, "Claude P cleanup tombstone has an unknown version")
            return UNREADABLE_TOMBSTONE
        }

        val alias = record.deviceKeyAlias
        if (!isPlausibleAlias(alias)) {
            Log.w(TAG, "Claude P cleanup tombstone has an implausible alias")
            return UNREADABLE_TOMBSTONE
        }

        return ClaudePCleanupTombstone(version = record.version, deviceKeyAlias = alias)
    }

    /** Stages beside the target and replaces atomically. Returns `false` when it could not. */
    override suspend fun write(tombstone: ClaudePCleanupTombstone): Boolean {
        if (!isPlausibleAlias(tombstone.deviceKeyAlias)) return false

        return try {
            if (!directory.exists() && !directory.mkdirs()) return false

            val payload = json.encodeToString(
                StoredTombstone(
                    version = tombstone.version,
                    deviceKeyAlias = tombstone.deviceKeyAlias,
                ),
            )
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
            // fallback: a half-written tombstone is worse than none, because the coordinator treats
            // a failed write as "do not start deleting".
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

    /** Bounded shape check, so an arbitrary string cannot become a deletion target. */
    private fun isPlausibleAlias(alias: String): Boolean =
        alias.isNotEmpty() && alias.length <= MAX_ALIAS_LENGTH && ALIAS_PATTERN.matches(alias)

    private companion object {
        const val TAG = "ClaudePTombstone"
        const val DIRECTORY_NAME = "claude_p"
        const val FILE_NAME = "cleanup.tombstone"
        const val MAX_ALIAS_LENGTH = 128

        /**
         * The alias shape `ClaudePPairingClient` produces: a fixed prefix plus a base64url suffix.
         * Anything else is refused rather than handed to `deleteEntry`.
         */
        val ALIAS_PATTERN = Regex("^[A-Za-z0-9_.-]{1,128}$")

        /**
         * Stands in for a record that exists but cannot be read.
         *
         * The empty alias is not a valid alias — [isPlausibleAlias] rejects it — so the coordinator
         * cannot mistake this for a key it may delete, while still seeing that *something* is
         * pending and keeping the device non-dispatchable.
         */
        val UNREADABLE_TOMBSTONE = ClaudePCleanupTombstone(deviceKeyAlias = "")
    }
}

/**
 * On-disk shape.
 *
 * Private to this file: the tombstone is storage, not a domain type, and keeping its serialization
 * here means nothing else can accidentally encode one.
 */
@kotlinx.serialization.Serializable
private data class StoredTombstone(
    @kotlinx.serialization.SerialName("version") val version: Int,
    @kotlinx.serialization.SerialName("device_key_alias") val deviceKeyAlias: String,
)
