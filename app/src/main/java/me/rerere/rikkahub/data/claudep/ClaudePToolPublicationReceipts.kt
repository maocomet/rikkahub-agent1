package me.rerere.rikkahub.data.claudep

import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The exact, process-local name of one pending publication.
 *
 * ## Why a name is needed at all
 *
 * A Claude P tool call that needs the user's approval cannot be run until the card is in the
 * conversation **and** the approval barrier behind it is durably committed. The card goes in
 * through the provider's status sink, which hands a chunk to the stream — and the stream does not
 * wait: `ProviderTurnRunner` forwards chunks through an unbounded channel, so the host that
 * published the card is suspended long before anything has been applied or committed. "The emit
 * returned" therefore says nothing at all.
 *
 * So the host has to ask, and this is what it asks about. The three fields are the whole of the
 * question, and each rules out a specific way of being answered wrongly:
 *
 * - [generationId] is the app's run identity — the same value `GenerationRunControl.runId`,
 *   `ClaudePToolGenerationContext.runId` and the host's execution plan all carry. It is *not* the
 *   Server's own `generationId`: the conversation authority never learns that value, and a key
 *   that required it could not be recomputed on both sides. Generation-exactness comes from this
 *   field plus the fact that an entry only exists between [ClaudePToolPublicationReceipts.begin]
 *   and the generation's close.
 * - [toolCallId] is the call the card is for.
 * - [invocationIdentity] is a digest of the **runtime tool name** the card was published for. It
 *   is what stops a completion for one invocation from releasing a waiter for a different one
 *   that happens to share the other two fields.
 *
 * ## Why the arguments are deliberately not part of it
 *
 * They were the obvious thing to hash, and they are the one thing that cannot be. The
 * conversation authority reads the card from a **post-redaction** copy
 * (`RuntimeSecretRedactor.redactMessages` rewrites `UIMessagePart.Tool.input` before the chunk is
 * emitted), while the publisher hashes what it sent. For any approval-gated call whose arguments
 * happen to contain a known secret the two digests would differ — so the receipt would never be
 * completed for precisely the calls where asking the user matters most. A key that is only right
 * for the calls nobody minds about is worse than a smaller key.
 *
 * The tool name survives every transform on that path, so it is what an invocation is identified
 * by here. The identity is therefore "which tool was invoked", not "with which arguments": the
 * arguments are a property of the call, and the call is already named exactly by
 * [generationId] and [toolCallId].
 *
 * ## Why the approval identity is absent, on purpose
 *
 * `approvalId` and `executionId` are derived by the app's single authoritative site
 * (`SecondUserApprovalLifecycle`). They are the *answer* this key is exchanged for, carried back
 * in [ClaudePToolPublicationOutcome.Committed], precisely so that the host never has to grow a
 * second implementation of that derivation.
 *
 * ## What this must never reach
 *
 * Process-local correlation only. It is never serialized, never sent on the Server wire, never
 * fingerprinted, never persisted and never written to a log line — which is why [toString]
 * redacts the identities rather than printing them.
 */
