package me.rerere.rikkahub.ui.pages.setting.components

import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.claudep.ClaudePPairingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the Claude P settings surface.
 *
 * A Compose screen cannot be rendered in these JVM unit tests, so what is asserted here is the
 * logic the screen's honesty depends on: there is no base URL to edit or reset, no API key to
 * enter, and the provider cannot present itself as connected.
 *
 * The specific failure this guards against is a settings page that looks complete — a key field, a
 * URL field, a green "Connected" badge — while every request behind it fails. It is better for the
 * page to say "not yet paired" than to look finished.
 */
class ClaudePProviderConfigureTest {

    @Test
    fun `claude p has no base url to display, reset or default`() {
        val provider = ProviderSetting.ClaudeP(
            pairedOrigin = "https://claude.example.com",
        )

        // The origin is learned from pairing and is not user-editable, so the generic config form
        // has nothing to offer — including a "reset to default" affordance.
        assertEquals("", provider.defaultBaseUrlForReset())
        assertTrue(provider.isUsingDefaultBaseUrl())
        assertSame(provider, provider.resetBaseUrlToDefault())
    }

    @Test
    fun `an unpaired claude p reports itself as using no base url`() {
        val provider = ProviderSetting.ClaudeP()

        assertEquals("", provider.defaultBaseUrlForReset())
        assertTrue(provider.isUsingDefaultBaseUrl())
    }

    /**
     * Structural, not cosmetic: if a credential field is ever added to this type it will be
     * serialized into settings, QR exports and WebDAV backups. The provider must not have one.
     */
    @Test
    fun `claude p declares no api key or base url field`() {
        val fields = ProviderSetting.ClaudeP::class.java.declaredFields.map { it.name }

        listOf("apiKey", "baseUrl", "privateKey", "accessToken", "refreshToken").forEach { banned ->
            assertFalse(
                "ProviderSetting.ClaudeP must not declare a '$banned' field, found: $fields",
                fields.any { it.equals(banned, ignoreCase = true) },
            )
        }
        // The paired origin is present, but as a read-only record of the pairing rather than an
        // editable endpoint.
        assertTrue(fields.contains("pairedOrigin"))
    }

    @Test
    fun `a fresh claude p can never claim to be connected`() {
        val provider = ProviderSetting.ClaudeP()

        assertEquals(ClaudePPairingState.NOT_PAIRED, provider.pairingState)
        assertFalse(provider.enabled)
        assertTrue(provider.pairedOrigin == null)
        assertTrue(provider.gatewayFingerprint == null)
        assertTrue(provider.device.deviceId.isEmpty())
        assertTrue(provider.claudeCodeVersion == null)
    }

    @Test
    fun `claude p is not a convertible provider type`() {
        // Convert offers API-key provider types; Claude P cannot be produced by conversion because
        // it has neither a key nor an endpoint to carry across.
        assertFalse(ProviderSetting.Types.contains(ProviderSetting.ClaudeP::class))
    }
}
