package me.rerere.ai.provider.providers

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.claudep.ClaudePCancelOutcome
import me.rerere.ai.provider.claudep.ClaudePCancelReason
import me.rerere.ai.provider.claudep.ClaudePCatalogResultBody
import me.rerere.ai.provider.claudep.ClaudePClientHelloBody
import me.rerere.ai.provider.claudep.ClaudePErrorCode
import me.rerere.ai.provider.claudep.ClaudePEventType
import me.rerere.ai.provider.claudep.ClaudePGatewayClient
import me.rerere.ai.provider.claudep.ClaudePGatewayException
import me.rerere.ai.provider.claudep.ClaudePGenerationHandle
import me.rerere.ai.provider.claudep.ClaudePGenerationStartBody
import me.rerere.ai.provider.claudep.ClaudePReceiptBody
import me.rerere.ai.provider.claudep.ClaudePResumeResult
import me.rerere.ai.provider.claudep.ClaudePServerHelloBody
import me.rerere.ai.provider.claudep.BridgeToolExecution
import me.rerere.ai.provider.claudep.ClaudePToolBridgeHost
import me.rerere.ai.provider.claudep.ClaudePToolGenerationContext
import me.rerere.ai.provider.claudep.ClaudePToolPreparation
import me.rerere.ai.provider.claudep.ClaudePToolQueryBody
import me.rerere.ai.provider.claudep.ClaudePToolResultBody
import me.rerere.ai.provider.claudep.ClaudePToolStatusSink
import me.rerere.ai.provider.claudep.FakeClaudePGatewayClient
import me.rerere.ai.provider.claudep.FakeFrame
import me.rerere.ai.provider.claudep.bridge.BridgeCatalogBuild
import me.rerere.ai.provider.claudep.bridge.BridgeExecutionHost
import me.rerere.ai.provider.claudep.bridge.BridgeInvocation
import me.rerere.ai.provider.claudep.bridge.BridgeToolCandidate
import me.rerere.ai.provider.claudep.bridge.BridgeToolCatalog
import me.rerere.ai.provider.claudep.bridge.ToolCallOutcome
import me.rerere.ai.provider.claudep.bridge.ToolCallState
import me.rerere.ai.provider.claudep.bridge.ToolSource
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The order in which a generation is named and a tool frame is allowed to be consumed.
 *
 * ## The guarantee, stated so it cannot be overstated
 *
 * The provider does **not** know a generation id before it sends `generation.start`, and it must
 * never pretend to. The id is assigned by the Server and arrives in the answer; the catalog has to
 * travel *inside* the request, so the preparation is built before the frame leaves and the real id
 * only exists afterwards. There is therefore no instant at which both are in hand.
 *
 * What is actually guaranteed — and what every test here is a different angle on — is this: **by
 * the time any `tool.invoke` is read off the stream, the generation it names resolves to exactly
 * one execution plan.** A tool frame that arrived in the very first batch is still read only after
 * the binding is in place, because the binding happens between the answer and the first pump.
 *
 * ## Why these are ordering assertions and not state assertions
 *
 * Asserting "the binding exists at the end" would pass for an implementation that bound *after*
 * executing, or bound twice, or bound the wrong id. So the assertions are on the sequence: how
 * many times each host method ran, what had already happened when `generation.start` was sent,
 * what had already happened when the binding was established, and which generation the executed
 * call actually belonged to.
 *
 * Everything runs against the deterministic fake gateway, whose whole script is built inside
 * `startGeneration` — so the tool frame is genuinely already produced and waiting when the provider
 * binds. No sleeps, no timing assumptions.
 */
class ClaudePProviderBindingOrderTest {

    private val setting = ProviderSetting.ClaudeP()

    private val sonnet = Model(modelId = "sonnet", displayName = "Claude Sonnet")

    /** A complete generation identity, of the shape the app supplies. */
    private fun context() = ClaudePToolGenerationContext(
        runId = "run-1",
        commandId = "command-1",
        conversationId = "conversation-1",
        assistantId = "assistant-1",
        branchId = "branch-1",
        callOrigin = "LocalChat",
    )

