package me.rerere.rikkahub.data.claudep

import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The half of a publication's identity the conversation authority can rebuild from its own state.
 *
 * ## Two identities, and why they must not be conflated
 *
 * A Claude P tool call is named twice, by two systems that do not share a vocabulary, and treating
 * one as the other is a defect rather than a shorthand:
 *
 * - **`serverGenerationId`** is assigned by the Server/Gateway when it accepts `generation.start`.
 *   It names a *remote* generation. It is what `BridgeExecutionBindings` binds an execution plan
 *   under, and the only thing an inbound `tool.invoke` can be matched against.
 * - **`runId`** is the Android identity: `GenerationRunControl.runId`,
 *   `ClaudePToolGenerationContext.runId`, and the run the conversation authority is executing.
 *
 * They are not interchangeable in either direction. A `runId` is not a `serverGenerationId`
 * because the Server has never heard of it; a `serverGenerationId` is not a `runId` because the
 * conversation authority never learns it. [ClaudePToolPublicationReceipts] therefore holds **both**
 * for every publication, records the pairing immutably, and refuses a pairing that contradicts one
 * it already holds.
 *
 * ## Why this key carries the run id and not the Server's
 *
 * This is the half that has to be rebuilt by the side that *answers*. The conversation authority
 * commits the barrier, so it is the side that calls [ClaudePToolPublicationReceipts.complete] — and
 * it holds the run id and nothing else. A key requiring the Server's generation id could not be
 * rebuilt there, and a completion that guessed would be a completion for the wrong call.
 *
 * ## Why the arguments are deliberately not part of it
 *
 * They were the obvious thing to hash, and they are the one thing that cannot be. The conversation
 * authority reads the card from a **post-redaction** copy
 * (`RuntimeSecretRedactor.redactMessages` rewrites `UIMessagePart.Tool.input` before the chunk is
 * emitted), while the publisher hashes what the Server actually sent. For any approval-gated call
 * whose arguments contain a known secret the two digests would differ — so the receipt would never
 * be completed for precisely the calls where asking the user matters most.
 *
 * The argument digest is therefore kept **on the publisher's side of the record**
 * ([ClaudePToolPublicationInvocation.argsDigest]), where it is compared against the ledger's own
 * value and never against anything derived from the UI. The tool name survives every transform on
 * the answering path, so it is what the shared half is keyed on.
 *
 * ## Why the approval identity is absent, on purpose
 *
 * `approvalId` and `executionId` are derived by the app's single authoritative site
 * (`SecondUserApprovalLifecycle`). They are the *answer* this key is exchanged for, carried back
 * in [ClaudePToolPublicationOutcome.Committed], precisely so that the host never has to grow a
 * second implementation of that derivation.
 */
