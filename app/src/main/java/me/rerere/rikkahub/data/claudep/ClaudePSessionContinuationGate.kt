package me.rerere.rikkahub.data.claudep

import me.rerere.ai.provider.claudep.ClaudePSessionBindOutcome
import me.rerere.ai.provider.claudep.ClaudePSessionBindingIntent
import me.rerere.ai.provider.claudep.ClaudePSessionBindingRequest
import me.rerere.ai.provider.claudep.ClaudePSessionContinuation
import me.rerere.ai.provider.claudep.ClaudePSessionContinuationResolution
import me.rerere.ai.provider.claudep.ClaudePSessionContinuationState
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.service.chat.ChatCommand
import me.rerere.rikkahub.service.chat.ResumeAfterApprovalCommand

/**
 * The one app-side seam that turns a command into a Claude P binding decision.
 *
 * ## Why this exists as a single object
 *
 * Three call sites need to agree about continuation: the admission transaction (which must write a
 * barrier before anything is dispatched), the dispatch path (which must carry the binding request
 * into the provider), and the terminal settle (which must record what the generation proved). If
 * each of them read the graph, resolved the branch and reasoned about states for itself, there
 * would be three state machines and at least one of them would eventually allow something the
 * others refuse — and the thing they would allow is a second Claude session for a branch that
 * already has one.
 *
 * So the reasoning lives here, once, and the call sites only *apply* what this returns.
 *
 * ## What it does not do
 *
 * It persists nothing. Every member here is a pure function over the authoritative conversation
 * that returns either a decision or the [UIMessage] the caller must write inside the transaction it
 * already owns. Staying pure is what makes the whole decision table testable without Room, and it
 * is also what keeps the atomicity where it belongs: the caller's transaction.
 *
 * It also does not re-derive anything the rest of the layer already owns. The mode comes from
 * [ClaudePSessionBranchPlanner], the branch identity from [ClaudePSessionBranchPlanner.branchIdOf],
 * the state from [ClaudePSessionContinuationResolver], the legality of every step from
 * [ClaudePSessionContinuationTransitions], and the meaning of a bind answer from
 * [ClaudePSessionBindOutcome]. A second spelling of any of those is the failure mode this file is
 * written to avoid.
 *
 * ## The barrier, and why some paths refuse
 *
 * A continuation record lives *inside* the message that carries it, so writing one changes that
 * message's payload digest — and that digest is the authority layer's source revision for the
 * message. Two existing durable identities are therefore perturbed by a write:
 *
 * - **The branch anchor's revision.** A child command (an approval, a resume-after-approval) is
 *   admitted with the anchor revision its parent recorded, and
 *   `CommandAdmissionAuthorityCoordinator` refuses the admission when the anchor has moved. So the
 *   anchor may only be written where nothing recorded its revision yet: the message created by the
 *   very transaction that writes the record.
 * - **An assistant message that owns tool executions.** The execution records are bound to the
 *   assistant revision resolved at the WAITING checkpoint and re-checked at the final commit, so
 *   bumping that message between the two makes `ExecutionMessageAuthorityBinder` report a conflict.
 *
 * [admission] therefore takes [AnchorOrigin] and refuses — rather than guessing — the one case
 * where the barrier cannot be written atomically. A refusal costs a visible failure; the
 * alternative costs a generation running with no durable barrier, which is exactly the state the
 * barrier exists to make impossible.
 */
object ClaudePSessionContinuationGate {

    /**
     * Whether the message that will carry the barrier is created by the admission transaction
     * that is about to write it, or already exists in the committed graph.
     *
     * This is an *input* rather than something the gate infers, because only the admission path
     * knows it: a `SendMessageCommand`'s anchor is a message the admission graph appends, while a
     * regenerate's anchor is a user message that has been committed for a while. Inferring it from
     * the command type would be a second copy of "what does this command do to the graph", and the
     * two copies would disagree the first time a command changed.
     */
    enum class AnchorOrigin {
        /**
         * The barrier message is appended by this admission transaction, so its revision is
         * produced by that same write and no recorded revision can be invalidated.
         */
        CREATED_BY_ADMISSION,

        /**
         * The barrier message is already committed and its revision is part of a durable identity
         * (an approval lineage). Writing a record on it would invalidate that identity.
         */
        ALREADY_COMMITTED,
    }

    /** What the dispatch path must do for one command. A closed set. */
    sealed interface Decision {

        /** The assistant every member names, so a caller can log or refuse without re-deriving it. */
        val assistantId: String

