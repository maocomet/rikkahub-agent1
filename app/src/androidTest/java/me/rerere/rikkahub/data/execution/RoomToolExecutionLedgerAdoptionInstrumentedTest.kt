package me.rerere.rikkahub.data.execution

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.capability.SubjectType
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Room half of the tool-adoption seam, against a real database.
 *
 * ## Why this is an instrumented test and not a unit test
 *
 * `RoomToolExecutionLedger` is where the two things the adoption decision cannot provide for itself
 * actually happen: the record and its approval are read inside one Room transaction, and the claim
 * is a compare-and-set against a row version. Both are properties of Room — a transaction, and a
 * conditional update — and this module's unit tests have no in-memory Room, so a unit test could
 * only restate them. The behavioural unit tests next to this file
 * (`ApprovedToolStartAdoptionTest`) run the real sink and the real decision against an in-memory
 * ledger; this one runs the real ledger against a real database, so the two together cover the
 * decision and the storage it stands on.
 *
 * The case that matters is the third: a claim made against a snapshot that a stop has overtaken
 * must be refused. That is the whole reason adoption is a compare-and-set rather than a read and a
 * nod, and it is the one shape that cannot be checked by reading code.
 */
@RunWith(AndroidJUnit4::class)
class RoomToolExecutionLedgerAdoptionInstrumentedTest {
    private lateinit var database: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var repository: ExecutionRepository
    private lateinit var ledger: RoomToolExecutionLedger

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        repository = ExecutionRepository(
            dao = database.executionRecordDao(),
            transaction = ExecutionStateTransaction(
                database = database,
                recordDao = database.executionRecordDao(),
                eventDao = database.executionEventDao(),
            ),
            retention = ExecutionRetentionManager(
                recordDao = database.executionRecordDao(),
                eventDao = database.executionEventDao(),
                approvalDao = database.pendingToolApprovalDao(),
                scope = scope,
            ),
        )
        ledger = RoomToolExecutionLedger(
            database = database,
            repository = repository,
            approvalDao = database.pendingToolApprovalDao(),
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
    }

    /**
     * The rows an approval commits, written by the same production functions the barrier uses: the
     * projection, the record it opens, and the transition the tap commits.
     */
    private suspend fun approvedCallAtStarting(): String {
        val owner = PendingApprovalOwner(
            runId = RUN_ID,
            commandId = COMMAND_ID,
            conversationId = CONVERSATION_ID,
            subjectId = SUBJECT_ID,
            subjectType = SubjectType.LOCAL_ASSISTANT,
            origin = ToolCallOrigin.LocalChat,
        )
        val tool = PendingApprovalTool(
            toolCallId = TOOL_CALL_ID,
            toolName = "mcp__b82fa262_funf__ping",
            arguments = JsonObject(mapOf("target" to JsonPrimitive("pkg"))),
            toolSchemaFingerprint = FINGERPRINT,
        )
        val projection = pendingApprovalProjection(
            owner = owner,
            tool = tool,
            requestedAtMs = 1_700_000_000_000L,
            continuationMode = ApprovalContinuationMode.IN_FLIGHT,
        )
        database.pendingToolApprovalDao().insertIgnore(
            projection.copy(
                status = ApprovalStatus.APPROVED.name,
                stateVersion = 2,
                resolvedAtMs = 1_700_000_002_000L,
                resolutionReason = "approval_granted",
                resolutionRequestId = "approval-request-1",
            ),
        )
        ledger.open(
            draft = pendingApprovalExecutionDraft(
                owner = owner,
                tool = tool,
                projection = projection,
                toolSchemaFingerprint = FINGERPRINT,
            ),
            mutationId = "test:open",
            source = ExecutionStateSource.DATABASE,
            reasonCode = "approval_pending",
        )
        val moved = repository.transition(
            id = projection.executionId,
            target = ExecutionStatus.starting,
            mutationId = "test:resolve",
            source = ExecutionStateSource.USER,
            reasonCode = "approval_granted",
            verificationState = VerificationState.DATABASE_CONFIRMED,
        )
        assertTrue("the approval must leave the call starting", moved is ExecutionTransitionResult.Applied)
        return projection.executionId
    }

    @Test
    fun `the start view returns the record and the approval that owns it`() = runBlocking {
        val executionId = approvedCallAtStarting()

        val view = ledger.toolStartView(executionId)

        val record = assertNotNullView(view.record)
        val approval = assertNotNullApproval(view.approval)
        assertEquals(executionId, record.id)
        assertEquals(executionId, approval.executionId)
        assertEquals(ApprovalStatus.APPROVED.name, approval.status)
        assertEquals(ExecutionStatus.starting.name, record.status)

        assertNull("nothing else has this id", ledger.toolStartView("tool:absent:call").record)
    }

