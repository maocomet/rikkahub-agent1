package me.rerere.rikkahub.data.claudep

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.claudep.BridgeToolExecution
import me.rerere.ai.provider.claudep.ClaudePToolCallStatus
import me.rerere.ai.provider.claudep.ClaudePToolGenerationContext
import me.rerere.ai.provider.claudep.ClaudePToolStatusPublication
import me.rerere.ai.provider.claudep.ClaudePToolStatusSink
import me.rerere.ai.provider.claudep.ClaudePToolStatusUpdate
import me.rerere.ai.provider.claudep.bridge.BridgeContract
import me.rerere.ai.provider.claudep.bridge.BridgeExecutionClaimant
import me.rerere.ai.provider.claudep.bridge.BridgeInvocation
import me.rerere.ai.provider.claudep.bridge.BridgeInvokeDecision
import me.rerere.ai.provider.claudep.bridge.BridgeToolAdapter
import me.rerere.ai.provider.claudep.bridge.BridgeToolCandidate
import me.rerere.ai.provider.claudep.bridge.BridgeToolCatalog
import me.rerere.ai.provider.claudep.bridge.FrozenCatalog
import me.rerere.ai.provider.claudep.bridge.GenerationBinding
import me.rerere.ai.provider.claudep.bridge.ToolCallState
import me.rerere.ai.provider.claudep.bridge.ToolSource
import me.rerere.ai.provider.claudep.bridge.isAndroidReportable
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationRunControl
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.execution.ToolAssessment
import me.rerere.rikkahub.data.ai.execution.ToolAssessmentRequest
import me.rerere.rikkahub.data.ai.execution.ToolCancellationCapability
import me.rerere.rikkahub.data.ai.execution.ToolConcurrency
import me.rerere.rikkahub.data.ai.execution.ToolExecutionPlanRequest
import me.rerere.rikkahub.data.ai.execution.ToolExecutionPlanResult
import me.rerere.rikkahub.data.ai.execution.ToolExecutionPolicy
import me.rerere.rikkahub.data.ai.execution.ToolPreExecutionDecision
import me.rerere.rikkahub.data.ai.execution.ToolRuntime
import me.rerere.rikkahub.data.capability.CapabilitySubject
import me.rerere.rikkahub.data.capability.SubjectType
import me.rerere.rikkahub.data.execution.InFlightApprovalWaiters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * The claim, end to end through the **real** ledger.
 *
 * ## Why this suite exists beside `BridgeExecutionClaimTest`
 *
 * That one proves the ledger's own rules in isolation. This one proves the thing the ledger cannot
 * check about itself: that the request the **host** builds is the one the ledger admits, field for
 * field, and that everything the host was handed is either confirmed or refused before a runtime is
 * asked to run anything.
 *
 * The claimant here is a real `BridgeToolAdapter` — the same object the provider hands the host in
 * production — so the binding, the digests and the canonical invocation all come from the code
 * that produces them rather than from a fixture that agrees with the host by construction. A host
 * that built a claim the ledger would refuse could not pass this suite while passing the
 * fake-claimant one.
 */
class ClaudePToolExecutionClaimTest {

    private val runId = Uuid.parse("11111111-1111-1111-1111-111111111111")
    private val conversationUuid = Uuid.parse("33333333-3333-3333-3333-333333333333")
    private val assistantId = "44444444-4444-4444-4444-444444444444"

    private fun context() = ClaudePToolGenerationContext(
        runId = runId.toString(),
        commandId = "22222222-2222-2222-2222-222222222222",
        conversationId = conversationUuid.toString(),
        assistantId = assistantId,
        branchId = "branch-1",
        callOrigin = ToolCallOrigin.LocalChat.name,
    )

    /** A tool that records that it ran, and with which arguments. */
    private class RecordingTool(name: String, private val needsApproval: Boolean = false) {
        val argumentsSeen = mutableListOf<String>()
        val tool: Tool = Tool(
            name = name,
            description = "records that it ran",
            parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
            needsApproval = { needsApproval },
            execute = { element ->
                argumentsSeen += element.toString()
                listOf(UIMessagePart.Text("ran:$name"))
            },
        )
    }

