package me.rerere.rikkahub.data.execution

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationRunControl
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.execution.CriticalToolLifecycleSink
import me.rerere.rikkahub.data.ai.execution.DefaultToolRuntime
import me.rerere.rikkahub.data.ai.execution.ToolCancellationCapability
import me.rerere.rikkahub.data.ai.execution.ToolConcurrency
import me.rerere.rikkahub.data.ai.execution.ToolEffect
import me.rerere.rikkahub.data.ai.execution.ToolExecutionPlanRequest
import me.rerere.rikkahub.data.ai.execution.ToolExecutionPlanResult
import me.rerere.rikkahub.data.ai.execution.ToolExecutionPolicy
import me.rerere.rikkahub.data.ai.execution.ToolExecutionPolicyResolver
import me.rerere.rikkahub.data.ai.execution.ToolRuntime
import me.rerere.rikkahub.data.ai.execution.ToolTrackingState
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext
import me.rerere.rikkahub.data.ai.tools.ToolResult
import me.rerere.rikkahub.data.capability.CapabilitySubject
import me.rerere.rikkahub.data.capability.SubjectType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Starting an approved call on the record the approval authority already opened.
 *
 * ## The defect, in one sentence
 *
 * The barrier opens an execution record under the approval's admission identity; the runtime, about
 * to run the call the user had just approved, opened the *same id* under the ordinary runtime
 * identity; the ledger refused the second, different admission — and because durable tracking is
 * required before a side effect, the granted tool never ran and reported
 * `execution_tracking_unavailable`.
 *
 * ## What these tests run, and what they stand in for
 *
 * They run the real pieces: `ExecutionRecordCriticalToolLifecycleSink`, `DefaultToolRuntime`,
 * `decideToolStartAdmission`, transitions through `ExecutionMutationReducer`, and records built by
 * the same functions the production barrier uses ([pendingApprovalProjection] and
 * [pendingApprovalExecutionDraft]). The single substitution is storage — the sink is given an
 * in-memory [ToolExecutionLedger] instead of `RoomToolExecutionLedger`, because this module's unit
 * tests have no in-memory Room. Every check, every refusal and every lifecycle write below is
 * production code; only the table they are written to is not.
 *
 * Every case counts the tool body's executions, because "the tool did not run" is the property this
 * seam exists to guarantee, and it is the one a status assertion alone cannot see.
 */
class ApprovedToolStartAdoptionTest {

    // ---------------------------------------------------------------------------------------
    // The in-memory ledger
    // ---------------------------------------------------------------------------------------

    /**
     * A ledger with the same *semantics* as the Room one and none of its storage.
     *
     * `open` reproduces `ExecutionStateTransaction.open`, including its refusal of a second,
     * different admission — so passing an approval record through it fails exactly the way the
     * phone's ledger failed. `transition` and the claim both reduce through the real
     * [ExecutionMutationReducer], so the states asserted here are states production would reach.
     *
     * ## The two seams that make a race testable
     *
     * A race that cannot be reproduced cannot be fixed with confidence, and threads are the wrong
     * tool for reproducing one: a test that starts a real concurrent writer either misses the
     * window or becomes flaky. So the window is opened deterministically instead — the production
     * contract is a *pair of calls* (one snapshot read, then one compare-and-set), and each seam
     * below stands exactly one of them at a chosen instant:
     *
     * - [onAfterSnapshot] runs inside [toolStartView], after both rows have been read. It is "a
     *   stop committed while this call was between its snapshot and its claim".
     * - [staleRecord] hands back a record the store no longer holds — the torn read a non-atomic
     *   implementation would produce. The store still holds the truth, so the claim sees a moved
     *   version.
     *
     * Neither seam exists in production; both are the test's, and both leave the code under test
     * exactly as it ships.
     */
    private class InMemoryLedger : ToolExecutionLedger {
        private val records = linkedMapOf<String, ExecutionRecord>()
        private val approvals = linkedMapOf<String, PendingToolApprovalRecord>()

        /** Claim mutation ids the journal already holds, so a repeat is a `Duplicate`. */
        private val claimedMutations = linkedSetOf<String>()

        /** How many times the ordinary open path was asked to run. Adoption must not call it. */
        var openAttempts = 0
        var transitions = 0

        /** Snapshot reads. One per start, never one per row. */
        var snapshotReads = 0
        var claims = 0

        /** The last draft the ordinary path tried to open, for tests about that path. */
        var lastOpenDraft: ExecutionRecordDraft? = null

        /** Runs inside [toolStartView], after both rows are read. */
        var onAfterSnapshot: (() -> Unit)? = null

        /** A record to hand back in place of the one the store holds. */
        var staleRecord: ExecutionRecord? = null

        val recordCount: Int get() = records.size

        fun seedExecution(record: ExecutionRecord) {
            records[record.id] = record
        }

        fun seedApproval(projection: PendingToolApprovalRecord) {
            approvals[projection.executionId] = projection
        }

        fun removeApproval(executionId: String) {
            approvals.remove(executionId)
        }

        fun seedClaimedMutation(mutationId: String) {
            claimedMutations += mutationId
        }

        /** Moves the stored record the way a concurrent actor would, through the real reducer. */
        fun moveStored(id: String, target: ExecutionStatus, reasonCode: String) {
            val current = records[id] ?: return
            val reduced = ExecutionMutationReducer.reduce(
                existing = current,
                mutation = ExecutionMutation(
                    executionId = id,
                    mutationId = "concurrent:$id:${target.name}",
                    expectedVersion = current.stateVersion,
                    source = ExecutionStateSource.USER,
                    reasonCode = reasonCode,
                    targetStatus = target,
                    verificationState = VerificationState.DATABASE_CONFIRMED,
                ),
                nowMs = TRANSITIONED_AT_MS,
            )
            if (reduced is ExecutionReduction.Next) records[id] = reduced.record
        }

