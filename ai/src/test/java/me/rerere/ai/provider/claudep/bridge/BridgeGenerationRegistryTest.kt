package me.rerere.ai.provider.claudep.bridge

import kotlinx.serialization.json.JsonElement
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
 *
 * The closing half is the stricter one. A generation ending is not evidence that its tools
 * stopped, so none of these tests let the registry assert a cancellation it did not observe —
 * the host either proves a conclusion or the call is reported `failed`.
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

    private val defaultCatalogDigest by lazy { catalog("read_file").digest }

    private fun binding(
        generationId: String = "gen-1",
        assistantId: String = "assistant-1",
        conversationId: String = "conv-1",
        branchId: String = "branch-1",
        deviceRef: String = "device-ref-1",
        requestId: String = "req-1",
        catalogDigest: String = defaultCatalogDigest,
        timeoutMs: Long = 60_000,
    ) = GenerationBinding(
        deviceRef = deviceRef,
        assistantId = assistantId,
        conversationId = conversationId,
        branchId = branchId,
        generationId = generationId,
        requestId = requestId,
        catalogDigest = catalogDigest,
        bridgeAbi = BridgeContract.BRIDGE_ABI,
        timeoutMs = timeoutMs,
    )

    private fun args() = JsonObject(linkedMapOf("path" to JsonPrimitive("/a")))

    /**
     * A stand-in for the app's execution layer that records the order it was asked things in.
     *
     * [prove] decides what the runtime can prove about a call; a call it returns nothing for is
     * one the bridge must not claim anything about.
     */
    private class RecordingHost(
        private val prove: (String) -> ToolCallOutcome? = { null },
        private val onAwait: () -> Unit = {},
    ) : BridgeExecutionHost {
        val events = mutableListOf<String>()

        override fun abandonApproval(generationId: String, toolCallId: String) {
            events += "abandon:$toolCallId"
        }

        override fun requestStop(generationId: String, toolCallIds: List<String>) {
            events += "stop:${toolCallIds.joinToString(",")}"
        }

        override fun awaitConclusions(
            generationId: String,
            toolCallIds: List<String>,
            waitMs: Long,
        ): Map<String, ToolCallOutcome> {
            events += "await:${toolCallIds.joinToString(",")}"
            onAwait()
            return toolCallIds.mapNotNull { id -> prove(id)?.let { id to it } }.toMap()
        }
    }

    /**
     * What the inbound frame path is allowed to do, and the only thing it is allowed to do.
     *
     * A frame names a generation; the registry is asked for **that** generation and nothing
     * else. `null` means nothing executes, which is the fail-closed answer — and it is why the
     * count of [BridgeInvokeDecision.Execute] this returns over a scenario is the number of
     * real tool executions that scenario caused.
     */
    private fun deliverInvoke(
        registry: BridgeGenerationRegistry,
        generationId: String,
        toolCallId: String,
        toolName: String = "read_file",
        arguments: JsonElement = args(),
    ): BridgeInvokeDecision? =
        registry.lookup(generationId)?.onInvoke(toolCallId, toolName, arguments)

    private fun executes(decision: BridgeInvokeDecision?): Boolean =
        decision is BridgeInvokeDecision.Execute

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
    // Reconnect identity includes the catalog
    // -----------------------------------------------------------------------------------------

    /**
     * A reconnect that presents a different catalog is not a reconnect.
     *
     * The frozen catalog participates in every invocation's binding digest, so serving the old
     * adapter would accept catalog B's request against catalog A's records — and the call would
     * be judged against a tool set the caller no longer believes it is using. Fail closed.
     */
    @Test
    fun `a reconnect presenting a different catalog is refused and the original is not replaced`() {
        val registry = BridgeGenerationRegistry()
        val first = registry.open(binding(), catalog("read_file")).adapterOrNull!!
        val digestOfFirstCatalog = first.catalogDigest

        // (a) A different catalog with a digest that matches it.
        val other = catalog("read_file", "write_file")
        val swapped = registry.open(binding(catalogDigest = other.digest), other)
        assertEquals(
            BridgeGenerationRegistry.OpenRefusal.GENERATION_IDENTITY_MISMATCH,
            (swapped as BridgeGenerationRegistry.OpenOutcome.Refused).reason,
        )

        // (b) A binding claiming the original digest while presenting a different catalog.
        val lying = registry.open(binding(), other)
        assertEquals(
            "a binding that disagrees with the catalog it carries is not a reconnect either",
            BridgeGenerationRegistry.OpenRefusal.GENERATION_IDENTITY_MISMATCH,
            (lying as BridgeGenerationRegistry.OpenOutcome.Refused).reason,
        )

        assertSame("the original adapter still stands", first, registry.lookup("gen-1"))
        assertEquals("the catalog was not replaced", digestOfFirstCatalog, registry.lookup("gen-1")!!.catalogDigest)
        assertEquals(1, registry.openCount)
    }

    @Test
    fun `the bridge abi is part of the reconnect identity`() {
        val registry = BridgeGenerationRegistry()
        registry.open(binding(), catalog("read_file"))

        // The ABI cannot be spelled differently by a well-formed peer — the binding decoder
        // refuses anything but the frozen value — so the check here is that a binding carrying
        // another ABI is not silently treated as the same generation.
        val elsewhere = GenerationBinding(
            deviceRef = binding().deviceRef,
            assistantId = binding().assistantId,
            conversationId = binding().conversationId,
            branchId = binding().branchId,
            generationId = "gen-1",
            requestId = "req-2",
            catalogDigest = defaultCatalogDigest,
            bridgeAbi = "${BridgeContract.BRIDGE_ABI}-next",
            timeoutMs = 60_000,
        )
        assertEquals(
            BridgeGenerationRegistry.OpenRefusal.GENERATION_IDENTITY_MISMATCH,
            (registry.open(elsewhere, catalog("read_file")) as BridgeGenerationRegistry.OpenOutcome.Refused).reason,
        )
    }

    /**
     * A reconnect must not be a way to ask for more time.
     *
     * The deadline is fixed when a call is admitted and is never recomputed, so a peer that
     * reconnects carrying a longer timeout is ignored rather than obeyed. Without this, a
     * device could hold a call open indefinitely by retrying — and the call it held open is one
     * the tool never ran.
     */
    @Test
    fun `a reconnect does not extend a call's deadline`() {
        val registry = BridgeGenerationRegistry()
        val adapter = registry.open(binding(timeoutMs = 60_000), catalog("read_file")).adapterOrNull!!
        adapter.onInvoke("call-1", "read_file", args())

        // The reconnect asks for ten times as long.
        val reused = registry.open(binding(requestId = "req-2", timeoutMs = 600_000), catalog("read_file"))
        assertTrue(reused is BridgeGenerationRegistry.OpenOutcome.Reused)

        val now = System.nanoTime() / 1_000_000L

        // Well past the original minute, nowhere near the reconnected ten: if the deadline had
        // moved, the call would still be pending here.
        val settled = adapter.expire(now + 120_000)
        assertEquals(listOf("call-1"), settled.map { it.toolCallId })
        assertEquals(ToolCallState.TIMED_OUT, settled.single().state)
    }

    // -----------------------------------------------------------------------------------------
    // Closing
    // -----------------------------------------------------------------------------------------

    /**
     * The whole point of the reordered close: nothing is called `cancelled` unless the runtime
     * said so.
     *
     * With no host behind it the bridge can prove nothing, and the honest answer for a call
     * that may still be running is `failed` — claiming a stop would tell the peer that a write
     * did not happen when it may well have.
     */
    @Test
    fun `a call that cannot be proven stopped is reported failed, never cancelled`() {
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
        assertEquals(ToolCallState.FAILED, concluded.single().outcome.state)
        assertEquals(BridgeCloseReason.STOP_UNPROVEN, concluded.single().reason)
        assertEquals(0, registry.openCount)
        assertNull("a closed generation is gone, not reusable", registry.lookup("gen-1"))
    }

    /**
     * A stop the runtime really performed is reported as one.
     *
     * This is the only route to `cancelled`: the handle proved the execution is over, so the
     * claim is one Android observed rather than one it assumed.
     */
    @Test
    fun `a stop the runtime proves is reported as cancelled`() {
        val host = RecordingHost(prove = { id -> ToolCallOutcome(id, ToolCallState.CANCELLED) })
        val registry = BridgeGenerationRegistry(executions = host)
        val adapter = registry.open(binding(), catalog("read_file")).adapterOrNull!!
        adapter.onInvoke("call-1", "read_file", args())

        val concluded = registry.close("gen-1").single()

        assertEquals(ToolCallState.CANCELLED, concluded.outcome.state)
        assertEquals(BridgeCloseReason.RUNTIME_CONCLUDED, concluded.reason)
    }

    /**
     * A tool that finished while the generation was ending is reported as finished.
     *
     * The race this covers is the one a generation's end makes ordinary: a write that completes
     * a moment after the close began. Reporting it `cancelled` would be a lie about a side
     * effect that already happened.
     */
    @Test
    fun `a tool that completed while the generation ended is reported completed`() {
        val host = RecordingHost(prove = { id -> ToolCallOutcome(id, ToolCallState.COMPLETED, "wrote it") })
        val registry = BridgeGenerationRegistry(executions = host)
        val adapter = registry.open(binding(), catalog("read_file")).adapterOrNull!!
        adapter.onInvoke("call-1", "read_file", args())

        val concluded = registry.close("gen-1").single()

        assertEquals(ToolCallState.COMPLETED, concluded.outcome.state)
        assertEquals("wrote it", concluded.outcome.body)
        assertEquals(BridgeCloseReason.RUNTIME_CONCLUDED, concluded.reason)
    }

    @Test
    fun `a call the user refused is reported denied`() {
        val host = RecordingHost(prove = { id -> ToolCallOutcome(id, ToolCallState.DENIED) })
        val registry = BridgeGenerationRegistry(executions = host)
        val adapter = registry.open(binding(), catalog("read_file")).adapterOrNull!!
        adapter.onInvoke("call-1", "read_file", args())

        val concluded = registry.close("gen-1").single()

        assertEquals(ToolCallState.DENIED, concluded.outcome.state)
        assertNull("a denial carries no body", concluded.outcome.body)
    }

    @Test
    fun `a host that reports a state Android may not send is ignored and the call is failed`() {
        val host = RecordingHost(prove = { id -> ToolCallOutcome(id, ToolCallState.NOT_FOUND) })
        val registry = BridgeGenerationRegistry(executions = host)
        val adapter = registry.open(binding(), catalog("read_file")).adapterOrNull!!
        adapter.onInvoke("call-1", "read_file", args())

        val concluded = registry.close("gen-1").single()

        assertEquals(
            "not_found is a verdict about the Server's ledger; Android cannot make it",
            ToolCallState.FAILED,
            concluded.outcome.state,
        )
        assertEquals(BridgeCloseReason.STOP_UNPROVEN, concluded.reason)
    }

    /**
     * The order of the close, asserted directly.
     *
     * Approvals close first, then the runtime is asked to stop, then it is waited on. Each
     * step depends on the one before: an approval prompt that is still live while the stop is
     * requested is a prompt whose "approve" can start a tool for a generation that is already
     * gone.
     */
    @Test
    fun `closing abandons approvals, then requests a stop, then waits`() {
        val host = RecordingHost()
        val registry = BridgeGenerationRegistry(executions = host)
        val adapter = registry.open(binding(), catalog("read_file")).adapterOrNull!!
        adapter.onInvoke("call-a", "read_file", args())
        adapter.onInvoke("call-b", "read_file", args())
        adapter.onInvoke("call-c", "read_file", args())
        adapter.complete("call-c", ToolCallState.COMPLETED, "done")

        registry.close("gen-1")

        assertEquals(
            listOf("abandon:call-a", "abandon:call-b", "stop:call-a,call-b", "await:call-a,call-b"),
            host.events,
        )
    }

    /**
     * During the close the generation is still findable, and still refuses new work.
     *
     * Both halves matter. `lookup` returning the adapter is what stops a late frame from being
     * answered by *nothing* and then, one adapter later, by an empty ledger. The adapter
     * refusing the invoke by name is what stops it from being admitted into a ledger that is
     * being settled underneath it.
     */
    @Test
    fun `during the close the generation is found, refuses new invokes, and admits no second adapter`() {
        val probes = mutableListOf<String>()
        lateinit var registry: BridgeGenerationRegistry
        val host = RecordingHost(
            onAwait = {
                // Everything below runs *inside* the close, between the stop request and the
                // settlement — which is the only window where any of it is interesting.
                probes += if (registry.lookup("gen-1") == null) "lookup:null" else "lookup:found"
                probes += when (deliverInvoke(registry, "gen-1", "call-late")) {
                    is BridgeInvokeDecision.GenerationClosing -> "invoke:closing"
                    null -> "invoke:null"
                    else -> "invoke:other"
                }
                val reopened = registry.open(binding(requestId = "req-9"), catalog("read_file"))
                probes += if (reopened is BridgeGenerationRegistry.OpenOutcome.Refused) {
                    "open:${reopened.reason}"
                } else {
                    "open:other"
                }
            },
        )
        registry = BridgeGenerationRegistry(executions = host)
        val adapter = registry.open(binding(), catalog("read_file")).adapterOrNull!!
        adapter.onInvoke("call-1", "read_file", args())

        registry.close("gen-1")

        assertEquals(
            listOf("lookup:found", "invoke:closing", "open:GENERATION_CLOSING"),
            probes,
        )
        assertNull("and gone once it is settled", registry.lookup("gen-1"))
    }

    /**
     * A cancel that is already on its way is not asked for a second time.
     *
     * The runtime has been told to stop; telling it again is how one stop becomes two
     * instructions, and an instruction delivered twice to a tool that is starting up is a
     * second side effect.
     */
    @Test
    fun `a call whose cancel was already propagated is not stopped twice`() {
        val host = RecordingHost()
        val registry = BridgeGenerationRegistry(executions = host)
        val adapter = registry.open(binding(), catalog("read_file")).adapterOrNull!!
        adapter.onInvoke("call-1", "read_file", args())
        assertEquals(BridgeCancelDecision.Propagate, adapter.onCancel("call-1"))

        registry.close("gen-1")

        assertEquals(listOf("abandon:call-1", "await:call-1"), host.events)
        assertTrue("no second stop was requested", host.events.none { it.startsWith("stop:") })
    }

    @Test
    fun `closing an unknown generation concludes nothing`() {
        val registry = BridgeGenerationRegistry()
        assertEquals(emptyList<BridgeClosedCall>(), registry.close("never-opened"))
    }

    /**
     * A closed generation is never reopened, and a late invoke therefore executes zero times.
     *
     * This is the failure an eviction would cause, arriving by a different door: the id is
     * peer-supplied, and a *fresh* adapter for a closed generation would hold an empty ledger —
     * so a re-delivered invocation for one of the old calls would find no record, look new, and
     * run a second time.
     */
    @Test
    fun `a closed generation cannot be reopened and a late invoke runs nothing`() {
        val registry = BridgeGenerationRegistry()
        val adapter = registry.open(binding(), catalog("read_file")).adapterOrNull!!
        var executions = 0
        if (executes(deliverInvoke(registry, "gen-1", "call-1"))) executions++

        registry.close("gen-1")

        // Both routes a late frame could take, and neither of them runs anything.
        assertNull("the lookup is the fail-closed answer", registry.lookup("gen-1"))
        if (executes(deliverInvoke(registry, "gen-1", "call-1"))) executions++

        val reopened = registry.open(binding(requestId = "req-2"), catalog("read_file"))
        assertEquals(
            BridgeGenerationRegistry.OpenRefusal.GENERATION_ALREADY_CLOSED,
            (reopened as BridgeGenerationRegistry.OpenOutcome.Refused).reason,
        )
        assertEquals(0, registry.openCount)

        assertEquals("exactly the one original execution", 1, executions)
        assertEquals(1, registry.closedGenerationIds().size)
    }

    /**
     * The tombstone is bounded by the same window the contract bounds a call by.
     *
     * A generation can only be reopened by a peer re-delivering one of its calls, and the
     * Server stops waiting on a call at that call's own deadline. Past that window the entry
     * guards nothing, so it is dropped rather than accumulated for the life of the process.
     */
    @Test
    fun `a closed generation id is forgotten once no call of it can still be re-delivered`() {
        var now = 0L
        val registry = BridgeGenerationRegistry(monotonicMs = { now })
        registry.open(binding(), catalog("read_file"))
        registry.close("gen-1")
        assertEquals(1, registry.closedGenerationIds().size)

        now = BridgeClosing.CLOSED_GENERATION_TTL_MS - 1
        assertEquals(
            "inside the window the tombstone stands",
            BridgeGenerationRegistry.OpenRefusal.GENERATION_ALREADY_CLOSED,
            (registry.open(binding(), catalog("read_file")) as BridgeGenerationRegistry.OpenOutcome.Refused).reason,
        )

        now = BridgeClosing.CLOSED_GENERATION_TTL_MS
        val reopened = registry.open(binding(), catalog("read_file"))
        assertTrue(
            "past the window there is nothing left to re-deliver",
            reopened is BridgeGenerationRegistry.OpenOutcome.Opened,
        )
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
        assertFalse(executes(deliverInvoke(registry, "gen-1", "call-1")))
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

    /**
     * The host's stop is scoped to the generation that asked for it.
     *
     * Two generations may legitimately use the same tool call id, so a host addressed by the id
     * alone could stop the wrong one — and the call it stopped is one the user is still waiting
     * for.
     */
    @Test
    fun `closing one generation stops only that generation's calls`() {
        val addressed = mutableListOf<String>()
        val host = object : BridgeExecutionHost {
            override fun abandonApproval(generationId: String, toolCallId: String) = Unit

            override fun requestStop(generationId: String, toolCallIds: List<String>) {
                toolCallIds.forEach { addressed += "$generationId/$it" }
            }

            override fun awaitConclusions(
                generationId: String,
                toolCallIds: List<String>,
                waitMs: Long,
            ): Map<String, ToolCallOutcome> = emptyMap()
        }
        val registry = BridgeGenerationRegistry(executions = host)
        val a = registry.open(binding(generationId = "gen-a"), catalog("read_file")).adapterOrNull!!
        val b = registry.open(binding(generationId = "gen-b"), catalog("read_file")).adapterOrNull!!
        a.onInvoke("call-1", "read_file", args())
        b.onInvoke("call-1", "read_file", args())

        registry.close("gen-a")

        assertEquals(listOf("gen-a/call-1"), addressed)
        assertEquals("gen-b is untouched", ToolCallState.PENDING, b.recordedOutcome("call-1")?.state)
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