    /**
     * The claim succeeds on the snapshot it was given, and leaves the record startable.
     *
     * Its status is deliberately unchanged: `running` is the runtime's own statement, made with a
     * handle this claim does not have. What the claim writes is the handover.
     */
    @Test
    fun `a claim takes the record and leaves the call startable`() = runBlocking {
        val executionId = approvedCallAtStarting()
        val snapshot = assertNotNullView(ledger.toolStartView(executionId).record)

        val claimed = ledger.claimApprovedToolStart(
            record = snapshot,
            mutationId = CLAIM_MUTATION_ID,
            reasonCode = "tool_start_adopted",
        )

        val applied = claimed as? ExecutionMutationResult.Applied
        assertNotNull("the claim must be applied", applied)
        assertEquals(ExecutionStatus.starting.name, applied!!.record.status)
        assertEquals(VerificationState.LIVE_CONFIRMED.name, applied.record.verificationState)
        assertTrue(
            "the claim is a new version of the same row",
            applied.record.stateVersion > snapshot.stateVersion,
        )
        assertEquals(
            "and it opened no second record",
            1,
            database.executionRecordDao().getRecent(10).count { it.toolCallId == TOOL_CALL_ID },
        )
    }

    /**
     * A snapshot a stop has overtaken cannot be claimed.
     *
     * This is the race the claim exists for, run against a real database: the caller holds a
     * `starting` record it read a moment ago, a cancellation commits in between, and the claim —
     * which names the version it read — is refused instead of starting the call over a state that
     * no longer exists.
     */
    @Test
    fun `a snapshot a stop has overtaken is refused`() = runBlocking {
        val executionId = approvedCallAtStarting()
        val stale = assertNotNullView(ledger.toolStartView(executionId).record)

        val stopped = repository.transition(
            id = executionId,
            target = ExecutionStatus.cancelled,
            mutationId = "test:emergency-stop",
            source = ExecutionStateSource.USER,
            reasonCode = "emergency_stop",
            verificationState = VerificationState.DATABASE_CONFIRMED,
        )
        assertTrue("the stop must commit", stopped is ExecutionTransitionResult.Applied)

        val claimed = ledger.claimApprovedToolStart(
            record = stale,
            mutationId = CLAIM_MUTATION_ID,
            reasonCode = "tool_start_adopted",
        )

        assertTrue(
            "the claim must be refused, not applied: $claimed",
            claimed is ExecutionMutationResult.Conflict,
        )
        assertEquals(
            "and the stop's own state stands",
            ExecutionStatus.cancelled.name,
            assertNotNullView(repository.get(executionId)).status,
        )
    }

    /** A record that already ended is refused as well, however fresh the snapshot is. */
    @Test
    fun `a terminal record is never claimed`() = runBlocking {
        val executionId = approvedCallAtStarting()
        repository.transition(
            id = executionId,
            target = ExecutionStatus.orphaned,
            mutationId = "test:orphan",
            source = ExecutionStateSource.RECOVERY,
            reasonCode = "tool_call_process_lost",
            verificationState = VerificationState.STALE,
        )
        val fresh = assertNotNullView(ledger.toolStartView(executionId).record)

        val claimed = ledger.claimApprovedToolStart(
            record = fresh,
            mutationId = CLAIM_MUTATION_ID,
            reasonCode = "tool_start_adopted",
        )

        assertTrue(
            "a terminal record must not be claimed: $claimed",
            claimed is ExecutionMutationResult.Terminal,
        )
    }

    private fun assertNotNullView(record: ExecutionRecord?): ExecutionRecord {
        assertNotNull("the execution record must exist", record)
        return record!!
    }

    private fun assertNotNullApproval(
        approval: PendingToolApprovalRecord?,
    ): PendingToolApprovalRecord {
        assertNotNull("the approval must exist", approval)
        return approval!!
    }

    private companion object {
        const val RUN_ID = "11111111-1111-1111-1111-111111111111"
        const val CONVERSATION_ID = "22222222-2222-2222-2222-222222222222"
        const val COMMAND_ID = "33333333-3333-3333-3333-333333333333"
        const val SUBJECT_ID = "assistant:conversation-1"
        const val TOOL_CALL_ID = "call-1"
        const val CLAIM_MUTATION_ID = "tool-event:claim:call-1"
        const val FINGERPRINT =
            "8f2c1a09d4e7b3650c1f8a29be47d03561c9ae2b784df0136ab5e8c27d940f31"
    }
}
