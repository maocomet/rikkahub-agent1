package me.rerere.ai.provider.claudep.bridge

/**
 * The Android lifecycle owner for the per-generation tool adapters.
 *
 * ## What the keying buys
 *
 * Adapters are keyed by the exact `generationId` and looked up by nothing else. That is what
 * turns "one adapter per generation" from a convention into a structure: there is no method
 * that takes a tool call id and returns *an* adapter, so the cross-generation search the plan
 * forbids has no expression here. A frame naming a generation this registry does not hold is
 * answered by `null`, and `null` is the fail-closed answer — nothing executes and nothing is
 * guessed.
 *
 * ## Why [open] is idempotent, and why that is the reconnect rule
 *
 * A transport that reconnects, or a screen that is rebuilt, reaches the same generation again.
 * If that produced a *fresh* adapter, the fresh one would hold an empty ledger — and an
 * invocation the Server re-delivers after a reconnect would find no record, look new, and run
 * the tool a second time. That is precisely the double execution the whole design exists to
 * prevent, and it would arrive by the ordinary route a user triggers by walking into a tunnel.
 *
 * So [open] returns the adapter that is already open for that generation, ledger intact. What
 * it does **not** do is accept the reconnect's own claims: a re-supplied catalog is checked
 * against the one the generation was opened with, and a generation whose catalog digest has
 * moved is refused rather than served. The frozen catalog is immutable for the life of a
 * generation — a call admitted under one digest is not the same call under another — so a
 * reconnect carrying a different one is not a reconnect.
 *
 * The reconnect's `requestId` and `timeoutMs` are ignored for a different reason: both are
 * per-attempt values that legitimately differ on a retry, and honouring either would be
 * honouring a peer's attempt to re-open a generation on its own terms. The deadline in
 * particular is fixed when a call is admitted and is never recomputed, so no number of
 * reconnects can extend how long a call waits.
 *
 * ## Why capacity refuses rather than evicts
 *
 * An LRU would be the obvious bound, and it is the wrong one. Evicting an open generation
 * discards the record of every call it was holding, so the next re-delivery of one of those
 * calls is admitted as fresh and executes again. A bound that can cause a second execution is
 * not a safety bound. This one refuses the new generation instead, which costs a turn and
 * cannot cost correctness.
 *
 * ## Why a closed generation is remembered
 *
 * Dropping an adapter on close is not enough on its own. The generation id is peer-supplied and
 * nothing stops a peer from naming it again — and a *fresh* adapter for a closed generation
 * would hold an empty ledger, so a re-delivered invocation for one of the old calls would look
 * new and run a second time. That is the same failure as an eviction, arriving by a different
 * door.
 *
 * So a closed id is tombstoned and [open] refuses it. The tombstone is remembered for exactly
 * as long as a tool call may remain pending: the Server abandons a call at that call's own
 * deadline, so after that window there is no re-delivery left for the tombstone to guard
 * against, and it is dropped to keep this map bounded by traffic rather than by uptime.
 */
