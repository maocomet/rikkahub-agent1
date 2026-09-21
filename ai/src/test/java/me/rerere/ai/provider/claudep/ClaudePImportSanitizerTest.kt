package me.rerere.ai.provider.claudep

import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Import hardening.
 *
 * A provider string is untrusted input — it comes from a scanned QR code or a pasted blob — so what
 * it decodes into must never be able to claim a pairing the user did not perform. These tests feed
 * the sanitiser a deliberately hostile Claude P (enabled, "paired", naming a foreign origin) and
 * assert that nothing survives.
 *
 * The Base64 decode itself is not exercised here: it runs through `android.util.Base64`, which a
 * plain JVM unit test cannot execute. That split is exactly why the rule lives in `ai` as a pure
 * function rather than inline in the import UI.
 */
class ClaudePImportSanitizerTest {

    @Test
    fun `a hostile imported provider is reset to unpaired and disabled`() {
        val hostile = ProviderSetting.ClaudeP(
            id = Uuid.random(),
            enabled = true,
            name = "Claude P",
            pairingState = ClaudePPairingState.PAIRED,
            pairedOrigin = "https://attacker.example.com",
            gatewayFingerprint = "deadbeef",
            gatewayInstallationId = "someone-elses-gateway",
            device = ClaudePDeviceDescriptor(
                deviceId = "not-our-device",
                displayName = "Not our device",
            ),
            cachedModels = listOf(ClaudePCachedModel(alias = "sonnet")),
            catalogCachedAt = "2026-01-01T00:00:00Z",
            claudeCodeVersion = "9.9.9",
        )

        val sanitized = hostile.sanitizedAfterImport() as ProviderSetting.ClaudeP

        assertFalse("an imported provider must never arrive enabled", sanitized.enabled)
        assertEquals(ClaudePPairingState.NOT_PAIRED, sanitized.pairingState)
        assertNull("the endpoint must not come from imported text", sanitized.pairedOrigin)
        assertNull(sanitized.gatewayFingerprint)
        assertNull(sanitized.gatewayInstallationId)
        assertEquals("", sanitized.device.deviceId)
        assertNull(sanitized.claudeCodeVersion)
        assertTrue(sanitized.cachedModels.isEmpty())
        assertNull(sanitized.catalogCachedAt)
    }

    @Test
    fun `a sanitized provider offers pairing rather than claiming to work`() {
        val sanitized = ProviderSetting.ClaudeP(
            enabled = true,
            pairingState = ClaudePPairingState.PAIRED,
            pairedOrigin = "https://attacker.example.com",
        ).sanitizedAfterImport() as ProviderSetting.ClaudeP

        val status = ClaudePUiStatusMapper.map(
            settingsState = sanitized.pairingState,
            credentialRead = ClaudePCredentialRead.Absent,
            connectionState = ClaudePConnectionState.DISCONNECTED,
            pairingInFlight = false,
            nowEpochSeconds = 0,
        )

        // End to end: an imported provider lands on "scan a pairing code", not on "online".
        assertEquals(ClaudePUiStatus.NOT_PAIRED, status)
        assertFalse(status.allowsDispatch)
    }

    @Test
    fun `a hostile import claiming a live connection still cannot dispatch`() {
        val sanitized = ProviderSetting.ClaudeP(
            enabled = true,
            pairingState = ClaudePPairingState.PAIRED,
            pairedOrigin = "https://attacker.example.com",
            gatewayFingerprint = "deadbeef",
            gatewayInstallationId = "someone-elses-gateway",
        ).sanitizedAfterImport() as ProviderSetting.ClaudeP

        // Sanitisation reset the settings to NOT_PAIRED, and settings are the pairing authority. A
        // leftover or forged credential can therefore only *downgrade* that verdict — expired,
        // unusable or missing — and can never upgrade NOT_PAIRED back to a paired one.
        //
        // So this import is refused here, by the status derivation itself, before anything reaches
        // the device-key load or a socket. The earlier revision of this test expected PAIRED and
        // argued the remaining defence was the missing key; that was written before the resolver was
        // made settings-authoritative, and it was wrong: there is no "remaining defence", because
        // this gate already refuses.
        val status = ClaudePUiStatusMapper.map(
            settingsState = sanitized.pairingState,
            credentialRead = ClaudePCredentialRead.Present(
                ClaudePPairedDevice(
                    deviceId = "not-our-device",
                    deviceName = "x",
                    keyAlias = "alias",
                    accessCredential = "credential",
                    accessExpiresAtEpochSeconds = 10_000,
                    gatewayFingerprint = "deadbeef",
                    gatewayInstallationId = "someone-elses-gateway",
                    pairedOrigin = "https://attacker.example.com",
                ),
            ),
            connectionState = ClaudePConnectionState.DISCONNECTED,
            pairingInFlight = false,
            nowEpochSeconds = 0,
        )

        assertEquals(ClaudePUiStatus.NOT_PAIRED, status)
        assertFalse(status.allowsDispatch)
    }

    @Test
    fun `non Claude P providers are returned unchanged`() {
        // Import hardening is specific to the provider whose authority comes from a device pairing.
        // Rewriting other types here would break ordinary provider sharing.
        val openAi = ProviderSetting.OpenAI(
            id = Uuid.random(),
            enabled = true,
            name = "My OpenAI",
            apiKey = "sk-test",
        )

        val sanitized = openAi.sanitizedAfterImport()

        assertTrue(sanitized is ProviderSetting.OpenAI)
        assertEquals(openAi, sanitized)
    }

    @Test
    fun `a revoked provider is reset to unpaired rather than left revoked`() {
        // `REVOKED` means "the user explicitly unpaired"; an imported file claiming it would pin the
        // device into a state it can only leave through a settings write. NOT_PAIRED is the honest
        // reading of a device that has no credential.
        val sanitized = ProviderSetting.ClaudeP(
            pairingState = ClaudePPairingState.REVOKED,
            enabled = false,
        ).sanitizedAfterImport() as ProviderSetting.ClaudeP

        assertEquals(ClaudePPairingState.NOT_PAIRED, sanitized.pairingState)
    }
}
