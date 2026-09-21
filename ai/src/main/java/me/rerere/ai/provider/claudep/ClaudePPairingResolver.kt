package me.rerere.ai.provider.claudep

/**
 * The single authority on whether this device is actually paired.
 *
 * Pairing state lives in two places that can disagree: `ProviderSetting.ClaudeP.pairingState` is
 * ordinary settings JSON, while the device credential lives in an encrypted store that a device
 * transfer, an app restore or a Keystore key invalidation can leave behind. `claudep/03-security-and-operations.md`
 * §10 requires that a restored device is **not** mistaken for a paired one, and §2 requires that a
 * credential that cannot be read degrades to "unpaired" rather than to "try anyway".
 *
 * So the rule is not "what does settings say?" but "what can this device actually prove?":
 *
 * | settings | credential store | result |
 * |---|---|---|
 * | `PAIRED` | usable, unexpired | **paired** |
 * | `PAIRED` | usable, expired | unpaired — expired |
 * | `PAIRED` | unusable | unpaired — unusable |
 * | `PAIRED` | absent | unpaired — missing |
 * | `NOT_PAIRED` | anything | unpaired — never paired |
 * | `REVOKED` | anything | unpaired — revoked |
 *
 * ### Settings state is authoritative, and an earlier revision had that backwards
 *
 * The first version let a present, usable credential outrank settings saying `NOT_PAIRED`, on the
 * theory that a half-applied settings write must not strand a genuinely paired device. That reasoning
 * no longer holds — pairing now compensates for a failed settings write by destroying the credential
 * it just stored — and it had become a genuine hazard: after an unpair whose deletion failed, a
 * surviving credential would make this function report "paired" while `gatewayClientOrNull` — which
 * requires settings to say `PAIRED` — refused to build a transport. Two authorities, disagreeing, and
 * the UI siding with the permissive one.
 *
 * So the rule is now the simple one: **if settings do not say `PAIRED`, the device is not paired.**
 * The credential can only ever *downgrade* that verdict (expired, unusable, missing), never upgrade it.
 * A lost settings write therefore fails closed into "re-pair", which is the safe direction.
 *
 * Pure and clock-injected, so it is exhaustively testable on the JVM — which matters, because this is
 * the function that decides whether the app is allowed to believe it holds a device identity.
 */
object ClaudePPairingResolver {
    fun resolve(
        settingsState: ClaudePPairingState,
        credentialRead: ClaudePCredentialRead,
        nowEpochSeconds: Long,
    ): ClaudePPairingResolution {
        // Revocation is explicit and wins over everything: the user (or the gateway) said stop.
        if (settingsState == ClaudePPairingState.REVOKED) {
            return ClaudePPairingResolution.Unpaired(ClaudePUnpairedReason.REVOKED)
        }

        // `NOT_PAIRED` means the device holds no pairing as far as the app is concerned. Whatever a
        // leftover credential says, there is no authority that will build a transport for it.
        if (settingsState == ClaudePPairingState.NOT_PAIRED) {
            return ClaudePPairingResolution.Unpaired(ClaudePUnpairedReason.NEVER_PAIRED)
        }

        return when (credentialRead) {
            is ClaudePCredentialRead.Present ->
                if (credentialRead.device.isExpired(nowEpochSeconds)) {
                    ClaudePPairingResolution.Unpaired(ClaudePUnpairedReason.CREDENTIAL_EXPIRED)
                } else {
                    ClaudePPairingResolution.Paired(credentialRead.device)
                }

            is ClaudePCredentialRead.Unusable ->
                ClaudePPairingResolution.Unpaired(ClaudePUnpairedReason.CREDENTIAL_UNUSABLE)

            ClaudePCredentialRead.Absent ->
                ClaudePPairingResolution.Unpaired(
                    if (settingsState == ClaudePPairingState.PAIRED) {
                        // Settings claim a pairing the device cannot back. This is the restore case:
                        // the settings row survived and the credential did not.
                        ClaudePUnpairedReason.CREDENTIAL_MISSING
                    } else {
                        ClaudePUnpairedReason.NEVER_PAIRED
                    },
                )
        }
    }
}

/** Outcome of [ClaudePPairingResolver.resolve]. */
sealed interface ClaudePPairingResolution {
    data class Paired(val device: ClaudePPairedDevice) : ClaudePPairingResolution

    /** Not paired. [reason] is a stable enum safe to log and to show. */
    data class Unpaired(val reason: ClaudePUnpairedReason) : ClaudePPairingResolution

    /** Convenience for the one question every caller actually asks. */
    val isPaired: Boolean get() = this is Paired
}

/** Why a device is not usable. Stable enum — never a decryption message. */
enum class ClaudePUnpairedReason {
    /** No pairing was ever completed. */
    NEVER_PAIRED,

    /** Settings recorded a pairing but the credential store has nothing — a restore, or a wipe. */
    CREDENTIAL_MISSING,

    /** A credential exists but could not be decrypted, or its Keystore key is gone. */
    CREDENTIAL_UNUSABLE,

    /** The access credential's lifetime has passed. The device must re-pair. */
    CREDENTIAL_EXPIRED,

    /** Explicitly revoked. */
    REVOKED,
}

/** True when the device's access credential is past its lifetime. */
internal fun ClaudePPairedDevice.isExpired(nowEpochSeconds: Long): Boolean =
    accessExpiresAtEpochSeconds <= 0 || nowEpochSeconds >= accessExpiresAtEpochSeconds