        /**
         * A generation on a branch that already exists: `mode: "auto"`, `binding_intent:
         * "immediate"` and the branch identity as `remote_branch_id`.
         *
         * [admissionRecord] is the record the admission transaction must attach to [barrierMessage]
         * before it persists, or `null` when this generation continues one that is already in
         * flight. `null` is not "skip the barrier": it means a barrier for this branch is already
         * durable, which is only ever true for the tool-loop continuation below.
         */
        data class Immediate(
            override val assistantId: String,
            val branchId: String,
            /**
             * The revision the branch's next record takes if one is written. For a continuation of
             * an in-flight generation it is the revision that generation's start already carries,
             * because nothing new is written.
             */
            val revision: Long,
            val admissionRecord: ClaudePSessionContinuation?,
            val request: ClaudePSessionBindingRequest,
        ) : Decision

        /**
         * A generation that will create its branch: `mode: "auto"`, `binding_intent: "deferred"`
         * and **no** `remote_branch_id`.
         *
         * There is no admission record. §5.2 stores no start for this path — the candidate does
         * not exist until the generation succeeds — and the barrier for it is the `BIND_PENDING`
         * record written in the transaction that commits the new variant.
         */
        data class Deferred(
            override val assistantId: String,
            val request: ClaudePSessionBindingRequest,
        ) : Decision

        /** No request may be sent for this command. */
        data class Refused(
            override val assistantId: String,
            val reason: Reason,
        ) : Decision

        /**
         * The command dispatches no model generation at all, so it has no continuation to plan.
         *
         * A separate member rather than a refusal because it is a correct answer: an approval
         * decision, a steering update or a queue edit genuinely dispatches nothing.
         */
        data object NotModelGeneration : Decision {
            override val assistantId: String = ""
        }
    }

    /** Why a decision is a refusal. A closed set, and each member sends a reader somewhere different. */
    enum class Reason {
        /** The committed graph is not an ordered list of nodes each selecting one of its own variants. */
        BRANCH_NOT_DESCRIBABLE,

        /** The graph's selection vector is not a legal branch identity. */
        BRANCH_MALFORMED,

        /** The branch is running, unproven or closed, and a new generation must not start on it. */
        CONTINUATION_BLOCKED,

        /** A continuation record on the selected path could not be used. */
        CONTINUATION_REFUSED,

        /** The continuation records on the selected path cannot all be true at once. */
        CONTINUATION_CONFLICTED,

        /**
         * The branch permits a generation, but its `START_IN_FLIGHT` record cannot be written by
         * the admission transaction that must carry it, so the barrier would not be atomic.
         *
         * This is the fail-closed answer, never a licence to dispatch without one. See this
         * object's doc for the two durable identities that make the write unsafe.
         */
        BARRIER_NOT_ATOMIC,
    }

    /**
     * The decision for [command] on [conversation].
     *
     * @param targetRole the role of the message a `RegenerateCommand` targets, read from the
     *   committed graph; `null` when the target is unresolved, which is deliberately treated as
     *   deferred rather than as evidence of the harmless case.
     * @param anchorOrigin whether the barrier message is created by the admission transaction.
     */
    fun admission(
        conversation: Conversation,
        command: ChatCommand,
        targetRole: me.rerere.ai.core.MessageRole?,
        anchorOrigin: AnchorOrigin,
    ): Decision {
        // A resume-after-approval is classified before the planner rather than by it, and that is a
        // correction rather than a shortcut. The planner asks the resolver whether the branch
        // permits a new generation, and a branch in `START_IN_FLIGHT` does not — which is exactly
        // the state this command runs in, because the tool loop it resumes has not reached a
        // terminal yet. Feeding it to the planner would refuse every approved tool call.
        //
        // Not a relaxation, either: see [resumeAfterApproval], which refuses the command unless the
        // branch really is in flight.
        if (command is ResumeAfterApprovalCommand) return resumeAfterApproval(conversation)

        return when (val plan = ClaudePSessionBranchPlanner.plan(conversation, command, targetRole)) {
            is ClaudePSessionContinuationPlan.NotModelGeneration -> Decision.NotModelGeneration

            is ClaudePSessionContinuationPlan.Deferred ->
                Decision.Deferred(plan.assistantId, deferredRequest(plan.assistantId))

            is ClaudePSessionContinuationPlan.Refused ->
                Decision.Refused(plan.assistantId, refusalOf(plan.reason))

            is ClaudePSessionContinuationPlan.Immediate ->
                immediate(plan, anchorOrigin)
        }
    }

