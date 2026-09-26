package me.rerere.rikkahub.data.claudep

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.createAppSQLiteOpenHelperFactory
import me.rerere.rikkahub.data.execution.ApprovalContinuationMode
import me.rerere.rikkahub.data.execution.ApprovalStatus
import me.rerere.rikkahub.data.execution.ExecutionKind
import me.rerere.rikkahub.data.execution.ExecutionRecord
import me.rerere.rikkahub.data.execution.ExecutionStateSource
import me.rerere.rikkahub.data.execution.ExecutionStatus
import me.rerere.rikkahub.data.execution.PendingToolApprovalRecord
import me.rerere.rikkahub.data.execution.VerificationState
import me.rerere.rikkahub.data.execution.isInFlightContinuation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The approval barrier, against a **real** `AppDatabase` on a real device.
 *
 * ## Why this cannot be a JVM test
 *
 * Nothing in `app/src/test` can open Room. The claims below are about what the authority's writes
 * actually do to the schema — an exact four-field identity, a `continuationMode` column that
 * round-trips as the meaning it was written with, and two rows that commit together or not at
 * all. Every one of them is a statement about bytes in SQLite, and asserting them against a fake
 * DAO would assert nothing but the fake.
 *
 * ## What this does *not* prove, stated rather than implied
 *
 * It does not drive `RuntimeRunAuthority.checkpointWaiting` or `ChatService`. Those need the full
 * Koin graph and a live conversation pipeline, and the properties that depend on them — that a
 * receipt is completed strictly after `checkpointWaiting` returns, and that a second gesture
 * cannot resume an in-flight call — are evidenced by their placement in the source plus the JVM
 * suites over the receipt registry and the approval waiters. This file is the storage half, and it
 * is the half a unit test genuinely cannot reach.
 */
@RunWith(AndroidJUnit4::class)
class ClaudePToolApprovalInstrumentationTest {

    private val conversationId = "33333333-3333-3333-3333-333333333333"
    private val toolCallId = "toolu_01A2b3C4d5E6f7G8h9I0j1K2"
    private val executionId = "exec-1"
    private val approvalId = "approval-1"

    private lateinit var context: Context
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .openHelperFactory(createAppSQLiteOpenHelperFactory(context))
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    // -----------------------------------------------------------------------------------------
    // A barrier is written with the identity a tap will match, and with nothing adjacent
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the barrier's four-field identity is exact`() = runBlocking {
        val dao = database.pendingToolApprovalDao()
        dao.insertIgnore(pendingRecord())

        val exact = dao.getExact(approvalId, executionId, conversationId, toolCallId)
        assertNotNull("the barrier is found by the identity the authority derived", exact)

