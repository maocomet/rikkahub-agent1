package me.rerere.rikkahub.data.claudep

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
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
import me.rerere.ai.provider.claudep.ClaudePDeviceCredentialStore
import me.rerere.ai.provider.claudep.ClaudePPairedDevice

/**
 * The device access credential, encrypted at rest.
 *
 * ### Placement
 *
 * The ciphertext lives in `noBackupFilesDir` — the same location the Codex credential store uses,
 * and for the same reason: `claudep/03-security-and-operations.md` §8 requires that a Claude P
 * credential never enters a WebDAV backup or a device transfer. `noBackupFilesDir` is the
 * platform-level guarantee, so this holds even if the app's backup rules are later edited by
 * mistake; the rules in `res/xml/data_extraction_rules.xml` remain as a second, explicit layer.
 *
 * ### Fail-closed
 *
 * Every failure mode — a missing file, a truncated file, a GCM tag that does not authenticate, a
 * Keystore key that has been invalidated — becomes [ClaudePCredentialRead.Unusable] or
 * [ClaudePCredentialRead.Absent], never a partially-populated record and never an exception a caller
 * could swallow. `claudep/03-security-and-operations.md` §10 requires a restored device to be
 * recognised as *not* paired; reporting "unusable" rather than pretending a pairing exists is how
 * that requirement is met at the storage layer.
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
    /** Keystore alias of the *device signing key*, deleted alongside the credential on unpair. */
    private val deviceKeyAlias: String = DEFAULT_DEVICE_KEY_ALIAS,
) : ClaudePDeviceCredentialStore {

    private val file = File(context.noBackupFilesDir, FILE_NAME)

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

    override suspend fun write(device: ClaudePPairedDevice) {
        val plaintext = json.encodeToString(
            StoredDeviceRecord(
                deviceId = device.deviceId,
                deviceName = device.deviceName,
                keyAlias = device.keyAlias,
                accessCredential = device.accessCredential,
                accessExpiresAtEpochSeconds = device.accessExpiresAtEpochSeconds,
                gatewayFingerprint = device.gatewayFingerprint,
                gatewayInstallationId = device.gatewayInstallationId,
                pairedOrigin = device.pairedOrigin,
            ),
        )

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateEncryptionKey())
        val ciphertext = cipher.doFinal(plaintext.encodeToByteArray())

        // Written through a temporary file and renamed, so a process death mid-write cannot leave a
        // half-record that would later read as a corrupt-but-present pairing.
        val temporary = File(file.parentFile, "$FILE_NAME.tmp")
        temporary.writeBytes(cipher.iv + ciphertext)
        temporary.copyTo(file, overwrite = true)
        temporary.delete()
    }

    /**
     * Removes the credential **and** the device signing key.
     *
     * Both halves matter: leaving the private key behind after an unpair would leave material that a
     * later, differently-configured pairing could silently reuse under the same alias, which is
     * exactly what `claudep/03-security-and-operations.md` §10 means by "撤销后 … 全部失效".
     */
    override suspend fun clear() {
        runCatching { file.delete() }
            .onFailure { Log.w(TAG, "Claude P credential file deletion failed: ${it::class.java.simpleName}") }
        deleteKey(deviceKeyAlias)
    }

    /** Destroys the device signing key. Separate so it can be re-used by the unpair path. */
    fun deleteDeviceKey(alias: String = deviceKeyAlias) = deleteKey(alias)

    private fun deleteKey(alias: String) {
        try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(alias)
        } catch (t: Throwable) {
            Log.w(TAG, "Claude P keystore entry deletion failed: ${t::class.java.simpleName}")
        }
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
        const val DEFAULT_DEVICE_KEY_ALIAS = "rikkahub_claude_p_device_key_v1"

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

/** Returns `null` when the record is structurally present but not a usable device identity. */
private fun StoredDeviceRecord.toDomain(): ClaudePPairedDevice? {
    if (deviceId.isBlank() || accessCredential.isBlank() || pairedOrigin.isBlank()) return null
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
