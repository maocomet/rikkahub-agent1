package me.rerere.ai.provider.claudep

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Device identity, as the gateway knows it: an opaque id plus a short-lived access credential.
 *
 * This pair is the *only* thing a connection presents. It is not a Claude credential, it grants no
 * access to the user's Claude subscription, and it is revocable from either end
 * (`claudep/01-architecture-and-trust-boundaries.md` §4).
 */
data class ClaudePDeviceAccess(
    val deviceId: String,
    val credential: ClaudePAccessCredential,
    /** Unix epoch seconds. A credential at or past this instant is not used. */
    val expiresAtEpochSeconds: Long,
) {
    fun isExpired(nowEpochSeconds: Long): Boolean =
        expiresAtEpochSeconds <= 0 || nowEpochSeconds >= expiresAtEpochSeconds

    override fun toString(): String =
        "ClaudePDeviceAccess(deviceId=${deviceId.redactedRef()}, " +
            "credential=<redacted>, expiresAtEpochSeconds=$expiresAtEpochSeconds)"
}

/**
 * Supplies the credential for a connection.
 *
 * Returns `null` — never a placeholder, never an empty credential — whenever the device is not
 * paired, the credential has expired, or the store cannot be read. Every `null` becomes a fail-closed
 * "not paired" at the call site, so a broken credential store can never turn into an unauthenticated
 * connection attempt.
 */
fun interface ClaudePDeviceAccessProvider {
    suspend fun currentAccess(nowEpochSeconds: Long): ClaudePDeviceAccess?
}

/**
 * The device's non-exportable signing key.
 *
 * The private half never leaves the Android Keystore; this interface only ever exposes a signature
 * and the public key. Both return `null` rather than throwing when the key is missing or has been
 * invalidated (a Keystore key can be permanently invalidated by a device-credential change), because
 * "the key is gone" is an ordinary, expected state that must map onto "unpaired", not onto an
 * exception that some caller might swallow.
 */
interface ClaudePDeviceKey {
    /** Keystore alias this key lives under. */
    val keyAlias: String

    /** X.509 SubjectPublicKeyInfo (DER) of the public key, or `null` when unavailable. */
    suspend fun publicKeyDer(): ByteArray?

    /** Signature over [payload] using SHA-256 with ECDSA P-256, or `null` when unavailable. */
    suspend fun sign(payload: ByteArray): ByteArray?
}

/**
 * Creates, loads and destroys device keys.
 *
 * Separate from [ClaudePDeviceCredentialStore] on purpose: the key is long-lived and never leaves the
 * Keystore, while the credential is short-lived and *is* stored as ciphertext. Conflating them would
 * make it easy to write code that treats one as the other.
 *
 * ### Why creating and loading are separate operations
 *
 * An earlier revision exposed a single `loadOrCreate`. That is a fail-*open* shape: the runtime
 * handshake would happily mint a brand-new device key whenever the real one was missing — after a
 * restore, a Keystore invalidation, or a manual wipe — and then present an existing device credential
 * alongside a key that never authorised it. The gateway would see a valid credential with a
 * mismatched signature, and the device would keep believing it was paired.
 *
 * The two directions are therefore distinct, and callers must choose deliberately:
 *
 * - [createFresh] — **pairing only.** Always a new identity; a re-pairing must never silently reuse a
 *   revoked one.
 * - [loadExisting] — **runtime only.** Never creates. A missing key means "not paired", full stop.
 */
interface ClaudePDeviceKeyStore {
    /**
     * Creates a **new** key under [keyAlias], replacing anything already there.
     *
     * Only [ClaudePPairingClient] may call this. A re-pairing intentionally produces a new device
     * identity: silently reusing the previous key would resurrect an identity the gateway may have
     * revoked.
     *
     * Returns `null` when no key could be produced — the pairing must fail rather than continue with
     * an identity it cannot prove possession of.
     */
    suspend fun createFresh(keyAlias: String): ClaudePDeviceKey?

    /**
     * Loads the key under [keyAlias] **without creating one**.
     *
     * Returns `null` when the alias is absent, its entry is not a usable private key, or the
     * Keystore refuses it (a key can be permanently invalidated by a device-credential change).
     * Every one of those means "this device cannot prove its identity" and must be treated as
     * unpaired — never as a reason to mint a replacement.
     */
    suspend fun loadExisting(keyAlias: String): ClaudePDeviceKey?

    /**
     * Deletes the key under [keyAlias].
     *
     * Returns `true` only when the alias is gone afterwards. A `false` return is a real outcome that
     * callers must surface: an unpair that could not delete the private key has not finished, and
     * reporting success would leave material on the device that a later pairing could reuse.
     */
    suspend fun delete(keyAlias: String): Boolean
}

/**
 * Everything persisted for one paired device.
 *
 * [accessCredential] is the only secret here, and it is written to the encrypted credential store —
 * never to ordinary settings, never to a backup, never to a QR export. `toString` is redacted so the
 * secret cannot escape through a log line that formats the whole object.
 */
