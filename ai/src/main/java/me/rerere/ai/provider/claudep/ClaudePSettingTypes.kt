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
 * Records one successful catalog read.
 *
 * A single-method interface rather than a direct dependency on a settings store, so a caller states
 * the one thing it needs and a test can supply it without one. The production implementation is the
 * same authority that owns the pairing fields — there is exactly one writer for
 * `ProviderSetting.ClaudeP`'s catalog columns, for the same reason the pairing lifecycle has one.
 *
 * It is deliberately **not** a dispatch gate. What is cached here is what the settings screen and an
 * offline hint read; whether a model may run is decided by the live connection and the gateway, never
 * by this cache. Treating it as authority would make a stale entry a capability.
 */
fun interface ClaudePCatalogRecorder {
    /**
     * Persists [entries] and the Claude Code version the **same connection** reported.
     *
     * One call for both, because they are one observation: written separately, the columns could hold
     * entries from one connection beside a version from another and present them as a single fact.
     * Returns `false` when the write failed.
     */
    suspend fun recordCatalog(entries: List<ClaudePCachedModel>, claudeCodeVersion: String): Boolean
}

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

