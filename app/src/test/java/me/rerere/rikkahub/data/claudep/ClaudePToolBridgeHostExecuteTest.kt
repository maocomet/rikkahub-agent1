package me.rerere.rikkahub.data.claudep

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.claudep.ClaudePToolGenerationContext
import me.rerere.ai.provider.claudep.ClaudePToolStatusSink
import me.rerere.ai.provider.claudep.ClaudePToolStatusUpdate
import me.rerere.ai.provider.claudep.bridge.BridgeInvocation
import me.rerere.ai.provider.claudep.bridge.InvocationBinding
import me.rerere.ai.provider.claudep.bridge.ToolCallState
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationRunControl
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.execution.ToolAssessment
import me.rerere.rikkahub.data.ai.execution.ToolAssessmentRequest
import me.rerere.rikkahub.data.ai.execution.ToolConcurrency
import me.rerere.rikkahub.data.ai.execution.ToolCancellationCapability
import me.rerere.rikkahub.data.ai.execution.ToolExecutionPlanRequest
import me.rerere.rikkahub.data.ai.execution.ToolExecutionPlanResult
import me.rerere.rikkahub.data.ai.execution.ToolExecutionPolicy
import me.rerere.rikkahub.data.ai.execution.ToolPreExecutionDecision
import me.rerere.rikkahub.data.ai.execution.ToolRuntime
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext
import me.rerere.rikkahub.data.capability.CapabilitySubject
import me.rerere.rikkahub.data.capability.SubjectType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * The two execution paths that need no user decision: a local tool, and an MCP tool.
 *
 * ## Why the MCP case is not a separate path here — and that is the finding
 *
 * An MCP tool's `execute` closure already dispatches to `McpManager`
 * (`ChatService.kt:3889-3891`). The host receives the *assembled* `Tool` objects in `prepare`, so
 * it runs an MCP tool through exactly the same `legacyExecute` slot as a local one. It never holds
 * an `McpManager`, never sees a server id it was not handed as a closure, and never touches OAuth
 * state or a token.
 *
 * That is why the "credentials must never reach the catalog, arguments, Server, Worker, Claude Code
 * or the logs" requirement is met by *not handling them at all* rather than by filtering them —
 * and the test below pins the mechanism rather than the intention: it proves the host runs the tool
 * it was handed, which is the only thing that makes the claim true.
 *
 * ## What is deliberately not covered
 *
 * The approval path (C2). A call that needs a decision is refused here rather than run without one,
 * and one test says so explicitly. See §12.3 of the M2-B report for the handshake that has to exist
 * before it can be implemented.
 */
class ClaudePToolBridgeHostExecuteTest {

    // ---------------------------------------------------------------------------------------
    // Fakes
    // ---------------------------------------------------------------------------------------

    /**
     * A runtime that records what it was asked to run, and **actually runs it**.
     *
     * Calling the request's own execution slot is the whole point. A fake that returned a canned
     * result without invoking it would pass whether or not the host supplied a working
     * `legacyExecute` or `startableTool` — so it would assert nothing about the wiring, which is
     * the only thing under test here.
     */
    private class RecordingRuntime : ToolRuntime {

        val requests = mutableListOf<ToolExecutionPlanRequest>()

        /** What the host's `preExecutionGate` answered, as the runtime would have seen it. */
        var gateDecision: ToolPreExecutionDecision? = null

        override suspend fun assess(request: ToolAssessmentRequest): ToolAssessment = ToolAssessment(
            policy = policy(),
            securityDescriptor = null,
            accepted = true,
        )

        override suspend fun execute(request: ToolExecutionPlanRequest): ToolExecutionPlanResult {
            requests += request
            gateDecision = request.preExecutionGate()
            // `startableTool` when the app has a cancellable adapter for this tool, `legacyExecute`
            // otherwise — the same choice `DefaultToolRuntime` makes.
            val startable = request.startableTool
            val output = if (startable != null) {
                startable.start(request.args, checkNotNull(request.executionContext)).awaitResult()
            } else {
                request.legacyExecute(request.args)
            }
            return ToolExecutionPlanResult.Completed(
                output = output,
                policy = policy(),
                executionId = "exec-1",
            )
        }

        private fun policy() = ToolExecutionPolicy(
            effects = emptySet(),
            concurrency = ToolConcurrency.GLOBAL_SERIAL,
            cancellationCapability = ToolCancellationCapability.COOPERATIVE,
        )
    }

    private fun context() = ClaudePToolGenerationContext(
        runId = "11111111-1111-1111-1111-111111111111",
        commandId = "22222222-2222-2222-2222-222222222222",
        conversationId = "33333333-3333-3333-3333-333333333333",
        assistantId = "44444444-4444-4444-4444-444444444444",
        branchId = "branch-1",
        callOrigin = ToolCallOrigin.LocalChat.name,
    )

