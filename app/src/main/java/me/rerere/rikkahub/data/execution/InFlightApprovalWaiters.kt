package me.rerere.rikkahub.data.execution

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The exact identity of one pending approval.
 *
 * The same four fields `PendingToolApprovalDao.getExact` matches on, and for the same reason: a
 * waiter must be released by **that** approval's decision and by nothing else. A tuple a caller
 * could satisfy by naming something adjacent — a conversation, a tool call id on its own — would
 * let one conversation's tap release another's tool, so the type makes the full identity the only
 * thing you can wait on.
 */
data class InFlightApprovalIdentity(
    val approvalId: String,
    val executionId: String,
    val conversationId: String,
    val toolCallId: String,
)

/** What a `RESUME_COMMAND` approval reaches. Only the two outcomes that execute a continuation. */
enum class InFlightApprovalDecision {
    APPROVED,
    DENIED,
}

/**
 * Why an in-flight wait ended without a decision.
 *
 * A closed set with stable spellings, local to this process and never carried on any wire. Every
 * value means the same thing to the caller: **no decision was observed, so nothing may execute.**
 * They are kept apart so a diagnostic can say which way the wait was lost.
 */
enum class InFlightApprovalAbandonReason(val localReason: String) {
    /** The generation that was holding this call ended. */
    GENERATION_CLOSED("generation_closed"),

    /** A cancel reached the call before its approval was decided. */
    CANCELLED("cancelled"),

    /** The peer connection went away while the call was pending. */
    DISCONNECTED("disconnected"),

    /** The registry itself was closed — a re-pair, a shutdown, a process teardown. */
    REGISTRY_CLOSED("registry_closed"),

    /** The caller's own deadline elapsed first. */
    TIMEOUT("timeout"),
}

/** The answer to an in-flight wait: either the decision, or the reason there is none. */
sealed interface InFlightApprovalOutcome {
    /** The approval was decided. The decision is the one that was **durably committed**. */
    data class Decided(val decision: InFlightApprovalDecision) : InFlightApprovalOutcome

    /**
     * No decision was observed. Fail-closed: the caller must treat this as "do not execute",
     * never as "assume approved" and never as "retry to find out".
     */
    data class Abandoned(val reason: InFlightApprovalAbandonReason) : InFlightApprovalOutcome
}

/**
 * Releases the app-side waiters that a live Claude P generation holds open on an approval.
 *
 * ## Why this exists at all
 *
 * An ordinary approval resolves by ending the turn: the generation stops, the user taps, and a
 * resume command starts a new run. The bridge cannot work that way — the peer is blocked inside
 * the Worker waiting for **this** call's answer, and creating a resume command there would start a
 * second generation nobody asked for (see [ApprovalContinuationMode.IN_FLIGHT]).
 *
 * So the bridge suspends instead, and something has to wake it. This is that something, and it is
 * deliberately the smallest thing that can be: a map from an approval's exact identity to the
 * coroutine waiting on it.
 *
 * ## Why the decision is remembered, not just delivered
 *
 * "The tap arrived before the bridge got around to waiting" is not an edge case to tolerate, it is
 * the ordinary outcome of a fast user and a slow frame: the approval is raised, the user approves
 * while the tool part is still being persisted, and only then does `execute` reach the wait. A
 * registry that only delivered to a *present* waiter would drop that decision and report a
 * timeout — for a call the user explicitly approved. So a decision with nobody waiting is held
 * until somebody asks, which is what makes both orders correct.
 *
 * ## Why a repeat cannot execute twice
 *
 * A duplicate approve (or a second tap on a stale card) reaches [signalDecided] again. The first
 * call completes the waiter and removes it; the second finds neither a waiter nor an expectation,
 * so it changes nothing. A waiter is released exactly once, and the caller's `execute` therefore
 * runs at most once per admitted call — which is the same guarantee the bridge's ledger already
 * gives on the wire.
 *
 * ## Why nothing here polls
 *
 * There is no database read, no flow collection and no timer beyond the caller's own deadline. The
 * decision is handed over in-process by whoever committed it, so "waiting" costs nothing and
 * cannot observe a half-applied transaction.
 *
 * ## Why it is bounded
 *
 * [maxRememberedDecisions] caps the decisions held for waiters that never arrived. It is a
 * leak bound, not a correctness one: entries are dropped oldest-first, and a dropped entry
 * degrades to a timeout — a lost capability, never an execution.
 */
