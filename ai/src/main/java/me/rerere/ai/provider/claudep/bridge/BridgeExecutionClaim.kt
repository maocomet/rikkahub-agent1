package me.rerere.ai.provider.claudep.bridge

/**
 * The proof a claim carries that a call needing a human decision was given one.
 *
 * ## Why the approval travels as a value and not as a boolean
 *
 * "This call needs no approval" and "a human approved this call" are different facts, and the
 * second one has an identity: the approval the app committed, and the execution record beside it.
 * Collapsing them into one `true` would let a caller that never obtained a decision assert the
 * same thing as a caller that did — which is the whole failure this type exists to make
 * inexpressible.
 *
 * The two ids are the app's own, produced by the single authoritative derivation site inside the
 * authority transaction (`SecondUserApprovalLifecycle`). They are **not** re-derived here and are
 * not part of any key: they are the receipt that the claim was earned, and the ledger records
 * only that some approval was presented.
 */
sealed interface BridgeExecutionApproval {
    /**
     * The call does not need a decision.
     *
     * Not a default and not a fallback: the app asserts this only after re-assessing the call's
     * own tool against the **real** arguments, which is the same question every other provider
     * asks the app's approval policy.
     */
    data object NotRequired : BridgeExecutionApproval

    /** A human approved this exact call. The ids are the authority's, carried back unwidened. */
    data class Granted(val approvalId: String, val executionId: String) : BridgeExecutionApproval
}

/**
 * Everything one execution claim is decided from.
 *
 * Every field is an obligation the claim checks against the ledger's own record rather than
 * something the ledger takes on trust. The point is not that these values are secret — they are
 * not — but that a caller assembling one by hand, or carrying one over from a different call,
 * must be refused rather than executed.
 *
 * @property serverGenerationId the **Server's** generation id, the one the frame arrived under.
 *   It is not the Android run id; [runId] is, and keeping the two apart is the reason a call can
 *   never be executed under a generation that is not the one that asked for it.
 * @property runId the Android run serving that generation.
 * @property toolName the name the **runtime** knows — the app's own tool name, not the frozen one.
 * @property argsDigest the contract's digest of the arguments the Server actually sent. Never a
 *   digest recomputed from a conversation part: `RuntimeSecretRedactor` rewrites the part before
 *   the authority sees it, so a digest taken from there would disagree with the ledger for exactly
 *   the calls that carry a secret.
 * @property binding the full invocation binding, compared **by equality** against the canonical
 *   one the ledger stored. A partial comparison is how a changed conversation, branch, device,
 *   catalog or schema would be waved through.
 */
data class BridgeExecutionClaim(
    val serverGenerationId: String,
    val runId: String,
    val toolCallId: String,
    val toolName: String,
    val argsDigest: String,
    val binding: InvocationBinding,
    val approval: BridgeExecutionApproval,
)

/**
 * Why a claim was refused. A closed set with stable local spellings.
 *
 * These are **local**: they are recorded for diagnostics and never written to a frame. The
 * contract's own vocabulary has no word for "Android declined to start a call", and inventing one
 * would be claiming a value the Server does not hold. What goes on the wire for every one of
 * these is `failed` — a claim that was refused is a call that did not run, and `failed` is the
 * only honest reportable state that claims nothing.
 *
 * The mapping is deliberately not "one refusal per wire state": `conflict`, `not_found` and
 * `interrupted` are verdicts about a ledger, and the Server holds its own.
 */
enum class BridgeClaimRefusal(val localReason: String) {
    /**
     * The claim disagrees with the canonical invocation the ledger stored — a different binding,
     * tool name, argument digest or Server generation. Nothing runs, and a call whose content
     * changed under a repeated id never did.
     */
    CONFLICT("claim_conflict"),

    /**
     * The ledger holds no record of this call.
     *
     * After a process restart this is the only possible answer, and it is the answer: what a tool
     * did before the process died cannot be proven, so it must not be re-run to find out. The
     * local reason `not_found` is **not** the wire state `not_found` — that one is the Server's
     * verdict about its own ledger, and Android may not send it.
     */
    NOT_FOUND("claim_not_found"),

