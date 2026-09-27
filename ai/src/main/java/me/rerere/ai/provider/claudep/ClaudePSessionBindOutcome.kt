package me.rerere.ai.provider.claudep

/**
 * What a `session.bind` attempt actually established.
 *
 * ## Why four members and not a boolean
 *
 * The caller has to write a durable state from this, and the states are not "worked / did not
 * work". §6.1 distinguishes a bind that *settled* — in either direction — from one whose outcome
 * is simply unknown, and the difference is what the branch is allowed to do next:
 *
 * - [Bound] and [AlreadyBound] prove the binding exists. The branch is resumable.
 * - [Refused] and [Malformed] are *answers*. Nothing was written and nothing will be by
 *   re-sending, so the branch closes.
 * - [Unproven] is the absence of an answer. The Server may have applied the bind before the
 *   socket died, so treating it as a failure would discard a binding that exists, and treating
 *   it as a success would resume a session that may not. The only honest state is "not proven",
 *   and the only honest recovery is to re-send the same bind later.
 *
 * Collapsing [Unproven] into either side is the mistake this type exists to prevent.
 */
sealed interface ClaudePSessionBindOutcome {

    /** `bound`: the candidate matched field for field and the binding was written atomically. */
    data object Bound : ClaudePSessionBindOutcome

    /**
     * `already_bound`: the identical bind was applied before.
     *
     * Kept apart from [Bound] so a replay can be *proved* idempotent rather than assumed to be —
     * §6.1 promises no new write and no CLI child for this answer, and a test that cannot tell
     * the two apart cannot check that promise.
     */
    data object AlreadyBound : ClaudePSessionBindOutcome

    /**
     * A settled refusal, carrying the Server's own word for it.
     *
     * [state] is one of `conflict`, `candidate_unavailable` or `refused` — never `bound` or
     * `already_bound`, which have their own members. The three are kept distinct here because a
     * log line that said only "refused" could not tell "you asked for the wrong branch" apart
     * from "the Worker restarted and lost it", and those send a reader to different places.
     */
    data class Refused(val state: ClaudePSessionBindState) : ClaudePSessionBindOutcome {
        init {
            require(state != ClaudePSessionBindState.BOUND && state != ClaudePSessionBindState.ALREADY_BOUND) {
                "A bound state is not a refusal"
            }
        }
    }

    /**
     * The Server answered, and the answer cannot be read.
     *
     * An unrecognised state string is not evidence that anything was written, and it is not a
     * transport failure either — the Server replied and this build did not understand it. That is
     * a version mismatch, and the fail-closed reading of one is to close the branch rather than
     * to re-send into a vocabulary that has moved.
     */
    data object Malformed : ClaudePSessionBindOutcome

    /**
     * No answer was received: the connection dropped, or the RPC timed out.
     *
     * Deliberately **not** a failure. See the type's doc.
     */
    data object Unproven : ClaudePSessionBindOutcome

    /**
     * True when the binding is proven to exist.
     *
     * The two successful answers are not distinguished here because the caller's next action is
     * the same for both — §6.1 guarantees a repeat wrote nothing new — but they stay separate in
     * the sealed type so a test can prove which one arrived.
     */
    val settlesAsBound: Boolean
        get() = this is Bound || this is AlreadyBound
}

/**
 * Sends one `session.bind` and maps the answer onto [ClaudePSessionBindOutcome].
 *
 * The mapping lives here, in one place, because it is the only translation between the Server's
 * four-state vocabulary and the app's durable states, and a second copy of it is how
 * `candidate_unavailable` would come to mean "retry" in one path and "close" in another.
 *
 * It never dispatches a model generation. A bind is bookkeeping about a generation that already
 * finished, and a "replay" here is a re-send of the same frame — never a second `generation.start`
 * and never a second CLI child.
 */
internal suspend fun ClaudePGatewayClient.bindSessionOnce(
    generationId: String,
    remoteThreadId: String,
    branchId: String,
    assistantId: String,
): ClaudePSessionBindOutcome {
    val body = ClaudePSessionBindBody(
        generationId = generationId,
        remoteThreadId = remoteThreadId,
        remoteBranchId = branchId,
        assistantId = assistantId,
    )
    val answer = try {
        bindSession(generationId, body)
    } catch (_: ClaudePGatewayException) {
        // A transport failure is an absence of an answer, not an answer of "no". The Server may
        // have applied the bind before the socket died, so this must not close the branch.
        return ClaudePSessionBindOutcome.Unproven
    } catch (_: Exception) {
        return ClaudePSessionBindOutcome.Unproven
    }

    // An answer that names a different generation is not an answer about this one. Settling this
    // branch on it would be binding a session to a branch it never ran for — so it is treated
    // exactly like an answer this build cannot read, and the branch closes rather than being
    // credited with someone else's binding.
    if (answer.generationId.isNotBlank() && answer.generationId != generationId) {
        return ClaudePSessionBindOutcome.Malformed
    }

    val state = answer.safeState
    return when (state) {
        ClaudePSessionBindState.BOUND -> ClaudePSessionBindOutcome.Bound
        ClaudePSessionBindState.ALREADY_BOUND -> ClaudePSessionBindOutcome.AlreadyBound
        ClaudePSessionBindState.CONFLICT,
        ClaudePSessionBindState.CANDIDATE_UNAVAILABLE,
        ClaudePSessionBindState.REFUSED,
            -> ClaudePSessionBindOutcome.Refused(state)

        null -> ClaudePSessionBindOutcome.Malformed
    }
}
