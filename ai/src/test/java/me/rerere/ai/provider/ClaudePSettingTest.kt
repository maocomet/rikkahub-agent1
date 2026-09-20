package me.rerere.ai.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.provider.claudep.ClaudePCachedModel
import me.rerere.ai.provider.claudep.ClaudePDeviceDescriptor
import me.rerere.ai.provider.claudep.ClaudePPairingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Persistence contract for `ProviderSetting.ClaudeP`.
 *
 * Settings JSON is not private: it is written to disk, exported through QR codes and uploaded to
 * WebDAV by the backup feature. These tests therefore treat "what ends up in the serialized form"
 * as a security boundary, not a formatting detail.
 */
class ClaudePSettingTest {

    /**
     * Mirrors the app's `JsonInstant` exactly — notably `explicitNulls` is left at its default
     * (true), so null fields ARE written. Using a stricter test config here would have asserted a
     * key set that production never actually persists, which is the opposite of the point.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `the serialized name is the stable claude_p discriminator`() {
        val encoded = json.encodeToString(ProviderSetting.serializer(), ProviderSetting.ClaudeP())
        val obj = json.decodeFromString(JsonObject.serializer(), encoded)

        assertEquals("claude_p", obj["type"]?.toString()?.trim('"'))
    }

    @Test
    fun `the built-in default is disabled and unpaired`() {
        val provider = ProviderSetting.ClaudeP()

        assertFalse("Claude P must ship disabled", provider.enabled)
        assertTrue(provider.builtIn)
        assertEquals("Claude P", provider.name)
        assertEquals(ClaudePPairingState.NOT_PAIRED, provider.pairingState)
        assertTrue(provider.models.isEmpty())
    }

    @Test
    fun `the provider id is stable across instances`() {
        assertEquals(CLAUDEP_PROVIDER_ID, ProviderSetting.ClaudeP().id)
        assertEquals(CLAUDEP_PROVIDER_ID, ProviderSetting.ClaudeP().id)
        assertEquals(
            "cb1ade90-0001-4a1a-9f01-0000000000a1",
            CLAUDEP_PROVIDER_ID.toString(),
        )
    }

    /**
     * Claude P is a separate subtype from the API-key providers, but that is a *compile-time*
     * fact about a sealed hierarchy — asserting it with `is` is rejected by Kotlin 2.x as
     * "check for instance is always 'false'", and it could never have failed at runtime.
     *
     * The property that actually matters is enforced where it can fail: `claude_p` has its own
     * wire discriminator (`the serialized name is the stable claude_p discriminator`) and a
     * `claude_p` entry decodes back to `ClaudeP` and nothing else, including inside a list that
     * also holds OpenAI and Codex entries (`a claude_p entry round-trips inside a mixed provider
     * list`). What remains here is only the Kotlin type name used by diagnostics.
     */
    @Test
    fun `claude_p kotlin class name is stable`() {
        assertEquals("ClaudeP", ProviderSetting.ClaudeP::class.simpleName)
    }

    /**
     * The Add/Convert selector is driven by this list. Claude P is a singleton built-in whose
     * configuration cannot be created by conversion (there is no API key or base URL to carry
     * over), so offering it there would produce an unusable provider.
     */
    @Test
    fun `claude_p is not offered in the provider type list`() {
        assertFalse(ProviderSetting.Types.contains(ProviderSetting.ClaudeP::class))
        assertFalse(ProviderSetting.Types.any { it.simpleName == "ClaudeP" })
    }

    @Test
    fun `a full round trip preserves every non-secret field`() {
        val original = ProviderSetting.ClaudeP(
            enabled = true,
            pairedOrigin = "https://claude.example.com",
            gatewayFingerprint = "SHA256:abc123",
            gatewayInstallationId = "install-1",
            device = ClaudePDeviceDescriptor(
                deviceId = "device-1",
                displayName = "Pixel",
                lastConnectedAt = "2026-09-20T00:00:00Z",
            ),
            pairingState = ClaudePPairingState.PAIRED,
            cachedModels = listOf(
                ClaudePCachedModel("sonnet", "Claude Sonnet", reasoningSummary = true),
                ClaudePCachedModel("haiku", "Claude Haiku", reasoningSummary = false),
            ),
            catalogCachedAt = "2026-09-20T00:00:00Z",
            claudeCodeVersion = "2.0.1",
        )

        val decoded = json.decodeFromString(
            ProviderSetting.serializer(),
            json.encodeToString(ProviderSetting.serializer(), original),
        )

        assertEquals(original, decoded)
    }

