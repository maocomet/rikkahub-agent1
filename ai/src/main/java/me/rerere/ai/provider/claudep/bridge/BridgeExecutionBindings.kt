package me.rerere.ai.provider.claudep.bridge

/**
 * The per-generation execution plan, keyed by the exact generation id and by nothing else.
 *
 * ## What problem this solves
 *
 * [BridgeGenerationRegistry] answers "which call is this, and has it run before?" — it holds the
 * ledger. This answers a different question: "what is the app's authoritative context for the
 * generation this call belongs to?" The two are deliberately separate, because the answer to the
 * second is app-owned data that this module must not interpret, while the first is contract data
 * this module owns.
 *
 * That separation is also why this is not a second registry. It manages no lifecycle of its own:
 * it is staged by [ClaudePToolBridgeHost.prepare], opened and closed by the provider at exactly
 * the points the registry is opened and closed, and it holds no ledger, no deadline and no
 * tombstone. Every generation it knows about is one the registry already knows about; the registry
 * remains the only thing that decides whether a generation may accept work.
 *
 * ## Why a lookup is exact and a search is impossible
 *
 * There is one lookup and it takes a generation id. Nothing here can take a conversation, an
 * assistant, a branch or a tool call id and return *a* plan, so the cross-generation search the
 * M2 gate forbids has no expression — it cannot be written by accident on a later pass, because
 * there is no method that would accept it.
 *
 * ## Why the plan is staged under a token first
 *
 * A generation's id does not exist when its plan is built. The catalog travels *in*
 * `generation.start`, so the plan must be ready before that frame leaves, and the id only comes
 * back in the answer. [stage] therefore readies a plan under a caller-owned token and [open]
 * redeems that token for the real id once one exists. The provider performs the redemption before
 * it pumps the first frame, so no invoke can be read before its generation resolves to a plan.
 *
 * ## Why an identity witness is required
 *
 * [open] refuses a token that would rebind a generation to a *different* plan. The prompt for
 * this is a retry that reaches the same generation with a new token; the registry has already
 * judged that case, but a table that silently kept whichever plan arrived first would be correct
 * only because a different component was, and the failure would be a call executed under another
 * generation's context. The witness is a string the caller derives from the plan — the app
 * supplies its own; this class only compares it for equality and never parses it.
 *
 * ## Bounds
 *
 * Both maps are bounded by count and drop oldest-first. The bound is a leak bound, not a
 * correctness one, and every way of exceeding it fails closed: an evicted staged plan makes
 * [open] answer `false`, which the provider turns into a failed generation rather than a
 * generation whose tools nobody can answer.
 *
 * Thread-safe. The provider stages, opens and closes from its own flow, and a close runs on
 * whatever path ended the generation — including a cancelled one.
 *
 * @param T the app's execution plan. Opaque here: never inspected, never compared, never logged.
 */
class BridgeExecutionBindings<T : Any>(
    /**
     * How many staged plans and how many open bindings may be held at once.
     *
     * The same small number the generation registry uses, and for the same reason: generations are
     * sequential for a single user, so a bound that normal use never reaches would be a refusal
     * path that is never exercised. Staged plans are the transient half — a plan is staged and
     * redeemed within one dispatch — so holding more than a handful would mean dispatches that
     * never reached their generation.
     */
    private val maxEntries: Int = 8,
) {

    private val lock = Any()

    /** Plans readied by a `prepare` whose generation has not been named yet, oldest first. */
    private val staged = LinkedHashMap<String, Staged<T>>()

    /** Plans bound to a real generation id, oldest first. */
    private val bound = LinkedHashMap<String, Bound<T>>()

    /** For diagnostics and tests: how many plans are waiting for a generation id. */
    val stagedCount: Int get() = synchronized(lock) { staged.size }

    /** For diagnostics and tests: how many generations currently resolve to a plan. */
    val boundCount: Int get() = synchronized(lock) { bound.size }

    /** The generation ids currently bound, as a snapshot. */
    fun boundGenerationIds(): Set<String> = synchronized(lock) { LinkedHashSet(bound.keys) }

    /**
     * Readies a plan under [ref], to be redeemed by [open] once its generation has an id.
     *
     * @return `false` when this ref is already staged. A token is the host's own, so a repeat is a
     *   host bug rather than peer input; refusing keeps the first plan rather than letting a later
     *   one silently replace it.
     */
    fun stage(ref: String, identity: String, value: T): Boolean = synchronized(lock) {
        if (staged.containsKey(ref)) return false
        staged[ref] = Staged(identity, value)
        trimStaged()
        true
    }

    /**
     * Binds the plan staged under [ref] to this exact generation id.
     *
     * A `null` [ref] is the text path and every refusal: there is no plan and nothing to bind, and
     * that is a **success**, not a failure — a generation with no tools is a working generation.
     *
     * @return `false` when the generation cannot be served: the ref is unknown or was already
     *   redeemed (the plan is gone), or this generation is already bound to a plan with a
     *   different [identity]. The caller must fail the generation rather than run it with tools it
     *   cannot answer.
     */
    fun open(generationId: String, ref: String?): Boolean = synchronized(lock) {
        if (ref == null) return true

        val existing = bound[generationId]
        if (existing != null) {
            // Already bound by an earlier, accepted call.
            val offered = staged[ref]
            if (offered == null) {
                // The token is spent or unknown. That is not an error here: a reconnect reaches
                // the same generation again with the binding already in place, and this table is
                // not what decides whether that reconnect is legitimate — the registry runs first
                // and is the thing that proves the generation, device, assistant, branch and
                // catalog all still agree. What this branch must not do is *replace* the plan.
                return true
            }
            // A retry that would bind a *different* plan is refused, and the token is left staged
            // so the refusal is repeatable rather than satisfied by a spent entry on the next try.
            if (offered.identity != existing.identity) return false
            staged.remove(ref)
            return true
        }

        val plan = staged.remove(ref) ?: return false
        bound[generationId] = Bound(plan.identity, plan.value)
        trimBound()
        true
    }

    /**
     * The plan bound to this exact generation, or `null`.
     *
     * `null` is the fail-closed answer and it is the only one: nothing here looks for a
     * near-match, and a caller that receives it must execute nothing.
     */
    fun lookup(generationId: String): T? = synchronized(lock) { bound[generationId]?.value }

    /**
     * Releases a generation's binding.
     *
     * Called from the same `finally` that closes the registry, so a terminal, a cancellation, a
     * disconnect and a provider failure all release it. A no-op for a generation that was never
     * bound, which makes it safe on paths that never reached [open].
     */
    fun close(generationId: String) {
        synchronized(lock) { bound.remove(generationId) }
    }

    /** Releases every binding, for a shutdown. */
    fun closeAll() {
        synchronized(lock) {
            bound.clear()
            staged.clear()
        }
    }

    private fun trimStaged() {
        while (staged.size > maxEntries) {
            val oldest = staged.keys.firstOrNull() ?: return
            staged.remove(oldest)
        }
    }

    private fun trimBound() {
        while (bound.size > maxEntries) {
            val oldest = bound.keys.firstOrNull() ?: return
            bound.remove(oldest)
        }
    }

    private class Staged<T>(val identity: String, val value: T)

    private class Bound<T>(val identity: String, val value: T)
}
