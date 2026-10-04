package me.rerere.ai.provider.claudep

data class ClaudePConfigureUiState(
    val status: ClaudePUiStatus,
    val cleanupPending: Boolean,
    val cleanupInFlight: Boolean,
) {
    val canScanPairingQr: Boolean
        get() = status.offersPairing && !cleanupPending && !cleanupInFlight

    val canUnpair: Boolean
        get() = status.offersUnpair && !cleanupInFlight

    val canRetryCleanup: Boolean
        get() = cleanupPending && !cleanupInFlight

    val canEnableProvider: Boolean
        get() = status.allowsDispatch && !cleanupPending
}

object ClaudePConfigureUi {
    fun reduce(
        status: ClaudePUiStatus,
        cleanupPending: Boolean,
        cleanupInFlight: Boolean,
    ) = ClaudePConfigureUiState(status, cleanupPending, cleanupInFlight)
}
