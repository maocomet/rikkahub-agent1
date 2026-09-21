package me.rerere.rikkahub.data.claudep

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import me.rerere.ai.provider.claudep.ClaudePAccessCredential
import me.rerere.ai.provider.claudep.ClaudePConnectionState
import me.rerere.ai.provider.claudep.ClaudePCredentialRead
import me.rerere.ai.provider.claudep.ClaudePDeviceAccess
import me.rerere.ai.provider.claudep.ClaudePDeviceAccessProvider
import me.rerere.ai.provider.claudep.ClaudePDeviceCredentialStore
import me.rerere.ai.provider.claudep.ClaudePDeviceKeyStore
import me.rerere.ai.provider.claudep.ClaudePCleanupTombstoneStore
import me.rerere.ai.provider.claudep.ClaudePEndpoint
import me.rerere.ai.provider.claudep.ClaudePEndpointResult
import me.rerere.ai.provider.claudep.ClaudePGatewayClient
import me.rerere.ai.provider.claudep.ClaudePPairedDevice
import me.rerere.ai.provider.claudep.ClaudePPairingClient
import me.rerere.ai.provider.claudep.ClaudePPairingCoordinator
import me.rerere.ai.provider.claudep.ClaudePPairingOutcome
import me.rerere.ai.provider.claudep.ClaudePPairingSettingsGateway
import me.rerere.ai.provider.claudep.ClaudePPairingState
import me.rerere.ai.provider.claudep.ClaudePUiStatus
import me.rerere.ai.provider.claudep.ClaudePUiStatusMapper
import me.rerere.ai.provider.claudep.ClaudePUnpairResult
import me.rerere.ai.provider.claudep.OkHttpClaudePPairingTransport
import me.rerere.ai.provider.claudep.OkHttpClaudePWebSocketConnector
import me.rerere.ai.provider.claudep.WssClaudePGatewayClient

/**
 * The device's view of its Claude P pairing: status, and a transport when one is provable.
 *
 * ### Everything that mutates state goes through the coordinator
 *
 * This class deliberately owns **no** revocation, compensation, key-deletion or state-transition
 * logic. `pair`, `unpair` and `retryCleanup` are thin delegations to [ClaudePPairingCoordinator],
 * which holds the single lifecycle lock. An earlier revision kept a second, independently-modifiable
 * implementation here, which meant "what does unpair actually do?" had two answers and only one of
 * them was tested.
 *
 * What is left is the part the coordinator has no business knowing: the live transport, the derived
 * UI status, and the consistency checks that decide whether a transport may exist at all.
 */
