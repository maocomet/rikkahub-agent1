package me.rerere.rikkahub.data.claudep

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.claudep.ClaudePCredentialFailure
import me.rerere.ai.provider.claudep.ClaudePCredentialRead
import me.rerere.ai.provider.claudep.ClaudePCredentialStoreFailure
import me.rerere.ai.provider.claudep.ClaudePDeviceCredentialStore
import me.rerere.ai.provider.claudep.ClaudePPairedDevice

/**
 * The device access credential, encrypted at rest.
 *
 * ### Placement
 *
 * The ciphertext lives in `noBackupFilesDir` — the same location the Codex credential store uses, and
 * for the same reason: `claudep/03-security-and-operations.md` §8 requires that a Claude P credential
 * never enters a WebDAV backup or a device transfer. `noBackupFilesDir` is the platform-level
 * guarantee, so this holds even if the app's backup rules are later edited by mistake; the rules in
 * `res/xml/data_extraction_rules.xml` remain as a second, explicit layer.
 *
 * ### Fail-closed reads
 *
 * Every failure mode — a missing file, a truncated file, a GCM tag that does not authenticate, a
 * Keystore key that has been invalidated — becomes [ClaudePCredentialRead.Unusable] or
 * [ClaudePCredentialRead.Absent], never a partially-populated record and never an exception a caller
 * could swallow.
 *
 * ### Fail-closed writes and deletions
 *
 * [write] and [clear] **return** their failures instead of throwing or swallowing them, because both
 * have a caller that must compensate. An earlier revision wrote through `copyTo` (which truncates the
 * destination and is not atomic) and swallowed deletion errors, so a crash mid-write could leave a
 * half-record, and a failed unpair could leave a working credential on the device while reporting
 * success.
 *
 * This mirrors `CodexCredentialStore`'s AES/GCM-AndroidKeystore scheme deliberately rather than
 * introducing `androidx.security-crypto`, which the project does not depend on and which CP1-B may
 * not add.
 */
class EncryptedClaudePDeviceCredentialStore(
    context: Context,
    private val json: Json,
    /** Keystore alias for the AES key that wraps the credential. */
    private val encryptionKeyAlias: String = DEFAULT_ENCRYPTION_KEY_ALIAS,
) : ClaudePDeviceCredentialStore {

    private val file = File(context.noBackupFilesDir, FILE_NAME)
    private val temporaryFile = File(context.noBackupFilesDir, "$FILE_NAME.tmp")

    override suspend fun read(): ClaudePCredentialRead {
        if (!file.exists()) return ClaudePCredentialRead.Absent

        val record = try {
            val bytes = file.readBytes()
            if (bytes.size <= IV_SIZE) {
                return ClaudePCredentialRead.Unusable(ClaudePCredentialFailure.MALFORMED_RECORD)
            }
            val iv = bytes.copyOfRange(0, IV_SIZE)
            val ciphertext = bytes.copyOfRange(IV_SIZE, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, loadOrCreateEncryptionKey(), GCMParameterSpec(TAG_BITS, iv))
            json.decodeFromString<StoredDeviceRecord>(cipher.doFinal(ciphertext).decodeToString())
        } catch (t: Throwable) {
            // Distinguished by class name only: a decryption failure message can quote the
            // keystore's internals, and `AEADBadTagException` versus a missing alias is the
            // difference the caller actually needs.
            Log.w(TAG, "Claude P credential unreadable: ${t::class.java.simpleName}")
            return ClaudePCredentialRead.Unusable(
                if (isKeyProblem(t)) {
                    ClaudePCredentialFailure.KEY_INVALIDATED
                } else {
                    ClaudePCredentialFailure.DECRYPTION_FAILED
                },
            )
        }

        val device = record.toDomain() ?: run {
            // Structurally valid ciphertext whose contents are not a usable device record. Refusing
            // is the only safe reading: a record with a blank credential would authenticate nothing.
            return ClaudePCredentialRead.Unusable(ClaudePCredentialFailure.MALFORMED_RECORD)
        }
        return ClaudePCredentialRead.Present(device)
    }

    /**
     * Stages the ciphertext beside the real file and replaces the real file atomically.
     *
     * The staging file is created in the **same directory** as the target, which is what makes an
     * atomic move possible at all — `ATOMIC_MOVE` cannot be honoured across filesystems.
     *
     * If the move fails, the staged file is cleaned up and **the existing record is left untouched**;
     * the caller is told and compensates. A process death at any point leaves either the old record
     * or the new one, never a truncated mix.
     */
    override suspend fun write(device: ClaudePPairedDevice): List<ClaudePCredentialStoreFailure> {
        val plaintext = json.encodeToString(device.toRecord())

        val staged = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateEncryptionKey())
            val ciphertext = cipher.doFinal(plaintext.encodeToByteArray())
            temporaryFile.writeBytes(cipher.iv + ciphertext)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Claude P credential staging failed: ${t::class.java.simpleName}")
            false
        }

        if (!staged) {
            clearTemporaryFile()
            return listOf(ClaudePCredentialStoreFailure.TEMP_WRITE_FAILED)
        }

        val replaced = try {
            // `java.nio.file.Files.move` with ATOMIC_MOVE is the contract-backed way to replace a
            // file in place: on the POSIX filesystems Android uses it maps to `rename(2)`, which the
            // kernel guarantees is atomic. `File.renameTo` carries no such guarantee — it is
            // documented as platform-dependent and returns a bare boolean — so it must not be
            // described as atomic.
            //
            // There is deliberately **no** destructive fallback (delete-then-move). If an atomic
            // move is unavailable or fails, the previous record must stay readable; losing a
            // possibly-valid pairing to make room for one that never landed is the worse outcome.
            Files.move(
                temporaryFile.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            true
        } catch (t: Throwable) {
            // Includes AtomicMoveNotSupportedException and FileSystemException.
            Log.w(TAG, "Claude P credential replace failed: ${t::class.java.simpleName}")
            false
        }

        if (!replaced) {
            // The old record is still in place and still valid. Report rather than escalate to a
            // destructive fallback.
            clearTemporaryFile()
            return listOf(ClaudePCredentialStoreFailure.REPLACE_FAILED)
        }

        // Success: the staged name is gone. Cleanup is still called, because a failed rename on some
        // filesystems can leave a stale entry behind.
        clearTemporaryFile()
        return emptyList()
    }

    /**
     * Removes the ciphertext **and** the Keystore wrapping key.
     *
     * Both matter. Deleting only the ciphertext leaves the AES key, so a later `write` reuses it and
     * anything that recovered the old bytes could still decrypt them. Ordering is chosen so that the
     * failure modes are safe: the ciphertext goes first, so even if the key deletion fails, the
     * credential is no longer readable through this app.
     */
    override suspend fun clear(): List<ClaudePCredentialStoreFailure> {
        val failures = mutableListOf<ClaudePCredentialStoreFailure>()

        clearTemporaryFile()

        try {
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Claude P credential file could not be deleted")
                failures += ClaudePCredentialStoreFailure.FILE_DELETE_FAILED
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Claude P credential file deletion threw: ${t::class.java.simpleName}")
            failures += ClaudePCredentialStoreFailure.FILE_DELETE_FAILED
        }

        if (!deleteKeyStoreEntry(encryptionKeyAlias)) {
            failures += ClaudePCredentialStoreFailure.WRAPPING_KEY_DELETE_FAILED
        }

        return failures
    }

    /** Removes a staged file if one is lying around. Best-effort by design; never throws. */
    private fun clearTemporaryFile() {
        try {
            if (temporaryFile.exists()) temporaryFile.delete()
        } catch (t: Throwable) {
            Log.w(TAG, "Claude P temporary file cleanup failed: ${t::class.java.simpleName}")
        }
    }

    private fun deleteKeyStoreEntry(alias: String): Boolean = try {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        !keyStore.containsAlias(alias)
    } catch (t: Throwable) {
        Log.w(TAG, "Claude P keystore entry deletion failed: ${t::class.java.simpleName}")
        false
    }

    private fun isKeyProblem(t: Throwable): Boolean =
        t is java.security.UnrecoverableKeyException ||
            t is java.security.KeyStoreException ||
            t::class.java.simpleName.contains("KeyPermanentlyInvalidated") ||
            t is java.security.InvalidKeyException

    private fun loadOrCreateEncryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(encryptionKeyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    encryptionKeyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            generateKey()
        }
    }

    internal companion object {
        const val TAG = "ClaudePCredentialStore"
        const val FILE_NAME = "claude_p_device.enc"

        /**
         * Distinct from the Codex alias so unpairing one feature can never destroy the other's
         * credential.
         */
        const val DEFAULT_ENCRYPTION_KEY_ALIAS = "rikkahub_claude_p_credential_v1"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128
    }
}

