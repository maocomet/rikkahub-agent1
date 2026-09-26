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
 * ## Exactly one release per identity, and why that is a separate record
 *
 * Every identity reaches **at most one** terminal state here: one decision, or one abandonment,
 * whichever happens first. Everything afterwards is a complete no-op.
 *
 * That needs a record of its own rather than a flag on the delivery maps, because the two answer
 * different questions. The delivery maps say *"is there something to hand to a waiter that has
 * not arrived yet?"*, and a decision is **consumed** from them the moment a waiter takes it. If
 * the exactly-once guard were the same entry, consuming the decision would erase the guard — and
 * a duplicate approve arriving a moment after the tool started would look like a fresh decision
 * and be handed to the next wait. [settled] is that guard, it is written before the delivery, and
 * it is never cleared by a delivery.
 *
 * Concretely, these are the cases the guard is for, and each is a no-op after the first:
 *
 * - a second tap on a stale card, arriving after the waiter took the decision;
 * - a decision arriving after the call was abandoned (the generation closed, a cancel landed) —
 *   which must not become executable later, because nobody is waiting for it any more;
 * - a second abandonment for the same identity.
 *
 * ## Why the registration is one critical section
 *
 * A waiter must not be able to slip into the gap between "no decision is waiting for me" and "I am
 * registered". If those were two separate critical sections, a decision landing between them would
 * find no waiter, be remembered as an undelivered decision, and the waiter that arrived a
 * microsecond later would block to its deadline with that decision sitting right there —
 * unclaimed, for a call the user had already approved.
 *
 * So the settled check and the registration happen under one lock, and nothing observes the
 * intermediate state. This is the same "fast user, slow frame" case as above, arriving through a
 * narrower door.
 *
 * ## Why nothing here polls
 *
 * There is no database read, no flow collection and no timer beyond the caller's own deadline. The
 * decision is handed over in-process by whoever committed it, so "waiting" costs nothing and
 * cannot observe a half-applied transaction. A deadline is the caller's backstop, never the
 * mechanism by which a decision is normally received.
 *
 * ## Why it is bounded
 *
 * [maxRememberedDecisions] caps the decisions held for waiters that never arrived and the settled
 * identities remembered. It is a leak bound, not a correctness one: entries are dropped
 * oldest-first, and a dropped entry degrades to a timeout — a lost capability, never an execution.
 * Identities are unique per approval, so reaching the bound needs thousands of approvals that no
 * one ever waited on.
 */
