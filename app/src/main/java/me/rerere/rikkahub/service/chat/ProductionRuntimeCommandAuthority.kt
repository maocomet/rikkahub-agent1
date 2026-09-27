package me.rerere.rikkahub.service.chat

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import me.rerere.rikkahub.data.ai.GenerationRunControl
import me.rerere.rikkahub.data.authority.source.ConversationSourceScopeResolver
import me.rerere.rikkahub.data.authority.transaction.ApprovalBarrierAuthorityMutation
import me.rerere.rikkahub.data.authority.transaction.ApprovalBarrierAuthorityReceipt
import me.rerere.rikkahub.data.authority.transaction.CommandAdmissionAuthorityCoordinator
import me.rerere.rikkahub.data.authority.transaction.CommandAdmissionAuthorityDraft
import me.rerere.rikkahub.data.authority.transaction.CommandFinalAuthorityRequest
import me.rerere.rikkahub.data.authority.transaction.CommandStateAdmissionAuthorityAdapter
import me.rerere.rikkahub.data.authority.transaction.ConversationGraphAuthorityMutation
import me.rerere.rikkahub.data.authority.transaction.FinalConversationAuthorityCoordinator
import me.rerere.rikkahub.data.authority.transaction.FinalResultAuthorityMutation
import me.rerere.rikkahub.data.authority.transaction.WaitingApprovalAuthorityCoordinator
import me.rerere.rikkahub.data.execution.ExecutionMessageAuthorityBinder
import me.rerere.rikkahub.data.execution.ExecutionOwningMessageAuthority
import me.rerere.rikkahub.data.repository.ConversationRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/** Production adapter that keeps every P1 authority decision in the owning AppDatabase tx. */
class ProductionRuntimeCommandAuthority(
    private val conversations: ConversationRepository,
    private val admissionGraphs: RuntimeCommandAdmissionGraphProvider,
    private val admission: CommandAdmissionAuthorityCoordinator,
    private val admissionAdapter: CommandStateAdmissionAuthorityAdapter,
    private val waiting: WaitingApprovalAuthorityCoordinator,
    private val final: FinalConversationAuthorityCoordinator,
    private val executionMessages: ExecutionMessageAuthorityBinder,
) : RuntimeCommandAuthority {
    private val commandScopes = ConcurrentHashMap<Uuid, me.rerere.rikkahub.data.authority.source.ConversationSourceScope>()

    override suspend fun admit(
        envelope: CommandEnvelope<out ChatCommand>,
        encodedDraft: me.rerere.rikkahub.data.db.entity.PendingChatCommandEntity,
        authoritySubjectId: String?,
    ): RuntimeAuthorityAdmissionResult {
        val graph = admissionGraphs.prepare(envelope, authoritySubjectId)
        require(graph.conversation.id == envelope.conversationId)
        require(graph.branchAnchorMessageId == envelope.lineage?.branchAnchorMessageId)
        val lineage = requireNotNull(envelope.lineage)
        val exactRevision = if (lineage.branchAnchorMessageRevision != null) {
            lineage.branchAnchorMessageRevision
        } else {
            admission.activeAnchorRevision(
                scope = graph.scope,
                messageId = graph.branchAnchorMessageId.toString(),
            ) ?: graph.branchAnchorMessageRevision
        }
        val exactDraft = encodedDraft.copy(branchAnchorMessageRevision = exactRevision)
        val commit = admission.admit(
            command = CommandAdmissionAuthorityDraft(
                commandId = envelope.id.toString(),
                conversationId = envelope.conversationId.toString(),
                assistantIdSnapshot = lineage.assistantIdSnapshot.toString(),
                authoritySubjectId = authoritySubjectId,
                lineageId = lineage.lineageId.toString(),
                parentCommandId = lineage.parentCommandId?.toString(),
                branchAnchorMessageId = graph.branchAnchorMessageId.toString(),
                branchAnchorMessageRevision = exactRevision,
            ),
            graphMutation = graph.mutation(),
            commandMutation = admissionAdapter.mutation(exactDraft),
        )
        commandScopes[envelope.id] = graph.scope
        val result = if (commit.command.duplicate) {
            DurableSubmitResult.AlreadyExists(envelope.id)
        } else {
            DurableSubmitResult.Inserted(envelope.id)
        }
        return RuntimeAuthorityAdmissionResult(
            result = result,
            branchAnchorMessageRevision = commit.source.requireActiveMessage(
                graph.branchAnchorMessageId.toString(),
                expectedRole = "USER",
            ).sourceRevision,
        )
    }

    override suspend fun attachRun(
        envelope: CommandEnvelope<out ChatCommand>,
        lease: RuntimeAuthorityLease,
        control: GenerationRunControl,
        authoritySubjectId: String?,
    ) {
        val scope = ConversationSourceScopeResolver.forCommand(
            assistantIdSnapshot = requireNotNull(envelope.lineage).assistantIdSnapshot.toString(),
            authoritySubjectId = authoritySubjectId,
        )
        commandScopes.putIfAbsent(envelope.id, scope)
        control.attachRuntimeCommandAuthority(
            ProductionRuntimeRunAuthority(
                    envelope = envelope,
                    scope = scope,
                    conversations = conversations,
                    waiting = waiting,
                    final = final,
                    executionMessages = executionMessages,
                    lease = lease,
            ),
        )
    }

    override suspend fun finishUnclaimed(
        envelope: CommandEnvelope<out ChatCommand>,
        terminalState: DurableCommandState,
        errorCode: String?,
    ): Boolean {
        val scope = commandScopes.remove(envelope.id) ?: return false
        val conversation = conversations.getConversationById(envelope.conversationId) ?: return false
        val kind = when (terminalState) {
            DurableCommandState.COMPLETED -> CommandCompletionKind.CONTROL_ONLY
            DurableCommandState.CANCELLED -> if (errorCode == "SUPERSEDED_REGENERATE") {
                CommandCompletionKind.SUPERSEDED_REGENERATE
            } else {
                CommandCompletionKind.CENSORED_CANCELLED
            }
            else -> CommandCompletionKind.FAILED_OTHER
        }
        final.finishUnclaimed(
            commandId = envelope.id.toString(),
            conversationId = envelope.conversationId.toString(),
            terminalState = terminalState,
            completionKind = kind,
            errorCode = errorCode,
            graphMutation = ConversationGraphAuthorityMutation {
                conversations.persistAuthorityGraphInCurrentTransaction(
                    conversation = conversation,
                    scope = scope,
                    insert = false,
                )
            },
        )
        return true
    }

    override fun adoptRestored(
        envelope: CommandEnvelope<out ChatCommand>,
        authoritySubjectId: String?,
    ) {
        val lineage = requireNotNull(envelope.lineage) { "restored_command_lineage_required" }
        commandScopes.putIfAbsent(
            envelope.id,
            ConversationSourceScopeResolver.forCommand(
                assistantIdSnapshot = lineage.assistantIdSnapshot.toString(),
                authoritySubjectId = authoritySubjectId,
            ),
        )
    }

    override fun release(commandIds: Collection<Uuid>) {
        commandIds.forEach(commandScopes::remove)
    }


    private fun RuntimeCommandAdmissionGraph.mutation() = ConversationGraphAuthorityMutation {
        conversations.persistAuthorityGraphInCurrentTransaction(
            conversation = conversation,
            scope = scope,
            insert = null,
            sourceInvalidationMode = sourceInvalidationMode,
            sourceInvalidationNowMs = occurredAtMs,
        )
    }
}