        // Each field on its own. A lookup a caller could satisfy by naming something adjacent
        // would let one conversation's tap release another's tool.
        assertNull(dao.getExact("approval-other", executionId, conversationId, toolCallId))
        assertNull(dao.getExact(approvalId, "exec-other", conversationId, toolCallId))
        assertNull(dao.getExact(approvalId, executionId, "conv-other", toolCallId))
        assertNull(dao.getExact(approvalId, executionId, conversationId, "tool-call-other"))
    }

    @Test
    fun `a decision is applied to the exact version the record carried`() = runBlocking {
        val dao = database.pendingToolApprovalDao()
        dao.insertIgnore(pendingRecord())

        val stale = dao.resolveCas(
            approvalId = approvalId,
            expectedVersion = 99,
            nextVersion = 100,
            status = ApprovalStatus.APPROVED.name,
            resolvedAtMs = 1L,
            resolutionReason = null,
            resolutionRequestId = "req-1",
        )
        assertEquals("a decision against a version that is not the record's changes nothing", 0, stale)

        val applied = dao.resolveCas(
            approvalId = approvalId,
            expectedVersion = 1,
            nextVersion = 2,
            status = ApprovalStatus.DENIED.name,
            resolvedAtMs = 2L,
            resolutionReason = "user_denied",
            resolutionRequestId = "req-2",
        )
        assertEquals(1, applied)
        assertEquals(
            ApprovalStatus.DENIED.name,
            checkNotNull(dao.getById(approvalId)).status,
        )
    }

    // -----------------------------------------------------------------------------------------
    // The continuation mode is a durable meaning, not an in-memory convention
    // -----------------------------------------------------------------------------------------

    @Test
    fun `an in-flight barrier reads back as in-flight, and a legacy row does not`() = runBlocking {
        val dao = database.pendingToolApprovalDao()
        dao.insertIgnore(pendingRecord())
        dao.insertIgnore(
            pendingRecord(
                approvalId = "approval-legacy",
                executionId = "exec-legacy",
                continuationMode = ApprovalContinuationMode.RESUME_COMMAND.name,
            ),
        )
        // A row whose mode this build cannot read. It is refused at the import boundary, and this
        // is the second line of that defence: the conservative reading is the pre-v52 behaviour,
        // never "suppress the continuation" — which would leave an approved tool unexecuted and no
        // continuation at all.
        dao.insertIgnore(
            pendingRecord(
                approvalId = "approval-unreadable",
                executionId = "exec-unreadable",
                continuationMode = "not-a-mode-this-build-knows",
            ),
        )

        val inFlight = checkNotNull(dao.getById(approvalId))
        assertTrue(
            "the mode survives the schema, which is what lets the decision suppress a resume command",
            inFlight.isInFlightContinuation(),
        )
        assertFalse(
            "a row that names the resume command resumes, exactly as it always did",
            checkNotNull(dao.getById("approval-legacy")).isInFlightContinuation(),
        )
        assertFalse(
            "and an unreadable mode fails towards the old behaviour rather than towards silence",
            checkNotNull(dao.getById("approval-unreadable")).isInFlightContinuation(),
        )
        assertEquals(ApprovalStatus.PENDING.name, inFlight.status)
        assertEquals(PENDING_BARRIER_VERSION, inFlight.stateVersion)
    }

    /**
     * A pending barrier is still pending, and still in-flight, after the process goes away.
     *
     * Nothing in the storage layer turns it into a continuation. Whether the *app* revives it is
     * the recovery rules' business, and the point of this test is that the row the recovery rules
     * read is the row that was written — not one a restart quietly reshaped.
     */
    @Test
    fun `a pending barrier survives a restart unchanged and is not resumed by storage`() = runBlocking {
        val name = "claudep-approval-restart.db"
        context.deleteDatabase(name)
        val first = openFileDatabase(name)
        try {
            first.pendingToolApprovalDao().insertIgnore(pendingRecord())
        } finally {
            first.close()
        }

        val second = openFileDatabase(name)
        try {
            val restored = checkNotNull(second.pendingToolApprovalDao().getById(approvalId))
            assertEquals(ApprovalStatus.PENDING.name, restored.status)
            assertTrue(restored.isInFlightContinuation())
            assertEquals(
                "and it is found exactly as it was written",
                conversationId,
                restored.conversationId,
            )
            assertEquals(toolCallId, restored.toolCallId)
        } finally {
            second.close()
            context.deleteDatabase(name)
        }
    }

    // -----------------------------------------------------------------------------------------
    // One transaction, or neither row
    // -----------------------------------------------------------------------------------------

    /**
     * The barrier and the execution record commit together or not at all.
     *
     * This is the property `SecondUserApprovalLifecycle.persistPendingBarrier` relies on when it
     * writes both inside `database.withTransaction`: a rollback after the first write must leave
     * nothing behind, or the app would hold an execution record for an approval that does not
     * exist — an orphan the recovery rules would have to invent an answer for.
     */
    @Test
    fun `a barrier and its execution record are written in one transaction`() = runBlocking {
        val dao = database.pendingToolApprovalDao()
        val executions = database.executionRecordDao()

        runCatching {
            database.withTransaction {
                dao.insertIgnore(pendingRecord())
                executions.insertIgnore(executionRecord())
                throw IllegalStateException("the authority rolled back")
            }
        }

        assertNull("a rollback leaves no barrier", dao.getById(approvalId))
        assertNull("and no execution record", executions.getById(executionId))

        database.withTransaction {
            dao.insertIgnore(pendingRecord())
            executions.insertIgnore(executionRecord())
        }

        assertNotNull("a commit leaves both", dao.getById(approvalId))
        assertNotNull(executions.getById(executionId))
        assertEquals(
            "and the execution is opened in the state the barrier describes",
            ExecutionStatus.waiting_approval.name,
            checkNotNull(executions.getById(executionId)).status,
        )
    }

    @Test
    fun `an unresolved barrier is what a recovery sweep finds`() = runBlocking {
        val dao = database.pendingToolApprovalDao()
        dao.insertIgnore(pendingRecord())
        dao.insertIgnore(
            pendingRecord(approvalId = "approval-2", executionId = "exec-2", toolCallId = "call-2"),
        )

        val pending = dao.getPendingForConversation(conversationId)

        assertEquals(2, pending.size)
        assertTrue(
            "every one of them is in-flight, which is what stops a restart from resuming them",
            pending.all { it.isInFlightContinuation() },
        )
        assertEquals(setOf(approvalId, "approval-2"), pending.map { it.approvalId }.toSet())
    }

    // -----------------------------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------------------------

    private fun openFileDatabase(name: String): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .openHelperFactory(createAppSQLiteOpenHelperFactory(context))
            .allowMainThreadQueries()
            .build()

    private fun pendingRecord(
        approvalId: String = this.approvalId,
        executionId: String = this.executionId,
        toolCallId: String = this.toolCallId,
        continuationMode: String = ApprovalContinuationMode.IN_FLIGHT.name,
    ) = PendingToolApprovalRecord(
        approvalId = approvalId,
        executionId = executionId,
        traceId = RUN_ID,
        toolCallId = toolCallId,
        conversationId = conversationId,
        subjectId = "44444444-4444-4444-4444-444444444444",
        subjectType = "LOCAL_ASSISTANT",
        origin = "LocalChat",
        capabilityKey = "",
        resourceCategory = "unknown",
        requestedAtMs = 1_000L,
        stateVersion = PENDING_BARRIER_VERSION,
        continuationMode = continuationMode,
    )

    private fun executionRecord(
        id: String = executionId,
        toolCallId: String = this.toolCallId,
    ) = ExecutionRecord(
        id = id,
        traceId = RUN_ID,
        conversationId = conversationId,
        toolCallId = toolCallId,
        toolName = "read_file",
        toolSchemaFingerprint = "0".repeat(64),
        subjectId = "44444444-4444-4444-4444-444444444444",
        subjectType = "LOCAL_ASSISTANT",
        origin = "LocalChat",
        capabilityKeys = "",
        resourceSummary = "unknown",
        runtime = ExecutionKind.TOOL_CALL.name,
        executionKind = ExecutionKind.TOOL_CALL.name,
        status = ExecutionStatus.waiting_approval.name,
        createdAtMs = 1_000L,
        updatedAtMs = 1_000L,
        lastStateSource = ExecutionStateSource.DATABASE.name,
        lastReasonCode = "in_flight_pending_approval",
        verificationState = VerificationState.DATABASE_CONFIRMED.name,
    )

    private companion object {
        const val RUN_ID = "11111111-1111-1111-1111-111111111111"

        /** The version a freshly written barrier carries, as `persistPendingBarrier` writes it. */
        const val PENDING_BARRIER_VERSION = 1L
    }
}
