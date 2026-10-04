package me.rerere.ai.provider.claudep

import kotlinx.serialization.Serializable

/**
 * What has to be true about a Claude P branch before the next generation on it may be dispatched.
 *
 * ## Why this is stored on a message rather than in a side table
 *
 * The authority for "which branch is the user looking at" is the conversation graph, and the
 * authority for "did that branch's session actually get bound" has to live or die with the very
 * same rows. A separate table keyed by branch would be a second source of truth that a graph
 * rollback, a fork or a deletion would leave behind — and the stale half is exactly the one that
 * would answer "yes, resume it".
 *
 * So the state travels **on the message that recorded it**, inside the same JSON column that
 * already holds every other fact about that message, and every write of it happens inside the
 * same Room transaction that commits the graph ([ClaudePSessionContinuationState] says which
 * writes those are). There is no migration: the column already exists and an older build simply
 * ignores the field.
 *
 * ## What is deliberately not stored
 *
 * No Claude session id, no config hash, no credential, no prompt, no system instruction. The
 * session id belongs to the Worker, which is the only party that may name it; a device that
 * stored one would be a device able to *assert* a continuation it cannot prove, and the whole
 * point of `mode: "auto"` is that Android cannot resolve new-versus-resume on its own. What is
 * stored is only the app's own bookkeeping: which assistant, which branch, how far along, and —
 * while a bind is outstanding — the Server's generation id needed to replay that exact bind.
 *
 * The absence is structural rather than a rule: there is no field for any of them to travel in.
 *
 * ## Defaults are impossible sentinels, never values
 *
 * Every field has a default so that a damaged or partially-written record cannot fail the decode
 * of a whole conversation — the alternative is that one bad byte costs the user their entire
 * message history. Those defaults are `""`, `0` and `null`, which no writer ever produces, and
 * [ClaudePSessionContinuationTransitions.fold] refuses each of them with a distinct reason. A
 * record is therefore allowed to *decode* into an impossible shape and never allowed to *resolve*
 * into one.
 */
@Serializable
data class ClaudePSessionContinuation(
    /**
     * The assistant this branch belongs to. Part of the exact `(assistantId, branchId)` pair a
     * resolver matches on; never inherited, never defaulted from the conversation.
     */
    val assistantId: String = "",
    /**
     * The branch identity this record describes: the 64 lowercase hex digest
     * [ClaudePSessionBranchId] computes over the selected variants. Stored rather than
     * recomputed, because the record describes the branch *at the moment it was written* — a
     * later fork or variant switch does not change what happened here, it only stops this record
     * from matching.
     */
    val branchId: String = "",
    /**
     * How far along this branch is. Strictly increasing along the selected path, which is what
     * lets a resolver reject a path whose records have been reordered, and what makes "two
     * records claim the same step and disagree" a detectable contradiction rather than a
     * coin toss.
     */
    val revision: Long = 0L,
    /**
     * The stored state. Nullable so a record that lost this field is refused rather than
     * silently read as whichever state happened to be the default.
     */
    val state: ClaudePSessionContinuationState? = null,
    /**
     * The Server's generation id, present **only** while a bind obligation is outstanding, and
     * only ever together with the branch and assistant above — the three of them are what a
     * replay re-sends, and a replay that changed any one of them would be binding a different
     * session than the one the user is looking at.
     */
    val generationId: String? = null,
) {
    /**
     * Redacted: a log line may correlate two records, never identify a session.
     *
     * There is no session id here to leak, but the assistant and branch identities are still
     * the app's own, and [redactedRef] distinguishes two values without disclosing either.
     */
    override fun toString(): String =
        "ClaudePSessionContinuation(" +
            "assistantId=${assistantId.redactedRef()}, " +
            "branchId=${branchId.redactedRef()}, " +
            "revision=$revision, state=$state, " +
            "generationId=${generationId.redactedRef()})"
}