    /**
     * The security-relevant assertion: the persisted key set is closed. A future edit that adds a
     * credential field to this provider fails here rather than silently publishing it to backups.
     */
    @Test
    fun `the serialized form contains only non-secret fields`() {
        val encoded = json.encodeToString(
            ProviderSetting.serializer(),
            ProviderSetting.ClaudeP(
                enabled = true,
                pairedOrigin = "https://claude.example.com",
                gatewayFingerprint = "SHA256:abc123",
                gatewayInstallationId = "install-1",
                device = ClaudePDeviceDescriptor(deviceId = "device-1", displayName = "Pixel"),
                pairingState = ClaudePPairingState.PAIRED,
                claudeCodeVersion = "2.0.1",
            ),
        )
        val keys = json.decodeFromString(JsonObject.serializer(), encoded).keys

        val forbidden = listOf(
            "apiKey", "api_key",
            "baseUrl", "base_url",
            "accessToken", "access_token", "refreshToken", "refresh_token",
            "cookie", "cookies",
            "oauth", "clientSecret", "client_secret", "privateKey", "private_key",
            "socketPath", "socket_path", "cliPath", "cli_path", "binaryPath",
            "sshKey", "sshKeyPath", "password", "secret", "token",
        )
        // Substring matching is intentional: it also catches names like `oauthToken`.
        keys.forEach { key ->
            forbidden.forEach { banned ->
                assertFalse(
                    "ProviderSetting.ClaudeP must not persist a '$banned'-like field, found '$key'",
                    key.contains(banned, ignoreCase = true),
                )
            }
        }

        val allowed = setOf(
            "type", "id", "enabled", "name", "models", "balanceOption",
            "paired_origin", "gateway_fingerprint", "gateway_installation_id",
            "device", "pairing_state", "cached_models", "catalog_cached_at",
            "claude_code_version",
        )
        assertEquals(allowed, keys)
    }

    @Test
    fun `the device descriptor carries no credential material`() {
        val encoded = json.encodeToString(
            ClaudePDeviceDescriptor.serializer(),
            ClaudePDeviceDescriptor(deviceId = "device-1", displayName = "Pixel"),
        )
        val keys = json.decodeFromString(JsonObject.serializer(), encoded).keys

        assertEquals(setOf("deviceId", "displayName", "last_connected_at"), keys)
    }

    /**
     * Upgrade path: a settings file or backup written before Claude P existed must still load, and
     * must simply not contain one. `decodeProvidersTolerant` also relies on unknown subtypes
     * failing per-entry rather than discarding the whole list.
     */
    @Test
    fun `a backup written before claude_p existed still decodes`() {
        val legacy = """
            [
              {
                "type": "openai",
                "id": "1eeea727-9ee5-4cae-93e6-6fb01a4d051e",
                "enabled": true,
                "name": "OpenAI",
                "models": [],
                "apiKey": "sk-legacy",
                "baseUrl": "https://api.openai.com/v1"
              },
              {
                "type": "codex",
                "id": "7ce7e322-b995-4b0c-9d48-42e08dcfcdda",
                "enabled": false,
                "name": "Codex",
                "models": []
              }
            ]
        """.trimIndent()

        val providers = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(ProviderSetting.serializer()),
            legacy,
        )

        assertEquals(2, providers.size)
        assertTrue(providers.none { it is ProviderSetting.ClaudeP })
        assertTrue(providers.any { it is ProviderSetting.OpenAI })
        assertTrue(providers.any { it is ProviderSetting.Codex })
    }

    @Test
    fun `a claude_p entry round-trips inside a mixed provider list`() {
        val mixed = listOf(
            ProviderSetting.OpenAI(id = Uuid_OPENAI, name = "OpenAI"),
            ProviderSetting.ClaudeP(enabled = true, pairingState = ClaudePPairingState.PAIRED),
            ProviderSetting.Codex(),
        )

        val decoded = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(ProviderSetting.serializer()),
            json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(ProviderSetting.serializer()),
                mixed,
            ),
        )

        assertEquals(mixed, decoded)
    }

    @Test
    fun `provider helpers keep the claude_p branch intact`() {
        val original = ProviderSetting.ClaudeP()
        val model = Model(modelId = "sonnet", displayName = "Claude Sonnet")

        val added = original.addModel(model)
        assertTrue(added is ProviderSetting.ClaudeP)
        assertEquals(1, added.models.size)

        val deleted = added.delModel(model)
        assertTrue(deleted is ProviderSetting.ClaudeP)
        assertTrue(deleted.models.isEmpty())
    }
}

private val Uuid_OPENAI = kotlin.uuid.Uuid.parse("1eeea727-9ee5-4cae-93e6-6fb01a4d051e")
