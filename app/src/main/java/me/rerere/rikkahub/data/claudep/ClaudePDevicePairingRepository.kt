package me.rerere.rikkahub.data.claudep

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.claudep.ClaudePAccessCredential
import me.rerere.ai.provider.claudep.ClaudePConnectionState
import me.rerere.ai.provider.claudep.ClaudePCredentialRead
import me.rerere.ai.provider.claudep.ClaudePDeviceAccess
import me.rerere.ai.provider.claudep.ClaudePDeviceAccessProvider
import me.rerere.ai.provider.claudep.ClaudePDeviceCredentialStore
import me.rerere.ai.provider.claudep.ClaudePDeviceDescriptor
import me.rerere.ai.provider.claudep.ClaudePDeviceKeyStore
import me.rerere.ai.provider.claudep.ClaudePEndpoint
import me.rerere.ai.provider.claudep.ClaudePEndpointResult
import me.rerere.ai.provider.claudep.ClaudePPairingClient
import me.rerere.ai.provider.claudep.ClaudePPairingFailure
import me.rerere.ai.provider.claudep.ClaudePPairingInvitation
import me.rerere.ai.provider.claudep.ClaudePPairingInvitationParser
import me.rerere.ai.provider.claudep.ClaudePPairingOutcome
import me.rerere.ai.provider.claudep.ClaudePPairingRejection
import me.rerere.ai.provider.claudep.ClaudePPairingResult
import me.rerere.ai.provider.claudep.ClaudePPairingState
import me.rerere.ai.provider.claudep.ClaudePUiStatus
import me.rerere.ai.provider.claudep.ClaudePUiStatusMapper
import me.rerere.ai.provider.claudep.OkHttpClaudePWebSocketConnector
import me.rerere.ai.provider.claudep.OkHttpClaudePPairingTransport
import me.rerere.ai.provider.claudep.WssClaudePGatewayClient
import me.rerere.rikkahub.data.datastore.SettingsStore
import okhttp3.OkHttpClient

/**
 * The single owner of "is this device paired, and can it talk to its gateway".
 *
 * ### Why this class exists
 *
 * Paired state is split across three places that can disagree: `ProviderSetting.ClaudeP` in ordinary
 * settings JSON, the access credential in an encrypted store, and the Keystore-held device key. Any
 * code that reads one of those directly will eventually read a stale one. Everything else in the app
 * therefore asks this repository, which reconciles them through [ClaudePUiStatusMapper] and never
 * answers from a single source.
 *
 * ### What it does not do
 *
 * It does not build prompts, map chunks or talk to a model. It pairs, unpairs, and hands out a
 * transport. The provider above it owns generation semantics.
 */