/**
 * The stored states. Every one of them is durable — a restart reads the same value back.
 *
 * [START_IN_FLIGHT] — a generation on this branch was admitted and has not reached a terminal we
 * can prove. It is written *before* dispatch, which is what makes "the model may be running" a
 * fact on disk rather than a fact in a coroutine.

 * [BIND_PENDING] — a `deferred` generation succeeded and its new branch variant is committed,
 * but the Server has not yet confirmed `session.bind`. The branch is **not** resumable in this
 * state: the Worker holds an uncommitted candidate, and resuming it would be claiming a binding
 * that no bind ever wrote.

 * [BOUND] — the Server confirmed the binding. The only state, besides `UNINITIALIZED`, from
 * which a new immediate generation may start.

 * [FAILED_CLOSED] — the branch failed, was cancelled, or was rolled back. Terminal: nothing
 * recovers from it automatically, and a replay is not applicable because there is nothing left
 * to bind.

 * [INTERRUPTED] — the connection dropped, or the process died, at a point where the outcome
 * cannot be proven. Carries a [ClaudePSessionContinuation.generationId] exactly when it
 * descends from a pending bind, because only then is there a bind that may be replayed with the
 * same identity; when it descends from an in-flight start there is nothing to replay and the
 * branch stays closed.

 * There is deliberately no `UNINITIALIZED` member: "no record matched on the selected path" is a
 * *result of resolving*, not something a writer ever stores, and making it storable would create
 * a second spelling of `absent` that a reader could produce by accident.
 */
@Serializable
enum class ClaudePSessionContinuationState {
    START_IN_FLIGHT,
    BIND_PENDING,
    BOUND,
    FAILED_CLOSED,
    INTERRUPTED,
}

/**
 * The `binding_intent` of `02-wire-protocol-v1.md` §5.2, as a closed vocabulary.
 *
 * The wire strings are the protocol's, and are carried here so that no call site spells them.
 */
enum class ClaudePSessionBindingIntent(val wireValue: String) {
    /** The branch is already committed; `remote_branch_id` must be present. */
    IMMEDIATE("immediate"),

    /** This generation will create the branch; `remote_branch_id` must be absent. */
    DEFERRED("deferred"),
}

/**
 * The fold over a branch's records, and the closed transition table it uses.
 *
 * ## What the table is for
 *
 * A branch's selected path can carry more than one record: turn one writes a start and a
 * terminal, turn two writes another pair, and a deferred bind writes a pending state that is
 * later superseded. Taken together they are the branch's history, and the history has to *chain*.
 * A pair of records that could not have happened in that order is evidence that the graph was not
 * written by this state machine — a rollback that half-applied, a restore from an older backup, a
 * fork that inherited records it should not have — and the only safe answer to that is to refuse
 * the branch.
 *
 * ## What it is not for
 *
 * It is **not** last-write-wins. Picking the last record and ignoring an impossible predecessor
 * is precisely the failure this exists to prevent: the permissive answer is always "resume it".
 *
 * ## Why the first record is not constrained
 *
 * `null -> any` is legal for every state, and that is a decision rather than an oversight. A lone
 * record is the branch's *current* state, not a claim about its history, and there is nothing for
 * it to contradict. Each of the five is genuinely reachable first:
 *
 * - `START_IN_FLIGHT` — written on the admitted user message before dispatch.
 * - `BIND_PENDING` — written on the new variant a `deferred` generation committed (§5.2: no
 *   admission transaction runs for that path, so no start is ever stored for it).
 * - `BOUND` — the same slot as above, once `session.bind` confirmed.
 * - `FAILED_CLOSED` / `INTERRUPTED` — the same slot as a start, when the generation failed or the
 *   connection dropped and the graph was rolled back to a point where that slot is the only
 *   record left.
 *
 * Constraining it further would not add safety — the shape checks in [fold] already refuse a
 * record that no writer produces — and it would reject real histories.
 *
 * The table is written as an exhaustive `when` on purpose, exactly as
 * [me.rerere.ai.provider.providers.ClaudePProvider]'s input rejection is: adding a state becomes
 * a compile error here, so it has to be classified deliberately rather than inheriting whichever
 * branch a catch-all would have given it.
 */
object ClaudePSessionContinuationTransitions {

