package me.rerere.rikkahub.data.claudep

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
import me.rerere.ai.provider.claudep.ClaudePGatewayClient
import me.rerere.ai.provider.claudep.ClaudePPairedDevice
import me.rerere.ai.provider.claudep.ClaudePPairingClient
import me.rerere.ai.provider.claudep.ClaudePPairingFailure
import me.rerere.ai.provider.claudep.ClaudePPairingInvitationParser
import me.rerere.ai.provider.claudep.ClaudePPairingOutcome
import me.rerere.ai.provider.claudep.ClaudePPairingRejection
import me.rerere.ai.provider.claudep.ClaudePPairingResult
import me.rerere.ai.provider.claudep.ClaudePPairingState
import me.rerere.ai.provider.claudep.ClaudePRevocation
import me.rerere.ai.provider.claudep.ClaudePUnpairFailure
import me.rerere.ai.provider.claudep.ClaudePUnpairResult
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
 * settings JSON, the access credential in an encrypted store, and the Keystore-held device key. Code
 * that reads one of them directly will eventually read a stale one. Everything else asks this
 * repository, which reconciles all three and never answers from a single source.
 *
 * ### What it owns, and why ownership matters
 *
 * - **Pairing serialisation.** Exactly one [ClaudePPairingClient] instance exists for the lifetime of
 *   the repository, so its mutex and its consumed-ticket guard span every call. An earlier revision
 *   built a fresh client per `pair()`, which gave each call its own mutex and its own empty guard —
 *   so two taps on one QR could both reach the gateway.
 * - **Transport lifetime.** At most one [WssClaudePGatewayClient] exists, keyed to the exact device
 *   identity it was built for. A change of credential, key or origin destroys it rather than letting
 *   a socket keep authenticating with state the app no longer believes.
 * - **Revocation authority.** [unpair] moves the logical state to a non-dispatchable one *before*
 *   touching any storage, so a cleanup failure can never leave a dispatchable device behind.
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
    private val _connectionState = MutableStateFlow(ClaudePConnectionState.DISCONNECTED)
    private val _status = MutableStateFlow(ClaudePUiStatus.NOT_PAIRED)
    private val _lastFailure = MutableStateFlow<ClaudePPairingFailure?>(null)
    private val _lastCleanupFailures = MutableStateFlow<List<ClaudePPairingCleanupFailure>>(emptyList())

    /** Connection lifecycle of the current transport, or `DISCONNECTED` when there is none. */
    val connectionState: StateFlow<ClaudePConnectionState> = _connectionState.asStateFlow()

    /** The status the settings screen renders. Derived; never written from the UI. */
    val status: StateFlow<ClaudePUiStatus> = _status.asStateFlow()

    /** The last pairing failure, for a one-shot message. */
    val lastFailure: StateFlow<ClaudePPairingFailure?> = _lastFailure.asStateFlow()

    /** Failures from the most recent unattempted-cleanup, non-empty only when something leaked. */
    val lastCleanupFailures: StateFlow<List<ClaudePPairingCleanupFailure>> =
        _lastCleanupFailures.asStateFlow()

    /**
     * The one pairing client. Built once and reused, which is what makes its mutex and its
     * consumed-ticket guard span calls.
     */
    private val pairingClient: ClaudePPairingClient by lazy {
        ClaudePPairingClient(
            // Endpoint is only known after the QR is parsed, so the transport is built per attempt —
            // but the *client* stays, and it is the client that serialises.
            transportFor = { endpoint -> OkHttpClaudePPairingTransport(okHttpClient, endpoint) },
            keyStore = deviceKeyStore,
            appVersion = appVersion,
        )
    }

    /** The live transport, with the exact identity it was built for. */
    private var cachedClient: CachedGatewayClient? = null

    /** Last device id seen from a readable credential; used for the request fingerprint. */
    @Volatile
    private var lastKnownDeviceId: String? = null

    /** Last device key alias seen; lets [unpair] clean up even if the credential became unreadable. */
    @Volatile
    private var lastKnownKeyAlias: String? = null

    init {
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
     * ### Compensation
     *
     * A successful exchange is not a successful pairing. If the credential cannot be stored
     * durably, or the settings write fails afterwards, everything this attempt created is destroyed
     * and the outcome is reported as a failure — because a credential that exists without settings
     * agreeing, or settings that claim a pairing with no credential, both leave the user with a
     * provider that cannot work and cannot explain why.
     */
    suspend fun pair(invitationPayload: String, deviceName: String): ClaudePPairingOutcome {
        _pairingInFlight.value = true
        _lastFailure.value = null
        _lastCleanupFailures.value = emptyList()
        refreshStatus()
        try {
            val invitation = when (val parsed = ClaudePPairingInvitationParser.parse(invitationPayload)) {
                is ClaudePPairingResult.Rejected -> return reject(parsed.reason.toPairingFailure())
                is ClaudePPairingResult.Accepted -> parsed.invitation
            }

            val outcome = pairingClient.pair(invitation, deviceName, nowEpochSeconds())
            when (outcome) {
                is ClaudePPairingOutcome.Rejected -> {
                    _lastCleanupFailures.value = outcome.cleanupFailures
                    if (outcome.cleanupFailures.isNotEmpty()) {
                        Log.w(TAG, "Claude P pairing cleanup incomplete: ${outcome.cleanupFailures}")
                    }
                    return reject(outcome.reason)
                }

                is ClaudePPairingOutcome.Paired -> return persist(outcome.device)
            }
        } finally {
            _pairingInFlight.value = false
            refresh()
        }
    }

    private suspend fun persist(device: ClaudePPairedDevice): ClaudePPairingOutcome {
        val writeFailures = credentialStore.write(device)
        if (writeFailures.isNotEmpty()) {
            Log.w(TAG, "Claude P credential not stored: $writeFailures")
            compensate(device)
            return reject(ClaudePPairingFailure.PAIRING_NOT_PERSISTED)
        }

        val settingsWritten = try {
            updateSettings {
                it.copy(
                    pairingState = ClaudePPairingState.PAIRED,
                    pairedOrigin = device.pairedOrigin,
                    gatewayFingerprint = device.gatewayFingerprint,
                    gatewayInstallationId = device.gatewayInstallationId,
                    device = ClaudePDeviceDescriptor(
                        deviceId = device.deviceId,
                        displayName = device.deviceName,
                        lastConnectedAt = null,
                    ),
                    // Only non-secret fields are persisted. The credential and the private key stay
                    // in their own stores, so a QR export of these settings is harmless.
                    cachedModels = emptyList(),
                    catalogCachedAt = null,
                    claudeCodeVersion = null,
                )
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Claude P settings write failed: ${t::class.java.simpleName}")
            false
        }

        if (!settingsWritten) {
            // The credential is on disk but nothing points at it. Destroy it rather than leave a
            // usable credential for a pairing the app does not believe happened.
            compensate(device)
            return reject(ClaudePPairingFailure.PAIRING_NOT_PERSISTED)
        }

        // A new identity supersedes the old one: a socket built for the previous credential must not
        // survive it.
        closeCachedClient()
        _lastFailure.value = null
        Log.i(TAG, "Claude P paired")
        return ClaudePPairingOutcome.Paired(device)
    }

    /** Best-effort destruction of everything an unsuccessful persistence attempt created. */
    private suspend fun compensate(device: ClaudePPairedDevice) {
        val failures = buildList {
            addAll(credentialStore.clear())
            if (!deviceKeyStore.delete(device.keyAlias)) add(ClaudePPairingCleanupFailure.KEY_NOT_DELETED)
        }
        if (failures.isNotEmpty()) {
            _lastCleanupFailures.value = failures
            Log.w(TAG, "Claude P pairing compensation incomplete: $failures")
        }
    }

    private fun reject(reason: ClaudePPairingFailure): ClaudePPairingOutcome {
        _lastFailure.value = reason
        return ClaudePPairingOutcome.Rejected(reason)
    }

    // -----------------------------------------------------------------------------------------
    // Revocation
    // -----------------------------------------------------------------------------------------

    /**
     * Ends the pairing.
     *
     * ### Ordering is the safety property
     *
     * 1. **Logical state first.** Settings move to `REVOKED` and the provider is disabled *before*
     *    any storage is touched. [gatewayClientOrNull] only ever builds a transport for a device whose
     *    settings say `PAIRED`, so from this instant nothing can dispatch — even if every physical
     *    deletion below fails.
     * 2. **Then the socket**, so an in-flight generation is not left talking to the gateway.
     * 3. **Then the storage**, collecting failures rather than swallowing them.
     * 4. **Then `NOT_PAIRED`**, the honest resting state once the attempt is over.
     *
     * The returned failures mean "something remains on this device". They are surfaced to the caller
     * because an unpair that could not delete a private key has not finished, and reporting success
     * would be a lie the user acts on.
     */
    suspend fun unpair(): ClaudePUnpairResult {
        val alias = lastKnownKeyAlias
            ?: (credentialStore.read() as? ClaudePCredentialRead.Present)?.device?.keyAlias

        updateSettings { it.copy(pairingState = ClaudePPairingState.REVOKED, enabled = false) }
        closeCachedClient()
        refreshStatus()

        val failures = mutableListOf<ClaudePUnpairFailure>()
        credentialStore.clear().forEach { failures += it.toUnpairFailure() }

        if (alias != null) {
            if (!deviceKeyStore.delete(alias)) {
                failures += ClaudePUnpairFailure.DEVICE_KEY_NOT_DELETED
            }
        } else {
            // Nothing readable told us which alias to destroy. Reported rather than assumed away.
            failures += ClaudePUnpairFailure.DEVICE_KEY_ALIAS_UNKNOWN
        }

        val result = ClaudePUnpairResult(failures)

        // `NOT_PAIRED` is earned, not assumed: it is written only when every deletion was confirmed.
        // Otherwise the device stays `REVOKED` — still undispatchable, but honest that material may
        // remain, and offering a retry. Moving straight to `NOT_PAIRED` would tell the user the
        // device is clean while a credential or private key could still be on it.
        updateSettings { it.copy(pairingState = result.resultingState, enabled = false) }
        if (result.isComplete) {
            lastKnownDeviceId = null
            lastKnownKeyAlias = null
        }
        refresh()
        return result
    }

    // -----------------------------------------------------------------------------------------
    // Transport
    // -----------------------------------------------------------------------------------------

    /**
     * The gateway client for the current pairing, or `null` when any part of the identity is not
     * provable.
     *
     * This is the **only** way to obtain a transport. It re-derives the device through
     * [resolveDevice] on every call, so a credential that expired, a key that was wiped, or settings
     * that drifted from the stored record all collapse to `null` — and a `null` caller cannot send
     * anything, where a fail-open stand-in would.
     */
    suspend fun gatewayClientOrNull(): ClaudePGatewayClient? {
        val device = resolveDevice() ?: run {
            closeCachedClient()
            return null
        }

        val identity = identityOf(device)
        cachedClient?.let { cached ->
            if (cached.identity == identity) return cached.client
            // The device changed underneath the socket. Destroy it rather than let it keep
            // authenticating with a credential the app no longer holds.
            closeCachedClient()
        }

        val endpoint = device.endpointOrNull() ?: return null

        val client = WssClaudePGatewayClient(
            connector = OkHttpClaudePWebSocketConnector(okHttpClient),
            endpoint = endpoint,
            accessProvider = ClaudePDeviceAccessProvider { now ->
                // Re-read on every connection rather than closing over the credential captured above,
                // so a revoked device cannot keep refreshing a socket with a stale secret.
                val current = resolveDevice() ?: return@ClaudePDeviceAccessProvider null
                ClaudePDeviceAccess(
                    deviceId = current.deviceId,
                    credential = ClaudePAccessCredential(current.accessCredential),
                    expiresAtEpochSeconds = current.accessExpiresAtEpochSeconds,
                ).takeIf { now < it.expiresAtEpochSeconds }
            },
            deviceKeyStore = deviceKeyStore,
            keyAlias = device.keyAlias,
            scope = scope,
            appVersion = appVersion,
        )

        val stateJob = scope.launch {
            client.connectionState.collect { state ->
                // Only the *current* client may move the displayed state. Without this guard a client
                // that was replaced by a re-pair could publish a late event over the new device's
                // status.
                if (cachedClient?.client !== client) return@collect
                _connectionState.value = state
                refreshStatus()
            }
        }

        cachedClient = CachedGatewayClient(identity, client, stateJob)
        return client
    }

    /**
     * The one authoritative reading of "this device is paired", or `null`.
     *
     * Every condition is checked because each one independently means the identity is not provable:
     * `claudep/03-security-and-operations.md` §10 requires revocation to invalidate the connection,
     * and a credential that disagrees with settings is the signature of a partial write or a
     * tampered record.
     */
    private suspend fun resolveDevice(): ClaudePPairedDevice? {
        val setting = currentClaudePSetting() ?: return null

        // Only `PAIRED` may reach a gateway. `NOT_PAIRED` and `REVOKED` are both non-dispatchable,
        // which is what makes revocation effective even when physical deletion failed.
        if (setting.pairingState != ClaudePPairingState.PAIRED) return null

        val device = (credentialStore.read() as? ClaudePCredentialRead.Present)?.device ?: return null
        if (device.isExpiredInternal(nowEpochSeconds())) return null

        // Settings and the stored record must agree. A mismatch means one of them is stale or
        // forged, and there is no safe way to pick a winner.
        if (setting.pairedOrigin != device.pairedOrigin) return null
        if (setting.gatewayFingerprint != device.gatewayFingerprint) return null
        if (setting.gatewayInstallationId != device.gatewayInstallationId) return null
        if (setting.device.deviceId != device.deviceId) return null
        if (device.keyAlias.isBlank()) return null

        // The device key must load. `loadExisting` never creates one, so a wiped or invalidated key
        // ends here rather than becoming a fresh identity behind a credential that never matched it.
        val key = deviceKeyStore.loadExisting(device.keyAlias) ?: return null
        if (key.publicKeyDer() == null) return null

        lastKnownDeviceId = device.deviceId
        lastKnownKeyAlias = device.keyAlias
        return device
    }

    private fun identityOf(device: ClaudePPairedDevice): String =
        "${device.deviceId}|${device.keyAlias}|${device.pairedOrigin}|${device.accessCredential}"

    private fun closeCachedClient() {
        cachedClient?.let { cached ->
            cached.stateJob.cancel()
            scope.launch { cached.client.shutdown() }
        }
        cachedClient = null
        _connectionState.value = ClaudePConnectionState.DISCONNECTED
    }

    // -----------------------------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------------------------

    /** The paired device id, or `null` when there is none. Used for the request fingerprint. */
    fun currentDeviceIdOrNull(): String? = lastKnownDeviceId

    /**
     * Re-reads the credential store and recomputes [status].
     *
     * The single place status is derived from. Both the settings collector and the connection-state
     * collector funnel through here, so there is exactly one mapping and no way for the two to drift.
     */
    suspend fun refresh() {
        val setting = currentClaudePSetting()
        val read = credentialStore.read()
        (read as? ClaudePCredentialRead.Present)?.device?.let {
            lastKnownDeviceId = it.deviceId
            lastKnownKeyAlias = it.keyAlias
        }
        _status.value = ClaudePUiStatusMapper.map(
            settingsState = setting?.pairingState ?: ClaudePPairingState.NOT_PAIRED,
            credentialRead = read,
            connectionState = _connectionState.value,
            pairingInFlight = _pairingInFlight.value,
            nowEpochSeconds = nowEpochSeconds(),
        )
    }

    private suspend fun refreshStatus() = refresh()

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

    /** The transport plus the identity it was built for, and the collector watching its state. */
    private data class CachedGatewayClient(
        val identity: String,
        val client: WssClaudePGatewayClient,
        val stateJob: Job,
    )

    private companion object {
        const val TAG = "ClaudePPairing"
    }
}

private fun ClaudePPairedDevice.endpointOrNull(): ClaudePEndpoint? =
    (ClaudePEndpoint.parse(pairedOrigin) as? ClaudePEndpointResult.Accepted)?.endpoint

private fun ClaudePPairedDevice.isExpiredInternal(nowEpochSeconds: Long): Boolean =
    accessExpiresAtEpochSeconds <= 0 || nowEpochSeconds >= accessExpiresAtEpochSeconds

/** Maps a QR-level rejection onto the bounded pairing failure the UI reports. */
private fun ClaudePPairingRejection.toPairingFailure(): ClaudePPairingFailure = when (this) {
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
