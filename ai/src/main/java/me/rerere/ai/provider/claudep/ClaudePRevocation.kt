package me.rerere.ai.provider.claudep

/**
 * The pairing state a revocation attempt is allowed to leave behind.
 *
 * ### Why there are two "not paired" states, not one
 *
 * `NOT_PAIRED` reads as "this device holds nothing". `REVOKED` reads as "this device must not be
 * scheduled **and** may still hold material". Those are different claims, and only the second is
 * honest while a deletion has failed.
 *
 * An earlier revision moved straight to `NOT_PAIRED` at the end of every unpair. When the credential
 * file or the private key survived — a locked file, a Keystore that refused, a process death
 * mid-cleanup — the settings said the device was clean while it was not, and the user had no way to
 * find out or to retry.
 *
 * So the rule is: **`NOT_PAIRED` is earned, not assumed.** It is reachable only when every deletion
 * was confirmed. Otherwise the device stays `REVOKED`, which the resolver already treats as
 * undispatchable, and the UI offers a retry.
 *
 * Both states refuse to dispatch, so this is not a safety relaxation — it is a correctness one, and
 * it is what makes "cleanup failed" a recoverable condition rather than a silent lie.
 */
object ClaudePRevocation {
    /**
     * The state to persist after a revocation attempt.
     *
     * @param cleanupFailures everything that could not be removed. Empty means the device is clean.
     */
    fun stateAfterCleanup(cleanupFailures: List<ClaudePUnpairFailure>): ClaudePPairingState =
        if (cleanupFailures.isEmpty()) {
            ClaudePPairingState.NOT_PAIRED
        } else {
            ClaudePPairingState.REVOKED
        }

    /**
     * Whether a retry should be offered.
     *
     * True while the device is `REVOKED` — that state *means* "a cleanup did not finish", so the
     * affordance and the state cannot disagree.
     */
    fun offersCleanupRetry(state: ClaudePPairingState): Boolean = state == ClaudePPairingState.REVOKED
}

/** Something an unpair was supposed to remove and did not. */
enum class ClaudePUnpairFailure {
    /** The ciphertext file survived. */
    CREDENTIAL_FILE_NOT_DELETED,

    /** The Keystore AES wrapping key survived, so a recovered ciphertext would still decrypt. */
    CREDENTIAL_WRAPPING_KEY_NOT_DELETED,

    /** The device signing key survived. */
    DEVICE_KEY_NOT_DELETED,

    /**
     * No readable record named the key to destroy, so it could not even be attempted.
     *
     * This is the case a cleanup tombstone exists for: without one, a device whose credential became
     * unreadable has no way to discover which private key it left behind.
     */
    DEVICE_KEY_ALIAS_UNKNOWN,

    /** A staged file was left behind. */
    TEMP_FILE_NOT_DELETED,
}

/** What remained after an unpair attempt. An empty list means nothing is left. */
data class ClaudePUnpairResult(val failures: List<ClaudePUnpairFailure>) {
    val isComplete: Boolean get() = failures.isEmpty()

    /** The state this result permits. See [ClaudePRevocation]. */
    val resultingState: ClaudePPairingState get() = ClaudePRevocation.stateAfterCleanup(failures)
}