    /** Why a record cannot be used at all. A closed set. */
    enum class Refusal {
        /** The record carries no state — a damaged or partial write. */
        MISSING_STATE,
        /** The assistant identity is absent, so the record is not bound to anything. */
        BLANK_ASSISTANT_ID,
        /** The branch identity is not the 64 lowercase hex digest a branch id always is. */
        MALFORMED_BRANCH_ID,
        /** Revisions are one-based and strictly increasing; zero or below is not a revision. */
        NON_POSITIVE_REVISION,
        /** A generation id was present but empty, which is neither a value nor an absence. */
        BLANK_GENERATION_ID,
        /** A state that cannot carry a bind obligation was given a generation id. */
        GENERATION_ID_NOT_PERMITTED,
        /** A pending bind with no generation id could never be replayed. */
        GENERATION_ID_REQUIRED,
        /**
         * A node on the selected path has no message at its `selectIndex`, so whatever that node
         * recorded cannot be read.
         *
         * Skipping it would be the dangerous reading: the node is exactly where a record would
         * be, and dropping it would report a branch whose history is missing a step as though the
         * step had never happened.
         */
        SELECTED_MESSAGE_UNREADABLE,
    }

    /** Why a sequence of records cannot describe one branch. A closed set. */
    enum class Conflict {
        /** A record claims an earlier step than one already folded. */
        REVISION_REGRESSION,
        /**
         * Two *consecutive* records skip a step between them.
         *
         * Every writer that produces this state advances the revision by exactly one per record —
         * a start then a terminal, a pending then a bound, an interrupted then a pending then a
         * bound — so a step with nothing on it means a record was lost. That is evidence the graph
         * was not written by this state machine (a half-applied rollback, a restore from a partial
         * backup), and the fail-closed reading of it is to refuse the branch rather than to resume
         * across the hole.
         *
         * The **first** record is deliberately exempt and stays so: a rollback can legitimately
         * leave a lone record at a revision above one, which is why `null -> any` is legal.
         */
        REVISION_GAP,
        /** Two records claim the same step and do not agree about it. */
        REVISION_DISAGREEMENT,
        /** The pair could not have happened in this order. */
        ILLEGAL_TRANSITION,
        /**
         * A bind obligation changed identity across the fold. A replay must re-send the same
         * `(generationId, branchId)` pair it persisted, so any change here means the binding
         * that would be written is not the one that was promised.
         */
        GENERATION_ID_INCONSISTENT,
    }

    /** The 64-lowercase-hex shape every branch identity has. */
    private val BRANCH_ID = Regex("^[0-9a-f]{64}$")

    /**
     * Folds [records] — the branch's records, **in selected-path order** — into one resolution.
     *
     * The caller supplies the order; this function checks that the order is the one the revisions
     * describe rather than sorting them into agreement. A path whose records disagree about their
     * own sequence is a defect in the graph, and reordering it away would hide that.
     */
    fun fold(records: List<ClaudePSessionContinuation>): ClaudePSessionContinuationResolution {
        if (records.isEmpty()) return ClaudePSessionContinuationResolution.UNINITIALIZED

        var state: ClaudePSessionContinuationState? = null
        var generationId: String? = null
        var revision = 0L
        var assistantId = ""
        var branchId = ""

        for (record in records) {
            refuse(record)?.let {
                return ClaudePSessionContinuationResolution.Refused(it)
            }
            val next = requireNotNull(record.state)

            when {
                record.revision < revision ->
                    return conflict(Conflict.REVISION_REGRESSION)

                record.revision == revision -> {
                    // The same step, observed twice. Identical observations are the same record
                    // persisted twice and fold to themselves; anything else is two records
                    // claiming one step, which no writer can produce and no reader may resolve.
                    if (state == null || next != state || record.generationId != generationId) {
                        return conflict(Conflict.REVISION_DISAGREEMENT)
                    }
                    continue
                }

                // A step with nothing on it. Only checked once a record has been folded, because a
                // lone record at a revision above one is a legal rollback outcome — see
                // [Conflict.REVISION_GAP].
                revision > 0L && record.revision != revision + 1L ->
                    return conflict(Conflict.REVISION_GAP)
            }

            val folded = transition(state, next) ?: return conflict(Conflict.ILLEGAL_TRANSITION)
            if (!generationIdContinues(state, next, generationId, record.generationId)) {
                return conflict(Conflict.GENERATION_ID_INCONSISTENT)
            }

            state = folded
            generationId = record.generationId
            revision = record.revision
            assistantId = record.assistantId
            branchId = record.branchId
        }

        return ClaudePSessionContinuationResolution.Resolved(
            assistantId = assistantId,
            branchId = branchId,
            state = requireNotNull(state),
            revision = revision,
            generationId = generationId,
        )
    }

