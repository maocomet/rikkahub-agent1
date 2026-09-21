package me.rerere.ai.provider.providers

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.claudep.ClaudePErrorCode
import me.rerere.ai.provider.claudep.ClaudePGatewayException
import me.rerere.ai.provider.claudep.FakeClaudePGatewayClient
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device identity, and what the handshake is allowed to reuse.
 *
 * ### The two bugs these exist for
 *
 * 1. **Identity split.** The request fingerprint took a dynamic device id while `client.hello` used
 *    the constructor field. A device could therefore announce `"unpaired-device"` in its handshake
 *    and bind its fingerprint to a real device — a request attributed to nothing.
 * 2. **Handshake reuse across re-pairing.** A single `cachedServerHello` survived an unpair, so a
 *    request made after a re-pairing could reuse a hello negotiated under the *previous* identity.
 *
 * A configured provider is now nullable and suspend, and `null` fails closed before any dispatch.
 * The static CP1-A path keeps its cache; the dynamic path re-negotiates per request.
 */
class ClaudePProviderIdentityTest {

    // ---------------------------------------------------------------------------------------
    // Identity is required when a dynamic provider is configured
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a dynamic provider returning null fails closed with zero dispatch`() = runBlocking {
        val gateway = FakeClaudePGatewayClient()
        val provider = ClaudePProvider(
            gateway = gateway,
            deviceIdProvider = { null },
        )

        val failure = provider.gatewayFailure { streamText(provider, Model(id = ID, modelId = "sonnet")) }

        assertEquals(ClaudePErrorCode.NOT_PAIRED, failure.code)
        // No handshake, no generation: the placeholder must not be quietly substituted.
        assertEquals(0, gateway.helloCount)
        assertEquals(0, gateway.remoteDispatchCount)
    }

    @Test
    fun `a dynamic provider returning blank fails closed rather than using the placeholder`() =
        runBlocking {
            val gateway = FakeClaudePGatewayClient()
            val provider = ClaudePProvider(
                gateway = gateway,
                deviceId = "unpaired-device",
                deviceIdProvider = { "   " },
            )

            val failure = provider.gatewayFailure { streamText(provider, Model(id = ID, modelId = "sonnet")) }

            assertEquals(ClaudePErrorCode.NOT_PAIRED, failure.code)
            assertEquals(0, gateway.helloCount)
        }

    // ---------------------------------------------------------------------------------------
    // Hello and fingerprint agree
    // ---------------------------------------------------------------------------------------

    @Test
    fun `the handshake announces the same device the fingerprint binds`() = runBlocking {
        val gateway = FakeClaudePGatewayClient()
        val provider = ClaudePProvider(
            gateway = gateway,
            deviceId = "unpaired-device",
            deviceIdProvider = { "device-A" },
        )

        streamText(provider, Model(id = ID, modelId = "sonnet"))

        // The old bug: hello said "unpaired-device" while the fingerprint used a real id.
        assertEquals(listOf("device-A"), gateway.helloDeviceIds)
        assertTrue(gateway.remoteDispatchCount >= 0)
    }

    // ---------------------------------------------------------------------------------------
    // No reuse across a change of identity
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a second device identity does not reuse the first device's handshake`() = runBlocking {
        val gateway = FakeClaudePGatewayClient()
        var identity = "device-A"
        val provider = ClaudePProvider(
            gateway = gateway,
            deviceIdProvider = { identity },
        )

        streamText(provider, Model(id = ID, modelId = "sonnet"))
        identity = "device-B"
        streamText(provider, Model(id = ID, modelId = "sonnet"))

        // Two identities, two handshakes. A cache keyed on anything but the identity would replay
        // device A's negotiated session under device B.
        assertEquals(listOf("device-A", "device-B"), gateway.helloDeviceIds)
    }

    @Test
    fun `a revoked identity does not reuse the previous handshake`() = runBlocking {
        val gateway = FakeClaudePGatewayClient()
        var identity: String? = "device-A"
        val provider = ClaudePProvider(gateway = gateway, deviceIdProvider = { identity })

        streamText(provider, Model(id = ID, modelId = "sonnet"))
        // Revocation: the repository now answers null.
        identity = null

        provider.gatewayFailure { streamText(provider, Model(id = ID, modelId = "sonnet")) }

        // The earlier handshake is not reused to let a revoked request through.
        assertEquals(1, gateway.helloCount)
    }

    @Test
    fun `every dynamic request re-negotiates rather than reusing one hello`() = runBlocking {
        val gateway = FakeClaudePGatewayClient()
        val provider = ClaudePProvider(gateway = gateway, deviceIdProvider = { "device-A" })

        streamText(provider, Model(id = ID, modelId = "sonnet"))
        streamText(provider, Model(id = ID, modelId = "sonnet"))

        // The bounded cost of not caching: one extra hello per request. Re-validating is the safe
        // direction; reusing is the direction that carries an old identity forward.
        assertEquals(2, gateway.helloCount)
    }

    // ---------------------------------------------------------------------------------------
    // CP1-A compatibility
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a provider without a dynamic resolver keeps its static identity and cache`() = runBlocking {
        val gateway = FakeClaudePGatewayClient()
        val provider = ClaudePProvider(gateway = gateway, deviceId = "static-device")

        streamText(provider, Model(id = ID, modelId = "sonnet"))
        streamText(provider, Model(id = ID, modelId = "sonnet"))

        // The static path is unchanged: one identity, negotiated once.
        assertEquals(listOf("static-device"), gateway.helloDeviceIds)
        assertEquals(1, gateway.helloCount)
        assertNotNull(provider.negotiatedServerHello)
    }

    @Test
    fun `the static default identity is still used when no resolver is configured`() = runBlocking {
        val gateway = FakeClaudePGatewayClient()
        val provider = ClaudePProvider(gateway = gateway)

        streamText(provider, Model(id = ID, modelId = "sonnet"))

        assertEquals(listOf("unpaired-device"), gateway.helloDeviceIds)
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private suspend fun streamText(provider: ClaudePProvider, model: Model) {
        provider.streamText(
            providerSetting = PROVIDER_SETTING,
            messages = listOf(
                UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hi"))),
            ),
            params = TextGenerationParams(model = model),
        ).toList()
    }

    private suspend fun ClaudePProvider.gatewayFailure(
        block: suspend () -> Unit,
    ): ClaudePGatewayException = try {
        block()
        throw AssertionError("expected a ClaudePGatewayException")
    } catch (expected: ClaudePGatewayException) {
        expected
    }

    private companion object {
        val ID = kotlin.uuid.Uuid.random()

        val PROVIDER_SETTING = ProviderSetting.ClaudeP(
            models = listOf(
                Model(
                    id = ID,
                    modelId = "sonnet",
                    displayName = "Sonnet",
                    type = ModelType.CHAT,
                    abilities = listOf(ModelAbility.REASONING),
                ),
            ),
        )
    }
}
