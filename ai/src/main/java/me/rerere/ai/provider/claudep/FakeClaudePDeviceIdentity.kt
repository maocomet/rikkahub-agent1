package me.rerere.ai.provider.claudep

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import kotlinx.serialization.encodeToString

/**
 * In-memory device key store.
 *
 * **This is not the Android Keystore and is not evidence that the Keystore implementation works.**
 * It generates a genuine EC P-256 key pair with the JVM provider and signs with real ECDSA, so the
 * transport and pairing logic above it are exercised against correct cryptography — but the key is
 * extractable and lives in the heap. The Keystore-backed store is a separate class and needs its own
 * instrumentation evidence; a JVM test can never stand in for it.
 *
 * It exists so everything *else* — transcript construction, proof verification, failure teardown,
 * state handling — is testable deterministically on the JVM.
 */
class InMemoryClaudePDeviceKeyStore : ClaudePDeviceKeyStore {
    private val lock = Any()
    private val keys = mutableMapOf<String, InMemoryClaudePDeviceKey>()

    /** When set, every load reports "the key is gone", as a wiped Keystore would. */
    @Volatile
    var loadFails: Boolean = false

    /** Aliases created so far, oldest first — lets a test assert teardown removed the key. */
    val aliases: List<String>
        get() = synchronized(lock) { keys.keys.toList() }

    override suspend fun loadOrCreate(keyAlias: String): ClaudePDeviceKey? {
        if (loadFails) return null
        return synchronized(lock) {
            keys.getOrPut(keyAlias) { InMemoryClaudePDeviceKey(keyAlias) }
        }
    }

    override suspend fun delete(keyAlias: String) {
        synchronized(lock) { keys.remove(keyAlias) }
    }
}

/** One JVM-backed device key. */
class InMemoryClaudePDeviceKey(
    override val keyAlias: String,
) : ClaudePDeviceKey {
    private val keyPair = KeyPairGenerator.getInstance(KEY_ALGORITHM).apply {
        initialize(ECGenParameterSpec(CURVE))
    }.generateKeyPair()

    /** When set, signing fails — the "key was invalidated" path. */
    @Volatile
    var signFails: Boolean = false

    /** When set, the public key cannot be read. */
    @Volatile
    var publicKeyFails: Boolean = false

    /** Signatures produced, so a test can assert signing happened exactly once per attempt. */
    @Volatile
    var signCount: Int = 0
        private set

    override suspend fun publicKeyDer(): ByteArray? =
        if (publicKeyFails) null else keyPair.public.encoded

    override suspend fun sign(payload: ByteArray): ByteArray? {
        if (signFails) return null
        signCount += 1
        return Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(keyPair.private)
            update(payload)
            sign()
        }
    }

    /** Verifies a signature this key produced. Used by the fake gateway to check possession proofs. */
    fun verify(payload: ByteArray, signature: ByteArray): Boolean = try {
        Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initVerify(keyPair.public)
            update(payload)
            verify(signature)
        }
    } catch (_: Exception) {
        false
    }

    private companion object {
        const val KEY_ALGORITHM = "EC"
        const val CURVE = "secp256r1"
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    }
}

/** In-memory credential store, satisfying the same contract the encrypted one must. */
class InMemoryClaudePDeviceCredentialStore : ClaudePDeviceCredentialStore {
    @Volatile
    var stored: ClaudePPairedDevice? = null
        private set

    /** When set, reads report this failure instead of the stored record. */
    @Volatile
    var readFailure: ClaudePCredentialFailure? = null

    /** Writes performed — lets a test prove cancellation wrote nothing. */
    @Volatile
    var writeCount: Int = 0
        private set

    override suspend fun read(): ClaudePCredentialRead {
        readFailure?.let { return ClaudePCredentialRead.Unusable(it) }
        return stored?.let { ClaudePCredentialRead.Present(it) } ?: ClaudePCredentialRead.Absent
    }

    override suspend fun write(device: ClaudePPairedDevice) {
        writeCount += 1
        readFailure = null
        stored = device
    }

    override suspend fun clear() {
        stored = null
        readFailure = null
    }
}

/**
 * Deterministic pairing gateway.
 *
 * It enforces the server-side half of the pairing contract so the client cannot pass by being
 * permissive:
 *
 * - **single use** — a ticket that already produced a pairing is refused, so a replay is observable;
 * - **possession proof** — the submitted signature is verified against the submitted public key over
 *   the reconstructed transcript, so a client that signs the wrong bytes fails;
 * - **state echo** — the response echoes the request's state unless a test deliberately breaks it.
 *
 * It performs no I/O and reads no clock.
 */
