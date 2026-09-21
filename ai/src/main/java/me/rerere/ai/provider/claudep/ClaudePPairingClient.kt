package me.rerere.ai.provider.claudep

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString

/**
 * Runs one pairing exchange and returns the device identity it produced.
 *
 * The client owns every decision that protects the user's gateway from a hostile QR, and every
 * decision that protects the user from being paired to a gateway they did not choose:
 *
 * 1. **Expiry first.** A stale ticket is refused locally rather than presented
 *    (`claudep/03-security-and-operations.md` §2).
 * 2. **One attempt at a time.** A [Mutex] serializes pairing, so two concurrent attempts at the
 *    same QR cannot both reach the gateway — the second finds the ticket already consumed.
 * 3. **Local single-use guard.** A ticket that already produced a pairing is refused, keyed by the
 *    ticket's *digest* so the guard never stores the ticket itself.
 * 4. **Possession proof.** The device signs a transcript binding origin, ticket, public key, state
 *    and challenge. The gateway can therefore verify the device holds the key it is registering,
 *    and no captured proof is reusable elsewhere.
 * 5. **Response validation** — echoed `state`, protocol, and gateway identity. A response that does
 *    not match the invitation is discarded, credential and all.
 * 6. **Nothing left behind on failure.** If the exchange fails or is cancelled, the device key
 *    created for it is destroyed, so an abandoned pairing leaves no key material and no credential.
 *
 * Persisting the returned [ClaudePPairedDevice] is deliberately *not* this class's job — see
 * `ClaudePDevicePairingRepository`, which is the single owner of paired state.
 */
class ClaudePPairingClient(
    private val transport: ClaudePPairingTransport,
    private val keyStore: ClaudePDeviceKeyStore,
    private val appVersion: String,
    private val consumedTickets: ClaudePConsumedTicketGuard = InMemoryConsumedTicketGuard(),
    /**
     * Alias for the device key. Must be stable for the lifetime of a pairing, and must be
     * *different* for a re-pairing so a revoked identity cannot be silently reused.
     */
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) {
    private val pairingMutex = Mutex()

    /**
     * Pairs this device against [invitation].
     *
     * Cancelling this call is safe and leaves nothing behind: the key created for the attempt is
     * destroyed, and no credential is written anywhere, because this class never writes one.
     */
    suspend fun pair(
        invitation: ClaudePPairingInvitation,
        deviceName: String,
        nowEpochSeconds: Long,
    ): ClaudePPairingOutcome = pairingMutex.withLock {
        if (invitation.isExpired(nowEpochSeconds)) {
            return@withLock ClaudePPairingOutcome.Rejected(ClaudePPairingFailure.EXPIRED_TICKET)
        }

        val ticketDigest = invitation.ticket.digest()
        if (consumedTickets.isConsumed(ticketDigest)) {
            return@withLock ClaudePPairingOutcome.Rejected(ClaudePPairingFailure.TICKET_ALREADY_USED)
        }

        val key = keyStore.loadOrCreate(keyAlias)
            ?: return@withLock ClaudePPairingOutcome.Rejected(ClaudePPairingFailure.KEY_UNAVAILABLE)
        val publicKey = key.publicKeyDer()
            ?: return@withLock ClaudePPairingOutcome.Rejected(ClaudePPairingFailure.KEY_UNAVAILABLE)

        val state = ClaudePRandom.nonce()
        val challenge = ClaudePRandom.nonce()
        val publicKeyEncoded = ClaudePRandom.base64Url(publicKey)

        val transcript = ClaudePPairingTranscript.build(
            origin = invitation.endpoint.origin,
            ticket = invitation.ticket.value,
            devicePublicKeyBase64Url = publicKeyEncoded,
            state = state,
            challenge = challenge,
            appVersion = appVersion,
        )
        val proof = key.sign(transcript)
            ?: return@withLock ClaudePPairingOutcome.Rejected(ClaudePPairingFailure.KEY_UNAVAILABLE)

        val request = ClaudePPairingWireRequest(
            protocol = ClaudePProtocol.PROTOCOL_ID,
            appVersion = appVersion,
            deviceName = deviceName,
            devicePublicKey = publicKeyEncoded,
            ticket = invitation.ticket.value,
            state = state,
            challenge = challenge,
            proof = ClaudePRandom.base64Url(proof),
        )

        // From here on, any exit other than a completed pairing destroys the key. A half-finished
        // pairing must not leave a private key behind for a gateway that never authorised it.
        var paired = false
        try {
            val response = transport.send(request)
            val outcome = response.toOutcome(invitation, deviceName, keyAlias, state)
            if (outcome is ClaudePPairingOutcome.Paired) {
                paired = true
                consumedTickets.consume(ticketDigest)
            }
            return@withLock outcome
        } finally {
            if (!paired) {
                // `NonCancellable` because this cleanup runs on the cancellation path too: a user
                // who cancels pairing must still get their key material destroyed, and a suspending
                // cleanup would otherwise be skipped exactly when it matters.
                withContext(NonCancellable) {
                    runCatching { keyStore.delete(keyAlias) }
                }
            }
        }
    }

    private suspend fun ClaudePPairingTransportResult.toOutcome(
        invitation: ClaudePPairingInvitation,
        deviceName: String,
        keyAlias: String,
        expectedState: String,
    ): ClaudePPairingOutcome {
        val responded = this as? ClaudePPairingTransportResult.Responded
            ?: return ClaudePPairingOutcome.Rejected(ClaudePPairingFailure.TRANSPORT_FAILED)

        if (responded.httpStatus !in 200..299) {
            return ClaudePPairingOutcome.Rejected(ClaudePPairingFailure.SERVER_REFUSED)
        }

        val body = try {
            ClaudePProtocol.json.decodeFromString<ClaudePPairingWireResponse>(responded.body)
        } catch (_: Exception) {
            return ClaudePPairingOutcome.Rejected(ClaudePPairingFailure.MALFORMED_RESPONSE)
        }

        // The pairing equivalent of an OAuth `state` check. Without it, a response captured from
        // another pairing attempt could be fed to this one and the device would adopt the wrong
        // device id and credential.
        if (body.state != expectedState) {
            return ClaudePPairingOutcome.Rejected(ClaudePPairingFailure.STATE_MISMATCH)
        }

        if (!ClaudePProtocol.isCompatible(body.protocol)) {
            return ClaudePPairingOutcome.Rejected(ClaudePPairingFailure.PROTOCOL_MISMATCH)
        }

        // The user confirmed *this* gateway's fingerprint when they scanned the QR. A response
        // naming a different one means the origin answered for someone else's installation, and
        // accepting it would pair the device to a gateway the user never chose.
        val responseFingerprint = ClaudePPairingInvitationParser.normalizeFingerprint(body.gatewayFingerprint)
        if (responseFingerprint == null || responseFingerprint != invitation.gatewayFingerprint) {
            return ClaudePPairingOutcome.Rejected(ClaudePPairingFailure.GATEWAY_MISMATCH)
        }

        if (body.deviceId.isBlank() ||
            body.accessCredential.isBlank() ||
            body.accessExpiresAtEpochSeconds <= 0
        ) {
            return ClaudePPairingOutcome.Rejected(ClaudePPairingFailure.MALFORMED_RESPONSE)
        }

        return ClaudePPairingOutcome.Paired(
            ClaudePPairedDevice(
                deviceId = body.deviceId,
                deviceName = body.deviceName.ifBlank { deviceName },
                keyAlias = keyAlias,
                accessCredential = body.accessCredential,
                accessExpiresAtEpochSeconds = body.accessExpiresAtEpochSeconds,
                gatewayFingerprint = responseFingerprint,
                gatewayInstallationId = body.gatewayInstallationId,
                pairedOrigin = invitation.endpoint.origin,
            ),
        )
    }

    private companion object {
        /**
         * Fixed alias rather than a random one.
         *
         * A re-pairing must replace the previous identity, and the Keystore replaces a key stored
         * under the same alias — which is exactly the behaviour wanted here. A random alias per
         * attempt would silently accumulate orphaned private keys.
         */
        const val DEFAULT_KEY_ALIAS = "rikkahub_claude_p_device_key_v1"
    }
}