class InFlightApprovalWaiters(
    /**
     * How many decisions and settled identities are remembered.
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

    /**
     * Identities that have already reached a terminal state — a decision or an abandonment.
     *
     * The exactly-once guard. Written **before** the corresponding delivery, and never removed by
     * one, so a delivery cannot erase the record that it happened. Oldest-first bounded like the
     * maps above.
     */
    private val settled = LinkedHashSet<InFlightApprovalIdentity>()

    /**
     * A test seam, invoked once inside the registration critical section, after the settled maps
     * have been consulted and with the waiter already installed.
     *
     * It exists so the registration boundary can be exercised **deterministically** rather than by
     * racing threads and hoping: a test sets this to a body that signals a decision on the same
     * thread, which reenters the lock. A correct implementation has the waiter installed by then,
     * so the signal finds it and the wait returns the decision immediately; the two-critical-
     * section version this replaced would not yet have registered, so the same signal would be
     * remembered as undelivered and the wait would run to its deadline.
     *
     * Production never sets it, and the cost of it there is one null check.
     */
    internal var onAwaitRegistration: (() -> Unit)? = null

    /** For diagnostics and tests: how many waits are currently suspended. */
    val waitingCount: Int get() = synchronized(lock) { waiting.size }

    /** For diagnostics and tests: how many identities have reached a terminal state. */
    val settledCount: Int get() = synchronized(lock) { settled.size }

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
        val deferred = CompletableDeferred<InFlightApprovalOutcome>()

        // One critical section: consult what has already settled, and register if it has not.
        val alreadySettled = synchronized(lock) {
            val recorded = decidedAhead.remove(identity) ?: abandonedAhead.remove(identity)
            if (recorded != null) {
                recorded
            } else {
                // A second wait for one identity would otherwise strand the first: only one of
                // them could ever be completed by the single decision this identity can produce.
                waiting.put(identity, deferred)
                    ?.complete(InFlightApprovalOutcome.Abandoned(InFlightApprovalAbandonReason.CANCELLED))
                onAwaitRegistration?.invoke()
                null
            }
        }
        if (alreadySettled != null) return alreadySettled

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
     * Idempotent in both directions, and the second direction is the one that matters: a repeat
     * arriving after a waiter has already taken the decision leaves **no** trace — not a waiter,
     * not a remembered decision, nothing a later wait could pick up — so a duplicate tap cannot
     * reach the runtime twice.
     */
    fun signalDecided(identity: InFlightApprovalIdentity, outcome: InFlightApprovalOutcome.Decided) {
        synchronized(lock) {
            // Before anything else, and before the delivery: an identity that has already reached
            // a terminal state ignores every later signal, whether or not a waiter claimed it.
            if (!settled.add(identity)) return
            trim()

            val deferred = waiting.remove(identity)
            if (deferred != null) {
                deferred.complete(outcome)
                return
            }
            decidedAhead[identity] = outcome
        }
    }

    /**
     * Ends a wait with no decision, so the caller fails closed instead of hanging.
     *
     * A no-op when the identity has already reached a terminal state, which is what stops an
     * abandon from overwriting a real decision — that would turn a granted tool into an
     * unexecuted one. The converse is also guaranteed by the same guard: once a call has been
     * abandoned, a decision arriving afterwards is ignored rather than remembered, because the
     * generation it belonged to is gone and nothing is waiting for it any more.
     */
    fun abandon(identity: InFlightApprovalIdentity, reason: InFlightApprovalAbandonReason) {
        synchronized(lock) {
            if (!settled.add(identity)) return
            trim()

            val outcome = InFlightApprovalOutcome.Abandoned(reason)
            val deferred = waiting.remove(identity)
            if (deferred != null) {
                deferred.complete(outcome)
                return
            }
            abandonedAhead[identity] = outcome
        }
    }

    /**
     * Ends every wait, for a shutdown, a re-pair or a registry close.
     *
     * Nothing is executed afterwards: each waiter receives [reason] and the bridge reports the
     * call as it reports any unproven conclusion.
     *
     * [settled] is deliberately **not** cleared. The registry closing does not make a decision
     * that already happened un-happen, and an identity that was released before the shutdown must
     * stay released after it — otherwise a decision still in flight would look fresh to a
     * reconnected registry and could run a tool twice.
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

    /**
     * Drops an **undelivered** decision or abandonment for this identity.
     *
     * Used when a call is concluded for good and nobody is coming for the answer. It does not
     * clear [settled]: forgetting that a decision was delivered is exactly the state in which a
     * duplicate tap becomes a second execution.
     */
    fun forget(identity: InFlightApprovalIdentity) {
        synchronized(lock) {
            decidedAhead.remove(identity)
            abandonedAhead.remove(identity)
        }
    }

    private fun trim() {
        while (decidedAhead.size > maxRememberedDecisions) {
            val oldest = decidedAhead.keys.firstOrNull() ?: break
            decidedAhead.remove(oldest)
        }
        while (abandonedAhead.size > maxRememberedDecisions) {
            val oldest = abandonedAhead.keys.firstOrNull() ?: break
            abandonedAhead.remove(oldest)
        }
        while (settled.size > maxRememberedDecisions) {
            val oldest = settled.firstOrNull() ?: break
            settled.remove(oldest)
        }
    }
}
