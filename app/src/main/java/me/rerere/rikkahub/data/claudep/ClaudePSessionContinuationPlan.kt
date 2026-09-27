package me.rerere.rikkahub.data.claudep

/**
 * What the dispatch path must send, and what it must write, for one command.
 *
 * ## Why this is computed before the request fingerprint
 *
 * `02-wire-protocol-v1.md` §7 requires every field a request carries to enter its fingerprint,
 * and `binding_intent` and `remote_branch_id` are such fields. If the plan were computed after
 * the fingerprint, the two could disagree — a request fingerprinted as immediate and sent as
 * deferred would be a request whose idempotency key does not describe it, and the Server would
 * deduplicate two genuinely different requests. So the plan is an **input** to the request, and
 * the request is built from it, never reconciled with it afterwards.
 *
 * ## Why there is no "unknown, use the old request shape" member
 *
 * Every way this can fail is a [Refused]. The temptation is a member meaning "we could not work
 * out the continuation, so send what we used to send" — `mode: new`, the pre-M3 shape. That is
 * the one answer that must not exist: it would take a branch that is mid-generation, or holding
 * an unconfirmed bind, and silently start a *second* Claude session for it, with the user unable
 * to tell. A refusal costs a visible failure. The fallback costs a silent duplicate session, and
 * only the first of those is recoverable.
 */
sealed interface ClaudePSessionContinuationPlan {

    /** The assistant every member names, so a caller can log or refuse without re-deriving it. */
    val assistantId: String

    /**
     * A generation on a branch that is already committed: `mode: "auto"`, `binding_intent:
     * "immediate"`, and this [branchId] as `remote_branch_id`.
     *
     * [revision] is the revision the `START_IN_FLIGHT` record written in the admission
     * transaction must carry. It is computed here, from the graph as it stands *before* that
     * write, so that the barrier and the request agree about which generation on this branch is
     * being described.
     */
    data class Immediate(
        override val assistantId: String,
        val branchId: String,
        val revision: Long,
    ) : ClaudePSessionContinuationPlan

    /**
     * A generation that will create its branch: `mode: "auto"`, `binding_intent: "deferred"`, and
     * **no** `remote_branch_id` at all.
     *
     * The absence is the point. §5.3 encodes "this branch does not exist yet" as the field's own
     * presence flag, and forbids `""` as a stand-in — an empty string is a *present but empty*
     * branch identity, which is not a legal value, and the two would produce different
     * fingerprints for the same request shape.
     *
     * There is no branch id here because there cannot be one: the branch is the variant this
     * generation is about to create, and it is knowable only once that variant is committed.
     */
    data class Deferred(
        override val assistantId: String,
    ) : ClaudePSessionContinuationPlan

    /**
     * The command starts no model generation, so it has no continuation to plan.
     *
     * A separate member rather than a refusal, because it is a correct answer: an approval
     * decision, a steering update or a queue edit genuinely dispatches nothing. Reporting it as
     * a refusal would fill a log with failures for commands that behaved exactly as intended.
     *
     * It is still not a licence to dispatch: the dispatch path treats this as "run nothing", and
     * a command of this member reaching a model-dispatch branch is a wiring defect that must
     * fail closed.
     */
    data class NotModelGeneration(
        override val assistantId: String,
    ) : ClaudePSessionContinuationPlan

    /** No request may be sent for this command. */
    data class Refused(
        override val assistantId: String,
        val reason: Reason,
    ) : ClaudePSessionContinuationPlan

    /** Why a plan could not be produced. A closed set. */
    enum class Reason {
        /**
         * The committed graph is not an ordered list of nodes each selecting one of its own
         * variants, so it has no branch identity to continue.
         */
        BRANCH_NOT_DESCRIBABLE,

        /**
         * The graph's selection vector is well-formed but is not a legal branch identity — a
         * duplicated node or message, or a blank one.
         */
        BRANCH_MALFORMED,

        /**
         * The branch is in a state that does not permit a new generation: a generation may
         * already be running on it, a bind may be outstanding, or it may be closed.
         */
        CONTINUATION_BLOCKED,

        /** A continuation record on the selected path could not be used. */
        CONTINUATION_REFUSED,

        /** The continuation records on the selected path cannot all be true at once. */
        CONTINUATION_CONFLICTED,
    }
}
