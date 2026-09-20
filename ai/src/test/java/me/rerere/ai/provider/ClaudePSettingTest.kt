package me.rerere.ai.provider

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

        val encoded = json.encodeToString(ProviderSetting.serializer(), original)

        // Deliberately held as the sealed supertype: the whole point is to observe what the
        // discriminator actually routed to, which a narrowed variable could not tell us.
        val decoded: ProviderSetting = json.decodeFromString(ProviderSetting.serializer(), encoded)

        assertTrue(decoded is ProviderSetting.ClaudeP)
        assertFalse(decoded is ProviderSetting.Claude)
        assertFalse(decoded is ProviderSetting.OpenAI)
        assertFalse(decoded is ProviderSetting.Google)
        assertFalse(decoded is ProviderSetting.AICore)
        assertFalse(decoded is ProviderSetting.LiteRtLocal)
        assertFalse(decoded is ProviderSetting.Codex)

        assertEquals("claude_p", discriminatorOf(encoded))

        // Whole-object equality is NOT used here: `description` and `shortDescription` are
        // `@Transient` lambdas that a data class still compares by reference, so two independently
        // built instances can never be equal even though every persisted field matches.
        assertClaudePPersistentFieldsEqual(original, decoded as ProviderSetting.ClaudeP)
        assertTransientUiFieldsAbsent(encoded)
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
        val openAi = ProviderSetting.OpenAI(id = Uuid_OPENAI, name = "OpenAI")
        val claudeP = ProviderSetting.ClaudeP(
            enabled = true,
            pairingState = ClaudePPairingState.PAIRED,
        )
        val codex = ProviderSetting.Codex()
        val mixed: List<ProviderSetting> = listOf(openAi, claudeP, codex)
        val serializer = ListSerializer(ProviderSetting.serializer())

        val encoded = json.encodeToString(serializer, mixed)
        val decoded: List<ProviderSetting> = json.decodeFromString(serializer, encoded)

        // Order is preserved, and every element keeps its own concrete type. Each check keeps the
        // sealed supertype as its subject so the compiler cannot fold it away.
        assertEquals(3, decoded.size)
        assertTrue(decoded[0] is ProviderSetting.OpenAI)
        assertFalse(decoded[0] is ProviderSetting.ClaudeP)
        assertTrue(decoded[1] is ProviderSetting.ClaudeP)
        assertFalse(decoded[1] is ProviderSetting.Claude)
        assertFalse(decoded[1] is ProviderSetting.OpenAI)
        assertFalse(decoded[1] is ProviderSetting.Codex)
        assertTrue(decoded[2] is ProviderSetting.Codex)
        assertFalse(decoded[2] is ProviderSetting.ClaudeP)

        // The stable discriminator sits on the entry that carries it — and could not be mistaken
        // for a neighbouring provider's.
        val encodedArray = json.decodeFromString(JsonArray.serializer(), encoded)
        assertEquals("openai", encodedArray[0].discriminator())
        assertEquals("claude_p", encodedArray[1].discriminator())
        assertEquals("codex", encodedArray[2].discriminator())

        // Field values, element by element.
        assertEquals(openAi.id, decoded[0].id)
        assertEquals(openAi.name, decoded[0].name)
        assertClaudePPersistentFieldsEqual(claudeP, decoded[1] as ProviderSetting.ClaudeP)
        assertEquals(codex.id, decoded[2].id)
        assertEquals(codex.name, decoded[2].name)
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

    /**
     * Compares exactly the fields the persisted contract carries for Claude P.
     *
     * This list mirrors the closed key set asserted by
     * `the serialized form contains only non-secret fields`: thirteen persistent fields plus the
     * polymorphic `type` discriminator, which is asserted separately. The fields are enumerated by
     * hand on purpose — a reflective walk, or re-serializing both objects and comparing strings,
     * would keep passing if a field were added and then silently dropped by serialization.
     */
    private fun assertClaudePPersistentFieldsEqual(
        expected: ProviderSetting.ClaudeP,
        actual: ProviderSetting.ClaudeP,
    ) {
        assertEquals("id", expected.id, actual.id)
        assertEquals("enabled", expected.enabled, actual.enabled)
        assertEquals("name", expected.name, actual.name)
        assertEquals("models", expected.models, actual.models)
        assertEquals("balanceOption", expected.balanceOption, actual.balanceOption)
        assertEquals("pairedOrigin", expected.pairedOrigin, actual.pairedOrigin)
        assertEquals("gatewayFingerprint", expected.gatewayFingerprint, actual.gatewayFingerprint)
        assertEquals(
            "gatewayInstallationId",
            expected.gatewayInstallationId,
            actual.gatewayInstallationId,
        )
        assertEquals("device", expected.device, actual.device)
        assertEquals("pairingState", expected.pairingState, actual.pairingState)
        assertEquals("cachedModels", expected.cachedModels, actual.cachedModels)
        assertEquals("catalogCachedAt", expected.catalogCachedAt, actual.catalogCachedAt)
        assertEquals("claudeCodeVersion", expected.claudeCodeVersion, actual.claudeCodeVersion)
        // `builtIn`, `description` and `shortDescription` are excluded by design, not by omission:
        // they are `@Transient` UI state, and `description`/`shortDescription` are function types
        // that a data class compares by reference. Comparing them would fail for every pair of
        // independently constructed instances and would say nothing about persistence.
    }

    /** The `@Transient` UI fields must never reach the persisted form at all. */
    private fun assertTransientUiFieldsAbsent(encoded: String) {
        val keys = json.decodeFromString(JsonObject.serializer(), encoded).keys
        listOf("builtIn", "description", "shortDescription").forEach { transientKey ->
            assertFalse(
                "'$transientKey' is @Transient and must never be persisted, found: $keys",
                keys.contains(transientKey),
            )
        }
    }

    /** Reads the polymorphic `type` discriminator of a single encoded provider object. */
    private fun discriminatorOf(encoded: String): String? = json
        .decodeFromString(JsonObject.serializer(), encoded)["type"]
        ?.jsonPrimitive
        ?.content
}

private val Uuid_OPENAI = kotlin.uuid.Uuid.parse("1eeea727-9ee5-4cae-93e6-6fb01a4d051e")

/** Reads the polymorphic `type` discriminator of one element of an encoded provider array. */
private fun JsonElement.discriminator(): String? =
    jsonObject["type"]?.jsonPrimitive?.content