    private fun userMessage() = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text("read me a file")),
    )

    /** One declared tool, which is what makes the provider freeze a non-empty catalog. */
    private fun declaredTools() = listOf(
        Tool(
            name = "read_file",
            description = "Read a file",
            parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
            execute = { emptyList() },
        ),
    )

    private fun paramsWithTools() = TextGenerationParams(
        model = sonnet,
        tools = declaredTools(),
        claudePToolGenerationContext = context(),
    )

    /** The catalog the host hands the provider: exactly one tool, named as the frame will name it. */
    private fun catalogBuild(): BridgeCatalogBuild = BridgeToolCatalog.build(
        listOf(
            BridgeToolCandidate.tool(
                name = "read_file",
                description = "Read a file",
                // The bridge takes a schema as JSON, not as the app's `InputSchema` type: the
                // catalog is frozen into bytes, and the app's own encoding is the app's business.
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", JsonObject(emptyMap()))
                },
                source = ToolSource.LOCAL,
            ),
        ),
    )

    /**
     * The `tool.invoke` the fake injects mid-stream, immediately after `message.started`.
     *
     * The tool name is the **frozen** spelling, because that is what the Server sends: it looks the
     * tool up in the catalog it froze and names it the way that catalog does.
     */
    private fun invokeFrame(toolCallId: String = "call-1", toolName: String = "read_file") =
        FakeFrame(
            type = ClaudePEventType.TOOL_INVOKE,
            body = buildJsonObject {
                put("tool_call_id", toolCallId)
                put("tool_name", toolName)
                put("arguments", JsonObject(emptyMap()))
            },
        )

    // ---------------------------------------------------------------------------------------
    // The host under observation
    // ---------------------------------------------------------------------------------------

    /**
     * A bridge host that records the order it was asked to do things in.
     *
     * It is deliberately *not* a real host: what is under test here is the provider's sequencing,
     * not the app's execution. Its one job is to be observable, so that "the runtime was asked to
     * run this" can be distinguished from "the runtime was asked to run this at the right moment".
     */
    private class RecordingHost(
        private val build: BridgeCatalogBuild,
        /** Whether the binding succeeds. `false` is the fail-closed path. */
        private val bindSucceeds: Boolean = true,
    ) : ClaudePToolBridgeHost {

        val events = mutableListOf<String>()

        var prepareCalls = 0
            private set

        var openCalls = 0
            private set

        var executeCalls = 0
            private set

        /** How many executions had happened when [openGeneration] ran. Must be 0. */
        var executeCallsAtOpen = -1
            private set

        /** The generation id the binding was established for. */
        var boundGenerationId: String? = null
            private set

        /** The generation id the executed call belonged to, from the invocation itself. */
        var executedGenerationId: String? = null
            private set

        override suspend fun prepare(
            tools: List<Tool>,
            context: ClaudePToolGenerationContext?,
        ): ClaudePToolPreparation {
            prepareCalls++
            events += "prepare"
            return ClaudePToolPreparation(
                deviceRef = "device-1",
                assistantId = context?.assistantId.orEmpty(),
                conversationId = context?.conversationId.orEmpty(),
                branchId = context?.branchId.orEmpty(),
                timeoutMs = 60_000L,
                catalog = build.catalog,
                snapshot = build.snapshot,
                executionRef = "prep-token-1",
            )
        }

        override suspend fun openGeneration(
            generationId: String,
            preparation: ClaudePToolPreparation,
        ): Boolean {
            openCalls++
            executeCallsAtOpen = executeCalls
            events += "open:$generationId"
            if (!bindSucceeds) return false
            boundGenerationId = generationId
            return true
        }

        override fun closeGeneration(generationId: String) {
            events += "close:$generationId"
        }

        override suspend fun execute(
            invocation: BridgeInvocation,
            status: ClaudePToolStatusSink,
        ): BridgeToolExecution {
            executeCalls++
            executedGenerationId = invocation.binding.generationId
            events += "execute"
            return BridgeToolExecution(
                outcome = ToolCallOutcome(
                    toolCallId = invocation.binding.toolCallId,
                    state = ToolCallState.COMPLETED,
                    body = "contents",
                ),
                part = null,
            )
        }

        override val executions: BridgeExecutionHost = BridgeExecutionHost.NONE
    }

    /**
     * A gateway that records what the provider had already done when `generation.start` was sent.
     *
     * This is the only place the "the id does not exist yet" claim can be checked from: at the
     * moment the request leaves, the binding must not have happened, because there is nothing to
     * bind to.
     */
    private class ObservingGateway(
        private val delegate: FakeClaudePGatewayClient,
        private val onStart: () -> Unit,
    ) : ClaudePGatewayClient {

        val startBodies = mutableListOf<ClaudePGenerationStartBody>()

        override suspend fun startGeneration(
            requestId: String,
            fingerprint: String,
            body: ClaudePGenerationStartBody,
        ): ClaudePGenerationHandle {
            startBodies += body
            onStart()
            return delegate.startGeneration(requestId, fingerprint, body)
        }

        override suspend fun hello(request: ClaudePClientHelloBody): ClaudePServerHelloBody =
            delegate.hello(request)

        override suspend fun catalog(): ClaudePCatalogResultBody = delegate.catalog()

        override suspend fun cancel(
            generationId: String,
            reason: ClaudePCancelReason,
        ): ClaudePCancelOutcome = delegate.cancel(generationId, reason)

        override suspend fun receipt(generationId: String): ClaudePReceiptBody =
            delegate.receipt(generationId)

        override suspend fun sendToolResult(generationId: String, body: ClaudePToolResultBody) =
            delegate.sendToolResult(generationId, body)

        override suspend fun queryToolCall(generationId: String, body: ClaudePToolQueryBody) =
            delegate.queryToolCall(generationId, body)

        override suspend fun resume(generationId: String, lastEventSeq: Long): ClaudePResumeResult =
            delegate.resume(generationId, lastEventSeq)

        override val startGenerationCallCount: Int get() = delegate.startGenerationCallCount
        override val remoteDispatchCount: Int get() = delegate.remoteDispatchCount
        override val cancelCallCount: Int get() = delegate.cancelCallCount
        override val toolResultCallCount: Int get() = delegate.toolResultCallCount

        /** The fake names generations `gen-<n>-<alias>`; tests read it rather than assume `n`. */
        val lastGenerationId: String? get() = delegate.lastGenerationId
    }

    // ---------------------------------------------------------------------------------------
    // 1. The ordering itself
    // ---------------------------------------------------------------------------------------

    /**
     * Start carries the snapshot; the id comes back; the frame is already waiting; only then is the
     * binding made and the frame consumed.
     *
     * Each assertion below is one link of that chain, and the chain is the guarantee. Read them in
     * order: if any single one holds while its predecessor does not, the implementation is doing
     * something other than what it claims.
     */
    @Test
    fun `the binding is established before the first tool frame is consumed`() = runBlocking {
        val host = RecordingHost(catalogBuild())

        // What the provider had already done when the request left. Captured *inside*
        // `generation.start`, because that is the only instant at which the claim "the id does not
        // exist yet" can be checked — reading the counters afterwards says nothing about it.
        var prepareCallsAtStart = -1
        var openCallsAtStart = -1
        val gateway = ObservingGateway(
            delegate = FakeClaudePGatewayClient(midStreamFrames = listOf(invokeFrame())),
            onStart = {
                prepareCallsAtStart = host.prepareCalls
                openCallsAtStart = host.openCalls
            },
        )
        val provider = ClaudePProvider(
            gateway = gateway,
            requestIdFactory = { "req-order" },
            toolHost = host,
        )

        provider.streamText(setting, listOf(userMessage()), paramsWithTools()).toList()

        val generationId = host.boundGenerationId
        assertNotNull("no binding was ever established", generationId)

        // 1. The catalog travelled in `generation.start`. Without this the Server would never send
        //    an invoke at all, so every assertion after it would be vacuous.
        assertNotNull(
            "the frozen catalog must be in the start frame",
            gateway.startBodies.single().toolSnapshot,
        )

        // 2. The preparation is built **before** the request leaves — it has to be, it is in it.
        assertEquals("prepare must run before start", 1, prepareCallsAtStart)

        // 3. And the binding has **not** happened when the request leaves, because the id the
        //    Server is about to assign does not exist yet. An implementation that claimed a
        //    generation id here would be inventing one.
        assertEquals(
            "the generation could not be bound before its id existed",
            0,
            openCallsAtStart,
        )

        // 4. Exactly one binding, for the generation the Server named.
        assertEquals("the binding must happen exactly once", 1, host.openCalls)

        // 5. No frame was consumed before the binding. This is the guarantee, asserted at the
        //    instant it would be violated.
        assertEquals(
            "a tool frame was consumed before the generation was bound",
            0,
            host.executeCallsAtOpen,
        )

        // 6. Exactly one execution, and it belongs to the generation that was bound. A binding for
        //    one id with an execution under another is the cross-generation confusion this whole
        //    mechanism exists to make impossible.
        assertEquals("the call must execute exactly once", 1, host.executeCalls)
        assertEquals(generationId, host.executedGenerationId)

        // 7. The order, end to end. `execute` between `open` and `close` is the same claim as (5)
        //    and (6) read as a sequence.
        assertEquals(
            listOf("prepare", "open:$generationId", "execute", "close:$generationId"),
            host.events,
        )

        // 8. The outcome went back to the Server, once.
        assertEquals(1, gateway.toolResultCallCount)
    }

    /**
     * Nothing about a tool frame is consumed until the binding is in place — even though the whole
     * script, invoke included, already exists when `startGeneration` returns.
     */
    @Test
    fun `the tool frame is already produced when the binding happens`() = runBlocking {
        val host = RecordingHost(catalogBuild())
        val gateway = ObservingGateway(
            delegate = FakeClaudePGatewayClient(midStreamFrames = listOf(invokeFrame())),
            onStart = { },
        )
        val provider = ClaudePProvider(
            gateway = gateway,
            requestIdFactory = { "req-buffered" },
            toolHost = host,
        )

        provider.streamText(setting, listOf(userMessage()), paramsWithTools()).toList()

        // The fake builds its entire script inside `startGeneration`, so by the time the provider
        // has an id to bind, the invoke frame exists. The binding still precedes reading it.
        assertEquals(1, gateway.remoteDispatchCount)
        assertEquals(1, host.executeCalls)
        assertEquals(0, host.executeCallsAtOpen)
    }

    /**
     * A generation with no tools binds nothing, and its bytes are what they always were.
     *
     * The empty catalog is the text path: `prepare` runs, the snapshot is null, and the binding is
     * never attempted because there is nothing to bind. `openGeneration` is not called at all —
     * which is what keeps the text path free of the bridge rather than merely inert.
     */
    @Test
    fun `an empty catalog never binds and never sends a snapshot`() = runBlocking {
        val host = RecordingHost(BridgeToolCatalog.build(emptyList()))
        val gateway = ObservingGateway(
            delegate = FakeClaudePGatewayClient(),
            onStart = { },
        )
        val provider = ClaudePProvider(
            gateway = gateway,
            requestIdFactory = { "req-text" },
            toolHost = host,
        )

        provider.streamText(setting, listOf(userMessage()), paramsWithTools()).toList()

        assertNull("the text path must send no snapshot", gateway.startBodies.single().toolSnapshot)
        assertEquals("prepare still runs; the host is the one that decides", 1, host.prepareCalls)
        assertEquals("nothing to bind means no binding", 0, host.openCalls)
        assertEquals(0, host.executeCalls)
    }

    // ---------------------------------------------------------------------------------------
    // 2. When binding fails
    // ---------------------------------------------------------------------------------------

    /**
     * A binding that cannot be made fails the generation, and nothing runs.
     *
     * The alternative — running the generation with tools Android cannot answer — leaves the Server
     * blocked on a call until its deadline, and every execution that did happen would be one the
     * app could not have concluded. So this is the loud path, and the loud path has to be proven
     * loud: an exception, no execution, and no tool result claiming an outcome.
     */
    @Test
    fun `a refused binding fails the generation and executes nothing`() = runBlocking {
        val host = RecordingHost(catalogBuild(), bindSucceeds = false)
        val gateway = ObservingGateway(
            delegate = FakeClaudePGatewayClient(midStreamFrames = listOf(invokeFrame())),
            onStart = { },
        )
        val provider = ClaudePProvider(
            gateway = gateway,
            requestIdFactory = { "req-refused" },
            toolHost = host,
        )

        val thrown = assertThrows(ClaudePGatewayException::class.java) {
            runBlocking {
                provider.streamText(setting, listOf(userMessage()), paramsWithTools()).toList()
            }
        }
        assertEquals(ClaudePErrorCode.PROTOCOL_MISMATCH, thrown.code)

        // The frame was produced and buffered, and it was still never consumed.
        assertEquals(1, host.openCalls)
        assertEquals("a generation that could not be bound must run nothing", 0, host.executeCalls)
        assertEquals(0, gateway.toolResultCallCount)
    }

    /**
     * A refused binding also closes the ledger, so the same generation cannot be reopened by a
     * re-delivered frame.
     *
     * Closing and *abandoning* are different acts. Abandoning the adapter leaves a live ledger a
     * replay could still reach; the tombstone is what makes the refusal stick, which is why the
     * provider closes the registry rather than merely failing.
     */
    @Test
    fun `a refused binding closes the generation rather than leaving it reachable`() = runBlocking {
        val host = RecordingHost(catalogBuild(), bindSucceeds = false)
        val gateway = ObservingGateway(
            delegate = FakeClaudePGatewayClient(midStreamFrames = listOf(invokeFrame())),
            onStart = { },
        )
        val provider = ClaudePProvider(
            gateway = gateway,
            requestIdFactory = { "req-refused-replay" },
            toolHost = host,
        )

        assertThrows(ClaudePGatewayException::class.java) {
            runBlocking {
                provider.streamText(setting, listOf(userMessage()), paramsWithTools()).toList()
            }
        }

        // `closeGeneration` is never reached on this path — there is no binding to release — and
        // the ledger is closed inside `openToolGeneration` instead. The observable consequence is
        // that a reconnect for this generation finds no adapter and runs nothing.
        val generationId = gateway.lastGenerationId
        assertNotNull("the refused generation must still exist on the gateway", generationId)

        provider.resumeStream(generationId!!, 0L).toList()

        assertEquals("a closed generation must not execute a replayed frame", 0, host.executeCalls)
    }

    // ---------------------------------------------------------------------------------------
    // 3. After the terminal
    // ---------------------------------------------------------------------------------------

    /**
     * Once a generation has ended, a replayed invoke reaches no executor — not a second one, and
     * not the same one again.
     *
     * This is the reconnect path. The generation completed, the provider closed it, and the
     * gateway could still be holding frames for it. The registry's tombstone is what answers: a
     * lookup by that exact id finds nothing, so no handler is built, so nothing is executed and
     * nothing is re-reported. A reconnect buys a replay and never a second side effect.
     */
    @Test
    fun `a replayed frame for a closed generation executes nothing`() = runBlocking {
        val host = RecordingHost(catalogBuild())
        val gateway = ObservingGateway(
            delegate = FakeClaudePGatewayClient(midStreamFrames = listOf(invokeFrame())),
            onStart = { },
        )
        val provider = ClaudePProvider(
            gateway = gateway,
            requestIdFactory = { "req-replay" },
            toolHost = host,
        )

        provider.streamText(setting, listOf(userMessage()), paramsWithTools()).toList()

        val generationId = host.boundGenerationId!!
        assertEquals("the live stream executed the call once", 1, host.executeCalls)
        val opensAfterLiveStream = host.openCalls
        val resultsAfterLiveStream = gateway.toolResultCallCount

        // The replay carries the same script, `tool.invoke` included.
        provider.resumeStream(generationId, 0L).toList()

        assertEquals("a replay must not execute anything", 1, host.executeCalls)
        assertEquals("a replay must not re-establish a binding", opensAfterLiveStream, host.openCalls)
        assertEquals("a replay must not send a second tool result", resultsAfterLiveStream, gateway.toolResultCallCount)
    }

    /**
     * A reconnect that replays a generation this provider never bound executes nothing.
     *
     * The fail-closed direction, and the one a process restart actually produces: the gateway
     * still holds the generation and will happily replay its frames, but the registry that would
     * have answered them lives in the provider instance that is gone. An unknown id is therefore
     * not an invitation to build an adapter for it — the second provider runs nothing, and it
     * invents no binding for a generation it cannot vouch for.
     */
    @Test
    fun `a replayed frame for a generation this provider never bound executes nothing`() = runBlocking {
        val gateway = ObservingGateway(
            delegate = FakeClaudePGatewayClient(midStreamFrames = listOf(invokeFrame())),
            onStart = { },
        )
        val firstHost = RecordingHost(catalogBuild())
        val first = ClaudePProvider(
            gateway = gateway,
            requestIdFactory = { "req-first" },
            toolHost = firstHost,
        )

        first.streamText(setting, listOf(userMessage()), paramsWithTools()).toList()
        val generationId = firstHost.boundGenerationId!!
        assertEquals(1, firstHost.executeCalls)

        // A second provider over the same gateway: the handshake is already done there, so this
        // reaches the replay path, but the generation was bound in the *first* provider's registry.
        val secondHost = RecordingHost(catalogBuild())
        val second = ClaudePProvider(
            gateway = gateway,
            requestIdFactory = { "req-second" },
            toolHost = secondHost,
        )

        second.resumeStream(generationId, 0L).toList()

        assertEquals("a generation this registry never bound must execute nothing", 0, secondHost.executeCalls)
        assertEquals("and it must not bind one on the way in", 0, secondHost.openCalls)
    }
}
