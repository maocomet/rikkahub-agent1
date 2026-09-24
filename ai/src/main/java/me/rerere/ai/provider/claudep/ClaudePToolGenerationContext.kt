package me.rerere.ai.provider.claudep

/**
 * The app-side generation identity a Claude P tool call must be bound to.
 *
 * ## Why this exists
 *
 * A tool call that arrives over the bridge is bound to a *particular* generation: one run, one
 * conversation, one assistant, one branch. Every one of those is a property the app knows and
 * the provider does not — `Provider.streamText` is handed messages and a model, and nothing
 * about which run it is serving. Without them a host could only guess, and every way of
 * guessing eventually picks *a* generation rather than *the* generation.
 *
 * So the app supplies them explicitly, through a field that is never serialized, never
 * fingerprinted and never logged.
 *
 * ## Why every field is a string
 *
 * Deliberately un-typed. The app's tool layer is free to change the concrete types of its own
 * identities without this module following, and a mismatch is caught where the app maps them
 * rather than here. What this type promises is only that the app handed over *its* authority's
 * values, verbatim — not that this module understands them.
 *
 * `callOrigin` is a token rather than an enum for the same reason: the origin vocabulary
 * belongs to the app's execution layer, and this module must not carry a copy of it that could
 * silently drift out of step with the real one. The app maps the token, and an unrecognised
 * token fails closed.
 *
 * ## What this must never reach
 *
 * These are Android-internal identities. They do not belong in a prompt, a log line, a
 * fingerprint or any Server frame — the frozen protocol carries `assistantId`,
 * `conversationId` and `branchId` because it already names them, and nothing here adds a field
 * to the wire. The type is carried on [me.rerere.ai.provider.TextGenerationParams] as a
 * `@Transient` property, which is what makes "never serialized into a request body" a property
 * of the declaration rather than a rule someone has to remember.
 *
 * ## Why absence is not a default
 *
 * There is no empty instance and no fallback. `null` means the app did not supply the identity,
 * and the only correct answer to that is to execute nothing — a tool call whose generation
 * cannot be named is a call whose result nobody is waiting for.
 */
data class ClaudePToolGenerationContext(
    /** The app's run identity, matching `GenerationRunControl.runId` for this generation. */
    val runId: String,
    /** The durable command identity this run belongs to. */
    val commandId: String,
    val conversationId: String,
    val assistantId: String,
    val branchId: String,
    /**
     * The app's tool-call origin token; mapped by the app, refused when unrecognised.
     *
     * The mapping is the app's, and it must be an **exact** one: a token is admissible only if it
     * equals some `ToolCallOrigin` entry's `name` character for character — the equivalent of
     * `ToolCallOrigin.entries.singleOrNull { it.name == token }`. Anything else — an unknown
     * token, a blank one, a differently-cased one — is *not* an origin, and the only correct
     * answer is to refuse the call.
     *
     * In particular it is not an origin to fall back to. Substituting `LocalChat`,
     * `TrustedWorkflow` or any other entry for a token the app did not recognise would grant the
     * call that entry's tool surface and approval policy on the strength of a string this layer
     * merely carried. Absence of a mapping is the same answer as absence of a context: run
     * nothing.
     *
     * This module deliberately offers no normalisation to lean on — no trimming, no case
     * folding, no alias table. The token is stored and returned exactly as the app supplied it,
     * so an app that compares it exactly is comparing the same bytes this layer received, and an
     * app that would rather be lenient has to say so in code a reviewer can see.
     */
    val callOrigin: String,
) {
    /**
     * Redacted rendering: a log line may correlate two events, never identify a generation.
     *
     * The five identity fields are Android-internal, and the way they reach a log is a `toString`
     * somebody did not think about — an exception message, a debug print, a test failure. This
     * override is what makes that harmless: each identity is replaced by [redactedRef], the
     * module's existing short digest, which distinguishes two values without disclosing either.
     *
     * [callOrigin] is handled differently on purpose. It is a token from a **small, closed**
     * vocabulary, so a digest of it is not a redaction — enumerating the app's origins recovers
     * it exactly. It is therefore reported as present-but-unstated rather than as a pseudonym.
     *
     * What is left is still useful: `isComplete` says whether a generation could be bound at
     * all, and the digests say whether two log lines describe the same generation.
     */
    override fun toString(): String =
        "ClaudePToolGenerationContext(" +
            "runId=${runId.redactedRef()}, " +
            "commandId=${commandId.redactedRef()}, " +
            "conversationId=${conversationId.redactedRef()}, " +
            "assistantId=${assistantId.redactedRef()}, " +
            "branchId=${branchId.redactedRef()}, " +
            "callOrigin=<redacted>, " +
            "isComplete=$isComplete)"

    /**
     * True when every identity needed to bind and execute a call is present and non-blank.
     *
     * Callers must treat `false` as "no context", never as "use defaults": a partially-filled
     * context is exactly the state in which a call would be bound to the wrong generation.
     */
    val isComplete: Boolean
        get() = runId.isNotBlank() &&
            commandId.isNotBlank() &&
            conversationId.isNotBlank() &&
            assistantId.isNotBlank() &&
            branchId.isNotBlank() &&
            callOrigin.isNotBlank()
}