data class ClaudePToolPublicationId(
    val generationId: String,
    val toolCallId: String,
    val invocationIdentity: String,
) {
    override fun toString(): String =
        "ClaudePToolPublicationId(" +
            "generationId=${generationId.shortRef()}, " +
            "toolCallId=${toolCallId.shortRef()}, " +
            "invocation=${invocationIdentity.take(12)})"

    companion object {
        /**
         * The key for one invocation, derived identically by the publisher and by the authority
         * that acknowledges it.
         *
         * Both sides supply the **runtime** tool name — the app's own name for the tool, never the
         * frozen one. They agree because it is the same value that was written onto the card:
         * `ClaudePToolStatusUpdate.asInterimToolPart` sets the part's `toolName` from it verbatim.
         */
        fun of(
            generationId: String,
            toolCallId: String,
            toolName: String,
        ): ClaudePToolPublicationId = ClaudePToolPublicationId(
            generationId = generationId,
            toolCallId = toolCallId,
            invocationIdentity = invocationIdentity(toolName),
        )

        /**
         * A digest over the runtime tool name — see the class doc for why the arguments are
         * deliberately not included.
         *
         * A digest rather than the name itself because the identity ends up in a data class that a
         * debugger, a test failure or an exception message may render, and because it keeps this
         * type's fields uniformly bounded.
         */
        fun invocationIdentity(toolName: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(toolName.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * Why a publication wait ended with no commit.
 *
 * A closed set with stable spellings, local to this process, never carried on any wire. Every
 * value means the same thing to the caller: **the card is not known to be durably published, so
 * nothing may run and no waiter may be armed.**
 */
enum class ClaudePToolPublicationAbandonReason(val localReason: String) {
    /** The generation that was publishing this call ended. */
    GENERATION_CLOSED("generation_closed"),

    /** The caller's own deadline elapsed first. */
    TIMEOUT("timeout"),

    /** A cancel reached the publication before its barrier committed. */
    CANCELLED("cancelled"),

    /** The publisher gave up on this key — its own attempt ended. */
    RELEASED("released"),

    /** A live entry was evicted to stay inside the bound. */
    EVICTED("evicted"),

    /** The registry itself was closed — a re-pair, a shutdown, a process teardown. */
    REGISTRY_CLOSED("registry_closed"),
}

/** What acknowledging a publication produced. */
sealed interface ClaudePToolPublicationOutcome {
    /**
     * The card is applied and its barrier is **durably committed**. The decision may now be
     * waited on.
     *
     * The two identities are the authority's own, produced by the one derivation site that owns
     * them. The host carries them onward; it does not re-derive them.
     */
    data class Committed(val approvalId: String, val executionId: String) : ClaudePToolPublicationOutcome

    /**
     * The publication was applied to the conversation but no barrier was committed for it, or the
     * commit failed. [localReason] is a stable spelling for diagnostics and never travels.
     *
     * Fail-closed: the caller must run nothing.
     */
    data class Refused(val localReason: String) : ClaudePToolPublicationOutcome

    /** No commit was observed. Fail-closed, exactly as [Refused]. */
    data class Abandoned(val reason: ClaudePToolPublicationAbandonReason) : ClaudePToolPublicationOutcome
}

/**
 * One host's claim on one publication, returned by [ClaudePToolPublicationReceipts.begin].
 *
 * The handle exists so the publisher can wait for a specific answer without ever holding the map.
 * It is not a capability the registry depends on: completion is addressed by
 * [ClaudePToolPublicationId] alone, because the side that completes is the conversation authority
 * and it has no handle to hand back.
 */
class ClaudePToolPublicationRequest internal constructor(
    val id: ClaudePToolPublicationId,
    private val deferred: CompletableDeferred<ClaudePToolPublicationOutcome>,
    private val onDetach: (ClaudePToolPublicationId, CompletableDeferred<ClaudePToolPublicationOutcome>) -> Unit,
) {
    /**
     * Waits for this publication's acknowledgement, or gives up after [timeoutMs].
     *
     * Bounded by the caller's own tool deadline — the contract's own ceiling — so a call can never
     * outlive the point at which the Server has already concluded it.
     *
     * Registration is **not** withdrawn on timeout: unlike the approval waiter, the winner here is
     * the authority, and a completion that lands a moment after the caller stopped listening must
     * still be recorded so a duplicate cannot look fresh later. The detach below only drops a
     * *completed* entry; a still-live one is left for [ClaudePToolPublicationReceipts.release].
     */
    suspend fun await(timeoutMs: Long): ClaudePToolPublicationOutcome {
        val outcome = withTimeoutOrNull(timeoutMs) { deferred.await() }
            ?: return ClaudePToolPublicationOutcome.Abandoned(ClaudePToolPublicationAbandonReason.TIMEOUT)
        onDetach(id, deferred)
        return outcome
    }

    /** Ends this wait with no commit, for a caller that is giving up. Idempotent. */
    fun cancel(reason: ClaudePToolPublicationAbandonReason) {
        deferred.complete(ClaudePToolPublicationOutcome.Abandoned(reason))
    }
}

/**
 * The one-shot, in-process acknowledgement that a pending publication was durably committed.
 *
 * ## The problem it solves, in the order it happens
 *
 * 1. the host creates a request for an exact [ClaudePToolPublicationId];
 * 2. it publishes the pending status through the provider's sink;
 * 3. the conversation authority applies `UIMessagePart.Tool(Pending)` and writes the exact
 *    `IN_FLIGHT` approval barrier **in one authority transaction**;
 * 4. only after that transaction commits may the receipt be completed;
 * 5. only after a **successful** receipt may the host arm `InFlightApprovalWaiters`.
 *
 * Steps 1 and 3 are on different coroutines — that is the whole point. The provider collects its
 * flow in a child coroutine and forwards chunks through an unbounded channel, so the host's
 * `publish` returns before the authority has looked at anything, and the authority's collector is
 * free to run while the host is suspended here. Neither side waits on the other's stack, which is
 * what makes a cycle impossible rather than merely unlikely.
 *
 * ## Why exactly one outcome per id
 *
 * The entry lives in [pending] until it reaches a terminal, and every terminal **removes** it under
 * the same lock. A later completion for the same id therefore finds nothing and changes nothing —
 * no second delivery, no reviving a wait that was already answered. That is also what makes a
 * duplicate harmless rather than merely rare.
 *
 * [settled] is a separate record for the same reason `InFlightApprovalWaiters` keeps one: an id
 * whose answer has been consumed must not be re-armed by a later [begin], or a stale duplicate
 * could release a *new* request. It holds only ids that reached a terminal and whose publisher has
 * not released them yet, so it is bounded by the same bound as [pending].
 *
 * ## Why it is bounded, and what eviction costs
 *
 * [maxEntries] caps both maps. It is a leak bound, not a correctness one: eviction drops
 * oldest-first and completes the evicted wait as [ClaudePToolPublicationAbandonReason.EVICTED],
 * which the host turns into "run nothing". A dropped publication is a lost capability, never an
 * execution.
 *
 * ## What this is not
 *
 * It holds no `ToolRuntime`, no `McpManager`, no `Conversation`, no credential, no database and no
 * lifecycle of its own. It persists nothing, schedules nothing and polls nothing — there is no
 * timer here, and the only deadline is the caller's.
 *
 * Thread-safe: [begin] and the completions are called from the provider flow and the conversation
 * collector, which are different threads as well as different coroutines.
 */
class ClaudePToolPublicationReceipts(
    /**
     * How many live publications and recently-settled ids are remembered.
     *
     * Small on purpose. Publications are approvals, which a single user produces one at a time;
     * reaching this bound would mean hundreds of pending cards nobody ever answered.
     */
    private val maxEntries: Int = 64,
) {

    private val lock = Any()

    /** Live publications, oldest first. */
    private val pending =
        LinkedHashMap<ClaudePToolPublicationId, CompletableDeferred<ClaudePToolPublicationOutcome>>()

    /** Ids that reached a terminal and have not been released, oldest first. */
    private val settled = LinkedHashSet<ClaudePToolPublicationId>()

    /** For diagnostics and tests: how many publications are still waiting for an answer. */
    val pendingCount: Int get() = synchronized(lock) { pending.size }

    /** For diagnostics and tests: how many terminal ids are being remembered. */
    val settledCount: Int get() = synchronized(lock) { settled.size }

    /** The ids currently waiting for an answer, as a snapshot. */
    fun pendingIds(): Set<ClaudePToolPublicationId> = synchronized(lock) { LinkedHashSet(pending.keys) }

    /**
     * Readies a publication, or refuses.
     *
     * @return `null` when this exact id is already live, or reached a terminal and has not been
     *   released. Both are refusals rather than replacements: a second entry under one id would
     *   give two waiters one answer, and re-arming a settled id would let a stale duplicate release
     *   a request it does not describe. The caller must publish nothing when it receives `null`.
     */
    fun begin(id: ClaudePToolPublicationId): ClaudePToolPublicationRequest? {
        val deferred = CompletableDeferred<ClaudePToolPublicationOutcome>()
        synchronized(lock) {
            if (pending.containsKey(id) || settled.contains(id)) return null
            pending[id] = deferred
            trim()
        }
        return ClaudePToolPublicationRequest(id, deferred) { detachedId, detached ->
            synchronized(lock) {
                // Only forget a wait that is genuinely over. A live one belongs to a caller that is
                // still suspended on it, and dropping it here would leave that caller unreachable.
                if (detached.isCompleted && pending[detachedId] === detached) pending.remove(detachedId)
            }
        }
    }

    /**
     * Acknowledges the commit, exactly once.
     *
     * Called by the conversation authority **after** `checkpointWaiting` has returned — never from
     * inside the transaction, and never because an emit or a channel send succeeded.
     *
     * The two identities are the authority's, taken from the records its own derivation produced.
     *
     * @return `true` when this call was the one that settled the id. `false` means the id is
     *   unknown, already answered, or was never begun — and in every one of those cases nothing
     *   changed.
     */
    fun complete(id: ClaudePToolPublicationId, approvalId: String, executionId: String): Boolean =
        settle(id, ClaudePToolPublicationOutcome.Committed(approvalId, executionId))

    /**
     * Refuses a publication, exactly once, with a stable local reason.
     *
     * The card may still be on screen — the part is applied before the barrier is attempted — but
     * nothing is committed behind it, so nothing may wait on it and nothing may run. One-shot in
     * the same sense as [complete].
     */
    fun refuse(id: ClaudePToolPublicationId, localReason: String): Boolean =
        settle(id, ClaudePToolPublicationOutcome.Refused(localReason))

    /**
     * Ends every live publication belonging to one generation.
     *
     * Called from the same release path that closes the binding for a generation that has ended,
     * for any reason. Addresses by generation rather than by a second index so there is one map to
     * keep bounded; the scan is over at most [maxEntries] entries.
     */
    fun abandonGeneration(generationId: String, reason: ClaudePToolPublicationAbandonReason) {
        val toRelease = synchronized(lock) {
            val ids = pending.keys.filter { it.generationId == generationId }
            ids.mapNotNull { id -> pending.remove(id) }
        }
        toRelease.forEach { it.complete(ClaudePToolPublicationOutcome.Abandoned(reason)) }
    }

    /**
     * Ends every live publication, for a shutdown or a registry close.
     *
     * [settled] is deliberately **not** cleared: closing the registry does not make an answer that
     * already happened un-happen, and an id released before the shutdown must stay released after
     * it — otherwise a completion still in flight would look fresh and could arm a waiter twice.
     */
    fun abandonAll(reason: ClaudePToolPublicationAbandonReason) {
        val toRelease = synchronized(lock) {
            val all = pending.values.toList()
            pending.clear()
            all
        }
        toRelease.forEach { it.complete(ClaudePToolPublicationOutcome.Abandoned(reason)) }
    }

    /**
     * Forgets this exact id — a live wait is ended, and a settled one stops being remembered.
     *
     * The publisher calls this once it is done with the id, whatever the outcome. Forgetting a
     * settled id is safe because [complete] and [refuse] both require a live entry: once the entry
     * is gone, a duplicate completion has nothing to act on.
     */
    fun release(id: ClaudePToolPublicationId) {
        val deferred = synchronized(lock) {
            settled.remove(id)
            pending.remove(id)
        }
        deferred?.complete(
            ClaudePToolPublicationOutcome.Abandoned(ClaudePToolPublicationAbandonReason.RELEASED),
        )
    }

    /** One terminal per id, and only for an id that is actually live. */
    private fun settle(id: ClaudePToolPublicationId, outcome: ClaudePToolPublicationOutcome): Boolean {
        val deferred = synchronized(lock) {
            val live = pending.remove(id) ?: return false
            settled.add(id)
            trim()
            live
        }
        deferred.complete(outcome)
        return true
    }

    /**
     * Keeps both maps inside [maxEntries] by dropping the oldest.
     *
     * An evicted live wait is completed as [ClaudePToolPublicationAbandonReason.EVICTED] rather than
     * dropped, so a publisher blocked on it fails closed instead of hanging until its deadline.
     */
    private fun trim() {
        val evicted = mutableListOf<CompletableDeferred<ClaudePToolPublicationOutcome>>()
        while (pending.size > maxEntries) {
            val oldest = pending.keys.firstOrNull() ?: break
            pending.remove(oldest)?.let(evicted::add)
        }
        while (settled.size > maxEntries) {
            val oldest = settled.firstOrNull() ?: break
            settled.remove(oldest)
        }
        // Completed outside the lock: a continuation running under it could reenter the registry.
        evicted.forEach {
            it.complete(
                ClaudePToolPublicationOutcome.Abandoned(ClaudePToolPublicationAbandonReason.EVICTED),
            )
        }
    }
}

/**
 * A short, stable pseudonym for an identity in a diagnostic string.
 *
 * Local rather than shared: the bridge module's own `redactedRef` is internal to `:ai`, and the
 * point here is only that a log line may correlate two events without disclosing either identity.
 * It is not a cryptographic commitment and must not be described as one.
 */
private fun String.shortRef(): String =
    "ref:" + MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .take(6)
        .joinToString("") { "%02x".format(it) }
