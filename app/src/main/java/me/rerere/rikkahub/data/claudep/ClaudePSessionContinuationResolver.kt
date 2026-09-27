package me.rerere.rikkahub.data.claudep

import me.rerere.ai.provider.claudep.ClaudePSessionContinuation
import me.rerere.ai.provider.claudep.ClaudePSessionContinuationResolution
import me.rerere.ai.provider.claudep.ClaudePSessionContinuationState
import me.rerere.ai.provider.claudep.ClaudePSessionContinuationTransitions
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode

/**
 * Reads one branch's continuation state out of the authoritative message graph.
 *
 * ## Why the graph itself is the source, and not a lookup
 *
 * A conversation is an ordered list of [MessageNode]s, each holding its own variants and the
 * `selectIndex` that says which one the user is looking at. "Which branch" is therefore a
 * *combination of choices*, and the only way to know it is to read the selections. Anything else
 * — the most recent command, the tip, a remembered id — is a guess that is right until the user
 * switches a variant.
 *
 * ## The four rules, and what each one refuses
 *
 * 1. **Only the selected variant of each node is read.** An unselected variant is a message the
 *    user is not looking at. Scanning it would let a branch read a state that belongs to a
 *    sibling branch, which is how a fork would inherit a session it never bound.
 * 2. **A record must match `(assistantId, branchId)` exactly.** Never a prefix, never a
 *    case-fold, never "closest". Switching assistants or switching a variant changes the pair,
 *    and a record for a different pair is a record about a different branch.
 * 3. **There is no conversation-level fallback.** A missing record is
 *    [ClaudePSessionContinuationResolution.UNINITIALIZED] — "this branch has no history" — and
 *    never "use the conversation's". A fallback would answer for a branch that has no answer,
 *    and the answer it would give is a binding belonging to something else.
 * 4. **A record that cannot be used refuses the whole resolution.** This is deliberately global
 *    rather than per-branch: the records on one path are written by one state machine, so a
 *    record that machine could not have produced means the path is not trustworthy about
 *    *anything*, not merely about the branch that tripped the check. The permissive alternative
 *    — ignore the damaged one — leaves the caller acting on a history with a hole in it.
 *
 * The fold over the matched records is [ClaudePSessionContinuationTransitions.fold]'s, and the
 * reasons it can give are its own; nothing here re-implements the transition table.
 */
object ClaudePSessionContinuationResolver {

    /**
     * The state of [branchId] for [assistantId], read from [nodes].
     *
     * The query identity is checked with the same shape rules the records are, because it is the
     * same kind of value: a blank assistant or a branch id that is not a 64-lowercase-hex digest
     * cannot match anything, and reporting that as "no history" would let a wiring defect look
     * like a fresh branch.
     */
    fun resolve(
        nodes: List<MessageNode>,
        assistantId: String,
        branchId: String,
    ): ClaudePSessionContinuationResolution {
        if (assistantId.isBlank()) {
            return ClaudePSessionContinuationResolution.Refused(
                ClaudePSessionContinuationTransitions.Refusal.BLANK_ASSISTANT_ID,
            )
        }
        if (!BRANCH_ID.matches(branchId)) {
            return ClaudePSessionContinuationResolution.Refused(
                ClaudePSessionContinuationTransitions.Refusal.MALFORMED_BRANCH_ID,
            )
        }

        val matched = ArrayList<ClaudePSessionContinuation>()

        for (node in nodes) {
            // An unreadable selection is refused rather than skipped: the node is where a record
            // would be, so skipping it would report a history that is missing a step as a history
            // that never had one.
            val selected = node.messages.getOrNull(node.selectIndex)
                ?: return ClaudePSessionContinuationResolution.Refused(
                    ClaudePSessionContinuationTransitions.Refusal.SELECTED_MESSAGE_UNREADABLE,
                )

            val record = selected.claudePSessionContinuation ?: continue

            // The shape check runs for every record on the path, including ones for other
            // branches. A damaged record anywhere on the path is evidence about the path.
            shapeRefusal(record)?.let {
                return ClaudePSessionContinuationResolution.Refused(it)
            }

            if (record.assistantId == assistantId && record.branchId == branchId) {
                matched += record
            }
        }

        return ClaudePSessionContinuationTransitions.fold(matched)
    }