    /**
     * Whether [next] may follow [current], or `null` when it may not.
     *
     * The legal steps, and the flow each one describes:
     *
     * | from | to | the flow |
     * |---|---|---|
     * | absent | [START_IN_FLIGHT] | an immediate generation admitted on a branch with no history |
     * | absent | [BIND_PENDING] | a `deferred` generation whose new variant was just committed |
     * | absent | [BOUND] | that same variant, once the bind confirmed |
     * | absent | [FAILED_CLOSED] / [INTERRUPTED] | a start that failed or dropped, leaving no other record |
     * | [START_IN_FLIGHT] | [BOUND] | the turn completed and the binding was proven |
     * | [START_IN_FLIGHT] | [BIND_PENDING] | a `deferred` generation succeeded on a branch already started |
     * | [START_IN_FLIGHT] | [FAILED_CLOSED] | it failed, was cancelled, or rolled back |
     * | [START_IN_FLIGHT] | [INTERRUPTED] | it dropped, or the process died, without a provable outcome |
     * | [BIND_PENDING] | [BOUND] | `session.bind` answered `bound` or `already_bound` |
     * | [BIND_PENDING] | [FAILED_CLOSED] | it answered `conflict`/`candidate_unavailable`/`refused`, or was malformed |
     * | [BIND_PENDING] | [INTERRUPTED] | the connection dropped before any answer arrived |
     * | [BOUND] | [START_IN_FLIGHT] | the next user turn on the same branch |
     * | [BOUND] | [FAILED_CLOSED] / [INTERRUPTED] | that turn ended with no message to write to, superseding its own barrier |
     * | [INTERRUPTED] | [BIND_PENDING] | a bind replay, carrying the same generation id |
     *
     * Everything else is illegal. Two of those refusals carry most of the design:
     *
     * - **[FAILED_CLOSED] is absorbing.** Nothing recovers from it automatically, so a record
     *   claiming the branch moved on from it did not come from this state machine. A user who
     *   wants to continue past a failure does it by creating a new branch — a regenerate or a
     *   fork — and the failed record stays on the variant that is no longer selected.
     * - **[INTERRUPTED] leads only back to a pending bind.** It is not a synonym for "absent":
     *   reading it as absent is how a dropped connection would quietly buy a *second* Claude
     *   session for a branch that may already have one.
     */
    private fun transition(
        current: ClaudePSessionContinuationState?,
        next: ClaudePSessionContinuationState,
    ): ClaudePSessionContinuationState? = when (current) {
        null -> when (next) {
            ClaudePSessionContinuationState.START_IN_FLIGHT,
            ClaudePSessionContinuationState.BIND_PENDING,
            ClaudePSessionContinuationState.BOUND,
            ClaudePSessionContinuationState.FAILED_CLOSED,
            ClaudePSessionContinuationState.INTERRUPTED,
                -> next
        }

        ClaudePSessionContinuationState.START_IN_FLIGHT -> when (next) {
            ClaudePSessionContinuationState.BOUND,
            ClaudePSessionContinuationState.BIND_PENDING,
            ClaudePSessionContinuationState.FAILED_CLOSED,
            ClaudePSessionContinuationState.INTERRUPTED,
                -> next

            ClaudePSessionContinuationState.START_IN_FLIGHT -> null
        }

        ClaudePSessionContinuationState.BIND_PENDING -> when (next) {
            ClaudePSessionContinuationState.BOUND,
            ClaudePSessionContinuationState.FAILED_CLOSED,
            ClaudePSessionContinuationState.INTERRUPTED,
                -> next

            ClaudePSessionContinuationState.START_IN_FLIGHT,
            ClaudePSessionContinuationState.BIND_PENDING,
                -> null
        }

        ClaudePSessionContinuationState.BOUND -> when (next) {
            ClaudePSessionContinuationState.START_IN_FLIGHT -> next

            // A later turn on a bound branch can end without producing anything: the generation
            // failed, the user cancelled, or the connection dropped before an assistant message
            // existed. The barrier for that turn is then the last record on the path with nowhere
            // after it to write a terminal, so the terminal **supersedes it in the same slot** —
            // and the observable path is `BOUND(n) -> FAILED_CLOSED(n+1)`, contiguous because the
            // barrier's revision was already n+1.
            //
            // These are the only two additions, and the line they do not cross matters: a bound
            // branch is never returned to `BOUND` by an unproven outcome. Treating "we could not
            // find out" as "it is still bound" is the permissive reading this whole layer exists to
            // refuse — it would resume a branch whose last turn may have left the Server holding
            // something else.
            ClaudePSessionContinuationState.FAILED_CLOSED,
            ClaudePSessionContinuationState.INTERRUPTED,
                -> next

            ClaudePSessionContinuationState.BOUND,
            ClaudePSessionContinuationState.BIND_PENDING,
                -> null
        }

        ClaudePSessionContinuationState.INTERRUPTED -> when (next) {
            ClaudePSessionContinuationState.BIND_PENDING -> next

            ClaudePSessionContinuationState.START_IN_FLIGHT,
            ClaudePSessionContinuationState.BOUND,
            ClaudePSessionContinuationState.FAILED_CLOSED,
            ClaudePSessionContinuationState.INTERRUPTED,
                -> null
        }

        ClaudePSessionContinuationState.FAILED_CLOSED -> null
    }