    /**
     * A tool that records the fact it ran, and with which arguments.
     *
     * The recorder is the point for the MCP case: whatever dispatch the closure performs is
     * invisible to the host, so "the host ran the tool it was handed" is exactly what has to be
     * asserted — there is nothing else the host could be doing.
     */
    private class RecordingTool(
        name: String,
        private val needsApproval: Boolean,
    ) {
        val invocations = mutableListOf<String>()

        val tool: Tool = Tool(
            name = name,
            description = "Records that it ran",
            parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
            needsApproval = { needsApproval },
            execute = { element ->
                invocations += element.toString()
                listOf(UIMessagePart.Text("ran:$name"))
            },
        )
    }

    private fun invocation(
        generationId: String,
        toolName: String,
        toolCallId: String = "call-1",
    ) = BridgeInvocation(
        binding = InvocationBinding(
            deviceRef = "device-1",
            assistantId = "44444444-4444-4444-4444-444444444444",
            conversationId = "33333333-3333-3333-3333-333333333333",
            branchId = "branch-1",
            generationId = generationId,
            requestId = "req-1",
            catalogDigest = "digest",
            bridgeAbi = "abi",
            timeoutMs = 60_000L,
            toolCallId = toolCallId,
            toolName = toolName,
            schemaDigest = "schema",
            argsDigest = "args",
        ),
        frozenName = toolName,
        // The raw runtime name, which is what `BridgeToolCatalog` sets `displayName` to and what
        // the adapter binds here — so it is the key the plan's tool list is looked up by.
        toolNameForRuntime = toolName,
        arguments = buildJsonObject { put("path", "/tmp/x") },
        canonicalArguments = """{"path":"/tmp/x"}""",
        argsDigest = "args",
        readOnly = true,
        source = me.rerere.ai.provider.claudep.bridge.ToolSource.LOCAL,
    )

    /** Builds a host whose generation is bound, so `execute` has a plan to find. */
    private fun boundHost(
        tools: List<Tool>,
        runtime: RecordingRuntime,
        runControl: GenerationRunControl? = GenerationRunControl(Uuid.parse("11111111-1111-1111-1111-111111111111")),
        gate: ClaudePToolGate = ClaudePToolGate { _, _, _ -> ToolPreExecutionDecision.Allow },
        subject: CapabilitySubject? = CapabilitySubject("44444444-4444-4444-4444-444444444444", SubjectType.LOCAL_ASSISTANT),
        generationId: String = "gen-1",
        publications: ClaudePToolPublicationReceipts = ClaudePToolPublicationReceipts(),
    ): ClaudePToolBridgeHostImpl = runBlocking {
        val controls = ClaudePToolRunControls()
        runControl?.let { controls.register(it.runId.toString(), it) }

        val host = ClaudePToolBridgeHostImpl(
            deviceRefProvider = { "device-1" },
            offerCatalog = true,
            toolRuntime = runtime,
            runControls = controls,
            gate = gate,
            subjectFor = { _, _, _ -> subject },
            publications = publications,
        )
        val preparation = host.prepare(tools, context())
        check(host.openGeneration(generationId, preparation)) { "the test's own binding must succeed" }
        host
    }

    // ---------------------------------------------------------------------------------------
    // C1 — a local tool that needs no decision runs
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a local tool runs once through the runtime and its result comes back`() = runBlocking {
        val tool = RecordingTool("read_file", needsApproval = false)
        val runtime = RecordingRuntime()
        val host = boundHost(listOf(tool.tool), runtime)

        val execution = host.execute(invocation("gen-1", "read_file"), ClaudePToolStatusSink.NONE)

        assertEquals("the runtime must be asked exactly once", 1, runtime.requests.size)
        assertEquals("the tool itself must run exactly once", 1, tool.invocations.size)
        assertEquals(ToolCallState.COMPLETED, execution.outcome.state)
        assertEquals(
            "the tool's real output must be the body the Server is told",
            "ran:read_file",
            execution.outcome.body,
        )

        val part = execution.part
        assertNotNull("a completed call must come back as a part", part)
        assertEquals("call-1", part!!.toolCallId)
        assertEquals("read_file", part.toolName)
        assertEquals(ToolApprovalState.Auto, part.approvalState)
        assertEquals("ran:read_file", (part.output.single() as UIMessagePart.Text).text)
    }

    /**
     * The runtime is handed the app's real identities, read back from the un-typed context.
     *
     * A wrong `runId` or `conversationId` here would register the tool's handle against the wrong
     * run — so it would be uncancellable by the generation that owns it, and cancellable by one
     * that does not.
     */
    @Test
    fun `the execution context carries the generation's real identities`() = runBlocking {
        val tool = RecordingTool("read_file", needsApproval = false)
        val runtime = RecordingRuntime()
        val host = boundHost(listOf(tool.tool), runtime)

