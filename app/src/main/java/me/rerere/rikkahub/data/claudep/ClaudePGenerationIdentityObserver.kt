package me.rerere.rikkahub.data.claudep

/**
 * Hands the Server's generation id of one dispatch to its caller exactly once.
 *
 * ## Why this is an object and not a lambda
 *
 * The one property that matters is what happens on the *second* observation, and a closure inside a
 * four-thousand-line generation function cannot be tested without running a model. Pulling the rule
 * out makes "a repeat fails closed" a thing a test can assert directly, which is the only way to
 * know it holds.
 *
 * ## What is observed
 *
 * The id is never computed here. The Claude P provider stamps
 * [me.rerere.ai.ui.MessageChunk.claudePGenerationId] on the chunks of a `deferred` generation — and
 * on nothing else — reading it from the `generation.accepted` event the Server itself sent. A chunk
 * that carries no id is every other generation, and is ignored.
 *
 * ## Why a repeat fails closed
 *
 * The provider turn runner calls the observation point for a primary attempt, a fallback and a
 * retry alike. Each of those is a separate `generation.start`, so a second, *different* id is a
 * second generation — and the caller's next step is to bind a branch to one of them. Choosing the
 * later would attach the branch to a generation the app never recorded as the turn's; choosing the
 * earlier would ignore the generation that may actually have run. Neither is recoverable, so the
 * second observation is raised rather than resolved, and the caller refuses the turn.
 *
 * The *same* id observed twice is not a second generation: a retry the Server deduplicated by
 * request fingerprint replays the accepted event, so the identity has not moved and is passed
 * through unchanged.
 *
 * @param onAccepted invoked at most once, with the first id observed. Never invoked for a chunk
 *   that carries no id.
 */
internal class ClaudePGenerationIdentityObserver(
    private val onAccepted: (String) -> Unit,
) {
    private var observed: String? = null

    /**
     * True once an id has been handed over, so a caller can tell "no id yet" from "this generation
     * carries none" without re-deriving it.
     */
    val hasIdentity: Boolean get() = observed != null

    /**
     * Observes one chunk's identity, if it carries one.
     *
     * @throws IllegalStateException when a second, different id arrives — a second generation for
     *   one dispatch, which the caller must not silently resolve.
     */
    fun observe(generationId: String?) {
        val id = generationId ?: return
        val previous = observed
        if (previous == null) {
            observed = id
            onAccepted(id)
            return
        }
        if (previous == id) return
        throw IllegalStateException("claude_p_generation_identity_conflict")
    }
}