class ClaudePDevicePairingRepository(
    private val credentialStore: ClaudePDeviceCredentialStore,
    private val deviceKeyStore: ClaudePDeviceKeyStore,
    private val tombstoneStore: ClaudePCleanupTombstoneStore,
    private val settingsGateway: ClaudePPairingSettingsGateway,
    private val scope: CoroutineScope,
    private val appVersion: String,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val _pairingInFlight = MutableStateFlow(false)
    private val _connectionState = MutableStateFlow(ClaudePConnectionState.DISCONNECTED)
    private val _status = MutableStateFlow(ClaudePUiStatus.NOT_PAIRED)

    /** Connection lifecycle of the current transport, or `DISCONNECTED` when there is none. */
    val connectionState: StateFlow<ClaudePConnectionState> = _connectionState.asStateFlow()

    /** The status the settings screen renders. Derived; never written from the UI. */
    val status: StateFlow<ClaudePUiStatus> = _status.asStateFlow()

    private var cachedClient: CachedGatewayClient? = null

    @Volatile
    private var lastKnownDeviceId: String? = null

    /**
     * The one lifecycle orchestrator.
     *
     * Its `onRevokeTransport` closes the cached client **inside** the lifecycle lock, so dropping the
     * socket cannot race a concurrent transport build.
     */
    private val coordinator: ClaudePPairingCoordinator = ClaudePPairingCoordinator(
        credentialStore = credentialStore,
        deviceKeyStore = deviceKeyStore,
        tombstoneStore = tombstoneStore,
        settings = settingsGateway,
        pairingClient = ClaudePPairingClient(
            transportFor = { endpoint -> OkHttpClaudePPairingTransport(endpoint) },
            keyStore = deviceKeyStore,
            appVersion = appVersion,
        ),
        nowEpochSeconds = nowEpochSeconds,
        onRevokeTransport = { closeCachedClient() },
    )

    init {
        // Restores durable state at start: a pending tombstone or a REVOKED device must block
        // dispatch from the first frame, before any screen is opened.
        scope.launch { refresh() }
    }

    // -----------------------------------------------------------------------------------------
    // Lifecycle — delegated, never reimplemented
    // -----------------------------------------------------------------------------------------

    /**
     * Runs a pairing through the coordinator.
     *
     * Parse, exchange, persist and compensate all happen under the coordinator's lifecycle lock, so
     * a second tap or a concurrent unpair cannot interleave with them.
     */
    suspend fun pair(invitationPayload: String, deviceName: String): ClaudePPairingOutcome {
        _pairingInFlight.value = true
        refreshStatus()
        return try {
            coordinator.pair(invitationPayload, deviceName).also { refreshStatus() }
        } finally {
            _pairingInFlight.value = false
            refreshStatus()
        }
    }

    /** Revokes the device. Delegates entirely to the coordinator. */
    suspend fun unpair(): ClaudePUnpairResult = try {
        coordinator.unpair()
    } finally {
        refreshStatus()
    }

    /**
     * Retries an incomplete cleanup.
     *
     * A separate entry point from [unpair] only so the UI can label the action; both run the same
     * coordinator path, so a retry can never reach a state an unpair could not.
     */
    suspend fun retryCleanup(): ClaudePUnpairResult = try {
        coordinator.retryCleanup()
    } finally {
        refreshStatus()
    }

    /** True while a cleanup is outstanding — a tombstone exists or the device is `REVOKED`. */
    suspend fun hasPendingCleanup(): Boolean = coordinator.mustNotDispatch()

    // -----------------------------------------------------------------------------------------
    // Transport
    // -----------------------------------------------------------------------------------------

    /**
     * The gateway client, or `null` when any part of the identity is not provable.
     *
     * Re-derived on every call. A tombstone, a `REVOKED` device, an expired credential, a key that
     * will not load, or settings that drifted from the stored record all collapse to `null` — and a
     * `null` caller cannot send anything, where a fail-open stand-in would.
     */
    suspend fun gatewayClientOrNull(): ClaudePGatewayClient? {
        // Checked first: a pending cleanup outranks a readable credential.
        if (coordinator.mustNotDispatch()) {
            closeCachedClient()
            return null
        }

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

        val endpoint = (ClaudePEndpoint.parse(device.pairedOrigin) as? ClaudePEndpointResult.Accepted)
            ?.endpoint ?: return null

        val client = WssClaudePGatewayClient(
            connector = OkHttpClaudePWebSocketConnector(),
            endpoint = endpoint,
            accessProvider = ClaudePDeviceAccessProvider { now ->
                // Re-read on every connection rather than closing over the credential captured above,
                // so a revoked device cannot keep refreshing a socket with a stale secret.
                if (coordinator.mustNotDispatch()) return@ClaudePDeviceAccessProvider null
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
                // replaced by a re-pair could publish a late event over the new device's status.
                if (cachedClient?.client !== client) return@collect
                _connectionState.value = state
                refreshStatus()
            }
        }

        cachedClient = CachedGatewayClient(identity, client, stateJob)
        return client
    }

    /** Closes the transport and stops its state collector. Idempotent. */
    private suspend fun closeCachedClient() {
        val cached = cachedClient ?: return
        cachedClient = null
        cached.stateJob.cancel()
        cached.client.shutdown()
        _connectionState.value = ClaudePConnectionState.DISCONNECTED
    }

    /**
     * The one authoritative reading of "this device is paired", or `null`.
     *
     * Each condition independently means the identity is not provable. A credential that disagrees
     * with settings is the signature of a partial write or a tampered record, and there is no safe
     * way to pick a winner.
     */
    private suspend fun resolveDevice(): ClaudePPairedDevice? {
        if (settingsGateway.pairingState() != ClaudePPairingState.PAIRED) return null

        val metadata = settingsGateway.pairedMetadata() ?: return null
        val device = (credentialStore.read() as? ClaudePCredentialRead.Present)?.device ?: return null
        if (device.accessExpiresAtEpochSeconds <= 0 ||
            nowEpochSeconds() >= device.accessExpiresAtEpochSeconds
        ) {
            return null
        }

        if (metadata.pairedOrigin != device.pairedOrigin) return null
        if (metadata.gatewayFingerprint != device.gatewayFingerprint) return null
        if (metadata.gatewayInstallationId != device.gatewayInstallationId) return null
        if (metadata.deviceId != device.deviceId) return null
        if (device.keyAlias.isBlank()) return null

        // `loadExisting` never creates a key, so a wiped or invalidated one ends here rather than
        // becoming a fresh identity behind a credential that never matched it.
        val key = deviceKeyStore.loadExisting(device.keyAlias) ?: return null
        if (key.publicKeyDer() == null) return null

        lastKnownDeviceId = device.deviceId
        return device
    }

    private fun identityOf(device: ClaudePPairedDevice): String =
        "${device.deviceId}|${device.keyAlias}|${device.pairedOrigin}|${device.accessCredential}"

    // -----------------------------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------------------------

    /** The paired device id, or `null`. Used for the request fingerprint. */
    fun currentDeviceIdOrNull(): String? = lastKnownDeviceId

    /** Re-reads durable state and recomputes [status]. */
    suspend fun refresh() {
        (credentialStore.read() as? ClaudePCredentialRead.Present)?.device?.let {
            lastKnownDeviceId = it.deviceId
        }
        refreshStatus()
    }

    private suspend fun refreshStatus() {
        // A pending cleanup is surfaced through the connection state so the screen reflects it even
        // before a transport is attempted.
        val pendingCleanup = coordinator.mustNotDispatch()
        _status.value = ClaudePUiStatusMapper.map(
            settingsState = settingsGateway.pairingState(),
            credentialRead = credentialStore.read(),
            connectionState = if (pendingCleanup) {
                ClaudePConnectionState.OFFLINE
            } else {
                _connectionState.value
            },
            pairingInFlight = _pairingInFlight.value,
            nowEpochSeconds = nowEpochSeconds(),
        )
    }

    private data class CachedGatewayClient(
        val identity: String,
        val client: WssClaudePGatewayClient,
        val stateJob: Job,
    )
}
