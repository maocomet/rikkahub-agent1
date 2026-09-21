package me.rerere.ai.provider.claudep

/**
 * What the settings screen is allowed to tell the user.
 *
 * This is a *derived* type, not stored state: [ClaudePUiStatusMapper] computes it from the pairing
 * record, the credential store and the live connection, in that order of authority. Keeping the
 * derivation pure means the one property that matters most — **an unpaired or unreachable device is
 * never shown as usable** — is a total function that can be enumerated in a test, instead of a set of
 * `if` statements spread across a composable.
 */
enum class ClaudePUiStatus {
    /** No usable device identity. No request can be sent. */
    NOT_PAIRED,

    /** A pairing exchange is in flight. */
    PAIRING,

    /** Paired, but no connection has been attempted yet. */
    PAIRED,

    /** Connecting, or mid-handshake. */
    CONNECTING,

    /** Handshake completed. Requests may be issued. */
    ONLINE,

    /** Paired but not reachable — disconnected, reconnecting, or out of retries. */
    OFFLINE,

    /** The gateway speaks something this build cannot honour. Stable until the user acts. */
    PROTOCOL_ERROR,

    /** The device identity is unusable or was refused. The user must re-pair. */
    CREDENTIAL_INVALID,
}

/**
 * Derives [ClaudePUiStatus].
 *
 * Order of precedence, and why:
 *
 * 1. **An in-flight pairing wins**, so the button that started it shows progress.
 * 2. **A missing or unusable credential beats everything else.** A connection state of `READY` with
 *    no readable credential is a contradiction, and resolving it in favour of "online" would be the
 *    exact fail-open this whole layer exists to prevent.
 * 3. **Only then does the connection state matter**, and only for a device that is genuinely paired.
 */
object ClaudePUiStatusMapper {
    fun map(
        settingsState: ClaudePPairingState,
        credentialRead: ClaudePCredentialRead,
        connectionState: ClaudePConnectionState,
        pairingInFlight: Boolean,
        nowEpochSeconds: Long,
    ): ClaudePUiStatus {
        if (pairingInFlight) return ClaudePUiStatus.PAIRING

        val resolution = ClaudePPairingResolver.resolve(settingsState, credentialRead, nowEpochSeconds)
        if (resolution is ClaudePPairingResolution.Unpaired) {
            return when (resolution.reason) {
                // A credential that exists but cannot be used is a distinct, actionable message:
                // "re-pair", not "pair".
                ClaudePUnpairedReason.CREDENTIAL_UNUSABLE,
                ClaudePUnpairedReason.CREDENTIAL_EXPIRED,
                ClaudePUnpairedReason.REVOKED,
                -> ClaudePUiStatus.CREDENTIAL_INVALID

                ClaudePUnpairedReason.NEVER_PAIRED,
                ClaudePUnpairedReason.CREDENTIAL_MISSING,
                -> ClaudePUiStatus.NOT_PAIRED
            }
        }

        return when (connectionState) {
            ClaudePConnectionState.CONNECTING,
            ClaudePConnectionState.HANDSHAKING,
            -> ClaudePUiStatus.CONNECTING

            ClaudePConnectionState.READY -> ClaudePUiStatus.ONLINE

            ClaudePConnectionState.PROTOCOL_ERROR -> ClaudePUiStatus.PROTOCOL_ERROR

            ClaudePConnectionState.CREDENTIAL_INVALID -> ClaudePUiStatus.CREDENTIAL_INVALID

            // A fresh, paired device that has not been contacted yet is "paired", not "offline" —
            // the distinction is what stops the screen from implying a failure that has not happened.
            ClaudePConnectionState.DISCONNECTED -> ClaudePUiStatus.PAIRED

            ClaudePConnectionState.RECONNECTING,
            ClaudePConnectionState.OFFLINE,
            -> ClaudePUiStatus.OFFLINE
        }
    }
}

/**
 * Whether the provider may be enabled, and therefore whether the agent loop may select it.
 *
 * Only [ClaudePUiStatus.ONLINE] and [ClaudePUiStatus.PAIRED] are usable: a provider that is enabled
 * while offline would appear in model pickers and fail at request time, which is the "pretend to be a
 * working provider" failure `claudep/00-scope-and-product-contract.md` §6 rules out.
 */
val ClaudePUiStatus.allowsDispatch: Boolean
    get() = this == ClaudePUiStatus.ONLINE || this == ClaudePUiStatus.PAIRED

/** Whether the user should be offered a pairing action. */
val ClaudePUiStatus.offersPairing: Boolean
    get() = this == ClaudePUiStatus.NOT_PAIRED || this == ClaudePUiStatus.CREDENTIAL_INVALID

/** Whether the user should be offered an unpair action. */
val ClaudePUiStatus.offersUnpair: Boolean
    get() = this != ClaudePUiStatus.NOT_PAIRED && this != ClaudePUiStatus.PAIRING
