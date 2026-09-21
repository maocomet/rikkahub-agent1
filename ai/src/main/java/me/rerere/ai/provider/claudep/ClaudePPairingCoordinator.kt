package me.rerere.ai.provider.claudep

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns every state change that can turn a device into, or out of, a paired one.
 *
 * ### Why one lock over the whole sequence
 *
 * `ClaudePPairingClient` already serialises its *exchange* with a mutex. That is not enough: the
 * window between the exchange returning and the repository finishing persistence was open, so a
 * second `pair()` or an `unpair()` could interleave there — pairing B could persist over pairing A's
 * partial state, or an unpair could revoke a device that was still being written.
 *
 * So there is exactly one [lifecycleMutex], held across the entire sequence for every operation that
 * touches pairing state:
 *
 * - input parsing, ticket exchange, credential/key/settings persistence, compensation
 * - revocation, cleanup, cleanup retry, tombstone handling
 *
 * The lock is held across suspension points, which is intentional — that *is* the serialisation. It
 * is never held across an unbounded wait: the exchange inside it is bounded by the pairing client's
 * own timeouts, and cancellation propagates normally.
 *
 * ### The revocation protocol
 *
 * Ordering is the safety property, not an implementation detail:
 *
 * 1. `REVOKED` + provider disabled — dispatch stops now, whatever happens later.
 * 2. Close the transport, so nothing in flight keeps talking.
 * 3. **Write the tombstone before any destructive step.** If this fails, nothing is deleted: losing
 *    the only record of the alias would make a surviving key unlocatable forever.
 * 4. Delete credential, temporary file, wrapping key, device key.
 * 5. Confirm each.
 * 6. Only when everything is confirmed: clear the tombstone, then `NOT_PAIRED`.
 * 7. Anything unconfirmed leaves `REVOKED` and the tombstone in place, with a bounded failure list.
 *
 * Step 3 before step 4 is what makes the failure modes recoverable rather than merely reported.
 */
