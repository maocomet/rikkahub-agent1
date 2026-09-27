package me.rerere.rikkahub.data.claudep

import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.claudep.ClaudePSessionBranchId
import me.rerere.ai.provider.claudep.ClaudePSessionContinuationResolution
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.service.chat.CancelCurrentToolCommand
import me.rerere.rikkahub.service.chat.CancelQueuedCommand
import me.rerere.rikkahub.service.chat.CancelSteeringCommand
import me.rerere.rikkahub.service.chat.ChatCommand
import me.rerere.rikkahub.service.chat.ClearPendingQueueCommand
import me.rerere.rikkahub.service.chat.InterruptCommand
import me.rerere.rikkahub.service.chat.InterruptRegenerateCommand
import me.rerere.rikkahub.service.chat.PetDialogueCommand
import me.rerere.rikkahub.service.chat.PromoteQueuedMessageToSteeringCommand
import me.rerere.rikkahub.service.chat.RegenerateCommand
import me.rerere.rikkahub.service.chat.ResumeAfterApprovalCommand
import me.rerere.rikkahub.service.chat.ResumeQueueCommand
import me.rerere.rikkahub.service.chat.SendMessageCommand
import me.rerere.rikkahub.service.chat.SteerCommand
import me.rerere.rikkahub.service.chat.StopCommand
import me.rerere.rikkahub.service.chat.ToolApprovalCommand
import me.rerere.rikkahub.service.chat.UpdateQueuedMessageCommand

/**
 * Maps the authoritative message graph onto a Claude P continuation branch identity, and decides
 * whether that identity is knowable before the next model dispatch.
 *
 * ## Why the mapping lives here and not in the provider
 *
 * The provider is handed messages and a model and knows nothing about which node variant the user
 * is looking at. The conversation graph is the only authority for that, and it belongs to the app.
 * So the app computes the identity and hands it over; nothing downstream re-derives it.
 *
 * ## Why the classification is not a guess
 *
 * [Mode] is decided by what the *command* does to the graph, never by the provider's name, the
 * current tip, the most recent command, or a search for something that looks like a match:
 *
 * - A `SendMessageCommand` appends a **new node** whose `selectIndex` is `0`. A node at index 0 is
 *   not part of the selection vector, so the branch identity is already fully determined by the
 *   graph as it stands. It is [Mode.KNOWN_BEFORE_DISPATCH].
 * - A `RegenerateCommand` targeting an **assistant** message appends a new *variant* to that node
 *   and selects it, so the selection vector changes — but only once the variant is committed, which
 *   happens after the model returns. It is [Mode.NEW_DEFERRED_BIND]: the generation must start as a
 *   new session, and the binding is written afterwards against the committed graph.
 * - A `RegenerateCommand` targeting a **user** message truncates the nodes after the target and then
 *   appends a fresh assistant node at `selectIndex == 0`. The truncation is applied before dispatch,
 *   and the appended node is a default one, so the identity is again already determined.
 * - Commands that start no model generation at all — approval decisions, steering, queue
 *   maintenance, stops — are [Mode.NOT_MODEL_GENERATION]. They are not "known"; they have no branch
 *   to resolve, and saying otherwise would make a command that dispatched nothing look like a
 *   settled branch.
 *
 * Getting this wrong in the permissive direction is the dangerous one: treating a deferred case as
 * known would send the *old* branch identity with a generation that is about to create a new branch,
 * which is precisely the silent re-identification this design exists to prevent.
 *
 * That is also why [classify] has no `else`. The `when` is exhaustive over the sealed command
 * hierarchy, so a new command type is a **compile error** here and has to be classified
 * deliberately. A catch-all would silently answer for it, and the answer it would give is the
 * permissive one.
 */
object ClaudePSessionBranchPlanner {

    /**
     * When the branch identity for a generation becomes knowable.
     *
     * [KNOWN_BEFORE_DISPATCH] — the identity can be computed from the committed graph before the
     * model is asked anything, and carried on the request.
     *
     * [NEW_DEFERRED_BIND] — the identity depends on a variant this generation will only create if it
     * succeeds. The generation must run as a new session and the binding must be written afterwards,
     * against the committed graph. The candidate session is never resumable before that commit.
     */
    enum class Mode {
        KNOWN_BEFORE_DISPATCH,
        NEW_DEFERRED_BIND,
        /**
         * The command does not initiate a model generation at all, so it has no branch identity to
         * resolve. It is a separate answer rather than "known": a queue-maintenance or approval
         * command that were reported as [KNOWN_BEFORE_DISPATCH] would look like a generation whose
         * branch had been settled, and would hide the fact that nothing was ever dispatched.
         *
         * If this value ever reaches a model-dispatch branch during wiring, that is a wiring defect
         * and must fail closed.
         */
        NOT_MODEL_GENERATION,
    }