data class ClaudePPairedDevice(
    val deviceId: String,
    val deviceName: String,
    /** Alias of the Keystore key this pairing was made with. */
    val keyAlias: String,
    val accessCredential: String,
    val accessExpiresAtEpochSeconds: Long,
    val gatewayFingerprint: String,
    val gatewayInstallationId: String,
    /** Normalized origin the pairing was made against. */
    val pairedOrigin: String,
) {
    override fun toString(): String =
        "ClaudePPairedDevice(deviceId=${deviceId.redactedRef()}, deviceName=$deviceName, " +
            "keyAlias=${keyAlias.redactedRef()}, accessCredential=<redacted>, " +
            "accessExpiresAtEpochSeconds=$accessExpiresAtEpochSeconds, " +
            "gatewayFingerprint=${gatewayFingerprint.redactedRef()}, " +
            "gatewayInstallationId=${gatewayInstallationId.redactedRef()}, " +
            "pairedOrigin=${pairedOrigin.redactedRef()})"
}

/**
 * Encrypted storage for one [ClaudePPairedDevice].
 *
 * Implementations must satisfy three properties, all of which are asserted against the in-memory fake
 * so the contract is testable without a device:
 *
 * 1. A read that cannot be decrypted reports [ClaudePCredentialRead.Unusable]; it never returns a
 *    partially-populated record.
 * 2. [clear] removes the credential *and* the key material, so an unpaired device has nothing left
 *    to authenticate with.
 * 3. Nothing here is written to a path that participates in backup or device transfer.
 */
interface ClaudePDeviceCredentialStore {
    suspend fun read(): ClaudePCredentialRead

    /**
     * Durably stores [device].
     *
     * Returns the failures that occurred; an empty list means the record is stored and readable. A
     * non-empty list is **not** a soft warning: the caller must compensate, because a caller that
     * treats a failed write as success would go on to tell the user the device is paired while the
     * credential is absent, truncated, or half-written.
     */
    suspend fun write(device: ClaudePPairedDevice): List<ClaudePCredentialStoreFailure>

    /**
     * Removes the credential.
     *
     * Returns the failures that occurred; an empty list means nothing is left to authenticate with.
     * A deletion that failed must be reported rather than swallowed — a device that still holds a
     * valid credential has not been unpaired, whatever the settings say.
     */
    suspend fun clear(): List<ClaudePCredentialStoreFailure>
}

/** Something a credential-store operation should have done and, as far as it can tell, did not. */
enum class ClaudePCredentialStoreFailure {
    /** The ciphertext could not be staged to its temporary file. */
    TEMP_WRITE_FAILED,

    /** The staged file could not be moved into place, so the previous record is unchanged. */
    REPLACE_FAILED,

    /** The stored ciphertext could not be deleted. */
    FILE_DELETE_FAILED,

    /** The Keystore wrapping key could not be deleted. */
    WRAPPING_KEY_DELETE_FAILED,
}

/** Outcome of reading the credential store. */
sealed interface ClaudePCredentialRead {
    /** Never paired, or explicitly unpaired. */
    data object Absent : ClaudePCredentialRead

    data class Present(val device: ClaudePPairedDevice) : ClaudePCredentialRead

    /**
     * A record exists but cannot be used.
     *
     * This is the backup-restore and key-invalidation case: settings still claim the device is
     * paired, but the credential cannot be decrypted or its Keystore key is gone. Callers must
     * collapse this to "unpaired" — `claudep/03-security-and-operations.md` §10 requires that a
     * restored device is not mistaken for a paired one.
     */
    data class Unusable(val reason: ClaudePCredentialFailure) : ClaudePCredentialRead
}

/** Why a stored credential could not be used. Stable enum. */
enum class ClaudePCredentialFailure {
    /** Ciphertext exists but did not authenticate / decrypt. */
    DECRYPTION_FAILED,

    /** The Keystore key that protected it is gone or permanently invalidated. */
    KEY_INVALIDATED,

    /** Stored bytes were not a well-formed record. */
    MALFORMED_RECORD,

    /** The store itself could not be read. */
    STORE_UNAVAILABLE,
}

/**
 * Signs the `client.hello` transcript.
 *
 * The signature proves possession of the device key at connection time; the credential proves the
 * *gateway* authorized this device. Both are required — a stolen credential without the key cannot
 * complete a handshake,
 * which is what stops a bearer token alone from being a durable credential.
 */
object ClaudePHandshakeTranscript {
    private const val DOMAIN = "rikkahub-claude-p-handshake-v1"

    /**
     * Builds the exact bytes that get signed.
     *
     * Every field is length-prefixed. Concatenating raw strings would let two different handshakes
     * collide — `("ab", "c")` and `("a", "bc")` — and a signature that covers the wrong transcript is
     * a signature that proves the wrong thing.
     */
    fun build(
        deviceId: String,
        nonce: String,
        gatewayAuthority: String,
        appVersion: String,
    ): ByteArray = buildString {
        append(lengthPrefixed(DOMAIN))
        append(lengthPrefixed(deviceId))
        append(lengthPrefixed(nonce))
        append(lengthPrefixed(gatewayAuthority))
        append(lengthPrefixed(appVersion))
    }.toByteArray(Charsets.UTF_8)

    private fun lengthPrefixed(value: String): String = "${value.length}:$value"
}

/** Random, URL-safe, 128-bit-or-better values. One place, so nothing invents a weaker nonce. */
internal object ClaudePRandom {
    private const val NONCE_BYTES = 24
    private val random = SecureRandom()

    fun nonce(): String = base64Url(randomBytes(NONCE_BYTES))

    fun randomBytes(count: Int): ByteArray = ByteArray(count).also { random.nextBytes(it) }

    fun base64Url(bytes: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun fingerprintOf(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
}