class ClaudePPairingCoordinator(
    private val credentialStore: ClaudePDeviceCredentialStore,
    private val deviceKeyStore: ClaudePDeviceKeyStore,
    private val tombstoneStore: ClaudePCleanupTombstoneStore,
    private val settings: ClaudePPairingSettingsGateway,
    /**
     * Long-lived, so its own exchange mutex and consumed-ticket guard span calls. Unlike the
     * lifecycle mutex this one only covers the network exchange.
     */
    private val pairingClient: ClaudePPairingClient,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    /** Invoked under the lifecycle lock whenever the transport must be dropped or replaced. */
    private val onRevokeTransport: suspend () -> Unit = {},
) {
    private val lifecycleMutex = Mutex()

    /**
     * Runs one pairing, from QR payload to persisted state.
     *
     * Holds the lifecycle lock for the whole sequence, so a concurrent `pair` or `unpair` waits
     * rather than interleaving with persistence or compensation.
     */
    suspend fun pair(invitationPayload: String, deviceName: String): ClaudePPairingOutcome =
        lifecycleMutex.withLock {
            val invitation = when (val parsed = ClaudePPairingInvitationParser.parse(invitationPayload)) {
                is ClaudePPairingResult.Rejected ->
                    return@withLock ClaudePPairingOutcome.Rejected(parsed.reason.toPairingFailure())

                is ClaudePPairingResult.Accepted -> parsed.invitation
            }

            val outcome = pairingClient.pair(invitation, deviceName, nowEpochSeconds())
            when (outcome) {
                is ClaudePPairingOutcome.Rejected -> outcome
                is ClaudePPairingOutcome.Paired -> persistPaired(outcome.device)
            }
        }

    private suspend fun persistPaired(device: ClaudePPairedDevice): ClaudePPairingOutcome {
        val writeFailures = credentialStore.write(device)
        if (writeFailures.isNotEmpty()) {
            compensate(device)
            return ClaudePPairingOutcome.Rejected(ClaudePPairingFailure.PAIRING_NOT_PERSISTED)
        }

        if (!settings.markPaired(device)) {
            // The credential is on disk but nothing points at it. Destroy it rather than leave a
            // usable credential for a pairing the app does not believe happened.
            compensate(device)
            return ClaudePPairingOutcome.Rejected(ClaudePPairingFailure.PAIRING_NOT_PERSISTED)
        }

        // A successful pairing supersedes any pending cleanup: the stale alias belongs to a key no
        // credential will ever reference again, so it is destroyed once, best-effort, and the
        // tombstone is retired. This is what keeps a retry from damaging the *new* pairing.
        tombstoneStore.read()?.let { stale ->
            if (stale.deviceKeyAlias != device.keyAlias) {
                deviceKeyStore.delete(stale.deviceKeyAlias)
            }
            tombstoneStore.clear()
        }

        onRevokeTransport()
        return ClaudePPairingOutcome.Paired(device)
    }

    /** Destroys everything an unsuccessful persistence attempt created. */
    private suspend fun compensate(device: ClaudePPairedDevice) {
        credentialStore.clear()
        deviceKeyStore.delete(device.keyAlias)
    }

    /**
     * Revokes the device.
     *
     * Idempotent: repeating it re-runs the same confirmations and reaches the same verdict. Safe to
     * call when nothing is paired.
     */
    suspend fun unpair(): ClaudePUnpairResult = lifecycleMutex.withLock { revoke() }

    /**
     * Retries an incomplete cleanup.
     *
     * Deliberately a **user-initiated** action rather than an automatic retry loop: a cleanup that
     * keeps failing in the background would hide the problem, and the protocol's own remedy is a
     * deliberate retry the user can see. It is the same code path as [unpair], so a retry can never
     * reach a state an unpair could not.
     */
    suspend fun retryCleanup(): ClaudePUnpairResult = lifecycleMutex.withLock { revoke() }

    private suspend fun revoke(): ClaudePUnpairResult {
        // Read before revoking: whether there was anything to revoke decides whether a missing alias
        // is a finding or simply "there was never a device here".
        val hadPairing = settings.pairingState() == ClaudePPairingState.PAIRED

        // 1. Dispatch stops here, before anything else is attempted.
        settings.markRevoked()
        // 2. Nothing in flight keeps talking to the gateway.
        onRevokeTransport()

        // 3. The alias must be known *and recorded before* anything is destroyed.
        val existingTombstone = tombstoneStore.read()
        val alias = (credentialStore.read() as? ClaudePCredentialRead.Present)?.device?.keyAlias
            ?: existingTombstone?.deviceKeyAlias

        if (alias != null) {
            // Rewritten on every attempt so a retry after a restart still knows what to remove.
            val recorded = tombstoneStore.write(ClaudePCleanupTombstone(deviceKeyAlias = alias))
            if (!recorded) {
                // No record, no destructive step: deleting the credential now would lose the only
                // pointer to a key that may still exist.
                return finishWith(ClaudePUnpairResult(listOf(ClaudePUnpairFailure.TOMBSTONE_WRITE_FAILED)))
            }
        }

        // 4/5. Delete, then confirm each item.
        val failures = mutableListOf<ClaudePUnpairFailure>()
        credentialStore.clear().forEach { failures += it.toUnpairFailure() }
        if (alias != null && !deviceKeyStore.delete(alias)) {
            failures += ClaudePUnpairFailure.DEVICE_KEY_NOT_DELETED
        }
        // An unnamable key is only a *finding* if there was something to revoke. Unpairing a device
        // that was never paired has nothing to clean, and reporting a failure there would leave a
        // clean device stuck in REVOKED with a retry that could never succeed.
        if (alias == null && (hadPairing || existingTombstone != null)) {
            failures += ClaudePUnpairFailure.DEVICE_KEY_ALIAS_UNKNOWN
        }

        val result = ClaudePUnpairResult(failures.distinct())
        if (!result.isComplete) {
            // 7. Anything unconfirmed keeps the device revoked and the tombstone in place.
            return finishWith(result)
        }

        // 6. Only a fully confirmed cleanup may retire the tombstone and rest a clean device.
        if (alias != null && !tombstoneStore.clear()) {
            return finishWith(ClaudePUnpairResult(listOf(ClaudePUnpairFailure.TOMBSTONE_DELETE_FAILED)))
        }

        return finishWith(result)
    }

    private suspend fun finishWith(result: ClaudePUnpairResult): ClaudePUnpairResult {
        when (result.resultingState) {
            ClaudePPairingState.NOT_PAIRED -> settings.markNotPaired()
            // Any other state is `REVOKED`, which was already written at the start.
            else -> settings.markRevoked()
        }
        return result
    }

    /**
     * True when the device must not reach a gateway.
     *
     * `REVOKED` always, and also when a tombstone exists — a leftover tombstone means a cleanup did
     * not finish, and a device in that condition has no business dispatching.
     */
    suspend fun mustNotDispatch(): Boolean =
        settings.pairingState() == ClaudePPairingState.REVOKED || tombstoneStore.read() != null
}

/** Maps a credential-store failure onto the revocation vocabulary. */
private fun ClaudePCredentialStoreFailure.toUnpairFailure(): ClaudePUnpairFailure = when (this) {
    ClaudePCredentialStoreFailure.FILE_DELETE_FAILED,
    ClaudePCredentialStoreFailure.REPLACE_FAILED,
    -> ClaudePUnpairFailure.CREDENTIAL_FILE_NOT_DELETED

    ClaudePCredentialStoreFailure.WRAPPING_KEY_DELETE_FAILED ->
        ClaudePUnpairFailure.CREDENTIAL_WRAPPING_KEY_NOT_DELETED

    ClaudePCredentialStoreFailure.TEMP_WRITE_FAILED,
    // The store reports a stray staging file through the same channel; it is still something that
    // did not get removed, so it must not be reported as a clean result.
    -> ClaudePUnpairFailure.TEMP_FILE_NOT_DELETED
}

/** Maps a QR-level rejection onto the bounded pairing failure the UI reports. */
internal fun ClaudePPairingRejection.toPairingFailure(): ClaudePPairingFailure = when (this) {
    ClaudePPairingRejection.PROTOCOL_MISMATCH -> ClaudePPairingFailure.PROTOCOL_MISMATCH

    ClaudePPairingRejection.EMPTY_PAYLOAD,
    ClaudePPairingRejection.PAYLOAD_TOO_LARGE,
    ClaudePPairingRejection.MALFORMED_PAYLOAD,
    ClaudePPairingRejection.INVALID_ORIGIN,
    ClaudePPairingRejection.INVALID_FINGERPRINT,
    ClaudePPairingRejection.MISSING_TICKET,
    ClaudePPairingRejection.MALFORMED_TICKET,
    ClaudePPairingRejection.MISSING_EXPIRY,
    -> ClaudePPairingFailure.MALFORMED_RESPONSE
}