    /** Why the graph could not be described as a selection vector. A closed set. */
    enum class GraphRejection {
        /** A node with no messages cannot carry a selected variant. */
        EMPTY_NODE,
        /** `selectIndex` is negative or past the end of the node's variants. */
        SELECT_INDEX_OUT_OF_RANGE,
        /** The selected variant has no identity, or carries a blank one. */
        BLANK_SELECTED_MESSAGE_ID,
    }

    sealed interface Selections {
        data class Valid(val value: List<ClaudePSessionBranchId.Selection>) : Selections
        data class Rejected(val reason: GraphRejection) : Selections
    }

    /**
     * The selection vector of [nodes], in node order, or a rejection.
     *
     * Only nodes selecting a non-default variant are included. A node on `selectIndex == 0` is
     * described by the default and encoding it would make the identity depend on how many plain
     * messages precede the branch rather than on the branch itself.
     *
     * A graph that cannot be described is **rejected**, not partially encoded: dropping the
     * offending node would produce a well-formed digest of a graph that does not exist.
     */
    fun selectionsOf(nodes: List<MessageNode>): Selections {
        val selections = ArrayList<ClaudePSessionBranchId.Selection>(nodes.size)
        for (node in nodes) {
            if (node.messages.isEmpty()) return Selections.Rejected(GraphRejection.EMPTY_NODE)
            if (node.selectIndex !in node.messages.indices) {
                return Selections.Rejected(GraphRejection.SELECT_INDEX_OUT_OF_RANGE)
            }
            if (node.selectIndex == 0) continue
            val selected = node.messages[node.selectIndex].id.toString()
            if (selected.isBlank()) {
                return Selections.Rejected(GraphRejection.BLANK_SELECTED_MESSAGE_ID)
            }
            selections += ClaudePSessionBranchId.Selection(
                nodeId = node.id.toString(),
                selectedMessageId = selected,
            )
        }
        return Selections.Valid(selections)
    }

    /** The branch identity of a committed graph. */
    sealed interface Branch {
        data class Known(val id: String) : Branch
        /** The graph could not be described as a selection vector. */
        data class Rejected(val reason: GraphRejection) : Branch
        /** The graph described a selection vector that is not a legal one. */
        data class Malformed(val reason: ClaudePSessionBranchId.Reason) : Branch
    }

    /**
     * The branch identity of [nodes].
     *
     * The two failure vocabularies are kept distinct on purpose: [GraphRejection] says the graph
     * could not be described, while [ClaudePSessionBranchId.Reason] says the description was not a
     * legal selection vector. Both are fail-closed for a caller, but they are different defects and
     * collapsing one into the other would put a wrong reason in a log line — an empty node reported
     * as a blank identity sends the reader looking in the wrong place.
     */
    fun branchIdOf(nodes: List<MessageNode>): Branch =
        when (val selections = selectionsOf(nodes)) {
            is Selections.Rejected -> Branch.Rejected(selections.reason)
            is Selections.Valid -> when (val computed = ClaudePSessionBranchId.compute(selections.value)) {
                is ClaudePSessionBranchId.Result.Valid -> Branch.Known(computed.id)
                is ClaudePSessionBranchId.Result.Malformed -> Branch.Malformed(computed.reason)
            }
        }