/**
 * On-disk shape.
 *
 * Deliberately a private DTO rather than making [ClaudePPairedDevice] itself `@Serializable`: the
 * domain object holds a secret, and giving it a serializer makes it one accidental `encodeToString`
 * away from landing in settings JSON, a QR export or a log line.
 */
@Serializable
private data class StoredDeviceRecord(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("key_alias") val keyAlias: String,
    @SerialName("access_credential") val accessCredential: String,
    @SerialName("access_expires_at") val accessExpiresAtEpochSeconds: Long,
    @SerialName("gateway_fingerprint") val gatewayFingerprint: String,
    @SerialName("gateway_installation_id") val gatewayInstallationId: String,
    @SerialName("paired_origin") val pairedOrigin: String,
) {
    /** Redacted: this is the one object that literally contains the credential. */
    override fun toString(): String =
        "StoredDeviceRecord(deviceId=<redacted>, deviceName=$deviceName, keyAlias=<redacted>, " +
            "accessCredential=<redacted>, accessExpiresAtEpochSeconds=$accessExpiresAtEpochSeconds, " +
            "gatewayFingerprint=<redacted>, gatewayInstallationId=<redacted>, pairedOrigin=<redacted>)"
}

private fun ClaudePPairedDevice.toRecord(): StoredDeviceRecord = StoredDeviceRecord(
    deviceId = deviceId,
    deviceName = deviceName,
    keyAlias = keyAlias,
    accessCredential = accessCredential,
    accessExpiresAtEpochSeconds = accessExpiresAtEpochSeconds,
    gatewayFingerprint = gatewayFingerprint,
    gatewayInstallationId = gatewayInstallationId,
    pairedOrigin = pairedOrigin,
)

/** Returns `null` when the record is structurally present but not a usable device identity. */
private fun StoredDeviceRecord.toDomain(): ClaudePPairedDevice? {
    if (deviceId.isBlank() || accessCredential.isBlank() || pairedOrigin.isBlank()) return null
    if (keyAlias.isBlank()) return null
    return ClaudePPairedDevice(
        deviceId = deviceId,
        deviceName = deviceName,
        keyAlias = keyAlias,
        accessCredential = accessCredential,
        accessExpiresAtEpochSeconds = accessExpiresAtEpochSeconds,
        gatewayFingerprint = gatewayFingerprint,
        gatewayInstallationId = gatewayInstallationId,
        pairedOrigin = pairedOrigin,
    )
}
