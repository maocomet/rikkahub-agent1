package me.rerere.ai.provider.providers

import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.claudep.ClaudePErrorCode
import me.rerere.ai.provider.claudep.ClaudePGatewayException
import me.rerere.ai.provider.claudep.FakeClaudePGatewayClient
import me.rerere.ai.provider.claudep.InMemoryClaudePPairingSettingsGateway
import me.rerere.ai.provider.claudep.UnpairedClaudePGatewayClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Catalog persistence: what a successful read leaves behind, and what a failed one must not.
 *
 * ### Why this is worth its own file
 *
 * Before this, `cachedModels`, `catalogCachedAt` and `claudeCodeVersion` had **no production
 * writer at all** — the only assignments anywhere were `emptyList()` and `null` in the pairing
 * lifecycle. A phone could therefore pair, reach the gateway, read a catalog and still show
 * "Claude Code: Unavailable" with an empty model list, because nothing ever filled those columns.
 * The columns existed, were serialized, were shown, and were never written.
 *
 * So the assertions here are about the columns actually being written, and about the three
 * properties that keep the write honest:
 *
 * 1. it happens **only** after a successful read, so a failure cannot replace a good cache with an
 *    empty one;
 * 2. it carries the version from the **same** handshake, so the columns never describe two
 *    different connections at once;
 * 3. it dispatches nothing, because a directory read must never become a model run.
 */
class ClaudePProviderCatalogTest {

    private val setting = ProviderSetting.ClaudeP()

    private fun provider(
        gateway: me.rerere.ai.provider.claudep.ClaudePGatewayClient,
        recorder: InMemoryClaudePPairingSettingsGateway?,
    ) = ClaudePProvider(
        gateway = gateway,
        requestIdFactory = { "req-fixed" },
        catalogRecorder = recorder,
    )

    @Test
    fun `a successful catalog read records the enabled entries and the negotiated version`() = runBlocking {
        val recorder = InMemoryClaudePPairingSettingsGateway()
        val gateway = FakeClaudePGatewayClient(claudeCodeVersion = "2.1.236")

        provider(gateway, recorder).listModels(setting)

        val (entries, version) = requireNotNull(recorder.recordedCatalog)
        assertEquals(listOf("sonnet", "haiku", "opus"), entries.map { it.alias })
        // The disabled alias is not cached. The cache is what the screen offers a user, so caching
        // something unpickable would put a model in the list that can never be dispatched.
        assertTrue(entries.none { it.alias == "retired" })
        // The version comes from the handshake this read used, not from a constant and not from a
        // previous connection's cache.
        assertEquals("2.1.236", version)
        assertEquals(1, recorder.recordedCatalogCount)
    }

    @Test
    fun `the cached entry carries exactly what the live mapping would show`() = runBlocking {
        val recorder = InMemoryClaudePPairingSettingsGateway()
        val gateway = FakeClaudePGatewayClient()

        val models = provider(gateway, recorder).listModels(setting)
        val (entries, _) = requireNotNull(recorder.recordedCatalog)

        // The cached view and the fetched view must agree, field for field, or the offline list
        // would describe a different set of models than the online one just did.
        assertEquals(models.map { it.modelId }, entries.map { it.alias })
        assertEquals(models.map { it.displayName }, entries.map { it.displayName })
        assertEquals(
            models.map { it.abilities.contains(me.rerere.ai.provider.ModelAbility.REASONING) },
            entries.map { it.reasoningSummary },
        )
    }

    @Test
    fun `a catalog read starts no generation`() = runBlocking {
        val recorder = InMemoryClaudePPairingSettingsGateway()
        val gateway = FakeClaudePGatewayClient()

        provider(gateway, recorder).listModels(setting)

        // The whole point of the catalog path: it is a directory read. A refresh that dispatched
        // would spend the user's subscription to populate a list.
        assertEquals(0, gateway.startGenerationCallCount)
        assertEquals(0, gateway.remoteDispatchCount)
    }

    @Test
    fun `a failed catalog read records nothing`() {
        val recorder = InMemoryClaudePPairingSettingsGateway()

        val failure = assertThrows(ClaudePGatewayException::class.java) {
            runBlocking { provider(UnpairedClaudePGatewayClient, recorder).listModels(setting) }
        }

        assertEquals(ClaudePErrorCode.NOT_PAIRED, failure.code)
        // Nothing was written, so a cache that existed before the failure survives it. Writing here
        // is what would turn "the refresh failed" into "this gateway offers no models".
        assertNull(recorder.recordedCatalog)
        assertEquals(0, recorder.recordedCatalogCount)
    }

    @Test
    fun `a failed write is reported and does not fail the read`() = runBlocking {
        val recorder = InMemoryClaudePPairingSettingsGateway().apply { writesFail = true }
        val gateway = FakeClaudePGatewayClient()

        // Persistence is a side effect of a successful read, not a precondition for one: a settings
        // write that cannot land must not turn a catalog the user just fetched into an error.
        val models = provider(gateway, recorder).listModels(setting)

        assertEquals(listOf("sonnet", "haiku", "opus"), models.map { it.modelId })
        assertNull(recorder.recordedCatalog)
        assertEquals(1, recorder.failedWriteCount)
    }

    @Test
    fun `a provider with no recorder still reads its catalog`() = runBlocking {
        // The default, and every other provider's shape: no recorder means nothing is persisted and
        // nothing else changes. This is the "other providers are unaffected" control.
        val models = provider(FakeClaudePGatewayClient(), recorder = null).listModels(setting)

        assertEquals(listOf("sonnet", "haiku", "opus"), models.map { it.modelId })
    }
}
