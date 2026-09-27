package me.rerere.rikkahub.service.chat

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.authority.source.ConversationSourceScope
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationSourceInvalidationMode
import kotlin.uuid.Uuid

/** Exact graph prepared before a durable command can become visible to a runtime worker. */
data class RuntimeCommandAdmissionGraph(
    val conversation: Conversation,
    val scope: ConversationSourceScope,
    val branchAnchorMessageId: Uuid,
    val branchAnchorMessageRevision: Long = 1L,
    val sourceInvalidationMode: ConversationSourceInvalidationMode =
        ConversationSourceInvalidationMode.APPLY,
    val occurredAtMs: Long = System.currentTimeMillis(),
)

data class RuntimeAuthorityAdmissionResult(
    val result: DurableSubmitResult,
    val branchAnchorMessageRevision: Long,
)

fun interface RuntimeCommandAdmissionGraphProvider {
    suspend fun prepare(
        envelope: CommandEnvelope<out ChatCommand>,
        authoritySubjectId: String?,
    ): RuntimeCommandAdmissionGraph
}

/** Production combined-authority seam; tests may omit it and retain the legacy queue path. */
interface RuntimeCommandAuthority {
    suspend fun admit(
        envelope: CommandEnvelope<out ChatCommand>,
        encodedDraft: me.rerere.rikkahub.data.db.entity.PendingChatCommandEntity,
        authoritySubjectId: String?,
    ): RuntimeAuthorityAdmissionResult

    suspend fun attachRun(
        envelope: CommandEnvelope<out ChatCommand>,
        lease: RuntimeAuthorityLease,
        control: me.rerere.rikkahub.data.ai.GenerationRunControl,
        authoritySubjectId: String?,
    )

    suspend fun finishUnclaimed(
        envelope: CommandEnvelope<out ChatCommand>,
        terminalState: DurableCommandState,
        errorCode: String?,
    ): Boolean

    /** Reconstructs the frozen scope for a restored v2 row before it acquires a run claim. */
    fun adoptRestored(envelope: CommandEnvelope<out ChatCommand>, authoritySubjectId: String?)

    fun release(commandIds: Collection<Uuid>)

}

interface RuntimeAuthorityResult {
    fun isWaitingCommitted(): Boolean
    fun isTerminalCommitted(): Boolean
    fun terminalizedCommandIds(): List<Uuid>
}

interface RuntimeAuthorityLease {
    suspend fun <T> mutateWithCurrentClaim(block: suspend (CommandClaim) -> T): T?
}

enum class RuntimeAuthorityTerminalKind {
    GENERATION_FINAL_SAVED,
    FAST_PATH_HANDLED,
    CONTROL_ONLY,
    CENSORED_CANCELLED,
    SUPERSEDED_REGENERATE,
    FAILED_OTHER,
}

interface RuntimeRunAuthority : RuntimeAuthorityResult {
    suspend fun checkpointWaiting(
        conversation: Conversation,
        assistantMessageId: Uuid,
        approvalMutation: suspend (assistantMessageId: String, assistantRevision: Long) -> Unit = { _, _ -> },
        occurredAtMs: Long = System.currentTimeMillis(),
    )

    suspend fun finish(
        conversation: Conversation,
        terminalState: DurableCommandState,
        kind: RuntimeAuthorityTerminalKind,
        resultAssistantMessageId: Uuid?,
        errorCode: String? = null,
        executionIds: Collection<String> = emptyList(),
        sourceInvalidationMode: ConversationSourceInvalidationMode =
            ConversationSourceInvalidationMode.APPLY,
        occurredAtMs: Long = System.currentTimeMillis(),
    )

    suspend fun finishAfterFinalSaveFailure(errorCode: String = "FINAL_SAVE_FAILED")

    /**
     * Fences a claimed command that returned before a command-specific coordinator ran. This is
     * intentionally result-less: generation paths must use [finish] with an exact assistant pair,
     * or [finishGenerationWithoutResult] when they have a graph obligation but no pair.
     *
     * Unchanged by the generation sibling below, and deliberately so: the control and dispatch
     * paths that use it have no graph obligation, and giving this method a conversation would make
     * every one of them capable of writing a graph none of them owns.
     */
    suspend fun finishFallback(
        terminalState: DurableCommandState,
        errorCode: String?,
    )

    /**
     * Ends a run that **dispatched a model generation**, produced **no result assistant message**,
     * and owes a graph settlement.
     *
     * The sibling of [finishFallback], and the only one of the two that may touch the graph:
     *
     * - `resultAssistantMessageId` is `null`, because there is no result to name;
     * - [settlement] is read **inside** the command's authority transaction, applied to the
     *   conversation read there, and persisted by the same transaction that terminalises the
     *   command — so the graph and the command cannot disagree about whether the turn ended;
     * - a `null` return from the settlement, a missing or ambiguous target, or any failure while
     *   applying it **fails the command** rather than completing it. A run that cannot record what
     *   it owes must not be durable as finished.
     *
     * `null` settlement means the run attached none, and is equivalent to [finishFallback]: the
     * non-Claude-P paths attach nothing and keep their behaviour byte for byte.
     */
    suspend fun finishGenerationWithoutResult(
        terminalState: DurableCommandState,
        errorCode: String?,
        settlement: GenerationTerminalGraphSettlement?,
    )
}

/** Run-local attachment. It is never serialized or included in a provider request. */
class RuntimeRunAuthoritySlot {
    private val mutex = Mutex()
    private var authority: RuntimeRunAuthority? = null

    suspend fun attach(value: RuntimeRunAuthority) = mutex.withLock {
        check(authority == null) { "runtime_authority_already_attached" }
        authority = value
    }

    suspend fun current(): RuntimeRunAuthority? = mutex.withLock { authority }
}
