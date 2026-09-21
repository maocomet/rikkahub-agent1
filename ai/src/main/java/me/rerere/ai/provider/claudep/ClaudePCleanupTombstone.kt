package me.rerere.ai.provider.claudep

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The minimum needed to finish a cleanup after the app has been restarted.
 *
 * ### Why this exists
 *
 * A device key alias is randomly generated per pairing attempt, and it is normally recovered from the
 * credential record. But the case that matters is precisely the one where the credential is *gone* or
 * unreadable while the private key survived — a partially failed unpair, a wipe, a Keystore that
 * refused. Without a record that outlives the credential, the app would restart, find nothing to
 * delete, and have to admit it cannot locate the material it left behind.
 *
 * ### What it deliberately does not contain
 *
 * No ticket, no credential, no private or public key material, no gateway URL, no token, no device
 * identity, and nothing that could be replayed to authorise anything. It holds an alias — a *name*,
 * meaningless without Keystore access on this device — and a format version.
 *
 * It lives in a Claude P private directory under `noBackupFilesDir` and never enters settings, a QR
 * export, a WebDAV archive or a log line.
 */
@Serializable
data class ClaudePCleanupTombstone(
    /** Bumped only if the shape changes; an unknown version is refused rather than guessed at. */
    @SerialName("version") val version: Int = CURRENT_VERSION,
    @SerialName("device_key_alias") val deviceKeyAlias: String,
) {
    /** Redacted: an alias names key material, so it is not rendered. */
    override fun toString(): String =
        "ClaudePCleanupTombstone(version=$version, deviceKeyAlias=<redacted>)"

    companion object {
        const val CURRENT_VERSION: Int = 1
    }
}

/**
 * Durable storage for a pending cleanup.
 *
 * Every method reports success rather than throwing, because each has a caller that must decide what
 * is safe to do next:
 *
 * - [write] failing means the app does not know which key to destroy, so it must **not** begin the
 *   destructive cleanup — deleting the credential while losing the only record of the alias would
 *   make the leftover key permanently unlocatable.
 * - [clear] failing means a completed cleanup cannot be recorded, so the device must stay in the
 *   non-dispatchable state rather than claim it is finished.
 */
interface ClaudePCleanupTombstoneStore {
    /**
     * Reads the pending cleanup, or `null` when there is none.
     *
     * Implementations must return `null` for an unreadable, corrupt or unknown-version record rather
     * than throwing: a tombstone is a hint about leftover material, and failing to parse it must not
     * take down the provider.
     */
    suspend fun read(): ClaudePCleanupTombstone?

    /** Atomically persists [tombstone]. Returns `false` when it could not be stored durably. */
    suspend fun write(tombstone: ClaudePCleanupTombstone): Boolean

    /** Removes the tombstone. Returns `true` only when it is gone. */
    suspend fun clear(): Boolean
}

/**
 * The non-secret pairing state the coordinator needs to read and write.
 *
 * An interface rather than a direct dependency on the settings store, because the whole point of
 * putting the coordinator in `ai` is that it can then be driven by tests without Android. The app
 * layer implements it over `SettingsStore`.
 */
interface ClaudePPairingSettingsGateway {
    /** Current persisted pairing state. */
    suspend fun pairingState(): ClaudePPairingState

    /** Non-secret metadata recorded at pairing time, or `null` when unpaired. */
    suspend fun pairedMetadata(): ClaudePPairedMetadata?

    /** Records a successful pairing. Returns `false` when the write failed. */
    suspend fun markPaired(device: ClaudePPairedDevice): Boolean

    /**
     * Moves to the non-dispatchable `REVOKED` state **and disables the provider**.
     *
     * Disabling is part of the state, not a separate courtesy: a `REVOKED` provider that is still
     * enabled is one settings-merge away from being schedulable.
     */
    suspend fun markRevoked(): Boolean

    /** Moves to `NOT_PAIRED` and clears the paired metadata. Only legal once cleanup is complete. */
    suspend fun markNotPaired(): Boolean
}

/** The non-secret fields persisted alongside a pairing. */
data class ClaudePPairedMetadata(
    val pairedOrigin: String,
    val gatewayFingerprint: String,
    val gatewayInstallationId: String,
    val deviceId: String,
)
