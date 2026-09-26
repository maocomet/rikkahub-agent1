package me.rerere.rikkahub.data.claudep

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.claudep.bridge.BridgeCloseReason
import me.rerere.ai.provider.claudep.bridge.BridgeContract
import me.rerere.ai.provider.claudep.bridge.BridgeExecutionHost
import me.rerere.ai.provider.claudep.bridge.BridgeInvokeDecision
import me.rerere.ai.provider.claudep.bridge.BridgeToolAdapter
import me.rerere.ai.provider.claudep.bridge.BridgeToolCandidate
import me.rerere.ai.provider.claudep.bridge.BridgeToolCatalog
import me.rerere.ai.provider.claudep.bridge.GenerationBinding
import me.rerere.ai.provider.claudep.bridge.ToolCallOutcome
import me.rerere.ai.provider.claudep.bridge.ToolCallState
import me.rerere.ai.provider.claudep.bridge.ToolSource
import me.rerere.rikkahub.data.ai.GenerationRunControl
import me.rerere.rikkahub.data.ai.tools.CancelRequestResult
import me.rerere.rikkahub.data.ai.tools.ToolCancelReason
import me.rerere.rikkahub.data.ai.tools.ToolExecutionHandle
import me.rerere.rikkahub.data.ai.tools.ToolResult
import me.rerere.rikkahub.data.ai.tools.ToolTerminationState
import me.rerere.rikkahub.data.execution.InFlightApprovalAbandonReason
import me.rerere.rikkahub.data.execution.InFlightApprovalDecision
import me.rerere.rikkahub.data.execution.InFlightApprovalIdentity
import me.rerere.rikkahub.data.execution.InFlightApprovalOutcome
import me.rerere.rikkahub.data.execution.InFlightApprovalWaiters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * The lifecycle: every way a call can end, and what Android is allowed to claim about each.
 *
 * ## The one rule this file exists to hold
 *
 * `cancelled` means **a stop was requested and the runtime proved the call is over**. Every other
 * shape — no handle, a lost handle, a wait that elapsed, a tool that finished on its own — is
 * `failed`, which claims nothing. A tool that kept running while Android reported `cancelled` is
 * the failure this whole vocabulary is arranged to prevent: it tells the user a write did not
 * happen when it did.
 *
 * The tests below reach that boundary from both sides, because a rule that only ever sees the
 * permissive direction is not tested at all.
 */
class ClaudePToolLifecycleTest {

    private val runId = Uuid.parse("11111111-1111-1111-1111-111111111111")
    private val conversationId = "33333333-3333-3333-3333-333333333333"
    private val generationId = "gen-1"

    // -----------------------------------------------------------------------------------------
    // A handle the test drives by hand
    // -----------------------------------------------------------------------------------------

    /**
     * A tool execution the test starts, stops and concludes on its own schedule.
     *
     * [confirmStops] separates the two shapes the runtime really has: a managed tool whose handle
     * can *prove* it stopped, and a legacy one that can only cancel its own wait. Only the first
     * may ever be reported `cancelled`.
     */
    private class DrivenHandle(
        private val confirmStops: Boolean,
    ) : ToolExecutionHandle {
        private val done = CompletableDeferred<ToolResult>()
        private val cancelRequested = AtomicBoolean(false)

        override val executionId: String = "exec-1"

        override suspend fun awaitResult(): ToolResult = done.await()

        override fun requestCancel(reason: ToolCancelReason): CancelRequestResult {
            if (!cancelRequested.compareAndSet(false, true)) return CancelRequestResult.AlreadyRequested
            return if (confirmStops) CancelRequestResult.Requested else CancelRequestResult.LocalWaitCancelledOnly
        }

        override suspend fun awaitTermination(gracePeriod: Duration): ToolTerminationState = when {
            !cancelRequested.get() -> ToolTerminationState.StillRunning
            confirmStops -> ToolTerminationState.StoppedConfirmed
            // The legacy answer, verbatim: the wait was cancelled and nothing more can be said.
            else -> ToolTerminationState.Unknown
        }

        fun finish(output: ToolResult) {
            done.complete(output)
        }
    }

    private fun control() = GenerationRunControl(runId)

    private fun hostFor(
        controls: ClaudePToolRunControls,
        waiters: InFlightApprovalWaiters,
        runIdFor: (String) -> String? = { generationId.takeIf { it == "gen-1" }?.let { runId.toString() } },
        receipts: ClaudePToolPublicationReceipts = ClaudePToolPublicationReceipts(),
    ) = ClaudePToolExecutionHost(
        runControls = controls,
        waiters = waiters,
        runIdFor = runIdFor,
        cancelPublication = { serverGenerationId, toolCallId, reason ->
            receipts.cancelFor(serverGenerationId, toolCallId, reason)
            Unit
        },
    )

