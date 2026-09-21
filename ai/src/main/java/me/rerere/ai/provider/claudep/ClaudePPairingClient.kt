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
    /**
     * Builds the transport for a specific gateway.
     *
     * A factory rather than a ready transport because the endpoint is only known once the QR has been
     * parsed — and because one client instance must outlive a single exchange. That is what makes the
     * mutex and the consumed-ticket guard in this class actually serialise concurrent attempts: an
     * earlier revision constructed a fresh client per call, so each one had its own mutex and its own
     * empty guard, and two taps on the same QR could both reach the gateway.
     */
    private val transportFor: (ClaudePEndpoint) -> ClaudePPairingTransport,
    private val keyStore: ClaudePDeviceKeyStore,
    private val appVersion: String,
    private val consumedTickets: ClaudePConsumedTicketGuard = InMemoryConsumedTicketGuard(),
    /** Prefix for the per-attempt device key alias. */
    private val keyAliasPrefix: String = KEY_ALIAS_PREFIX,
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

        // A **new** identity for every attempt. Reusing a fixed alias would mean two attempts share
        // one key, so a failing attempt's cleanup could delete the key a concurrently-succeeding
        // attempt just registered — and a re-pairing could silently resurrect a revoked identity.
        val attemptAlias = "$keyAliasPrefix${ClaudePRandom.base64Url(ClaudePRandom.randomBytes(ATTEMPT_ID_BYTES))}"

        val key = keyStore.createFresh(attemptAlias)
            ?: return@withLock ClaudePPairingOutcome.Rejected(ClaudePPairingFailure.KEY_UNAVAILABLE)
        val publicKey = key.publicKeyDer() ?: return@withLock ClaudePPairingOutcome
            .Rejected(ClaudePPairingFailure.KEY_UNAVAILABLE, cleanupAttempt(attemptAlias))

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
        val proof = key.sign(transcript) ?: return@withLock ClaudePPairingOutcome
            .Rejected(ClaudePPairingFailure.KEY_UNAVAILABLE, cleanupAttempt(attemptAlias))

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

        // From here on, any exit other than a completed pairing destroys *this attempt's* key. A
        // half-finished pairing must not leave a private key behind, and it must not touch any other
        // attempt's key.
        var paired = false
        var cleaned = false
        try {
            val response = transportFor(invitation.endpoint).send(request)
            val outcome = response.toOutcome(invitation, deviceName, attemptAlias, state)
            if (outcome is ClaudePPairingOutcome.Paired) {
                paired = true
                consumedTickets.consume(ticketDigest)
                return@withLock outcome
            }
            val failures = cleanupAttempt(attemptAlias)
            cleaned = true
            return@withLock (outcome as ClaudePPairingOutcome.Rejected).copy(cleanupFailures = failures)
        } finally {
            if (!paired && !cleaned) {
                // Reached only when the exchange threw or was cancelled. `NonCancellable` because a
                // user who cancels pairing must still get their key material destroyed, and a
                // suspending cleanup would otherwise be skipped exactly when it matters.
                withContext(NonCancellable) { cleanupAttempt(attemptAlias) }
            }
        }
    }

    /**
     * Destroys one attempt's key, reporting — rather than swallowing — a failure.
     *
     * A key that could not be deleted is inert on its own (no credential references this alias), but
     * silently ignoring the failure would make an unpair look complete when material remains.
     */
    private suspend fun cleanupAttempt(alias: String): List<ClaudePPairingCleanupFailure> =
        if (keyStore.delete(alias)) {
            emptyList()
        } else {
            listOf(ClaudePPairingCleanupFailure.KEY_NOT_DELETED)
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

    companion object {
        /**
         * Prefix for per-attempt device key aliases.
         *
         * A random suffix per attempt, rather than one fixed alias, so that:
         *
         * - a failed or cancelled attempt can only ever destroy its *own* key, never the key a
         *   concurrently-succeeding attempt just registered;
         * - a re-pairing cannot silently reuse a revoked identity.
         *
         * The suffix is persisted in the paired-device record, so the runtime side can load exactly
         * the key that pairing created.
         */
        const val KEY_ALIAS_PREFIX = "rikkahub_claude_p_device_key_v1_"

        /** 72 bits of attempt id: enough that two attempts cannot collide in practice. */
        private const val ATTEMPT_ID_BYTES = 9
    }
}

/** Something that should have been destroyed and, as far as the device can tell, was not. */
enum class ClaudePPairingCleanupFailure {
    /** The attempt's device key could not be deleted. */
    KEY_NOT_DELETED,
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

    /**
     * [cleanupFailures] is non-empty when something the attempt created could not be destroyed.
     * Callers must surface it rather than treat the attempt as cleanly abandoned.
     */
    data class Rejected(
        val reason: ClaudePPairingFailure,
        val cleanupFailures: List<ClaudePPairingCleanupFailure> = emptyList(),
    ) : ClaudePPairingOutcome
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

    /**
     * The exchange succeeded but the result could not be stored durably.
     *
     * A distinct code because it is the one failure where the gateway *did* authorise this device and
     * the app threw the result away. It is recoverable only by pairing again — the credential the
     * gateway just issued has been destroyed, and the ticket that produced it is spent.
     */
    PAIRING_NOT_PERSISTED,

    /**
     * A previous cleanup is unresolved — a tombstone exists that this build cannot interpret.
     *
     * Distinct from a generic failure because the remedy is different: the user must resolve the
     * cleanup (or reinstall) rather than simply retry pairing.
     */
    CLEANUP_PENDING,
}
