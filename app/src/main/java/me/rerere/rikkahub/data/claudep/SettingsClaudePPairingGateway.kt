package me.rerere.rikkahub.data.claudep

import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.claudep.ClaudePDeviceDescriptor
import me.rerere.ai.provider.claudep.ClaudePPairedDevice
import me.rerere.ai.provider.claudep.ClaudePPairedMetadata
import me.rerere.ai.provider.claudep.ClaudePPairingSettingsGateway
import me.rerere.ai.provider.claudep.ClaudePPairingState
import me.rerere.rikkahub.data.datastore.SettingsStore

/**
 * The settings half of the pairing lifecycle.
 *
 * ### Why this is a separate object from the repository
 *
 * `ClaudePPairingCoordinator` is the single orchestrator, and it reaches settings only through this
 * interface. Keeping the writes here — rather than letting both a repository and a coordinator write
 * the same fields — is what prevents a double notification or two writers overwriting each other.
 *
 * ### Transactions
 *
 * `SettingsStore` is a DataStore; it has no cross-medium transaction with a Keystore or a file. That
 * is stated plainly rather than papered over. What replaces a transaction is an **ordering that is
 * safe at every point**:
 *
 * - `REVOKED` + `enabled = false` is persisted *before* any destructive step, so a crash at any
 *   later moment still leaves a non-dispatchable device.
 * - `NOT_PAIRED` is written only after the coordinator has confirmed every deletion *and* the
 *   tombstone is gone, so the clean state is never written speculatively.
 *
 * A crash between those two writes leaves `REVOKED` with a tombstone, which the app resumes on next
 * start — a recoverable state, not a silent one.
 */
class SettingsClaudePPairingGateway(
    private val settingsStore: SettingsStore,
) : ClaudePPairingSettingsGateway {

    override suspend fun pairingState(): ClaudePPairingState =
        currentSetting()?.pairingState ?: ClaudePPairingState.NOT_PAIRED

    override suspend fun pairedMetadata(): ClaudePPairedMetadata? = currentSetting()?.let { setting ->
        val origin = setting.pairedOrigin ?: return@let null
        ClaudePPairedMetadata(
            pairedOrigin = origin,
            gatewayFingerprint = setting.gatewayFingerprint.orEmpty(),
            gatewayInstallationId = setting.gatewayInstallationId.orEmpty(),
            deviceId = setting.device.deviceId,
        )
    }

    override suspend fun markPaired(device: ClaudePPairedDevice): Boolean = write { setting ->
        setting.copy(
            pairingState = ClaudePPairingState.PAIRED,
            pairedOrigin = device.pairedOrigin,
            gatewayFingerprint = device.gatewayFingerprint,
            gatewayInstallationId = device.gatewayInstallationId,
            device = ClaudePDeviceDescriptor(
                deviceId = device.deviceId,
                displayName = device.deviceName,
                lastConnectedAt = null,
            ),
            // Only non-secret fields. The credential and the private key stay in their own stores, so
            // these settings remain safe to export.
            cachedModels = emptyList(),
            catalogCachedAt = null,
            claudeCodeVersion = null,
        )
    }

    /**
     * Persists the non-dispatchable state *and* disables the provider, in one write.
     *
     * Disabling is part of revocation rather than a separate courtesy: a `REVOKED` provider that
     * stayed enabled is one settings merge away from being schedulable again.
     */
    override suspend fun markRevoked(): Boolean = write { setting ->
        setting.copy(pairingState = ClaudePPairingState.REVOKED, enabled = false)
    }

    /**
     * Persists the clean state and clears the paired metadata.
     *
     * Reached only when the coordinator has confirmed every deletion and the tombstone is gone, so a
     * `NOT_PAIRED` here genuinely means the device holds no pairing.
     */
    override suspend fun markNotPaired(): Boolean = write { setting ->
        setting.copy(
            pairingState = ClaudePPairingState.NOT_PAIRED,
            enabled = false,
            pairedOrigin = null,
            gatewayFingerprint = null,
            gatewayInstallationId = null,
            device = ClaudePDeviceDescriptor(),
            cachedModels = emptyList(),
            catalogCachedAt = null,
            claudeCodeVersion = null,
        )
    }

    /** Applies [transform] to the Claude P provider only. Returns `false` when the write failed. */
    private suspend fun write(transform: (ProviderSetting.ClaudeP) -> ProviderSetting.ClaudeP): Boolean =
        try {
            settingsStore.update { settings ->
                settings.copy(
                    providers = settings.providers.map { provider ->
                        if (provider is ProviderSetting.ClaudeP) transform(provider) else provider
                    },
                )
            }
            true
        } catch (_: Throwable) {
            // The coordinator decides what is safe next; it must not assume the write landed.
            false
        }

    private fun currentSetting(): ProviderSetting.ClaudeP? =
        settingsStore.settingsFlow.value.providers
            .filterIsInstance<ProviderSetting.ClaudeP>()
            .firstOrNull()
}
