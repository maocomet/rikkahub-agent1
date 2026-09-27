package me.rerere.rikkahub.data.claudep

import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.service.chat.ChatCommand
import me.rerere.rikkahub.service.chat.EmergencyCommand

/**
 * Whether a command may dispatch a Claude P model generation, and the named reason when it may not.
 *
 * ## The invariant this enforces
 *
 * **Every Claude P model dispatch carries the decision its admission produced.** The decision is
 * what names the branch the Server must resume, and it is also what the run will settle its barrier
 * with. A dispatch without one therefore does two wrong things at once: it sends `mode: "new"` for a
 * branch that may already hold a session — starting a second one, silently — and it leaves whatever
 * barrier exists unsettled.
 *
 * ## Why this is a value and not an assertion
 *
 * The check has to hold over *every* path that reaches a model, including paths added later. The
 * tempting shape is a `require` at the dispatch site, and it is the wrong one: the failure would be
 * a crash whose message is a string nobody enumerated, on a path a user reached through ordinary
 * use. So the answer is a closed set of **named refusals** the caller returns upward as its own
 * rejected-command result — dispatch is zero, the failure is identifiable, and the code is a value
 * a test can assert against.
 *
 * ## The one shape that legitimately cannot be admitted, and why it is named here
 *
 * `InterruptCommand` and `InterruptRegenerateCommand` are `EmergencyCommand`s: they exist to
 * preempt a running command, so they deliberately bypass `persistDurable` — and therefore bypass
 * the admission transaction that writes the barrier. They still start a model generation. So they
 * are exactly the case this file exists to name rather than to let through.
 *
 * There is no second planner and no second state machine here. Which commands dispatch a model is
 * asked of [ClaudePSessionBranchPlanner.classify] — the one place that question already has an
 * answer — and what a decision permits is asked of the decision itself. This function only decides
 * whether the pair is complete.
 *
 * ## Why an emergency command *with* a decision is allowed
 *
 * If a later batch routes an emergency command through the same admission seam, it will arrive here
 * holding a decision, and refusing it then would be refusing a dispatch that is fully accounted
 * for. The refusal is therefore about the **missing** decision, not about the command's type: the
 * type is only how the missing case is recognised early enough to name it precisely.
 */
object ClaudePSessionContinuationDispatch {

    /**
     * Why a Claude P model dispatch was refused. A closed set.
     *
     * Every member carries a stable `code`, because the caller returns it upward as the reason a
     * command was rejected — a human reads it in the UI, and a test asserts against it.
     */
    enum class Refusal(val code: String) {
        /**
         * An `EmergencyCommand` would dispatch a model without ever reaching the admission
         * transaction, so no barrier was written and no decision exists.
         *
         * **This shape is unsupported by M3-B.** It is refused rather than allowed through, and the
         * alternative — dispatching it as `mode: "new"` — is precisely the silent second session the
         * continuation layer exists to prevent.
         */
        EMERGENCY_NOT_ADMITTED("claude_p_continuation_emergency_not_admitted"),

        /**
         * A command that dispatches a model has no decision recorded for it.
         *
         * Distinct from [EMERGENCY_NOT_ADMITTED] because it sends a reader somewhere different: an
         * ordinary command reaches admission unconditionally, so a missing decision here is a
         * wiring defect rather than a known unsupported shape.
         */
        ADMISSION_MISSING("claude_p_continuation_admission_missing"),

        /**
         * The decision recorded for this command does not describe a generation.
         *
         * [ClaudePSessionContinuationGate.Decision.NotModelGeneration] reaching a model dispatch is
         * a wiring defect by its own documentation, and a [ClaudePSessionContinuationGate.Decision.Refused]
         * one means the admission that refused was bypassed.
         */
        NOT_A_GENERATION("claude_p_continuation_not_a_generation"),
    }

    /**
     * The refusal for a Claude P model dispatch, or `null` when the dispatch is fully accounted for.
     *
     * @param command the command about to execute.
     * @param targetRole the role of the message a regenerate targets, read from the committed graph.
     *   Threaded through rather than re-derived, so that this asks [ClaudePSessionBranchPlanner] the
     *   same question admission asked it.
     * @param decision the decision admission recorded for this command, or `null` when it recorded
     *   none — which for an emergency command is the expected state and for any other is a defect.
     */
    fun refusalFor(
        command: ChatCommand,
        targetRole: MessageRole?,
        decision: ClaudePSessionContinuationGate.Decision?,
    ): Refusal? {
        if (!dispatchesModel(command, targetRole)) return null

        if (command is EmergencyCommand && decision == null) return Refusal.EMERGENCY_NOT_ADMITTED

        return when (decision) {
            null -> Refusal.ADMISSION_MISSING

            is ClaudePSessionContinuationGate.Decision.Immediate,
            is ClaudePSessionContinuationGate.Decision.Deferred,
                -> null

            is ClaudePSessionContinuationGate.Decision.Refused,
            ClaudePSessionContinuationGate.Decision.NotModelGeneration,
                -> Refusal.NOT_A_GENERATION
        }
    }

    /**
     * Whether [command] starts a model generation at all.
     *
     * Asked of the planner rather than answered by a list of command types here: a second list is a
     * second answer to the same question, and it would be the wrong one the first time a command's
     * classification changed. Both callers of this file's rules — the dispatch gate and the replay
     * hook — need the same answer, so it is written once.
     */
    fun dispatchesModel(command: ChatCommand, targetRole: MessageRole?): Boolean =
        ClaudePSessionBranchPlanner.classify(command, targetRole) !=
            ClaudePSessionBranchPlanner.Mode.NOT_MODEL_GENERATION

    /**
     * A Claude P model dispatch that reached the provider carrying no generation decision.
     *
     * ## Why this is not an assertion
     *
     * It is a domain refusal carrying a stable [Refusal.code], not a `check` whose message is a
     * string nobody enumerated. The **mechanism** is [refusalFor] at the command boundary, which
     * returns the same code upward as a rejected-command result — dispatch zero, the failure
     * identifiable, the user told why. This type exists only for the backstop at the provider call,
     * so that a dispatch site added later fails in the same vocabulary rather than by crashing on
     * an assertion.
     */
    class Refused(val refusal: Refusal) : IllegalStateException(refusal.code)
}