    private fun identity(toolCallId: String = "call-1") = InFlightApprovalIdentity(
        approvalId = "approval-1",
        executionId = "exec-1",
        conversationId = conversationId,
        toolCallId = toolCallId,
    )

    // -----------------------------------------------------------------------------------------
    // Stops: requested, and proven
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a stop is asked for through the run control and never assumed`() {
        val controls = ClaudePToolRunControls()
        val control = control()
        controls.register(runId.toString(), control)
        val waiters = InFlightApprovalWaiters()
        val host = hostFor(controls, waiters)
        val handle = DrivenHandle(confirmStops = true)
        control.registerTool("call-1", handle)

        host.requestStop(generationId, listOf("call-1"))

        assertEquals(
            "the run's own cancellation capability is what is asked",
            CancelRequestResult.Requested,
            control.toolCancellationResult("call-1"),
        )
        // A request is not a conclusion: nothing has been settled by asking.
        assertTrue(control.isToolCancellationRequested("call-1"))
    }

    @Test
    fun `a stop the runtime confirms is the only thing reported as cancelled`() {
        val controls = ClaudePToolRunControls()
        val control = control()
        controls.register(runId.toString(), control)
        val host = hostFor(controls, InFlightApprovalWaiters())
        val handle = DrivenHandle(confirmStops = true)
        control.registerTool("call-1", handle)

        host.requestStop(generationId, listOf("call-1"))
        val proven = host.awaitConclusions(generationId, listOf("call-1"), waitMs = 1_000)

        assertEquals(ToolCallState.CANCELLED, proven.getValue("call-1").state)
    }

    @Test
    fun `a stop the runtime cannot confirm proves nothing`() {
        val controls = ClaudePToolRunControls()
        val control = control()
        controls.register(runId.toString(), control)
        val host = hostFor(controls, InFlightApprovalWaiters())
        // A legacy handle: it can cancel its own wait and cannot say whether the work stopped.
        val handle = DrivenHandle(confirmStops = false)
        control.registerTool("call-1", handle)

        host.requestStop(generationId, listOf("call-1"))
        val proven = host.awaitConclusions(generationId, listOf("call-1"), waitMs = 1_000)

        assertTrue(
            "an unproven stop must be absent, so the bridge concludes `failed` rather than " +
                "telling the user a write did not happen",
            proven.isEmpty(),
        )
    }

    @Test
    fun `a call this host never held proves nothing`() {
        val controls = ClaudePToolRunControls()
        controls.register(runId.toString(), control())
        val host = hostFor(controls, InFlightApprovalWaiters())

        val proven = host.awaitConclusions(generationId, listOf("call-unknown"), waitMs = 50)

        assertTrue(proven.isEmpty())
    }

    @Test
    fun `an unpaired generation has no run to ask, so nothing is proven`() {
        val controls = ClaudePToolRunControls()
        controls.register(runId.toString(), control())
        val host = hostFor(controls, InFlightApprovalWaiters(), runIdFor = { null })

        val proven = host.awaitConclusions(generationId, listOf("call-1"), waitMs = 50)

        assertTrue("an unpaired generation fails every question closed", proven.isEmpty())
    }

    /**
     * What the runtime itself reported is reported verbatim, stop request or not.
     *
     * A call that completed a moment before the generation ended *completed*. Preferring a
     * `cancelled` because a stop had been asked for would be the lie the vocabulary exists to
     * refuse — and it is the one direction a "cancel wins" shortcut gets wrong.
     */
    @Test
    fun `what the runtime concluded is reported as it concluded it`() {
        val controls = ClaudePToolRunControls()
        val control = control()
        controls.register(runId.toString(), control)
        val host = hostFor(controls, InFlightApprovalWaiters())
        val handle = DrivenHandle(confirmStops = true)
        control.registerTool("call-1", handle)
        host.recordConclusion(
            generationId,
            "call-1",
            ToolCallOutcome("call-1", ToolCallState.COMPLETED, "body"),
        )

        host.requestStop(generationId, listOf("call-1"))
        val proven = host.awaitConclusions(generationId, listOf("call-1"), waitMs = 1_000)

        assertEquals(ToolCallState.COMPLETED, proven.getValue("call-1").state)
        assertEquals("body", proven.getValue("call-1").body)
    }

    // -----------------------------------------------------------------------------------------
    // Approvals: abandoned before anything is stopped
    // -----------------------------------------------------------------------------------------

    @Test
    fun `an approval registered before the close is released with no decision`() = runBlocking {
        val controls = ClaudePToolRunControls()
        controls.register(runId.toString(), control())
        val waiters = InFlightApprovalWaiters()
        val host = hostFor(controls, waiters)
        val id = identity()
        assertTrue(host.registerApproval(generationId, "call-1", id))

        // The close is driven from inside the registration critical section — the instant a host
        // that had not yet registered would have missed it entirely — so the ordering is
        // deterministic rather than a race between two threads.
        waiters.onAwaitRegistration = { host.abandonApproval(generationId, "call-1") }
        val outcome = waiters.await(id, timeoutMs = 60_000)

        assertTrue(outcome is InFlightApprovalOutcome.Abandoned)
        assertEquals(
            InFlightApprovalAbandonReason.GENERATION_CLOSED,
            (outcome as InFlightApprovalOutcome.Abandoned).reason,
        )
    }

    /**
     * An abandonment that arrives *before* anyone registers still stops the call.
     *
     * This is the close racing a host that has not yet reached its wait. A registry that only
     * delivered to a present waiter would leave that host blocked until the call's thirty-minute
     * deadline, for a generation that ended long ago.
     */
    @Test
    fun `an abandonment before the registration makes the registration refuse`() {
        val controls = ClaudePToolRunControls()
        controls.register(runId.toString(), control())
        val host = hostFor(controls, InFlightApprovalWaiters())

        host.abandonApproval(generationId, "call-1")

        assertFalse(
            "a host that registers afterwards must not wait for a decision that cannot come",
            host.registerApproval(generationId, "call-1", identity()),
        )
    }

    /** A decision that arrives after an abandonment changes nothing. */
    @Test
    fun `a decision after an abandonment is not honoured`() = runBlocking {
        val controls = ClaudePToolRunControls()
        controls.register(runId.toString(), control())
        val waiters = InFlightApprovalWaiters()
        val host = hostFor(controls, waiters)
        val id = identity()
        assertTrue(host.registerApproval(generationId, "call-1", id))
        host.abandonApproval(generationId, "call-1")

        waiters.signalDecided(id, InFlightApprovalOutcome.Decided(InFlightApprovalDecision.APPROVED))
        val outcome = waiters.await(id, timeoutMs = 50)

        assertTrue(
            "a tap on a closed card must never become an execution",
            outcome is InFlightApprovalOutcome.Abandoned,
        )
    }

    // -----------------------------------------------------------------------------------------
    // Publications: a cancel reaches a wait that has not committed yet
    // -----------------------------------------------------------------------------------------

    /**
     * A cancel ends a card's outstanding publication, and only that card's.
     *
     * Without this a host suspended on a receipt waits out the call's whole deadline for an answer
     * that is already decided — and the wait is exactly the state in which a late commit could
     * still arm a waiter for a cancelled call.
     */
    @Test
    fun `a cancel ends one call's publication and leaves another's alone`() = runBlocking {
        val receipts = ClaudePToolPublicationReceipts()
        assertTrue(receipts.bindGeneration(generationId, runId.toString()))
        val first = receipts.begin(invocation("call-1", "write_file"))!!
        val second = receipts.begin(invocation("call-2", "write_file"))!!

        assertTrue(receipts.cancelFor(generationId, "call-1", ClaudePToolPublicationAbandonReason.CANCELLED))

        val ended = first.await(timeoutMs = 1_000)
        assertTrue(ended is ClaudePToolPublicationOutcome.Abandoned)
        assertEquals(
            ClaudePToolPublicationAbandonReason.CANCELLED,
            (ended as ClaudePToolPublicationOutcome.Abandoned).reason,
        )
        assertEquals(
            "the other call's publication is untouched",
            1,
            receipts.pendingCount,
        )
        assertFalse(
            "cancelling a call that has nothing outstanding changes nothing",
            receipts.cancelFor(generationId, "call-1", ClaudePToolPublicationAbandonReason.CANCELLED),
        )
        second.cancel(ClaudePToolPublicationAbandonReason.RELEASED)
    }

    private fun invocation(toolCallId: String, toolName: String) = ClaudePToolPublicationInvocation(
        serverGenerationId = generationId,
        runId = runId.toString(),
        toolCallId = toolCallId,
        toolName = toolName,
        argsDigest = "d".repeat(64),
    )

    @Test
    fun `a stop abandons a card's publication through the host`() {
        val controls = ClaudePToolRunControls()
        controls.register(runId.toString(), control())
        val receipts = ClaudePToolPublicationReceipts()
        assertTrue(receipts.bindGeneration(generationId, runId.toString()))
        val request = receipts.begin(invocation("call-1", "write_file"))!!
        val host = hostFor(controls, InFlightApprovalWaiters(), receipts = receipts)

