package me.rerere.ai.provider.claudep

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Persisted, non-secret supporting types for `ProviderSetting.ClaudeP`.
 *
 * Everything in this file is written to ordinary settings JSON and therefore to WebDAV backups and
 * QR exports. It must stay free of credentials, prompts and device secrets — the pairing private
 * key belongs in the Android Keystore and the short-lived access credential belongs in an
 * encrypted credential store (both CP1-B).
 */

/** Device pairing lifecycle. Only [PAIRED] may ever reach the gateway. */
@Serializable
enum class ClaudePPairingState {
    @SerialName("not_paired")
    NOT_PAIRED,

    @SerialName("paired")
    PAIRED,

    @SerialName("revoked")
    REVOKED,
}

/**
 * One cached catalog alias.
 *
 * Cached so the settings page can render a model list without contacting the gateway. It is a
 * cache, never an authority: abilities are re-derived from a live catalog before use, and nothing
 * here can grant a capability the provider does not implement.
 */
@Serializable
data class ClaudePCachedModel(
    val alias: String = "",
    val displayName: String = "",
    /** Mirrors the server's `reasoning_summary` feature. Never implies TOOL or multimodal input. */
    val reasoningSummary: Boolean = false,
)

/**
 * Device identity shared with the gateway.
 *
 * `deviceId` is an opaque public identifier, not a credential — it is useless without the
 * Keystore-held private key that signs handshake nonces.
 */
@Serializable
data class ClaudePDeviceDescriptor(
    val deviceId: String = "",
    val displayName: String = "",
    /** ISO-8601 timestamp of the last successful handshake, or null if never. */
    @SerialName("last_connected_at")
    val lastConnectedAt: String? = null,
)
