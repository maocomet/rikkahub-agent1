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
 * So [open] returns the adapter that is already open for that generation, ledger intact, and
 * ignores a re-supplied catalog: the frozen catalog is immutable for the life of a generation,
 * and accepting a second one would mean the digest a call was admitted under could change
 * underneath it.
 *
 * ## Why capacity refuses rather than evicts
 *
 * An LRU would be the obvious bound, and it is the wrong one. Evicting an open generation
 * discards the record of every call it was holding, so the next re-delivery of one of those
 * calls is admitted as fresh and executes again. A bound that can cause a second execution is
 * not a safety bound. This one refuses the new generation instead, which costs a turn and
 * cannot cost correctness.
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
) {

    /** Keyed by `generationId`, and by nothing else. */
    private val open = LinkedHashMap<String, BridgeToolAdapter>()

    /** The binding each open adapter was created under, so a re-open can be checked for identity. */
    private val bindings = LinkedHashMap<String, GenerationBinding>()

    /** Generations currently open. */
    val openCount: Int get() = open.size

    /** The generations currently open, as a snapshot for diagnostics and tests. */
    fun openGenerationIds(): Set<String> = LinkedHashSet(open.keys)

    /**
     * Opens a generation, or returns the one already open for it.
     *
     * @throws BridgeRejected when the generation is already open under a *different* identity.
     *   That is not a reconnect: it is two different conversations claiming one generation id,
     *   and serving either would bind calls to the wrong assistant.
     */
    fun open(binding: GenerationBinding, catalog: FrozenCatalog): OpenOutcome {
        val existingBinding = bindings[binding.generationId]
        if (existingBinding != null) {
            val existing = open[binding.generationId]!!
            if (!sameIdentity(existingBinding, binding)) {
                return OpenOutcome.Refused(OpenRefusal.GENERATION_IDENTITY_MISMATCH)
            }
            // A reconnect. The adapter — and therefore its ledger — is the one already here.
            return OpenOutcome.Reused(existing)
        }

        if (open.size >= maxOpenGenerations) {
            // Refused, never evicted. See the class comment: an eviction can cause a second
            // execution, and a bound that can do that is worse than no bound.
            return OpenOutcome.Refused(OpenRefusal.TOO_MANY_OPEN_GENERATIONS)
        }

        val adapter = BridgeToolAdapter(binding, catalog)
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
     */
    fun lookup(generationId: String?): BridgeToolAdapter? =
        generationId?.let { open[it] }

    /**
     * Ends a generation: concludes what it was holding, then releases it.
     *
     * Returns the calls that were still outstanding, already concluded `cancelled`, so the
     * caller can stop whatever the runtime is doing for them and close the matching approval
     * UI. Nothing is sent anywhere and nothing is re-dispatched — there is no waiter left for
     * a generation that has ended, and the whole point of concluding them here is that a late
     * result for one attaches to a terminal record instead of becoming an announcement.
     *
     * The order is deliberate: the calls are concluded **before** the adapter is dropped, so
     * there is no instant in which the generation is gone but its calls are still pending.
     */
    fun close(generationId: String): List<ToolCallOutcome> {
        val adapter = open.remove(generationId) ?: return emptyList()
        bindings.remove(generationId)
        return adapter.concludeForClosedGeneration()
    }

    /**
     * Ends every generation, for a shutdown or a re-pair.
     *
     * Returns each concluded call paired with the generation it belonged to, because the caller
     * has to stop the runtime's work per generation and cannot recover that from the outcome
     * alone.
     */
    fun closeAll(): Map<String, List<ToolCallOutcome>> {
        val closed = LinkedHashMap<String, List<ToolCallOutcome>>()
        for (generationId in open.keys.toList()) {
            closed[generationId] = close(generationId)
        }
        return closed
    }

    /**
     * True when two bindings describe the same conversation and assistant.
     *
     * `requestId`, `catalogDigest` and `timeoutMs` are deliberately **not** compared. They
     * differ between two attempts at the same generation — a retry has a new request id — and
     * comparing them would refuse the reconnect this method exists to allow. What must never
     * differ is who the generation belongs to, because that is what a tool call is bound to.
     */
    private fun sameIdentity(a: GenerationBinding, b: GenerationBinding): Boolean =
        a.deviceRef == b.deviceRef &&
            a.assistantId == b.assistantId &&
            a.conversationId == b.conversationId &&
            a.branchId == b.branchId &&
            a.bridgeAbi == b.bridgeAbi

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
        /** This generation id is already open for a different device, assistant or branch. */
        GENERATION_IDENTITY_MISMATCH,

        /** Too many generations are open and none may be evicted safely. */
        TOO_MANY_OPEN_GENERATIONS,
    }
}