    /**
     * The immediate path, which always owes a barrier — and refuses when it cannot be written.
     *
     * The record has to be attached to the message the admission transaction creates. Attaching it
     * to an already-committed message would move that message's source revision, and two durable
     * identities are built on those revisions: the approval lineage's recorded anchor revision, and
     * the execution bindings' assistant-message revision. Either one moving turns a later
     * transaction into a conflict, so the answer is [Reason.BARRIER_NOT_ATOMIC] rather than a
     * dispatch whose barrier is not atomic with its admission.
     */
    private fun immediate(
        plan: ClaudePSessionContinuationPlan.Immediate,
        anchorOrigin: AnchorOrigin,
    ): Decision {
        if (anchorOrigin != AnchorOrigin.CREATED_BY_ADMISSION) {
            return Decision.Refused(plan.assistantId, Reason.BARRIER_NOT_ATOMIC)
        }
        return Decision.Immediate(
            assistantId = plan.assistantId,
            branchId = plan.branchId,
            revision = plan.revision,
            admissionRecord = ClaudePSessionContinuation(
                assistantId = plan.assistantId,
                branchId = plan.branchId,
                revision = plan.revision,
                state = ClaudePSessionContinuationState.START_IN_FLIGHT,
                // A start has no candidate to bind. An id here could only be mistaken for
                // something replayable, and the transition table refuses it for that reason.
                generationId = null,
            ),
            request = immediateRequest(plan.assistantId, plan.branchId),
        )
    }

    /**
     * The continuation an approved tool resumes, or a refusal.
     *
     * The generation that opened the branch is still in flight — its `START_IN_FLIGHT` is on the
     * selected path and no terminal has settled it — so this is the *same* generation continuing,
     * not a second one starting. The request is therefore the same `immediate` shape the opening
     * dispatch sent, so the Worker resumes the session it already established; the barrier is
     * already durable and is deliberately not written again, because `START_IN_FLIGHT ->
     * START_IN_FLIGHT` is not a transition the table has and inventing it would let a branch
     * accumulate starts.
     *
     * Every other state refuses. A branch that is `BOUND` had a turn that finished — an approval
     * arriving after that is not a continuation of anything, and a fresh generation on it would be
     * a new turn that should have arrived as one. A branch that is `BIND_PENDING`, `INTERRUPTED`,
     * `FAILED_CLOSED`, absent, or unreadable has no in-flight generation for this command to
     * resume.
     *
     * Nothing here re-plans or re-binds, which is what §5 requires of the tool loop: one
     * generation, one plan, one bind.
     */
    private fun resumeAfterApproval(conversation: Conversation): Decision {
        val assistantId = conversation.assistantId.toString()
        val branchId = when (val described = ClaudePSessionBranchPlanner.branchIdOf(conversation.messageNodes)) {
            is ClaudePSessionBranchPlanner.Branch.Known -> described.id
            is ClaudePSessionBranchPlanner.Branch.Rejected ->
                return Decision.Refused(assistantId, Reason.BRANCH_NOT_DESCRIBABLE)

            is ClaudePSessionBranchPlanner.Branch.Malformed ->
                return Decision.Refused(assistantId, Reason.BRANCH_MALFORMED)
        }

        val resolution = ClaudePSessionContinuationResolver.resolve(conversation, branchId)
        val inFlight = resolution as? ClaudePSessionContinuationResolution.Resolved
        if (inFlight == null || inFlight.state != ClaudePSessionContinuationState.START_IN_FLIGHT) {
            return Decision.Refused(assistantId, refusalOf(resolution))
        }

        return Decision.Immediate(
            assistantId = assistantId,
            branchId = branchId,
            revision = inFlight.revision,
            // The barrier this generation already holds. `null` here means "already durable", not
            // "skipped": the opening admission wrote it, and it is the same generation.
            admissionRecord = null,
            request = immediateRequest(assistantId, branchId),
        )
    }

    /**
     * The refusal reason for a resolution that does not permit the generation the caller wanted.
     *
     * Shared by the two places that read a resolution directly rather than through the planner, so
     * that "which reason does this state produce" has one answer. The mapping mirrors the planner's
     * own, which is why it is written as a total `when` over the same three failure shapes.
     */
    private fun refusalOf(resolution: ClaudePSessionContinuationResolution): Reason = when (resolution) {
        is ClaudePSessionContinuationResolution.Refused -> Reason.CONTINUATION_REFUSED
        is ClaudePSessionContinuationResolution.Conflicted -> Reason.CONTINUATION_CONFLICTED
        else -> Reason.CONTINUATION_BLOCKED
    }