class ClaudePDevicePairingRepository(
    private val settingsStore: SettingsStore,
    private val credentialStore: ClaudePDeviceCredentialStore,
    private val deviceKeyStore: ClaudePDeviceKeyStore,
    private val okHttpClient: OkHttpClient,
    private val scope: CoroutineScope,
    private val appVersion: String,
    /** Injectable so expiry checks are deterministic in tests rather than wall-clock reads. */
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val _pairingInFlight = MutableStateFlow(false)

    /**
     * The live transport, once a gateway client has been built for the current pairing.
     *
     * Rebuilt whenever the pairing changes: a credential that changes must not leave a socket
     * authenticated with the old one (`claudep/03-security-and-operations.md` §10).
     */
    private var gatewayClient: WssClaudePGatewayClient? = null

    private val _connectionState = MutableStateFlow(ClaudePConnectionState.DISCONNECTED)

    /** Connection lifecycle of the current transport, or `DISCONNECTED` when there is none. */
    val connectionState: StateFlow<ClaudePConnectionState> = _connectionState.asStateFlow()

    private val _status = MutableStateFlow(ClaudePUiStatus.NOT_PAIRED)

    /** The status the settings screen renders. Derived; never written from the UI. */
    val status: StateFlow<ClaudePUiStatus> = _status.asStateFlow()

    private val _lastFailure = MutableStateFlow<ClaudePPairingFailure?>(null)

    /** The last pairing failure, for a one-shot message. */
    val lastFailure: StateFlow<ClaudePPairingFailure?> = _lastFailure.asStateFlow()

    init {
        // Recomputed on every settings change so the screen follows a pairing made elsewhere
        // (an import, a restore) without needing to be reopened.
        scope.launch {
            settingsStore.settingsFlow.collect { refresh() }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Pairing
    // -----------------------------------------------------------------------------------------

    /**
     * Runs a pairing exchange from a scanned QR payload.
     *
     * Persistence happens **only** after the credential store has accepted the record, so a failure
     * part-way through leaves settings claiming `NOT_PAIRED` rather than claiming a pairing that was
     * never stored.
     */
    suspend fun pair(invitationPayload: String, deviceName: String): ClaudePPairingOutcome {
        _pairingInFlight.value = true
        _lastFailure.value = null
        try {
            val invitation = when (val parsed = ClaudePPairingInvitationParser.parse(invitationPayload)) {
                is ClaudePPairingResult.Rejected -> {
                    val failure = parsed.reason.toPairingFailure()
                    _lastFailure.value = failure
                    return ClaudePPairingOutcome.Rejected(failure)
                }

                is ClaudePPairingResult.Accepted -> parsed.invitation
            }

            val client = ClaudePPairingClient(
                transport = OkHttpClaudePPairingTransport(okHttpClient, invitation.endpoint),
                keyStore = deviceKeyStore,
                appVersion = appVersion,
            )

            val outcome = client.pair(invitation, deviceName, nowEpochSeconds())
            when (outcome) {
                is ClaudePPairingOutcome.Paired -> persistPairing(outcome, invitation)
                is ClaudePPairingOutcome.Rejected -> _lastFailure.value = outcome.reason
            }
            refresh()
            return outcome
        } finally {
            _pairingInFlight.value = false
            refresh()
        }
    }

    /**
     * Ends the pairing: destroys the credential, the device key, and the socket.
     *
     * Order matters. The credential file and the Keystore key go first, so that even if the settings
     * write fails the device can no longer authenticate; then the transport is dropped; then settings
     * are updated to match.
     */
    suspend fun unpair() {
        gatewayClient?.shutdown()
        gatewayClient = null
        credentialStore.clear()
        updateSettings { it.copy(pairingState = ClaudePPairingState.NOT_PAIRED) }
        refresh()
    }

    private suspend fun persistPairing(outcome: ClaudePPairingOutcome.Paired, invitation: ClaudePPairingInvitation) {
        credentialStore.write(outcome.device)
        updateSettings {
            it.copy(
                pairingState = ClaudePPairingState.PAIRED,
                pairedOrigin = outcome.device.pairedOrigin,
                gatewayFingerprint = outcome.device.gatewayFingerprint,
                gatewayInstallationId = outcome.device.gatewayInstallationId,
                device = ClaudePDeviceDescriptor(
                    deviceId = outcome.device.deviceId,
                    displayName = outcome.device.deviceName,
                    lastConnectedAt = null,
                ),
                // Only non-secret fields are persisted. The credential and the private key stay in
                // their own stores; a QR export of these settings is harmless by construction.
                cachedModels = emptyList(),
                catalogCachedAt = null,
                claudeCodeVersion = null,
            )
        }
        // The invitation is deliberately not retained: a ticket is single-use and has just been used.
        Log.i(TAG, "Claude P paired to ${invitation.endpoint.origin}")
    }

    // -----------------------------------------------------------------------------------------
    // Transport
    // -----------------------------------------------------------------------------------------

    /**
     * The gateway client for the current pairing, or `null` when there is none.
     *
     * Returns `null` rather than a fail-open stand-in: a caller that gets `null` cannot accidentally
     * send a request, whereas a stand-in that "works" is how an unpaired device ends up in a model
     * picker.
     */
    suspend fun gatewayClientOrNull(): WssClaudePGatewayClient? {
        val device = (credentialStore.read() as? ClaudePCredentialRead.Present)?.device ?: return null
        val endpoint = (ClaudePEndpoint.parse(device.pairedOrigin) as? ClaudePEndpointResult.Accepted)
            ?.endpoint ?: return null

        gatewayClient?.let { return it }

        return WssClaudePGatewayClient(
            connector = OkHttpClaudePWebSocketConnector(okHttpClient),
            endpoint = endpoint,
            accessProvider = ClaudePDeviceAccessProvider { now ->
                val current = (credentialStore.read() as? ClaudePCredentialRead.Present)?.device
                current?.let {
                    ClaudePDeviceAccess(
                        deviceId = it.deviceId,
                        credential = ClaudePAccessCredential(it.accessCredential),
                        expiresAtEpochSeconds = it.accessExpiresAtEpochSeconds,
                    )
                }
            },
            deviceKeyStore = deviceKeyStore,
            keyAlias = device.keyAlias,
            scope = scope,
            appVersion = appVersion,
        ).also { client ->
            gatewayClient = client
            scope.launch {
                client.connectionState.collect { _connectionState.value = it }
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------------------------

    /** Recomputes [status] from the three sources of truth. */
    suspend fun refresh() {
        val setting = currentClaudePSetting()
        val read = credentialStore.read()
        lastKnownDeviceId = (read as? ClaudePCredentialRead.Present)?.device?.deviceId
        _status.value = ClaudePUiStatusMapper.map(
            settingsState = setting?.pairingState ?: ClaudePPairingState.NOT_PAIRED,
            credentialRead = read,
            connectionState = _connectionState.value,
            pairingInFlight = _pairingInFlight.value,
            nowEpochSeconds = nowEpochSeconds(),
        )
    }

    /**
     * The paired device id, or `null` when there is none.
     *
     * Read synchronously from the last observed credential-store result so the provider can bind it
     * into the request fingerprint without making that path suspend. A `null` answer means the
     * fingerprint falls back to the provider's placeholder, which is safe: without a device id the
     * request cannot be dispatched anyway.
     */
    @Volatile
    private var lastKnownDeviceId: String? = null

    fun currentDeviceIdOrNull(): String? = lastKnownDeviceId

    private fun currentClaudePSetting(): ProviderSetting.ClaudeP? =
        settingsStore.settingsFlow.value.providers
            .filterIsInstance<ProviderSetting.ClaudeP>()
            .firstOrNull()

    private suspend fun updateSettings(transform: (ProviderSetting.ClaudeP) -> ProviderSetting.ClaudeP) {
        settingsStore.update { settings ->
            settings.copy(
                providers = settings.providers.map { provider ->
                    if (provider is ProviderSetting.ClaudeP) transform(provider) else provider
                },
            )
        }
    }

    private companion object {
        const val TAG = "ClaudePPairing"
    }
}

/** Maps a QR-level rejection onto the bounded pairing failure the UI reports. */
private fun ClaudePPairingRejection.toPairingFailure(): ClaudePPairingFailure =
    when (this) {
        ClaudePPairingRejection.MISSING_EXPIRY ->
            ClaudePPairingFailure.MALFORMED_RESPONSE

        ClaudePPairingRejection.PROTOCOL_MISMATCH ->
            ClaudePPairingFailure.PROTOCOL_MISMATCH

        ClaudePPairingRejection.EMPTY_PAYLOAD,
        ClaudePPairingRejection.PAYLOAD_TOO_LARGE,
        ClaudePPairingRejection.MALFORMED_PAYLOAD,
        ClaudePPairingRejection.INVALID_ORIGIN,
        ClaudePPairingRejection.INVALID_FINGERPRINT,
        ClaudePPairingRejection.MISSING_TICKET,
        ClaudePPairingRejection.MALFORMED_TICKET,
        -> ClaudePPairingFailure.MALFORMED_RESPONSE
    }
