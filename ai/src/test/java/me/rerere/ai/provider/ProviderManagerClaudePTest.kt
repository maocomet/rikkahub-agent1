package me.rerere.ai.provider

import me.rerere.ai.provider.claudep.UnpairedClaudePGatewayClient
import me.rerere.ai.provider.providers.ClaudePProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Type-to-provider dispatch.
 *
 * The manager itself needs an Android `Context`, which a JVM unit test cannot construct, so
 * `getProviderByType` delegates to the pure [providerRegistryKeyOf] and the registry is injectable.
 * That keeps the branch that matters — "does a Claude P setting reach the Claude P provider?" —
 * under test rather than under review.
 */
class ProviderManagerClaudePTest {

    @Test
    fun `claude_p maps to its own registry key`() {
        assertEquals(CLAUDEP_REGISTRY_KEY, providerRegistryKeyOf(ProviderSetting.ClaudeP()))
    }

    /**
     * The failure this prevents: Claude P silently reusing the Anthropic API provider, which would
     * send a device-scoped request to `api.anthropic.com` with no credentials.
     */
    @Test
    fun `claude_p does not share a key with claude, openai or codex`() {
        val claudeP = providerRegistryKeyOf(ProviderSetting.ClaudeP())

        assertNotEquals(providerRegistryKeyOf(ProviderSetting.Claude()), claudeP)
        assertNotEquals(providerRegistryKeyOf(ProviderSetting.OpenAI()), claudeP)
        assertNotEquals(providerRegistryKeyOf(ProviderSetting.Codex()), claudeP)
        assertNotEquals(providerRegistryKeyOf(ProviderSetting.AICore()), claudeP)
        assertNotEquals(providerRegistryKeyOf(ProviderSetting.Google()), claudeP)
        assertNotEquals(providerRegistryKeyOf(ProviderSetting.LiteRtLocal()), claudeP)
    }

    @Test
    fun `every provider type maps to a distinct key`() {
        val keys = listOf(
            providerRegistryKeyOf(ProviderSetting.OpenAI()),
            providerRegistryKeyOf(ProviderSetting.Google()),
            providerRegistryKeyOf(ProviderSetting.Claude()),
            providerRegistryKeyOf(ProviderSetting.AICore()),
            providerRegistryKeyOf(ProviderSetting.LiteRtLocal()),
            providerRegistryKeyOf(ProviderSetting.Codex()),
            providerRegistryKeyOf(ProviderSetting.ClaudeP()),
        )

        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `a claude_p setting resolves to the registered claude p provider`() {
        val claudeP = ClaudePProvider(UnpairedClaudePGatewayClient)
        val manager = ProviderManager.withRegistry(CLAUDEP_REGISTRY_KEY to claudeP)

        val resolved = manager.getProviderByType(ProviderSetting.ClaudeP())

        assertSame(claudeP, resolved)
    }

    /**
     * Fail closed. When the Claude P transport is missing, the manager must raise rather than
     * quietly hand back whichever provider happens to be registered first.
     */
    @Test
    fun `a claude_p setting never falls back to a generic provider`() {
        val manager = ProviderManager.withRegistry(
            "openai" to ClaudePProvider(UnpairedClaudePGatewayClient),
            "claude" to ClaudePProvider(UnpairedClaudePGatewayClient),
        )

        assertThrows(IllegalArgumentException::class.java) {
            manager.getProviderByType(ProviderSetting.ClaudeP())
        }
    }

    @Test
    fun `the registry still answers other types after claude p is added`() {
        val openAi = ClaudePProvider(UnpairedClaudePGatewayClient)
        val claudeP = ClaudePProvider(UnpairedClaudePGatewayClient)
        val manager = ProviderManager.withRegistry(
            "openai" to openAi,
            CLAUDEP_REGISTRY_KEY to claudeP,
        )

        assertSame(openAi, manager.getProviderByType(ProviderSetting.OpenAI()))
        assertSame(claudeP, manager.getProviderByType(ProviderSetting.ClaudeP()))
    }

    @Test
    fun `registering claude p twice keeps the last binding`() {
        val first = ClaudePProvider(UnpairedClaudePGatewayClient)
        val second = ClaudePProvider(UnpairedClaudePGatewayClient)
        val manager = ProviderManager.withRegistry(CLAUDEP_REGISTRY_KEY to first)

        manager.registerProvider(CLAUDEP_REGISTRY_KEY, second)

        assertSame(second, manager.getProviderByType(ProviderSetting.ClaudeP()))
    }

    @Test
    fun `the registry key constant is stable`() {
        assertEquals("claude_p", CLAUDEP_REGISTRY_KEY)
    }
}
