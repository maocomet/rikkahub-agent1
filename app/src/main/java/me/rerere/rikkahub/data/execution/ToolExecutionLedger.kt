package me.rerere.rikkahub.data.execution

import androidx.room.withTransaction
import me.rerere.rikkahub.data.db.AppDatabase

/**
 * The two rows that decide whether a tool may start, read as one consistent snapshot.
 *
 * They are a pair because the decision needs both and neither is meaningful alone: the record says
 * whether the call is already admitted to run, the approval says *why* it may, and a decision made
 * from one of them read at a different instant than the other is a decision about a state that
 * never existed. [ToolExecutionLedger.toolStartView] is the only way to obtain one, so a caller
 * cannot take them apart by accident.
 */
data class ToolStartView(
    val record: ExecutionRecord?,
    val approval: PendingToolApprovalRecord?,
)

/**
 * The durable operations one tool-lifecycle sink needs, stated as a seam.
 *
 * ## Why this exists
 *
 * [ExecutionRecordCriticalToolLifecycleSink] decides whether a tool may start, and that decision is
 * a security boundary: it is the last thing between an approved call and its side effect. It was
 * previously welded to `ExecutionRepository`, whose constructor needs a real `AppDatabase` — and
 * this module's unit tests have no in-memory Room and no Robolectric, so the decision could only be
 * read, never exercised. A boundary that cannot be run in a test is a boundary whose failures are
 * discovered on a phone, which is exactly how the tool-adoption defect was found.
 *
 * So the storage is behind this interface and the *decision* stays in the sink. The production
 * implementation is [RoomToolExecutionLedger], which is a straight delegation to the ledger that
 * already existed; a test supplies an in-memory implementation and therefore runs the real sink,
 * the real checks and the real lifecycle events against it.
 *
 * ## What it deliberately is not
 *
 * It is not a place to put policy. Nothing here decides anything — every method is a faithful
 * restatement of the corresponding [ExecutionRepository] call, including its mutex, its CAS and its
 * retention scheduling, so a caller cannot observe a difference between the two.
 */
interface ToolExecutionLedger {
    /**
     * Reads the call's execution record and the approval that owns it **in one snapshot**.
     *
     * An implementation must not read them independently. A stop, a cancellation or a recovery can
     * land between two reads, and the dangerous shape is exactly the one that looks fine: a record
     * still `starting` read before the stop, paired with an approval still `APPROVED` read after it.
     * Atomicity here is what makes the pair describe one instant.
     */
    suspend fun toolStartView(recordId: String): ToolStartView

    /**
     * Takes the start of an already-admitted approved call, conditionally on the record still being
     * exactly as it was when the decision was made.
     *
     * This is the linearization point of adoption. It is a compare-and-set on [record]'s version:
     * any cancellation, emergency stop, recovery or other mutation that committed in the meantime
     * has moved the version, so the claim fails rather than starting a call over a state that no
     * longer exists. Implementations must **not** retry on conflict — a retry would re-derive the
     * expectation from whatever the row has become, which is the opposite of the guarantee.
     *
     * A successful claim is the durable execution right for this start attempt. Approval grants
     * permission to execute; it does not make later attempts safe. Consequently an already
     * journaled claim is a refusal, including on paths that do not pass through the Claude P
     * bridge's in-memory claim.
     */
    suspend fun claimApprovedToolStart(
        record: ExecutionRecord,
        mutationId: String,
        reasonCode: String,
    ): ExecutionMutationResult

    /** Opens [draft] idempotently, exactly as [ExecutionRepository.open] does. */
    suspend fun open(
        draft: ExecutionRecordDraft,
        mutationId: String,
        source: ExecutionStateSource,
        reasonCode: String,
    ): ExecutionRecord

    /**
     * Applies one status transition, exactly as [ExecutionRepository.transition] does — including
     * its CAS retry and its retention scheduling. Defaults mirror that method's.
     */
    suspend fun transition(
        id: String,
        target: ExecutionStatus,
        runtimeHandleSummary: String? = null,
        cancellationResult: String? = null,
        detail: String? = null,
        mutationId: String,
        source: ExecutionStateSource = ExecutionStateSource.LIVE_EVENT,
        reasonCode: String?,
        verificationState: VerificationState? = null,
        runtime: ExecutionRuntime? = null,
        requestedTerminalOutcome: RequestedTerminalOutcome? = null,
    ): ExecutionTransitionResult
}

/** The production ledger: the execution table plus the approval projection that owns it. */
class RoomToolExecutionLedger(
    private val database: AppDatabase,
    private val repository: ExecutionRepository,
    private val approvalDao: PendingToolApprovalDao,
) : ToolExecutionLedger {
    /**
     * Both rows inside one Room transaction.
     *
     * The transaction is what makes them a snapshot: reads inside it see one database state, so no
     * mutation can land between the record and the approval. It is a read in effect, and it is not
     * what makes adoption safe on its own — the claim below is. It is what stops a decision from
     * being made about a pair of rows that never coexisted.
     */
    override suspend fun toolStartView(recordId: String): ToolStartView =
        database.withTransaction {
            ToolStartView(
                record = repository.get(recordId),
                approval = approvalDao.getByExecutionId(recordId),
            )
        }

    /**
     * A single, non-retried compare-and-set.
     *
     * `mutateObserved` is the un-retried mutation path: one attempt against `record.stateVersion`,
     * so a row that moved is a `Conflict` rather than a silent re-read that would accept whatever
     * the row has become. `LIVE_EVENT`/`LIVE_CONFIRMED` say what has actually changed: a live
     * runtime now owns a record the approval authority had only confirmed from the database. The
     * status is left where the approval put it — `starting` — because `RUNNING` is the runtime's own
     * statement, made with a handle this claim does not yet have.
     */
    override suspend fun claimApprovedToolStart(
        record: ExecutionRecord,
        mutationId: String,
        reasonCode: String,
    ): ExecutionMutationResult = repository.mutateObserved(
        ExecutionMutation(
            executionId = record.id,
            mutationId = mutationId,
            expectedVersion = record.stateVersion,
            source = ExecutionStateSource.LIVE_EVENT,
            reasonCode = reasonCode,
            targetStatus = ExecutionStatus.starting,
            verificationState = VerificationState.LIVE_CONFIRMED,
        ),
    )

    override suspend fun open(
        draft: ExecutionRecordDraft,
        mutationId: String,
        source: ExecutionStateSource,
        reasonCode: String,
    ): ExecutionRecord = repository.open(
        draft = draft,
        mutationId = mutationId,
        source = source,
        reasonCode = reasonCode,
    )

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
    ): ExecutionTransitionResult = repository.transition(  // overrides may not restate defaults
        id = id,
        target = target,
        runtimeHandleSummary = runtimeHandleSummary,
        cancellationResult = cancellationResult,
        detail = detail,
        mutationId = mutationId,
        source = source,
        reasonCode = reasonCode,
        verificationState = verificationState,
        runtime = runtime,
        requestedTerminalOutcome = requestedTerminalOutcome,
    )
}
