package me.rerere.ai.provider.claudep

/**
 * What the app knows about a generation's branch that the provider cannot work out for itself.
 *
 * ## Why this exists
 *
 * A branch is a *combination of variant selections* in the conversation graph, and the provider
 * is handed messages and a model — it has no way to tell which of a node's variants the user is
 * looking at, or whether this generation is about to create one. So the app resolves that against
 * the graph, which is the only authority for it, and hands the answer over.
 *
 * This is the input to `mode: "auto"`. Without it a Claude P request keeps the pre-M3 shape
 * (`mode: "new"`), which is what every other call path and every test that predates M3 uses.
 *
 * ## What is here, and the much longer list of what is not
 *
 * An assistant, an intent, and — for an immediate generation only — a branch identity. There is
 * no Claude session id, no config hash and no credential, because none of them is the app's to
 * know: the Server derives them and keeps them, and a client that sent one would be asserting a
 * continuation it cannot prove. §5.1's whole point is that `auto` means "resolve it yourself".
 *
 * ## Why this is not persisted
 *
 * It describes one dispatch, not one branch. The durable half of the same story is
 * [ClaudePSessionContinuation], which rides on the message graph; this value is rebuilt from the
 * graph each time and would be a second, staler copy if it were stored anywhere.
 *
 * The field on [me.rerere.ai.provider.TextGenerationParams] is `@Transient` for the same reason
 * the tool generation context is: it must never reach a request body, a fingerprint or a log
 * line. The three values it does carry to the wire travel as the protocol's own fields.
 */
data class ClaudePSessionBindingRequest(
    /**
     * The assistant the branch belongs to.
     *
     * The app takes this from the conversation authority rather than from a caller's argument,
     * so a caller cannot name an assistant the graph does not agree with.
     */
    val assistantId: String,
    val intent: ClaudePSessionBindingIntent,
    /**
     * The committed branch identity, for [ClaudePSessionBindingIntent.IMMEDIATE].
     *
     * It must be the 64-lowercase-hex digest [ClaudePSessionBranchId] produces and nothing else,
     * because it goes on the wire as `remote_branch_id` under the one shape §5.3 defines for a
     * real branch. `null` under [ClaudePSessionBindingIntent.DEFERRED], where the branch does not
     * exist yet and absence is the protocol's own way of saying so.
     */
    val branchId: String?,
) {
    init {
        require(assistantId.isNotBlank()) {
            "An auto request must name its assistant; the protocol refuses one that does not"
        }
        when (intent) {
            ClaudePSessionBindingIntent.IMMEDIATE -> require(
                branchId != null && BRANCH_ID.matches(branchId),
            ) {
                "An immediate binding must carry a canonical branch id"
            }

            ClaudePSessionBindingIntent.DEFERRED -> require(branchId == null) {
                "A deferred binding describes a branch that does not exist yet"
            }
        }
    }

    /**
     * Redacted: the assistant and branch identities are the app's own, and a log line may
     * correlate two dispatches without disclosing either.
     */
    override fun toString(): String =
        "ClaudePSessionBindingRequest(" +
            "assistantId=${assistantId.redactedRef()}, " +
            "intent=${intent.wireValue}, " +
            "branchId=${branchId.redactedRef()})"

    private companion object {
        val BRANCH_ID = Regex("^[0-9a-f]{64}$")
    }
}