    /**
     * The bind replay a user action must complete before its own generation may dispatch, or `null`
     * when none is owed.
     *
     * ## Why this is a user action and not a listener
     *
     * §6.1's only recovery is re-sending the same bind. A timer, a poll or a background loop that
     * did it would be sending an RPC the Server is under no obligation to answer, on a connection
     * that may not be the one that dropped — and the frames would arrive with no user action behind
     * them to correlate. So the replay is attached to the next thing the user does, and there is
     * at most one per action.
     *
     * ## Why an interrupted *start* never qualifies
     *
     * [ClaudePSessionContinuationState.INTERRUPTED] is written both for a dropped bind and for a
     * dropped start. Only the first carries a [ClaudePSessionContinuation.generationId], because
     * only the first has a candidate that may still exist on the Server; a start has nothing to
     * replay and the branch stays closed. Reading the state alone would turn a dropped start into a
     * branch that looks recoverable, which is the permissive mistake this whole layer exists to
     * prevent — so the identity is required, not merely the state.
     */
    fun replay(conversation: Conversation): Replay? {
        val branchId = when (val described = ClaudePSessionBranchPlanner.branchIdOf(conversation.messageNodes)) {
            is ClaudePSessionBranchPlanner.Branch.Known -> described.id
            else -> return null
        }
        val resolution = resolutionOf(conversation, branchId) as? ClaudePSessionContinuationResolution.Resolved
            ?: return null
        if (resolution.state != ClaudePSessionContinuationState.INTERRUPTED) return null
        val generationId = resolution.generationId ?: return null
        return Replay(
            assistantId = resolution.assistantId,
            branchId = resolution.branchId,
            generationId = generationId,
            revision = resolution.revision,
        )
    }

    /**
     * What a replay did, so the caller knows whether its own generation may start.
     *
     * A closed set, and every member that is not [NoReplayNeeded] or [Bound] means "do not
     * dispatch". That is the whole contract: §4 says a failed replay must fail visibly and the
     * generation it was for must not start, and the only way a caller can honour that is if
     * "the replay did not prove a binding" is a value it can see rather than an exception it might
     * swallow.
     */
    sealed interface ReplayOutcome {
        /** The branch holds no replayable bind, so the caller proceeds with its own decision. */
        data object NoReplayNeeded : ReplayOutcome

        /** The Server confirmed the binding. The caller may dispatch. */
        data object Bound : ReplayOutcome

        /**
         * The answer settled in the negative — a refusal, or a reply this build cannot read — so
         * the replay is over and the branch is closed. Re-sending would not change the answer.
         */
        data class Closed(val state: ClaudePSessionContinuationState) : ReplayOutcome

        /**
         * The outcome is still unknown: the connection dropped, or the record could not be written.
         * The branch stays unproven and the caller must not dispatch.
         */
        data class Unproven(val reason: ReplayFailure) : ReplayOutcome
    }

    /** Why a replay did not settle. A closed set. */
    enum class ReplayFailure {
        /**
         * The `BIND_PENDING` record could not be persisted, so no bind was sent.
         *
         * Sending it anyway would be a bind whose obligation exists only in memory, which a process
         * death would erase — the branch would then look untouched while the Server held a binding
         * for it.
         */
        PENDING_NOT_PERSISTED,

        /** The graph does not contain the message this replay must write to. */
        BRANCH_MESSAGE_MISSING,

        /** The Server's answer is not one this build can act on, so the branch is not proven. */
        OUTCOME_UNPROVEN,

        /**
         * The bind settled, but writing the settlement record failed. The Server holds a binding
         * this device cannot record, so the branch is not proven from here and the caller must not
         * dispatch on it.
         */
        SETTLEMENT_NOT_PERSISTED,
    }