    /**
     * A runtime that really runs the tool it is handed, honours the gate the way
     * `DefaultToolRuntime` does, and records what it was asked.
     *
     * Calling the request's own execution slots is the point: a fake that returned a canned result
     * would pass whether or not the host supplied a working dispatcher, and the dispatcher is what
     * carries an MCP tool's call to `McpManager`.
     */
    private class RecordingRuntime : ToolRuntime {
        val requests = mutableListOf<ToolExecutionPlanRequest>()

        /** What the host's `preExecutionGate` answered, as the runtime would have seen it. */
        var gateDecision: ToolPreExecutionDecision? = null

        override suspend fun assess(request: ToolAssessmentRequest) = ToolAssessment(
            policy = policy(),
            securityDescriptor = null,
            accepted = true,
        )

        override suspend fun execute(request: ToolExecutionPlanRequest): ToolExecutionPlanResult {
            requests += request
            val gate = request.preExecutionGate()
            gateDecision = gate
            if (gate is ToolPreExecutionDecision.Deny) {
                return ToolExecutionPlanResult.Rejected(gate.errorCode, gate.reason)
            }
            val output = request.startableTool
                ?.start(request.args, checkNotNull(request.executionContext))
                ?.awaitResult()
                ?: request.legacyExecute(request.args)
            return ToolExecutionPlanResult.Completed(output, policy(), "exec-1")
        }

        private fun policy() = ToolExecutionPolicy(
            effects = emptySet(),
            concurrency = ToolConcurrency.GLOBAL_SERIAL,
            cancellationCapability = ToolCancellationCapability.COOPERATIVE,
        )
    }

    /** A clock the test moves by hand, so a deadline is reachable in milliseconds. */
    private class Clock {
        var now: Long = 0L
        fun advanceBeyondAnyDeadline() {
            now = Long.MAX_VALUE / 2
        }
    }

    /**
     * The whole production-shaped rig: the host, the execution host, the receipts, the waiters, and
     * the **real** adapter the provider would hand over as the claimant.
     */
    private class Rig(
        val host: ClaudePToolBridgeHostImpl,
        val adapter: BridgeToolAdapter,
        val runtime: RecordingRuntime,
        val receipts: ClaudePToolPublicationReceipts,
        val waiters: InFlightApprovalWaiters,
        val control: GenerationRunControl?,
        val clock: Clock,
        val generationId: String,
        val catalog: FrozenCatalog,
        val binding: GenerationBinding,
    ) {
        /** Admits one invoke the way the frame handler does, and returns it. */
        fun admit(
            toolName: String,
            toolCallId: String = "call-1",
            arguments: JsonObject = buildJsonObject { put("path", "/tmp/x") },
        ): BridgeInvocation {
            val decision = adapter.onInvoke(toolCallId, toolName, arguments)
            check(decision is BridgeInvokeDecision.Execute) { "the invoke must be admitted: $decision" }
            return (decision as BridgeInvokeDecision.Execute).invocation
        }

        /**
         * A second adapter for the same generation with a ledger of its own.
         *
         * The process-restart shape: the generation id is one this app recognises, and the record
         * the call was admitted under is not here.
         */
        fun freshClaimant(): BridgeToolAdapter = BridgeToolAdapter(
            binding = binding,
            catalog = catalog,
            executions = host.executions,
            monotonicMs = { clock.now },
        )

        suspend fun execute(
            invocation: BridgeInvocation,
            status: ClaudePToolStatusSink = ClaudePToolStatusSink.NONE,
            claims: BridgeExecutionClaimant = adapter,
        ): BridgeToolExecution = host.execute(invocation, status, claims)
    }