    /**
     * Whether the bind obligation survived the step unchanged.
     *
     * Three rules, and each one is a replay safety property rather than a tidiness rule:
     *
     * 1. A pending bind that is interrupted keeps its generation id. That id is the whole of what
     *    a replay re-sends; an interrupted record that lost it has nothing to replay, and one
     *    that replaced it would replay a different generation's bind under this branch's name.
     * 2. An interrupted bind that is retried reuses its generation id — §6.1's "the only recovery
     *    is re-sending the same bind", which a new id would not be.
     * 3. An interrupted *start* carries no generation id at all. A start has no candidate to
     *    bind, so an id here could only be mistaken for something replayable — and the only
     *    correct answer to an interrupted start is to stay closed.
     */
    private fun generationIdContinues(
        current: ClaudePSessionContinuationState?,
        next: ClaudePSessionContinuationState,
        currentGenerationId: String?,
        nextGenerationId: String?,
    ): Boolean = when {
        current == ClaudePSessionContinuationState.BIND_PENDING &&
            next == ClaudePSessionContinuationState.INTERRUPTED ->
            nextGenerationId != null && nextGenerationId == currentGenerationId

        current == ClaudePSessionContinuationState.INTERRUPTED &&
            next == ClaudePSessionContinuationState.BIND_PENDING ->
            nextGenerationId != null && nextGenerationId == currentGenerationId

        current == ClaudePSessionContinuationState.START_IN_FLIGHT &&
            next == ClaudePSessionContinuationState.INTERRUPTED ->
            nextGenerationId == null

        else -> true
    }