    /**
     * Performs the one replay [conversation] owes, if any, and reports what it proved.
     *
     * ## The order, and why it is this order
     *
     * 1. Decide whether a replay is owed — [replay], which requires an `INTERRUPTED` record
     *    carrying a generation id.
     * 2. **Persist `BIND_PENDING` before the RPC.** A bind that was in flight when the process
     *    died must leave a record saying so; persisting afterwards would make the crash window
     *    look like a branch that never owed anything.
     * 3. Send **exactly one** bind, re-using the persisted `(generationId, branchId)`. Not a fresh
     *    generation id: §6.1 recognises the repeat by identity and answers `already_bound`, which a
     *    new id would not be.
     * 4. Record what the answer proved.
     *
     * Nothing here is retried, scheduled or polled. There is one call per invocation, the caller is
     * a user action or a reconnect boundary, and a second invocation is a second user action.
     *
     * @param writeRecord persists [record] into the authoritative graph — for the branch
     *   [Replay.branchId] names — inside a transaction, and answers whether it committed. A `false`
     *   is a rollback, and it stops the replay: the graph authority commit is the permission to
     *   send, not a formality after it.
     * @param bind performs the `session.bind`. Injected rather than reached for so that the
     *   ordering this function exists to guarantee can be tested without a Server.
     */
    suspend fun replayBeforeGeneration(
        conversation: Conversation,
        writeRecord: suspend (ClaudePSessionContinuation) -> Boolean,
        bind: suspend (generationId: String, branchId: String, assistantId: String) -> ClaudePSessionBindOutcome,
    ): ReplayOutcome {
        val owed = replay(conversation) ?: return ReplayOutcome.NoReplayNeeded

        if (!writeRecord(owed.pendingRecord())) {
            return ReplayOutcome.Unproven(ReplayFailure.PENDING_NOT_PERSISTED)
        }

        val outcome = bind(owed.generationId, owed.branchId, owed.assistantId)

        if (!writeRecord(replaySettlement(owed, outcome))) {
            return ReplayOutcome.Unproven(ReplayFailure.SETTLEMENT_NOT_PERSISTED)
        }

        return when (outcome) {
            is ClaudePSessionBindOutcome.Bound,
            is ClaudePSessionBindOutcome.AlreadyBound,
                -> ReplayOutcome.Bound

            is ClaudePSessionBindOutcome.Refused,
            ClaudePSessionBindOutcome.Malformed,
                -> ReplayOutcome.Closed(ClaudePSessionContinuationState.FAILED_CLOSED)

            // Still unknown. The record written above says `INTERRUPTED` with the same generation
            // id, so the next user action replays the identical bind.
            ClaudePSessionBindOutcome.Unproven ->
                ReplayOutcome.Unproven(ReplayFailure.OUTCOME_UNPROVEN)
        }
    }

    /**
     * What a durable replay must re-send, and the two revisions the replay settles through.
     *
     * [pendingRevision] and [boundRevision] are computed here rather than at the call site so that
     * "a replay advances the branch by exactly two steps and lands on `BOUND`" is a property of
     * this type instead of arithmetic a caller has to repeat — and repeat identically — in two
     * transactions.
     */
    data class Replay(
        val assistantId: String,
        val branchId: String,
        /** The Server's generation id. The same one is re-sent, never a fresh one. */
        val generationId: String,
        /** The revision the interrupted record carried. */
        val revision: Long,
    ) {
        val pendingRevision: Long get() = revision + 1L
        val boundRevision: Long get() = revision + 2L

        /**
         * `INTERRUPTED -> BIND_PENDING`, carrying the same generation id.
         *
         * Written into the authoritative graph *before* the RPC is sent, so that a process death
         * between the two leaves a record that says "a bind was owed here" rather than a branch
         * that looks untouched.
         */
        fun pendingRecord(): ClaudePSessionContinuation = ClaudePSessionContinuation(
            assistantId = assistantId,
            branchId = branchId,
            revision = pendingRevision,
            state = ClaudePSessionContinuationState.BIND_PENDING,
            generationId = generationId,
        )

        override fun toString(): String =
            "Replay(revision=$revision, generationId=${generationId.redactedGateRef()})"
    }

    /**
     * The record a settled bind leaves, or `null` when the outcome proves nothing and the branch
     * must stay unproven.
     *
     * [ClaudePSessionBindOutcome.Unproven] is not a failure and is not a success: the Server may
     * have applied the bind before the socket died, so the branch is [INTERRUPTED] again — carrying
     * the same generation id, because that id is the whole of what a later replay re-sends.
     */
    fun replaySettlement(
        replay: Replay,
        outcome: ClaudePSessionBindOutcome,
    ): ClaudePSessionContinuation = when (outcome) {
        is ClaudePSessionBindOutcome.Bound,
        is ClaudePSessionBindOutcome.AlreadyBound,
            -> ClaudePSessionContinuation(
                assistantId = replay.assistantId,
                branchId = replay.branchId,
                revision = replay.boundRevision,
                state = ClaudePSessionContinuationState.BOUND,
                // A settled binding has no outstanding obligation, so the id is dropped — the
                // transition table refuses a `BOUND` that carries one.
                generationId = null,
            )

        is ClaudePSessionBindOutcome.Refused,
        ClaudePSessionBindOutcome.Malformed,
            -> closedRecord(replay.assistantId, replay.branchId, replay.boundRevision)

        ClaudePSessionBindOutcome.Unproven -> ClaudePSessionContinuation(
            assistantId = replay.assistantId,
            branchId = replay.branchId,
            revision = replay.boundRevision,
            state = ClaudePSessionContinuationState.INTERRUPTED,
            generationId = replay.generationId,
        )
    }

