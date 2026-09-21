package me.rerere.ai.provider.claudep

/**
 * What the Claude P settings screen is allowed to do, derived rather than decided inline.
 *
 * ### Why this is not written in the composable
 *
 * The rules here are safety rules — "a cleanup is outstanding, so do not offer pairing", "the device
 * is not dispatchable, so the enable switch stays off" — and a rule expressed as expressions inside
 * a `@Composable` can only ever be verified by reading it. Keeping the derivation pure puts it under
 * the ordinary JVM tests, where every combination can be enumerated.
 *
 * The screen renders [ClaudePConfigureUiState]; it does not re-derive any of it.
 *
 * Nothing here duplicates the coordinator's protocol. It answers "what may the user be offered?",
 * never "what should be deleted?" — that question belongs to the coordinator alone.
 */
data class ClaudePConfigureUiState(
    val status: ClaudePUiStatus,
    /** A cleanup is outstanding — a tombstone exists, or the device is `REVOKED`. */
    val cleanupPending: Boolean,
    /** An unpair or retry is running. */
    val cleanupInFlight: Boolean,
) {
    /**
     * True when local material may still be on the device.
     *
     * Driven by [cleanupPending] — durable state — rather than by the last click's failure list, so
     * it survives a process restart and does not depend on the user having watched the result.
     */
    val cleanupIncomplete: Boolean get() = cleanupPending

    /**
     * Scanning is refused while a cleanup is outstanding.
     *
     * Pairing over unresolved material would orphan the previous attempt's private key: the new
     * credential points at a new alias, and the old one becomes unreachable and permanently
     * undeletable.
     */
    val canScanPairingQr: Boolean
        get() = status.offersPairing && !cleanupInFlight && !cleanupIncomplete

    val canUnpair: Boolean get() = status.offersUnpair && !cleanupInFlight

    /** Offered exactly while a cleanup is outstanding, and never twice at once. */
    val canRetryCleanup: Boolean get() = cleanupIncomplete && !cleanupInFlight

    /** The enable switch follows the derived status; a non-dispatchable device cannot be enabled. */
    val canEnableProvider: Boolean get() = status.allowsDispatch && !cleanupIncomplete

    /** Whether the gateway card is meaningful. Nothing paired means nothing to show. */
    val showsGatewayDetails: Boolean get() = status.offersUnpair && !cleanupIncomplete

    /**
     * The message category to render, or `null`.
     *
     * A bounded enum rather than a string: the screen owns the wording and the localisation, and no
     * alias, path, exception text, credential or gateway detail can reach the user through it.
     */
    val notice: ClaudePConfigureNotice?
        get() = when {
            cleanupIncomplete -> ClaudePConfigureNotice.CLEANUP_INCOMPLETE
            status == ClaudePUiStatus.CREDENTIAL_INVALID -> ClaudePConfigureNotice.CREDENTIAL_INVALID
            status == ClaudePUiStatus.PROTOCOL_ERROR -> ClaudePConfigureNotice.PROTOCOL_ERROR
            else -> null
        }
}

/** Bounded, non-sensitive categories the screen may show. */
enum class ClaudePConfigureNotice {
    /** The connection is disabled but local material could not be fully removed. */
    CLEANUP_INCOMPLETE,

    /** The device identity is unusable and the user must pair again. */
    CREDENTIAL_INVALID,

    /** The gateway speaks something this build cannot honour. */
    PROTOCOL_ERROR,
}

/** Derives [ClaudePConfigureUiState] from the repository's published state. */
object ClaudePConfigureUi {
    fun reduce(
        status: ClaudePUiStatus,
        cleanupPending: Boolean,
        cleanupInFlight: Boolean,
    ): ClaudePConfigureUiState = ClaudePConfigureUiState(
        status = status,
        cleanupPending = cleanupPending,
        cleanupInFlight = cleanupInFlight,
    )
}