        host.execute(invocation("gen-1", "read_file"), ClaudePToolStatusSink.NONE)

        val context = runtime.requests.single().executionContext
        assertNotNull(context)
        assertEquals("11111111-1111-1111-1111-111111111111", context!!.runId.toString())
        assertEquals("33333333-3333-3333-3333-333333333333", context.conversationId.toString())
        assertEquals("44444444-4444-4444-4444-444444444444", context.assistantId)
        assertEquals("22222222-2222-2222-2222-222222222222", context.commandId.toString())
        assertEquals(ToolCallOrigin.LocalChat, context.callOrigin)
        assertEquals("call-1", context.toolCallId)
    }

    /** The call is registered against the run that is actually executing it. */
    @Test
    fun `the runtime is given the run control for this exact run`() = runBlocking {
        val tool = RecordingTool("read_file", needsApproval = false)
        val runtime = RecordingRuntime()
        val control = GenerationRunControl(Uuid.parse("11111111-1111-1111-1111-111111111111"))
        val host = boundHost(listOf(tool.tool), runtime, runControl = control)

        host.execute(invocation("gen-1", "read_file"), ClaudePToolStatusSink.NONE)

        assertSame(control, runtime.requests.single().runControl)
    }

    /** The host wires the injected gate through, rather than allowing everything by default. */
    @Test
    fun `the injected gate is the one the runtime is asked`() = runBlocking {
        val tool = RecordingTool("read_file", needsApproval = false)
        val runtime = RecordingRuntime()
        val host = boundHost(
            listOf(tool.tool),
            runtime,
            gate = ClaudePToolGate { _, _, _ ->
                ToolPreExecutionDecision.Deny("nope", "the test denied it")
            },
        )

        host.execute(invocation("gen-1", "read_file"), ClaudePToolStatusSink.NONE)

        assertEquals(
            ToolPreExecutionDecision.Deny("nope", "the test denied it"),
            runtime.gateDecision,
        )
    }

    /**
     * An MCP-shaped tool is run through the same slot, which is what makes credential handling
     * unnecessary rather than merely careful.
     *
     * The closure below stands in for `mcpManager.callTool(serverId, tool.name, args)`: it captures
     * the server id and the raw tool name from its own scope. The host is never given either, so it
     * has no way to leak them — and the assertion is that the arguments arriving at the closure are
     * the ones the model sent, unchanged.
     */
    @Test
    fun `an mcp tool dispatches through its own closure without the host knowing`() = runBlocking {
        val dispatched = mutableListOf<Pair<String, String>>()
        val serverId = "server-abc"
        val rawToolName = "search"
        val mcpTool = Tool(
            name = "mcp__abcd1234_myserver__search",
            description = "Search",
            parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
            needsApproval = { false },
            execute = { args ->
                dispatched += serverId to rawToolName
                listOf(UIMessagePart.Text("mcp:$args"))
            },
        )
        val runtime = RecordingRuntime()
        val host = boundHost(listOf(mcpTool), runtime)

        val namespaced = "mcp__abcd1234_myserver__search"
        val execution = host.execute(invocation("gen-1", namespaced), ClaudePToolStatusSink.NONE)

        assertEquals(ToolCallState.COMPLETED, execution.outcome.state)
        assertEquals(listOf(serverId to rawToolName), dispatched)
        assertEquals(
            "the model's arguments must reach the closure unchanged",
            buildJsonObject { put("path", "/tmp/x") },
            runtime.requests.single().args,
        )
    }

    // ---------------------------------------------------------------------------------------
    // Everything that must not run
    // ---------------------------------------------------------------------------------------

    /**
     * A call that needs a decision is refused, not run without one.
     *
     * The in-flight approval path is not implemented yet, and the only safe direction for that is
     * closed: running a write because Android could not ask would be the exact failure the approval
     * gate exists to prevent.
     */
    @Test
    fun `a call that needs approval is refused rather than run`() = runBlocking {
        val tool = RecordingTool("write_file", needsApproval = true)
        val runtime = RecordingRuntime()
        val host = boundHost(listOf(tool.tool), runtime)

        val execution = host.execute(invocation("gen-1", "write_file"), ClaudePToolStatusSink.NONE)

        assertEquals("a gated call must not run", 0, tool.invocations.size)
        assertEquals("and the runtime must not be asked", 0, runtime.requests.size)
        assertEquals(ToolCallState.FAILED, execution.outcome.state)
        assertNull("nothing was shown to the user, so nothing is shown back", execution.part)
    }

    /** A generation with no plan has nothing to execute under. */
    @Test
    fun `a call for an unbound generation is refused`() = runBlocking {
        val tool = RecordingTool("read_file", needsApproval = false)
        val runtime = RecordingRuntime()
        val host = boundHost(listOf(tool.tool), runtime)

        val execution = host.execute(invocation("gen-unknown", "read_file"), ClaudePToolStatusSink.NONE)

        assertEquals(0, runtime.requests.size)
        assertEquals(ToolCallState.FAILED, execution.outcome.state)
    }

    /** A tool this generation did not freeze is not reachable by naming it. */
    @Test
    fun `a tool outside the generation's catalog is refused`() = runBlocking {
        val tool = RecordingTool("read_file", needsApproval = false)
        val runtime = RecordingRuntime()
        val host = boundHost(listOf(tool.tool), runtime)

        val execution = host.execute(invocation("gen-1", "delete_everything"), ClaudePToolStatusSink.NONE)

        assertEquals(0, runtime.requests.size)
        assertEquals(ToolCallState.FAILED, execution.outcome.state)
    }

    /**
     * No run control for this run means no execution.
     *
     * The control is what makes the call cancellable and what the closing generation asks about a
     * stop. Running without it would produce a call that cannot be stopped and whose conclusion
     * nobody can prove.
     */
    @Test
    fun `a call with no live run control is refused`() = runBlocking {
        val tool = RecordingTool("read_file", needsApproval = false)
        val runtime = RecordingRuntime()
        val host = boundHost(listOf(tool.tool), runtime, runControl = null)

        val execution = host.execute(invocation("gen-1", "read_file"), ClaudePToolStatusSink.NONE)

        assertEquals("no control means no execution", 0, runtime.requests.size)
        assertEquals(ToolCallState.FAILED, execution.outcome.state)
    }

    /**
     * No subject means no execution, because a null subject *widens* what the gate permits.
     *
     * `ToolExecutionGate` skips its whole capability branch when the subject is absent, so running
     * with `null` would not be a conservative default — it would be a bypass.
     */
    @Test
    fun `a call whose subject cannot be resolved is refused`() = runBlocking {
        val tool = RecordingTool("read_file", needsApproval = false)
        val runtime = RecordingRuntime()
        val host = boundHost(listOf(tool.tool), runtime, subject = null)

        val execution = host.execute(invocation("gen-1", "read_file"), ClaudePToolStatusSink.NONE)

        assertEquals("an unresolvable subject must fail closed", 0, runtime.requests.size)
        assertEquals(ToolCallState.FAILED, execution.outcome.state)
    }

    /**
     * A host wired with no runtime runs nothing — it does not fall back to calling the tool.
     *
     * Calling `tool.execute` directly would skip the gate, the policy and the ledger that
     * `DefaultToolRuntime` exists to apply, so "no runtime" has to mean "no execution".
     */
    @Test
    fun `a host with no runtime refuses rather than running the tool directly`() = runBlocking {
        val tool = RecordingTool("read_file", needsApproval = false)
        val host = runBlocking {
            val instance = ClaudePToolBridgeHostImpl(
                deviceRefProvider = { "device-1" },
                offerCatalog = true,
                // No runtime, no gate, no subject: the unwired shape.
                publications = ClaudePToolPublicationReceipts(),
            )
            val preparation = instance.prepare(listOf(tool.tool), context())
            check(instance.openGeneration("gen-1", preparation))
            instance
        }

        val execution = host.execute(invocation("gen-1", "read_file"), ClaudePToolStatusSink.NONE)

        assertEquals("the tool must not be invoked directly", 0, tool.invocations.size)
        assertEquals(ToolCallState.FAILED, execution.outcome.state)
    }

    /** The status sink is told the call is running, so the conversation can show it while it is. */
    @Test
    fun `a running call publishes its running status`() = runBlocking {
        val tool = RecordingTool("read_file", needsApproval = false)
        val runtime = RecordingRuntime()
        val host = boundHost(listOf(tool.tool), runtime)

        val published = mutableListOf<ClaudePToolStatusUpdate>()
        host.execute(
            invocation("gen-1", "read_file"),
            ClaudePToolStatusSink { update ->
                published += update
                me.rerere.ai.provider.claudep.ClaudePToolStatusPublication.Accepted
            },
        )

        assertEquals(1, published.size)
        assertEquals("call-1", published.single().toolCallId)
        assertEquals("read_file", published.single().toolName)
    }
}