    /**
     * [resolve] for a conversation's own assistant.
     *
     * The assistant comes from the conversation row rather than from the caller, because the
     * conversation is the authority for which assistant its branch belongs to — a caller that
     * passed one in would be able to name an assistant the graph does not agree with.
     */
    fun resolve(
        conversation: Conversation,
        branchId: String,
    ): ClaudePSessionContinuationResolution =
        resolve(conversation.messageNodes, conversation.assistantId.toString(), branchId)

    /**
     * The shape checks [ClaudePSessionContinuationTransitions.fold] applies, available before the
     * fold so a damaged record on the path refuses the whole resolution rather than only the
     * branch it happens to name.
     *
     * Kept as a delegation rather than a copy: a second spelling of these rules would be a second
     * answer to "is this record usable", and the two would drift.
     */
    private fun shapeRefusal(
        record: ClaudePSessionContinuation,
    ): ClaudePSessionContinuationTransitions.Refusal? =
        ClaudePSessionContinuationTransitions.fold(listOf(record))
            .let { it as? ClaudePSessionContinuationResolution.Refused }
            ?.reason

    /** The 64-lowercase-hex shape every branch identity has. */
    private val BRANCH_ID = Regex("^[0-9a-f]{64}$")
}

/**
 * The one place a continuation record is attached to a message.
 *
 * Writing the field directly at each call site would work, and would also be how two sites come
 * to disagree about the revision, or about which message may carry which state. Every write goes
 * through here so that "the revision advances by exactly one" and "the state matches the slot"
 * are properties of the API rather than rules a reviewer has to check three times.
 *
 * None of these functions persist anything. They return the value the caller must put into the
 * graph mutation it is already in the middle of, so that the record and the graph commit in one
 * transaction — see [ClaudePSessionContinuationState]'s contract for which write belongs to which
 * transaction.
 */
object ClaudePSessionContinuationWrite {

    /**
     * The revision a record written now should carry: one past the branch's current state, or
     * one when the branch has no record at all.
     *
     * Returns `null` for a refusal or a conflict, because there is no revision to advance — a
     * caller that reaches here with one of those has skipped the gate, and a number would let it
     * continue.
     */
    fun nextRevision(resolution: ClaudePSessionContinuationResolution): Long? =
        when (resolution) {
            ClaudePSessionContinuationResolution.UNINITIALIZED -> 1L
            is ClaudePSessionContinuationResolution.Resolved -> resolution.revision + 1L
            is ClaudePSessionContinuationResolution.Refused,
            is ClaudePSessionContinuationResolution.Conflicted,
                -> null
        }

    /**
     * Attaches [state] to [message], as the branch's next revision.
     *
     * `generationId` travels only where a bind obligation does: a pending bind needs it to be
     * replayable at all, an interrupted bind needs it to be replayed under the same identity, and
     * every other state that carried one would be claiming a bind it cannot honour.
     */
    fun record(
        message: UIMessage,
        assistantId: String,
        branchId: String,
        revision: Long,
        state: ClaudePSessionContinuationState,
        generationId: String? = null,
    ): UIMessage {
        require(revision > 0L) { "A continuation record needs a revision above zero" }
        require(generationId == null || generationId.isNotBlank()) {
            "A generation id is either present or absent, never empty"
        }
        return message.copy(
            claudePSessionContinuation = ClaudePSessionContinuation(
                assistantId = assistantId,
                branchId = branchId,
                revision = revision,
                state = state,
                generationId = generationId,
            ),
        )
    }
}