data class ClaudePToolPublicationKey(
    /**
     * The Android run identity — `GenerationRunControl.runId`, and nothing else.
     *
     * Never a Server generation id standing in for it. The two are separate fields on
     * [ClaudePToolPublicationInvocation] for exactly that reason.
     */
    val runId: String,
    val toolCallId: String,
    /** A digest of the runtime tool name — the app's own name, never the frozen one. */
    val invocationIdentity: String,
) {
    override fun toString(): String =
        "ClaudePToolPublicationKey(" +
            "runId=${runId.shortRef()}, " +
            "toolCallId=${toolCallId.shortRef()}, " +
            "invocation=${invocationIdentity.shortRef()})"

    companion object {
        /**
         * The key for one invocation, derived identically by the publisher and by the authority
         * that acknowledges it.
         *
         * Both sides supply the **runtime** tool name. They agree because it is the same value
         * that was written onto the card: `ClaudePToolStatusUpdate.asInterimToolPart` sets the
         * part's `toolName` from it verbatim, and redaction does not touch a name.
         */
        fun of(
            runId: String,
            toolCallId: String,
            toolName: String,
        ): ClaudePToolPublicationKey = ClaudePToolPublicationKey(
            runId = runId,
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
 * The immutable invocation one publication is for, as the publisher holds it.
 *
 * ## Why this is recorded rather than re-derived
 *
 * Everything here comes from the **Server's original invocation** and the ledger that admitted it:
 * the generation it arrived under, the run serving it, the call id, the runtime tool name, and the
 * contract's own argument digest. None of it is rebuilt from the conversation.
 *
 * That distinction is the whole point. The card the user taps renders a copy of the arguments that
 * `RuntimeSecretRedactor` may have rewritten, and the arguments themselves never leave this
 * process in that direction. What gets executed after an approval is the invocation the ledger
 * holds — the same object, with the same digest — and never anything reconstructed from the card.
 *
 * ## What is *not* here
 *
 * The raw arguments. The record is a set of identities, so nothing on this path carries a secret
 * into the conversation layer or into a log; the digest is what makes "the same invocation" a
 * checkable fact without the values.
 *
 * ## What this must never reach
 *
 * Process-local correlation only: never serialized, never sent on the Server wire, never
 * fingerprinted, never persisted, never written to a log line and never rendered in the approval
 * UI — which is why [toString] redacts the identities rather than printing them.
 */
data class ClaudePToolPublicationInvocation(
    /**
     * The Server/Gateway generation this call arrived under.
     *
     * Verified against the plan `BridgeExecutionBindings` bound under this exact id, so a call
     * whose generation and run do not correspond cannot reach an execution.
     */
    val serverGenerationId: String,
    /** The Android run serving that generation. */
    val runId: String,
    val toolCallId: String,
    /** The runtime tool name — the app's own, never the frozen contract name. */
    val toolName: String,
    /**
     * The contract's digest of the **original** arguments, from the invocation that was admitted.
     *
     * Never recomputed from a conversation part. The ledger refuses a repeat of this call id
     * carrying any different digest (`InvocationDecision.CONFLICT`), and the host re-asserts this
     * value against the invocation it is about to run, so a tampered call is refused rather than
     * executed under the approval a different one earned.
     */
    val argsDigest: String,
) {
    val key: ClaudePToolPublicationKey
        get() = ClaudePToolPublicationKey.of(
            runId = runId,
            toolCallId = toolCallId,
            toolName = toolName,
        )

    /**
     * Redacted throughout, including the argument digest.
     *
     * The digest is a digest of the arguments, so echoing a prefix of it would be echoing a prefix
     * of whatever the caller passed — harmless for a real SHA-256, and not harmless at all for a
     * caller that supplied something else. Every field therefore goes through [shortRef], which
     * makes "this rendering cannot disclose its inputs" a property of the declaration rather than
     * an assumption about what the values look like.
     */
    override fun toString(): String =
        "ClaudePToolPublicationInvocation(" +
            "serverGenerationId=${serverGenerationId.shortRef()}, " +
            "runId=${runId.shortRef()}, " +
            "toolCallId=${toolCallId.shortRef()}, " +
            "toolName=${toolName.shortRef()}, " +
            "argsDigest=${argsDigest.shortRef()})"

    /**
     * True when [other] is the same call — every field, including the argument digest.
     *
     * Deliberately **not** the same question as [key] equality. The key is what the conversation
     * authority can rebuild, so it leaves the argument digest out; this is what the publisher
     * re-asserts against the ledger before anything runs, so it includes it. Two invocations that
     * share a key and differ here are two different calls wearing one call id, and the answer to
     * that is to run neither.
     */
    fun sameCallAs(other: ClaudePToolPublicationInvocation): Boolean =
        serverGenerationId == other.serverGenerationId &&
            runId == other.runId &&
            toolCallId == other.toolCallId &&
            toolName == other.toolName &&
            argsDigest == other.argsDigest
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
 * [ClaudePToolPublicationKey] alone, because the side that completes is the conversation authority
 * and it has no handle to hand back.
 */
class ClaudePToolPublicationRequest internal constructor(
    /**
     * The invocation this publication is for, as recorded when it was opened.
     *
     * Handed back to the publisher so it can re-assert — after the receipt commits and before
     * anything runs — that the invocation it is holding is still the one that was acknowledged.
     */
    val invocation: ClaudePToolPublicationInvocation,
    private val deferred: CompletableDeferred<ClaudePToolPublicationOutcome>,
    private val onDetach: (
        ClaudePToolPublicationKey,
        CompletableDeferred<ClaudePToolPublicationOutcome>,
    ) -> Unit,
) {
    val key: ClaudePToolPublicationKey get() = invocation.key
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
        onDetach(key, deferred)
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
 * 1. the host creates a request for an exact [ClaudePToolPublicationInvocation];
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

    /** Live publications, oldest first, keyed by the half the authority rebuilds. */
    private val pending = LinkedHashMap<ClaudePToolPublicationKey, Publication>()

    /** Keys that reached a terminal and have not been released, oldest first. */
    private val settled = LinkedHashSet<ClaudePToolPublicationKey>()

    /**
     * The immutable association between an Android run and the Server generation it serves.
     *
     * Written when a generation is opened and dropped when it is closed, and **never** derived from
     * a conversation id, an assistant id, or whichever run happens to be executing — those are all
     * ways to *find a* generation rather than to *prove the* one a call belongs to.
     *
     * The two halves are separately supplied, so a caller holding the right run and the wrong
     * generation cannot pair them: the association was fixed at open time and this map only ever
     * confirms it.
     */
    private val generationByRun = LinkedHashMap<String, String>()

    /** For diagnostics and tests: how many publications are still waiting for an answer. */
    val pendingCount: Int get() = synchronized(lock) { pending.size }

    /** For diagnostics and tests: how many terminal keys are being remembered. */
    val settledCount: Int get() = synchronized(lock) { settled.size }

    /** For diagnostics and tests: how many runs currently have a Server generation bound. */
    val boundRunCount: Int get() = synchronized(lock) { generationByRun.size }

    /** The keys currently waiting for an answer, as a snapshot. */
    fun pendingKeys(): Set<ClaudePToolPublicationKey> =
        synchronized(lock) { LinkedHashSet(pending.keys) }

    /** The Server generation bound to this run, or `null` when this run has none. */
    fun serverGenerationIdFor(runId: String): String? =
        synchronized(lock) { generationByRun[runId] }

    /**
     * The Android run bound to this Server generation, or `null` when nothing is serving it.
     *
     * The same association read from the other side, and **the same map**: a second table keyed the
     * other way would be a second answer to "which run is this generation?", and the two could
     * disagree. It exists because the bridge speaks the Server's vocabulary while the app's runtime
     * speaks its own, and this is the one place the two are converted — by looking up what was
     * recorded at `openGeneration`, never by inferring anything from the call.
     *
     * Because the association is dropped by the same release path that closes the generation, an
     * answer here is also a statement that the generation is still open. `null` is the fail-closed
     * answer: an unpaired generation has no run, so no control, so nothing that can be stopped and
     * nothing that can be proven.
     */
    fun runIdFor(serverGenerationId: String): String? = synchronized(lock) {
        generationByRun.entries.firstOrNull { it.value == serverGenerationId }?.key
    }

    /**
     * Records that this run serves this Server generation, for the life of that generation.
     *
     * Called when the generation is opened, once both halves exist: the Server's id is only known
     * in the answer to `generation.start`, and the run id is the app's own.
     *
     * @return `false` when the pairing contradicts one already held — this run is already serving a
     *   different generation, or this generation is already served by a different run. Both are
     *   refusals rather than replacements: an association that could be overwritten is one a later
     *   call could silently re-point at itself, which is precisely the confusion this map exists
     *   to make unexpressible.
     */
    fun bindGeneration(serverGenerationId: String, runId: String): Boolean = synchronized(lock) {
        generationByRun[runId]?.let { return it == serverGenerationId }
        val serving = generationByRun.entries.firstOrNull { it.value == serverGenerationId }
        if (serving != null) return serving.key == runId
        generationByRun[runId] = serverGenerationId
        trim()
        true
    }

    /**
     * Drops one Server generation's association and ends every publication it still holds.
     *
     * Called from the same release path that closes the execution binding, for a terminal, a
     * cancellation, a disconnect or a provider failure. Dropping the association as well as the
     * publications is what stops a later call from pairing that run with anything at all.
     */
    fun unbindGeneration(serverGenerationId: String, reason: ClaudePToolPublicationAbandonReason) {
        val toRelease = synchronized(lock) {
            generationByRun.entries.removeAll { it.value == serverGenerationId }
            val keys = pending
                .filterValues { it.invocation.serverGenerationId == serverGenerationId }
                .keys
            keys.mapNotNull { pending.remove(it)?.deferred }
        }
        toRelease.forEach { it.complete(ClaudePToolPublicationOutcome.Abandoned(reason)) }
    }

    /**
     * Readies a publication, or refuses.
     *
     * @return `null` when this invocation cannot be published: its run and Server generation were
     *   never bound together, or this exact key is already live, or it reached a terminal and has
     *   not been released. Every one of those is a refusal rather than a replacement — a second
     *   entry under one key would give two waiters one answer, and re-arming a settled key would
     *   let a stale duplicate release a request it does not describe. The caller must publish
     *   nothing when it receives `null`.
     */
    fun begin(invocation: ClaudePToolPublicationInvocation): ClaudePToolPublicationRequest? {
        val deferred = CompletableDeferred<ClaudePToolPublicationOutcome>()
        val key = invocation.key
        synchronized(lock) {
            if (generationByRun[invocation.runId] != invocation.serverGenerationId) return null
            if (pending.containsKey(key) || settled.contains(key)) return null
            pending[key] = Publication(invocation, deferred)
            trim()
        }
        return ClaudePToolPublicationRequest(invocation, deferred) { detachedKey, detached ->
            synchronized(lock) {
                // Only forget a wait that is genuinely over. A live one belongs to a caller that is
                // still suspended on it, and dropping it here would leave that caller unreachable.
                if (detached.isCompleted && pending[detachedKey]?.deferred === detached) {
                    pending.remove(detachedKey)
                }
            }
        }
    }

    /**
     * Acknowledges the commit, exactly once.
     *
     * Called by the conversation authority **after** `checkpointWaiting` has returned — never from
     * inside the transaction, and never because an emit or a channel send succeeded.
     *
     * Addressed by [ClaudePToolPublicationKey], which is the half that side can rebuild from its
     * own state. The Server generation is *not* part of the address: it is recorded on the entry
     * and was checked when the entry was opened.
     *
     * The two identities are the authority's, taken from the records its own derivation produced.
     *
     * @return `true` when this call was the one that settled the key. `false` means the key is
     *   unknown, already answered, or was never begun — and in every one of those cases nothing
     *   changed.
     */
    fun complete(key: ClaudePToolPublicationKey, approvalId: String, executionId: String): Boolean =
        settle(key, ClaudePToolPublicationOutcome.Committed(approvalId, executionId))

    /**
     * Refuses a publication, exactly once, with a stable local reason.
     *
     * The card may still be on screen — the part is applied before the barrier is attempted — but
     * nothing is committed behind it, so nothing may wait on it and nothing may run. One-shot in
     * the same sense as [complete].
     */
    fun refuse(key: ClaudePToolPublicationKey, localReason: String): Boolean =
        settle(key, ClaudePToolPublicationOutcome.Refused(localReason))

    /**
     * Ends every live publication belonging to one run.
     *
     * A narrower sibling of [unbindGeneration], for a caller that holds the run rather than the
     * generation. It does **not** drop the generation association: a run whose generation is still
     * open may publish again, and only the close retires the pairing.
     */
    fun abandonRun(runId: String, reason: ClaudePToolPublicationAbandonReason) {
        val toRelease = synchronized(lock) {
            val keys = pending.filterValues { it.invocation.runId == runId }.keys
            keys.mapNotNull { pending.remove(it)?.deferred }
        }
        toRelease.forEach { it.complete(ClaudePToolPublicationOutcome.Abandoned(reason)) }
    }

    /**
     * Ends every live publication and every association, for a shutdown or a registry close.
     *
     * [settled] is deliberately **not** cleared: closing the registry does not make an answer that
     * already happened un-happen, and a key released before the shutdown must stay released after
     * it — otherwise a completion still in flight would look fresh and could arm a waiter twice.
     */
    fun abandonAll(reason: ClaudePToolPublicationAbandonReason) {
        val toRelease = synchronized(lock) {
            generationByRun.clear()
            val all = pending.values.map { it.deferred }
            pending.clear()
            all
        }
        toRelease.forEach { it.complete(ClaudePToolPublicationOutcome.Abandoned(reason)) }
    }

    /**
     * Forgets this exact key — a live wait is ended, and a settled one stops being remembered.
     *
     * The publisher calls this once it is done with the key, whatever the outcome. Forgetting a
     * settled key is safe because [complete] and [refuse] both require a live entry: once the entry
     * is gone, a duplicate completion has nothing to act on.
     */
    fun release(key: ClaudePToolPublicationKey) {
        val deferred = synchronized(lock) {
            settled.remove(key)
            pending.remove(key)?.deferred
        }
        deferred?.complete(
            ClaudePToolPublicationOutcome.Abandoned(ClaudePToolPublicationAbandonReason.RELEASED),
        )
    }

    /** One live publication: the invocation it was opened for, and the wait on its answer. */
    private class Publication(
        val invocation: ClaudePToolPublicationInvocation,
        val deferred: CompletableDeferred<ClaudePToolPublicationOutcome>,
    )

    /** One terminal per key, and only for a key that is actually live. */
    private fun settle(
        key: ClaudePToolPublicationKey,
        outcome: ClaudePToolPublicationOutcome,
    ): Boolean {
        val deferred = synchronized(lock) {
            val live = pending.remove(key)?.deferred ?: return false
            settled.add(key)
            trim()
            live
        }
        deferred.complete(outcome)
        return true
    }

    /**
     * Keeps every map inside [maxEntries] by dropping the oldest.
     *
     * An evicted live wait is completed as [ClaudePToolPublicationAbandonReason.EVICTED] rather than
     * dropped, so a publisher blocked on it fails closed instead of hanging until its deadline. An
     * evicted *association* is different: dropping it does not end anything, it only means a later
     * publication for that run is refused — a lost capability, never an execution.
     */
    private fun trim() {
        val evicted = mutableListOf<CompletableDeferred<ClaudePToolPublicationOutcome>>()
        while (pending.size > maxEntries) {
            val oldest = pending.keys.firstOrNull() ?: break
            pending.remove(oldest)?.deferred?.let(evicted::add)
        }
        while (settled.size > maxEntries) {
            val oldest = settled.firstOrNull() ?: break
            settled.remove(oldest)
        }
        while (generationByRun.size > maxEntries) {
            val oldest = generationByRun.keys.firstOrNull() ?: break
            generationByRun.remove(oldest)
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