        fun execution(id: String): ExecutionRecord? = records[id]

        override suspend fun toolStartView(recordId: String): ToolStartView {
            snapshotReads++
            val view = ToolStartView(
                record = staleRecord ?: records[recordId],
                approval = approvals[recordId],
            )
            onAfterSnapshot?.invoke()
            return view
        }

        override suspend fun claimApprovedToolStart(
            record: ExecutionRecord,
            mutationId: String,
            reasonCode: String,
        ): ExecutionMutationResult {
            claims++
            val existing = records[record.id] ?: return ExecutionMutationResult.Missing(record.id)
            // The journal is consulted before the version, exactly as `mutateInCurrentTransaction`
            // does: a claim that already happened is a retry, whatever the row has done since.
            if (mutationId in claimedMutations) {
                return ExecutionMutationResult.Duplicate(existing)
            }
            if (existing.stateVersion != record.stateVersion) {
                return ExecutionMutationResult.Conflict(existing.stateVersion)
            }
            val reduced = ExecutionMutationReducer.reduce(
                existing = existing,
                mutation = ExecutionMutation(
                    executionId = record.id,
                    mutationId = mutationId,
                    expectedVersion = record.stateVersion,
                    source = ExecutionStateSource.LIVE_EVENT,
                    reasonCode = reasonCode,
                    targetStatus = ExecutionStatus.starting,
                    verificationState = VerificationState.LIVE_CONFIRMED,
                ),
                nowMs = TRANSITIONED_AT_MS,
            )
            return when (reduced) {
                is ExecutionReduction.Invalid ->
                    ExecutionMutationResult.Invalid(reduced.current, reduced.requested)
                is ExecutionReduction.Terminal -> ExecutionMutationResult.Terminal(reduced.record)
                is ExecutionReduction.Next -> {
                    claimedMutations += mutationId
                    records[record.id] = reduced.record
                    ExecutionMutationResult.Applied(reduced.record)
                }
            }
        }

        override suspend fun open(
            draft: ExecutionRecordDraft,
            mutationId: String,
            source: ExecutionStateSource,
            reasonCode: String,
        ): ExecutionRecord {
            openAttempts++
            lastOpenDraft = draft
            records[draft.id]?.let { existing ->
                check(existing.hasSameAdmissionIdentityAs(draft)) {
                    "execution_open_identity_conflict"
                }
                return existing
            }
            val created = draft.toRecord(OPENED_AT_MS).copy(
                stateVersion = 1,
                lastStateSource = source.name,
                lastReasonCode = reasonCode,
            )
            records[draft.id] = created
            return created
        }

