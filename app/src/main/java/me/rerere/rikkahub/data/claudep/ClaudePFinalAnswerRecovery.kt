package me.rerere.rikkahub.data.claudep

import me.rerere.ai.provider.ProviderSetting

/**
 * Whether the final-answer recovery may dispatch its **second** model call for this provider.
 *
 * ## Why a Claude P turn must not take it
 *
 * Final-answer recovery exists for a turn whose model produced no visible answer: it makes one more
 * model call with a compacted context and a reminder, and the answer it returns is what the user
 * sees. For every provider that is one conversation with one endpoint, and a second call is a second
 * question.
 *
 * A Claude P turn is not that. Its primary dispatch now runs under `mode: "auto"` with a binding
 * intent, which is what makes the Server resolve or resume **a session for the branch** — and the
 * branch's continuation record is settled from that dispatch. A recovery call is a separate
 * generation: it would have to go out as `mode: "new"`, because the recovery is a retry rather than
 * a continuation and no binding intent describes it. That means:
 *
 * - a **second session** for one user turn, which nothing in the app would show;
 * - the branch settled `BOUND` against the primary's session while the answer the user actually
 *   read came from the recovery's — so the next turn resumes a session that never produced it.
 *
 * Both are the silent re-identification this whole layer exists to prevent, and neither is visible
 * to the user. So the recovery is **not attempted** for Claude P: the turn ends in a bounded
 * `INCOMPLETE_NO_VISIBLE_ANSWER`, which keeps whatever the primary did produce and says plainly that
 * no final answer was generated. That is a worse answer than a successful recovery and a much better
 * one than a duplicated session.
 *
 * ## What it deliberately does not do
 *
 * It touches nothing the primary established. No continuation record is written, no binding intent
 * is invented, no protocol is extended, and `generation.start` is never reached from here — the
 * caller returns before the recovery dispatch, so the count for a refused turn is exactly the
 * primary's one.
 *
 * ## Why the decision is a value
 *
 * The same reason [FinalAnswerRecoveryPolicy]'s is: a rule that only exists as a branch inside a
 * four-thousand-line generation function cannot be tested without running a model, and this rule has
 * to hold for **every non-Claude-P provider too** — for which the answer is "proceed, unchanged".
 * Written here, that matrix is a thing a test asserts directly.
 *
 * ## Scope
 *
 * A recorded **MVP known limitation**, not a success requirement: Claude P turns do not get
 * final-answer recovery. It does not block M3-B activation, and M4 verifies only that no hidden
 * second model call occurs — never that recovery works for Claude P.
 */
object ClaudePFinalAnswerRecovery {

    /**
     * The named reason a Claude P recovery was refused, carried into the message annotation.
     *
     * Stable and namespaced, because it is what the user's bounded state and the diagnostics both
     * report: a human reads it, and a test asserts against it.
     */
    const val NOT_SUPPORTED = "claude_p_final_answer_recovery_not_supported"

    /**
     * [NOT_SUPPORTED] when the second model call must not be made, or `null` when it may.
     *
     * @param provider the provider the *primary* dispatch used. The recovery would use the same one,
     *   so this is also the provider the recovery would have called.
     * @param activationEnabled whether M3-B is acting on Claude P continuations at all. While it is
     *   `false` no branch carries a binding, so the recovery's `mode: "new"` is the shape every
     *   Claude P request has had all along and there is nothing to refuse — which is what keeps this
     *   batch's guard inert until activation.
     */
    fun refusal(
        provider: ProviderSetting,
        activationEnabled: Boolean,
    ): String? {
        if (!activationEnabled) return null
        if (provider !is ProviderSetting.ClaudeP) return null
        return NOT_SUPPORTED
    }
}