private class ProductionRuntimeRunAuthority(
    private val envelope: CommandEnvelope<out ChatCommand>,
    private val scope: me.rerere.rikkahub.data.authority.source.ConversationSourceScope,
    private val conversations: ConversationRepository,
    private val waiting: WaitingApprovalAuthorityCoordinator,
    private val final: FinalConversationAuthorityCoordinator,
    private val executionMessages: ExecutionMessageAuthorityBinder,
    private val lease: RuntimeAuthorityLease,
) : RuntimeRunAuthority {
    private val mutationMutex = Mutex()
    private val waitingCommitted = AtomicBoolean(false)
    private val terminalCommitted = AtomicBoolean(false)
    private val terminalized = AtomicReference<List<Uuid>>(emptyList())

    override suspend fun checkpointWaiting(
        conversation: me.rerere.rikkahub.data.model.Conversation,
        assistantMessageId: Uuid,
        approvalMutation: suspend (String, Long) -> Unit,
        occurredAtMs: Long,
    ) {
        mutationMutex.withLock {
            val commit = requireNotNull(
                lease.mutateWithCurrentClaim { claim ->
                    waiting.checkpoint(
                        claim = claim,
                        ownerCommandId = envelope.id.toString(),
                        assistantMessageId = assistantMessageId.toString(),
                        graphMutation = graphMutation(conversation, occurredAtMs),
                        approvalMutation = ApprovalBarrierAuthorityMutation { assistant ->
                            approvalMutation(assistant.messageId, assistant.messageRevision)
                            ApprovalBarrierAuthorityReceipt()
                        },
                    )
                },
            ) { "runtime_authority_claim_missing" }
            check(commit.command.state == DurableCommandState.WAITING_APPROVAL)
            waitingCommitted.set(true)
        }
    }

    override suspend fun finish(
        conversation: me.rerere.rikkahub.data.model.Conversation,
        terminalState: DurableCommandState,
        kind: RuntimeAuthorityTerminalKind,
        resultAssistantMessageId: Uuid?,
        errorCode: String?,
        executionIds: Collection<String>,
        sourceInvalidationMode: me.rerere.rikkahub.data.repository.ConversationSourceInvalidationMode,
        occurredAtMs: Long,
    ) {
        finishWithMutation(
            graphMutation = graphMutation(conversation, occurredAtMs, sourceInvalidationMode),
            terminalState = terminalState,
            kind = kind,
            resultAssistantMessageId = resultAssistantMessageId,
            errorCode = errorCode,
            executionIds = executionIds,
        )
    }

    /**
     * The shared body of every result-less-or-result-bearing terminal that persists a graph.
     *
     * [graphMutation] is a parameter rather than a conversation so that the caller decides *when*
     * the conversation is read. [finish] captured it before the transaction; the generation
     * sibling below reads it inside, because a settlement has to be applied to whatever the
     * transaction is actually about to persist rather than to a snapshot taken beside it.
     */
    private suspend fun finishWithMutation(
        graphMutation: ConversationGraphAuthorityMutation,
        terminalState: DurableCommandState,
        kind: RuntimeAuthorityTerminalKind,
        resultAssistantMessageId: Uuid?,
        errorCode: String?,
        executionIds: Collection<String>,
    ) {
        mutationMutex.withLock {
            if (terminalCommitted.get()) return@withLock
            val completionKind = when (kind) {
                RuntimeAuthorityTerminalKind.GENERATION_FINAL_SAVED ->
                    CommandCompletionKind.GENERATION_FINAL_SAVED
                RuntimeAuthorityTerminalKind.FAST_PATH_HANDLED ->
                    CommandCompletionKind.FAST_PATH_HANDLED
                RuntimeAuthorityTerminalKind.CONTROL_ONLY -> CommandCompletionKind.CONTROL_ONLY
                RuntimeAuthorityTerminalKind.CENSORED_CANCELLED ->
                    CommandCompletionKind.CENSORED_CANCELLED
                RuntimeAuthorityTerminalKind.SUPERSEDED_REGENERATE ->
                    CommandCompletionKind.SUPERSEDED_REGENERATE
                RuntimeAuthorityTerminalKind.FAILED_OTHER -> CommandCompletionKind.FAILED_OTHER
            }
            val commit = requireNotNull(
                lease.mutateWithCurrentClaim { claim ->
                    final.finish(
                        request = CommandFinalAuthorityRequest(
                            claim = claim,
                            commandId = envelope.id.toString(),
                            conversationId = envelope.conversationId.toString(),
                            terminalState = terminalState,
                            completionKind = completionKind,
                            resultAssistantMessageId = resultAssistantMessageId?.toString(),
                            errorCode = errorCode,
                            terminalizeWaitingLineage = envelope.command is ResumeAfterApprovalCommand,
                        ),
                        graphMutation = graphMutation,
                        resultMutation = FinalResultAuthorityMutation { assistant ->
                            if (executionIds.isNotEmpty()) {
                                val exact = requireNotNull(assistant) {
                                    "execution_result_message_required"
                                }
                                executionMessages.requireBoundInCurrentAuthorityTransaction(
                                    executionIds.map { executionId ->
                                        ExecutionOwningMessageAuthority(
                                            executionId = executionId,
                                            assistantMessageId = exact.messageId,
                                            assistantMessageRevision = exact.messageRevision,
                                        )
                                    },
                                )
                            }
                        },
                    )
                },
            ) { "runtime_authority_claim_missing" }
            terminalized.set(commit.command.terminalizedCommandIds.map(Uuid::parse))
            terminalCommitted.set(true)
        }
    }

    /**
     * Ends a dispatched generation that produced no result assistant message, writing whatever the
     * run owes into the graph **in the same transaction** that terminalises the command.
     *
     * The conversation is read inside the transaction and handed to [settlement], so the record is
     * applied to what is actually about to be persisted. A settlement that refuses — a missing or
     * ambiguous target, a graph that cannot carry the record — throws, which rolls the transaction
     * back and leaves both the command and the graph untouched. A run that cannot record what it
     * owes must not be durable as finished.
     *
     * With no settlement the run owes nothing and this is [finishFallback], which is the path every
     * non-Claude-P generation takes.
     */
    override suspend fun finishGenerationWithoutResult(
        terminalState: DurableCommandState,
        errorCode: String?,
        settlement: GenerationTerminalGraphSettlement?,
    ) {
        if (settlement == null) {
            finishFallback(terminalState, errorCode)
            return
        }
        finishWithMutation(
            graphMutation = ConversationGraphAuthorityMutation {
                val current = requireNotNull(conversations.getConversationById(envelope.conversationId)) {
                    "runtime_authority_conversation_missing"
                }
                val settled = requireNotNull(settlement.settle(current, terminalState)) {
                    "generation_terminal_settlement_refused"
                }
                conversations.persistAuthorityGraphInCurrentTransaction(
                    conversation = settled,
                    scope = scope,
                    insert = null,
                    sourceInvalidationMode =
                        me.rerere.rikkahub.data.repository.ConversationSourceInvalidationMode.APPLY,
                    sourceInvalidationNowMs = System.currentTimeMillis(),
                )
            },
            terminalState = terminalState,
            kind = terminalKindOf(terminalState, errorCode),
            resultAssistantMessageId = null,
            errorCode = errorCode,
            executionIds = emptyList(),
        )
    }

    override suspend fun finishAfterFinalSaveFailure(errorCode: String) {
        mutationMutex.withLock {
            if (terminalCommitted.get()) return@withLock
            val receipt = requireNotNull(
                lease.mutateWithCurrentClaim { claim ->
                    final.finishAfterFinalSaveFailure(
                        claim = claim,
                        commandId = envelope.id.toString(),
                        conversationId = envelope.conversationId.toString(),
                        errorCode = errorCode,
                        terminalizeWaitingLineage = envelope.command is ResumeAfterApprovalCommand,
                    )
                },
            ) { "runtime_authority_claim_missing" }
            terminalized.set(receipt.terminalizedCommandIds.map(Uuid::parse))
            terminalCommitted.set(true)
        }
    }

    override suspend fun finishFallback(
        terminalState: DurableCommandState,
        errorCode: String?,
    ) {
        val kind = terminalKindOf(terminalState, errorCode)
        finish(
            conversation = requireNotNull(conversations.getConversationById(envelope.conversationId)) {
                "runtime_authority_conversation_missing"
            },
            terminalState = terminalState,
            kind = kind,
            resultAssistantMessageId = null,
            errorCode = errorCode,
        )
    }

    /**
     * The terminal kind a durable state and error code describe, in one place.
     *
     * Shared by [finishFallback] and [finishGenerationWithoutResult] so the two cannot come to
     * disagree about what a cancellation is called — which would show up as two different
     * completion kinds for the same event depending on whether the run owed a graph write.
     */
    private fun terminalKindOf(
        terminalState: DurableCommandState,
        errorCode: String?,
    ): RuntimeAuthorityTerminalKind = when (terminalState) {
        DurableCommandState.COMPLETED -> RuntimeAuthorityTerminalKind.CONTROL_ONLY
        DurableCommandState.CANCELLED -> if (errorCode == "SUPERSEDED_REGENERATE") {
            RuntimeAuthorityTerminalKind.SUPERSEDED_REGENERATE
        } else {
            RuntimeAuthorityTerminalKind.CENSORED_CANCELLED
        }
        else -> RuntimeAuthorityTerminalKind.FAILED_OTHER
    }

    override fun isWaitingCommitted(): Boolean = waitingCommitted.get()
    override fun isTerminalCommitted(): Boolean = terminalCommitted.get()
    override fun terminalizedCommandIds(): List<Uuid> = terminalized.get()

    private fun graphMutation(
        conversation: me.rerere.rikkahub.data.model.Conversation,
        occurredAtMs: Long,
        invalidationMode: me.rerere.rikkahub.data.repository.ConversationSourceInvalidationMode =
            me.rerere.rikkahub.data.repository.ConversationSourceInvalidationMode.APPLY,
    ) = ConversationGraphAuthorityMutation {
        conversations.persistAuthorityGraphInCurrentTransaction(
            conversation = conversation,
            scope = scope,
            insert = null,
            sourceInvalidationMode = invalidationMode,
            sourceInvalidationNowMs = occurredAtMs,
        )
    }
}