    // -----------------------------------------------------------------------------------------
    // Deferred
    // -----------------------------------------------------------------------------------------

    /**
     * The `BIND_PENDING` record for a `deferred` generation whose new variant is being committed.
     *
     * [branchId] must be read from the graph *as it will be committed* — the exact selection
     * vector, including the variant this transaction is adding — because that digest is what the
     * Server checks the bind against. A digest computed from the graph as it stood before the
     * commit would name a branch that does not exist, and the Server's answer to that is
     * `conflict`, which closes a branch that was in fact fine.
     */
    fun pendingRecord(
        assistantId: String,
        branchId: String,
        revision: Long,
        generationId: String,
    ): ClaudePSessionContinuation = ClaudePSessionContinuation(
        assistantId = assistantId,
        branchId = branchId,
        revision = revision,
        state = ClaudePSessionContinuationState.BIND_PENDING,
        generationId = generationId,
    )

    /**
     * The record a `deferred` bind settles to.
     *
     * Identical in meaning to [replaySettlement] and deliberately not merged with it: a deferred
     * *first* bind and an *interrupted* replay are different flows with different preceding states,
     * and a shared helper would have to be told which one it is serving — which is the same
     * argument for two functions written once rather than one written twice.
     */
    fun deferredSettlement(
        assistantId: String,
        branchId: String,
        revision: Long,
        generationId: String,
        outcome: ClaudePSessionBindOutcome,
    ): ClaudePSessionContinuation = when (outcome) {
        is ClaudePSessionBindOutcome.Bound,
        is ClaudePSessionBindOutcome.AlreadyBound,
            -> supersededBound(assistantId, branchId, revision)

        is ClaudePSessionBindOutcome.Refused,
        ClaudePSessionBindOutcome.Malformed,
            -> closedRecord(assistantId, branchId, revision)

        ClaudePSessionBindOutcome.Unproven -> ClaudePSessionContinuation(
            assistantId = assistantId,
            branchId = branchId,
            revision = revision,
            state = ClaudePSessionContinuationState.INTERRUPTED,
            generationId = generationId,
        )
    }

    /**
     * `BIND_PENDING -> BOUND` **in the same slot**.
     *
     * The bound record keeps the pending record's revision, because it supersedes that exact record
     * rather than advancing past it: §5.2 says no admission transaction runs for a `deferred` path,
     * so there is no start to step over, and the branch's selected path shows one record either
     * way. `ClaudePSessionContinuationTransitions` permits `BOUND` as a lone record precisely so
     * this compacted history can be read back.
     */
    private fun supersededBound(
        assistantId: String,
        branchId: String,
        revision: Long,
    ): ClaudePSessionContinuation = ClaudePSessionContinuation(
        assistantId = assistantId,
        branchId = branchId,
        revision = revision,
        state = ClaudePSessionContinuationState.BOUND,
        generationId = null,
    )

    // -----------------------------------------------------------------------------------------
    // Terminal settle
    // -----------------------------------------------------------------------------------------

    /** What ended a generation, as the app can prove it. A closed set. */
    enum class Terminal {
        /** The model finished and the turn is over. */
        SUCCEEDED,

        /** The model failed, or the user cancelled. Nothing was left running. */
        FAILED,

        /** The connection dropped, or the process died. No outcome can be proven. */
        UNPROVEN,
    }