    /** A cancel reached this call, or the generation that owns it is ending. */
    CANCELLED("claim_cancelled"),

    /** The call's own deadline had already elapsed when the claim was made. */
    TIMED_OUT("claim_timed_out"),

    /**
     * The record has already reached a terminal, or cannot be proven canonical — a settled call,
     * or one whose invocation this process no longer holds.
     */
    INTERRUPTED("claim_interrupted"),

    /**
     * Somebody already holds this call's execution right.
     *
     * The duplicate caller's answer. A claim is granted **once** per call, so a second caller
     * cannot obtain a second execution — which is what makes "execute exactly once" a property of
     * the ledger rather than a property of every caller's self-restraint.
     */
    ALREADY_CLAIMED("claim_already_claimed"),

    /** The claim asserted an approval that is absent, blank or otherwise not a decision. */
    APPROVAL_REQUIRED("claim_approval_required"),

    /** The claim is malformed in a way no other value describes — a blank run id, for instance. */
    REFUSED("claim_refused"),
}

/** What claiming one call produced. A closed set, so an unhandled outcome is a compile error. */
sealed interface BridgeExecutionClaimResult {
    /**
     * The execution right, and the **canonical** invocation it is a right to run.
     *
     * The runtime executes this object and no other. It is the ledger's stored instance, not a
     * value reassembled from the request, from a UI part, from Room, from a conversation message
     * or from redacted input — every one of which is a *copy* of the call, and a copy is a second
     * opinion about what was asked for.
     */
    data class Claimed(val invocation: BridgeInvocation) : BridgeExecutionClaimResult

    /** No execution right, and nothing ran, runs or will run for this call. */
    data class Refused(val reason: BridgeClaimRefusal) : BridgeExecutionClaimResult
}

/**
 * The one thing an app needs in order to run a bridged call: the right to, and the exact call.
 *
 * ## Why this is handed over rather than looked up
 *
 * The ledger belongs to **one generation**, and it is created and destroyed with it. An app that
 * could reach a ledger by naming a conversation, an assistant or "the call I am running now" could
 * answer one generation's call with another's context — and every such answer looks correct in
 * isolation. So the app is given a claimant bound to the generation whose frame it is answering,
 * and there is no method anywhere that takes anything else.
 *
 * ## Why there are two questions
 *
 * [admissible] is asked **before** anything user-visible happens — before a pending card is
 * published — because raising a card for a call that has already been cancelled is asking the user
 * to decide something that cannot happen. It is a read: it grants nothing and consumes nothing.
 * [claim] is asked **after** the decision, and it is the only thing that grants execution.
 *
 * Neither may be skipped: [admissible] returning `null` says "you may still ask the user", not
 * "you may run this".
 */
interface BridgeExecutionClaimant {

    /**
     * Whether this call could still be claimed, without claiming it.
     *
     * `null` means nothing is known to prevent it. A non-null refusal is binding: the call must not
     * be published, waited on or executed.
     */
    fun admissible(request: BridgeExecutionClaim): BridgeClaimRefusal?

    /**
     * Grants the execution right, exactly once, or refuses.
     *
     * The claim is made **after** the approval has been decided and **before** the runtime is
     * asked to run anything. An invocation held from before the approval is never the execution
     * authority: while the wait was open the call could have been cancelled, timed out,
     * disconnected, settled by a terminal, closed with its generation, or duplicated — and none of
     * those is visible in the object the caller is holding.
     */
    fun claim(request: BridgeExecutionClaim): BridgeExecutionClaimResult

    companion object {
        /**
         * The claimant that grants nothing.
         *
         * Every question is refused, which is the correct answer for a bridge with no ledger behind
         * it: a call it cannot look up is a call it must not run.
         */
        val NONE: BridgeExecutionClaimant = object : BridgeExecutionClaimant {
            override fun admissible(request: BridgeExecutionClaim): BridgeClaimRefusal =
                BridgeClaimRefusal.NOT_FOUND

            override fun claim(request: BridgeExecutionClaim): BridgeExecutionClaimResult =
                BridgeExecutionClaimResult.Refused(BridgeClaimRefusal.NOT_FOUND)
        }
    }
}