    /**
     * The shape checks, or `null` when the record is usable.
     *
     * These are what make the defaults on [ClaudePSessionContinuation] sentinels instead of
     * values: a record that lost its assistant, its branch, its revision or its state is refused
     * here, so no caller ever reads one of those defaults as a fact.
     */
    private fun refuse(record: ClaudePSessionContinuation): Refusal? {
        val state = record.state ?: return Refusal.MISSING_STATE
        if (record.assistantId.isBlank()) return Refusal.BLANK_ASSISTANT_ID
        if (!BRANCH_ID.matches(record.branchId)) return Refusal.MALFORMED_BRANCH_ID
        if (record.revision <= 0L) return Refusal.NON_POSITIVE_REVISION

        val generationId = record.generationId
        if (generationId != null && generationId.isBlank()) return Refusal.BLANK_GENERATION_ID

        return when (state) {
            // A pending bind with no generation id is a bind that can never be replayed, so it
            // is not a state to resume into — it is a broken record.
            ClaudePSessionContinuationState.BIND_PENDING ->
                if (generationId == null) Refusal.GENERATION_ID_REQUIRED else null

            // An interrupted record may carry one (a dropped bind) or not (a dropped start).
            ClaudePSessionContinuationState.INTERRUPTED -> null

            // A settled binding, an in-flight start and a closed branch have no bind outstanding,
            // so an id on one of them is a claim nothing can honour.
            ClaudePSessionContinuationState.START_IN_FLIGHT,
            ClaudePSessionContinuationState.BOUND,
            ClaudePSessionContinuationState.FAILED_CLOSED,
                -> if (generationId == null) null else Refusal.GENERATION_ID_NOT_PERMITTED
        }
    }

    private fun conflict(reason: Conflict) = ClaudePSessionContinuationResolution.Conflicted(reason)
}

/**
 * The answer a resolver gives for one `(assistantId, branchId)` on the selected path.
 *
 * The two failure kinds are kept apart because they are different defects and a caller reads
 * them differently: [Refused] says a record could not be used at all, [Conflicted] says the
 * records could not all be true at once. Both are fail-closed, and collapsing one into the other
 * would put a wrong reason in the one log line that exists to say which it was.
 */
sealed interface ClaudePSessionContinuationResolution {

    /**
     * Whether a new immediate generation may be dispatched on this branch.
     *
     * Only a branch with no continuation record at all, or one whose binding is already proven,
     * may start. Every other state is either "the model may be running right now"
     * ([ClaudePSessionContinuationState.START_IN_FLIGHT]) or "the outcome is not proven yet"
     * ([ClaudePSessionContinuationState.BIND_PENDING],
     * [ClaudePSessionContinuationState.INTERRUPTED]) or "it is over"
     * ([ClaudePSessionContinuationState.FAILED_CLOSED]).
     *
     * The rule lives here rather than at each call site because there is exactly one correct
     * answer, and a per-site copy is how one of them would come to allow
     * [ClaudePSessionContinuationState.INTERRUPTED] — the permissive mistake that reads a
     * dropped connection as a free branch.
     *
     * A refusal or a conflict answers `false`: a branch whose state cannot be established is
     * not a branch anything may be dispatched on.
     */
    val allowsNewGeneration: Boolean

    /**
     * No record on the selected path matches this `(assistantId, branchId)`.
     *
     * This is the branch's first generation, and it is **not** a stored state: no writer writes
     * "no record", and no reader may read a missing record as a settled one. It is also the
     * state a record does *not* decay into — a record that stops matching because the user
     * switched variants describes a different branch, and this branch is simply new.
     */
    data object UNINITIALIZED : ClaudePSessionContinuationResolution {
        override val allowsNewGeneration: Boolean = true
    }

    /** One settled answer for the branch. */
    data class Resolved(
        val assistantId: String,
        val branchId: String,
        val state: ClaudePSessionContinuationState,
        val revision: Long,
        /**
         * The outstanding bind's generation id, present exactly when the state carries a bind
         * obligation that a replay must re-send. `null` for every other state, including
         * [ClaudePSessionContinuationState.INTERRUPTED] that descends from an in-flight start —
         * which is the signal that such a branch has nothing to replay and stays closed.
         */
        val generationId: String?,
    ) : ClaudePSessionContinuationResolution {
        override val allowsNewGeneration: Boolean
            get() = state == ClaudePSessionContinuationState.BOUND
    }

    /** A record that cannot be used. */
    data class Refused(val reason: ClaudePSessionContinuationTransitions.Refusal) :
        ClaudePSessionContinuationResolution {
        override val allowsNewGeneration: Boolean = false
    }

    /** Records that cannot all be true at once. */
    data class Conflicted(val reason: ClaudePSessionContinuationTransitions.Conflict) :
        ClaudePSessionContinuationResolution {
        override val allowsNewGeneration: Boolean = false
    }
}