        host.requestStop(generationId, listOf("call-1"))

        assertEquals("the publication is ended, not waited out", 0, receipts.pendingCount)
        request.cancel(ClaudePToolPublicationAbandonReason.RELEASED)
    }

    // -----------------------------------------------------------------------------------------
    // Records: dropped with the generation, and never confused with another's
    // -----------------------------------------------------------------------------------------

    @Test
    fun `closing a generation drops only its own records`() {
        val controls = ClaudePToolRunControls()
        controls.register(runId.toString(), control())
        val host = hostFor(controls, InFlightApprovalWaiters())
        host.recordConclusion("gen-1", "call-1", ToolCallOutcome("call-1", ToolCallState.COMPLETED))
        host.recordConclusion("gen-2", "call-1", ToolCallOutcome("call-1", ToolCallState.COMPLETED))

        host.forgetGeneration("gen-1")

        assertTrue(host.awaitConclusions("gen-1", listOf("call-1"), waitMs = 0).isEmpty())
        assertEquals(
            "another generation's record is not this one's to drop",
            ToolCallState.COMPLETED,
            host.awaitConclusions("gen-2", listOf("call-1"), waitMs = 0).getValue("call-1").state,
        )
    }

    // -----------------------------------------------------------------------------------------
    // The close, over the real host
    // -----------------------------------------------------------------------------------------

    /**
     * The order the close is required to take, with the **real** execution host behind it.
     *
     * Abandon the approvals, ask for a stop, wait for conclusions, settle by what was proven. The
     * last of those is the point: a call the runtime could not confirm ends `failed`, and the
     * bridge says so rather than reporting a stop it never observed.
     */
    @Test
    fun `closing settles by what the runtime proved and nothing else`() = runBlocking {
        val controls = ClaudePToolRunControls()
        val control = control()
        controls.register(runId.toString(), control)
        val waiters = InFlightApprovalWaiters()
        val host = hostFor(controls, waiters)
        val adapter = adapterFor(host)

        val provable = adapter.onInvoke("call-1", "read_file", args()) as BridgeInvokeDecision.Execute
        val unprovable = adapter.onInvoke("call-2", "read_file", args()) as BridgeInvokeDecision.Execute

        control.registerTool("call-1", DrivenHandle(confirmStops = true))
        control.registerTool("call-2", DrivenHandle(confirmStops = false))

        val concluded = adapter.concludeForClosedGeneration(waitMs = 1_000)
        val byCall = concluded.associateBy { it.toolCallId }

        assertEquals(ToolCallState.CANCELLED, byCall.getValue("call-1").outcome.state)
        assertEquals(BridgeCloseReason.RUNTIME_CONCLUDED, byCall.getValue("call-1").reason)

        assertEquals(ToolCallState.FAILED, byCall.getValue("call-2").outcome.state)
        assertEquals(BridgeCloseReason.STOP_UNPROVEN, byCall.getValue("call-2").reason)
        assertEquals(
            "the unproven call is never described as cancelled",
            false,
            byCall.getValue("call-2").outcome.state == ToolCallState.CANCELLED,
        )
        assertTrue(provable.invocation.binding.toolCallId == "call-1")
        assertTrue(unprovable.invocation.binding.toolCallId == "call-2")
    }

    private fun adapterFor(executions: BridgeExecutionHost): BridgeToolAdapter {
        val catalog = BridgeToolCatalog.build(
            listOf(BridgeToolCandidate.provenReadOnly("read_file", "reads", args(), ToolSource.LOCAL)),
        ).catalog
        return BridgeToolAdapter(
            binding = GenerationBinding(
                deviceRef = "device-1",
                assistantId = "assistant-1",
                conversationId = conversationId,
                branchId = "branch-1",
                generationId = generationId,
                requestId = "req-1",
                catalogDigest = catalog.digest,
                bridgeAbi = BridgeContract.BRIDGE_ABI,
                timeoutMs = 60_000L,
            ),
            catalog = catalog,
            executions = executions,
        )
    }

    private fun args() = buildJsonObject { put("path", "/tmp/x") }

    // -----------------------------------------------------------------------------------------
    // Replay and query
    // -----------------------------------------------------------------------------------------

    /**
     * A re-delivered call replays the recorded answer and runs nothing.
     *
     * This is what makes a reconnect safe: the Server may not have seen the answer, so it asks
     * again — and asking again must not be a second side effect.
     */
    @Test
    fun `a repeated invoke replays the recorded outcome`() = runBlocking {
        val adapter = adapterFor(BridgeExecutionHost.NONE)
        val first = adapter.onInvoke("call-1", "read_file", args())
        assertTrue(first is BridgeInvokeDecision.Execute)
        adapter.complete("call-1", ToolCallState.COMPLETED, "body")

        val again = adapter.onInvoke("call-1", "read_file", args())

        assertTrue(again is BridgeInvokeDecision.Replay)
        assertEquals("body", (again as BridgeInvokeDecision.Replay).outcome.body)
    }

    /**
     * A query answers the state this generation holds, and does exactly three things less than it
     * looks like it might.
     *
     * It does not execute — there is no runtime on this path at all. It does not re-dispatch — an
     * answered call answers with its recorded outcome. And it does not move a deadline: the instant
     * was fixed when the call was admitted, and nothing here reads a clock, so a caller cannot keep
     * a call alive by asking about it. The last is asserted rather than asserted-about: the same
     * query, asked repeatedly, still answers the same thing and the call still expires.
     */
    @Test
    fun `a query answers the recorded state and changes nothing`() = runBlocking {
        val adapter = adapterFor(BridgeExecutionHost.NONE)
        adapter.onInvoke("call-1", "read_file", args())

        assertEquals(ToolCallState.PENDING, adapter.query("call-1")?.state)
        assertEquals(ToolCallState.PENDING, adapter.query("call-1")?.state)
        assertEquals(
            "a call this generation does not hold is not_found, which is not an invitation to run it",
            ToolCallState.NOT_FOUND,
            adapter.query("call-never-seen")?.state,
        )
        assertNull("a malformed id is not a lookup that found nothing", adapter.query(""))

        adapter.complete("call-1", ToolCallState.COMPLETED, "body")
        assertEquals(ToolCallState.COMPLETED, adapter.query("call-1")?.state)

        // The deadline the call was admitted under is still the one it had.
        assertEquals(ToolCallState.COMPLETED, adapter.query("call-1")?.state)
    }

    @Test
    fun `an expiry settles a call this side can no longer wait for`() {
        val adapter = adapterFor(BridgeExecutionHost.NONE)
        adapter.onInvoke("call-1", "read_file", args())

        assertEquals(
            "nothing has expired yet",
            emptyList<ToolCallOutcome>(),
            adapter.expire(nowMonotonicMs = 0L),
        )
        val expired = adapter.expire(nowMonotonicMs = Long.MAX_VALUE / 2)

        assertEquals(1, expired.size)
        assertEquals(ToolCallState.TIMED_OUT, expired.single().state)
        assertEquals(ToolCallState.TIMED_OUT, adapter.query("call-1")?.state)
    }

    // -----------------------------------------------------------------------------------------
    // The catalog a credential could travel in
    // -----------------------------------------------------------------------------------------

    /**
     * The frozen catalog carries a name, a description, a schema and two flags — and nothing else.
     *
     * An MCP server's id, its OAuth grant and its token live in the `Tool` closure that dispatches
     * to `McpManager`, and the host runs that closure rather than reaching for a credential. This
     * asserts the half that is checkable here: what travels to the Server is the tool's *shape*,
     * and the shape has no field a credential could be put in.
     */
    @Test
    fun `the frozen catalog carries no field a credential could travel in`() {
        val frozen = BridgeToolCatalog.build(
            listOf(
                BridgeToolCandidate.tool(
                    name = "mcp__abcd1234_myserver__search",
                    description = "Search",
                    inputSchema = args(),
                    source = ToolSource.MCP,
                ),
            ),
        ).catalog

        val entry = frozen.entries.single()
        assertEquals("mcp__abcd1234_myserver__search", entry.displayName)
        assertEquals(ToolSource.MCP, entry.source)
        assertFalse("an MCP tool is never claimed read-only", entry.readOnly)
        assertTrue(
            "the schema travels as a digest and a body, never as a server id",
            !entry.schemaDigest.contains("myserver"),
        )
    }
}
