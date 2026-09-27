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
import me.rerere.rikkahub.service.chat.GenerationTerminalGraphSettlement
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
         * A turn reported success but the graph holds no message that success could have produced.
         *
         * There is deliberately no fallback. Writing `BOUND` for a turn that produced no answer
         * would credit the branch with a binding no model turn ever proved, and the next turn would
         * send `immediate` on the strength of it.
         */
        SUCCESS_WITHOUT_TERMINAL_MESSAGE,

        /**
         * The run's barrier record is not on the selected path exactly once — zero matches, or the
         * same step recorded twice.
         *
         * Either way the graph is not the one this obligation was written against, so there is no
         * message the terminal belongs to and no safe place to invent one.
         */
        BARRIER_NOT_UNIQUELY_LOCATED,

        /** The message a record was to be written to is no longer in the graph. */
        SETTLEMENT_TARGET_MISSING,

        /**
         * The record was written, and the branch does not read back as it.
         *
         * The transition table refused the step — an illegal shape, a revision regression, a gap,
         * or two records that cannot both be true — so the write is discarded rather than left in
         * the graph as a record no reader can resolve.
         */
        SETTLEMENT_NOT_READABLE,

        /**
         * A `deferred` obligation reached the generation-terminal seam.
         *
         * A deferred branch is settled by the `session.bind` that follows the variant commit, not
         * by a generation terminal, so this is a wiring defect rather than a branch state.
         */
        DEFERRED_SETTLES_THROUGH_BIND,
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
                immediate(plan)
        }
    }

    /**
     * The immediate path, which always owes a barrier.
     *
     * An earlier build refused the cases where the barrier had to be attached to an
     * already-committed message, because writing a record moved that message's source revision and
     * an approval lineage had recorded it. That premise is gone:
     * `ConversationSourceSnapshotFactory.payloadIntegritySha256` now hashes a projection that
     * clears `claudePSessionContinuation`, so a continuation-only write leaves the revision where
     * it was — proved by `ConversationContinuationSourceRevisionTest`. The refusal was removed
     * rather than left in place, because a refusal whose reason no longer exists refuses real work.
     *
     * What the caller must get right instead is *where* the record goes, and that is now a fact
     * about the graph rather than about atomicity: appended with the message when the admission
     * transaction creates it (`appendWithBarrier`), attached to it when the anchor already exists
     * (`attach`).
     */
    private fun immediate(plan: ClaudePSessionContinuationPlan.Immediate): Decision {
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
        // The record this replay supersedes has to be located before it can be replaced, and it is
        // located by *exact* match — so a path holding the same step twice resolves to nothing and
        // the replay is refused rather than writing over one of two candidates.
        val messageId = recordMessageIds(
            conversation,
            ClaudePSessionContinuation(
                assistantId = resolution.assistantId,
                branchId = resolution.branchId,
                revision = resolution.revision,
                state = ClaudePSessionContinuationState.INTERRUPTED,
                generationId = generationId,
            ),
        ).singleOrNull() ?: return null
        return Replay(
            assistantId = resolution.assistantId,
            branchId = resolution.branchId,
            generationId = generationId,
            revision = resolution.revision,
            messageId = messageId,
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
        /**
         * The message whose record this replay supersedes, located when the obligation was read.
         *
         * Carried rather than re-derived, for the same reason [DeferredPending.messageId] is: the
         * two records a replay writes replace the one already in that slot, and the slot stops being
         * findable by shape the moment the first write lands on it. A caller that recomputed it
         * would write the settled state somewhere the interrupted one never was.
         */
        val messageId: String,
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
     * The conversation a replay transaction must persist for [record], or a refusal.
     *
     * The replay writes **two** records into one slot — the `BIND_PENDING` that says the bind is
     * owed, then whatever the answer proved — and both replace the interrupted record the obligation
     * was read from. So both go through here: the target is [Replay.messageId] rather than a message
     * re-derived from the graph, and the result is checked by the same arbiter every other write
     * uses, so a step the transition table refuses is a refusal rather than a record no reader can
     * resolve.
     *
     * A refusal must stop the caller: the first one means no bind was sent, and the second means the
     * Server answered and this device cannot record what it said.
     */
    fun settleReplay(
        conversation: Conversation,
        replay: Replay,
        record: ClaudePSessionContinuation,
    ): Settlement {
        val settled = attach(conversation, replay.messageId, record)
            ?: return Settlement.Refused(Reason.SETTLEMENT_TARGET_MISSING)
        return validated(settled, replay.branchId, record)
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
     * The conversation the transaction **after** a `deferred` bind must persist, or a refusal.
     *
     * ## Why the bind's two writes are two transactions
     *
     * The `BIND_PENDING` shares the transaction that commits the variant, because a bind obligation
     * that is not durable must not be sent. The *answer* arrives later, over a socket, and cannot be
     * inside that transaction — so it is written by a transaction of its own, and the record it
     * writes supersedes the pending one **in the same slot** ([pending]`."messageId"`), keeping its
     * revision: the branch shows one record either way, which is what §5.2's "no admission
     * transaction runs for a deferred path" leaves room for.
     *
     * A refusal here is not "nothing to do": the Server has already answered and this device cannot
     * record what it said, so the caller must surface it rather than leave the branch reading as
     * `BIND_PENDING` for a bind that in fact settled.
     */
    fun settleDeferred(
        conversation: Conversation,
        pending: DeferredPending,
        outcome: ClaudePSessionBindOutcome,
    ): Settlement {
        val record = deferredSettlement(
            assistantId = pending.record.assistantId,
            branchId = pending.branchId,
            revision = pending.revision,
            generationId = pending.record.generationId
                ?: return Settlement.Refused(Reason.SETTLEMENT_TARGET_MISSING),
            outcome = outcome,
        )
        val settled = attach(conversation, pending.messageId, record)
            ?: return Settlement.Refused(Reason.SETTLEMENT_TARGET_MISSING)
        return validated(settled, pending.branchId, record)
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

    /**
     * The terminal a durable command state describes, or `null` when it describes no end at all.
     *
     * ## Why the authority's vocabulary maps here
     *
     * The settlement attached to a run is written when the barrier is, long before the outcome
     * exists, so it is handed the authority's own terminal state and has to translate. That
     * translation lives here — one function, one table — rather than inside the closure that
     * happens to be attached, because a per-attachment copy is how the same cancellation would come
     * to mean `FAILED_CLOSED` on one path and `INTERRUPTED` on another.
     *
     * The interesting row is `COMPLETED`: a run that reached this seam reporting completion while
     * producing no result assistant message maps to [Terminal.SUCCEEDED], and [settleImmediate]
     * then **refuses** it if the graph holds nothing that completion could have produced. That is
     * deliberate — the mapping states what the authority said, and the gate decides whether the
     * graph agrees. Folding the two together would either invent a `BOUND` or fail a real turn.
     */
    fun terminalFor(terminalState: me.rerere.rikkahub.service.chat.DurableCommandState): Terminal? =
        when (terminalState) {
            me.rerere.rikkahub.service.chat.DurableCommandState.COMPLETED -> Terminal.SUCCEEDED
            me.rerere.rikkahub.service.chat.DurableCommandState.CANCELLED -> Terminal.UNPROVEN
            me.rerere.rikkahub.service.chat.DurableCommandState.FAILED -> Terminal.FAILED
            // PENDING, RUNNING and WAITING_APPROVAL are not ends, so there is nothing to settle.
            else -> null
        }

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
     * The continuation obligation one admitted command carries for the whole of its run.
     *
     * ## Why this exists as a value rather than a decision at each exit
     *
     * A run can end in more than one place — a completed generation, a model failure, a user
     * cancellation, a terminal-less fallback, a connection that dropped — and each of those sites
     * used to be a separate opportunity to decide what the branch's durable state should become. A
     * decision made per site is a decision that eventually differs per site, and the difference is
     * always in the permissive direction: one path writes `FAILED_CLOSED` where another reads the
     * absence of a record as "nothing happened here".
     *
     * So admission produces this once, the run carries it, and **every** exit calls
     * [settleImmediate] (or the deferred pair) with it. There is one place that maps a terminal onto
     * a durable state, and it is this object.
     *
     * [Decision.NotModelGeneration], [Decision.Refused] and a caller that resolved no branch all
     * produce `null`: they have no branch to be responsible for, which is a different fact from
     * "this branch is fine".
     */
    sealed interface Obligation {
        /** The assistant the branch belongs to, so a caller can log without re-deriving it. */
        val assistantId: String

        /**
         * A branch that already exists. When the run ends, its terminal must be written — a
         * completed turn proves the binding, and anything else closes or interrupts it.
         */
        data class Immediate(
            override val assistantId: String,
            val branchId: String,
            /** The revision of the `START_IN_FLIGHT` this run's barrier wrote. */
            val revision: Long,
        ) : Obligation

        /**
         * A branch this run will create. It has no admission-time barrier — §5.2 stores no start for
         * a deferred path — so the obligation is the `BIND_PENDING` written in the transaction that
         * commits the new variant, and the bind that follows it.
         */
        data class Deferred(override val assistantId: String) : Obligation

        companion object {
            /**
             * The obligation a decision carries, or `null` when it carries none.
             *
             * `null` is not a licence to skip settling: it means this run is not responsible for a
             * branch, which is true for a command that dispatched nothing and for one the gate
             * already refused.
             */
            fun of(decision: Decision): Obligation? = when (decision) {
                is Decision.Immediate ->
                    Immediate(decision.assistantId, decision.branchId, decision.revision)

                is Decision.Deferred -> Deferred(decision.assistantId)

                is Decision.Refused, Decision.NotModelGeneration -> null
            }
        }
    }

    /** What a settlement leaves the caller to do. A closed set. */
    sealed interface Settlement {
        /**
         * Persist this conversation inside the transaction that is already ending the command. It
         * carries the record the terminal proved.
         */
        data class Commit(val conversation: Conversation) : Settlement

        /**
         * This run carried no continuation obligation at all, so there is nothing a terminal could
         * be about.
         *
         * **This is the only thing it means.** It is not "there was an obligation and the graph
         * offered nowhere to put it": that case is [Refused], because a run that wrote a barrier
         * and then could not settle it has left the branch in a state the gate reads as *a model
         * may be running*, and reporting that as "nothing to do" is how a branch lingers in
         * `START_IN_FLIGHT` forever.
         */
        data object NothingToWrite : Settlement

        /** An obligation exists and could not be settled. The caller must fail visibly. */
        data class Refused(val reason: Reason) : Settlement
    }

    /**
     * The conversation the terminal transaction must persist for an immediate run.
     *
     * ## The one place a terminal becomes a durable state
     *
     * [Terminal] maps onto exactly three states and never onto "leave it alone". A completed turn
     * proves the binding ([ClaudePSessionContinuationState.BOUND]); a model or protocol failure, and
     * a bind that settled in the negative, close the branch
     * ([ClaudePSessionContinuationState.FAILED_CLOSED], which is absorbing); a cancellation, a
     * dropped connection or a run that ended with no terminal at all leaves it unproven
     * ([ClaudePSessionContinuationState.INTERRUPTED]).
     *
     * A caller must not pass [Terminal.SUCCEEDED] for a run it did not see complete. The type cannot
     * enforce that — only the caller knows — but every exit path in the app routes through here, so
     * the question "was this a success?" is asked in three places instead of answered in six.
     */
    fun settleImmediate(
        conversation: Conversation,
        obligation: Obligation.Immediate,
        terminal: Terminal,
    ): Settlement {
        // Where a terminal may lawfully go, in order of preference:
        //
        // 1. the newest message this branch gained — the assistant answer a completed turn
        //    produced, or any message added after the barrier. The terminal takes the next revision,
        //    so the path reads start then terminal.
        // 2. failing that, the barrier's own message, **superseded in its own slot**. The terminal
        //    keeps the barrier's revision rather than taking the next one, so the path reads as if
        //    the start had never been there at all — which is what makes `BOUND(n) ->
        //    FAILED_CLOSED(n+1)` contiguous, and what leaves a first turn showing a lone
        //    `FAILED_CLOSED(1)` or `INTERRUPTED(1)`.
        //
        // A success has no second option. A turn that produced no answer did not succeed, and
        // writing `BOUND` for it would claim a binding that no model turn ever proved.
        val target = terminalTarget(conversation, obligation.assistantId, obligation.branchId)
        if (target != null) {
            val record = immediateTerminal(
                assistantId = obligation.assistantId,
                branchId = obligation.branchId,
                boundRevision = obligation.revision + 1L,
                terminal = terminal,
            )
            val settled = attach(conversation, target, record)
                ?: return Settlement.Refused(Reason.SETTLEMENT_TARGET_MISSING)
            return validated(settled, obligation.branchId, record)
        }

        if (terminal == Terminal.SUCCEEDED) {
            return Settlement.Refused(Reason.SUCCESS_WITHOUT_TERMINAL_MESSAGE)
        }

        // The rollback case: dispatch happened, the barrier is durable, and the graph grew nothing
        // — no assistant message, or one that a rollback took with it. The exact barrier record is
        // located rather than assumed, and superseded in place, keeping its revision.
        val barrier = barrierMessageIds(conversation, obligation)
        if (barrier.size != 1) {
            return Settlement.Refused(Reason.BARRIER_NOT_UNIQUELY_LOCATED)
        }
        val record = immediateTerminal(
            assistantId = obligation.assistantId,
            branchId = obligation.branchId,
            boundRevision = obligation.revision,
            terminal = terminal,
        )
        val settled = attach(conversation, barrier.single(), record)
            ?: return Settlement.Refused(Reason.SETTLEMENT_TARGET_MISSING)
        return validated(settled, obligation.branchId, record)
    }

    /**
     * [candidate] if the branch reads back as the record that was just written, or a refusal.
     *
     * ## Why the transition table is the arbiter and not this function
     *
     * Superseding a barrier is legal only when the terminal is the record the fold *ends* on. Turn
     * two's barrier, for instance, sits after turn one's `BOUND`, and the table permits `BOUND` to
     * be followed only by a start — so replacing that barrier with a terminal would fold as an
     * illegal transition. Rather than restate that rule here (and get it subtly wrong), the
     * candidate graph is resolved through the same resolver every other reader uses, and a
     * resolution that is not the expected settled state is a refusal.
     *
     * That also catches the revision rules for free: a supersede that would read as a regression or
     * a gap is not a `Resolved` terminal, so it is refused rather than written.
     */
    private fun validated(
        candidate: Conversation,
        branchId: String,
        record: ClaudePSessionContinuation,
    ): Settlement {
        val read = ClaudePSessionContinuationResolver.resolve(candidate, branchId)
        if (read !is ClaudePSessionContinuationResolution.Resolved) {
            return Settlement.Refused(Reason.SETTLEMENT_NOT_READABLE)
        }
        if (read.state != record.state || read.revision != record.revision) {
            return Settlement.Refused(Reason.SETTLEMENT_NOT_READABLE)
        }
        return Settlement.Commit(candidate)
    }

    /**
     * The message ids on the selected path carrying **exactly** this run's barrier record, in path
     * order.
     *
     * Exactness is the point and it is total: assistant, branch, revision, state *and* the absence
     * of a generation id. A looser match would let a terminal settle onto a record from a different
     * turn of the same branch — which carries the same assistant and branch by construction, and
     * differs only in the revision that says *which* turn it was.
     *
     * The caller requires exactly one. Zero means the barrier is not where this run left it, and
     * more than one means the path holds the same step twice, which no writer produces. Both are
     * refusals, because both mean the graph is not the one this obligation was written against.
     */
    fun barrierMessageIds(
        conversation: Conversation,
        obligation: Obligation.Immediate,
    ): List<String> = recordMessageIds(
        conversation,
        ClaudePSessionContinuation(
            assistantId = obligation.assistantId,
            branchId = obligation.branchId,
            revision = obligation.revision,
            state = ClaudePSessionContinuationState.START_IN_FLIGHT,
            generationId = null,
        ),
    )

    /**
     * The message ids on the selected path carrying **exactly** [record], in path order.
     *
     * The general form of [barrierMessageIds], and the reason both exist as one expression: every
     * write in this file replaces a record **in the slot that record already occupies**, so each
     * writer has to locate that slot before it can supersede it. A per-writer locator would be a
     * second answer to "which message is this record on", and the two would disagree the first time
     * one of them was written for a slightly different record shape.
     *
     * Exactness is total — every field, including the *absence* of a generation id where the state
     * forbids one — because a looser match lets a writer supersede a record from a different turn of
     * the same branch, which carries the same assistant and branch by construction and differs only
     * in the revision that says *which* turn it was.
     */
    fun recordMessageIds(
        conversation: Conversation,
        record: ClaudePSessionContinuation,
    ): List<String> = conversation.messageNodes
        .mapNotNull { node -> node.messages.getOrNull(node.selectIndex) }
        .filter { message -> message.claudePSessionContinuation == record }
        .map { message -> message.id.toString() }

    /**
     * Settles whatever obligation [decision] carries, or [Settlement.NothingToWrite] when it
     * carries none.
     *
     * The entry point every runtime exit calls. A `deferred` obligation is **refused** here rather
     * than ignored: it is settled by the bind that follows the variant commit, not by the
     * generation's terminal, and answering "nothing to write" for it would silently drop the bind
     * this run owes.
     */
    fun settle(
        conversation: Conversation,
        decision: Decision,
        terminal: Terminal,
    ): Settlement = when (val obligation = Obligation.of(decision)) {
        null -> Settlement.NothingToWrite
        is Obligation.Immediate -> settleImmediate(conversation, obligation, terminal)
        is Obligation.Deferred -> Settlement.Refused(Reason.DEFERRED_SETTLES_THROUGH_BIND)
    }

    /**
     * The conversation the admission transaction must commit, with this run's barrier attached, or
     * `null` when the barrier cannot be written where it belongs.
     *
     * ## Why the caller gets a conversation rather than a record
     *
     * The record is only half of the write. *Which message carries it* is the other half, and it is
     * a fact about the graph rather than about the run: for a send the message is the one the
     * admission transaction is creating, for a regenerate it is the anchor the command already
     * names. Both are [anchorMessageId], and the caller that owns the transaction is the only party
     * that knows it has appended the first one.
     *
     * `null` is a refusal and not a no-op, because the two cases must not be the same answer:
     *
     * - a [Decision.Immediate] whose `admissionRecord` is `null` owes **no** write — the barrier is
     *   already durable and this run continues the generation it belongs to — and returns the
     *   conversation unchanged;
     * - a decision that names a record the graph cannot accept returns `null`, which must stop the
     *   admission rather than admit a command whose barrier was never written.
     *
     * The anchor must be the **selected** variant of its node. An unselected variant is a message
     * the user is not looking at, so a record written there would sit on a branch that does not
     * exist — and the resolver, which reads only selected variants, would never see it.
     */
    fun barricade(
        conversation: Conversation,
        decision: Decision,
        anchorMessageId: String,
    ): Conversation? {
        val immediate = decision as? Decision.Immediate ?: return conversation
        val record = immediate.admissionRecord ?: return conversation
        val onSelectedPath = conversation.messageNodes.any { node ->
            node.messages.getOrNull(node.selectIndex)?.id?.toString() == anchorMessageId
        }
        if (!onSelectedPath) return null
        return attach(conversation, anchorMessageId, record)
    }

    /**
     * The run-local obligation to settle when [decision]'s run ends **without a result assistant
     * message**, or `null` when the run owes no graph write.
     *
     * ## Why this is built from the decision and not from the outcome
     *
     * It is attached when the barrier is — long before anything is known about how the run will
     * end — so it cannot capture an outcome. It is handed the authority's [DurableCommandState]
     * when it is finally called, and maps it through [terminalFor] exactly once, in the one place
     * that mapping lives. That is what keeps a cancellation from being reported as a failure on one
     * path and an interruption on another.
     *
     * A `deferred` obligation produces no settlement at all, and that is not an omission: a
     * deferred run owes nothing until it commits the variant that creates its branch, and the write
     * it owes then is the `BIND_PENDING` of [deferredPending], not a generation terminal. A run
     * that ends without producing that variant has no branch to settle, so `null` — which routes
     * the run to the unchanged `finishFallback` — is the honest answer.
     */
    fun settlementFor(decision: Decision): GenerationTerminalGraphSettlement? =
        when (decision) {
            is Decision.Immediate -> GenerationTerminalGraphSettlement { conversation, state ->
                val terminal = terminalFor(state)
                    ?: return@GenerationTerminalGraphSettlement null
                val settlement = settleImmediate(
                    conversation = conversation,
                    obligation = Obligation.Immediate(
                        assistantId = decision.assistantId,
                        branchId = decision.branchId,
                        revision = decision.revision,
                    ),
                    terminal = terminal,
                )
                (settlement as? Settlement.Commit)?.conversation
            }

            is Decision.Deferred,
            is Decision.Refused,
            Decision.NotModelGeneration,
                -> null
        }

    /**
     * The unproven start this branch holds, when [command] is refused **because** of it, or `null`.
     *
     * ## Why the planner is asked first
     *
     * `START_IN_FLIGHT` means "a model may be running on this branch". That is exactly what
     * [Reason.CONTINUATION_BLOCKED] reports, and this function refines *which* blocked state it was
     * — so the refinement is anchored to the refusal rather than to a second reading of the graph
     * that could disagree with the planner about whether a generation was permitted at all.
     *
     * ## Why the other blocked states are not returned
     *
     * An outstanding `BIND_PENDING`, a settled `INTERRUPTED` and an absorbing `FAILED_CLOSED` are
     * all blocked states, and none of them is recoverable by reconciliation. `BIND_PENDING` waits
     * for an answer that may still arrive, `INTERRUPTED` waits for a user-triggered replay of a
     * bind rather than of a generation, and `FAILED_CLOSED` does not recover. Reporting any of them
     * as a stranded start would rewrite a state that is still correct into one that is not.
     */
    fun staleInFlight(
        conversation: Conversation,
        command: ChatCommand,
        targetRole: me.rerere.ai.core.MessageRole?,
    ): Obligation.Immediate? {
        val plan = ClaudePSessionBranchPlanner.plan(conversation, command, targetRole)
        if (plan !is ClaudePSessionContinuationPlan.Refused) return null
        if (plan.reason != ClaudePSessionContinuationPlan.Reason.CONTINUATION_BLOCKED) return null

        val candidate = ClaudePSessionBranchPlanner.graphAfterCommand(conversation, command)
        val branchId = when (val described = ClaudePSessionBranchPlanner.branchIdOf(candidate.messageNodes)) {
            is ClaudePSessionBranchPlanner.Branch.Known -> described.id
            else -> return null
        }
        val resolved = ClaudePSessionContinuationResolver.resolve(candidate, branchId)
        if (resolved !is ClaudePSessionContinuationResolution.Resolved) return null
        if (resolved.state != ClaudePSessionContinuationState.START_IN_FLIGHT) return null
        return Obligation.Immediate(
            assistantId = resolved.assistantId,
            branchId = resolved.branchId,
            revision = resolved.revision,
        )
    }

    /**
     * [conversation] with the stranded start [stale] describes superseded by [Terminal.UNPROVEN],
     * **in the barrier's own slot**.
     *
     * ## Why this is not [settleImmediate]
     *
     * [settleImmediate] prefers the newest message the branch gained, which is right after a
     * dispatch and wrong here: nothing was dispatched, so the newest message is the previous turn's
     * answer, and a terminal written there would fold *before* the barrier it is meant to replace —
     * a revision regression the transition table refuses.
     *
     * The barrier's own message is therefore located exactly and replaced. Because [attach] replaces
     * rather than appends, the path afterwards shows a lone `INTERRUPTED` at the barrier's revision,
     * which is what "the start never completed and nothing can be proven about it" looks like on
     * disk. The revision is deliberately **not** advanced: the terminal supersedes the start in its
     * slot, exactly as the rollback case of [settleImmediate] does.
     *
     * A refusal is not a no-op: the caller must stop and pause, because a branch whose barrier
     * cannot be located is a graph this build cannot reason about, and dispatching on it is what the
     * whole barrier exists to prevent.
     */
    fun supersedeStale(
        conversation: Conversation,
        stale: Obligation.Immediate,
    ): Settlement {
        val barrier = barrierMessageIds(conversation, stale)
        if (barrier.size != 1) {
            return Settlement.Refused(Reason.BARRIER_NOT_UNIQUELY_LOCATED)
        }
        val record = immediateTerminal(
            assistantId = stale.assistantId,
            branchId = stale.branchId,
            boundRevision = stale.revision,
            terminal = Terminal.UNPROVEN,
        )
        val settled = attach(conversation, barrier.single(), record)
            ?: return Settlement.Refused(Reason.SETTLEMENT_TARGET_MISSING)
        return validated(settled, stale.branchId, record)
    }

    /**
     * The `BIND_PENDING` write that must share the transaction committing a `deferred` variant.
     *
     * The branch identity is computed from [conversation] **as it will be committed** — the variant
     * already in place and selected — because that digest is what the Server checks the bind
     * against. Computing it from the graph as it stood before the commit would name a branch that
     * does not exist, and the Server answers that with `conflict`, which closes a branch that was in
     * fact fine.
     *
     * Returns `null` when the graph has no message to write to, which is a caller error rather than
     * a refusal: a deferred commit always adds a variant, so a graph with nowhere to put the record
     * is not the graph the caller meant.
     */
    fun deferredPending(
        conversation: Conversation,
        assistantId: String,
        generationId: String,
    ): DeferredPending? {
        val branchId = when (val described = ClaudePSessionBranchPlanner.branchIdOf(conversation.messageNodes)) {
            is ClaudePSessionBranchPlanner.Branch.Known -> described.id
            else -> return null
        }
        val target = terminalTarget(conversation, assistantId, branchId) ?: return null
        val revision = ClaudePSessionContinuationWrite
            .nextRevision(ClaudePSessionContinuationResolver.resolve(conversation, branchId))
            ?: 1L
        val record = pendingRecord(
            assistantId = assistantId,
            branchId = branchId,
            revision = revision,
            generationId = generationId,
        )
        val written = attach(conversation, target, record) ?: return null
        return DeferredPending(
            conversation = written,
            branchId = branchId,
            revision = revision,
            record = record,
            messageId = target,
        )
    }

    /**
     * A committed `BIND_PENDING` and everything the bind needs to re-send it.
     *
     * Returned as one value so the caller cannot take the conversation to commit and the identity
     * to bind from two different computations — which is the shape of bug this whole layer is
     * written against.
     *
     * [messageId] is the message the pending record was written to, and it is carried for the same
     * reason: the settlement that follows the bind **supersedes that record in its own slot**, and
     * the slot cannot be re-derived afterwards. [terminalTarget] answers "the last message with no
     * record of this branch", and once the pending record is on it the answer is a different
     * message — so a caller that recomputed it would write the settled state somewhere the pending
     * one never was, leaving the path showing two records where there should be one.
     */
    data class DeferredPending(
        val conversation: Conversation,
        val branchId: String,
        val revision: Long,
        val record: ClaudePSessionContinuation,
        val messageId: String,
    )

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