    /**
     * When [command]'s branch identity becomes knowable.
     *
     * The default is **not** "known". A `RegenerateCommand` is only known when the target is
     * positively identified as a user message, because that is the case whose graph effect has been
     * read out of the code (truncate, then append a default node). Anything else — an assistant
     * target, or a target that could not be resolved at all — is deferred.
     *
     * Deferring an operation that would in fact have been knowable costs a new session. Claiming to
     * know one that is in fact deferred costs a generation running under the *old* branch identity
     * while the graph is about to move to a new one, which is the silent re-identification this
     * whole design exists to prevent. Those are not symmetric, so an unresolved target is not
     * treated as evidence of the harmless case.
     *
     * @param targetRole the role of the message a `RegenerateCommand` targets; `null` when the
     *   command has no target or the target is not in the committed graph. Read from the committed
     *   graph, never from the request.
     */
    fun classify(command: ChatCommand, targetRole: MessageRole?): Mode = when (command) {
        // A send appends a new node at the default index, so the branch it continues already
        // exists. An interrupt carries a SendMessageCommand and behaves identically.
        is SendMessageCommand -> classifySend()
        is InterruptCommand -> classifySend()

        // Both regenerate forms are classified by the same function. An InterruptRegenerateCommand
        // is a RegenerateCommand with an interrupt policy, not a second kind of operation, and it
        // must use the inner `regeneration` target — a copy of these rules is how the two would
        // silently drift apart.
        is RegenerateCommand -> classifyRegeneration(targetRole)
        is InterruptRegenerateCommand -> classifyRegeneration(targetRole)

        // The continuation after an approved tool runs inside the branch that opened the
        // generation: it resumes the existing assistant message and selects nothing new, so the
        // selection vector is already committed when it is admitted.
        is ResumeAfterApprovalCommand -> Mode.KNOWN_BEFORE_DISPATCH

        // Memory-only: rejected before any dispatch, never a model generation.
        is PetDialogueCommand -> Mode.NOT_MODEL_GENERATION

        // Terminal control. Cancels or halts work rather than starting any.
        is StopCommand -> Mode.NOT_MODEL_GENERATION
        is CancelCurrentToolCommand -> Mode.NOT_MODEL_GENERATION

        // Approval decisions. The decision itself dispatches nothing; the generation that follows
        // is a ResumeAfterApprovalCommand and is classified as such.
        is ToolApprovalCommand -> Mode.NOT_MODEL_GENERATION

        // Steering and queue maintenance. These reshape what a later command will do; none of them
        // is itself a model generation.
        is SteerCommand -> Mode.NOT_MODEL_GENERATION
        is ResumeQueueCommand -> Mode.NOT_MODEL_GENERATION
        is ClearPendingQueueCommand -> Mode.NOT_MODEL_GENERATION
        is CancelQueuedCommand -> Mode.NOT_MODEL_GENERATION
        is CancelSteeringCommand -> Mode.NOT_MODEL_GENERATION
        is UpdateQueuedMessageCommand -> Mode.NOT_MODEL_GENERATION
        is PromoteQueuedMessageToSteeringCommand -> Mode.NOT_MODEL_GENERATION
    }

    /**
     * The production entry point: what to send, and what to write, for [command] on
     * [conversation].
     *
     * This is the seam the dispatch path reads **before** it builds a request fingerprint,
     * because everything it returns travels in the request and §7 requires every carried field to
     * enter the fingerprint. Computing it later would let a request be fingerprinted as one shape
     * and sent as another.
     *
     * ## Where each field comes from
     *
     * - **The branch identity** is computed from the committed graph by [branchIdOf]. It is never
     *   read from the command, the envelope, or any remembered value: the graph is the only thing
     *   that knows which variant the user is looking at.
     * - **The assistant** is the conversation's own, never a caller's argument. A caller able to
     *   name an assistant the graph does not agree with could bind a branch to the wrong one.
     * - **Nothing else.** No session id, no config hash, no credential — the wire forbids them in
     *   both directions and the Server computes what it needs itself.
     *
     * ## The gate, for a branch that already exists
     *
     * An immediate plan is only produced when the branch's continuation resolution allows a new
     * generation — which is to say when the branch has no record at all, or when its binding is
     * already proven. Every other state refuses: a branch whose model may be running right now,
     * or whose bind is unconfirmed, must not quietly acquire a second generation, and a branch
     * that failed closed stays closed.
     *
     * A **deferred** plan is not gated on the current branch, and that is not a gap. The
     * generation it describes creates a *different* branch — the new variant — and the current
     * branch's state is not a claim about it. The concurrency question this looks like ("may
     * another generation run?") belongs to command authority, which is where it is answered.
     *
     * @param targetRole the role of the message a `RegenerateCommand` targets, read from the
     *   committed graph; `null` when the target is unresolved. See [classify].
     */
    fun plan(
        conversation: Conversation,
        command: ChatCommand,
        targetRole: MessageRole?,
    ): ClaudePSessionContinuationPlan {
        val assistantId = conversation.assistantId.toString()
        return when (classify(command, targetRole)) {
            Mode.NOT_MODEL_GENERATION ->
                ClaudePSessionContinuationPlan.NotModelGeneration(assistantId)

            Mode.NEW_DEFERRED_BIND -> ClaudePSessionContinuationPlan.Deferred(assistantId)

            Mode.KNOWN_BEFORE_DISPATCH -> {
                // The candidate graph — the one this command is about to commit — and not the one
                // the command was admitted against. See [graphAfterCommand].
                val candidate = graphAfterCommand(conversation, command)
                val branch = when (val described = branchIdOf(candidate.messageNodes)) {
                    is Branch.Known -> described.id
                    is Branch.Rejected -> return ClaudePSessionContinuationPlan.Refused(
                        assistantId,
                        ClaudePSessionContinuationPlan.Reason.BRANCH_NOT_DESCRIBABLE,
                    )

                    is Branch.Malformed -> return ClaudePSessionContinuationPlan.Refused(
                        assistantId,
                        ClaudePSessionContinuationPlan.Reason.BRANCH_MALFORMED,
                    )
                }

                when (
                    val resolution = ClaudePSessionContinuationResolver.resolve(
                        conversation = candidate,
                        branchId = branch,
                    )
                ) {
                    is ClaudePSessionContinuationResolution.Refused ->
                        ClaudePSessionContinuationPlan.Refused(
                            assistantId,
                            ClaudePSessionContinuationPlan.Reason.CONTINUATION_REFUSED,
                        )

                    is ClaudePSessionContinuationResolution.Conflicted ->
                        ClaudePSessionContinuationPlan.Refused(
                            assistantId,
                            ClaudePSessionContinuationPlan.Reason.CONTINUATION_CONFLICTED,
                        )

                    else -> if (!resolution.allowsNewGeneration) {
                        // START_IN_FLIGHT, BIND_PENDING, INTERRUPTED or FAILED_CLOSED: the branch
                        // is running, unproven, or closed. Refusing is the whole point — the
                        // alternative is a second generation for a branch that may already have
                        // one.
                        ClaudePSessionContinuationPlan.Refused(
                            assistantId,
                            ClaudePSessionContinuationPlan.Reason.CONTINUATION_BLOCKED,
                        )
                    } else {
                        // Non-null exactly when the gate above allowed it: the two states that
                        // permit a new generation are the two `nextRevision` answers, and a
                        // refusal or conflict never reaches here.
                        val revision = requireNotNull(
                            ClaudePSessionContinuationWrite.nextRevision(resolution),
                        ) { "A permitted generation must have a next revision" }
                        ClaudePSessionContinuationPlan.Immediate(
                            assistantId = assistantId,
                            branchId = branch,
                            revision = revision,
                        )
                    }
                }
            }
        }
    }