class BridgeGenerationRegistry(
    /**
     * How many generations may be open at once.
     *
     * A small number on purpose: generations are sequential for a single user, and a bound that
     * is never reached in normal use is a bound whose refusal path is never exercised. The
     * value matches the transport's own `MAX_TRACKED_GENERATIONS`, so a leak here shows up as
     * the same ceiling rather than as a second, silently different one.
     */
    private val maxOpenGenerations: Int = 8,

    /**
     * The app's real execution layer, handed to every adapter this registry opens.
     *
     * It is what lets [close] ask for a real cancellation and get a real conclusion back,
     * instead of assuming that a generation ending means its tools stopped.
     */
    private val executions: BridgeExecutionHost = BridgeExecutionHost.NONE,

    /** The monotonic clock, injected so the tombstone window is testable without waiting. */
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {

    /** Keyed by `generationId`, and by nothing else. */
    private val open = LinkedHashMap<String, BridgeToolAdapter>()

    /** The binding each open adapter was created under, so a re-open can be checked for identity. */
    private val bindings = LinkedHashMap<String, GenerationBinding>()

    /** Closed generation ids, with the monotonic instant they were closed at. Never reopened. */
    private val closed = LinkedHashMap<String, Long>()

    /** Generations currently open, including any that are in the middle of closing. */
    val openCount: Int get() = open.size

    /** The generations currently open, as a snapshot for diagnostics and tests. */
    fun openGenerationIds(): Set<String> = LinkedHashSet(open.keys)

    /** The generation ids this registry still refuses to reopen, as a snapshot. */
    fun closedGenerationIds(): Set<String> = LinkedHashSet(closed.keys)

    /**
     * Opens a generation, or returns the one already open for it.
     *
     * @throws BridgeRejected never.
     */
    fun open(binding: GenerationBinding, catalog: FrozenCatalog): OpenOutcome {
        pruneExpiredTombstones()

        val existingBinding = bindings[binding.generationId]
        if (existingBinding != null) {
            val existing = open[binding.generationId]!!
            if (!sameIdentity(existingBinding, binding, catalog)) {
                return OpenOutcome.Refused(OpenRefusal.GENERATION_IDENTITY_MISMATCH)
            }
            if (existing.isClosing) {
                // The generation is on its way out. Serving it would let a call be admitted
                // while the close is settling the calls it already has, and a call admitted
                // then is one nobody stops.
                return OpenOutcome.Refused(OpenRefusal.GENERATION_CLOSING)
            }
            // A reconnect. The adapter — and therefore its ledger — is the one already here.
            return OpenOutcome.Reused(existing)
        }

        if (closed.containsKey(binding.generationId)) {
            // A fresh adapter here would hold an empty ledger, and a re-delivered call would
            // look new and run again. See the class comment.
            return OpenOutcome.Refused(OpenRefusal.GENERATION_ALREADY_CLOSED)
        }

        if (open.size >= maxOpenGenerations) {
            // Refused, never evicted. See the class comment: an eviction can cause a second
            // execution, and a bound that can do that is worse than no bound.
            return OpenOutcome.Refused(OpenRefusal.TOO_MANY_OPEN_GENERATIONS)
        }

        val adapter = BridgeToolAdapter(binding, catalog, executions)
        open[binding.generationId] = adapter
        bindings[binding.generationId] = binding
        return OpenOutcome.Opened(adapter)
    }

    /**
     * The adapter for this exact generation, or `null`.
     *
     * The only lookup in this class, it takes a generation id, and it does no searching — a
     * miss is `null` rather than "the closest match", which is what keeps a frame from another
     * generation from being answered by this one's records.
     *
     * **A closing generation is still found here.** The adapter is released only after its calls
     * have been settled, so there is no instant in which the generation has ended and its frames
     * have become somebody else's. A frame that arrives during the close reaches the adapter
     * that owns the calls, and that adapter refuses it by name rather than by absence — which
     * is what keeps a late invoke from being mistaken for a fresh one.
     */
    fun lookup(generationId: String?): BridgeToolAdapter? =
        generationId?.let { open[it] }

    /**
     * Ends a generation: refuses new work, stops what is running, settles by what really
     * happened, and only then releases the adapter.
     *
     * Returns the calls that were outstanding, each with what Android concluded about it and
     * whether that conclusion was proven. Nothing is sent anywhere and nothing is re-dispatched
     * — there is no waiter left for a generation that has ended, and the point of concluding
     * here is that a late result for one of these calls attaches to a terminal record instead
     * of becoming an announcement.
     *
     * The order is the security-relevant part and is documented on
     * [BridgeToolAdapter.concludeForClosedGeneration]. What this method adds is the last step:
     * the adapter is removed from the registry **after** the settlement, never before, so a
     * frame arriving mid-close is answered by the records that are being settled rather than by
     * nothing at all. The id is then tombstoned, so nothing can re-open it and start over.
     *
     * @param waitMs how long to wait for the runtime to prove a stop. Bounded by the caller;
     *   this method blocks for at most that long and must not be called on the main thread.
     */
    fun close(
        generationId: String,
        waitMs: Long = BridgeClosing.DEFAULT_STOP_WAIT_MS,
    ): List<BridgeClosedCall> {
        val adapter = open[generationId] ?: return emptyList()
        val concluded = adapter.concludeForClosedGeneration(waitMs)
        // Only now, with every call the generation was holding settled, is it gone.
        open.remove(generationId)
        bindings.remove(generationId)
        closed[generationId] = monotonicMs()
        pruneExpiredTombstones()
        return concluded
    }

    /**
     * Ends every generation, for a shutdown or a re-pair.
     *
     * Returns each concluded call paired with the generation it belonged to, because the caller
     * has to stop the runtime's work per generation and cannot recover that from the outcome
     * alone.
     */
    fun closeAll(waitMs: Long = BridgeClosing.DEFAULT_STOP_WAIT_MS): Map<String, List<BridgeClosedCall>> {
        val closedGenerations = LinkedHashMap<String, List<BridgeClosedCall>>()
        for (generationId in open.keys.toList()) {
            closedGenerations[generationId] = close(generationId, waitMs)
        }
        return closedGenerations
    }

    /**
     * Drops tombstones whose window has passed.
     *
     * A tombstone exists to stop a peer re-opening a generation it already ended, and the only
     * peer that does that is one re-delivering a call — which the Server stops doing at that
     * call's own deadline. Past that window the entry guards nothing, so it goes, and this map
     * stays bounded by recent traffic instead of by process uptime.
     */
    private fun pruneExpiredTombstones() {
        if (closed.isEmpty()) return
        val now = monotonicMs()
        closed.entries.removeAll { now - it.value >= BridgeClosing.CLOSED_GENERATION_TTL_MS }
    }

    /**
     * True when two bindings describe the same conversation and assistant, under the same
     * catalog.
     *
     * `requestId` and `timeoutMs` are deliberately **not** compared. They differ between two
     * attempts at the same generation — a retry has a new request id — and comparing them would
     * refuse the reconnect this method exists to allow. What must never differ is who the
     * generation belongs to and what it was allowed to do, because that is what a tool call is
     * bound to.
     *
     * The catalog digest is on that list because the frozen catalog is part of the generation's
     * identity, not a detail of the attempt that opened it: the whole catalog participates in
     * every invocation's binding digest, so a reconnect that presents a different one is asking
     * this registry to serve calls under a catalog the calls were not admitted under. Serving
     * the old adapter would silently accept catalog B's request against catalog A's records;
     * the refusal is the only answer that cannot do that.
     */
    private fun sameIdentity(
        a: GenerationBinding,
        b: GenerationBinding,
        catalog: FrozenCatalog,
    ): Boolean =
        a.deviceRef == b.deviceRef &&
            a.assistantId == b.assistantId &&
            a.conversationId == b.conversationId &&
            a.branchId == b.branchId &&
            a.bridgeAbi == b.bridgeAbi &&
            a.catalogDigest == b.catalogDigest &&
            a.catalogDigest == catalog.digest

    /** What [open] did. A closed set, so a refusal cannot be mistaken for a success. */
    sealed interface OpenOutcome {
        /** A new generation. */
        data class Opened(val adapter: BridgeToolAdapter) : OpenOutcome

        /**
         * The generation was already open, and this is the adapter that was already here.
         *
         * Distinct from [Opened] on purpose: a caller that treats a reconnect as a fresh start
         * is the caller that re-executes a tool, so the two cases are different values rather
         * than the same value with a flag.
         */
        data class Reused(val adapter: BridgeToolAdapter) : OpenOutcome

        /** Refused, and no adapter exists for this generation. */
        data class Refused(val reason: OpenRefusal) : OpenOutcome

        /** The adapter, when one exists. `null` on refusal. */
        val adapterOrNull: BridgeToolAdapter?
            get() = when (this) {
                is Opened -> adapter
                is Reused -> adapter
                is Refused -> null
            }
    }

    /** Why a generation could not be opened. A closed set; never carries a peer value. */
    enum class OpenRefusal {
        /** This generation id is already open for a different device, assistant, branch or catalog. */
        GENERATION_IDENTITY_MISMATCH,

        /** Too many generations are open and none may be evicted safely. */
        TOO_MANY_OPEN_GENERATIONS,

        /** This generation ended, and reopening it would give its old calls an empty ledger. */
        GENERATION_ALREADY_CLOSED,

        /** This generation is being closed right now; it will not take new work. */
        GENERATION_CLOSING,
    }
}