    private fun schema(): JsonObject = JsonObject(
        linkedMapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(
                linkedMapOf("path" to JsonObject(linkedMapOf("type" to JsonPrimitive("string")))),
            ),
        ),
    )

    private fun catalogOf(vararg names: String): FrozenCatalog =
        BridgeToolCatalog.build(
            names.map { BridgeToolCandidate.provenReadOnly(it, "reads", schema(), ToolSource.LOCAL) },
        ).catalog

    private fun generationBinding(generationId: String, catalog: FrozenCatalog) = GenerationBinding(
        deviceRef = "device-1",
        assistantId = assistantId,
        conversationId = conversationUuid.toString(),
        branchId = "branch-1",
        generationId = generationId,
        requestId = "req-1",
        catalogDigest = catalog.digest,
        bridgeAbi = BridgeContract.BRIDGE_ABI,
        timeoutMs = 60_000L,
    )

    /**
     * Builds a bound host plus the adapter that serves the same generation, exactly as the provider
     * pairs them: the host is opened with the app's own context, and the adapter is created for the
     * Server's generation id with the catalog the plan froze.
     */
    private fun rig(
        tools: List<Tool>,
        runtime: RecordingRuntime = RecordingRuntime(),
        generationId: String = "gen-1",
        runControl: GenerationRunControl? = GenerationRunControl(runId),
        gate: ClaudePToolGate = ClaudePToolGate { _, _, _ -> ToolPreExecutionDecision.Allow },
        withExecutionHost: Boolean = true,
    ): Rig = runBlocking {
        val clock = Clock()
        val controls = ClaudePToolRunControls()
        runControl?.let { controls.register(it.runId.toString(), it) }
        val waiters = InFlightApprovalWaiters()
        val receipts = ClaudePToolPublicationReceipts()

        val executionHost = if (withExecutionHost) {
            ClaudePToolExecutionHost(
                runControls = controls,
                waiters = waiters,
                runIdFor = { serverGenerationId ->
                    runControl?.takeIf { serverGenerationId == generationId }?.runId?.toString()
                },
                cancelPublication = { serverGenerationId, toolCallId, reason ->
                    receipts.cancelFor(serverGenerationId, toolCallId, reason)
                    Unit
                },
            )
        } else {
            null
        }

        val host = ClaudePToolBridgeHostImpl(
            deviceRefProvider = { "device-1" },
            offerCatalog = true,
            toolRuntime = runtime,
            runControls = controls,
            gate = gate,
            publications = receipts,
            inFlightWaiters = waiters,
            executionHost = executionHost,
            subjectFor = { _, _, _ -> CapabilitySubject(assistantId, SubjectType.LOCAL_ASSISTANT) },
        )
        val preparation = host.prepare(tools, context())
        check(host.openGeneration(generationId, preparation)) { "the test's own binding must succeed" }

        val catalog = catalogOf(*tools.map { it.name }.toTypedArray())
        val binding = generationBinding(generationId, catalog)
        val adapter = BridgeToolAdapter(
            binding = binding,
            catalog = catalog,
            executions = host.executions,
            monotonicMs = { clock.now },
        )

        Rig(
            host = host,
            adapter = adapter,
            runtime = runtime,
            receipts = receipts,
            waiters = waiters,
            control = runControl,
            clock = clock,
            generationId = generationId,
            catalog = catalog,
            binding = binding,
        )
    }

    // -----------------------------------------------------------------------------------------
    // The claim the host builds is the claim the ledger admits
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a call admitted by the ledger is claimed and runs with the canonical arguments`() =
        runBlocking {
            val tool = RecordingTool("read_file")
            val rig = rig(listOf(tool.tool))
            val invocation = rig.admit(
                "read_file",
                arguments = buildJsonObject { put("path", "/etc/x") },
            )

            val execution = rig.execute(invocation)

            assertEquals(ToolCallState.COMPLETED, execution.outcome.state)
            assertEquals(1, tool.argumentsSeen.size)
            assertEquals(
                "the tool runs the call the ledger validated, arguments and all",
                buildJsonObject { put("path", "/etc/x") },
                rig.runtime.requests.single().args,
            )
        }

    @Test
    fun `a second execute for the same call is refused the right and runs nothing`() = runBlocking {
        val tool = RecordingTool("read_file")
        val rig = rig(listOf(tool.tool))
        val invocation = rig.admit("read_file")

        assertEquals(ToolCallState.COMPLETED, rig.execute(invocation).outcome.state)
        val second = rig.execute(invocation)

        assertEquals("a second caller cannot obtain a second execution", 1, rig.runtime.requests.size)
        assertEquals("and it is told nothing ran", ToolCallState.FAILED, second.outcome.state)
    }

    @Test
    fun `a claim for another Server generation is refused before the runtime`() = runBlocking {
        val tool = RecordingTool("read_file")
        val rig = rig(listOf(tool.tool))
        val invocation = rig.admit("read_file")

        val elsewhere = invocation.copy(
            binding = invocation.binding.copy(generationId = "gen-other"),
        )
        val execution = rig.execute(elsewhere)

        assertEquals("a claim addressed at another generation runs nothing", 0, rig.runtime.requests.size)
        assertEquals(ToolCallState.FAILED, execution.outcome.state)
    }

    /**
     * The run serving this generation is not registered, so the call has no control.
     *
     * This is the observable form of "the Server generation and the Android run no longer agree":
     * the plan names a run, the run-control registry does not hold it, and a call whose run cannot
     * be named is one that cannot be stopped, cannot be proven and therefore must not be started.
     */
    @Test
    fun `a call whose run is not the one serving the generation is refused`() = runBlocking {
        val tool = RecordingTool("read_file")
        val rig = rig(listOf(tool.tool), runControl = null)
        val invocation = rig.admit("read_file")

        val execution = rig.execute(invocation)

        assertEquals(0, rig.runtime.requests.size)
        assertEquals(ToolCallState.FAILED, execution.outcome.state)
    }

    /** A generation that has ended retires the pairing, and a call under it must not run. */
    @Test
    fun `a call whose pairing was retired is refused`() = runBlocking {
        val tool = RecordingTool("read_file")
        val rig = rig(listOf(tool.tool))
        val invocation = rig.admit("read_file")

        rig.host.closeGeneration("gen-1")
        val execution = rig.execute(invocation)

        assertEquals(0, rig.runtime.requests.size)
        assertEquals(ToolCallState.FAILED, execution.outcome.state)
    }

    /**
     * The call's own deadline elapsed while nothing was watching it.
     *
     * The deadline is the instant stored when the call was admitted, compared on a monotonic clock
     * — never recomputed from `timeoutMs`, which would let a caller extend a call's life by asking
     * about it again.
     */
    @Test
    fun `a call past its deadline is refused and never run`() = runBlocking {
        val tool = RecordingTool("read_file")
        val rig = rig(listOf(tool.tool))
        val invocation = rig.admit("read_file")

        rig.clock.advanceBeyondAnyDeadline()
        val execution = rig.execute(invocation)

        assertEquals(0, rig.runtime.requests.size)
        assertEquals(0, tool.argumentsSeen.size)
        assertEquals(ToolCallState.FAILED, execution.outcome.state)
    }

    /** A cancel that reached the call before it was claimed refuses the claim. */
    @Test
    fun `a cancelled call cannot be claimed`() = runBlocking {
        val tool = RecordingTool("read_file")
        val rig = rig(listOf(tool.tool))
        val invocation = rig.admit("read_file")

        rig.adapter.onCancel(invocation.binding.toolCallId)
        val execution = rig.execute(invocation)

        assertEquals(0, rig.runtime.requests.size)
        assertEquals(ToolCallState.FAILED, execution.outcome.state)
    }

    /** A generation whose close has begun refuses the claim, whatever else is true. */
    @Test
    fun `a call whose generation began closing cannot be claimed`() = runBlocking {
        val tool = RecordingTool("read_file")
        val rig = rig(listOf(tool.tool))
        val invocation = rig.admit("read_file")

        rig.adapter.concludeForClosedGeneration()
        val execution = rig.execute(invocation)

        assertEquals(0, rig.runtime.requests.size)
        assertEquals(ToolCallState.FAILED, execution.outcome.state)
    }

    /**
     * A claim presented by a ledger that holds no record of the call.
     *
     * The process-restart shape, and the one that must never become a re-run: what a tool did
     * before the process died cannot be proven, and executing it again to find out is how a write
     * happens twice.
     */
    @Test
    fun `a claim from a ledger with no record runs nothing`() = runBlocking {
        val tool = RecordingTool("read_file")
        val rig = rig(listOf(tool.tool))
        val invocation = rig.admit("read_file")

        val execution = rig.execute(invocation, claims = rig.freshClaimant())

        assertEquals(0, rig.runtime.requests.size)
        assertEquals(0, tool.argumentsSeen.size)
        assertEquals(ToolCallState.FAILED, execution.outcome.state)
        assertTrue(
            "`failed` claims nothing, which is the only honest report for a claim that was refused",
            execution.outcome.state.isAndroidReportable(),
        )
    }

    /**
     * The canonical invocation is the Server's call, not the copy the user sees.
     *
     * The app rewrites `UIMessagePart.Tool.input` before it reaches the conversation, so a redacted
     * copy of this call genuinely exists elsewhere. What runs is the ledger's own object, with the
     * arguments the peer sent — otherwise a tool would be handed a value nobody asked it to use.
     */
    @Test
    fun `the runtime is given the Server's arguments, not a redacted copy`() = runBlocking {
        val tool = RecordingTool("read_file")
        val rig = rig(listOf(tool.tool))
        val secret = "sk-live-0123456789abcdef"
        val invocation = rig.admit(
            "read_file",
            arguments = buildJsonObject {
                put("path", "/tmp/x")
                put("token", secret)
            },
        )

        rig.execute(invocation)

        val args = rig.runtime.requests.single().args.toString()
        assertTrue("the tool must receive what the Server sent", args.contains(secret))
        assertTrue("and nothing has replaced it", !args.contains("[redacted]"))
    }

    // -----------------------------------------------------------------------------------------
    // The status the conversation sees
    // -----------------------------------------------------------------------------------------

    @Test
    fun `an ungated call publishes a running status and no card`() = runBlocking {
        val tool = RecordingTool("read_file")
        val rig = rig(listOf(tool.tool))
        val invocation = rig.admit("read_file")
        val updates = mutableListOf<ClaudePToolStatusUpdate>()

        rig.execute(invocation, status = ClaudePToolStatusSink { updates += it; ClaudePToolStatusPublication.Accepted })

        assertEquals(listOf(ClaudePToolCallStatus.RUNNING), updates.map { it.status })
        assertNull("an ungated call raises no card", updates.single().pendingContinuation)
    }

    /** The host wires the injected gate through rather than allowing everything by default. */
    @Test
    fun `a call the gate denies is reported denied and never runs`() = runBlocking {
        val tool = RecordingTool("read_file")
        val rig = rig(
            listOf(tool.tool),
            gate = ClaudePToolGate { _, _, _ ->
                ToolPreExecutionDecision.Deny("nope", "the test denied it")
            },
        )
        val invocation = rig.admit("read_file")

        val execution = rig.execute(invocation)

        assertEquals("the runtime was asked, and refused", 1, rig.runtime.requests.size)
        assertEquals(ToolPreExecutionDecision.Deny("nope", "the test denied it"), rig.runtime.gateDecision)
        assertEquals("and the tool itself never ran", 0, tool.argumentsSeen.size)
        assertEquals(ToolCallState.DENIED, execution.outcome.state)
    }

    @Test
    fun `the execution context carries this generation's own identities`() = runBlocking {
        val tool = RecordingTool("read_file")
        val rig = rig(listOf(tool.tool))
        val invocation = rig.admit("read_file")

        rig.execute(invocation)

        val context = checkNotNull(rig.runtime.requests.single().executionContext)
        assertEquals(runId, context.runId)
        assertEquals(conversationUuid, context.conversationId)
        assertEquals(assistantId, context.assistantId)
        assertEquals(ToolCallOrigin.LocalChat, context.callOrigin)
        assertEquals("call-1", context.toolCallId)
        assertSame(
            "the handle is registered against the run that is executing the call",
            rig.control,
            rig.runtime.requests.single().runControl,
        )
    }

    // -----------------------------------------------------------------------------------------
    // The host without an execution layer still refuses rather than guessing
    // -----------------------------------------------------------------------------------------

    @Test
    fun `an approval-gated call on a host with no execution layer is refused unrun`() = runBlocking {
        val tool = RecordingTool("write_file", needsApproval = true)
        val rig = rig(listOf(tool.tool), withExecutionHost = false)
        val invocation = rig.admit("write_file")
        val updates = mutableListOf<ClaudePToolStatusUpdate>()

        val execution = rig.execute(
            invocation,
            status = ClaudePToolStatusSink { updates += it; ClaudePToolStatusPublication.Accepted },
        )

        assertEquals(0, rig.runtime.requests.size)
        assertEquals(0, tool.argumentsSeen.size)
        assertEquals(ToolCallState.FAILED, execution.outcome.state)
        assertTrue("a host that cannot wait must not ask the user to decide", updates.isEmpty())
    }
}