/**
 * Remembers tickets that already produced a pairing.
 *
 * The **authoritative** single-use enforcement is server-side: the gateway stores only a ticket hash
 * and consumes it once. This guard is the local half — it stops a double tap, or a second attempt
 * racing the first, from presenting the same ticket twice, and it stops a ticket that already worked
 * from working again in this process.
 *
 * It stores digests, never tickets, so the guard itself cannot become a place a ticket leaks from.
 * It is intentionally in-memory: persisting ticket digests would leave metadata about consumptions on
 * disk for no benefit, because the short TTL and the server-side check both expire far sooner than
 * the app process.
 */
interface ClaudePConsumedTicketGuard {
    fun isConsumed(ticketDigest: String): Boolean

    fun consume(ticketDigest: String)
}

/** Bounded, in-memory implementation. Evicts oldest-first so it cannot grow without limit. */
class InMemoryConsumedTicketGuard(
    private val capacity: Int = DEFAULT_CAPACITY,
) : ClaudePConsumedTicketGuard {
    private val lock = Any()
    private val consumed = LinkedHashSet<String>()

    override fun isConsumed(ticketDigest: String): Boolean = synchronized(lock) {
        consumed.contains(ticketDigest)
    }

    override fun consume(ticketDigest: String) {
        synchronized(lock) {
            if (consumed.size >= capacity) {
                consumed.remove(consumed.first())
            }
            consumed.add(ticketDigest)
        }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 64
    }
}

/** Result of a pairing attempt. */
sealed interface ClaudePPairingOutcome {
    data class Paired(val device: ClaudePPairedDevice) : ClaudePPairingOutcome

    data class Rejected(val reason: ClaudePPairingFailure) : ClaudePPairingOutcome
}

/** Why pairing did not produce a device. Stable enum — safe to show and to log. */
enum class ClaudePPairingFailure {
    /** The invitation's own expiry had already passed. */
    EXPIRED_TICKET,

    /** This ticket already produced a pairing (locally, or the gateway reported it spent). */
    TICKET_ALREADY_USED,

    /** The device key was missing, unusable, or could not sign. */
    KEY_UNAVAILABLE,

    /** The gateway answered for a different installation than the QR named. */
    GATEWAY_MISMATCH,

    /** The response did not echo the attempt's `state`. */
    STATE_MISMATCH,

    /** The gateway speaks a different protocol family or major. */
    PROTOCOL_MISMATCH,

    /** The response was not a decodable pairing response, or lacked required fields. */
    MALFORMED_RESPONSE,

    /** The gateway answered with a non-2xx status. */
    SERVER_REFUSED,

    /** The exchange never completed. */
    TRANSPORT_FAILED,
}
