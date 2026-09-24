package me.rerere.ai.provider.claudep.bridge

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lifecycle of the per-generation tool adapters.
 *
 * Codex's ruling accepted one adapter per generation *conditional on the lifecycle being
 * correct*, and this file is that condition made checkable. The properties that matter are
 * all of the form "a thing that would cause a second execution must be impossible", because
 * a tool runs for real and there is no undo.
 */
class BridgeGenerationRegistryTest {

    private fun schema(): JsonObject = JsonObject(
        linkedMapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(
                linkedMapOf("path" to JsonObject(linkedMapOf("type" to JsonPrimitive("string")))),
            ),
        ),
    )

    private fun candidate(name: String) =
        BridgeToolCandidate.provenReadOnly(name, "reads", schema(), ToolSource.LOCAL)

    private fun catalog(vararg names: String) =
        BridgeToolCatalog.build(names.map(::candidate)).catalog

    private fun binding(
        generationId: String = "gen-1",
        assistantId: String = "assistant-1",
        conversationId: String = "conv-1",
        branchId: String = "branch-1",
        deviceRef: String = "device-ref-1",
        requestId: String = "req-1",
    ) = GenerationBinding(
        deviceRef = deviceRef,
        assistantId = assistantId,
        conversationId = conversationId,
        branchId = branchId,
        generationId = generationId,
        requestId = requestId,
        catalogDigest = catalog("read_file").digest,
        bridgeAbi = BridgeContract.BRIDGE_ABI,
        timeoutMs = 60_000,
    )

    private fun args() = JsonObject(linkedMapOf("path" to JsonPrimitive("/a")))

    // -----------------------------------------------------------------------------------------

    @Test
    fun `a generation is opened once and found by its exact id`() {
        val registry = BridgeGenerationRegistry()
        val outcome = registry.open(binding(), catalog("read_file"))

        assertTrue(outcome is BridgeGenerationRegistry.OpenOutcome.Opened)
        assertSame(outcome.adapterOrNull, registry.lookup("gen-1"))
        assertNull("a generation this registry does not hold is null, not a near match", registry.lookup("gen-2"))
        assertNull(registry.lookup(null))
        assertEquals(1, registry.openCount)
    }

    /**
     * The reconnect rule. A re-open must return the adapter that is already there, because a
     * fresh one would hold an empty ledger — and an invocation the Server re-delivers after a
     * reconnect would then find no record, look new, and run the tool a second time.
     */
    @Test
    fun `re-opening a generation returns the same adapter with its ledger intact`() {
        val registry = BridgeGenerationRegistry()
        val first = registry.open(binding(), catalog("read_file")).adapterOrNull!!
        first.onInvoke("call-1", "read_file", args())
        assertEquals(1, first.pendingCount)

        // A reconnect: same conversation, new request id, catalog re-supplied.
        val second = registry.open(binding(requestId = "req-2"), catalog("read_file"))
        assertTrue("a reconnect is not a fresh start", second is BridgeGenerationRegistry.OpenOutcome.Reused)
        assertSame("the ledger must survive the reconnect", first, second.adapterOrNull)

        // And the re-delivered invoke attaches rather than executing again.
        assertTrue(
            second.adapterOrNull!!.onInvoke("call-1", "read_file", args()) is BridgeInvokeDecision.Await,
        )
        assertEquals(1, registry.openCount)
    }

    @Test
    fun `a different identity under the same generation id is refused`() {
        val registry = BridgeGenerationRegistry()
        registry.open(binding(), catalog("read_file"))

        for (changed in listOf(
            binding(assistantId = "assistant-2"),
            binding(conversationId = "conv-2"),
            binding(branchId = "branch-2"),
            binding(deviceRef = "device-ref-2"),
        )) {
            val outcome = registry.open(changed, catalog("read_file"))
            assertEquals(
                "a generation id is not a name two conversations may share",
                BridgeGenerationRegistry.OpenRefusal.GENERATION_IDENTITY_MISMATCH,
                (outcome as BridgeGenerationRegistry.OpenOutcome.Refused).reason,
            )
        }
    }

    /**
     * The bound refuses rather than evicts. An LRU eviction discards a live ledger, and the
     * next re-delivery of one of its calls would be admitted as fresh and execute again — a
     * bound that can cause a second execution is worse than no bound.
     */
    @Test
    fun `the generation bound refuses a new generation rather than evicting a live one`() {
        val registry = BridgeGenerationRegistry(maxOpenGenerations = 2)
        val a = registry.open(binding(generationId = "gen-a"), catalog("read_file")).adapterOrNull!!
        a.onInvoke("call-1", "read_file", args())

        registry.open(binding(generationId = "gen-b"), catalog("read_file"))

        val refused = registry.open(binding(generationId = "gen-c"), catalog("read_file"))
        assertEquals(
            BridgeGenerationRegistry.OpenRefusal.TOO_MANY_OPEN_GENERATIONS,
            (refused as BridgeGenerationRegistry.OpenOutcome.Refused).reason,
        )

        assertSame("the oldest generation is still open, ledger and all", a, registry.lookup("gen-a"))
        assertEquals(1, a.pendingCount)
        assertNull(registry.lookup("gen-c"))
    }

    // -----------------------------------------------------------------------------------------

    @Test
    fun `closing a generation concludes its pending calls and releases it`() {
        val registry = BridgeGenerationRegistry()
        val adapter = registry.open(binding(), catalog("read_file")).adapterOrNull!!
        adapter.onInvoke("call-1", "read_file", args())
        adapter.onInvoke("call-2", "read_file", args())
        adapter.complete("call-2", ToolCallState.COMPLETED, "done")

        val concluded = registry.close("gen-1")

        assertEquals(
            "only the call that had not settled is concluded",
            listOf("call-1"),
            concluded.map { it.toolCallId },
        )
        assertEquals(ToolCallState.CANCELLED, concluded.single().state)
        assertEquals(0, registry.openCount)
        assertNull("a closed generation is gone, not reusable", registry.lookup("gen-1"))
    }

    @Test
    fun `closing an unknown generation concludes nothing`() {
        val registry = BridgeGenerationRegistry()
        assertEquals(emptyList<ToolCallOutcome>(), registry.close("never-opened"))
    }

    /**
     * After a generation is closed, an adapter opened for the same id is a different object —
     * which is exactly why the inbound-frame path must only ever call `lookup`, and why a
     * `null` there is the fail-closed answer rather than a cue to open one.
     */
    @Test
    fun `an inbound frame for a closed generation finds nothing and executes nothing`() {
        val registry = BridgeGenerationRegistry()
        val adapter = registry.open(binding(), catalog("read_file")).adapterOrNull!!
        adapter.onInvoke("call-1", "read_file", args())
        registry.close("gen-1")

        // The only thing a frame handler is allowed to do.
        assertNull(registry.lookup("gen-1"))
    }

    @Test
    fun `closing every generation reports each generation's calls separately`() {
        val registry = BridgeGenerationRegistry()
        val a = registry.open(binding(generationId = "gen-a"), catalog("read_file")).adapterOrNull!!
        val b = registry.open(binding(generationId = "gen-b"), catalog("read_file")).adapterOrNull!!
        a.onInvoke("call-a", "read_file", args())
        b.onInvoke("call-b", "read_file", args())

        val closed = registry.closeAll()

        assertEquals(setOf("gen-a", "gen-b"), closed.keys)
        assertEquals(listOf("call-a"), closed.getValue("gen-a").map { it.toolCallId })
        assertEquals(listOf("call-b"), closed.getValue("gen-b").map { it.toolCallId })
        assertEquals(0, registry.openCount)
    }

    // -----------------------------------------------------------------------------------------

    /**
     * Two generations using the *same* tool call id stay strictly isolated.
     *
     * This is the case the ledger key — the tool call id alone — looks like it would confuse,
     * and the reason it does not is structural: each generation has its own ledger, and the
     * registry cannot be asked for "the call with this id", only for a generation. A frame is
     * therefore answered by the records of the generation it named and by no other.
     */
    @Test
    fun `two generations sharing a tool call id do not see each other`() {
        val registry = BridgeGenerationRegistry()
        val a = registry.open(binding(generationId = "gen-a"), catalog("read_file")).adapterOrNull!!
        val b = registry.open(binding(generationId = "gen-b"), catalog("read_file")).adapterOrNull!!

        assertTrue(a.onInvoke("call-1", "read_file", args()) is BridgeInvokeDecision.Execute)
        b.onInvoke("call-1", "read_file", args())
        a.complete("call-1", ToolCallState.COMPLETED, "from-a")

        assertEquals("from-a", a.recordedOutcome("call-1")?.body)
        assertEquals(
            "the same id in another generation is a different call, still running",
            ToolCallState.PENDING,
            b.recordedOutcome("call-1")?.state,
        )

        // And the reverse direction: settling b does not disturb a's answer.
        b.complete("call-1", ToolCallState.DENIED)
        assertEquals("from-a", a.recordedOutcome("call-1")?.body)
        assertEquals(ToolCallState.DENIED, b.recordedOutcome("call-1")?.state)
    }

    // -----------------------------------------------------------------------------------------

    /**
     * A call this side cannot prove anything about is reported `failed`.
     *
     * After a process restart there is no record, and the contract forbids Android from saying
     * `not_found` — that is a verdict about the Server's ledger. The tempting mistake is to
     * forward the local lookup's answer, which is what this asserts against.
     */
    @Test
    fun `a lost call is reported failed and never not_found`() {
        val outcome = BridgeOutcomes.lostCallOutcome("call-1")
        assertEquals(ToolCallState.FAILED, outcome.state)
        assertTrue("Android may not report the Server's own verdicts", outcome.state.isAndroidReportable())
        assertNotSame(ToolCallState.NOT_FOUND, outcome.state)
    }

    @Test
    fun `a lost call is not an invitation to re-run the tool`() {
        val registry = BridgeGenerationRegistry()
        // No generation is open — what a restart looks like.
        assertNull(registry.lookup("gen-1"))
        assertEquals(
            "nothing was dispatched and nothing is pending",
            0,
            registry.openCount,
        )
    }

    // -----------------------------------------------------------------------------------------

    @Test
    fun `an adapter refuses a call for a generation it does not serve`() {
        val registry = BridgeGenerationRegistry()
        val adapter = registry.open(binding(generationId = "gen-1"), catalog("read_file")).adapterOrNull!!

        assertTrue(adapter.serves("gen-1"))
        assertFalse("a frame naming another generation is not served here", adapter.serves("gen-2"))
        assertFalse(adapter.serves(null))
    }
}