    /**
     * The terminal record for an immediate generation, or `null` when [terminal] proves nothing
     * about the binding and the flow must settle as [Terminal.UNPROVEN].
     *
     * Only [Terminal.SUCCEEDED] writes [ClaudePSessionContinuationState.BOUND]. A `mode: "auto"`
     * request that completed is the proof §6.1's bind would otherwise give: the Worker resolved the
     * branch and the session it chose is now the branch's session, so the next turn may send
     * `immediate` again. A failure or a cancellation proves the opposite — the turn did not
     * complete — and [ClaudePSessionContinuationState.FAILED_CLOSED] is absorbing, so a branch that
     * reached it can only be continued by creating a new one.
     */
    fun immediateTerminal(
        assistantId: String,
        branchId: String,
        boundRevision: Long,
        terminal: Terminal,
    ): ClaudePSessionContinuation = when (terminal) {
        Terminal.SUCCEEDED -> ClaudePSessionContinuation(
            assistantId = assistantId,
            branchId = branchId,
            revision = boundRevision,
            state = ClaudePSessionContinuationState.BOUND,
            generationId = null,
        )

        Terminal.FAILED -> closedRecord(assistantId, branchId, boundRevision)

        // An interrupted *start* carries no generation id: there is no candidate to bind, and an id
        // here could only be mistaken for something a replay could re-send.
        Terminal.UNPROVEN -> ClaudePSessionContinuation(
            assistantId = assistantId,
            branchId = branchId,
            revision = boundRevision,
            state = ClaudePSessionContinuationState.INTERRUPTED,
            generationId = null,
        )
    }

    private fun closedRecord(
        assistantId: String,
        branchId: String,
        revision: Long,
    ): ClaudePSessionContinuation = ClaudePSessionContinuation(
        assistantId = assistantId,
        branchId = branchId,
        revision = revision,
        state = ClaudePSessionContinuationState.FAILED_CLOSED,
        generationId = null,
    )

    // -----------------------------------------------------------------------------------------
    // Writing onto the graph
    // -----------------------------------------------------------------------------------------

    /**
     * The message a terminal record for `(assistantId, branchId)` must be written to, or `null`
     * when the graph offers none.
     *
     * ## Why the target is chosen here and not at the call site
     *
     * A branch's records are folded **in node order**, so a terminal is only legal if it sits after
     * the barrier that opened the generation. Writing it back onto the barrier's own message reads
     * as a revision regression, and writing it onto any earlier message reads as the branch
     * restarting — both are refused by the transition table, and both would leave a turn that
     * completed with a branch that cannot be continued.
     *
     * The rule that produces the right message is therefore structural rather than positional: the
     * **last message on the selected path that does not already carry this branch's record**.
     *
     * - Turn one: the barrier is on the user message, so the target is the assistant message the
     *   turn produced.
     * - Turn two: the barrier is on the *new* user message, so the target is again the new
     *   assistant message, and the fold reads start, bound, start, bound.
     * - A `deferred` generation: the variant is committed carrying nothing, so the new variant is
     *   its own target and the `BIND_PENDING` it takes is the branch's first record.
     *
     * `null` means the graph grew no message for this branch, so there is nothing a terminal could
     * be about. A caller must treat that as a rollback rather than as "nothing to do": the
     * generation is over either way, and a branch left in `START_IN_FLIGHT` is closed, not open.
     */
    fun terminalTarget(
        conversation: Conversation,
        assistantId: String,
        branchId: String,
    ): String? = conversation.messageNodes
        .asSequence()
        .mapNotNull { node -> node.messages.getOrNull(node.selectIndex) }
        .filter { message ->
            val existing = message.claudePSessionContinuation
            existing == null || existing.assistantId != assistantId || existing.branchId != branchId
        }
        .lastOrNull()
        ?.id
        ?.toString()

    /**
     * [conversation] with [record] attached to the message [messageId] names, or `null` when that
     * message is not in the graph.
     *
     * Returns `null` rather than the unchanged conversation because "the message I was told to
     * write to is gone" and "I wrote it" must not be the same answer: a caller that treated them
     * alike would commit a graph it believed carried a barrier. The caller decides what a missing
     * target means — for a terminal settle it is a rollback, for a replay it is a refusal.
     *
     * The record replaces any record the message already carried, which is what makes
     * `BIND_PENDING -> BOUND` a supersede rather than an append. Legality is the transition table's
     * to decide, and the writer is only ever handed a record that table already permits.
     */
    fun attach(
        conversation: Conversation,
        messageId: String,
        record: ClaudePSessionContinuation,
    ): Conversation? {
        var found = false
        val nodes = conversation.messageNodes.map { node ->
            if (node.messages.none { it.id.toString() == messageId }) return@map node
            found = true
            node.copy(
                messages = node.messages.map { message ->
                    if (message.id.toString() == messageId) message.with(record) else message
                },
            )
        }
        return if (found) conversation.copy(messageNodes = nodes) else null
    }