class InFlightApprovalWaiters(
    /**
     * How many decided-but-unclaimed approvals are remembered.
     *
     * Small on purpose. In normal use a decision is claimed within milliseconds by the wait that
     * is about to be reached, so this only ever holds the handful of taps that raced ahead. A
     * larger bound would hold more, for no benefit, in a map keyed by identities that are about to
     * be forgotten anyway.
     */
    private val maxRememberedDecisions: Int = 64,
) {

    private val lock = Any()

    /** Waiters currently suspended, by exact identity. */
    private val waiting = HashMap<InFlightApprovalIdentity, CompletableDeferred<InFlightApprovalOutcome>>()

    /** Decisions that arrived before their waiter, oldest first, bounded by [maxRememberedDecisions]. */
    private val decidedAhead = LinkedHashMap<InFlightApprovalIdentity, InFlightApprovalOutcome>()

    /** Identities abandoned before their waiter arrived, so a late wait does not hang. */
    private val abandonedAhead = LinkedHashMap<InFlightApprovalIdentity, InFlightApprovalOutcome>()

    /** For diagnostics and tests: how many waits are currently suspended. */
    val waitingCount: Int get() = synchronized(lock) { waiting.size }

    /**
     * Waits for this exact approval's decision, or gives up after [timeoutMs].
     *
     * A decision already recorded is returned immediately and consumed, so a second wait on the
     * same identity cannot be released by the same decision twice. On timeout the wait is
     * **deregistered** rather than left behind: a waiter that arrives after its own deadline must
     * not be completed later by a decision the caller has stopped listening for.
     */
    suspend fun await(
        identity: InFlightApprovalIdentity,
        timeoutMs: Long,
    ): InFlightApprovalOutcome {
        val settled = synchronized(lock) {
            decidedAhead.remove(identity) ?: abandonedAhead.remove(identity)
        }
        if (settled != null) return settled

        val deferred = CompletableDeferred<InFlightApprovalOutcome>()
        val previous = synchronized(lock) { waiting.put(identity, deferred) }
        // A second wait for one identity would otherwise strand the first: only one of them could
        // ever be completed by the single decision this identity can produce.
        previous?.complete(InFlightApprovalOutcome.Abandoned(InFlightApprovalAbandonReason.CANCELLED))

        val outcome = withTimeoutOrNull(timeoutMs) { deferred.await() }
            ?: InFlightApprovalOutcome.Abandoned(InFlightApprovalAbandonReason.TIMEOUT)

        synchronized(lock) {
            // Only withdraw the registration if it is still ours: a decision that landed in the
            // same instant must keep its record, not have it deleted by a timing-out wait.
            if (waiting[identity] === deferred) waiting.remove(identity)
        }
        return outcome
    }

    /**
     * Hands over the decision that was durably committed for this approval.
     *
     * Idempotent in both directions: a second call for an identity whose decision is already
     * claimed changes nothing, which is what keeps a duplicate tap from reaching the runtime.
     */
    fun signalDecided(identity: InFlightApprovalIdentity, outcome: InFlightApprovalOutcome.Decided) {
        synchronized(lock) {
            val deferred = waiting.remove(identity)
            if (deferred != null) {
                deferred.complete(outcome)
                return
            }
            if (identity in decidedAhead) return
            decidedAhead[identity] = outcome
            trim(decidedAhead)
        }
    }

    /**
     * Ends a wait with no decision, so the caller fails closed instead of hanging.
     *
     * A no-op when the identity has already been decided: an abandon must never overwrite a real
     * decision, because that would turn a granted tool into an unexecuted one.
     */
    fun abandon(identity: InFlightApprovalIdentity, reason: InFlightApprovalAbandonReason) {
        synchronized(lock) {
            if (identity in decidedAhead) return
            val deferred = waiting.remove(identity)
            val outcome = InFlightApprovalOutcome.Abandoned(reason)
            if (deferred != null) {
                deferred.complete(outcome)
                return
            }
            abandonedAhead[identity] = outcome
            trim(abandonedAhead)
        }
    }

    /**
     * Ends every wait, for a shutdown, a re-pair or a registry close.
     *
     * Nothing is executed afterwards: each waiter receives [reason] and the bridge reports the
     * call as it reports any unproven conclusion.
     */
    fun abandonAll(reason: InFlightApprovalAbandonReason) {
        val toRelease = synchronized(lock) {
            val all = waiting.values.toList()
            waiting.clear()
            decidedAhead.clear()
            abandonedAhead.clear()
            all
        }
        toRelease.forEach { it.complete(InFlightApprovalOutcome.Abandoned(reason)) }
    }

    /** Drops any remembered decision for this identity. Used when a call is concluded for good. */
    fun forget(identity: InFlightApprovalIdentity) {
        synchronized(lock) {
            decidedAhead.remove(identity)
            abandonedAhead.remove(identity)
        }
    }

    private fun trim(map: LinkedHashMap<InFlightApprovalIdentity, InFlightApprovalOutcome>) {
        while (map.size > maxRememberedDecisions) {
            val oldest = map.keys.firstOrNull() ?: return
            map.remove(oldest)
        }
    }
}