class FakeClaudePPairingTransport(
    /** Fingerprint this fabricated gateway claims. Must match the invitation for success. */
    val gatewayFingerprint: String,
    val gatewayInstallationId: String = "fake-installation-1",
    val protocolId: String = ClaudePProtocol.PROTOCOL_ID,
    /** Lifetime granted to the issued credential, in seconds from an arbitrary fixed epoch. */
    val credentialTtlSeconds: Long = 3600,
    /** Fixed "now" used for issued expiry, so nothing depends on the wall clock. */
    val issuedAtEpochSeconds: Long = 1_000_000,
    /** Overrides the echoed `state`, to model a mismatched or hostile response. */
    val stateTransform: (String) -> String = { it },
    /** Overrides the reported fingerprint, to model a gateway answering for someone else. */
    val fingerprintOverride: String? = null,
    /** When set, the transport fails before producing a response. */
    val failure: ClaudePPairingTransportFailure? = null,
    val httpStatus: Int = 200,
    /** Replaces the response body entirely, for malformed-response tests. */
    val rawBody: String? = null,
    /** When true, tickets are not remembered, so server-side single-use is not enforced. */
    val allowTicketReuse: Boolean = false,
    /**
     * Origin the gateway believes it is serving, used to reconstruct the signed transcript.
     *
     * A gateway knows its own origin; the client must have signed *that* one, so a proof produced
     * for a different origin fails to verify here.
     */
    val expectedOrigin: String = DEFAULT_ORIGIN,
    /**
     * When set, `send` suspends on this gate before answering.
     *
     * Lets a test hold one pairing exchange open while a second starts, without a sleep or a timing
     * assumption — the test decides exactly when the first exchange is allowed to complete.
     */
    val gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null,
) : ClaudePPairingTransport {

    /** Tickets this gateway has already spent — the server-side single-use record. */
    private val spentTickets = mutableSetOf<String>()

    @Volatile
    var sendCount: Int = 0
        private set

    /** Set when a request arrived with an unverifiable possession proof. */
    @Volatile
    var proofRejected: Boolean = false
        private set

    val requests: MutableList<ClaudePPairingWireRequest> = mutableListOf()

    override suspend fun send(request: ClaudePPairingWireRequest): ClaudePPairingTransportResult {
        sendCount += 1
        requests += request

        gate?.await()

        failure?.let { return ClaudePPairingTransportResult.Failed(it) }

        if (!allowTicketReuse && !spentTickets.add(request.ticket)) {
            // The ticket was already exchanged. This is the replay the client's guard and the
            // gateway's single-use record both exist to stop.
            return ClaudePPairingTransportResult.Responded(409, ERROR_BODY)
        }

        if (!verifyProof(request)) {
            proofRejected = true
            return ClaudePPairingTransportResult.Responded(403, ERROR_BODY)
        }

        return ClaudePPairingTransportResult.Responded(
            httpStatus,
            rawBody ?: defaultBody(request),
        )
    }

    /**
     * Rebuilds the transcript the client should have signed and verifies the signature against the
     * public key the client submitted.
     *
     * Reconstruction is the point: it proves the client signed bytes that bind the origin, ticket,
     * key, state and challenge — a client that signed something simpler (or nothing) fails here, and
     * so would a client whose transcript is ambiguous and could be replayed elsewhere.
     */
    private fun verifyProof(request: ClaudePPairingWireRequest): Boolean {
        val publicKey = try {
            KeyFactory.getInstance(KEY_ALGORITHM)
                .generatePublic(X509EncodedKeySpec(decodeBase64Url(request.devicePublicKey)))
        } catch (_: Exception) {
            return false
        }
        val transcript = ClaudePPairingTranscript.build(
            origin = expectedOrigin,
            ticket = request.ticket,
            devicePublicKeyBase64Url = request.devicePublicKey,
            state = request.state,
            challenge = request.challenge,
            appVersion = request.appVersion,
        )
        return try {
            Signature.getInstance(SIGNATURE_ALGORITHM).run {
                initVerify(publicKey)
                update(transcript)
                verify(decodeBase64Url(request.proof))
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun defaultBody(request: ClaudePPairingWireRequest): String =
        ClaudePProtocol.json.encodeToString(
            ClaudePPairingWireResponse(
                protocol = protocolId,
                state = stateTransform(request.state),
                deviceId = "device-1",
                deviceName = request.deviceName,
                accessCredential = "credential-abc123",
                accessExpiresAtEpochSeconds = issuedAtEpochSeconds + credentialTtlSeconds,
                gatewayFingerprint = fingerprintOverride ?: gatewayFingerprint,
                gatewayInstallationId = gatewayInstallationId,
            ),
        )

    companion object {
        const val DEFAULT_ORIGIN = "https://gateway.example.com"
        const val ERROR_BODY = """{"error":"refused"}"""

        private const val KEY_ALGORITHM = "EC"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

        private fun decodeBase64Url(value: String): ByteArray =
            java.util.Base64.getUrlDecoder().decode(value)
    }
}