    /**
     * [conversation] with [record] attached to the message the admission transaction is about to
     * create, or `null` when the graph already contains that identity.
     *
     * The inserted message is the barrier. It is created *with* the record rather than updated
     * afterwards, because the authority layer derives each message's source revision from its
     * payload digest at the moment it is first stored: a message inserted carrying the record has
     * revision 1 and no earlier revision exists to invalidate, while a message updated afterwards
     * would move a revision that an approval lineage may already have recorded.
     *
     * A pre-existing message with the same identity is **not** overwritten. Either it already
     * carries the record — a repeated admission of the same command — or it carries something
     * else, and in both cases the honest answer is `null`: the caller must read the existing record
     * and decide, rather than have this function silently replace a fact it did not write.
     */
    fun appendWithBarrier(
        conversation: Conversation,
        message: UIMessage,
        record: ClaudePSessionContinuation,
    ): Conversation? {
        val id = message.id.toString()
        if (conversation.messageNodes.any { node -> node.messages.any { it.id.toString() == id } }) {
            return null
        }
        return conversation.copy(
            messageNodes = conversation.messageNodes + message.with(record).toNode(),
        )
    }

    // -----------------------------------------------------------------------------------------
    // Shared wiring
    // -----------------------------------------------------------------------------------------

    private fun UIMessage.with(record: ClaudePSessionContinuation): UIMessage =
        copy(claudePSessionContinuation = record)

    private fun UIMessage.toNode(): MessageNode = MessageNode(messages = listOf(this), selectIndex = 0)

    /**
     * The resolution of [branchId] on [conversation]'s selected path, or `null` when the branch
     * identity itself is not a usable one.
     *
     * The two failure vocabularies are kept apart, as
     * [ClaudePSessionBranchPlanner.branchIdOf] keeps them: a graph that cannot be described and a
     * vector that is not a legal one send a reader to different places. Here both are simply "no
     * resolution", because every caller is deciding whether to proceed, and neither answer is a
     * reason to proceed.
     */
    private fun resolutionOf(
        conversation: Conversation,
        branchId: String,
    ): ClaudePSessionContinuationResolution? {
        val described = ClaudePSessionBranchPlanner.branchIdOf(conversation.messageNodes)
        if (described !is ClaudePSessionBranchPlanner.Branch.Known) return null
        if (described.id != branchId) return null
        return ClaudePSessionContinuationResolver.resolve(conversation, branchId)
    }

    private fun immediateRequest(assistantId: String, branchId: String) =
        ClaudePSessionBindingRequest(
            assistantId = assistantId,
            intent = ClaudePSessionBindingIntent.IMMEDIATE,
            branchId = branchId,
        )

    private fun deferredRequest(assistantId: String) =
        ClaudePSessionBindingRequest(
            assistantId = assistantId,
            intent = ClaudePSessionBindingIntent.DEFERRED,
            // Absent, never empty: §5.3 encodes "this branch does not exist yet" with the field's
            // own presence flag, and an empty string is a present-but-empty identity that would
            // share a fingerprint with a shape it is not.
            branchId = null,
        )

    /**
     * The plan's refusal reason, mapped onto this seam's own vocabulary.
     *
     * The two enums are separate on purpose: the planner's reasons are about *producing a plan*,
     * and this one adds [Reason.BARRIER_NOT_ATOMIC], which is a fact about the admission
     * transaction rather than about the plan. Folding the barrier case into the planner's enum
     * would put a transaction-shaped reason in a type that never sees a transaction.
     */
    private fun refusalOf(reason: ClaudePSessionContinuationPlan.Reason): Reason = when (reason) {
        ClaudePSessionContinuationPlan.Reason.BRANCH_NOT_DESCRIBABLE -> Reason.BRANCH_NOT_DESCRIBABLE
        ClaudePSessionContinuationPlan.Reason.BRANCH_MALFORMED -> Reason.BRANCH_MALFORMED
        ClaudePSessionContinuationPlan.Reason.CONTINUATION_BLOCKED -> Reason.CONTINUATION_BLOCKED
        ClaudePSessionContinuationPlan.Reason.CONTINUATION_REFUSED -> Reason.CONTINUATION_REFUSED
        ClaudePSessionContinuationPlan.Reason.CONTINUATION_CONFLICTED -> Reason.CONTINUATION_CONFLICTED
    }

    /** The gate's own redaction, because the provider module keeps its helper internal. */
    private fun String.redactedGateRef(): String =
        if (isEmpty()) "<none>" else "<${length}:${hashCode().toUInt().toString(16)}>"
}