        override suspend fun transition(
            id: String,
            target: ExecutionStatus,
            runtimeHandleSummary: String?,
            cancellationResult: String?,
            detail: String?,
            mutationId: String,
            source: ExecutionStateSource,
            reasonCode: String?,
            verificationState: VerificationState?,
            runtime: ExecutionRuntime?,
            requestedTerminalOutcome: RequestedTerminalOutcome?,
        ): ExecutionTransitionResult {
            transitions++
            val existing = records[id] ?: return ExecutionTransitionResult.Missing(id)
            val reduced = ExecutionMutationReducer.reduce(
                existing = existing,
                mutation = ExecutionMutation(
                    executionId = id,
                    mutationId = mutationId,
                    expectedVersion = existing.stateVersion,
                    source = source,
                    reasonCode = reasonCode,
                    targetStatus = target,
                    verificationState = verificationState,
                    runtime = runtime,
                    runtimeHandleSummary = runtimeHandleSummary,
                    cancellationResult = cancellationResult,
                    requestedTerminalOutcome = requestedTerminalOutcome,
                    terminalDetail = detail,
                ),
                nowMs = TRANSITIONED_AT_MS,
            )
            return when (reduced) {
                is ExecutionReduction.Invalid ->
                    ExecutionTransitionResult.Invalid(reduced.current, reduced.requested)
                is ExecutionReduction.Terminal -> ExecutionTransitionResult.Terminal(reduced.record)
                is ExecutionReduction.Next -> {
                    records[id] = reduced.record
                    ExecutionTransitionResult.Applied(reduced.record)
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Fixture
    // ---------------------------------------------------------------------------------------

    private val policy = ToolExecutionPolicy(
        effects = setOf(ToolEffect.NETWORK_WRITE),
        concurrency = ToolConcurrency.GLOBAL_SERIAL,
        cancellationCapability = ToolCancellationCapability.REAL,
    )

    /** Counts how many times a tool body actually ran. The assertion that matters. */
    private class BodyCounter {
        var calls = 0

        suspend fun ran(): ToolResult {
            calls++
            return listOf(UIMessagePart.Text("ok"))
        }
    }

    /**
     * One approved call: the two rows the approval transaction commits, and their identities.
     *
     * The execution record is not hand-written. It is the barrier's own
     * [pendingApprovalExecutionDraft], moved to its committed status by the same
     * [ExecutionMutationReducer] transition the approval commits when the user taps approve. So
     * "the record after approval" here and in production are the same record, built the same way.
     */
    private class ApprovedCall(
        val ledger: InMemoryLedger,
        val projection: PendingToolApprovalRecord,
        val executionId: String,
    ) {
        fun execution(): ExecutionRecord? = ledger.execution(executionId)
    }

    private fun approvedCall(
        toolName: String,
        subjectId: String = SUBJECT_ID,
        subjectType: SubjectType = SubjectType.LOCAL_ASSISTANT,
        projectionStatus: ApprovalStatus = ApprovalStatus.APPROVED,
        recordStatus: ExecutionStatus = ExecutionStatus.starting,
    ): ApprovedCall {
        val ledger = InMemoryLedger()
        val owner = PendingApprovalOwner(
            runId = RUN_ID.toString(),
            commandId = COMMAND_ID.toString(),
            conversationId = CONVERSATION_ID.toString(),
            subjectId = subjectId,
            subjectType = subjectType,
            origin = ToolCallOrigin.LocalChat,
        )
        val tool = PendingApprovalTool(
            toolCallId = TOOL_CALL_ID,
            toolName = toolName,
            arguments = ARGUMENTS,
            toolSchemaFingerprint = FROZEN_FINGERPRINT,
        )
        val pendingProjection = pendingApprovalProjection(
            owner = owner,
            tool = tool,
            requestedAtMs = REQUESTED_AT_MS,
            continuationMode = ApprovalContinuationMode.IN_FLIGHT,
        )
        val waiting = pendingApprovalExecutionDraft(
            owner = owner,
            tool = tool,
            projection = pendingProjection,
            toolSchemaFingerprint = FROZEN_FINGERPRINT,
        ).toRecord(OPENED_AT_MS).copy(
            stateVersion = 1,
            lastStateSource = ExecutionStateSource.DATABASE.name,
            lastReasonCode = "approval_pending",
        )
        val committed = when (recordStatus) {
            // Still waiting for the decision: the approval transaction has not run.
            ExecutionStatus.waiting_approval -> waiting
            ExecutionStatus.starting -> waiting.movedTo(ExecutionStatus.starting, "approval_granted")
            // A call that was approved and then ended: the approval's transition first, then
            // whatever ended it. Never a jump the ledger could not make.
            else -> waiting.movedTo(ExecutionStatus.starting, "approval_granted")
                .movedTo(recordStatus, "tool_ended")
        }
        val resolvedProjection = if (projectionStatus == ApprovalStatus.PENDING) {
            pendingProjection
        } else {
            pendingProjection.copy(
                status = projectionStatus.name,
                stateVersion = 2,
                resolvedAtMs = APPROVED_AT_MS,
                resolutionReason = "approval_granted",
                resolutionRequestId = "approval-request-1",
            )
        }
        ledger.seedExecution(committed)
        ledger.seedApproval(resolvedProjection)
        return ApprovedCall(
            ledger = ledger,
            projection = resolvedProjection,
            executionId = pendingProjection.executionId,
        )
    }

    /** Applies one real transition, so a fixture cannot invent a state production could not reach. */
    private fun ExecutionRecord.movedTo(
        target: ExecutionStatus,
        reasonCode: String,
    ): ExecutionRecord {
        val reduced = ExecutionMutationReducer.reduce(
            existing = this,
            mutation = ExecutionMutation(
                executionId = id,
                mutationId = "fixture:${id}:${target.name}",
                expectedVersion = stateVersion,
                source = ExecutionStateSource.USER,
                reasonCode = reasonCode,
                targetStatus = target,
                verificationState = VerificationState.DATABASE_CONFIRMED,
            ),
            nowMs = APPROVED_AT_MS,
        )
        return when (reduced) {
            is ExecutionReduction.Next -> reduced.record
            // Already terminal: the ledger's own answer is to leave it where it is.
            is ExecutionReduction.Terminal -> reduced.record
            is ExecutionReduction.Invalid -> error(
                "fixture cannot move ${reduced.current} to ${reduced.requested}",
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // The runtime under test
    // ---------------------------------------------------------------------------------------

    /**
     * The real runtime and the real sink. [criticalSink] is replaced only where the test is about
     * the sink failing rather than about what it decides.
     */
    private fun runtime(
        ledger: ToolExecutionLedger,
        criticalSink: CriticalToolLifecycleSink = ExecutionRecordCriticalToolLifecycleSink(ledger),
    ): ToolRuntime = DefaultToolRuntime(
        policyResolver = ToolExecutionPolicyResolver { _, _, _ -> policy },
        criticalSink = criticalSink,
    )

    private fun context(
        commandId: Uuid? = COMMAND_ID,
        conversationId: Uuid = CONVERSATION_ID,
        runId: Uuid = RUN_ID,
        subjectId: String = SUBJECT_ID,
        subjectType: SubjectType = SubjectType.LOCAL_ASSISTANT,
        origin: ToolCallOrigin = ToolCallOrigin.LocalChat,
    ) = ToolExecutionContext(
        runId = runId,
        conversationId = conversationId,
        assistantId = ASSISTANT_ID,
        callOrigin = origin,
        commandId = commandId,
        toolCallId = TOOL_CALL_ID,
        capabilitySubject = CapabilitySubject(id = subjectId, type = subjectType),
    )

    private fun request(
        toolName: String,
        toolSchemaFingerprint: String? = FROZEN_FINGERPRINT,
        executionContext: ToolExecutionContext? = context(),
        wallClockBudgetMs: Long = 30_000L,
        legacyExecute: suspend (JsonElement) -> ToolResult,
    ) = ToolExecutionPlanRequest(
        toolCallId = TOOL_CALL_ID,
        toolName = toolName,
        toolSchemaFingerprint = toolSchemaFingerprint,
        args = ARGUMENTS,
        executionContext = executionContext,
        startableTool = null,
        legacyExecute = legacyExecute,
        runControl = GenerationRunControl(RUN_ID),
        wallClockBudgetMs = wallClockBudgetMs,
    )

    private fun rejected(result: ToolExecutionPlanResult): ToolExecutionPlanResult.Rejected {
        assertTrue("expected a refusal, got $result", result is ToolExecutionPlanResult.Rejected)
        return result as ToolExecutionPlanResult.Rejected
    }

    // ---------------------------------------------------------------------------------------
    // 1. The fix: an approved call runs, exactly once, on the record that approved it
    // ---------------------------------------------------------------------------------------

    /**
     * An approved **MCP** call — the case the phone reported — starts and completes.
     *
     * `openAttempts` is what makes this the fix rather than a coincidence: the ordinary open path is
     * never asked, so there is no second admission to conflict with the first, and the ledger holds
     * exactly one row for the call.
     */
    @Test
    fun `an approved MCP call starts on the approval record and runs exactly once`() = runBlocking {
        val call = approvedCall(MCP_TOOL)
        val body = BodyCounter()

        val result = runtime(call.ledger).execute(
            request(MCP_TOOL, legacyExecute = { body.ran() }),
        )

        assertEquals("the body ran exactly once", 1, body.calls)
        assertTrue("the call completed", result is ToolExecutionPlanResult.Completed)
        assertEquals(
            listOf(UIMessagePart.Text("ok")),
            (result as ToolExecutionPlanResult.Completed).output,
        )
        assertEquals(ToolTrackingState.TRACKED, result.trackingState)
        assertEquals("no second admission was attempted", 0, call.ledger.openAttempts)
        assertEquals("one row, not two", 1, call.ledger.recordCount)
        assertEquals("the start was claimed exactly once", 1, call.ledger.claims)

        val record = checkNotNull(call.execution())
        assertEquals(call.executionId, record.id)
        assertEquals(ExecutionStatus.succeeded.name, record.status)
        assertTrue("the run is timed from the runtime's own start", record.startedAtMs != null)
        assertTrue("and finished", record.finishedAtMs != null)
        // The claim keeps the status where the approval put it, so what moves the record is the
        // runtime's own two statements.
        assertEquals("running, then succeeded", 2, call.ledger.transitions)
    }

    /** The same fix for an approval-gated tool that is not an MCP tool: nothing keys on `mcp__`. */
    @Test
    fun `an approved non-MCP call starts on the approval record and runs exactly once`() =
        runBlocking {
            val call = approvedCall(LOCAL_TOOL)
            val body = BodyCounter()

            val result = runtime(call.ledger).execute(
                request(LOCAL_TOOL, legacyExecute = { body.ran() }),
            )

            assertEquals(1, body.calls)
            assertTrue(result is ToolExecutionPlanResult.Completed)
            assertEquals(0, call.ledger.openAttempts)
            assertEquals(
                ExecutionStatus.succeeded.name,
                checkNotNull(call.execution()).status,
            )
        }

    /** A second user's approved call takes the same path. */
    @Test
    fun `a second user's approved call starts on the approval record`() = runBlocking {
        val call = approvedCall(
            toolName = MCP_TOOL,
            subjectId = SECOND_USER_SUBJECT_ID,
            subjectType = SubjectType.LOCAL_SECOND_USER,
        )
        val body = BodyCounter()

        runtime(call.ledger).execute(
            request(
                MCP_TOOL,
                executionContext = context(
                    subjectId = SECOND_USER_SUBJECT_ID,
                    subjectType = SubjectType.LOCAL_SECOND_USER,
                ),
                legacyExecute = { body.ran() },
            ),
        )

        assertEquals(1, body.calls)
        assertEquals(
            ExecutionStatus.succeeded.name,
            checkNotNull(call.execution()).status,
        )
    }

    /**
     * A tool body that throws lands the adopted record on `failed` — not on `starting`.
     *
     * "The tool was approved and then failed" is a different fact from "the tool was approved and is
     * still starting", and the ledger has to be able to tell them apart.
     */
    @Test
    fun `a failing approved call reaches failed on the same record`() = runBlocking {
        val call = approvedCall(MCP_TOOL)

        val thrown = runCatching {
            runtime(call.ledger).execute(
                request(MCP_TOOL, legacyExecute = { throw IllegalStateException("tool_failed") }),
            )
        }

        assertTrue("the body's failure reaches the caller", thrown.isFailure)
        assertEquals(ExecutionStatus.failed.name, checkNotNull(call.execution()).status)
        assertEquals(1, call.ledger.recordCount)
    }

    // ---------------------------------------------------------------------------------------
    // 2. Fail-closed: what must NOT run
    // ---------------------------------------------------------------------------------------

    /**
     * Every identity disagreement refuses, and every refusal is counted in tool-body executions.
     *
     * One table, one tampering per row, so no field can be quietly dropped from the check. The
     * assertion is the same for every row: `execution_tracking_unavailable`, a body that ran zero
     * times, and a record left exactly where it was.
     */
    @Test
    fun `any identity disagreement leaves the tool body unexecuted`() = runBlocking {
        data class Case(
            val what: String,
            val storedRecord: (ExecutionRecord) -> ExecutionRecord = { it },
            val storedApproval: (PendingToolApprovalRecord) -> PendingToolApprovalRecord = { it },
            val executionContext: (ToolExecutionContext) -> ToolExecutionContext = { it },
            val requestToolName: String = MCP_TOOL,
            val requestFingerprint: String? = FROZEN_FINGERPRINT,
        )

        val cases = listOf(
            Case(
                what = "the record belongs to another run",
                storedRecord = { it.copy(traceId = "another-run") },
            ),
            Case(
                what = "the record belongs to another command",
                storedRecord = { it.copy(commandId = "another-command") },
            ),
            Case(
                what = "the invocation runs under another command",
                executionContext = { context(commandId = OTHER_COMMAND_ID) },
            ),
            Case(
                what = "the record belongs to another conversation",
                storedRecord = { it.copy(conversationId = OTHER_CONVERSATION_ID.toString()) },
            ),
            Case(
                what = "the invocation runs in another conversation",
                executionContext = { context(conversationId = OTHER_CONVERSATION_ID) },
            ),
            Case(
                what = "the record names another tool call",
                storedRecord = { it.copy(toolCallId = "another-call") },
            ),
            Case(
                what = "the approval names another tool call",
                storedApproval = { it.copy(toolCallId = "another-call") },
            ),
            Case(
                what = "the record names another tool",
                storedRecord = { it.copy(toolName = "mcp__other__ping") },
            ),
            Case(
                what = "the invocation names another tool",
                requestToolName = "mcp__other__ping",
            ),
            Case(
                what = "the record carries another schema identity",
                storedRecord = { it.copy(toolSchemaFingerprint = OTHER_FINGERPRINT) },
            ),
            Case(
                what = "the invocation carries another schema identity",
                requestFingerprint = OTHER_FINGERPRINT,
            ),
            Case(
                what = "the record belongs to another subject",
                storedRecord = { it.copy(subjectId = "another-subject") },
            ),
            Case(
                what = "the invocation acts as another subject",
                executionContext = { context(subjectId = "another-subject") },
            ),
            Case(
                what = "the record was made for another subject type",
                storedRecord = { it.copy(subjectType = SubjectType.LOCAL_SECOND_USER.name) },
            ),
            Case(
                what = "the invocation acts as another subject type",
                executionContext = { context(subjectType = SubjectType.LOCAL_SECOND_USER) },
            ),
            Case(
                what = "the record came from another origin",
                storedRecord = { it.copy(origin = ToolCallOrigin.TrustedWorkflow.name) },
            ),
            Case(
                what = "the invocation came from another origin",
                executionContext = { context(origin = ToolCallOrigin.TrustedWorkflow) },
            ),
            Case(
                what = "the record's learning scope is not the approval's subject",
                storedRecord = {
                    it.copy(
                        learningScopeKind = "ASSISTANT",
                        learningScopeId = RUN_ID.toString(),
                    )
                },
            ),
            Case(
                what = "the record's capabilities are not the approval's",
                storedRecord = { it.copy(capabilityKeys = "other.capability") },
            ),
            Case(
                what = "the record's resource class is not the approval's",
                storedRecord = { it.copy(resourceSummary = "other") },
            ),
            Case(
                what = "the record was opened under the runtime's own idempotency key",
                storedRecord = {
                    it.copy(
                        idempotencyKey = ExecutionRecordIds.toolIdempotency(
                            RUN_ID.toString(),
                            TOOL_CALL_ID,
                        ),
                    )
                },
            ),
            Case(
                what = "the record is not a tool call",
                storedRecord = { it.copy(executionKind = ExecutionKind.MANAGED_PROCESS.name) },
            ),
            Case(
                what = "the approval row belongs to another execution",
                storedApproval = { it.copy(approvalId = "approval:another") },
            ),
            Case(
                what = "the approval row is for another run",
                storedApproval = { it.copy(traceId = "another-run") },
            ),
            Case(
                what = "the approval row is for another conversation",
                storedApproval = { it.copy(conversationId = OTHER_CONVERSATION_ID.toString()) },
            ),
            Case(
                what = "the approval row is for another subject",
                storedApproval = { it.copy(subjectId = "another-subject") },
            ),
            Case(
                what = "the approval row is from another origin",
                storedApproval = { it.copy(origin = ToolCallOrigin.TrustedWorkflow.name) },
            ),
        )

        var checked = 0
        for (case in cases) {
            val call = approvedCall(MCP_TOOL)
            call.ledger.seedExecution(case.storedRecord(checkNotNull(call.execution())))
            call.ledger.seedApproval(case.storedApproval(call.projection))
            val body = BodyCounter()

            val result = runtime(call.ledger).execute(
                request(
                    toolName = case.requestToolName,
                    toolSchemaFingerprint = case.requestFingerprint,
                    executionContext = case.executionContext(context()),
                    legacyExecute = { body.ran() },
                ),
            )

            assertEquals("${case.what}: the tool must not run", 0, body.calls)
            assertEquals(
                "${case.what}: the runtime must refuse",
                "execution_tracking_unavailable",
                rejected(result).errorCode,
            )
            assertEquals(
                "${case.what}: the record must be untouched",
                ExecutionStatus.starting.name,
                checkNotNull(call.execution()).status,
            )
            assertEquals("${case.what}: and no row may be added", 1, call.ledger.recordCount)
            checked++
        }
        assertEquals("every row ran", cases.size, checked)
    }

    /**
     * A record that is still waiting for its decision may not be started, even when another row
     * claims it was approved.
     *
     * The status on the record is the authority for "may this run": an approval row cannot grant a
     * state the execution ledger has not reached.
     */
    @Test
    fun `a record still waiting for approval is never started`() = runBlocking {
        val stillWaiting = approvedCall(
            toolName = MCP_TOOL,
            projectionStatus = ApprovalStatus.PENDING,
            recordStatus = ExecutionStatus.waiting_approval,
        )
        val approvedButNotStarted = approvedCall(
            toolName = MCP_TOOL,
            projectionStatus = ApprovalStatus.APPROVED,
            recordStatus = ExecutionStatus.waiting_approval,
        )

        for (call in listOf(stillWaiting, approvedButNotStarted)) {
            val body = BodyCounter()
            val result = runtime(call.ledger).execute(
                request(MCP_TOOL, legacyExecute = { body.ran() }),
            )

            assertEquals(0, body.calls)
            assertEquals("execution_tracking_unavailable", rejected(result).errorCode)
            assertEquals(ExecutionStatus.waiting_approval.name, checkNotNull(call.execution()).status)
        }
    }

    /** No approval, or an approval that is not an approval, is nothing to adopt. */
    @Test
    fun `an approval that was not granted is never started`() = runBlocking {
        val denied = approvedCall(
            toolName = MCP_TOOL,
            projectionStatus = ApprovalStatus.DENIED,
            recordStatus = ExecutionStatus.cancelled,
        )
        val invalidated = approvedCall(
            toolName = MCP_TOOL,
            projectionStatus = ApprovalStatus.INVALIDATED,
            recordStatus = ExecutionStatus.cancelled,
        )
        val noProjection = approvedCall(MCP_TOOL).also { call ->
            call.ledger.removeApproval(call.executionId)
        }

        for ((what, call) in listOf(
            "denied" to denied,
            "invalidated" to invalidated,
            "no projection at all" to noProjection,
        )) {
            val body = BodyCounter()
            val result = runtime(call.ledger).execute(
                request(MCP_TOOL, legacyExecute = { body.ran() }),
            )

            assertEquals("$what: the tool must not run", 0, body.calls)
            assertEquals(
                "$what: the runtime must refuse",
                "execution_tracking_unavailable",
                rejected(result).errorCode,
            )
        }
    }

    /** A call that already ended is not restarted, whatever the approval row says. */
    @Test
    fun `a terminal or orphaned record is never started`() = runBlocking {
        for (status in listOf(
            ExecutionStatus.succeeded,
            ExecutionStatus.failed,
            ExecutionStatus.orphaned,
        )) {
            val call = approvedCall(MCP_TOOL, recordStatus = status)
            val body = BodyCounter()

            val result = runtime(call.ledger).execute(
                request(MCP_TOOL, legacyExecute = { body.ran() }),
            )

            assertEquals("$status: the tool must not run", 0, body.calls)
            assertEquals(
                "$status: the runtime must refuse",
                "execution_tracking_unavailable",
                rejected(result).errorCode,
            )
            assertEquals(
                "$status: the terminal state is untouched",
                status.name,
                checkNotNull(call.execution()).status,
            )
        }
    }

    /**
     * A critical sink that really fails still stops the tool before its side effect.
     *
     * This is the guarantee that made the adoption defect visible in the first place, and the fix
     * must not weaken it in either direction: an approved call whose tracking cannot be written does
     * not run either.
     */
    @Test
    fun `a real sink failure leaves the tool body unexecuted`() = runBlocking {
        val call = approvedCall(MCP_TOOL)
        val body = BodyCounter()
        val failing = CriticalToolLifecycleSink { error("ledger_write_failed") }

        val result = runtime(call.ledger, criticalSink = failing).execute(
            request(MCP_TOOL, legacyExecute = { body.ran() }),
        )

        assertEquals(0, body.calls)
        assertEquals("execution_tracking_unavailable", rejected(result).errorCode)
        assertEquals(
            "the record is untouched",
            ExecutionStatus.starting.name,
            checkNotNull(call.execution()).status,
        )
    }

    // ---------------------------------------------------------------------------------------
    // 2b. The window between deciding and starting
    // ---------------------------------------------------------------------------------------

    /**
     * A stop, a cancellation or a recovery that commits after the snapshot is not run through.
     *
     * The decision is made from a snapshot, and a snapshot goes stale. Every one of these is a
     * mutation that lands in that window — an emergency stop cancels the execution and invalidates
     * the approval, a recovery orphans it, a cancel request is the first step of a stop — and the
     * claim has to refuse all of them, because the alternative is starting a call over a state that
     * no longer exists.
     *
     * `onAfterSnapshot` is the window, opened deterministically: the injection runs inside
     * `toolStartView`, after the record and the approval have both been read, which is exactly the
     * instant a concurrent actor would need to hit.
     */
    @Test
    fun `a stop that lands after the snapshot is not started`() = runBlocking {
        for (target in listOf(
            ExecutionStatus.cancelled,
            ExecutionStatus.orphaned,
            ExecutionStatus.cancel_requested,
        )) {
            val call = approvedCall(MCP_TOOL)
            call.ledger.onAfterSnapshot = {
                call.ledger.onAfterSnapshot = null
                call.ledger.moveStored(call.executionId, target, "concurrent_stop")
            }
            val body = BodyCounter()

            val result = runtime(call.ledger).execute(
                request(MCP_TOOL, legacyExecute = { body.ran() }),
            )

            assertEquals("$target: the tool must not run", 0, body.calls)
            assertEquals(
                "$target: the runtime must refuse",
                "execution_tracking_unavailable",
                rejected(result).errorCode,
            )
            assertEquals(
                "$target: the state the stop produced is untouched",
                target.name,
                checkNotNull(call.execution()).status,
            )
            assertEquals("$target: the claim was attempted", 1, call.ledger.claims)
        }
    }

    /**
     * A snapshot that was already torn when it was taken is refused too.
     *
     * This is the shape a *non-atomic* pair of reads produces: the record is read before a stop
     * commits, the approval after — or the other way round — and the decision is made about rows
     * that never coexisted. The store still holds the truth, so a claim against the stale version
     * cannot match, and the call does not start.
     */
    @Test
    fun `a snapshot taken before the stop is refused by the claim`() = runBlocking {
        val call = approvedCall(MCP_TOOL)
        val staleStarting = checkNotNull(call.execution())
        call.ledger.moveStored(call.executionId, ExecutionStatus.cancelled, "emergency_stop")
        call.ledger.staleRecord = staleStarting
        val body = BodyCounter()

        val result = runtime(call.ledger).execute(
            request(MCP_TOOL, legacyExecute = { body.ran() }),
        )

        assertEquals(0, body.calls)
        assertEquals("execution_tracking_unavailable", rejected(result).errorCode)
        assertEquals(
            "every one of the runtime's three durable-write attempts was refused",
            3,
            call.ledger.claims,
        )
        assertEquals(
            "the stop's own state is not overwritten",
            ExecutionStatus.cancelled.name,
            checkNotNull(call.execution()).status,
        )
    }

    /** An already-journaled claim is an execution right already spent, not permission to run. */
    @Test
    fun `an already claimed call is never started again`() = runBlocking {
        val retried = approvedCall(MCP_TOOL)
        retried.ledger.seedClaimedMutation(startingMutationId())
        val retriedBody = BodyCounter()

        val retriedResult = runtime(retried.ledger).execute(
            request(MCP_TOOL, legacyExecute = { retriedBody.ran() }),
        )

        assertEquals("a duplicate claim must not run the body", 0, retriedBody.calls)
        assertEquals("execution_tracking_unavailable", rejected(retriedResult).errorCode)
        assertEquals(
            ExecutionStatus.starting.name,
            checkNotNull(retried.execution()).status,
        )

        // The same duplicate, but the call ended between the claim and this retry.
        val ended = approvedCall(MCP_TOOL)
        ended.ledger.seedClaimedMutation(startingMutationId())
        ended.ledger.moveStored(ended.executionId, ExecutionStatus.orphaned, "process_lost")
        val endedBody = BodyCounter()

        val endedResult = runtime(ended.ledger).execute(
            request(MCP_TOOL, legacyExecute = { endedBody.ran() }),
        )

        assertEquals("a duplicate must not revive an ended call", 0, endedBody.calls)
        assertEquals("execution_tracking_unavailable", rejected(endedResult).errorCode)
    }

    /**
     * A call that is already running is not started a second time.
     *
     * `running` is only ever written by the runtime's own RUNNING statement for this call, so a
     * STARTING that finds it is a second start — and a second start is a second body execution. The
     * layer that is meant to prevent one at all is the bridge's exclusive claim; this is the layer
     * that refuses it if one ever reaches the runtime anyway.
     */
    @Test
    fun `an approved call that is already running is not started again`() = runBlocking {
        val call = approvedCall(MCP_TOOL, recordStatus = ExecutionStatus.running)
        val body = BodyCounter()

        val result = runtime(call.ledger).execute(
            request(MCP_TOOL, legacyExecute = { body.ran() }),
        )

        assertEquals(0, body.calls)
        assertEquals("execution_tracking_unavailable", rejected(result).errorCode)
        assertEquals(0, call.ledger.claims)
        assertEquals(
            "the running state is not disturbed",
            ExecutionStatus.running.name,
            checkNotNull(call.execution()).status,
        )
    }

    /**
     * A second start after the call completed is refused, so the sink never runs a body twice.
     *
     * A repeat of the *same* start — the same run, the same tool call — reaches the same record,
     * now terminal, and stops there. What makes that a guarantee rather than a coincidence is the
     * bridge's claim, which is where a duplicate `tool.invoke` is refused before a runtime is ever
     * called; this is the runtime's own half of the property.
     */
    @Test
    fun `a second start after the call completed is refused`() = runBlocking {
        val call = approvedCall(MCP_TOOL)
        val first = BodyCounter()
        val second = BodyCounter()

        runtime(call.ledger).execute(request(MCP_TOOL, legacyExecute = { first.ran() }))
        val result = runtime(call.ledger).execute(
            request(MCP_TOOL, legacyExecute = { second.ran() }),
        )

        assertEquals(1, first.calls)
        assertEquals("the second start must not run the body", 0, second.calls)
        assertEquals("execution_tracking_unavailable", rejected(result).errorCode)
        assertEquals(
            ExecutionStatus.succeeded.name,
            checkNotNull(call.execution()).status,
        )
    }

    /**
     * The start decision is taken from one snapshot, not from a pair of row reads.
     *
     * This is the structural half of the atomicity: the sink asks for the pair once and cannot ask
     * for the halves, because the interface no longer offers them. A regression that reintroduced
     * two reads would show up here as two snapshot reads for one start.
     */
    @Test
    fun `one start reads the record and the approval in one snapshot`() = runBlocking {
        val approved = approvedCall(MCP_TOOL)
        runtime(approved.ledger).execute(
            request(MCP_TOOL, legacyExecute = { BodyCounter().ran() }),
        )
        assertEquals("one snapshot per start", 1, approved.ledger.snapshotReads)

        val plain = InMemoryLedger()
        runtime(plain).execute(request(MCP_TOOL, legacyExecute = { BodyCounter().ran() }))
        assertEquals("and the ordinary path costs no more", 1, plain.snapshotReads)
    }

    private fun startingMutationId(): String =
        ExecutionRecordIds.toolEvent(RUN_ID.toString(), TOOL_CALL_ID, "STARTING", null)

    // ---------------------------------------------------------------------------------------
    // 3. The paths that must not regress
    // ---------------------------------------------------------------------------------------

    /** A tool that needs no approval still opens its own record, under its own identity, and runs. */
    @Test
    fun `a tool that needs no approval still opens its own record`() = runBlocking {
        val ledger = InMemoryLedger()
        val body = BodyCounter()

        val result = runtime(ledger).execute(
            request(MCP_TOOL, legacyExecute = { body.ran() }),
        )

        assertEquals(1, body.calls)
        assertTrue(result is ToolExecutionPlanResult.Completed)
        assertEquals(1, ledger.openAttempts)
        assertEquals(1, ledger.recordCount)

        val record = checkNotNull(
            ledger.execution(ExecutionRecordIds.tool(RUN_ID.toString(), TOOL_CALL_ID)),
        )
        assertEquals(ExecutionStatus.succeeded.name, record.status)
        assertEquals(
            "the ordinary path keeps its assistant learning scope",
            ASSISTANT_ID,
            record.learningScopeId,
        )
        assertEquals(
            ExecutionRecordIds.toolIdempotency(RUN_ID.toString(), TOOL_CALL_ID),
            record.idempotencyKey,
        )
    }

    /**
     * A record the ordinary path already opened for this exact call is re-opened, not refused.
     *
     * This is the branch the admission decision had to leave alone. The record is the one a
     * *previous, interrupted* start of the same call left behind — written by the real sink, then
     * rewound to `starting` — so the identity under test is the runtime's own, captured rather than
     * re-spelled.
     */
    @Test
    fun `an existing plain record for the same call is re-opened and the call runs`() = runBlocking {
        val ledger = InMemoryLedger()
        val first = BodyCounter()
        runtime(ledger).execute(request(MCP_TOOL, legacyExecute = { first.ran() }))
        val plainDraft = checkNotNull(ledger.lastOpenDraft)

        // What a killed process leaves behind: the same record, still `starting`.
        ledger.seedExecution(
            plainDraft.toRecord(OPENED_AT_MS).copy(
                stateVersion = 1,
                lastStateSource = ExecutionStateSource.LIVE_EVENT.name,
                lastReasonCode = "tool_starting",
            ),
        )

        val second = BodyCounter()
        val result = runtime(ledger).execute(request(MCP_TOOL, legacyExecute = { second.ran() }))

        assertEquals(1, first.calls)
        assertEquals("the resumed call still runs", 1, second.calls)
        assertTrue(result is ToolExecutionPlanResult.Completed)
        assertEquals("the open path was used both times", 2, ledger.openAttempts)
        assertEquals("and no second row appeared", 1, ledger.recordCount)
        assertEquals(
            ExecutionStatus.succeeded.name,
            checkNotNull(
                ledger.execution(ExecutionRecordIds.tool(RUN_ID.toString(), TOOL_CALL_ID)),
            ).status,
        )
    }

    /**
     * The ledger's own refusal is untouched: a *different* draft cannot re-open an existing record.
     *
     * The fix added a way to adopt an approval's record. It did not relax the global admission
     * check, and this pins that: the same in-memory ledger that adopts an approved call above still
     * refuses a draft whose identity differs from the stored row.
     */
    @Test
    fun `a different draft still cannot re-open an existing record`() = runBlocking {
        val call = approvedCall(MCP_TOOL)
        val stored = checkNotNull(call.execution())
        val foreign = ExecutionRecordDraft(
            id = stored.id,
            traceId = stored.traceId,
            conversationId = stored.conversationId,
            toolCallId = stored.toolCallId!!,
            toolName = stored.toolName!!,
            toolSchemaFingerprint = stored.toolSchemaFingerprint,
            learningScope = me.rerere.rikkahub.learning.model.LearningScope.Assistant(RUN_ID),
            subjectId = stored.subjectId,
            subjectType = stored.subjectType,
            origin = stored.origin,
            capabilityKeys = stored.capabilityKeys,
            resourceSummary = stored.resourceSummary,
            runtime = ExecutionRuntime.MCP,
            idempotencyKey = ExecutionRecordIds.toolIdempotency(
                RUN_ID.toString(),
                TOOL_CALL_ID,
            ),
            initialStatus = ExecutionStatus.starting,
        )

        val refused = runCatching {
            call.ledger.open(
                draft = foreign,
                mutationId = "test:open",
                source = ExecutionStateSource.LIVE_EVENT,
                reasonCode = "test",
            )
        }

        assertTrue(
            "the ledger must still refuse a second, different admission of the same id",
            refused.exceptionOrNull()?.message == "execution_open_identity_conflict",
        )
    }

    // ---------------------------------------------------------------------------------------
    // Constants
    // ---------------------------------------------------------------------------------------

    private companion object {
        val RUN_ID: Uuid = Uuid.parse("11111111-1111-1111-1111-111111111111")
        val CONVERSATION_ID: Uuid = Uuid.parse("22222222-2222-2222-2222-222222222222")
        val COMMAND_ID: Uuid = Uuid.parse("33333333-3333-3333-3333-333333333333")
        val OTHER_COMMAND_ID: Uuid = Uuid.parse("44444444-4444-4444-4444-444444444444")
        val OTHER_CONVERSATION_ID: Uuid = Uuid.parse("55555555-5555-5555-5555-555555555555")

        /** A real assistant id: the ordinary learning scope is parsed as a UUID. */
        const val ASSISTANT_ID = "66666666-6666-6666-6666-666666666666"
        const val SUBJECT_ID = "assistant:conversation-1"
        const val SECOND_USER_SUBJECT_ID = "second-user:conversation-1"
        const val TOOL_CALL_ID = "call-1"

        /** The tool the phone reported, and an approval-gated tool that is not an MCP tool. */
        const val MCP_TOOL = "mcp__b82fa262_funf__ping"
        const val LOCAL_TOOL = "clear_app_cache"

        const val FROZEN_FINGERPRINT =
            "8f2c1a09d4e7b3650c1f8a29be47d03561c9ae2b784df0136ab5e8c27d940f31"
        const val OTHER_FINGERPRINT =
            "1b7d5e3402c9a8f61d0b7e42ca95f3018de64b2079ac1f5e38b42d06f917ae5c"

        val ARGUMENTS: JsonObject = JsonObject(mapOf("target" to JsonPrimitive("pkg")))

        const val REQUESTED_AT_MS = 1_700_000_000_000L
        const val OPENED_AT_MS = 1_700_000_001_000L
        const val APPROVED_AT_MS = 1_700_000_002_000L
        const val TRANSITIONED_AT_MS = 1_700_000_003_000L
    }
}
