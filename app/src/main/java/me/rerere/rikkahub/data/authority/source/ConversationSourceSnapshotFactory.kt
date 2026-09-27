package me.rerere.rikkahub.data.authority.source

import java.security.MessageDigest
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.utils.JsonInstant

/** Builds content-free authority input from the exact graph being persisted. */
object ConversationSourceSnapshotFactory {
    fun fromConversation(
        scope: ConversationSourceScope,
        conversation: Conversation,
        occurredAtMs: Long,
    ): ConversationSourceSnapshot {
        val messages = conversation.messageNodes
            .flatMap { node -> node.messages }
            .map { message ->
                MessageSourceSnapshot(
                    messageId = message.id.toString(),
                    messageRole = message.role.name,
                    payloadIntegritySha256 = payloadIntegritySha256(message),
                )
            }
        val selected = conversation.messageNodes.map { node -> node.currentMessage.id.toString() }
        return ConversationSourceSnapshot(
            scope = scope,
            conversationId = conversation.id.toString(),
            assistantIdSnapshot = conversation.assistantId.toString(),
            messages = messages,
            selectedBranchMessageIds = selected,
            occurredAtMs = occurredAtMs,
        )
    }

    fun deletedConversation(
        scope: ConversationSourceScope,
        conversationId: String,
        assistantIdSnapshot: String,
        occurredAtMs: Long,
    ): ConversationSourceSnapshot = ConversationSourceSnapshot(
        scope = scope,
        conversationId = conversationId,
        assistantIdSnapshot = assistantIdSnapshot,
        messages = emptyList(),
        selectedBranchMessageIds = emptyList(),
        occurredAtMs = occurredAtMs,
        conversationDeleted = true,
    )

    /**
     * Deterministic integrity digest of the stored message **content**. The writer only compares
     * this value; it never converts it to a source revision or durable identity.
     *
     * The digest is taken over [contentIntegrityProjection] rather than over the message as stored.
     * See that function for the one field it removes and why.
     */
    fun payloadIntegritySha256(message: UIMessage): String =
        MessageDigest.getInstance("SHA-256")
            .digest(JsonInstant.encodeToString(contentIntegrityProjection(message)).encodeToByteArray())
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    /**
     * The content-integrity projection of a message: the exact value whose digest is that message's
     * content identity.
     *
     * ## What it removes, and the complete list
     *
     * Exactly one field: [UIMessage.claudePSessionContinuation]. Every other field — `id`, `role`,
     * `parts` and the tool inputs and results inside them, `annotations`, `createdAt`, `finishedAt`,
     * `modelId`, `usage`, `translation`, `state` — continues to participate in the digest,
     * unchanged. There is deliberately **no table of excluded names** here and no rule of the form
     * "anything that looks like control metadata": a field is removed by being named in this one
     * function, so adding a second one is an edit a reviewer sees rather than a pattern that
     * silently widens.
     *
     * ## Why it removes the value and not the key
     *
     * The projection substitutes `null` instead of dropping the property, and that is load-bearing
     * rather than cosmetic. `JsonInstant` encodes defaults and explicit nulls, so today every
     * message — including the overwhelming majority that have never carried a continuation — is
     * already stored with `"claudePSessionContinuation":null` in its bytes. Substituting `null`
     * therefore produces **byte-for-byte the same input** for every message whose continuation is
     * null, which is what keeps every digest already written into an authority row valid. Dropping
     * the key instead would change the digest of every message in every conversation on the first
     * write after this change, and that wave of `UPDATED` transitions is exactly the migration this
     * design is built to avoid.
     *
     * Substituting also makes the two halves of the requirement meet: a message carrying
     * `START_IN_FLIGHT` and the same message carrying `BOUND` project to the same value, *and* so
     * does a message carrying nothing at all. Removing the key could only have satisfied the first.
     *
     * ## Why the continuation is not content
     *
     * [UIMessage.claudePSessionContinuation] records what the app did about a Claude P branch — a
     * barrier it wrote, a bind it is owed, a binding it proved. It is not part of what the user
     * said or what the model answered, and no part of it reaches a prompt, a request body or a
     * fingerprint. Content identity answers "is this the same message"; a bookkeeping record that
     * changes twice per turn would otherwise answer "no" twice per turn, and every one of those
     * answers is a source revision and a branch-head move.
     *
     * ## What this does *not* do
     *
     * It does not weaken the continuation's own checks, which are the ones that decide whether a
     * branch may be resumed: the exact `(assistantId, branchId)` pair, the monotonic revision, the
     * closed transition table, the selected-path-only resolver and the fail-closed reading of a
     * malformed or contradictory record are all untouched by this function and are not derived
     * from any digest. Nor does it provide a write path: a continuation is still written only by
     * `ClaudePSessionContinuationGate`, still inside the graph authority transaction that commits
     * the message it rides on.
     */
    internal fun contentIntegrityProjection(message: UIMessage): UIMessage =
        if (message.claudePSessionContinuation == null) {
            // Already the projected value. Returned as-is so the common case allocates nothing and,
            // more importantly, so "the projection changed nothing" is visibly true here rather
            // than inferred from `copy` happening to be a no-op.
            message
        } else {
            message.copy(claudePSessionContinuation = null)
        }
}

object ConversationSourceScopeResolver {
    fun forCommand(
        assistantIdSnapshot: String,
        authoritySubjectId: String?,
    ): ConversationSourceScope = authoritySubjectId?.let { subjectId ->
        ConversationSourceScope(ConversationSourceScopeKind.AUTHORITY_SUBJECT, subjectId)
    } ?: ConversationSourceScope(
        ConversationSourceScopeKind.ASSISTANT,
        assistantIdSnapshot,
    )
}