    /**
     * The graph [command] is about to commit, which is what its branch identity must describe.
     *
     * ## Why the admitted graph is the wrong operand
     *
     * A user-target regenerate **truncates**: `executeRegenerateInline` keeps the nodes up to and
     * including the target and drops everything after, then appends the assistant node this
     * generation produces. The appended node sits at `selectIndex == 0` and so contributes nothing
     * to the selection vector, which means the committed branch is exactly the identity of the
     * **truncated** graph.
     *
     * Computing that identity from the graph as admitted — the whole conversation, suffix included —
     * gives the same answer only when every dropped node happened to sit at `selectIndex == 0`. When
     * any later node has a selected non-default variant, the two differ, and the difference is sent
     * on the wire as `remote_branch_id`. That is not a mismatch that announces itself: the Server
     * accepts the digest, resolves a session for a branch that is not the one about to exist, and
     * the app records a binding against the same wrong identity. It is precisely the silent
     * re-identification this whole layer exists to prevent, so the identity is derived from the
     * candidate graph and from nothing else.
     *
     * ## Why it lives here
     *
     * [classify] already encodes what each command does to the graph — that truncation is *why* a
     * user regenerate is `KNOWN_BEFORE_DISPATCH` at all. Putting the graph effect anywhere else
     * would be a second copy of that knowledge, and the two copies would disagree the first time a
     * command changed. This function is the executable form of the sentence in [classify]'s doc.
     *
     * Every other command leaves the graph as it is: a send appends a default node, an interrupt
     * carries a send, and an approval continuation selects nothing new.
     */
    fun graphAfterCommand(conversation: Conversation, command: ChatCommand): Conversation {
        val targetId = when (command) {
            is RegenerateCommand -> command.targetMessageId
            is InterruptRegenerateCommand -> command.regeneration.targetMessageId
            else -> return conversation
        }
        // Only a *user* target truncates. An assistant-target regenerate appends a variant to the
        // node it targets and selects it, which is the deferred path — it has no admission-time
        // identity to get wrong, and truncating here would delete the very node it appends to.
        val nodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { message -> message.id == targetId && message.role == MessageRole.USER }
        }
        if (nodeIndex < 0) return conversation
        if (nodeIndex == conversation.messageNodes.lastIndex) return conversation
        return conversation.copy(messageNodes = conversation.messageNodes.subList(0, nodeIndex + 1))
    }

    /** A send creates no variant, so its branch is always already committed. */
    private fun classifySend(): Mode = Mode.KNOWN_BEFORE_DISPATCH

    /**
     * The one regenerate rule, shared by both regenerate commands.
     *
     * Only a positive identification of a **user** target is known: that is the case whose graph
     * effect has been read out of the code (truncate after the target, then append a default node).
     * An assistant target appends and selects a variant, and an unresolved target is not evidence of
     * the harmless case either.
     */
    private fun classifyRegeneration(targetRole: MessageRole?): Mode =
        if (targetRole == MessageRole.USER) Mode.KNOWN_BEFORE_DISPATCH else Mode.NEW_DEFERRED_BIND
}
