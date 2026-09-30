package me.rerere.rikkahub.service

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.seconds
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.FinalAnswerRecoveryStatus
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.UIMessageState
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.ai.ui.finishPendingTools
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.assistant.SecondUserAuthorityRegistry
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.GenerationPersistenceBarrier
import me.rerere.rikkahub.data.ai.resolveInteractiveGenerationMaxSteps
import me.rerere.rikkahub.data.ai.resolveInteractiveGenerationTurnBudgetMs
import me.rerere.rikkahub.data.ai.sanitizeTransientConversationToolResults
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.tools.WebSearchPolicy
import me.rerere.rikkahub.data.ai.tools.createConversationTools
import me.rerere.rikkahub.data.ai.tools.ordinaryConversationToolNames
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.ai.tools.createSkillTools
import me.rerere.rikkahub.data.ai.tools.createWorkspaceTools
import me.rerere.rikkahub.data.execution.ApprovalContinuationMode
import me.rerere.rikkahub.data.execution.isInFlightContinuation
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.WorkspaceReminderTransformer
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.DEFAULT_AUTO_MODEL_ID
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.datastore.getChatModelForAssistant
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.ConversationSourceInvalidationMode
import me.rerere.rikkahub.data.repository.selectedMemorySourceVersions
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.MemoryRetrievalDiagnosticsStore
import me.rerere.rikkahub.data.repository.MemoryRetrievalQuerySource
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.diagnostics.agenttiming.AgentTimingEventKind
import me.rerere.rikkahub.diagnostics.agenttiming.AgentTimingEventResult
import me.rerere.rikkahub.diagnostics.agenttiming.AgentTimingHandle
import me.rerere.rikkahub.diagnostics.agenttiming.AgentTimingStore
import me.rerere.rikkahub.diagnostics.agenttiming.AgentTimingSubmissionToken
import me.rerere.rikkahub.diagnostics.agenttiming.AgentTimingTraceStatus
import me.rerere.rikkahub.diagnostics.agenttiming.hasAgentTimingRenderableContent
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.utils.applyPlaceholders
import me.rerere.rikkahub.utils.sendNotification
import me.rerere.rikkahub.utils.cancelNotification
import me.rerere.workspace.WorkspaceShellStatus
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.ai.GenerationRunControl
import me.rerere.rikkahub.data.ai.tools.CancelRequestResult
import me.rerere.rikkahub.data.ai.tools.ToolCancelReason
import me.rerere.rikkahub.service.chat.ChatCommand
import me.rerere.rikkahub.service.chat.PetDialogueCommand
import me.rerere.rikkahub.service.chat.CancelCurrentToolCommand
import me.rerere.rikkahub.service.chat.CommandEnvelope
import me.rerere.rikkahub.service.chat.CommandOrigin
import me.rerere.rikkahub.service.chat.CommandOutcome
import me.rerere.rikkahub.service.chat.ConversationRuntime
import me.rerere.rikkahub.service.chat.InterruptCommand
import me.rerere.rikkahub.service.chat.InterruptRegenerateCommand
import me.rerere.rikkahub.service.chat.PersistenceCoordinator
import me.rerere.rikkahub.service.chat.RawUserContent
import me.rerere.rikkahub.service.chat.RuntimeCommandExecutor
import me.rerere.rikkahub.service.chat.RuntimeHydrator
import me.rerere.rikkahub.service.chat.SendMessageCommand
import me.rerere.rikkahub.service.chat.StopCommand
import me.rerere.rikkahub.service.chat.SteerCommand
import me.rerere.rikkahub.service.chat.SteeringScope
import me.rerere.rikkahub.service.chat.StableCommandException
import me.rerere.rikkahub.service.chat.SubmitResult
import me.rerere.rikkahub.service.chat.ToolApprovalCommand
import me.rerere.rikkahub.service.chat.ToolDecision
import me.rerere.rikkahub.service.chat.NormalCommand
import me.rerere.rikkahub.service.chat.RegenerateCommand
import me.rerere.rikkahub.memory.MemorySourceVersion
import me.rerere.rikkahub.service.chat.RunOutcome
import me.rerere.rikkahub.service.chat.DispatcherProvider
import me.rerere.rikkahub.service.chat.DurableCommandQueue
import me.rerere.rikkahub.service.chat.EmergencyCommand
import me.rerere.rikkahub.service.chat.FastPathContext
import me.rerere.rikkahub.service.chat.FastPathDecision
import me.rerere.rikkahub.service.chat.FastPathRouter
import me.rerere.rikkahub.service.chat.FastPathCommitPlan
import me.rerere.rikkahub.service.chat.buildFastPathCommitPlan
import me.rerere.rikkahub.service.chat.toAnchoredUserMessage
import me.rerere.rikkahub.service.chat.ResumeAfterApprovalCommand
import me.rerere.rikkahub.service.chat.ResumeQueueCommand
import me.rerere.rikkahub.service.chat.ClearPendingQueueCommand
import me.rerere.rikkahub.service.chat.CancelQueuedCommand
import me.rerere.rikkahub.service.chat.CancelSteeringCommand
import me.rerere.rikkahub.service.chat.UpdateQueuedMessageCommand
import me.rerere.rikkahub.service.chat.PromoteQueuedMessageToSteeringCommand
import me.rerere.rikkahub.service.chat.QueuedMessageUiEntry
import me.rerere.rikkahub.subagent.allowsTool
import me.rerere.rikkahub.subagent.generationMaxSteps

private const val TAG = "ChatService"
private const val FAST_PATH_TOOL_BUDGET_MS = 30_000L

internal fun backgroundTextGenerationParams(
    model: Model,
    reasoningLevel: ReasoningLevel = ReasoningLevel.OFF,
): TextGenerationParams = TextGenerationParams(
    model = model,
    reasoningLevel = reasoningLevel,
    // Compression/title/suggestion calls are non-interactive. On generic OpenAI-compatible
    // gateways, an explicit disabled reasoning field (for example `reasoning_effort: low`) can
    // itself be rejected with HTTP 400. Keep ordinary chat behavior unchanged, but omit it here.
    omitReasoningConfigurationWhenOff = true,
    customHeaders = model.customHeaders,
    customBody = model.customBodies,
)

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val solution: ChatErrorSolution? = null,
)

internal data class TrackedCommandSubmission(
    val submission: SubmitResult,
    val outcome: Deferred<CommandOutcome>,
)

internal data class DurableRegenerationBaseline(
    val assistantScopeId: String,
    val selectedMessageIds: List<String>,
    val selectedSourceVersions: List<MemorySourceVersion>,
)

internal fun ChatCommand.durableRegenerationBaselineOrNull(): DurableRegenerationBaseline? {
    val regeneration = when (this) {
        is RegenerateCommand -> this
        is InterruptRegenerateCommand -> this.regeneration
        else -> return null
    }
    val assistantScopeId = regeneration.baselineAssistantScopeId
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: return null
    val selectedSourceVersions = normalizeDurableSourceVersions(
        regeneration.baselineSelectedSourceVersions,
    )
    val selectedMessageIds = (regeneration.baselineSelectedMessageIds.asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        + selectedSourceVersions.asSequence().map(MemorySourceVersion::messageId))
        .distinct()
        .sorted()
        .toList()
        .takeIf { it.isNotEmpty() }
        ?: return null
    return DurableRegenerationBaseline(
        assistantScopeId = assistantScopeId,
        selectedMessageIds = selectedMessageIds,
        selectedSourceVersions = selectedSourceVersions,
    )
}

private fun normalizeDurableSourceVersions(
    versions: Collection<MemorySourceVersion>,
): List<MemorySourceVersion> = versions.asSequence()
    .map { version ->
        MemorySourceVersion(
            messageId = version.messageId.trim(),
            consumedTextDigest = version.consumedTextDigest.trim().lowercase(),
        )
    }
    .filter { version ->
        version.messageId.isNotEmpty() &&
            version.consumedTextDigest.length == 64 &&
            version.consumedTextDigest.all { char -> char in '0'..'9' || char in 'a'..'f' }
    }
    .distinct()
    .sortedWith(compareBy(MemorySourceVersion::messageId, MemorySourceVersion::consumedTextDigest))
    .toList()

private data class DeferredGenerationPostCommit(
    val conversationId: Uuid,
    val commandOrigin: CommandOrigin,
    val toolOrigin: ToolCallOrigin,
    val assistant: Assistant,
    val conversation: Conversation,
    val isSubAgent: Boolean,
)

private fun rejectedTrackedCommand(reason: String) = TrackedCommandSubmission(
    submission = SubmitResult.Rejected(reason),
    outcome = CompletableDeferred(CommandOutcome.Rejected(reason)),
)

data class ChatEmergencyStopResult(
    val runtimeCount: Int,
    val stoppedRuntimeCount: Int,
    val clearedQueueCount: Int,
    val failures: Map<String, String> = emptyMap(),
) {
    val ok: Boolean get() = failures.isEmpty() &&
        stoppedRuntimeCount == runtimeCount && clearedQueueCount == runtimeCount
}

internal fun resolveGenerationCommandId(
    activeCommandId: Uuid?,
    runId: Uuid?,
): Uuid? = activeCommandId ?: runId

/** Authority correlation never treats an ephemeral generation run as an admitted command. */
internal fun resolveAuthoritativeCommandId(activeCommandId: Uuid?): Uuid? = activeCommandId

internal fun List<UIMessage>.withResponseCorrelation(
    annotation: UIMessageAnnotation?,
): List<UIMessage> {
    annotation ?: return this
    val sourceIndex = indexOfLast { message ->
        message.role == MessageRole.USER && annotation in message.annotations
    }
    if (sourceIndex < 0) return this
    return mapIndexed { index, message ->
        if (index > sourceIndex && message.role == MessageRole.ASSISTANT && annotation !in message.annotations) {
            message.copy(annotations = message.annotations + annotation)
        } else {
            message
        }
    }
}

private fun UIMessageAnnotation.isResponseCorrelation(): Boolean =
    this is UIMessageAnnotation.QuickCapture || this is UIMessageAnnotation.PetHandoff

internal data class ChatEmergencyRuntimeTarget(
    val conversationId: Uuid,
    val submitStop: () -> ChatEmergencyCommandSubmission,
    val clearQueue: suspend () -> ChatEmergencyCommandSubmission,
)

internal data class ChatEmergencyCommandSubmission(
    val submission: SubmitResult,
    val outcome: Deferred<CommandOutcome>,
)

internal suspend fun stopChatRuntimeSnapshot(
    targets: List<ChatEmergencyRuntimeTarget>,
): ChatEmergencyStopResult {
    val reports = coroutineScope {
        targets.map { target ->
            async {
                val failures = linkedMapOf<String, String>()
                val stop = runCatching { target.submitStop() }.getOrElse { error ->
                    ChatEmergencyCommandSubmission(
                        SubmitResult.Rejected(error.message ?: error.javaClass.simpleName),
                        CompletableDeferred(CommandOutcome.Failed(error)),
                    )
                }
                val clear = runCatching { target.clearQueue() }.getOrElse { error ->
                    ChatEmergencyCommandSubmission(
                        SubmitResult.Rejected(error.message ?: error.javaClass.simpleName),
                        CompletableDeferred(CommandOutcome.Failed(error)),
                    )
                }
                val stopConfirmed = confirmEmergencySubmission(
                    key = "${target.conversationId}:stop",
                    command = stop,
                    failures = failures,
                )
                val clearConfirmed = confirmEmergencySubmission(
                    key = "${target.conversationId}:queue",
                    command = clear,
                    failures = failures,
                )
                Triple(stopConfirmed, clearConfirmed, failures)
            }
        }.awaitAll()
    }
    val stopped = reports.count { it.first }
    val cleared = reports.count { it.second }
    val failures = linkedMapOf<String, String>().apply {
        reports.forEach { putAll(it.third) }
    }
    return ChatEmergencyStopResult(
        runtimeCount = targets.size,
        stoppedRuntimeCount = stopped,
        clearedQueueCount = cleared,
        failures = failures,
    )
}

private suspend fun confirmEmergencySubmission(
    key: String,
    command: ChatEmergencyCommandSubmission,
    failures: MutableMap<String, String>,
): Boolean {
    when (val result = command.submission) {
        is SubmitResult.Accepted -> Unit
        is SubmitResult.QueueFull -> {
            failures[key] = "Queue full (${result.limit})"
            return false
        }
        is SubmitResult.Rejected -> {
            failures[key] = result.reason
            return false
        }
        is SubmitResult.RuntimeUnavailable -> {
            failures[key] = result.reason
            return false
        }
    }
    val outcome = withTimeoutOrNull(CHAT_EMERGENCY_CONFIRM_TIMEOUT_MS) { command.outcome.await() }
    if (outcome == CommandOutcome.Completed) return true
    failures[key] = when (outcome) {
        null -> "Timed out waiting for Runtime confirmation"
        is CommandOutcome.Rejected -> outcome.reason
        is CommandOutcome.Conflict -> outcome.reason
        is CommandOutcome.NotApplied -> outcome.reason
        is CommandOutcome.Failed -> outcome.error.message ?: outcome.error.javaClass.simpleName
        else -> outcome.toString()
    }
    return false
}

private const val CHAT_EMERGENCY_CONFIRM_TIMEOUT_MS = 30_000L

internal fun Conversation.withGeneratedTitle(title: String): Conversation = copy(title = title)

internal fun Conversation.withGeneratedSuggestions(suggestions: List<String>): Conversation =
    copy(chatSuggestions = suggestions)

private fun Conversation.latestAssistantNeedsFinalAnswer(): Boolean =
    currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
        ?.state == UIMessageState.INCOMPLETE_NO_VISIBLE_ANSWER

internal fun Conversation.selectedPendingToolIds(): Set<String> =
    currentMessages.asSequence()
        .flatMap { message -> message.parts.asSequence() }
        .filterIsInstance<UIMessagePart.Tool>()
        .filter(UIMessagePart.Tool::isPending)
        .mapNotNull(UIMessagePart.Tool::toolCallId)
        .toSet()

internal fun Conversation.isEligibleForGenerationPostCommit(): Boolean =
    !latestAssistantNeedsFinalAnswer() && selectedPendingToolIds().isEmpty()

internal fun ChatCommand.requiresMemorySourceReadiness(): Boolean = when (this) {
    is SendMessageCommand,
    is InterruptCommand,
    is InterruptRegenerateCommand,
    is ToolApprovalCommand,
    is RegenerateCommand,
    ResumeAfterApprovalCommand,
    -> true

    else -> false
}

/**
 * Returns the startup reconciliation failure for model-facing commands, or null when it is safe
 * to continue. Cancellation and VM errors are never converted into an ordinary rejection.
 */
internal suspend fun memorySourceReadinessFailureOrNull(
    command: ChatCommand,
    readiness: Deferred<Unit>,
): Exception? {
    if (!command.requiresMemorySourceReadiness()) return null
    return try {
        readiness.await()
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        error
    }
}

private fun Conversation.latestFinalAnswerFailure(): StableCommandException? {
    val message = currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
        ?.takeIf { it.state == UIMessageState.INCOMPLETE_NO_VISIBLE_ANSWER }
        ?: return null
    val recovery = message.annotations
        .filterIsInstance<UIMessageAnnotation.FinalAnswerRecovery>()
        .lastOrNull()
    val reason = recovery?.reason.orEmpty()
    val code = when {
        "time_budget" in reason -> "FINAL_ANSWER_TIME_BUDGET_EXHAUSTED"
        "eof" in reason -> "FINAL_ANSWER_EOF"
        "tool_call" in reason -> "FINAL_ANSWER_ATTEMPTED_TOOL_CALL"
        else -> "FINAL_ANSWER_RECOVERY_EXHAUSTED"
    }
    val safeReason = reason
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9_:-]"), "_")
        .take(120)
        .ifBlank { "no_visible_answer" }
    return StableCommandException(
        durableErrorCode = code,
        durableErrorMessage = "The model did not produce a visible final answer ($safeReason).",
    )
}

internal suspend fun runRegenerationTransaction(
    restore: suspend () -> Unit,
    operation: suspend () -> RunOutcome,
): RunOutcome {
    return try {
        val outcome = operation()
        if (outcome !is RunOutcome.Completed && outcome !is RunOutcome.WaitingApproval) {
            withContext(NonCancellable) { restore() }
        }
        outcome
    } catch (error: Throwable) {
        runCatching {
            withContext(NonCancellable) { restore() }
        }.exceptionOrNull()?.let(error::addSuppressed)
        throw error
    }
}

internal suspend fun <T> withCommandHeadlessScope(
    conversationId: Uuid,
    origin: CommandOrigin,
    control: GenerationRunControl? = null,
    block: suspend () -> T,
): T {
    if (origin != CommandOrigin.CRON) return block()
    me.rerere.rikkahub.data.ai.tools.HeadlessConversations.markTransient(conversationId)
    val released = AtomicBoolean(false)
    val release = {
        if (released.compareAndSet(false, true)) {
            me.rerere.rikkahub.data.ai.tools.HeadlessConversations.unmarkTransient(conversationId)
        }
    }
    val cancellationRegistration = control?.registerCancellationCallback(release)
    return try {
        block()
    } finally {
        cancellationRegistration?.close()
        release()
    }
}

enum class ChatErrorSolution {
    CheckTitleModelSettings,
}

private val inputTransformers by lazy {
    listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
    )
}

private val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
        // Lifts a sent sticker out of its tool output and into the message, so the person sees a
        // sticker rather than a tool step that returned a picture. Runs on the same hook as the
        // base64 transformer above, and for the same reason: parts have to be settled before the
        // message is persisted.
        me.rerere.rikkahub.sticker.StickerSendToMessagePartTransformer,
    )
}

/**
 * Append one applied steering audit message to Conversation JSON.
 *
 * Both persistent (yellow) and transient (purple) guidance stay visible after the run.
 * The command id is the exactly-once key: retries, process recovery, or duplicate runtime
 * callbacks return the original snapshot instead of adding a second history card.
 */
internal fun Conversation.withSteeringAuditMessage(
    note: me.rerere.rikkahub.data.ai.SteeringNote,
): Conversation {
    val alreadyStored = messageNodes.any { node ->
        node.messages.any { message ->
            message.annotations.any { annotation ->
                annotation is UIMessageAnnotation.Steering &&
                    annotation.commandId == note.commandId.toString()
            }
        }
    }
    if (alreadyStored) return this

    val message = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(note.text)),
        annotations = listOf(
            UIMessageAnnotation.Steering(
                commandId = note.commandId.toString(),
                persistent = note.historyMode ==
                    me.rerere.rikkahub.service.chat.SteeringHistoryMode.PERSISTENT,
            )
        ),
    ).toMessageNode()
    return copy(messageNodes = messageNodes + message)
}

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val memoryRetrievalDiagnostics: MemoryRetrievalDiagnosticsStore,
    private val agentTimingStore: AgentTimingStore,
    private val memoryV2Coordinator: me.rerere.rikkahub.memory.MemoryV2Coordinator,
    private val generationHandler: GenerationHandler,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val localTools: LocalTools,
    private val spaceRepository: me.rerere.rikkahub.space.SpaceRepository,
    private val stickerRepository: me.rerere.rikkahub.sticker.StickerRepository,
    private val stickerDelivery: me.rerere.rikkahub.sticker.StickerDelivery,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val toolApprovalPreferences: me.rerere.rikkahub.data.preferences.ToolApprovalPreferences,
    private val capabilityGrantRepository:
        me.rerere.rikkahub.data.capability.CapabilityGrantRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val workflowRepository: WorkflowRepository,
    private val conversationDeletionPolicy:
        me.rerere.rikkahub.data.repository.ConversationDeletionPolicy,
    private val secondUserSecretVault: me.rerere.rikkahub.security.SecondUserSecretVault,
    private val durableCommandQueue: DurableCommandQueue,
    private val secondUserApprovalLifecycle:
        me.rerere.rikkahub.data.execution.SecondUserApprovalLifecycle,
    /**
     * Where each run's control is published for the Claude P tool bridge to find by run id.
     *
     * Handed to every `ConversationRuntime` this service builds, so the run that is actually
     * executing is discoverable while it runs and not afterwards.
     */
    private val claudePToolRunControls: me.rerere.rikkahub.data.claudep.ClaudePToolRunControls,
    /**
     * The acknowledgement this service owes a Claude P host that published a pending card.
     *
     * ## Why this service is the one that answers
     *
     * A card published from inside a live Claude P generation only counts as published once the
     * barrier behind it is durably committed — and the commit is this service's: it owns
     * `checkpointWaiting`, it is the side that knows the transaction succeeded, and it is the side
     * the approval's own identity is derived by. A host that waited on anything less would be
     * waiting on an `emit` having returned, which says nothing.
     *
     * ## The one Koin binding this costs, and what it does not buy
     *
     * This is the acknowledgement seam and nothing else. It grants no tool execution: the runtime,
     * the MCP manager and the execution gate are wired exactly as before, the host still offers no
     * catalog, and no snapshot is sent. It exists because a request created on one side and
     * completed on the other cannot work without one shared instance.
     *
     * Never held by the host as a `Conversation`, and never used to assemble a persisted copy —
     * the host asks a question here, it does not get to answer it.
     */
    private val claudePToolPublicationReceipts:
        me.rerere.rikkahub.data.claudep.ClaudePToolPublicationReceipts,
    private val toolExecutionGate: me.rerere.rikkahub.data.ai.ToolExecutionGate,
    private val toolRuntime: me.rerere.rikkahub.data.ai.execution.ToolRuntime,
    private val pluginToolCatalog: me.rerere.rikkahub.plugin.PluginToolCatalog,
    private val pluginHookBridge: me.rerere.rikkahub.plugin.PluginHookBridge,
    private val pluginRegistryStore: me.rerere.rikkahub.plugin.PluginRegistryStore,
    private val pluginPackageInstaller: me.rerere.rikkahub.plugin.PluginPackageInstaller,
    private val agentSafetySettings: me.rerere.rikkahub.data.ai.AgentSafetySettings,
    private val shizukuBridgeManager: me.rerere.rikkahub.privilege.ShizukuBridgeManager,
    private val workspaceProcessManager: me.rerere.workspace.WorkspaceProcessManager,
    private val structuredPrivilegedCommandExecutor:
        me.rerere.rikkahub.privilege.StructuredPrivilegedCommandExecutor? = null,
    private val subAgentExecutionProfileRegistry:
        me.rerere.rikkahub.subagent.SubAgentExecutionProfileRegistry,
    private val setupTransactionCoordinator:
        me.rerere.rikkahub.setup.SetupTransactionCoordinator,
    private val displayAutomationRuntime: me.rerere.rikkahub.display.DisplayAutomationRuntime? = null,
    private val toolExperienceRepository: me.rerere.rikkahub.toolcatalog.ToolExperienceRepository,
    private val toolShortcutRepository: me.rerere.rikkahub.toolcatalog.ToolShortcutRepository,
    private val secondUserAuthorityService: me.rerere.rikkahub.assistant.SecondUserAuthorityService,
    private val hostOperationDao: me.rerere.rikkahub.owner.db.HostOperationDao,
    private val secretPlaintextSessions: me.rerere.rikkahub.security.SecretPlaintextSessionManager,
    private val ephemeralToolResults: me.rerere.rikkahub.security.EphemeralToolResultStore,
    private val runtimeSecretRedactor: me.rerere.rikkahub.security.RuntimeSecretRedactor,
    private val assistantRemovalService: me.rerere.rikkahub.data.repository.AssistantRemovalService,
    private val persistentTtsLibrary: me.rerere.rikkahub.tts.PersistentTtsLibrary,
    private val workspaceManagedProcessStarter: me.rerere.rikkahub.execution.WorkspaceManagedProcessStarter,
    private val hostLocalServiceDao: me.rerere.rikkahub.owner.db.HostLocalServiceDao,
    private val ownerHttpClient: okhttp3.OkHttpClient,
    private val workflowActionRunner: me.rerere.rikkahub.workflow.execution.WorkflowActionRunner,
    private val automationControlFacade: me.rerere.rikkahub.automation.AutomationControlFacade,
    private val doctorChecks: me.rerere.rikkahub.ui.pages.setting.doctor.DoctorChecks,
    private val executionConsistencyDoctor: me.rerere.rikkahub.diagnostics.ExecutionConsistencyDoctor,
    private val ownerLocalServiceSupervisor: me.rerere.rikkahub.owner.OwnerLocalServiceSupervisor,
    private val agentRunRepository: me.rerere.rikkahub.data.agentrun.AgentRunRepository,
    private val ownerServiceSpecStore: me.rerere.rikkahub.owner.OwnerServiceSpecStore,
    private val ownerTermuxServiceLauncher: me.rerere.rikkahub.owner.OwnerTermuxServiceLauncher,
    private val ownerOperationFingerprinter: me.rerere.rikkahub.owner.OwnerOperationFingerprinter,
    private val localBackupFacade: me.rerere.rikkahub.data.sync.LocalBackupFacade,
    private val petDialogueRepository: me.rerere.rikkahub.pet.PetDialogueRepository,
    private val telegramBotPreferences: me.rerere.rikkahub.data.telegram.TelegramBotPreferences,
    private val telegramCredentialResolver: me.rerere.rikkahub.data.telegram.TelegramCredentialResolver,
    private val reverseGeocodeProviderTestGateway:
        me.rerere.rikkahub.data.ai.tools.local.ReverseGeocodeProviderTestGateway,
    private val dreamReviewRepository:
        me.rerere.rikkahub.memory.dreaming.review.DreamReviewRepository,
    private val learningForegroundRegistry:
        me.rerere.rikkahub.learning.resources.LearningForegroundRegistry,
    private val commandAdmissionAuthority:
        me.rerere.rikkahub.data.authority.transaction.CommandAdmissionAuthorityCoordinator,
    private val commandAdmissionAuthorityAdapter:
        me.rerere.rikkahub.data.authority.transaction.CommandStateAdmissionAuthorityAdapter,
    private val waitingApprovalAuthority:
        me.rerere.rikkahub.data.authority.transaction.WaitingApprovalAuthorityCoordinator,
    private val finalConversationAuthority:
        me.rerere.rikkahub.data.authority.transaction.FinalConversationAuthorityCoordinator,
    private val executionMessageAuthorityBinder:
        me.rerere.rikkahub.data.execution.ExecutionMessageAuthorityBinder,
) {
    /** UI-only admission seam. Disabled mode performs no clock read or allocation. */
    fun beginAgentTimingSubmission(conversationId: Uuid): AgentTimingSubmissionToken? {
        val enabled = settingsStore.settingsFlow.value.displaySetting.showAgentTiming
        agentTimingStore.setEnabled(enabled)
        return agentTimingStore.beginSubmission(conversationId)
    }

    fun onConversationVisible(conversationId: Uuid) {
        val session = secretPlaintextSessions.state.value as?
            me.rerere.rikkahub.security.SecretPlaintextSessionState.Open ?: return
        if (session.binding.conversationId != conversationId.toString()) {
            secretPlaintextSessions.close(
                me.rerere.rikkahub.security.SecretPlaintextSessionCloseReason.CONVERSATION_CHANGED,
            )
        }
    }

    private val conversationLibraryReader =
        me.rerere.rikkahub.data.ai.tools.ConversationLibraryReader(conversationRepo)
    // workspace 系统提示注入 (依赖 workspaceRepository, 故在类内构�?
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)
    private val privilegedActionGuard = me.rerere.rikkahub.privilege.DefaultPrivilegedActionGuard(
        context.packageName
    )
    private val hardDenyPolicy = me.rerere.rikkahub.privilege.DefaultHardDenyPolicy(
        context.packageName,
        privilegedActionGuard,
    )
    private val privilegedManagementBackend by lazy {
        me.rerere.rikkahub.privilege.HostCapabilityRegistry(
            backend = me.rerere.rikkahub.privilege.RepositoryPrivilegedManagementBackend(
                settingsStore = settingsStore,
                conversationRepository = conversationRepo,
                skillManager = skillManager,
                workspaceRepository = workspaceRepository,
                workflowRepository = workflowRepository,
                conversationDeletionPolicy = conversationDeletionPolicy,
                secretVault = secondUserSecretVault,
                onConversationDeleted = ::dropSession,
            ),
        )
    }
    private val ownerOperationGateway by lazy {
        val ownerTtsHandler = me.rerere.rikkahub.owner.OwnerTtsOperationHandler(
            settingsStore = settingsStore,
            vault = secondUserSecretVault,
            library = persistentTtsLibrary,
        )
        val ownerServiceHandler = me.rerere.rikkahub.owner.OwnerLocalServiceOperationHandler(
            dao = hostLocalServiceDao,
            manager = workspaceProcessManager,
            starter = workspaceManagedProcessStarter,
            workspaces = workspaceRepository,
            httpClient = ownerHttpClient,
            specStore = ownerServiceSpecStore,
            termux = ownerTermuxServiceLauncher,
        )
        val executor = me.rerere.rikkahub.owner.OwnerOperationExecutor(
            dao = hostOperationDao,
            handler = me.rerere.rikkahub.owner.CompositeOwnerOperationHandler(
                me.rerere.rikkahub.security.SecretOwnerOperationHandler(
                    sessions = secretPlaintextSessions,
                    ephemeralResults = ephemeralToolResults,
                    settingsStore = settingsStore,
                    vault = secondUserSecretVault,
                ),
                me.rerere.rikkahub.owner.OwnerSettingsOperationHandler(
                    context = context,
                    settingsStore = settingsStore,
                    conversations = conversationRepo,
                    assistantRemoval = assistantRemovalService,
                    providerManager = providerManager,
                    vault = secondUserSecretVault,
                ),
                me.rerere.rikkahub.owner.OwnerPackageControlHandler(
                    context = context,
                    settingsStore = settingsStore,
                    files = filesManager,
                    pluginInstaller = pluginPackageInstaller,
                    plugins = pluginRegistryStore,
                ),
                me.rerere.rikkahub.owner.OwnerRunOperationHandler(
                    controller = object : me.rerere.rikkahub.owner.OwnerRunController {
                        override suspend fun snapshot(conversationId: Uuid): me.rerere.rikkahub.owner.OwnerRunSnapshot {
                            val exists = conversationRepo.existsConversationById(conversationId)
                            if (!exists) return me.rerere.rikkahub.owner.OwnerRunSnapshot(false, "Missing", null, emptySet())
                            val runtime = getRuntimeStateFlow(conversationId).value
                            val queue = getQueueStatusFlow(conversationId).value
                            return me.rerere.rikkahub.owner.OwnerRunSnapshot(
                                exists = true,
                                runtimeState = runtime::class.simpleName ?: "Unknown",
                                activeCommandId = queue.activeCommandId,
                                pendingCommandIds = queue.pendingCommandIds.toSet(),
                            )
                        }

                        override suspend fun cancel(conversationId: Uuid, commandId: Uuid?): me.rerere.rikkahub.owner.OwnerRunSubmission {
                            val queue = getQueueStatusFlow(conversationId).value
                            val result = when {
                                commandId != null && commandId in queue.pendingCommandIds -> cancelQueuedCommand(conversationId, commandId)
                                commandId != null && commandId != queue.activeCommandId -> return me.rerere.rikkahub.owner.OwnerRunSubmission(false, "RUN_COMMAND_NOT_FOUND")
                                else -> stopGeneration(conversationId)
                            }
                            return result.toOwnerRunSubmission()
                        }

                        override suspend fun retryLastAssistant(conversationId: Uuid): me.rerere.rikkahub.owner.OwnerRunSubmission =
                            submitOwnerRetryLastAssistant(conversationId).toOwnerRunSubmission()
                    },
                ),
                me.rerere.rikkahub.owner.OwnerBackupOperationHandler(
                    backups = localBackupFacade,
                    files = filesManager,
                ),
                me.rerere.rikkahub.owner.OwnerQuickCaptureOperationHandler(context),
                me.rerere.rikkahub.owner.OwnerAndroidControlHandler(context, agentSafetySettings),
                me.rerere.rikkahub.owner.OwnerChannelOperationHandler(
                    context = context,
                    settingsStore = settingsStore,
                    preferences = telegramBotPreferences,
                    credentials = telegramCredentialResolver,
                    vault = secondUserSecretVault,
                ),
                me.rerere.rikkahub.owner.OwnerApplicationControlHandler(
                    settingsStore = settingsStore,
                    plugins = pluginRegistryStore,
                    safety = agentSafetySettings,
                    operations = hostOperationDao,
                    memories = memoryRepository,
                    vault = secondUserSecretVault,
                    petDialogues = petDialogueRepository,
                    reverseGeocodeTester = reverseGeocodeProviderTestGateway,
                    dreamReviews = dreamReviewRepository,
                ),
                ownerTtsHandler,
                me.rerere.rikkahub.owner.OwnerEmotionTtsOperationHandler(
                    settingsStore = settingsStore,
                    serviceHandler = ownerServiceHandler,
                    ttsHandler = ownerTtsHandler,
                ),
                ownerServiceHandler,
                me.rerere.rikkahub.owner.OwnerMcpOperationHandler(
                    settingsStore = settingsStore,
                    manager = mcpManager,
                    httpClient = ownerHttpClient,
                    vault = secondUserSecretVault,
                ),
                me.rerere.rikkahub.owner.OwnerSkillOperationHandler(
                    settingsStore = settingsStore,
                    skillManager = skillManager,
                    httpClient = ownerHttpClient,
                ),
                me.rerere.rikkahub.owner.OwnerWorkflowOperationHandler(
                    repository = workflowRepository,
                    actionRunner = workflowActionRunner,
                    automation = automationControlFacade,
                    conversations = conversationRepo,
                    settings = settingsStore,
                ),
                me.rerere.rikkahub.owner.OwnerUiOperationHandler(),
                me.rerere.rikkahub.owner.OwnerDoctorOperationHandler(
                    checks = doctorChecks,
                    executionDoctor = executionConsistencyDoctor,
                    operationDao = hostOperationDao,
                    serviceDao = hostLocalServiceDao,
                    serviceSupervisor = ownerLocalServiceSupervisor,
                    plaintextSessions = secretPlaintextSessions,
                ),
                me.rerere.rikkahub.owner.ExistingHostOwnerOperationHandler(
                    privilegedManagementBackend,
                ),
            ),
            isEmergencyStopActive = agentSafetySettings::isEmergencyStop,
            containsRuntimeSecret = runtimeSecretRedactor::containsKnownSecret,
            fingerprinter = ownerOperationFingerprinter,
        )
        me.rerere.rikkahub.owner.AgentRunOwnerOperationGateway(
            delegate = executor,
            operations = hostOperationDao,
            runs = agentRunRepository,
        )
    }

    // 统一会话管理
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val runtimes = ConcurrentHashMap<Uuid, ConversationRuntime>()
    private val sessionLifecycleLock = Any()
    private val commandSequences = ConcurrentHashMap<Uuid, AtomicLong>()
    /**
     * The tool origin of the current conversation run. Approval continuation commands are
     * intentionally INTERNAL, so retain the originating surface while the run is alive;
     * otherwise a remote approval could be misclassified as LocalChat.
     */
    private val activeToolOrigins = ConcurrentHashMap<Uuid, ToolCallOrigin>()
    private val _sessionsVersion = MutableStateFlow(0L)

    /**
     * Per-conversation mutex serialising state-mutating operations: handleToolApproval,
     * stopGeneration, the chunk-handling save path, and explicit DB writes. Without this
     * the audit reports identified multiple write races where a fresh approval mutation
     * gets clobbered by a concurrent write from a stale snapshot. Generation chunks
     * themselves are NOT held under this mutex �?only the persist boundaries.
     */

    /**
     * Hydrate the in-memory session for [conversationId] from disk if no authoritative
     * state has been installed yet. Used by entry points that may be hit
     * after a process restart with an empty session map �?without this they read an
     * empty Conversation, mutate it, and `saveConversation` then OVERWRITES the persisted
     * state with empty content (silent data loss). Idempotent and cheap after hydration.
     */
    suspend fun ensureHydrated(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId)
        if (session.isHydrated) return
        val fromDb = conversationRepo.getConversationById(conversationId) ?: return
        session.hydrateIfNeeded(fromDb)
    }

    // 错误状�?
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    fun addError(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) {
        if (error is CancellationException) return
        _errors.update { it + ChatError(title = title, error = error, conversationId = conversationId, solution = solution) }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }

    // 生成完成�?
    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    // 前台状态管�?
    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> _isForeground.value = true
            Lifecycle.Event.ON_STOP -> _isForeground.value = false
            else -> {}
        }
    }

    /** Recovers durable leases only; source tombstones are committed after successful generation. */
    private val durableRegenerationSourceReadiness: Deferred<Unit> = appScope.async(Dispatchers.IO) {
        try {
            // Include RUNNING rows even when the dead process's old lease has not expired yet.
            durableCommandQueue.recoverExpiredFenced()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Durable regeneration lease recovery failed", error)
            throw IllegalStateException("durable_regeneration_recovery_unavailable", error)
        }
    }

    init {
        // 添加生命周期观察�?
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
    }

    fun cleanup() = runCatching {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        val (runtimesToClose, sessionsToClose) = synchronized(sessionLifecycleLock) {
            val runtimeSnapshot = runtimes.values.toList()
            val sessionSnapshot = sessions.values.toList()
            runtimes.clear()
            sessions.clear()
            activeToolOrigins.clear()
            runtimeSnapshot to sessionSnapshot
        }
        runtimesToClose.forEach { it.close() }
        sessionsToClose.forEach { it.cleanup() }
    }.onFailure {
        // Don't let a teardown hiccup escape, but don't swallow it silently either �?
        // a failure here can leave the lifecycle observer registered (slow leak).
        Log.w(TAG, "cleanup failed", it)
    }

    // ---- Session 管理 ----

    private fun getOrCreateSession(conversationId: Uuid): ConversationSession =
        synchronized(sessionLifecycleLock) {
            sessions.computeIfAbsent(conversationId) { id ->
                val settings = settingsStore.settingsFlow.value
                lateinit var createdSession: ConversationSession
                createdSession = ConversationSession(
                    id = id,
                    initial = Conversation.ofId(
                        id = id,
                        assistantId = settings.getCurrentAssistant().id,
                    ),
                    scope = appScope,
                    onIdle = { removeSession(it, createdSession) },
                    canEvict = { runtimes[id]?.hasRetainedWork != true },
                )
                _sessionsVersion.value++
                Log.i(TAG, "createSession: $id (total: ${sessions.size + 1})")
                createdSession
            }
        }

    private fun removeSession(
        conversationId: Uuid,
        expectedSession: ConversationSession,
    ) {
        val removed = synchronized(sessionLifecycleLock) {
            val session = sessions[conversationId] ?: return
            if (session !== expectedSession) {
                Log.d(TAG, "removeSession: ignored stale idle callback for $conversationId")
                return
            }
            val runtime = runtimes[conversationId]
            if (session.isInUse || runtime?.hasRetainedWork == true) {
                Log.d(TAG, "removeSession: skipped $conversationId (still in use)")
                return
            }
            if (!sessions.remove(conversationId, session)) return
            val removedRuntime = runtime?.takeIf { runtimes.remove(conversationId, it) }
            activeToolOrigins.remove(conversationId)
            Triple(session, removedRuntime, sessions.size)
        }
        removed.second?.close()
        removed.first.cleanup()
        _sessionsVersion.value++
        Log.i(TAG, "removeSession: $conversationId (remaining: ${removed.third})")
    }

    private fun resolveToolOrigin(conversationId: Uuid, origin: CommandOrigin): ToolCallOrigin {
        val resolved = when (origin) {
            CommandOrigin.APP_UI -> ToolCallOrigin.LocalChat
            CommandOrigin.TELEGRAM -> ToolCallOrigin.Telegram
            CommandOrigin.WEB_API -> ToolCallOrigin.WebServer
            CommandOrigin.CRON -> ToolCallOrigin.TrustedWorkflow
            CommandOrigin.SYSTEM_ASSISTANT -> ToolCallOrigin.SystemAssistant
            CommandOrigin.SYSTEM_ASSISTANT_KEYGUARD -> ToolCallOrigin.SystemAssistantKeyguard
            CommandOrigin.QUICK_CAPTURE -> ToolCallOrigin.QuickCapture
            CommandOrigin.PET_INTERACTION -> ToolCallOrigin.PetInteraction
            CommandOrigin.PET_HANDOFF_CONFIRMED -> ToolCallOrigin.PetHandoffConfirmed
            CommandOrigin.PET_HANDOFF_AUTO -> ToolCallOrigin.PetHandoffAuto
            // Approval continuation is an internal command, but it must retain the
            // surface that created the pending tool call. If no in-memory provenance is
            // available (for example after process death), fail closed as a workflow.
            CommandOrigin.INTERNAL -> activeToolOrigins[conversationId]
                ?: ToolCallOrigin.TrustedWorkflow
        }
        activeToolOrigins[conversationId] = resolved
        return resolved
    }

    /**
     * Origins and principals are separate: a remote request can carry the same assistant id as
     * a local conversation, but it never becomes that assistant's local second-user profile.
     */
    private fun capabilitySubjectFor(
        assistant: Assistant,
        conversationId: Uuid,
        origin: ToolCallOrigin,
        privilege: me.rerere.rikkahub.privilege.PrivilegedSessionContext? = null,
    ): me.rerere.rikkahub.data.capability.CapabilitySubject =
        // The rule itself lives in `CapabilitySubjectResolver` so that the Claude P tool bridge
        // host asks the same question of the same code rather than keeping a copy of it. This
        // delegation preserves every existing call site and every existing answer.
        me.rerere.rikkahub.data.capability.CapabilitySubjectResolver.resolve(
            assistantId = assistant.id,
            conversationId = conversationId,
            origin = origin,
            privilege = privilege,
        )

    /**
     * The authority registry is intentionally fail-closed, but it used to be populated only by
     * an asynchronous app-start collector. A user who sent the first message immediately after a
     * cold start could therefore be demoted to an ordinary session and receive approval cards.
     * Admission re-reads the authoritative DataStore state and has no effect unless this exact
     * assistant/conversation is active, unlocked, and entered through a trusted local surface.
     */
    private suspend fun refreshSecondUserAuthorityForInvocation(
        assistant: Assistant,
        conversation: Conversation,
        origin: ToolCallOrigin,
    ) {
        if (origin !in me.rerere.rikkahub.data.ai.InvocationSurfacePolicy.CONFIRMED_LOCAL_SECOND_USER) return
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        val unlocked = keyguard?.let { !it.isDeviceLocked && !it.isKeyguardLocked } ?: true
        secondUserAuthorityService.admit(
            assistantId = assistant.id,
            conversationId = conversation.id,
            origin = origin,
            deviceUnlocked = unlocked,
        )
    }

    private fun getOrCreateRuntime(conversationId: Uuid): ConversationRuntime =
        synchronized(sessionLifecycleLock) {
            val session = getOrCreateSession(conversationId)
            runtimes.computeIfAbsent(conversationId) { id ->
                lateinit var createdRuntime: ConversationRuntime
                createdRuntime = ConversationRuntime(
                    appScope = appScope,
                    conversationId = id,
                    dispatchers = DispatcherProvider(),
                    executor = RuntimeCommandExecutor { envelope, control ->
                        executeRuntimeCommand(envelope, control)
                    },
                    hydrator = RuntimeHydrator {
                        ensureHydrated(id)
                    },
                    repairer = object : me.rerere.rikkahub.service.chat.RuntimeRepairer {
                        override suspend fun repair(
                            runId: Uuid,
                            reason: ToolCancelReason,
                        ): me.rerere.rikkahub.service.chat.InterruptCleanupResult {
                            finishInterruptedPendingTools(id, emptyMap())
                            return me.rerere.rikkahub.service.chat.InterruptCleanupResult.Completed
                        }

                        override suspend fun repair(
                            runId: Uuid,
                            reason: ToolCancelReason,
                            toolCancellationResults: Map<String, CancelRequestResult>,
                        ): me.rerere.rikkahub.service.chat.InterruptCleanupResult {
                            finishInterruptedPendingTools(id, toolCancellationResults)
                            return me.rerere.rikkahub.service.chat.InterruptCleanupResult.Completed
                        }
                    },
                    durableQueue = durableCommandQueue,
                    commandAuthority = runtimeCommandAuthority,
                    onBecameIdle = { runtimeId ->
                        synchronized(sessionLifecycleLock) {
                            if (runtimes[runtimeId] === createdRuntime) {
                                sessions[runtimeId]?.requestIdleCheck()
                            }
                        }
                    },
                    onRunJobChanged = { job -> session.attachRunJob(job) },
                    onRunStarted = {
                        learningForegroundRegistry.enter(
                            me.rerere.rikkahub.learning.resources.LearningForegroundWorkKind
                                .CONVERSATION_EXECUTION,
                            kotlinx.coroutines.currentCoroutineContext()[Job],
                        )
                    },
                    onPetRunStarted = {
                        learningForegroundRegistry.enter(
                            me.rerere.rikkahub.learning.resources.LearningForegroundWorkKind
                                .PET_DIALOGUE,
                            kotlinx.coroutines.currentCoroutineContext()[Job],
                        )
                    },
                    onPersistSteering = { note ->
                        val current = session.state.value
                        val updated = current.withSteeringAuditMessage(note)
                        if (updated !== current) {
                            saveConversation(id, updated)
                        }
                    },
                    onCancellationTimeout = { envelope, error ->
                        addError(
                            error = error,
                            conversationId = id,
                            title = context.getString(
                                if (envelope.command is me.rerere.rikkahub.service.chat.InterruptRegenerateCommand) {
                                    R.string.error_title_regenerate_message
                                } else {
                                    R.string.error_title_operation
                                }
                            ),
                        )
                    },
                    claudePToolRunControls = claudePToolRunControls,
                )
                createdRuntime
            }
        }

    suspend fun submitCommand(
        conversationId: Uuid,
        command: ChatCommand,
        origin: CommandOrigin,
        agentTimingSubmission: AgentTimingSubmissionToken? = null,
    ): SubmitResult {
        return submitCommand(
            conversationId = conversationId,
            command = command,
            origin = origin,
            dedupeKey = null,
            expiresAt = null,
            dependencies = emptyList(),
            agentTimingSubmission = agentTimingSubmission,
        )
    }

    private suspend fun submitCommand(
        conversationId: Uuid,
        command: ChatCommand,
        origin: CommandOrigin,
        dedupeKey: String?,
        expiresAt: kotlin.time.Instant?,
        dependencies: List<me.rerere.rikkahub.service.chat.CommandDependency>,
        agentTimingSubmission: AgentTimingSubmissionToken? = null,
        parentCommandId: Uuid? = null,
    ): SubmitResult = submitCommandTracked(
        conversationId = conversationId,
        command = command,
        origin = origin,
        dedupeKey = dedupeKey,
        expiresAt = expiresAt,
        dependencies = dependencies,
        agentTimingSubmission = agentTimingSubmission,
        parentCommandId = parentCommandId,
    ).submission

    /**
     * Root-cause repair for the first-message "Conversation not found" regression.
     *
     * The Compose/web "new conversation" flow keeps a brand-new ordinary chat only in the
     * in-memory ConversationSession and defers its first Room write to message execution.
     * Durable command admission (commit 0fd4df363) requires the row to already exist at
     * submit time, so the very first SendMessageCommand on such a chat was rejected before
     * execution could persist anything (upstream RikkaHub inserts the row inside the send
     * call, so it never had this chicken-and-egg). When the live session still describes an
     * unsaved new conversation, persist that draft now; execution then appends the first
     * message node to the same row. This restores lazy-create-on-first-send without relaxing
     * the persisted-conversation guarantee for commands targeting pre-existing conversations.
     */
    private suspend fun materializeFirstSendConversationForAdmissionOrNull(
        conversationId: Uuid,
        command: ChatCommand,
        origin: CommandOrigin,
    ): Conversation? {
        val session = getOrCreateSession(conversationId)
        if (!session.isHydrated) {
            try {
                initializeConversation(conversationId)
            } catch (_: Exception) {
                // An un-hydratable session stays gated below (no new conversation is created).
            }
        }
        val draft = session.state.value
        val assistantExists =
            settingsStore.settingsFlow.first().getAssistantById(draft.assistantId) != null
        if (!shouldMaterializeConversationAtFirstSend(
                origin = origin,
                command = command,
                isNewConversationDraft = draft.newConversation,
                assistantExists = assistantExists,
            )
        ) {
            return null
        }
        conversationRepo.insertConversation(draft)
        return conversationRepo.getConversationById(conversationId)
    }

    private suspend fun submitCommandTracked(
        conversationId: Uuid,
        command: ChatCommand,
        origin: CommandOrigin,
        dedupeKey: String?,
        expiresAt: kotlin.time.Instant?,
        dependencies: List<me.rerere.rikkahub.service.chat.CommandDependency>,
        commandId: Uuid? = null,
        agentTimingSubmission: AgentTimingSubmissionToken? = null,
        parentCommandId: Uuid? = null,
    ): TrackedCommandSubmission {
        require(command !is me.rerere.rikkahub.service.chat.EmergencyCommand) {
            "EmergencyCommand must use submitEmergency()"
        }
        me.rerere.rikkahub.service.chat.emergencyStopCommandBlockReason(
            active = agentSafetySettings.emergencyStopFlow.first(),
            command = command,
        )?.let { reason ->
            agentTimingSubmission?.handle?.finish(AgentTimingTraceStatus.FAILED)
            return rejectedTrackedCommand(reason)
        }
        me.rerere.rikkahub.service.chat.SystemAssistantCommandSecurityPolicy
            .commandBlockReason(origin, command)
            ?.let { reason ->
                agentTimingSubmission?.handle?.finish(AgentTimingTraceStatus.FAILED)
                return rejectedTrackedCommand(reason)
        }
        val resolvedCommandId = commandId ?: Uuid.random()
        // B1 approval/scheduled-lifecycle trace: every admission attempt, its outcome, and any
        // resolved parent lineage are logged keyed by ids (never by prompt/token/secret).
        Log.d(TAG, "cmd.submit conversation=$conversationId command=${command::class.simpleName} origin=$origin commandId=$resolvedCommandId")
        val persistedAdmissionConversation = conversationRepo.getConversationById(conversationId)
            ?: materializeFirstSendConversationForAdmissionOrNull(conversationId, command, origin)
            ?: return rejectedTrackedCommand("Conversation not found")
        if (origin == CommandOrigin.SYSTEM_ASSISTANT && command !is StopCommand) {
            val validation = me.rerere.rikkahub.service.chat.SystemAssistantCommandSecurityPolicy
                .validateAdmissionTarget(
                    command = command,
                    conversationId = conversationId,
                    settings = settingsStore.settingsFlow.first(),
                    persistedConversation = conversationRepo.getConversationById(conversationId),
                )
            if (validation is me.rerere.rikkahub.service.chat.SystemAssistantTargetValidation.Invalid) {
                agentTimingSubmission?.handle?.finish(AgentTimingTraceStatus.FAILED)
                return rejectedTrackedCommand(validation.reason)
            }
        }
        if (origin == CommandOrigin.QUICK_CAPTURE && command !is StopCommand) {
            val validation = me.rerere.rikkahub.service.chat.QuickCaptureCommandSecurityPolicy
                .validateAdmission(
                    commandId = resolvedCommandId,
                    command = command,
                    conversationId = conversationId,
                    settings = settingsStore.settingsFlow.first(),
                    persistedConversation = conversationRepo.getConversationById(conversationId),
                )
            if (validation is me.rerere.rikkahub.service.chat.QuickCaptureTargetValidation.Invalid) {
                agentTimingSubmission?.handle?.finish(AgentTimingTraceStatus.FAILED)
                return rejectedTrackedCommand(validation.reason)
            }
        }
        // The one recovery §6.1 defines, attached to the thing the user actually did and running
        // *before* admission — a branch awaiting a bind cannot be admitted at all, so this has to
        // settle first or the recovery could never happen. It sends `session.bind` and nothing else:
        // no `generation.start` is reachable from it, so a replay cannot buy a second model run.
        claudePBindReplayRefusal(conversationId, command)?.let { reason ->
            agentTimingSubmission?.handle?.finish(AgentTimingTraceStatus.FAILED)
            return rejectedTrackedCommand(reason)
        }
        val resolvedParentId = parentCommandId ?: when (command) {
            is ToolApprovalCommand -> {
                val exactOwner = if (command.approvalId != null && command.executionId != null) {
                    secondUserApprovalLifecycle.findOwningCommandIdExact(
                        approvalId = command.approvalId,
                        executionId = command.executionId,
                        conversationId = conversationId.toString(),
                        toolCallId = command.toolCallId,
                    )
                } else if (command.decision is ToolDecision.Denied) {
                    secondUserApprovalLifecycle.findOwningCommandId(
                        conversationId = conversationId.toString(),
                        toolCallId = command.toolCallId,
                    )
                } else {
                    return rejectedTrackedCommand("Approval exact identity is required")
                }
                exactOwner?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                    ?: if (command.decision is ToolDecision.Denied) {
                        durableCommandQueue.findSingleWaitingForConversation(conversationId)
                            ?.id
                            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                    } else {
                        null
                    }
            }
            else -> null
        }
        val parentLineage = resolvedParentId?.let { parentId ->
            val row = durableCommandQueue.findAuthorityRow(parentId)
                ?: return rejectedTrackedCommand("Parent command not found")
            if (row.conversationId != conversationId.toString()) {
                return rejectedTrackedCommand("Parent command belongs to another conversation")
            }
            me.rerere.rikkahub.service.chat.CommandLineageContext.fromAuthorityRowOrNull(row)
                ?: return rejectedTrackedCommand("Parent command lineage is unavailable")
        }
        if (command is ToolApprovalCommand || command is ResumeAfterApprovalCommand) {
            Log.d(
                TAG,
                "cmd.approval conversation=$conversationId command=${command::class.simpleName} " +
                    "commandId=$resolvedCommandId parentCommandId=$resolvedParentId " +
                    "lineageProven=${parentLineage != null}",
            )
        }
        if ((command is ToolApprovalCommand || command is ResumeAfterApprovalCommand) && parentLineage == null) {
            return rejectedTrackedCommand("Approval command lineage could not be proven")
        }
        val admissionAssistantId = parentLineage?.assistantIdSnapshot
            ?: (command as? SendMessageCommand)?.assistantIdSnapshot
            ?: persistedAdmissionConversation.assistantId
        if (admissionAssistantId != persistedAdmissionConversation.assistantId) {
            return rejectedTrackedCommand("Command assistant scope changed before admission")
        }
        val preparedCommand = if (command is SendMessageCommand) {
            val assistant = settingsStore.settingsFlow.first()
                .getAssistantById(admissionAssistantId)
                ?: return rejectedTrackedCommand("Command assistant is unavailable")
            command.copy(
                content = command.content.copy(
                    parts = preprocessUserInputParts(command.content.parts, assistant),
                ),
                assistantIdSnapshot = admissionAssistantId,
            )
        } else {
            command
        }
        val branchAnchor = parentLineage?.branchAnchorMessageId
            ?: when (preparedCommand) {
                is SendMessageCommand -> Uuid.random()
                is RegenerateCommand -> {
                    val selected = persistedAdmissionConversation.currentMessages
                    val targetIndex = selected.indexOfFirst { it.id == preparedCommand.targetMessageId }
                    if (targetIndex < 0) {
                        return rejectedTrackedCommand("Regeneration target is unavailable")
                    }
                    selected.subList(0, targetIndex + 1)
                        .lastOrNull { it.role == MessageRole.USER }
                        ?.id
                        ?: return rejectedTrackedCommand("Regeneration user anchor is unavailable")
                }
                else -> persistedAdmissionConversation.currentMessages
                    .lastOrNull { it.role == MessageRole.USER }
                    ?.id
                    ?: return rejectedTrackedCommand("Command user anchor is unavailable")
            }
        val envelope = CommandEnvelope(
            id = resolvedCommandId,
            conversationId = conversationId,
            command = preparedCommand,
            origin = origin,
            sequence = commandSequences.getOrPut(conversationId) { AtomicLong() }.incrementAndGet(),
            dedupeKey = dedupeKey,
            expiresAt = expiresAt,
            dependencies = dependencies,
            lineage = me.rerere.rikkahub.service.chat.CommandLineageContext(
                assistantIdSnapshot = admissionAssistantId,
                lineageId = parentLineage?.lineageId ?: resolvedCommandId,
                parentCommandId = resolvedParentId,
                branchAnchorMessageId = branchAnchor,
                branchAnchorMessageRevision = parentLineage?.branchAnchorMessageRevision,
            ),
            agentTimingSubmission = agentTimingSubmission,
        )
        val submission = getOrCreateRuntime(conversationId).enqueueEnvelope(envelope)
        Log.d(
            TAG,
            "cmd.submit.result conversation=$conversationId commandId=$resolvedCommandId " +
                "result=${submission::class.simpleName} accepted=${submission is SubmitResult.Accepted}",
        )
        if (submission !is SubmitResult.Accepted || submission.commandId != envelope.id) {
            agentTimingSubmission?.handle?.finish(AgentTimingTraceStatus.FAILED)
        }
        return TrackedCommandSubmission(
            submission = submission,
            outcome = envelope.result,
        )
    }

    suspend fun submitUserMessage(
        conversationId: Uuid,
        content: List<UIMessagePart>,
        answer: Boolean = true,
        origin: CommandOrigin = CommandOrigin.APP_UI,
        dedupeKey: String? = null,
        expiresAt: kotlin.time.Instant? = null,
        annotations: List<UIMessageAnnotation> = emptyList(),
        agentTimingSubmission: AgentTimingSubmissionToken? = null,
    ): SubmitResult = submitUserMessageTracked(
        conversationId = conversationId,
        content = content,
        answer = answer,
        origin = origin,
        dedupeKey = dedupeKey,
        expiresAt = expiresAt,
        annotations = annotations,
        agentTimingSubmission = agentTimingSubmission,
    ).submission

    suspend fun <T> runPetInteraction(
        conversationId: Uuid,
        block: suspend () -> T,
    ): me.rerere.rikkahub.service.chat.PetInteractionSlotResult<T> =
        getOrCreateRuntime(conversationId).runPetInteraction(block)

    internal suspend fun submitUserMessageTracked(
        conversationId: Uuid,
        content: List<UIMessagePart>,
        answer: Boolean = true,
        origin: CommandOrigin = CommandOrigin.APP_UI,
        dedupeKey: String? = null,
        expiresAt: kotlin.time.Instant? = null,
        annotations: List<UIMessageAnnotation> = emptyList(),
        assistantIdSnapshot: Uuid? = null,
        commandId: Uuid? = null,
        quickCaptureSessionId: Uuid? = null,
        agentTimingSubmission: AgentTimingSubmissionToken? = null,
    ): TrackedCommandSubmission {
        if (content.isEmptyInputMessage()) {
            agentTimingSubmission?.handle?.finish(AgentTimingTraceStatus.FAILED)
            return TrackedCommandSubmission(
                submission = SubmitResult.Rejected("Empty message"),
                outcome = CompletableDeferred(CommandOutcome.Rejected("Empty message")),
            )
        }
        return submitCommandTracked(
            conversationId = conversationId,
            command = SendMessageCommand(
                content = RawUserContent(content, answer, annotations),
                assistantIdSnapshot = assistantIdSnapshot,
                quickCaptureSessionId = quickCaptureSessionId,
            ),
            origin = origin,
            dedupeKey = dedupeKey,
            expiresAt = expiresAt,
            dependencies = emptyList(),
            commandId = commandId,
            agentTimingSubmission = agentTimingSubmission,
        )
    }

    suspend fun submitSteer(
        conversationId: Uuid,
        text: String,
        scope: SteeringScope = SteeringScope.REMAINDER_OF_RUN,
        applyPolicy: me.rerere.rikkahub.service.chat.SteeringApplyPolicy =
            me.rerere.rikkahub.service.chat.SteeringApplyPolicy.AFTER_CHECKPOINT,
        origin: CommandOrigin = CommandOrigin.APP_UI,
        historyMode: me.rerere.rikkahub.service.chat.SteeringHistoryMode =
            me.rerere.rikkahub.service.chat.SteeringHistoryMode.TRANSIENT,
    ): SubmitResult {
        if (text.isBlank()) return SubmitResult.Rejected("Steering text cannot be blank")
        return submitCommand(
            conversationId = conversationId,
            command = SteerCommand(
                text = text,
                scope = scope,
                applyPolicy = applyPolicy,
                historyMode = historyMode,
            ),
            origin = origin,
        )
    }

    fun updateSteeringHistoryMode(
        conversationId: Uuid,
        commandId: Uuid,
        historyMode: me.rerere.rikkahub.service.chat.SteeringHistoryMode,
    ): Boolean = getOrCreateRuntime(conversationId)
        .updateSteeringHistoryMode(commandId, historyMode)

    suspend fun cancelCurrentTool(
        conversationId: Uuid,
        toolCallId: String,
        origin: CommandOrigin = CommandOrigin.APP_UI,
    ): SubmitResult {
        if (toolCallId.isBlank() || toolCallId.length > 256) {
            return SubmitResult.Rejected("Invalid tool call id")
        }
        return submitCommand(
            conversationId = conversationId,
            command = CancelCurrentToolCommand(toolCallId),
            origin = origin,
        )
    }

    fun submitInterrupt(
        conversationId: Uuid,
        replacement: List<UIMessagePart>,
        answer: Boolean = true,
        origin: CommandOrigin = CommandOrigin.APP_UI,
        agentTimingSubmission: AgentTimingSubmissionToken? = null,
    ): SubmitResult = submitEmergency(
        conversationId = conversationId,
        command = InterruptCommand(SendMessageCommand(RawUserContent(replacement, answer))),
        origin = origin,
        agentTimingSubmission = agentTimingSubmission,
    )

    suspend fun resumeQueue(conversationId: Uuid, origin: CommandOrigin = CommandOrigin.APP_UI): SubmitResult =
        submitCommand(conversationId, ResumeQueueCommand(), origin)

    suspend fun clearPendingQueue(conversationId: Uuid, reason: String = "Cleared by user"): SubmitResult =
        submitCommand(conversationId, ClearPendingQueueCommand(reason), CommandOrigin.APP_UI)

    suspend fun cancelQueuedCommand(conversationId: Uuid, commandId: Uuid): SubmitResult =
        submitCommand(conversationId, CancelQueuedCommand(commandId), CommandOrigin.APP_UI)

    suspend fun cancelSteering(conversationId: Uuid, commandId: Uuid): SubmitResult =
        submitCommand(conversationId, CancelSteeringCommand(commandId), CommandOrigin.APP_UI)

    suspend fun updateQueuedMessage(
        conversationId: Uuid,
        commandId: Uuid,
        content: RawUserContent,
    ): SubmitResult = submitCommand(
        conversationId,
        UpdateQueuedMessageCommand(commandId, content),
        CommandOrigin.APP_UI,
    )

    suspend fun promoteQueuedMessageToSteering(
        conversationId: Uuid,
        commandId: Uuid,
    ): SubmitResult = submitCommand(
        conversationId,
        PromoteQueuedMessageToSteeringCommand(commandId),
        CommandOrigin.APP_UI,
    )

    fun submitEmergency(
        conversationId: Uuid,
        command: me.rerere.rikkahub.service.chat.EmergencyCommand,
        origin: CommandOrigin,
        agentTimingSubmission: AgentTimingSubmissionToken? = null,
    ): SubmitResult {
        val envelope = CommandEnvelope(
            conversationId = conversationId,
            command = command,
            origin = origin,
            sequence = commandSequences.getOrPut(conversationId) { AtomicLong() }.incrementAndGet(),
            agentTimingSubmission = agentTimingSubmission,
        )
        val result = getOrCreateRuntime(conversationId).replaceEmergencyEnvelope(envelope)
        if (result !is SubmitResult.Accepted || result.commandId != envelope.id) {
            agentTimingSubmission?.handle?.finish(AgentTimingTraceStatus.FAILED)
        }
        return result
    }

    /**
     * Stops only the Runtime instances that already exist. This never creates a session while
     * emergency stop is active, and the captured Runtime identity prevents a stale callback
     * from affecting a later replacement instance.
     */
    suspend fun stopAllActiveRuntimesForEmergency(): ChatEmergencyStopResult {
        secondUserApprovalLifecycle.invalidateAllPending(
            reasonCode = "emergency_stop",
            orphaned = false,
            source = me.rerere.rikkahub.data.execution.ExecutionStateSource.POLICY,
        ).forEach { conversation ->
            if (sessions.containsKey(conversation.id)) {
                updateConversation(conversation.id, conversation)
            }
        }
        val targets = synchronized(sessionLifecycleLock) {
            runtimes.entries.map { (conversationId, runtime) ->
                val stopEnvelope = CommandEnvelope(
                    conversationId = conversationId,
                    command = StopCommand(pauseQueue = true),
                    origin = CommandOrigin.INTERNAL,
                    sequence = commandSequences
                        .getOrPut(conversationId) { AtomicLong() }
                        .incrementAndGet(),
                )
                val clearEnvelope = CommandEnvelope(
                    conversationId = conversationId,
                    command = ClearPendingQueueCommand("Emergency stop"),
                    origin = CommandOrigin.INTERNAL,
                    sequence = commandSequences
                        .getOrPut(conversationId) { AtomicLong() }
                        .incrementAndGet(),
                )
                ChatEmergencyRuntimeTarget(
                    conversationId = conversationId,
                    submitStop = {
                        ChatEmergencyCommandSubmission(
                            submission = runtime.replaceEmergencyEnvelope(stopEnvelope),
                            outcome = stopEnvelope.result,
                        )
                    },
                    clearQueue = {
                        ChatEmergencyCommandSubmission(
                            submission = runtime.enqueueEnvelope(clearEnvelope),
                            outcome = clearEnvelope.result,
                        )
                    },
                )
            }
        }
        return stopChatRuntimeSnapshot(targets)
    }

    /**
     * Force-drop the in-memory session for [conversationId] regardless of refcount /
     * generation status. Used by /new in TelegramBotService to make sure a straggler
     * coroutine writing back to the session can't resurrect the conversation after the
     * user reset it. Safe to call when no session exists �?no-op.
     */
    fun dropSession(conversationId: Uuid) {
        val removed = synchronized(sessionLifecycleLock) {
            val runtime = runtimes.remove(conversationId)
            val session = sessions.remove(conversationId)
            activeToolOrigins.remove(conversationId)
            Triple(session, runtime, sessions.size)
        }
        removed.second?.close()
        removed.first?.cleanup()
        if (removed.first != null || removed.second != null) {
            _sessionsVersion.value++
            Log.i(TAG, "dropSession: $conversationId (remaining: ${removed.third})")
        }
    }

    // ---- 引用管理 ----

    fun addConversationReference(conversationId: Uuid) {
        getOrCreateSession(conversationId).acquire()
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessions[conversationId]?.release()
    }

    private fun launchWithConversationReference(
        conversationId: Uuid,
        block: suspend () -> Unit
    ): Job = appScope.launch {
        addConversationReference(conversationId)
        try {
            block()
        } finally {
            removeConversationReference(conversationId)
        }
    }

    // ---- 对话状态访�?----

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        return getOrCreateSession(conversationId).state
    }

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> {
        val session = sessions[conversationId] ?: return flowOf(null)
        return session.generationJob
    }

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> {
        val session = sessions[conversationId] ?: return MutableStateFlow(null)
        return session.processingStatus
    }

    fun getRuntimeStateFlow(conversationId: Uuid): StateFlow<me.rerere.rikkahub.service.chat.RuntimeState> =
        getOrCreateRuntime(conversationId).runtimeState

    fun getQueueStatusFlow(conversationId: Uuid): StateFlow<me.rerere.rikkahub.service.chat.QueueStatus> =
        getOrCreateRuntime(conversationId).queueStatus

    fun getQueuedMessagesFlow(conversationId: Uuid): StateFlow<List<QueuedMessageUiEntry>> =
        getOrCreateRuntime(conversationId).queuedMessages

    fun getSteeringStatusFlow(conversationId: Uuid): StateFlow<Map<Uuid, me.rerere.rikkahub.data.ai.SteeringState>> =
        getOrCreateRuntime(conversationId).steeringStatus

    fun getSteeringEntriesFlow(
        conversationId: Uuid,
    ): StateFlow<Map<Uuid, me.rerere.rikkahub.service.chat.SteeringUiEntry>> =
        getOrCreateRuntime(conversationId).steeringEntries

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> {
        return _sessionsVersion.flatMapLatest {
            val currentSessions = sessions.values.toList()
            if (currentSessions.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(currentSessions.map { s ->
                    s.generationJob.map { job -> s.id to job }
                }) { pairs ->
                    pairs.filter { it.second != null }.toMap()
                }
            }
        }
    }

    // ---- 初始化对�?----

    suspend fun initializeConversation(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId)
        if (!session.isHydrated) {
            val conversation = conversationRepo.getConversationById(conversationId)
            if (conversation != null) {
                session.hydrateIfNeeded(conversation)
            } else {
                val currentSettings = settingsStore.settingsFlowRaw.first()
                val assistant = currentSettings.getCurrentAssistant()
                session.hydrateIfNeeded(
                    Conversation.ofId(
                        id = conversationId,
                        assistantId = assistant.id,
                        newConversation = true,
                    ).updateCurrentMessages(assistant.presetMessages)
                )
            }
        }
        settingsStore.updateAssistant(session.state.value.assistantId)
    }

    // ---- 发送消�?----

    fun sendMessage(conversationId: Uuid, content: List<UIMessagePart>, answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return
        // Ordinary sends are in-memory FIFO commands.  Explicit interrupt UI
        // actions use submitEmergency(InterruptCommand) instead.
        appScope.launch {
            submitUserMessage(conversationId, content, answer, CommandOrigin.APP_UI)
        }
    }

    /**
     * Runs one command, and drops its run-local continuation decision when the run is over.
     *
     * The decision is only meaningful while a run is executing: the durable half of it — the
     * barrier, its revision, its state — lives in the message graph. Forgetting it here is
     * therefore not a cleanup of state anything still reads; it is what keeps this map bounded by
     * the commands actually running rather than by every Claude P command the process ever
     * admitted.
     */
    private suspend fun executeRuntimeCommand(
        envelope: CommandEnvelope<out ChatCommand>,
        control: GenerationRunControl,
    ): RunOutcome = try {
        executeRuntimeCommandBody(envelope, control)
    } finally {
        claudePSessionAdmissions.forget(envelope.id)
    }

    private suspend fun executeRuntimeCommandBody(
        envelope: CommandEnvelope<out ChatCommand>,
        control: GenerationRunControl,
    ): RunOutcome {
        val command = envelope.command
        val agentTiming = envelope.agentTimingSubmission?.handle
        agentTiming?.bindCommand(envelope.id)
        me.rerere.rikkahub.service.chat.emergencyStopCommandBlockReason(
            active = agentSafetySettings.emergencyStopFlow.first(),
            command = command,
        )?.let { reason -> return RunOutcome.Rejected(reason) }
        me.rerere.rikkahub.service.chat.SystemAssistantCommandSecurityPolicy
            .commandBlockReason(envelope.origin, command)
            ?.let { reason -> return RunOutcome.Rejected(reason) }

        memorySourceReadinessFailureOrNull(
            command = command,
            readiness = durableRegenerationSourceReadiness,
        )?.let { error ->
            Log.w(TAG, "Model-facing command blocked before durable command recovery", error)
            return RunOutcome.Rejected("durable_regeneration_recovery_unavailable")
        }

        // A named refusal, beside the emergency-stop and second-user policy blocks above, and for
        // the same reason: a command that may not run has to say so as a result rather than fail as
        // an exception. This is where the Claude P continuation invariant is enforced — every model
        // dispatch either carries the decision its admission produced, or is refused by name here.
        claudePDispatchRefusal(envelope)?.let { refusal ->
            Log.w(TAG, "Claude P continuation refused a dispatch: ${refusal.code}")
            return RunOutcome.Rejected(refusal.code)
        }

        val acceptedSystemAssistantTarget = if (
            envelope.origin == CommandOrigin.SYSTEM_ASSISTANT && command !is StopCommand
        ) {
            when (val validation =
                me.rerere.rikkahub.service.chat.SystemAssistantCommandSecurityPolicy
                    .validateAcceptedTarget(
                        command = command,
                        conversationId = envelope.conversationId,
                        settings = settingsStore.settingsFlow.first(),
                        persistedConversation = conversationRepo.getConversationById(
                            envelope.conversationId,
                        ),
                    )
            ) {
                is me.rerere.rikkahub.service.chat.SystemAssistantTargetValidation.Invalid ->
                    return RunOutcome.Rejected(validation.reason)
                is me.rerere.rikkahub.service.chat.SystemAssistantTargetValidation.Valid -> validation
            }
        } else {
            null
        }
        val acceptedQuickCaptureTarget = if (
            envelope.origin == CommandOrigin.QUICK_CAPTURE && command !is StopCommand
        ) {
            when (val validation = me.rerere.rikkahub.service.chat.QuickCaptureCommandSecurityPolicy
                .validateAccepted(
                    command = command,
                    conversationId = envelope.conversationId,
                    settings = settingsStore.settingsFlow.first(),
                    persistedConversation = conversationRepo.getConversationById(envelope.conversationId),
                )
            ) {
                is me.rerere.rikkahub.service.chat.QuickCaptureTargetValidation.Invalid ->
                    return RunOutcome.Rejected(validation.reason)
                is me.rerere.rikkahub.service.chat.QuickCaptureTargetValidation.Valid -> validation
            }
        } else {
            null
        }
        val acceptedAssistantSnapshot = acceptedSystemAssistantTarget?.assistant ?: acceptedQuickCaptureTarget?.assistant

        return when (command) {
            is PetDialogueCommand -> RunOutcome.Rejected("pet_dialogue_command_is_memory_only")
            is SendMessageCommand -> executeSendMessageLegacy(
                commandId = envelope.id,
                branchAnchorMessageId = envelope.lineage?.branchAnchorMessageId ?: Uuid.random(),
                origin = envelope.origin,
                conversationId = envelope.conversationId,
                content = command.content,
                control = control,
                acceptedAssistantSnapshot = acceptedAssistantSnapshot,
                agentTiming = agentTiming,
            )

            is InterruptCommand -> executeSendMessageLegacy(
                commandId = envelope.id,
                branchAnchorMessageId = envelope.lineage?.branchAnchorMessageId ?: Uuid.random(),
                origin = envelope.origin,
                conversationId = envelope.conversationId,
                content = command.replacement.content,
                control = control,
                acceptedAssistantSnapshot = acceptedAssistantSnapshot,
                agentTiming = agentTiming,
            )

            is InterruptRegenerateCommand -> executeRegenerateInline(
                envelope.conversationId,
                envelope.origin,
                command.regeneration,
                control,
                agentTiming,
                envelope.id,
            )

            is ToolApprovalCommand -> executeToolApprovalInline(
                conversationId = envelope.conversationId,
                command = command,
                control = control,
                origin = envelope.origin,
                envelopeId = envelope.id,
            )

            is RegenerateCommand -> executeRegenerateInline(
                envelope.conversationId,
                envelope.origin,
                command,
                control,
                agentTiming,
                envelope.id,
            )

            is ResumeAfterApprovalCommand -> {
                handleMessageComplete(
                    envelope.conversationId,
                    origin = envelope.origin,
                    runControl = control,
                    activeCommandId = envelope.id,
                    agentTiming = agentTiming,
                )
                val pending = pendingToolIds(envelope.conversationId)
                if (pending.isNotEmpty()) {
                    RunOutcome.WaitingApproval(pending)
                } else {
                    RunOutcome.Completed()
                }
            }

            is NormalCommand -> RunOutcome.Rejected("Unsupported normal command: ${command::class.simpleName}")
            is StopCommand -> RunOutcome.Stopped(me.rerere.rikkahub.service.chat.InterruptCleanupResult.Completed)
            is me.rerere.rikkahub.service.chat.SteerCommand -> {
                finishControlAuthority(envelope, control)
                RunOutcome.Completed()
            }
            is me.rerere.rikkahub.service.chat.CancelCurrentToolCommand -> {
                val request = control.requestCancelTool(
                    command.toolCallId,
                    me.rerere.rikkahub.data.ai.tools.ToolCancelReason(
                        "User cancelled tool ${command.toolCallId}",
                    ),
                )
                if (request is me.rerere.rikkahub.data.ai.tools.CancelRequestResult.NotFound) {
                    RunOutcome.Rejected("Tool call not found")
                } else {
                    val termination = control.awaitToolTermination(
                        command.toolCallId,
                        2.seconds,
                    )
                    if (termination ==
                        me.rerere.rikkahub.data.ai.tools.ToolTerminationState.StoppedConfirmed
                    ) {
                        finishControlAuthority(envelope, control)
                        RunOutcome.Completed()
                    } else {
                        RunOutcome.Rejected("Tool termination state is unknown")
                    }
                }
            }
        }
    }

    private suspend fun executeSendMessageLegacy(
        commandId: Uuid,
        branchAnchorMessageId: Uuid,
        origin: CommandOrigin,
        conversationId: Uuid,
        content: RawUserContent,
        control: GenerationRunControl,
        acceptedAssistantSnapshot: Assistant?,
        agentTiming: AgentTimingHandle?,
    ): RunOutcome = withCommandHeadlessScope(conversationId, origin, control) {
        executeSendMessageScoped(
            commandId = commandId,
            branchAnchorMessageId = branchAnchorMessageId,
            origin = origin,
            conversationId = conversationId,
            content = content,
            control = control,
            acceptedAssistantSnapshot = acceptedAssistantSnapshot,
            agentTiming = agentTiming,
        )
    }

    private suspend fun executeSendMessageScoped(
        commandId: Uuid,
        branchAnchorMessageId: Uuid,
        origin: CommandOrigin,
        conversationId: Uuid,
        content: RawUserContent,
        control: GenerationRunControl,
        acceptedAssistantSnapshot: Assistant?,
        agentTiming: AgentTimingHandle?,
    ): RunOutcome {
        try {
            val session = getOrCreateSession(conversationId)
            val targetBeforeMutation = session.state.value
            if (acceptedAssistantSnapshot != null &&
                (targetBeforeMutation.id != conversationId ||
                    targetBeforeMutation.assistantId != acceptedAssistantSnapshot.id)
            ) {
                return RunOutcome.Rejected(
                    "The accepted assistant target no longer matches this conversation.",
                )
            }
            finishInterruptedPendingTools(conversationId)
            val currentConversation = session.state.value
            val settings = settingsStore.settingsFlow.first()
            val assistant = acceptedAssistantSnapshot
                ?: settings.getAssistantById(currentConversation.assistantId)
                ?: settings.getCurrentAssistant()
            // Submission freezes the exact processed payload before the authority admission.
            // Reprocessing here could change the durable branch anchor after a settings update.
            val processedContent = content.parts
            val fastPath = if (content.answer) {
                fastPathRouter.resolve(
                    FastPathContext(
                        commandId = commandId,
                        conversation = currentConversation,
                        content = processedContent,
                        origin = origin,
                        assistant = assistant,
                    )
                )
            } else FastPathDecision.NotMatched
            val fastPathPlan = buildFastPathCommitPlan(processedContent, fastPath)
            if (fastPathPlan is FastPathCommitPlan.Rejected) return RunOutcome.Rejected(fastPathPlan.reason)
            val userContent = when (fastPathPlan) {
                is FastPathCommitPlan.Handled -> fastPathPlan.userContent
                is FastPathCommitPlan.ContinueToModel -> fastPathPlan.userContent
                is FastPathCommitPlan.NotMatched -> fastPathPlan.userContent
                is FastPathCommitPlan.Rejected -> emptyList()
            }
            val anchoredUserMessage = content.toAnchoredUserMessage(
                messageId = branchAnchorMessageId,
                effectiveParts = userContent,
            )
            val existingAnchorIndex = currentConversation.messageNodes.indexOfFirst { node ->
                node.messages.any { it.id == branchAnchorMessageId }
            }
            if (existingAnchorIndex >= 0) {
                val existing = currentConversation.messageNodes[existingAnchorIndex].messages
                    .first { it.id == branchAnchorMessageId }
                if (existing.role != MessageRole.USER || existing.parts != userContent ||
                    existing.annotations != content.annotations
                ) {
                    return RunOutcome.Conflict("command_branch_anchor_identity_conflict")
                }
                val alreadyHasAssistantResult = currentConversation.messageNodes
                    .drop(existingAnchorIndex + 1)
                    .any { node -> node.messages.any { it.role == MessageRole.ASSISTANT } }
                if (alreadyHasAssistantResult) {
                    val resultAssistant = currentConversation.messageNodes
                        .drop(existingAnchorIndex + 1)
                        .asSequence()
                        .flatMap { node -> node.messages.asSequence() }
                        .last { it.role == MessageRole.ASSISTANT }
                    val authority = control.runtimeCommandAuthority()
                    val pending = pendingToolIds(conversationId)
                    if (authority != null) {
                        if (pending.isNotEmpty()) {
                            authority.checkpointWaiting(
                                conversation = currentConversation,
                                assistantMessageId = resultAssistant.id,
                                approvalMutation = { messageId, revision ->
                                    executionMessageAuthorityBinder
                                        .requireBoundInCurrentAuthorityTransaction(
                                            resultAssistant.persistedToolExecutionIds(control).map {
                                                executionId ->
                                                me.rerere.rikkahub.data.execution
                                                    .ExecutionOwningMessageAuthority(
                                                        executionId = executionId,
                                                        assistantMessageId = messageId,
                                                        assistantMessageRevision = revision,
                                                    )
                                            },
                                        )
                                },
                            )
                        } else {
                            authority.finish(
                                conversation = currentConversation,
                                terminalState = me.rerere.rikkahub.service.chat
                                    .DurableCommandState.COMPLETED,
                                kind = me.rerere.rikkahub.service.chat.RuntimeAuthorityTerminalKind
                                    .GENERATION_FINAL_SAVED,
                                resultAssistantMessageId = resultAssistant.id,
                                executionIds = resultAssistant.persistedToolExecutionIds(control),
                            )
                        }
                    }
                    _generationDoneFlow.emit(conversationId)
                    return pending.takeIf { it.isNotEmpty() }
                        ?.let(RunOutcome::WaitingApproval)
                        ?: RunOutcome.Completed()
                }
            }
            val responseCorrelationAnnotation = content.annotations
                .filter { it.isResponseCorrelation() }
                .singleOrNull()
            val withUser = if (existingAnchorIndex >= 0) {
                currentConversation
            } else {
                currentConversation.copy(
                    messageNodes = currentConversation.messageNodes + anchoredUserMessage.toMessageNode(),
                )
            }
            when (fastPathPlan) {
                is FastPathCommitPlan.Handled -> {
                    val assistantMessage = UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = fastPathPlan.assistantContent,
                        annotations = listOfNotNull(responseCorrelationAnnotation),
                    )
                    val finalConversation = withUser.copy(
                        messageNodes = withUser.messageNodes + assistantMessage.toMessageNode(),
                    )
                    val authority = control.runtimeCommandAuthority()
                    if (authority != null) {
                        try {
                            val fastExecutionId = me.rerere.rikkahub.data.execution.ExecutionRecordIds
                                .tool(commandId.toString(), "fast-$commandId")
                            val executionIds = if (
                                executionMessageAuthorityBinder.find(fastExecutionId) != null
                            ) listOf(fastExecutionId) else emptyList()
                            authority.finish(
                                conversation = finalConversation,
                                terminalState = me.rerere.rikkahub.service.chat.DurableCommandState.COMPLETED,
                                kind = me.rerere.rikkahub.service.chat.RuntimeAuthorityTerminalKind
                                    .FAST_PATH_HANDLED,
                                resultAssistantMessageId = assistantMessage.id,
                                executionIds = executionIds,
                            )
                        } catch (saveError: Throwable) {
                            if (!authority.isTerminalCommitted()) {
                                runCatching { authority.finishAfterFinalSaveFailure() }
                            }
                            throw saveError
                        }
                        updateConversation(conversationId, finalConversation)
                        conversationRepo.refreshSearchProjection(finalConversation)
                    } else {
                        saveConversation(conversationId, finalConversation)
                    }
                    me.rerere.rikkahub.skills.FastPathRouterLog.record(
                        me.rerere.rikkahub.skills.FastPathRouterLog.Entry(
                            whenMs = System.currentTimeMillis(),
                            intent = "handled",
                            toolName = "fast_path",
                            userText = processedContent.filterIsInstance<UIMessagePart.Text>()
                                .joinToString(" ") { it.text }.take(120),
                            resultPreview = fastPathPlan.assistantContent.joinToString { it.toString() }.take(200),
                            skippedLlm = true,
                        )
                    )
                }
                else -> {
                    // The exact USER anchor was already persisted with schema-v2 ADMITTED before
                    // this run could claim the command. Avoid a second non-combined graph write.
                    if (control.runtimeCommandAuthority() == null) {
                        saveConversation(conversationId, withUser)
                    } else {
                        updateConversation(conversationId, withUser)
                    }
                    if (content.answer) {
                        // Must surface generation failures as RunOutcome.Failed. Swallowing them
                        // (propagateFailure=false) marks the durable command COMPLETED after only
                        // the user message is saved — UI shows loading then silence with no reply.
                        handleMessageComplete(
                            conversationId,
                            origin = origin,
                            runControl = control,
                            activeCommandId = commandId,
                            acceptedAssistantSnapshot = acceptedAssistantSnapshot,
                            responseCorrelationAnnotation = responseCorrelationAnnotation,
                            propagateFailure = true,
                            agentTiming = agentTiming,
                        )
                    } else {
                        finishControlAuthority(conversationId, control)
                    }
                }
            }
            _generationDoneFlow.emit(conversationId)
            return pendingToolIds(conversationId).takeIf { it.isNotEmpty() }
                ?.let { RunOutcome.WaitingApproval(it) }
                ?: (getConversationFlow(conversationId).value.latestFinalAnswerFailure()?.let {
                    RunOutcome.Failed(it)
                } ?: RunOutcome.Completed())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            return RunOutcome.Failed(e)
        }
    }

    private fun pendingToolIds(conversationId: Uuid): Set<String> =
        getConversationFlow(conversationId).value.selectedPendingToolIds()

    private fun UIMessage.toolExecutionIds(
        control: GenerationRunControl?,
    ): List<String> {
        val runId = control?.runId?.toString() ?: return emptyList()
        return parts.filterIsInstance<UIMessagePart.Tool>()
            .map { tool ->
                me.rerere.rikkahub.data.execution.ExecutionRecordIds.tool(runId, tool.toolCallId)
            }
            .distinct()
    }

    private suspend fun UIMessage.persistedToolExecutionIds(
        control: GenerationRunControl?,
    ): List<String> = toolExecutionIds(control).filter { executionId ->
        secondUserApprovalLifecycle.findExecution(executionId) != null
    }

    /**
     * Phase 16 �?fast-path router entry. Returns `true` if the router successfully handled
     * the turn (synthesised an assistant message and stored it) so the caller knows to skip
     * the normal LLM dispatch. Returns `false` to fall through.
     */

    private val fastPathRouter = FastPathRouter { context ->
        if (me.rerere.rikkahub.data.ai.tools.HeadlessConversations.isHeadless(context.conversation.id)) {
            return@FastPathRouter FastPathDecision.NotMatched
        }
        if (!context.assistant.fastPathRouterEnabled) return@FastPathRouter FastPathDecision.NotMatched
        val userText = context.content.filterIsInstance<UIMessagePart.Text>()
            .joinToString(" ") { it.text }.trim()
        if (userText.isBlank()) return@FastPathRouter FastPathDecision.NotMatched
        val match = me.rerere.rikkahub.skills.FastPathRouter.route(userText)
            ?: return@FastPathRouter FastPathDecision.NotMatched
        val tools = localTools.getTools(
            context.assistant.localTools,
            me.rerere.rikkahub.data.ai.tools.ToolInvocationContext(
                callerAssistantId = context.assistant.id.toString(),
                callerConversationId = context.conversation.id.toString(),
                callerRunId = context.commandId.toString(),
                callerWorkspaceId = context.assistant.workspaceId?.toString(),
                callOrigin = resolveToolOrigin(context.conversation.id, context.origin),
                isHeadless = false,
            ),
        )
        val tool = tools.firstOrNull { it.name == match.toolName }
            ?: return@FastPathRouter FastPathDecision.NotMatched
        val hardlineReason = me.rerere.rikkahub.data.ai.tools.HardlineCommandGuard
            .checkTool(match.toolName, match.args.toString())
        if (hardlineReason != null) return@FastPathRouter FastPathDecision.NotMatched
        val rendered = try {
            val callOrigin = resolveToolOrigin(context.conversation.id, context.origin)
            refreshSecondUserAuthorityForInvocation(
                assistant = context.assistant,
                conversation = context.conversation,
                origin = callOrigin,
            )
            val privilege = me.rerere.rikkahub.privilege.DefaultPrivilegedSessionResolver.resolve(
                assistant = context.assistant,
                conversation = context.conversation,
                origin = callOrigin,
            )
            val capabilitySubject = capabilitySubjectFor(
                assistant = context.assistant,
                conversationId = context.conversation.id,
                origin = callOrigin,
                privilege = privilege,
            )
            val runtimeResult = toolRuntime.execute(
                me.rerere.rikkahub.data.ai.execution.ToolExecutionPlanRequest(
                    toolCallId = "fast-${context.commandId}",
                    toolName = tool.name,
                    toolSchemaFingerprint = me.rerere.rikkahub.toolcatalog.ToolCatalogSnapshot
                        .fromDefinitions(listOf(tool))
                        .entry(tool.name)
                        ?.schemaFingerprint,
                    args = match.args,
                    executionContext = me.rerere.rikkahub.data.ai.tools.ToolExecutionContext(
                        runId = context.commandId,
                        conversationId = context.conversation.id,
                        assistantId = context.assistant.id.toString(),
                        callOrigin = callOrigin,
                        commandId = context.commandId,
                        capabilitySubject = capabilitySubject,
                        selectedPrivilegedConversation = privilege.isPrivileged,
                    ),
                    startableTool = null,
                    legacyExecute = { input -> tool.execute(input.jsonObject) },
                    runControl = null,
                    wallClockBudgetMs = FAST_PATH_TOOL_BUDGET_MS,
                    preExecutionGate = {
                        when (val gate = toolExecutionGate.evaluate(
                            toolName = tool.name,
                            origin = callOrigin,
                            conversationId = context.conversation.id,
                            commandId = context.commandId,
                            arguments = match.args,
                            capabilitySubject = capabilitySubject,
                            selectedPrivilegedConversation = privilege.isPrivileged,
                            unrestrictedOverride = false,
                        )) {
                            me.rerere.rikkahub.data.ai.ToolExecutionGate.GateResult.Allowed ->
                                me.rerere.rikkahub.data.ai.execution.ToolPreExecutionDecision.Allow
                            is me.rerere.rikkahub.data.ai.ToolExecutionGate.GateResult.Denied ->
                                me.rerere.rikkahub.data.ai.execution.ToolPreExecutionDecision.Deny(
                                    errorCode = "tool_blocked",
                                    reason = gate.reason,
                                )
                        }
                    },
                )
            )
            val out = runtimeResult.output
            val rawText = out.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
            val parsed = runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(rawText).jsonObject
            }.getOrNull()
            val formatted = if (match.format != null && parsed != null) {
                runCatching { match.format.invoke(parsed) }.getOrNull()
            } else null
            formatted?.takeIf { it.isNotBlank() } ?: rawText
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            me.rerere.rikkahub.skills.FastPathRouterLog.record(
                me.rerere.rikkahub.skills.FastPathRouterLog.Entry(
                    whenMs = System.currentTimeMillis(),
                    intent = match.intent,
                    toolName = match.toolName,
                    userText = userText.take(120),
                    resultPreview = "tool threw: ${e.message?.take(80)}",
                    skippedLlm = false,
                )
            )
            return@FastPathRouter FastPathDecision.ContinueToModel(context.content)
        }
        FastPathDecision.Handled(listOf(UIMessagePart.Text(rendered)))
    }

    private fun preprocessUserInputParts(
        parts: List<UIMessagePart>,
        assistant: Assistant,
    ): List<UIMessagePart> {
        return parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    part.copy(
                        text = part.text.replaceRegexes(
                            assistant = assistant,
                            scope = AssistantAffectScope.USER,
                            visual = false
                        )
                    )
                }

                else -> part
            }
        }
    }

    // ---- 重新生成消息 ----

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true,
        agentTimingSubmission: AgentTimingSubmissionToken? = null,
    ) {
        val baseline = getConversationFlow(conversationId).value
        val baselineSources = baseline.selectedMemorySourceVersions()
        val policy = if (regenerateAssistantMsg) {
            me.rerere.rikkahub.service.chat.RegeneratePolicy.INTERRUPT_CURRENT
        } else {
            me.rerere.rikkahub.service.chat.RegeneratePolicy.REJECT_IF_BUSY
        }
        val command = RegenerateCommand(
            targetMessageId = message.id,
            expectedTargetVersion = 0L,
            expectedBranchHeadMessageId = message.id,
            policy = policy,
            baselineAssistantScopeId = baseline.assistantId.toString(),
            baselineSelectedMessageIds = baselineSources
                .map(MemorySourceVersion::messageId)
                .distinct()
                .sorted(),
            baselineSelectedSourceVersions = baselineSources.sortedWith(
                compareBy(MemorySourceVersion::messageId, MemorySourceVersion::consumedTextDigest),
            ),
        )
        appScope.launch {
            val tracked = submitCommandTracked(
                conversationId = conversationId,
                command = command,
                origin = CommandOrigin.APP_UI,
                dedupeKey = null,
                expiresAt = null,
                dependencies = emptyList(),
                agentTimingSubmission = agentTimingSubmission,
            )
            val submissionFailure = when (val submission = tracked.submission) {
                is SubmitResult.Accepted -> null
                is SubmitResult.QueueFull -> "Conversation queue is full (${submission.limit})"
                is SubmitResult.Rejected -> submission.reason
                is SubmitResult.RuntimeUnavailable -> submission.reason
            }
            if (submissionFailure != null) {
                addError(
                    IllegalStateException(submissionFailure),
                    conversationId,
                    title = context.getString(R.string.error_title_regenerate_message),
                )
                return@launch
            }

            val terminalFailure = when (val outcome = tracked.outcome.await()) {
                is CommandOutcome.Conflict -> outcome.reason
                is CommandOutcome.Rejected -> outcome.reason
                is CommandOutcome.NotApplied -> outcome.reason
                is CommandOutcome.Failed ->
                    outcome.error.message ?: outcome.error.toString()
                is CommandOutcome.SkippedDependencyFailed ->
                    "Required command failed: ${outcome.dependencyId}"
                else -> null
            }
            terminalFailure?.let { reason ->
                addError(
                    IllegalStateException(reason),
                    conversationId,
                    title = context.getString(R.string.error_title_regenerate_message),
                )
            }
        }
    }

    private suspend fun submitOwnerRetryLastAssistant(conversationId: Uuid): SubmitResult {
        val conversation = conversationRepo.getConversationById(conversationId)
            ?: return SubmitResult.Rejected("Conversation not found")
        val message = conversation.currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
            ?: return SubmitResult.Rejected("No assistant message to retry")
        val baselineSources = conversation.selectedMemorySourceVersions()
        return submitCommand(
            conversationId = conversationId,
            command = RegenerateCommand(
                targetMessageId = message.id,
                expectedTargetVersion = 0L,
                expectedBranchHeadMessageId = message.id,
                policy = me.rerere.rikkahub.service.chat.RegeneratePolicy.REJECT_IF_BUSY,
                baselineAssistantScopeId = conversation.assistantId.toString(),
                baselineSelectedMessageIds = baselineSources
                    .map(MemorySourceVersion::messageId)
                    .distinct()
                    .sorted(),
                baselineSelectedSourceVersions = baselineSources.sortedWith(
                    compareBy(
                        MemorySourceVersion::messageId,
                        MemorySourceVersion::consumedTextDigest,
                    ),
                ),
            ),
            origin = CommandOrigin.APP_UI,
        )
    }

    private suspend fun executeRegenerateInline(
        conversationId: Uuid,
        origin: CommandOrigin,
        command: RegenerateCommand,
        control: GenerationRunControl,
        agentTiming: AgentTimingHandle?,
        commandId: Uuid,
    ): RunOutcome {
        ensureHydrated(conversationId)
        val session = getOrCreateSession(conversationId)
        val conversation = session.state.value
        val durableBaseline = command.durableRegenerationBaselineOrNull()
        val baselineAssistantScopeId = command.baselineAssistantScopeId
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: conversation.assistantId.toString()
        val currentSources = conversation.selectedMemorySourceVersions()
        val baselineSelectedMessageIds = durableBaseline?.selectedMessageIds
            ?: currentSources.map(MemorySourceVersion::messageId).distinct().sorted()
        val baselineSelectedSourceVersions = durableBaseline?.selectedSourceVersions
            ?: currentSources.sortedWith(
                compareBy(MemorySourceVersion::messageId, MemorySourceVersion::consumedTextDigest),
            )
        // Do not tombstone from this baseline yet. A rejected/failed replay restores its transient
        // graph and must keep the old memory source valid; only the final authority commit below
        // is allowed to invalidate deleted or superseded source versions.
        val message = conversation.messageNodes.asSequence()
            .flatMap { it.messages.asSequence() }
            .firstOrNull { it.id == command.targetMessageId }
            ?: return RunOutcome.Conflict("Target message no longer exists")
        // Look up by id: UIMessagePart.metadata is a var, so contains(message) equals can
        // fail after streaming updates even when the id is still present.
        val node = conversation.getMessageNodeByMessageId(command.targetMessageId)
            ?: return RunOutcome.Conflict("Target message is not in the conversation")
        val indexAt = conversation.messageNodes.indexOf(node)
        if (indexAt < 0) return RunOutcome.Conflict("Target message is not in the conversation")
        val transientWriteNowMs = System.currentTimeMillis()
        var deferredPostCommit: DeferredGenerationPostCommit? = null
        val outcome = runRegenerationTransaction(
            restore = {
                if (control.runtimeCommandAuthority()?.isTerminalCommitted() != true) {
                    val restored = mergeConversationState(conversationId) { current ->
                        current.copy(messageNodes = conversation.messageNodes)
                    }
                    saveConversation(
                        conversationId = conversationId,
                        conversation = restored,
                        sourceInvalidationMode =
                            ConversationSourceInvalidationMode.SKIP_TRANSIENT_WRITE,
                        sourceInvalidationNowMs = transientWriteNowMs,
                    )
                }
            },
        ) {
            try {
                if (message.role == MessageRole.USER) {
                    val transientConversation = conversation.copy(
                        messageNodes = conversation.messageNodes.subList(0, indexAt + 1),
                    )
                    if (control.runtimeCommandAuthority() == null) {
                        saveConversation(
                            conversationId,
                            transientConversation,
                            sourceInvalidationMode =
                                ConversationSourceInvalidationMode.SKIP_TRANSIENT_WRITE,
                            sourceInvalidationNowMs = transientWriteNowMs,
                        )
                    } else {
                        // Keep the authority graph unchanged until final/WAITING can commit graph,
                        // source and command together. Streaming state remains process-local.
                        updateConversation(conversationId, transientConversation)
                    }
                    handleMessageComplete(
                        conversationId,
                        origin = origin,
                        runControl = control,
                        activeCommandId = commandId,
                        propagateFailure = true,
                        persistenceSourceInvalidationMode =
                            ConversationSourceInvalidationMode.SKIP_TRANSIENT_WRITE,
                        persistenceSourceInvalidationNowMs = transientWriteNowMs,
                        deferPostCommitActions = true,
                        onDeferredPostCommit = { deferredPostCommit = it },
                        agentTiming = agentTiming,
                    )
                } else if (command.policy != me.rerere.rikkahub.service.chat.RegeneratePolicy.REJECT_IF_BUSY) {
                    handleMessageComplete(
                        conversationId,
                        origin = origin,
                        messageRange = 0..<indexAt,
                        runControl = control,
                        activeCommandId = commandId,
                        propagateFailure = true,
                        persistenceSourceInvalidationMode =
                            ConversationSourceInvalidationMode.SKIP_TRANSIENT_WRITE,
                        persistenceSourceInvalidationNowMs = transientWriteNowMs,
                        deferPostCommitActions = true,
                        onDeferredPostCommit = { deferredPostCommit = it },
                        agentTiming = agentTiming,
                    )
                }
                val finalConversation = getConversationFlow(conversationId).value
                val runtimeAuthority = control.runtimeCommandAuthority()
                val authorityAlreadyCommitted = runtimeAuthority?.let { authority ->
                    authority.isTerminalCommitted() || authority.isWaitingCommitted()
                } == true
                val finalization = if (authorityAlreadyCommitted) {
                    me.rerere.rikkahub.data.repository.ConversationUpdateResult.Updated(
                        finalConversation.id,
                    )
                } else if (runtimeAuthority != null) {
                    // No provider path ran (for example an unsupported regeneration policy).
                    // Still commit the final graph/source/command as one authority decision.
                    runtimeAuthority.finish(
                        conversation = finalConversation,
                        terminalState = me.rerere.rikkahub.service.chat.DurableCommandState.COMPLETED,
                        kind = me.rerere.rikkahub.service.chat.RuntimeAuthorityTerminalKind
                            .CONTROL_ONLY,
                        resultAssistantMessageId = null,
                        sourceInvalidationMode =
                            ConversationSourceInvalidationMode.SKIP_TRANSIENT_WRITE,
                    )
                    updateConversation(conversationId, finalConversation)
                    conversationRepo.refreshSearchProjection(finalConversation)
                    me.rerere.rikkahub.data.repository.ConversationUpdateResult.Updated(
                        finalConversation.id,
                    )
                } else conversationRepo.finalizeTransientConversationUpdate(
                    conversation = finalConversation,
                    baselineAssistantScopeId = baselineAssistantScopeId,
                    baselineSelectedMessageIds = baselineSelectedMessageIds,
                    baselineSelectedSourceVersions = baselineSelectedSourceVersions,
                    sourceInvalidationNowMs = System.currentTimeMillis(),
                )
                when (finalization) {
                    is me.rerere.rikkahub.data.repository.ConversationUpdateResult.Updated -> {
                        finalConversation.selectedPendingToolIds().takeIf { it.isNotEmpty() }
                            ?.let(RunOutcome::WaitingApproval)
                            ?: RunOutcome.Completed()
                    }
                    is me.rerere.rikkahub.data.repository.ConversationUpdateResult.Missing ->
                        RunOutcome.Conflict("Conversation disappeared during regeneration commit")
                    is me.rerere.rikkahub.data.repository.ConversationUpdateResult.RetainedSecondUser ->
                        RunOutcome.Rejected("Conversation assistant changed during regeneration")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
                RunOutcome.Failed(e)
            }
        }
        if (outcome !is RunOutcome.Completed && outcome !is RunOutcome.WaitingApproval) {
            val authority = control.runtimeCommandAuthority()
            if (authority != null && !authority.isTerminalCommitted()) {
                val restored = conversationRepo.getConversationById(conversationId) ?: conversation
                authority.finish(
                    conversation = restored,
                    terminalState = me.rerere.rikkahub.service.chat.DurableCommandState.FAILED,
                    kind = me.rerere.rikkahub.service.chat.RuntimeAuthorityTerminalKind.FAILED_OTHER,
                    resultAssistantMessageId = null,
                    errorCode = "REGENERATION_FAILED",
                )
            }
        }
        if (outcome is RunOutcome.Completed || outcome is RunOutcome.WaitingApproval) {
            deferredPostCommit?.let(::scheduleGenerationPostCommit)
            // This notification intentionally lives outside runRegenerationTransaction. A
            // cancelled rendezvous emit must never restore the old graph after the final source
            // invalidation and conversation graph committed atomically.
            _generationDoneFlow.emit(conversationId)
        }
        return outcome
    }

    // ---- 处理工具调用审批 ----

    /** Scope of an "approve" decision. Once = this single tool call only. ChatScope =
     *  every future call of the same tool name in this conversation (until /new). Always =
     *  every future call of this tool name across the whole app, persisted to disk. */
    enum class ApprovalScope { Once, ChatScope, Always }

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
        scope: ApprovalScope = ApprovalScope.Once,
        toolName: String? = null,
        origin: CommandOrigin = CommandOrigin.APP_UI,
    ) {
        val timingSubmission = getConversationFlow(conversationId).value.currentMessages
            .lastOrNull { message ->
                message.parts.any { part ->
                    part is UIMessagePart.Tool && part.toolCallId == toolCallId
                }
            }
            ?.id
            ?.let { messageId ->
                agentTimingStore.submissionTokenForMessage(conversationId, messageId)
            }
        timingSubmission?.handle?.approvalDecisionSubmitted(
            result = when {
                answer != null -> AgentTimingEventResult.ANSWERED
                approved -> AgentTimingEventResult.SUCCESS
                else -> AgentTimingEventResult.DENIED
            },
        )
        val decision = when {
            answer != null -> ToolDecision.Answered(answer)
            approved -> ToolDecision.Approved
            else -> ToolDecision.Denied(reason)
        }
        appScope.launch {
            val projection = secondUserApprovalLifecycle.findLatest(
                conversationId = conversationId.toString(),
                toolCallId = toolCallId,
            )
            submitCommand(
                conversationId,
                ToolApprovalCommand(
                    toolCallId = toolCallId,
                    decision = decision,
                    toolName = toolName,
                    scope = scope.name,
                    approvalId = projection?.approvalId,
                    executionId = projection?.executionId,
                    expectedStateVersion = projection?.stateVersion,
                    resolutionRequestId = Uuid.random().toString(),
                ),
                origin,
                timingSubmission,
            )
        }
    }

    private suspend fun executeToolApprovalInline(
        conversationId: Uuid,
        command: ToolApprovalCommand,
        control: GenerationRunControl,
        origin: CommandOrigin,
        envelopeId: Uuid,
    ): RunOutcome {
        val timing = agentTimingStore.handleForRun(control.runId)
            ?: agentTimingStore.openHandleForConversation(conversationId)
        val decision = command.decision
        val approved = decision is ToolDecision.Approved
        val answer = (decision as? ToolDecision.Answered)?.answer
        val reason = (decision as? ToolDecision.Denied)?.reason.orEmpty()
        ensureHydrated(conversationId)
        val session = getOrCreateSession(conversationId)
        val conversation = session.state.value
        val exactIdentityPresent = command.approvalId != null && command.executionId != null &&
            command.expectedStateVersion != null
        if (decision !is ToolDecision.Denied && !exactIdentityPresent) {
            // Legacy positive payloads used a mutable latest-by-tool lookup and can approve a
            // newer execution after replay. Only denial may use that conservative legacy path.
            return RunOutcome.Rejected("approval_exact_identity_required")
        }
        val approvalProjection = if (command.approvalId != null && command.executionId != null) {
            secondUserApprovalLifecycle.findExact(
                approvalId = command.approvalId,
                executionId = command.executionId,
                conversationId = conversationId.toString(),
                toolCallId = command.toolCallId,
            )
        } else {
            secondUserApprovalLifecycle.findLatest(
                conversationId = conversationId.toString(),
                toolCallId = command.toolCallId,
            )
        }
        if (exactIdentityPresent && approvalProjection == null) {
            return RunOutcome.Rejected("approval_exact_identity_mismatch")
        }
        if (approvalProjection != null &&
            decision !is ToolDecision.Denied &&
            origin != CommandOrigin.APP_UI
        ) {
            return RunOutcome.Rejected("approval_requires_trusted_app")
        }
        if (
            approvalProjection != null &&
            approvalProjection.subjectType ==
                me.rerere.rikkahub.data.capability.SubjectType.LOCAL_SECOND_USER.name &&
            approvalProjection.subjectId != SecondUserAuthorityRegistry.current()?.subjectId &&
            decision !is ToolDecision.Denied
        ) {
            // A positive decision (including an answer that resumes execution) is tied to the
            // exact authority epoch that created the projection. A stale epoch can only be
            // revoked/denied; it can never resume a tool after reassignment.
            return RunOutcome.Rejected("second_user_authority_stale")
        }
        val newApprovalState = when {
            answer != null -> ToolApprovalState.Answered(answer)
            approved -> ToolApprovalState.Approved
            else -> ToolApprovalState.Denied(reason)
        }
        val persistedDecision = when {
            answer != null -> me.rerere.rikkahub.data.execution.PersistedApprovalDecision.ANSWERED
            approved -> me.rerere.rikkahub.data.execution.PersistedApprovalDecision.APPROVED
            else -> me.rerere.rikkahub.data.execution.PersistedApprovalDecision.DENIED
        }
        var foundPending = false
        var appliedPendingDecision = false
        var foundSameTerminal = false
        var foundConflictingTerminal = false
        val updatedNodes = conversation.messageNodes.map { node ->
            node.copy(messages = node.messages.map { msg ->
                msg.copy(parts = msg.parts.map { part ->
                    if (part !is UIMessagePart.Tool || part.toolCallId != command.toolCallId) return@map part
                    when (val transition = me.rerere.rikkahub.service.chat.resolveToolApproval(
                        current = part.approvalState,
                        requested = newApprovalState,
                    )) {
                        is me.rerere.rikkahub.service.chat.ToolApprovalTransition.Apply -> {
                            foundPending = true
                            appliedPendingDecision = true
                            part.copy(approvalState = transition.state)
                        }
                        me.rerere.rikkahub.service.chat.ToolApprovalTransition.Idempotent -> {
                            foundSameTerminal = true
                            part
                        }
                        me.rerere.rikkahub.service.chat.ToolApprovalTransition.Conflict -> {
                            foundConflictingTerminal = true
                            part
                        }
                        me.rerere.rikkahub.service.chat.ToolApprovalTransition.NotPending -> part
                    }
                })
            })
        }
        if (!foundPending) {
            if (foundSameTerminal && !foundConflictingTerminal && approvalProjection != null) {
                val durable = conversationRepo.getConversationById(conversationId) ?: conversation
                val canResume = durable.messageNodes
                    .flatMap { it.messages }
                    .flatMap { it.parts }
                    .filterIsInstance<UIMessagePart.Tool>()
                    .any { it.toolCallId == command.toolCallId && it.canResumeExecution }
                when (val replay = secondUserApprovalLifecycle.resolve(
                    currentConversation = durable,
                    updatedConversation = durable,
                    approvalId = approvalProjection.approvalId,
                    executionId = approvalProjection.executionId,
                    toolCallId = command.toolCallId,
                    decision = persistedDecision,
                    expectedStateVersion = command.expectedStateVersion,
                    resolutionRequestId = command.resolutionRequestId ?: envelopeId.toString(),
                    trustedAppApproval = origin == CommandOrigin.APP_UI,
                    authorityCommitInCurrentTransaction = { projection, owningCommandId ->
                        // An IN_FLIGHT approval belongs to a generation that has not ended: the
                        // Claude P peer is still blocked inside the Worker waiting for this call's
                        // answer. Creating a resume command here would start a second generation
                        // nobody asked for, so the decision is released to the waiter that is
                        // already there instead — see SecondUserApprovalLifecycle.releaseDecision.
                        if (!canResume || projection.isInFlightContinuation()) {
                            null
                        } else {
                            durableCommandQueue.ensureApprovalResumeInCurrentTransaction(
                                conversationId = conversationId,
                                approvalId = projection.approvalId,
                                resolutionRequestId = projection.resolutionRequestId.orEmpty(),
                                resolvedAtMs = projection.resolvedAtMs,
                                approvalCommandId = envelopeId,
                                owningWaitingCommandId = owningCommandId,
                            )
                        }
                    },
                    authorityPostCommit = { commit ->
                        // Publish the graph committed by the outer Room transaction before the
                        // resume row can reach a runtime channel.
                        updateConversation(conversationId, durable)
                        adoptApprovalResumeCommit(conversationId, commit, timing)
                    },
                )) {
                    is me.rerere.rikkahub.data.execution.ApprovalResolutionResult.Applied,
                    is me.rerere.rikkahub.data.execution.ApprovalResolutionResult.Idempotent -> {
                        conversationRepo.getConversationById(conversationId)?.let {
                            updateConversation(conversationId, it)
                        }
                    }
                    is me.rerere.rikkahub.data.execution.ApprovalResolutionResult.Conflict ->
                        return RunOutcome.Conflict(replay.reasonCode)
                    me.rerere.rikkahub.data.execution.ApprovalResolutionResult.Missing ->
                        return RunOutcome.Rejected("approval_projection_missing")
                    me.rerere.rikkahub.data.execution.ApprovalResolutionResult.TrustedAppRequired ->
                        return RunOutcome.Rejected("approval_requires_trusted_app")
                }
                commitApprovalScopeGrant(conversationId, command, approved)
            }
            return when {
                foundConflictingTerminal -> RunOutcome.Conflict("Tool approval already resolved with another decision")
                foundSameTerminal -> RunOutcome.Completed()
                else -> RunOutcome.Rejected("Tool approval is no longer pending")
            }
        }
        val updatedConversation = conversation.copy(messageNodes = updatedNodes)
        val hasPendingAfterUpdate = updatedNodes
            .flatMap { it.messages }
            .flatMap { it.parts }
            .any { it is UIMessagePart.Tool && it.isPending }
        val shouldEnsureResume = me.rerere.rikkahub.service.chat.shouldResumeAfterApproval(
            appliedPendingDecision = appliedPendingDecision,
            hasPendingAfterUpdate = hasPendingAfterUpdate,
        )
        var resumeEnsuredInApprovalTransaction = false
        val resolvedProjection = if (approvalProjection == null) {
            saveConversation(conversationId, updatedConversation)
            timing?.checkpoint(AgentTimingEventKind.APPROVAL_COMMIT)
            null
        } else {
            when (val result = secondUserApprovalLifecycle.resolve(
                currentConversation = conversation,
                updatedConversation = updatedConversation,
                approvalId = approvalProjection.approvalId,
                executionId = approvalProjection.executionId,
                toolCallId = command.toolCallId,
                decision = persistedDecision,
                expectedStateVersion = command.expectedStateVersion,
                resolutionRequestId = command.resolutionRequestId ?: envelopeId.toString(),
                trustedAppApproval = origin == CommandOrigin.APP_UI,
                authorityCommitInCurrentTransaction = { projection, owningCommandId ->
                    // Same rule as the sibling site above: an IN_FLIGHT approval's generation has
                    // not ended, so its decision releases the live waiter instead of creating a
                    // resume command — which here would be a second generation.
                    if (!shouldEnsureResume || projection.isInFlightContinuation()) {
                        null
                    } else {
                        durableCommandQueue.ensureApprovalResumeInCurrentTransaction(
                            conversationId = conversationId,
                            approvalId = projection.approvalId,
                            resolutionRequestId = projection.resolutionRequestId.orEmpty(),
                            resolvedAtMs = projection.resolvedAtMs,
                            approvalCommandId = envelopeId,
                            owningWaitingCommandId = owningCommandId,
                        )
                    }
                },
                authorityPostCommit = { commit ->
                    // A runtime must never observe the pre-commit Pending graph after it sees the
                    // durable resume command.
                    resumeEnsuredInApprovalTransaction = true
                    updateConversation(conversationId, updatedConversation)
                    adoptApprovalResumeCommit(conversationId, commit, timing)
                },
            )) {
                is me.rerere.rikkahub.data.execution.ApprovalResolutionResult.Applied -> {
                    updateConversation(conversationId, updatedConversation)
                    timing?.checkpoint(AgentTimingEventKind.APPROVAL_COMMIT)
                    result.projection
                }
                is me.rerere.rikkahub.data.execution.ApprovalResolutionResult.Idempotent -> {
                    conversationRepo.getConversationById(conversationId)?.let {
                        updateConversation(conversationId, it)
                    }
                    result.projection
                }
                is me.rerere.rikkahub.data.execution.ApprovalResolutionResult.Conflict ->
                    return RunOutcome.Conflict(result.reasonCode)
                me.rerere.rikkahub.data.execution.ApprovalResolutionResult.Missing ->
                    return RunOutcome.Rejected("approval_projection_missing")
                me.rerere.rikkahub.data.execution.ApprovalResolutionResult.TrustedAppRequired ->
                    return RunOutcome.Rejected("approval_requires_trusted_app")
            }
        }
        // Broader allow-list authority is a consequence of a successfully committed exact
        // approval. Never grant it before the approval CAS/graph/execution transaction succeeds.
        commitApprovalScopeGrant(conversationId, command, approved)
        val committedNodes = getConversationFlow(conversationId).value.messageNodes
        val hasPending = committedNodes
            .flatMap { it.messages }
            .flatMap { it.parts }
            .any { it is UIMessagePart.Tool && it.isPending }
        if (me.rerere.rikkahub.service.chat.shouldResumeAfterApproval(
                appliedPendingDecision = appliedPendingDecision,
                hasPendingAfterUpdate = hasPending,
            )) {
            // Resume through the runtime's dedicated approval lane. This keeps the
            // approval run single-owner and prevents a second model continuation
            // from racing ordinary queued messages.
            if (resolvedProjection == null) {
                timing?.mark(AgentTimingEventKind.RESUME_ENQUEUED)
                submitCommand(
                    conversationId = conversationId,
                    command = ResumeAfterApprovalCommand,
                    origin = CommandOrigin.INTERNAL,
                    dedupeKey = null,
                    expiresAt = null,
                    dependencies = emptyList(),
                    agentTimingSubmission = timing?.let(agentTimingStore::tokenForHandle),
                    parentCommandId = envelopeId,
                )
            } else if (!resumeEnsuredInApprovalTransaction) {
                // Exact v2 projections must have admitted their deterministic resume in the
                // approval transaction. Reaching this branch means the boundary was not proven;
                // fail closed and leave the durable WAITING owner intact for repair/replay.
                return RunOutcome.Conflict("approval_resume_atomic_commit_missing")
            }
        }
        _generationDoneFlow.emit(conversationId)
        // This command owns one persisted approval decision only. Other pending tools suspend
        // the generation lineage; they must not leave this approval child non-terminal.
        finishControlAuthority(conversationId, control)
        return RunOutcome.Completed()
    }

    private suspend fun adoptApprovalResumeCommit(
        conversationId: Uuid,
        commit: me.rerere.rikkahub.data.execution.ApprovalResumeAuthorityCommit,
        timing: AgentTimingHandle? = null,
    ) {
        timing?.mark(AgentTimingEventKind.RESUME_ENQUEUED)
        val row = durableCommandQueue.approvalResumeCommitted(commit) ?: return
        if (row.conversationId != conversationId.toString() ||
            row.state !in setOf(
                me.rerere.rikkahub.service.chat.DurableCommandState.PENDING.name,
                me.rerere.rikkahub.service.chat.DurableCommandState.INTERRUPTED.name,
            )
        ) {
            return
        }
        val envelope = durableCommandQueue.decodeFencedEnvelope(
            row,
            origin = CommandOrigin.INTERNAL,
        ) ?: return
        if (envelope.command !is ResumeAfterApprovalCommand) return
        // The row is already durable. enqueueEnvelope re-adopts the exact identity and only
        // supplies an in-memory Deferred/channel entry; it cannot create another resume.
        getOrCreateRuntime(conversationId).enqueueEnvelope(envelope)
    }

    private suspend fun commitApprovalScopeGrant(
        conversationId: Uuid,
        command: ToolApprovalCommand,
        approved: Boolean,
    ) {
        val toolName = command.toolName ?: return
        if (!approved || command.scope == ApprovalScope.Once.name) return
        withTimeout(5.seconds) {
            withContext(Dispatchers.IO) {
                when (command.scope) {
                    ApprovalScope.ChatScope.name -> me.rerere.rikkahub.data.ai.tools
                        .ToolApprovalAllowList.grantForChat(conversationId, toolName)
                    ApprovalScope.Always.name -> toolApprovalPreferences.grantAlways(toolName)
                }
            }
        }
    }

    private suspend fun finishControlAuthority(
        envelope: CommandEnvelope<out ChatCommand>,
        control: GenerationRunControl,
    ) {
        val authority = control.runtimeCommandAuthority() ?: return
        val graph = conversationRepo.getConversationById(envelope.conversationId)
            ?: error("control_conversation_missing")
        authority.finish(
            conversation = graph,
            terminalState = me.rerere.rikkahub.service.chat.DurableCommandState.COMPLETED,
            kind = me.rerere.rikkahub.service.chat.RuntimeAuthorityTerminalKind.CONTROL_ONLY,
            resultAssistantMessageId = null,
        )
    }

    private suspend fun finishControlAuthority(
        conversationId: Uuid,
        control: GenerationRunControl,
    ) {
        val authority = control.runtimeCommandAuthority() ?: return
        val graph = conversationRepo.getConversationById(conversationId)
            ?: error("control_conversation_missing")
        authority.finish(
            conversation = graph,
            terminalState = me.rerere.rikkahub.service.chat.DurableCommandState.COMPLETED,
            kind = me.rerere.rikkahub.service.chat.RuntimeAuthorityTerminalKind.CONTROL_ONLY,
            resultAssistantMessageId = null,
        )
    }

    /**
     * Which Claude P continuation each admitted command owes, for as long as that command runs.
     *
     * Process-local, and deliberately so: every *durable* fact about a continuation is in the
     * message graph, written by the admission transaction. This carries only the decision the run
     * that is executing was admitted under — see [me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationAdmissions].
     */
    private val claudePSessionAdmissions =
        me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationAdmissions()

    /**
     * The `(generationId, connectionEpoch)` pairs a bind replay has already been attempted on.
     *
     * Process-local: the durable half of a bind obligation is the `INTERRUPTED` record carrying its
     * generation id, and the connection the attempt went out on did not survive the process either.
     */
    private val claudePSessionBindReplays =
        me.rerere.rikkahub.data.claudep.ClaudePSessionBindReplays()

    /**
     * Whether this conversation's own assistant dispatches its model through the Claude P provider.
     *
     * Read from the conversation's assistant rather than from a global pointer, and resolved
     * through the same `findModelById` / `findProvider` pair the generation path uses — a second
     * way of answering "which provider is this" is how a conversation comes to be treated as a
     * continuation on one path and as an ordinary generation on another.
     */
    private suspend fun dispatchesThroughClaudeP(conversation: Conversation): Boolean {
        // The one activation point. Everything downstream of this — the barrier, the settlement,
        // the binding request — is unreachable while it is `false`, so a Claude P dispatch keeps
        // exactly the `mode: "new"` shape it had before M3-B and no durable record is written.
        if (!me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationActivation.ENABLED) {
            return false
        }
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(conversation.assistantId) ?: return false
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId) ?: return false
        return model.findProvider(settings.providers) is
            me.rerere.ai.provider.ProviderSetting.ClaudeP
    }

    /**
     * The role of the message a regenerate targets, read from the committed graph, or `null`.
     *
     * Only the two regenerate commands have a target, and both spell it on the same field of the
     * inner command — reading them separately is how the two forms would come to classify
     * differently for the same target.
     */
    private fun regenerationTargetRole(
        conversation: Conversation,
        command: ChatCommand,
    ): MessageRole? {
        val targetId = when (command) {
            is RegenerateCommand -> command.targetMessageId
            is InterruptRegenerateCommand -> command.regeneration.targetMessageId
            else -> return null
        }
        return conversation.currentMessages.firstOrNull { it.id == targetId }?.role
    }

    /**
     * The Claude P decision [envelope] is admitted under, or `null` when this conversation is not a
     * Claude P one.
     *
     * ## The order, and why it is this order
     *
     * 1. **Reconcile a stranded start first.** A branch left with an unproven `START_IN_FLIGHT` by a
     *    process that died can never be planned: the planner reads the start as "a model may be
     *    running" and refuses every later command on it. The reconciliation supersedes it in its own
     *    transaction and stops *this* operation, so the user's next attempt is a fresh admission
     *    against a graph this build can read. Nothing is dispatched here, by construction.
     * 2. **Then plan.** The decision is computed from the graph the admission transaction is about
     *    to commit, never from a later reading of it.
     * 3. **A refusal refuses the admission.** The gate's refusals all mean "no request may be sent
     *    for this command"; admitting the command anyway would write a durable row that can only be
     *    failed later, and the user would see the message appear before it was rejected.
     */
    private suspend fun claudePAdmissionDecision(
        envelope: CommandEnvelope<out ChatCommand>,
        conversation: Conversation,
        admittedConversation: Conversation,
    ): me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationGate.Decision? {
        if (!dispatchesThroughClaudeP(conversation)) return null

        // A command admitted more than once is the same admission. Re-deriving the decision would
        // read a graph that already carries the barrier this command wrote, and refuse the command
        // it already admitted.
        claudePSessionAdmissions.find(envelope.id)?.let { return it }

        val targetRole = regenerationTargetRole(conversation, envelope.command)
        val decision = me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationGate.cacheFirstAdmission(
            conversation = admittedConversation,
            command = envelope.command,
            targetRole = targetRole,
        )
        if (decision is me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationGate.Decision.Refused) {
            throw me.rerere.rikkahub.data.claudep.ClaudePSessionAdmissionStop(
                pauseQueue = false,
                message = "claude_p_continuation_refused:${decision.reason}",
            )
        }
        return claudePSessionAdmissions.record(envelope.id, decision)
    }

    /**
     * Supersedes a start this branch holds that **no run in this process owns**, or returns.
     *
     * ## Why the live-run question is asked here
     *
     * A persisted `START_IN_FLIGHT` says a generation was admitted and has not reached a terminal.
     * Whether it is *still running* is not a fact about the graph — the graph cannot tell a running
     * generation from a process that died mid-turn — so it is answered by the runtime, which is the
     * only thing that knows. When a run really is live the refusal stands untouched: it is correct,
     * and superseding a running generation's barrier would erase the record that stops a second one
     * from starting.
     *
     * ## Why a failure pauses rather than retries
     *
     * A start that cannot be located or superseded means the graph is not one this build wrote.
     * Dispatching onto it is what the barrier exists to prevent, so the queue stops visibly.
     */
    private suspend fun recoverStrandedStart(
        conversation: Conversation,
        command: ChatCommand,
        targetRole: MessageRole?,
        conversationId: Uuid,
    ) {
        val stranded = me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationGate
            .staleInFlight(conversation, command, targetRole) ?: return
        if (runtimes[conversationId]?.isRunLive == true) return

        val settlement = me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationGate
            .supersedeStale(conversation, stranded)
        val recovered = when (settlement) {
            is me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationGate.Settlement.Commit ->
                settlement.conversation
            is me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationGate.Settlement.Refused ->
                throw me.rerere.rikkahub.data.claudep.ClaudePSessionAdmissionStop
                    .reconciliationFailed(settlement.reason.name)
            me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationGate.Settlement.NothingToWrite ->
                throw me.rerere.rikkahub.data.claudep.ClaudePSessionAdmissionStop
                    .reconciliationFailed("nothing_to_write")
        }
        val update = conversationRepo.updateConversation(recovered)
        if (update !is me.rerere.rikkahub.data.repository.ConversationUpdateResult.Updated) {
            throw me.rerere.rikkahub.data.claudep.ClaudePSessionAdmissionStop
                .reconciliationFailed("conversation_not_updated")
        }
        throw me.rerere.rikkahub.data.claudep.ClaudePSessionAdmissionStop.reconciled()
    }

    /**
     * Performs the one bind replay this conversation owes, or returns why it may not.
     *
     * ## Why this runs at a user action, before admission
     *
     * §6.1's only recovery is re-sending the same bind, and the brief's rule is that it is attached
     * to the next thing the user does — never to a timer, a poll or a background loop, which would
     * be sending an RPC the Server is under no obligation to answer on a connection that may not be
     * the one that dropped. Admission is too late: a branch awaiting a bind resolves to
     * `INTERRUPTED`, which refuses a new generation by construction, so the user's own command could
     * never be admitted until the bind is settled.
     *
     * ## What each outcome means for the user's command
     *
     * Only `NoReplayNeeded` and `Bound` let it proceed. Every other outcome means the branch is not
     * proven, and the brief is explicit that the generation must then not start — so the command is
     * rejected by name rather than dispatched against a branch whose state is unknown.
     */
    private suspend fun claudePBindReplayRefusal(
        conversationId: Uuid,
        command: ChatCommand,
    ): String? {
        if (!me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationActivation.ENABLED) {
            return null
        }
        val conversation = conversationRepo.getConversationById(conversationId) ?: return null
        if (!dispatchesThroughClaudeP(conversation)) return null
        // Only a command that would dispatch a model is a user action a replay may ride on: running
        // it for a queue edit or a stop would send a bind that nothing asked for.
        val targetRole = regenerationTargetRole(conversation, command)
        if (!me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationDispatch
                .dispatchesModel(command, targetRole)
        ) {
            return null
        }

        val owed = me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationGate
            .replay(conversation) ?: return null

        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(conversation.assistantId) ?: return null
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
            ?: return null
        val provider = model.findProvider(settings.providers) as?
            me.rerere.ai.provider.ProviderSetting.ClaudeP ?: return null
        val claudeP = providerManager.getProviderByType(provider) as?
            me.rerere.ai.provider.providers.ClaudePProvider ?: return null

        // One attempt per (generationId, connectionEpoch). A `null` epoch — no carried connection —
        // refuses, because a caller that cannot name a connection cannot decide "once".
        if (!claudePSessionBindReplays.claim(owed.generationId, claudeP.connectionEpoch())) {
            return me.rerere.rikkahub.data.claudep.ClaudePSessionReplayRefusal.NOT_PERMITTED
        }

        return when (
            val outcome = me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationGate
                .replayBeforeGeneration(
                    conversation = conversation,
                    writeRecord = { record ->
                        writeReplayRecord(conversationId, owed, record)
                    },
                    bind = { generationId, branchId, assistantId ->
                        claudeP.bindSession(
                            generationId = generationId,
                            branchId = branchId,
                            assistantId = assistantId,
                            requestRemoteThreadId = conversationId.toString(),
                        )
                    },
                )
        ) {
            me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationGate.ReplayOutcome
                .NoReplayNeeded,
            me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationGate.ReplayOutcome.Bound,
                -> null

            is me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationGate.ReplayOutcome.Closed ->
                me.rerere.rikkahub.data.claudep.ClaudePSessionReplayRefusal.CLOSED

            is me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationGate.ReplayOutcome.Unproven -> {
                Log.w(TAG, "claude_p replay unproven: ${outcome.reason}")
                me.rerere.rikkahub.data.claudep.ClaudePSessionReplayRefusal.UNPROVEN
            }
        }
    }

    /**
     * Persists one of a replay's two records, inside its own transaction, and says whether it
     * committed.
     *
     * A `false` stops the replay — including *before* the bind is sent, which is the point: the
     * graph authority commit is the permission to send, not a formality after it.
     */
    private suspend fun writeReplayRecord(
        conversationId: Uuid,
        replay: me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationGate.Replay,
        record: me.rerere.ai.provider.claudep.ClaudePSessionContinuation,
    ): Boolean {
        val current = conversationRepo.getConversationById(conversationId) ?: return false
        val settlement = me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationGate
            .settleReplay(current, replay, record)
        if (settlement !is me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationGate.Settlement.Commit) {
            Log.w(TAG, "claude_p replay record refused: $settlement")
            return false
        }
        val update = conversationRepo.updateConversation(settlement.conversation)
        if (update !is me.rerere.rikkahub.data.repository.ConversationUpdateResult.Updated) {
            return false
        }
        conversationRepo.refreshSearchProjection(settlement.conversation)
        return true
    }

    /**
     * Why this command may not dispatch a Claude P generation, or `null` when it may.
     *
     * ## Why the check is here and not at the provider call
     *
     * This is the last point at which a refusal is still a **command result**: the caller returns
     * `RunOutcome.Rejected(code)`, the runtime records it as the command's outcome, and the user
     * sees a named reason with nothing dispatched. A check one layer down would have to fail as an
     * exception on a path the user reached through ordinary use, which is exactly the shape the
     * brief forbids.
     *
     * The rule itself — which command shapes dispatch a model, and what a decision permits — is
     * [me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationDispatch]'s. Nothing is re-decided
     * here: this only gathers the three inputs it asks for.
     */
    private suspend fun claudePDispatchRefusal(
        envelope: CommandEnvelope<out ChatCommand>,
    ): me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationDispatch.Refusal? {
        if (!me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationActivation.ENABLED) {
            return null
        }
        val conversation = conversationRepo.getConversationById(envelope.conversationId) ?: return null
        if (!dispatchesThroughClaudeP(conversation)) return null
        return me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationDispatch.refusalFor(
            command = envelope.command,
            targetRole = regenerationTargetRole(conversation, envelope.command),
            decision = claudePSessionAdmissions.find(envelope.id),
        )
    }

    /**
     * Attaches the run's continuation settlement, immediately before the model is asked anything.
     *
     * ## Why this moment
     *
     * The barrier is already durable: it was written in the same transaction that admitted the
     * command, which committed long before this line. What is not yet durable is *who settles it* —
     * and a run that dispatched without that attachment would end through the plain
     * `finishFallback`, leaving `START_IN_FLIGHT` on a branch whose generation is over. That is the
     * one state the gate reads as "a model may be running", so it is the one state this ordering
     * exists to prevent.
     *
     * Throwing here is deliberate and is the fail-closed answer: the caller dispatches after this
     * returns, so an attachment that could not be made means no request leaves the process.
     */
    private fun attachContinuationSettlement(
        runControl: GenerationRunControl?,
        decision: me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationGate.Decision,
    ) {
        val settlement = me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationGate
            .settlementFor(decision) ?: return
        requireNotNull(runControl) { "claude_p_continuation_requires_a_run_control" }
        runControl.attachTerminalGraphSettlement(settlement)
    }

    /**
     * The binding request this run carries into its generation, or `null` when it carries none.
     *
     * An `immediate` request names the branch the Server must resume; a `deferred` one names no
     * branch at all, because the branch is the variant this generation is about to create. Both
     * come from the decision the admission produced and are never rebuilt here.
     */
    private fun continuationRequestFor(
        runControl: GenerationRunControl?,
    ): me.rerere.ai.provider.claudep.ClaudePSessionBindingRequest? =
        when (val decision = claudePSessionAdmissions.find(runControl?.runId)) {
            is me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationGate.Decision.Immediate ->
                decision.request

            is me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationGate.Decision.Deferred ->
                decision.request

            else -> null
        }

    /**
     * [conversation] with this run's continuation terminal written for [terminalState], or `null`
     * when the run owes a terminal the graph cannot carry.
     *
     * ## Why this is applied to the conversation the authority commits
     *
     * The record and the command row have to agree about whether the turn ended, and the only way
     * to make that structural is for both to be written by one transaction — so the terminal is
     * applied to the very conversation handed to `RuntimeRunAuthority.finish`, not to a copy
     * persisted beside it.
     *
     * ## Why a deferral is not settled here
     *
     * A `deferred` run's branch does not exist until its variant is committed, and what it owes
     * then is a `BIND_PENDING` and the `session.bind` that follows — never a generation terminal.
     * Writing one here would credit the branch with an outcome the bind has not produced.
     *
     * A `null` return is a refusal and must fail the save: a completed turn whose branch cannot be
     * settled would leave `START_IN_FLIGHT` behind and close the branch forever.
     */
    /**
     * What the terminal transaction must persist, and what the caller still owes once it commits.
     *
     * [deferred] is non-null exactly when this turn created a branch: the `BIND_PENDING` shares the
     * transaction that commits the new variant, but the `session.bind` that settles it cannot — it
     * is an RPC, and it must not be sent until the record it depends on is durable.
     */
    private data class ContinuationTerminalCommit(
        val conversation: Conversation,
        val deferred: me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationGate.DeferredPending? = null,
    )

    private fun continuationCommit(
        conversation: Conversation,
        runControl: GenerationRunControl?,
        terminalState: me.rerere.rikkahub.service.chat.DurableCommandState,
        generationId: String?,
    ): ContinuationTerminalCommit? {
        val decision = claudePSessionAdmissions.find(runControl?.runId)
            ?: return ContinuationTerminalCommit(conversation)

        if (decision is me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationGate.Decision.Immediate &&
            decision.admissionRecord == null
        ) {
            return ContinuationTerminalCommit(conversation)
        }

        if (decision is me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationGate.Decision.Deferred) {
            // A `deferred` run owes a `BIND_PENDING` only when it actually produced the variant
            // that creates its branch. One that failed or was cancelled produced nothing, so it
            // owes nothing — and writing a pending record for a candidate that does not exist
            // would be a bind obligation for a branch the Server never made.
            if (terminalState != me.rerere.rikkahub.service.chat.DurableCommandState.COMPLETED) {
                return ContinuationTerminalCommit(conversation)
            }
            // A completed `deferred` generation with no identity is the Server having never
            // accepted it. That cannot be bound, and a `BIND_PENDING` without a generation id is a
            // bind that can never be replayed — so it is a refusal rather than a record.
            val identity = generationId ?: return null
            val pending = me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationGate
                .deferredPending(conversation, decision.assistantId, identity)
                ?: return null
            return ContinuationTerminalCommit(pending.conversation, pending)
        }

        val terminal = me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationGate
            .terminalFor(terminalState) ?: return ContinuationTerminalCommit(conversation)
        return when (
            val settlement = me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationGate
                .settle(conversation, decision, terminal)
        ) {
            is me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationGate.Settlement.Commit ->
                ContinuationTerminalCommit(settlement.conversation)

            me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationGate.Settlement.NothingToWrite ->
                ContinuationTerminalCommit(conversation)

            is me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationGate.Settlement.Refused ->
                null
        }
    }

    /**
     * Sends the one `session.bind` a committed `deferred` generation owes, and records the answer.
     *
     * ## The order, and why it is this order
     *
     * The caller reaches here only after the transaction that committed the new variant **and** its
     * `BIND_PENDING` returned, so the obligation is durable before the RPC that discharges it is
     * sent. The identity sent is the one the persisted record carries — the same
     * `(generationId, branchId)` a replay would re-send — never a freshly derived one, because
     * §6.1 recognises the repeat by identity and a new id would be a different bind.
     *
     * ## A dropped connection is not a failure
     *
     * A transport failure means the outcome is *unknown*, never that the bind did not happen: the
     * Server may have applied it before the socket died. That is written as `INTERRUPTED` carrying
     * the same generation id, which is exactly the state a later user-triggered replay resumes
     * from. Treating it as a failure would close a branch that is in fact fine.
     */
    private suspend fun bindDeferredSession(
        pending: me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationGate.DeferredPending,
        provider: me.rerere.ai.provider.ProviderSetting.ClaudeP,
        conversationId: Uuid,
    ) {
        val generationId = pending.record.generationId ?: return
        val outcome = runCatching {
            val claudeP = providerManager.getProviderByType(provider) as?
                me.rerere.ai.provider.providers.ClaudePProvider
            claudeP?.bindSession(
                generationId = generationId,
                branchId = pending.branchId,
                assistantId = pending.record.assistantId,
                requestRemoteThreadId = conversationId.toString(),
            ) ?: me.rerere.ai.provider.claudep.ClaudePSessionBindOutcome.Unproven
        }.getOrElse {
            Log.w(TAG, "claude_p bind transport failure", it)
            me.rerere.ai.provider.claudep.ClaudePSessionBindOutcome.Unproven
        }

        val conversation = conversationRepo.getConversationById(conversationId) ?: return
        when (
            val settlement = me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationGate
                .settleDeferred(conversation, pending, outcome)
        ) {
            is me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationGate.Settlement.Commit -> {
                conversationRepo.updateConversation(settlement.conversation)
                conversationRepo.refreshSearchProjection(settlement.conversation)
            }

            else -> {
                // The Server answered and this device could not record what it said. The branch
                // stays `BIND_PENDING`, which is closed, and a replay does not apply to a bind that
                // already settled — so this is surfaced rather than swallowed.
                Log.w(TAG, "claude_p_bind_settlement_refused:$settlement")
                addError(
                    IllegalStateException("claude_p_bind_settlement_refused"),
                    conversationId,
                    title = context.getString(R.string.error_title_generation),
                )
            }
        }
    }

    private val runtimeAdmissionGraphProvider =
        me.rerere.rikkahub.service.chat.RuntimeCommandAdmissionGraphProvider {
            envelope, authoritySubjectId ->
            val conversation = conversationRepo.getConversationById(envelope.conversationId)
                ?: throw IllegalStateException("Conversation not found")
            val lineage = requireNotNull(envelope.lineage) { "command_lineage_required" }
            val anchorId = lineage.branchAnchorMessageId
            val admittedConversation = when (val command = envelope.command) {
                is SendMessageCommand -> {
                    val message = command.content.toAnchoredUserMessage(anchorId)
                    val existing = conversation.messageNodes
                        .flatMap { it.messages }
                        .firstOrNull { it.id == anchorId }
                    if (existing != null && existing != message) {
                        throw IllegalStateException("command_branch_anchor_identity_conflict")
                    }
                    if (existing == null) {
                        conversation.copy(messageNodes = conversation.messageNodes + message.toMessageNode())
                    } else conversation
                }
                else -> {
                    val anchor = conversation.currentMessages.firstOrNull { it.id == anchorId }
                        ?: throw IllegalStateException("command_branch_anchor_missing")
                    require(anchor.role == MessageRole.USER) { "command_branch_anchor_not_user" }
                    conversation
                }
            }
            // The barrier is written here, into the conversation the admission transaction is
            // about to persist — so the command row and the `START_IN_FLIGHT` record commit
            // together or not at all. `null` means the barrier could not be placed where the
            // resolver reads it, and admitting the command anyway would dispatch a generation
            // whose branch this build cannot account for.
            val decision = claudePAdmissionDecision(envelope, conversation, admittedConversation)
            val gatedConversation = if (decision == null) {
                admittedConversation
            } else {
                me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationGate.barricade(
                    conversation = admittedConversation,
                    decision = decision,
                    anchorMessageId = anchorId.toString(),
                ) ?: throw IllegalStateException("claude_p_continuation_barrier_not_writable")
            }
            me.rerere.rikkahub.service.chat.RuntimeCommandAdmissionGraph(
                conversation = gatedConversation,
                scope = me.rerere.rikkahub.data.authority.source.ConversationSourceScopeResolver
                    .forCommand(lineage.assistantIdSnapshot.toString(), authoritySubjectId),
                branchAnchorMessageId = anchorId,
                branchAnchorMessageRevision = lineage.branchAnchorMessageRevision ?: 1L,
            )
        }

    private val runtimeCommandAuthority by lazy {
        me.rerere.rikkahub.service.chat.ProductionRuntimeCommandAuthority(
            conversations = conversationRepo,
            admissionGraphs = runtimeAdmissionGraphProvider,
            admission = commandAdmissionAuthority,
            admissionAdapter = commandAdmissionAuthorityAdapter,
            waiting = waitingApprovalAuthority,
            final = finalConversationAuthority,
            executionMessages = executionMessageAuthorityBinder,
        )
    }


    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        origin: CommandOrigin = CommandOrigin.APP_UI,
        messageRange: ClosedRange<Int>? = null,
        runControl: GenerationRunControl? = null,
        activeCommandId: Uuid? = null,
        propagateFailure: Boolean = false,
        acceptedAssistantSnapshot: Assistant? = null,
        responseCorrelationAnnotation: UIMessageAnnotation? = null,
        persistenceSourceInvalidationMode: ConversationSourceInvalidationMode =
            ConversationSourceInvalidationMode.APPLY,
        persistenceSourceInvalidationNowMs: Long = System.currentTimeMillis(),
        deferPostCommitActions: Boolean = false,
        onDeferredPostCommit: ((DeferredGenerationPostCommit) -> Unit)? = null,
        agentTiming: AgentTimingHandle? = null,
    ) {
        // Some continuation paths (regenerate, resume-after-approval) do not carry the
        // original command id into this method, but every live generation still owns a
        // stable run id. Resolve the identity once and use it consistently for surface
        // authorization, capability checks, tool budgets, and transient history access.
        val effectiveCommandId = resolveGenerationCommandId(
            activeCommandId = activeCommandId,
            runId = runControl?.runId,
        )
        val authoritativeCommandId = resolveAuthoritativeCommandId(activeCommandId)
        suspend fun applyRunUpdate(block: suspend () -> Unit): Boolean =
            runControl?.runIfUpdatesAllowed(block) ?: run {
                block()
                true
            }

        val callOrigin = resolveToolOrigin(conversationId, origin)
        val settings = settingsStore.settingsFlow.first()
        // Resolve the assistant from this conversation's own assistantId �?the global
        // current-assistant pointer can have moved if the user switched assistants while
        // this generation was queued (multi-assistant crosstalk). Everything downstream
        // (model, memories, tools, sender name) keys off this resolved assistant.
        val initialConversation = getConversationFlow(conversationId).value
        val baseAssistant = acceptedAssistantSnapshot
            ?: settings.getAssistantById(initialConversation.assistantId)
            ?: if (callOrigin == ToolCallOrigin.SystemAssistant || callOrigin == ToolCallOrigin.QuickCapture) {
                throw IllegalStateException(
                    me.rerere.rikkahub.service.chat
                        .SYSTEM_ASSISTANT_TARGET_ASSISTANT_MISSING_REJECTION,
                )
            } else {
                settings.getCurrentAssistant()
            }
        val subAgentProfile = subAgentExecutionProfileRegistry.get(conversationId)
        val assistant = subAgentProfile?.let { profile ->
            baseAssistant.copy(
                chatModelId = profile.effectiveModelId,
                systemPrompt = profile.effectiveSystemPrompt,
            )
        } ?: baseAssistant
        refreshSecondUserAuthorityForInvocation(
            assistant = assistant,
            conversation = initialConversation,
            origin = callOrigin,
        )
        val privilegeContext = me.rerere.rikkahub.privilege.DefaultPrivilegedSessionResolver.resolve(
            assistant = assistant,
            conversation = initialConversation,
            origin = callOrigin,
        )
        val capabilitySubject = capabilitySubjectFor(
            assistant = assistant,
            conversationId = conversationId,
            origin = callOrigin,
            privilege = privilegeContext,
        )
        // Resolved ONCE per run and shared by two consumers: the tool-surface builder below and
        // the persistence sanitizer. Deriving both from the same value is what keeps an ordinary
        // assistant's `conversation_search` result from being mistaken for a Second-User
        // transient reader result. Computed here (not at the sanitize call site) so the surface
        // and the sanitizer cannot disagree even if settings change mid-run.
        val transientReaderToolNames = me.rerere.rikkahub.data.ai.tools.transientReaderToolNamesFor(
            privileged = privilegeContext.isPrivileged,
            historyReadEnabled = assistant.allowConversationHistoryRead,
        )
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
            ?: throw IllegalStateException(
                "No chat model selected. Pick one in Settings �?Default models, or send /model in Telegram."
            )
        // Defence against an upstream-Settings bug where disabling all providers can leave
        // the assistant's chatModelId pointing at a model whose provider has enabled=false:
        // the model lookup walks every provider regardless of state, so without this gate
        // inference fires (and bills) against the "disabled" provider's API key. Surface
        // the disabled state clearly instead of silently spending tokens.
        val resolvedProvider = model.findProvider(settings.providers)
        if (resolvedProvider == null) {
            throw IllegalStateException(
                "Selected model '${model.displayName.ifBlank { model.modelId }}' has no matching provider. " +
                    "Pick a different model in Settings or with /model."
            )
        }
        if (!resolvedProvider.enabled) {
            throw IllegalStateException(
                "Provider '${resolvedProvider.name}' is disabled �?refusing to send. " +
                    "Re-enable it in Settings �?Providers, or pick a different model with /model."
            )
        }

        val senderName = if (assistant.useAssistantAvatar) {
            assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
        } else {
            model.displayName
        }
        var timingSessionContentReady = false
        val timingAppliedToolResults = if (agentTiming != null) mutableSetOf<String>() else null
        val authority = runControl?.runtimeCommandAuthority()
        var waitingAuthorityCommitted = false
        /**
         * The Server's generation id for this dispatch, if it sends one.
         *
         * Only a `deferred` generation is stamped, and the observation point is the one place every
         * provider chunk passes — a streamed attempt, a non-streamed one, a fallback and a retry
         * alike. The rule about a *second, different* id lives in the observer rather than in a
         * closure here: two ids for one dispatch are two generations, and the caller's next step is
         * to bind a branch to one of them, so the conflict is raised rather than resolved.
         *
         * Read by the terminal path, which is why it is declared out here rather than inside the
         * generation block: a `deferred` commit needs the identity in the very transaction that
         * persists the new variant.
         */
        var claudePGenerationId: String? = null
        val claudePGenerationIdentity = me.rerere.rikkahub.data.claudep
            .ClaudePGenerationIdentityObserver { id -> claudePGenerationId = id }

        val generationResult = runCatching {
            // reset suggestions
            updateConversation(conversationId, initialConversation.copy(chatSuggestions = emptyList()))

            // memory tool
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                if (assistant.enableWebSearch ||
                    mcpManager.getAvailableToolsForAssistant(assistant.id).isNotEmpty()
                ) {
                    addError(
                        IllegalStateException(context.getString(R.string.tools_warning)),
                        conversationId,
                        title = context.getString(R.string.error_title_tool_unavailable)
                    )
                }
            }

            // check invalid messages
            checkInvalidMessages(conversationId)
            val conversation = getConversationFlow(conversationId).value
            val isHeadless = me.rerere.rikkahub.data.ai.tools.HeadlessConversations
                .isHeadless(conversationId)
            val toolNameSurface = me.rerere.rikkahub.data.ai.tools.ToolNameSurface()
            val toolExecutionSurface = me.rerere.rikkahub.data.ai.tools.ToolExecutionSurface()
            val invocationCtx = me.rerere.rikkahub.data.ai.tools.ToolInvocationContext(
                callerAssistantId = assistant.id.toString(),
                callerConversationId = conversationId.toString(),
                callerRunId = runControl?.runId?.toString(),
                callerWorkspaceId = assistant.workspaceId?.toString(),
                callOrigin = callOrigin,
                callerModelId = model.id.toString(),
                callerProviderId = resolvedProvider.id.toString(),
                isHeadless = isHeadless,
                modelCanSeeImages = Modality.IMAGE in model.inputModalities,
                privilege = privilegeContext,
                toolNameSurface = toolNameSurface,
                toolExecutionSurface = toolExecutionSurface,
            )
            val workspaceShellSharedStorage =
                me.rerere.rikkahub.data.ai.tools.canMountSecondUserSharedStorage(
                    privilege = privilegeContext,
                    grants = capabilityGrantRepository.current(),
                )
            val secondUserDeviceAccessAddendum = if (privilegeContext.expandLocalTools) {
                val workspace = assistant.workspaceId?.toString()?.let { workspaceId ->
                    workspaceRepository.getById(workspaceId)
                }
                me.rerere.rikkahub.data.ai.tools.secondUserDeviceAccessAddendum(
                    privilege = privilegeContext,
                    workspaceId = workspace?.id,
                    workspaceStorageMode = workspace?.storageMode,
                    workspaceShellSharedStorage = workspaceShellSharedStorage,
                )
            } else {
                null
            }
            val localToolOptions = me.rerere.rikkahub.data.ai.tools.SecondUserToolAllowlist
                .resolveLocalSurfaceTools(
                    assistantLocalTools = assistant.localTools,
                    // A selected local Second-user gets the full privileged surface restricted by
                    // the owner-configured allowlist (null = follow current full = legacy behaviour).
                    privileged = privilegeContext.expandLocalTools,
                    privilegedEnabledTokens = settings.secondUserEnabledLocalToolTokens,
                )
            val privilegedBridgeEnabled = agentSafetySettings
                .privilegedBridgeEnabledFlow.first()
            val privilegedBridgeStatus = shizukuBridgeManager.status()
            val deviceLocked = (context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager)
                ?.let { it.isDeviceLocked || it.isKeyguardLocked } == true
            val hasAuthorizedInvocation = when (callOrigin) {
                ToolCallOrigin.QuickCapture -> me.rerere.rikkahub.quickcapture
                    .QuickCaptureInvocationRegistry
                    .hasAuthorizedRun(conversationId, effectiveCommandId)
                else -> me.rerere.rikkahub.assistant.SystemAssistantInvocationRegistry
                    .hasAuthorizedUnlockedInvocation(conversationId, effectiveCommandId)
            }
            val invocationSurfaceContext = me.rerere.rikkahub.quickcapture.InvocationSurfaceContexts
                .currentContext(callOrigin, conversationId, effectiveCommandId)
            val toolExposurePlan = me.rerere.rikkahub.data.ai.ToolExposurePlan.create(
                origin = callOrigin,
                deviceLocked = deviceLocked,
                hasAuthorizedInvocation = hasAuthorizedInvocation,
                surfaceContext = invocationSurfaceContext,
            )
            val invocationSurfaceCanExposeTools = toolExposurePlan.surfaceAvailable
            val webSearchToolsEnabled = WebSearchPolicy.canInject(
                assistant = assistant,
                origin = callOrigin,
                toolSurfaceAvailable = invocationSurfaceCanExposeTools,
            )
            fun canExposeTool(toolName: String): Boolean {
                return toolExposurePlan.canExpose(toolName)
            }
            fun canExposeLocalTool(toolName: String): Boolean {
                return canExposeTool(toolName)
            }
            val localToolDefinitions = localTools.getTools(localToolOptions, invocationCtx)
                .filter { tool -> canExposeLocalTool(tool.name) }
            val pluginToolRegistrations = if (invocationSurfaceCanExposeTools) {
                pluginToolCatalog.registrations(
                    me.rerere.rikkahub.plugin.PluginToolSurfaceRequest(
                        assistantId = assistant.id.toString(),
                        conversationId = conversationId.toString(),
                        runId = runControl?.runId?.toString().orEmpty(),
                        origin = callOrigin,
                        assistantEnabledPluginIds = assistant.enabledPluginIds,
                        isHeadless = isHeadless,
                        isSubAgent = subAgentProfile != null,
                        stateProjection = buildJsonObject {
                            put("version", 1)
                            put("surface", "local_chat")
                            put("assistant_name", assistant.name.take(80))
                            put("memory_enabled", assistant.enableMemory)
                        }.toString(),
                    ),
                )
            } else {
                emptyList()
            }
            val pluginPromptAddendum = pluginHookBridge.collectPromptAddendum(
                me.rerere.rikkahub.plugin.PluginPromptHookRequest(
                    assistantId = assistant.id.toString(),
                    conversationId = conversationId.toString(),
                    runId = runControl?.runId?.toString().orEmpty(),
                    origin = callOrigin,
                    assistantEnabledPluginIds = assistant.enabledPluginIds,
                    isHeadless = isHeadless,
                    isSubAgent = subAgentProfile != null,
                ),
            )
            val privilegedShellRegistration = if (
                invocationSurfaceCanExposeTools &&
                canExposeTool(me.rerere.rikkahub.privilege.PRIVILEGED_SHELL_TOOL_NAME) &&
                me.rerere.rikkahub.privilege.shouldInjectPrivilegedShell(
                    privilege = privilegeContext,
                    origin = callOrigin,
                    isHeadless = isHeadless,
                    privilegedBridgeEnabled = privilegedBridgeEnabled,
                    bridgeStatus = privilegedBridgeStatus,
                )
            ) {
                me.rerere.rikkahub.privilege.createExternalBridgeRunCommandTool(
                    shizukuBridgeManager,
                )
            } else {
                null
            }
            val structuredPrivilegedRegistration = if (
                invocationSurfaceCanExposeTools &&
                structuredPrivilegedCommandExecutor != null &&
                me.rerere.rikkahub.privilege.shouldInjectStructuredPrivilegedTools(
                    privilege = privilegeContext,
                    origin = callOrigin,
                    isHeadless = isHeadless,
                    privilegedBridgeEnabled = privilegedBridgeEnabled,
                    bridgeStatus = privilegedBridgeStatus,
                )
            ) {
                me.rerere.rikkahub.privilege.createStructuredPrivilegedTools(
                    structuredPrivilegedCommandExecutor,
                )
            } else {
                null
            }
            val structuredPrivilegedV2Registration = if (
                invocationSurfaceCanExposeTools &&
                structuredPrivilegedCommandExecutor != null &&
                me.rerere.rikkahub.privilege.shouldInjectStructuredPrivilegedV2Tools(
                    privilege = privilegeContext,
                    origin = callOrigin,
                    isHeadless = isHeadless,
                    privilegedBridgeEnabled = privilegedBridgeEnabled,
                    bridgeStatus = privilegedBridgeStatus,
                    deviceLocked = deviceLocked,
                )
            ) {
                me.rerere.rikkahub.privilege.createStructuredPrivilegedV2Tools(
                    structuredPrivilegedCommandExecutor,
                )
            } else {
                null
            }
            val verifiedAccessibilityToolDefinitions = if (
                invocationSurfaceCanExposeTools &&
                me.rerere.rikkahub.data.ai.tools.local.shouldInjectVerifiedAccessibilityTools(
                    privilege = privilegeContext,
                    origin = callOrigin,
                    isHeadless = isHeadless,
                )
            ) {
                me.rerere.rikkahub.data.ai.tools.local.verifiedAccessibilityTools(
                    invocationContext = invocationCtx,
                    displayTargetResolver = displayAutomationRuntime?.let { runtime ->
                        me.rerere.rikkahub.data.ai.tools.local.DisplayTargetResolver(runtime)
                    },
                )
            } else {
                emptyList()
            }
            val workspaceProcessTools = if (
                invocationSurfaceCanExposeTools &&
                me.rerere.rikkahub.data.ai.tools.shouldInjectWorkspaceProcessTools(
                    privilege = privilegeContext,
                    origin = callOrigin,
                    isHeadless = isHeadless,
                )
            ) {
                me.rerere.rikkahub.data.ai.tools.createWorkspaceProcessTools(
                    manager = workspaceProcessManager,
                    workspaceRepository = workspaceRepository,
                    defaultWorkspaceId = assistant.workspaceId?.toString(),
                    defaultCwd = conversation.workspaceCwd,
                )
            } else {
                emptyList()
            }

            // start generating
            val session = getOrCreateSession(conversationId)
            // Restore the pre-directory direct tool surface for the active local second user.
            // Other assistants and remote/headless origins keep their existing behaviour.
            // Tool experience and Fast Lane remain durable library features; they are simply no
            // longer required as a schema-discovery detour on this direct surface.
            val secondUserDirectToolSurface =
                privilegeContext.isPrivileged &&
                capabilitySubject.type == me.rerere.rikkahub.data.capability.SubjectType.LOCAL_SECOND_USER &&
                callOrigin in me.rerere.rikkahub.data.ai.InvocationSurfacePolicy.CONFIRMED_LOCAL_SECOND_USER &&
                !isHeadless &&
                subAgentProfile == null
            val toolSurfaceSession = if (secondUserDirectToolSurface) {
                me.rerere.rikkahub.toolcatalog.ToolDiscoverySession(
                    snapshot = me.rerere.rikkahub.toolcatalog.ToolSurfaceBuilder.snapshot(emptyList()),
                    experienceLookup = toolExperienceRepository,
                    experienceEditor = toolExperienceRepository,
                    shortcutEditor = toolShortcutRepository,
                    onSnapshotResolved = { snapshot ->
                        appScope.launch {
                            toolShortcutRepository.reconcileSnapshot(snapshot)
                        }
                    },
                    mode = me.rerere.rikkahub.toolcatalog.ToolSurfaceMode.DIRECT,
                )
            } else {
                null
            }
            val ownerToolSurfaceAvailable =
                me.rerere.rikkahub.data.ai.tools.isOwnerToolSurfaceAvailable(invocationCtx)
            val legacyOwnerRuntimeTools = if (ownerToolSurfaceAvailable) {
                buildList {
                    addAll(
                        me.rerere.rikkahub.data.ai.tools.createPrivilegedManagementTools(
                            invocationContext = invocationCtx,
                            guard = privilegedActionGuard,
                            backend = privilegedManagementBackend,
                            hardDenyPolicy = hardDenyPolicy,
                        ),
                    )
                    addAll(
                        me.rerere.rikkahub.setup.createSetupTools(
                            invocationContext = invocationCtx,
                            coordinator = setupTransactionCoordinator,
                        ),
                    )
                }
            } else {
                emptyList()
            }
            val interactiveTurnBudgetMs = if (subAgentProfile != null) {
                me.rerere.rikkahub.data.ai.limits.ToolRuntimeLimits.turnBudgetMs
            } else {
                resolveInteractiveGenerationTurnBudgetMs(
                    configuredMinutes = assistant.generationTurnBudgetMinutes,
                    isActiveLocalSecondUser = secondUserDirectToolSurface,
                    globalTurnBudgetMs = me.rerere.rikkahub.data.ai.limits.ToolRuntimeLimits.turnBudgetMs,
                )
            }
            // Freeze one time boundary for standing, expiry, FTS and eventual lastAccess. A long
            // tool loop must not see internally inconsistent memory validity decisions.
            val memoryFrozenNowMs = System.currentTimeMillis()
            val generationInputMessages = conversation.currentMessages.let { allMessages ->
                if (messageRange != null) {
                    allMessages.subList(messageRange.start, messageRange.endInclusive + 1)
                } else {
                    allMessages
                }
            }
            // Stage D needs the exact command authority even when the independently reviewed
            // Stage-E injection opt-in is off. Merely attaching this content-free identity has no
            // provider effect; GenerationHandler applies the separate Stage-D and Stage-E gates.
            // Read once and reused: this row is the durable authority for which branch this run
            // belongs to, and it is consulted twice below — for the learning scope, and for the
            // branch identity a Claude P tool call is bound to. Two reads would be two chances to
            // disagree about the branch, which is the one thing a tool call must not be wrong about.
            val generationLineage = if (authoritativeCommandId != null) {
                durableCommandQueue.findAuthorityRow(authoritativeCommandId)
                    ?.let(me.rerere.rikkahub.service.chat.CommandLineageContext::fromAuthorityRowOrNull)
            } else {
                null
            }
            if (runControl != null) {
                generationLineage
                    ?.let lineage@ { lineage ->
                        val branchAnchorRevision = lineage.branchAnchorMessageRevision
                            ?: return@lineage
                        val scope = privilegeContext.authoritySubjectId?.let { subjectId ->
                            me.rerere.rikkahub.learning.model.LearningScope.AuthoritySubject(subjectId)
                        } ?: me.rerere.rikkahub.learning.model.LearningScope.Assistant(assistant.id)
                        runControl.attachPolicyLearningContext(
                            me.rerere.rikkahub.learning.exposure.PolicyLearningCommandContext(
                                scope = scope,
                                consumingAssistantId = assistant.id,
                                lineageId = lineage.lineageId,
                                branchAnchorMessageId = lineage.branchAnchorMessageId,
                                branchAnchorMessageRevision = branchAnchorRevision,
                                logicalRunId = runControl.runId,
                            ),
                        )
                    }
            }
            var memoryRetrievalTraceId: String? = null
            val generationMemories = if (!assistant.enableMemory) {
                emptyList()
            } else {
                agentTiming?.mark(AgentTimingEventKind.MEMORY_RETRIEVAL_STARTED)
                try {
                    val standingPreferences = memoryRepository.getUserApprovedStandingMemories(
                        assistantId = assistant.id,
                        includeGlobal = assistant.useGlobalMemory,
                        frozenNowMs = memoryFrozenNowMs,
                    )
                    val query = generationInputMessages
                        .lastOrNull { it.role == MessageRole.USER }
                        ?.parts
                        ?.filterIsInstance<UIMessagePart.Text>()
                        ?.joinToString("\n") { it.text }
                        .orEmpty()
                    val retrieval = memoryRepository.retrieveRelevant(
                        assistantId = assistant.id,
                        query = query,
                        includeGlobal = assistant.useGlobalMemory,
                        excludeMemoryIds = standingPreferences.mapTo(hashSetOf()) { it.id },
                        frozenNowMs = memoryFrozenNowMs,
                        querySource = MemoryRetrievalQuerySource.LATEST_USER_TEXT,
                    )
                    memoryRetrievalTraceId = memoryRetrievalDiagnostics.record(
                        trace = retrieval.trace,
                        recordedAtMs = memoryFrozenNowMs,
                    )
                    (standingPreferences + retrieval.matches.map { it.memory }).distinctBy { it.id }
                } finally {
                    agentTiming?.mark(AgentTimingEventKind.MEMORY_RETRIEVAL_FINISHED)
                }
            }
            agentTiming?.mark(AgentTimingEventKind.TOOL_SURFACE_STARTED)
            // The continuation decision this run was admitted under, if any. Its settlement is
            // attached here — after the barrier has been durable for as long as this run has
            // existed, and immediately before the request that could start a session. Nothing
            // above this line reaches the network, so "attached before dispatch" is a property of
            // the order rather than of a check.
            val continuationDecision = claudePSessionAdmissions.find(runControl?.runId)
            if (me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationActivation.ENABLED &&
                resolvedProvider is me.rerere.ai.provider.ProviderSetting.ClaudeP
            ) {
                // **Backstop, not the mechanism.** `claudePDispatchRefusal` already refuses every
                // Claude P model dispatch that carries no decision, so reaching here without one
                // means a dispatch site was added that bypasses it. The failure is a named domain
                // refusal with a code rather than an assertion, so whoever sees it has something to
                // act on — and no request has left the process either way.
                val refusal = when (continuationDecision) {
                    null -> me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationDispatch
                        .Refusal.ADMISSION_MISSING

                    is me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationGate.Decision.Immediate,
                    is me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationGate.Decision.Deferred,
                        -> null

                    else -> me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationDispatch
                        .Refusal.NOT_A_GENERATION
                }
                if (refusal != null) {
                    throw me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationDispatch
                        .Refused(refusal)
                }
            }
            if (continuationDecision != null) {
                attachContinuationSettlement(runControl, continuationDecision)
            }
            generationHandler.generateText(
                settings = settings,
                model = model,
                processingStatus = session.processingStatus,
                // The generation identity a Claude P tool call is bound to. Every field is read
                // from the authority that owns it — the run control, the durable command row, the
                // resolved assistant and the resolved call origin — and never from the message
                // text, the conversation fallback, the current page or a global. A field that
                // cannot be read is left blank, and an incomplete identity makes the Claude P
                // bridge refuse to offer tools rather than bind a call to the wrong generation.
                claudePToolGenerationContext =
                    me.rerere.rikkahub.data.claudep.ClaudePToolGenerationContextFactory.build(
                        runId = runControl?.runId,
                        // The durable admitted command only. Never the run id standing in for it:
                        // they are different identities and a substitution would bind a tool call
                        // to a command that did not admit it.
                        authoritativeCommandId = authoritativeCommandId,
                        conversationId = conversationId,
                        assistantId = assistant.id,
                        branchId = generationLineage?.branchAnchorMessageId,
                        // The enum's own name, which is what the app's exact-match mapping
                        // compares. Nothing normalises it, so a token the bridge cannot map is a
                        // refusal rather than a silently substituted origin.
                        callOrigin = callOrigin,
                    ),
                // The branch binding this dispatch carries, resolved by the admission that wrote
                // the barrier. `null` is not "the app tried and failed" here: it is the answer for
                // every conversation that does not dispatch through Claude P, and for a Claude P
                // command whose admission refused to produce a decision — in which case no
                // generation is running under it in the first place.
                claudePSessionBindingRequest = continuationRequestFor(runControl),
                // The Server's generation id, for a `deferred` generation to be bound to the
                // branch variant it is about to create.
                onClaudePGenerationAccepted = claudePGenerationIdentity::observe,
                // Read once per call so the surface that wrote the addendum (Telegram bot,
                // anything else) gets its runtime context into the system prompt without
                // having to plumb a parameter all the way through sendMessage. Returns null
                // for in-app conversations that didn't register one.
                systemAddendum = listOfNotNull(
                    me.rerere.rikkahub.data.ai.tools.ConversationSystemAddendum
                        .get(conversationId),
                    secondUserDeviceAccessAddendum,
                    pluginPromptAddendum,
                    if (toolSurfaceSession != null) {
                        """
                        Direct tool surface: all currently eligible tool schemas are available in this turn.
                        Use a visible tool directly; do not search or open a directory first. Tool experiences
                        and Fast Lane metadata are hints, never authorization. Re-check the current schema,
                        permission state, and approval requirements before acting.
                        The host library tools `tool_experience_update` and `tool_fast_lane_manage` are
                        available in this trusted second-user surface: use the former only to edit an
                        existing host-created experience, and the latter to list, pin, or unpin a shortcut.
                        """.trimIndent()
                    } else null,
                ).joinToString("\n\n").ifBlank { null },
                isToolAutoApproved = { toolName ->
                    // YOLO mode ("I AM STUPID" toggle in Settings �?Tool approvals): every
                    // tool auto-approves. User opted into this explicitly. HARDLINE still
                    // blocks rm -rf / et al �?that check runs BEFORE auto-approval in
                    // GenerationHandler, so YOLO can't smuggle one through.
                    //
                    // Headless conversations (cron-driven) also auto-approve EVERY tool;
                    // the user pre-authorised the schedule itself at job-creation time
                    // and there's no UI surface to prompt at fire time.
                    //
                    // Otherwise: "Allow for this chat" (in-memory, per-conversation) OR
                    // "Always Allow" (DataStore-backed, across the whole app). The
                    // Once-grant lives in the message itself as
                    // ToolApprovalState.Approved, so it's already handled by the regular
                    // Pending �?Approved transition.
                    //
                    // ask_user is a human-input request, NOT a permission gate. It must pause
                    // for the user whenever there's a surface to ask on (the in-app question card
                    // or the Telegram clarify flow), so it ignores YOLO and the allow-lists �?
                    // otherwise it auto-executes its placeholder body and returns
                    // ask_user_unavailable. In a headless run (cron / sub-agent) there's nobody to
                    // answer, so it still auto-approves there and falls through to that graceful
                    // envelope instead of hanging the turn.
                    if (toolName == "ask_user") {
                        me.rerere.rikkahub.data.ai.tools.HeadlessConversations
                            .shouldAutoApprove(conversationId)
                    } else if (callOrigin in me.rerere.rikkahub.data.ai.InvocationSurfacePolicy.REMOTE) {
                        // Telegram/Web/MCP/external origins are separate principals. They never
                        // inherit second-user, YOLO, or local allow-list decisions. A future
                        // scoped AccessGrant is the only path that may pre-authorize them.
                        false
                    } else if (me.rerere.rikkahub.owner.OwnerAutonomyPolicy.canAutoApprove(
                            privilege = privilegeContext,
                            origin = callOrigin,
                            toolName = toolName,
                        )) {
                        true
                    } else if (
                        me.rerere.rikkahub.plugin.isPluginModelToolName(toolName) ||
                        toolName == "linux_grant_request" ||
                        toolName == "linux_grant_revoke"
                    ) {
                        // Ordinary assistants retain the existing fresh-approval floor. Only the
                        // live local Owner principal above bypasses it.
                        false
                    } else {
                        privilegeContext.autoApproveTools ||
                            (toolName == "call_phone" && privilegeContext.unrestrictedOverride &&
                            toolExecutionGate.canAutoApproveUnrestrictedCallNow(
                                callOrigin,
                            )) ||
                            toolApprovalPreferences.currentYolo() ||
                            me.rerere.rikkahub.data.ai.tools.HeadlessConversations
                                .shouldAutoApprove(conversationId) ||
                            me.rerere.rikkahub.data.ai.tools.ToolApprovalAllowList
                                .isAllowedForChat(conversationId, toolName) ||
                            toolApprovalPreferences.current().contains(toolName)
                    }
                },
                runtimeOnlyTools = legacyOwnerRuntimeTools,
                messages = generationInputMessages,
                assistant = assistant,
                unrestrictedOverride = privilegeContext.unrestrictedOverride,
                capabilitySubject = capabilitySubject,
                selectedPrivilegedConversation = privilegeContext.isPrivileged,
                conversationSystemPrompt = conversation.customSystemPrompt,
                conversationModeInjectionIds = conversation.modeInjectionIds,
                conversationLorebookIds = conversation.lorebookIds,
                workspaceCwd = conversation.workspaceCwd,
                callOrigin = callOrigin,
                commandOrigin = origin,
                conversationId = conversationId,
                commandId = effectiveCommandId,
                authoritativeCommandId = authoritativeCommandId,
                memoryFrozenNowMs = memoryFrozenNowMs,
                memoryRetrievalTraceId = memoryRetrievalTraceId,
                runControl = runControl,
                agentTiming = agentTiming,
                isHeadless = isHeadless,
                isSubAgent = subAgentProfile != null,
                maxSteps = subAgentProfile?.generationMaxSteps()
                    ?: resolveInteractiveGenerationMaxSteps(
                        configured = assistant.generationMaxSteps,
                        isActiveLocalSecondUser = secondUserDirectToolSurface,
                    ),
                turnBudgetMs = interactiveTurnBudgetMs,
                memoryToolAllowed = subAgentProfile?.allowsTool("memory_tool") ?: true,
                invocationSurfaceContextProvider =
                    me.rerere.rikkahub.quickcapture.InvocationSurfaceContexts,
                isEmergencyStopActive = {
                    agentSafetySettings.emergencyStopFlow.first()
                },
                startableTools = buildMap {
                    privilegedShellRegistration?.let { registration ->
                        if (canExposeTool(registration.definition.name)) {
                            put(registration.definition.name, registration.startable)
                        }
                    }
                    structuredPrivilegedRegistration?.let { registration ->
                        putAll(registration.startables.filterKeys(::canExposeTool))
                    }
                    structuredPrivilegedV2Registration?.let { registration ->
                        putAll(registration.startables.filterKeys(::canExposeTool))
                    }
                    pluginToolRegistrations.forEach { registration ->
                        put(registration.definition.name, registration.startable)
                    }
                },
                memories = generationMemories,
                inputTransformers = buildList {
                    addAll(inputTransformers)
                    add(templateTransformer)
                    add(workspaceReminderTransformer)
                },
                outputTransformers = outputTransformers,
                tools = buildList {
                    if (webSearchToolsEnabled) {
                        addAll(createSearchTools(settings))
                    }
                    // Cat Garden. Gated on the ordinary-assistant path only, so the Second-User and
                    // Owner surfaces are untouched by this feature. When the assistant has not
                    // opted in — or the origin is not an allowed one — the set is empty and no
                    // space schema is built at all, rather than a tool that would refuse later.
                    // The final filter re-applies the same set, so the builder can never widen it.
                    if (!privilegeContext.isPrivileged) {
                        addAll(
                            me.rerere.rikkahub.space.CatGardenToolSurface.build(
                                repository = spaceRepository,
                                invocationContext = invocationCtx,
                                assistantEnabled = assistant.catGardenEnabled,
                            )
                        )
                    }
                    // Shared sticker library. Same shape as the Cat Garden block above and gated
                    // the same way — opt-in per assistant, and an origin allowlist inside the
                    // surface — but narrower on both counts: the surface admits LocalChat only, and
                    // a disabled assistant is sent no sticker schema rather than a tool that
                    // refuses later.
                    if (!privilegeContext.isPrivileged) {
                        addAll(
                            me.rerere.rikkahub.sticker.StickerToolSurface.build(
                                delivery = stickerDelivery,
                                repository = stickerRepository,
                                invocationContext = invocationCtx,
                                assistantEnabled = assistant.stickerToolsEnabled,
                            )
                        )
                    }
                    if (!privilegeContext.isPrivileged) {
                        // Ordinary assistants expose the on-demand conversation-history tools only
                        // when this assistant explicitly opted in. The legacy static recent-chats
                        // prompt (enableRecentChatsReference) and Second-User history access
                        // (allowConversationHistoryRead) are separate concerns with their own gates.
                        val conversationToolNames = ordinaryConversationToolNames(
                            historyToolsEnabled = assistant.allowConversationHistoryTools,
                            callOrigin = callOrigin,
                        )
                        if (conversationToolNames.isNotEmpty()) {
                            addAll(
                                createConversationTools(conversationRepo, assistant.id)
                                    .filter { tool -> tool.name in conversationToolNames }
                            )
                        }
                    } else if (transientReaderToolNames.isNotEmpty()) {
                        addAll(
                            me.rerere.rikkahub.data.ai.tools.createSecondUserConversationReaderTools(
                                reader = conversationLibraryReader,
                                invocationContext = invocationCtx,
                                commandId = effectiveCommandId,
                                historyReadEnabled = true,
                                deviceUnlocked = { !deviceLocked },
                            )
                        )
                    }
                    addAll(localToolDefinitions)
                    addAll(pluginToolRegistrations.map { it.definition })
                    privilegedShellRegistration?.let { add(it.definition) }
                    structuredPrivilegedRegistration?.let { addAll(it.definitions) }
                    structuredPrivilegedV2Registration?.let { addAll(it.definitions) }
                    addAll(verifiedAccessibilityToolDefinitions)
                    if (privilegeContext.isPrivileged) {
                        add(
                            me.rerere.rikkahub.data.ai.tools.createConversationSendMessageTool(
                                invocationContext = invocationCtx,
                                conversationExists = conversationRepo::existsConversationById,
                                submit = { message ->
                                    submitUserMessage(
                                        conversationId = message.conversationId,
                                        content = message.parts,
                                        answer = message.answer,
                                        origin = me.rerere.rikkahub.service.chat.CommandOrigin.INTERNAL,
                                        dedupeKey = message.dedupeKey,
                                        annotations = message.annotations,
                                    )
                                },
                            )
                        )
                        if (ownerToolSurfaceAvailable) {
                            addAll(
                                me.rerere.rikkahub.data.ai.tools.createOwnerManagementTools(
                                    invocationContext = invocationCtx,
                                    gateway = ownerOperationGateway,
                                    // Owner allowlist: null follows the full 24 families.
                                    enabledFamilies = me.rerere.rikkahub.data.ai.tools
                                        .SecondUserToolAllowlist
                                        .resolveOwnerFamilies(settings.secondUserEnabledOwnerFamilyNames),
                                ),
                            )
                        }
                        addAll(workspaceProcessTools)
                    }
                    addAll(
                        createWorkspaceToolsIfReady(
                            workspaceId = assistant.workspaceId?.toString(),
                            cwd = conversation.workspaceCwd,
                            allowSharedStorage = workspaceShellSharedStorage,
                        ),
                    )
                    if (assistant.enabledSkills.isNotEmpty()) {
                        addAll(
                            createSkillTools(
                                enabledSkills = assistant.enabledSkills,
                                allSkills = skillManager.listSkills(),
                                skillManager = skillManager,
                                // Direct mode exposes current schemas in the provider request, so
                                // the legacy tool reference must not point the model back into a
                                // search/open directory that is intentionally absent here.
                                redirectSecondUserToolReference = false,
                            )
                        )
                    }
                    agentTiming?.mark(AgentTimingEventKind.MCP_DISCOVERY_STARTED)
                    val availableMcpTools = try {
                        mcpManager.getAvailableToolsForAssistant(assistant.id)
                    } finally {
                        agentTiming?.mark(AgentTimingEventKind.MCP_DISCOVERY_FINISHED)
                    }
                    availableMcpTools.also { allTools ->
                        // Upstream name validation: a server name that isn't pure
                        // English+digits would produce an invalid `mcp__<name>__tool`
                        // surface, so surface it as an error rather than emit a tool the
                        // model can't address.
                        val invalidNames = allTools
                            .map { it.second }
                            .distinct()
                            .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
                        if (invalidNames.isNotEmpty()) {
                            addError(
                                error = IllegalStateException(
                                    context.getString(
                                        R.string.error_mcp_invalid_server_name,
                                        invalidNames.joinToString(", ")
                                    )
                                ),
                                conversationId = conversationId,
                            )
                            return
                        }
                    }.forEach { (serverId, serverName, tool) ->
                        // Namespace MCP tools by a server-id slug so two enabled servers that
                        // each expose a tool of the same name don't collide (which would 400 or
                        // mis-route to whichever server registered last). Keep the `mcp__` prefix
                        // intact: HardlineCommandGuard and ToolApprovalDefaults both branch on
                        // `startsWith("mcp__")`. The slug is the first 8 hex chars of the id with
                        // dashes stripped; the validated server name follows for human-readable
                        // disambiguation, keeping the name within the 64-char /
                        // ^[a-zA-Z0-9_-]+$ limit. The execute lambda below still calls callTool
                        // with the REAL tool.name, since the namespacing exists only on the
                        // model-facing surface.
                        val serverSlug = serverId.toString().take(8).replace("-", "")
                        val mcpToolName = "mcp__" + serverSlug + "_" + serverName + "__" + tool.name
                        add(
                            Tool(
                                name = mcpToolName,
                                description = tool.description ?: "",
                                parameters = { tool.inputSchema },
                                // MCP servers' tool surfaces are opaque to us �?we can't
                                // tell read from write or safe from destructive �?so
                                // every MCP call is approval-gated by default. The user
                                // can grant Always-Allow per-tool to suppress prompts on
                                // a known-safe MCP server. The HARDLINE floor still
                                // applies via HardlineCommandGuard's `mcp__*` branch,
                                // which scans every string arg for shell-content
                                // patterns (rm -rf /, mkfs, shutdown, encoded payloads).
                                needsApproval = {
                                    me.rerere.rikkahub.data.ai.tools
                                        .ToolApprovalDefaults.requiresApproval(mcpToolName) ||
                                        tool.needsApproval
                                },
                                execute = {
                                    mcpManager.callTool(serverId, tool.name, it.jsonObject)
                                },
                            )
                        )
                    }
                }
                    .asSequence()
                    .filter { tool -> canExposeTool(tool.name) }
                    .filter { tool -> subAgentProfile?.allowsTool(tool.name) ?: true }
                    .toList()
                    .let { definitions ->
                        me.rerere.rikkahub.data.ai.stableProviderToolOrder(definitions)
                    }
                    .also { definitions ->
                        val memoryToolAvailable = assistant.enableMemory &&
                            (subAgentProfile?.allowsTool("memory_tool") ?: true)
                        val availableNames = buildSet {
                            definitions.mapTo(this) { it.name }
                            if (memoryToolAvailable) {
                                add("memory_tool")
                                add("memory_query")
                            }
                            // ToolDiscoverySession adds these compact library tools after the
                            // candidate surface has been assembled. Publish their names too so
                            // nested owner/workflow handoffs do not incorrectly report them as
                            // unknown, while keeping them scoped to the same trusted session.
                            toolSurfaceSession?.managementToolNames()?.let(::addAll)
                        }
                        val knownNames = buildSet {
                            me.rerere.rikkahub.data.capability.CapabilityCatalog
                                .allCapabilities()
                                .flatMapTo(this) { it.toolNames }
                            add("memory_tool")
                            add("memory_query")
                            addAll(availableNames)
                        }
                        check(toolNameSurface.publish(availableNames, knownNames)) {
                            "tool surface was already published for conversation $conversationId"
                        }
                        check(toolExecutionSurface.publish(definitions)) {
                            "tool execution surface was already published for conversation $conversationId"
                        }
                        agentTiming?.mark(AgentTimingEventKind.TOOL_SURFACE_FINISHED)
                    },
                toolDiscoverySession = toolSurfaceSession,
            ).onCompletion {
                if (runControl?.isUpdateFenced() == true) return@onCompletion
                // 取消 Live Update 通知
                cancelLiveUpdateNotification(conversationId)

                // 可能被取消了，或者意外结束，兜底更新
                val updatedConversation = getConversationFlow(conversationId).value.copy(
                    messageNodes = getConversationFlow(conversationId).value.messageNodes.map { node ->
                        node.copy(messages = node.messages.map { it.finishReasoning() })
                    },
                    updateAt = Instant.now()
                )
                if (!applyRunUpdate { updateConversation(conversationId, updatedConversation) }) {
                    return@onCompletion
                }

                // Show notification if app is not in foreground
                if (!updatedConversation.latestAssistantNeedsFinalAnswer() &&
                    !isForeground.value &&
                    settings.displaySetting.enableNotificationOnMessageGeneration
                ) {
                    sendGenerationDoneNotification(conversationId, senderName)
                }
            }.collect { chunk ->
                if (runControl?.isUpdateFenced() == true) return@collect
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        val correlatedMessages = chunk.messages.withResponseCorrelation(
                            responseCorrelationAnnotation,
                        ).sanitizeTransientConversationToolResults(
                            transientToolNames = transientReaderToolNames,
                        )
                        val timingAssistantMessage = if (agentTiming != null) {
                            correlatedMessages.lastOrNull()
                                ?.takeIf(UIMessage::hasAgentTimingRenderableContent)
                        } else {
                            null
                        }
                        if (!timingSessionContentReady && timingAssistantMessage != null) {
                            agentTiming?.mark(AgentTimingEventKind.SESSION_STATE_APPLY_STARTED)
                        }
                        val updatedConversation = getConversationFlow(conversationId).value
                            .updateCurrentMessages(correlatedMessages)
                        if (!applyRunUpdate {
                                if (chunk.persistenceBarrier ==
                                    GenerationPersistenceBarrier.PENDING_APPROVAL
                                ) {
                                    // Which continuation this card is for decides who may own its
                                    // barrier, and what approving it does afterwards. The chunk
                                    // states it; nothing here infers it from the provider that
                                    // happens to be running. An absent or unrecognised mode is not
                                    // resolved by falling back to the legacy path — a card whose
                                    // continuation nobody can name is one whose approval might arm
                                    // a waiter for a generation that has already ended, so nothing
                                    // is committed behind it and no publisher is answered. The card
                                    // stays on screen and cannot run.
                                    val continuation = chunk.continuationMode
                                    val isSecondUser = capabilitySubject.type ==
                                        me.rerere.rikkahub.data.capability.SubjectType.LOCAL_SECOND_USER
                                    val pendingParts = correlatedMessages
                                        .lastOrNull()
                                        ?.parts
                                        ?.filterIsInstance<UIMessagePart.Tool>()
                                        ?.filter { it.isPending }
                                        .orEmpty()
                                    // The owner is the admission decision, and it is the one place
                                    // the two modes differ in *who* may hold a barrier:
                                    //   RESUME_COMMAND keeps the original rule verbatim — only a
                                    //     second user owns one, and an ordinary assistant is
                                    //     refused exactly as it was before any of this existed;
                                    //   IN_FLIGHT is admitted for every subject type, because the
                                    //     generation that raised the card has not ended and its
                                    //     peer is blocked on the answer.
                                    val pendingOwner = when (continuation) {
                                        ApprovalContinuationMode.IN_FLIGHT ->
                                            me.rerere.rikkahub.data.execution.PendingApprovalOwner(
                                                // The run that is actually executing, with no
                                                // fallback. The publisher found its run control by
                                                // this exact id, so a barrier keyed on the command
                                                // id instead would describe a generation the
                                                // publisher cannot name.
                                                runId = requireNotNull(runControl?.runId) {
                                                    "in_flight_approval_run_missing"
                                                }.toString(),
                                                commandId = authoritativeCommandId?.toString(),
                                                conversationId = conversationId.toString(),
                                                subjectId = capabilitySubject.id,
                                                subjectType = capabilitySubject.type,
                                                origin = callOrigin,
                                            )

                                        ApprovalContinuationMode.RESUME_COMMAND ->
                                            if (isSecondUser) {
                                                me.rerere.rikkahub.data.execution
                                                    .PendingApprovalOwner(
                                                        runId = (runControl?.runId
                                                            ?: effectiveCommandId).toString(),
                                                        commandId = authoritativeCommandId?.toString(),
                                                        conversationId = conversationId.toString(),
                                                        subjectId = capabilitySubject.id,
                                                        subjectType = capabilitySubject.type,
                                                        origin = callOrigin,
                                                    )
                                            } else {
                                                null
                                            }

                                        null -> null
                                    }
                                    // Built only when something will own it, exactly as before: the
                                    // mapping asserts a schema is present, and asserting that for a
                                    // card that is being left alone would turn a working legacy
                                    // path into a crash.
                                    val pendingTools = if (pendingOwner == null) {
                                        emptyList()
                                    } else {
                                        pendingParts.map { tool ->
                                            val schemaFingerprint = me.rerere.rikkahub.toolcatalog
                                                .ToolCatalogSnapshot
                                                .fromDefinitions(toolExecutionSurface.snapshot())
                                                .entry(tool.toolName)
                                                ?.schemaFingerprint
                                                ?: error("approval_tool_schema_missing")
                                            me.rerere.rikkahub.data.execution.PendingApprovalTool(
                                                toolCallId = tool.toolCallId,
                                                toolName = tool.toolName,
                                                arguments = tool.inputAsJson() as? JsonObject
                                                    ?: JsonObject(emptyMap()),
                                                toolSchemaFingerprint = schemaFingerprint,
                                            )
                                        }
                                    }
                                    // What this commit acknowledges. Only an in-flight publication
                                    // has a publisher waiting on it: a resume-command card is
                                    // answered by the user's tap and a later command, and nothing
                                    // in this process is blocked on it.
                                    //
                                    // Addressed by the *Android* run id, which is what this side
                                    // holds, and never by the Server's generation id, which it
                                    // never learns. The Server's id was paired with this run when
                                    // the generation was opened and is checked there — this side
                                    // could not supply it, and a key that required it could not be
                                    // rebuilt here at all.
                                    //
                                    // The tool name comes from the part, and the parts here are
                                    // the **post-redaction** copy: `RuntimeSecretRedactor` rewrites
                                    // `UIMessagePart.Tool.input` before this collector sees it. That
                                    // is exactly why the key is built from the name and the ids and
                                    // not from a digest of the input — redaction changes the
                                    // arguments, never the identity of the call.
                                    val publicationKeys = if (
                                        continuation == ApprovalContinuationMode.IN_FLIGHT &&
                                        pendingOwner != null
                                    ) {
                                        pendingTools.map { tool ->
                                            me.rerere.rikkahub.data.claudep
                                                .ClaudePToolPublicationKey.of(
                                                    runId = pendingOwner.runId,
                                                    toolCallId = tool.toolCallId,
                                                    toolName = tool.toolName,
                                                )
                                        }
                                    } else {
                                        emptyList()
                                    }
                                    // The authority's own approval and execution identities, by
                                    // card, as produced inside the transaction by the single site
                                    // that derives them. Captured rather than recomputed: the
                                    // publisher is waiting for exactly these, and a second
                                    // derivation here would be a second answer to give it.
                                    var barrierReceipts: Map<String, Pair<String, String>> = emptyMap()
                                    val owningMessage = correlatedMessages.lastOrNull()
                                        ?: error("approval_assistant_message_missing")
                                    val existingExecutionIds = owningMessage
                                        .persistedToolExecutionIds(runControl)
                                    if (authority != null) {
                                        try {
                                            authority.checkpointWaiting(
                                                conversation = updatedConversation,
                                                assistantMessageId = owningMessage.id,
                                                approvalMutation = { messageId, revision ->
                                                    pendingOwner?.let { owner ->
                                                        barrierReceipts =
                                                            secondUserApprovalLifecycle
                                                                .persistPendingBarrierInCurrentAuthorityTransaction(
                                                                    owner = owner,
                                                                    tools = pendingTools,
                                                                    assistantMessageId = messageId,
                                                                    assistantMessageRevision = revision,
                                                                    // Non-null whenever an owner
                                                                    // exists: an owner is only built
                                                                    // for a mode this chunk named.
                                                                    continuationMode =
                                                                        requireNotNull(continuation),
                                                                )
                                                                .associate { record ->
                                                                    record.toolCallId to
                                                                        (record.approvalId to
                                                                            record.executionId)
                                                                }
                                                    }
                                                    executionMessageAuthorityBinder
                                                        .requireBoundInCurrentAuthorityTransaction(
                                                            existingExecutionIds.map { executionId ->
                                                                me.rerere.rikkahub.data.execution
                                                                    .ExecutionOwningMessageAuthority(
                                                                        executionId = executionId,
                                                                        assistantMessageId = messageId,
                                                                        assistantMessageRevision = revision,
                                                                    )
                                                            },
                                                        )
                                                },
                                                occurredAtMs = persistenceSourceInvalidationNowMs,
                                            )
                                            waitingAuthorityCommitted = true
                                        } catch (rollback: Throwable) {
                                            // The card and the barrier failed as one transaction,
                                            // so nothing behind the card is durable. Answering here
                                            // is what lets the publisher fail closed now instead of
                                            // at its deadline, and it is answered with a refusal —
                                            // never a commit, because a rollback is precisely the
                                            // state in which a tool must not run. Rethrown
                                            // unchanged: a failed authority commit still fails the
                                            // run, and a cancellation is still a cancellation.
                                            publicationKeys.forEach { key ->
                                                claudePToolPublicationReceipts.refuse(
                                                    key = key,
                                                    localReason = "approval_authority_rollback",
                                                )
                                            }
                                            throw rollback
                                        }
                                        // Only here. The transaction has committed, the barrier is
                                        // durable and the card is applied — so the publisher may
                                        // now arm its waiter. Nothing above this line may
                                        // acknowledge: an `emit` that returned, or a Channel send
                                        // that succeeded, says nothing about a commit, and treating
                                        // it as one would let a tool wait on a decision for a
                                        // barrier that rolled back.
                                        publicationKeys.forEach { key ->
                                            val receipt = barrierReceipts[key.toolCallId]
                                            if (receipt == null) {
                                                // Committed, but the barrier the authority wrote
                                                // did not name this card. Refused rather than
                                                // guessed at: without an exact approval identity
                                                // there is nothing for a waiter to wait on.
                                                claudePToolPublicationReceipts.refuse(
                                                    key = key,
                                                    localReason = "approval_barrier_missing",
                                                )
                                            } else {
                                                claudePToolPublicationReceipts.complete(
                                                    key = key,
                                                    approvalId = receipt.first,
                                                    executionId = receipt.second,
                                                )
                                            }
                                        }
                                    } else if (pendingOwner != null) {
                                        secondUserApprovalLifecycle.persistPendingBarrier(
                                            conversation = updatedConversation,
                                            owner = pendingOwner,
                                            tools = pendingTools,
                                            sourceInvalidationMode = persistenceSourceInvalidationMode,
                                            sourceInvalidationNowMs = persistenceSourceInvalidationNowMs,
                                            continuationMode = requireNotNull(continuation),
                                        )
                                        // No authority transaction means no commit, so there is
                                        // nothing to acknowledge. A publisher waiting on one of
                                        // these would otherwise hang until its deadline; refusing
                                        // tells it to run nothing straight away.
                                        publicationKeys.forEach { key ->
                                            claudePToolPublicationReceipts.refuse(
                                                key = key,
                                                localReason = "approval_authority_transaction_absent",
                                            )
                                        }
                                    }
                                }
                                updateConversation(conversationId, updatedConversation)
                            }
                        ) return@collect
                        if (!timingSessionContentReady && timingAssistantMessage != null) {
                            agentTiming?.bindAssistantMessage(timingAssistantMessage.id)
                            agentTiming?.checkpointOnce(AgentTimingEventKind.SESSION_CONTENT_READY)
                            timingSessionContentReady = true
                        }
                        val newlyAppliedToolResults = timingAppliedToolResults?.let { applied ->
                            correlatedMessages.asSequence()
                                .filter { message -> message.role == MessageRole.ASSISTANT }
                                .flatMap { message ->
                                    message.parts.asSequence().mapIndexedNotNull { index, part ->
                                        (part as? UIMessagePart.Tool)
                                            ?.takeIf { it.output.isNotEmpty() }
                                            ?.let { "${message.id}:$index:${it.toolCallId}" }
                                    }
                                }
                                .count(applied::add) > 0
                        } == true
                        if (newlyAppliedToolResults) {
                            agentTiming?.checkpoint(AgentTimingEventKind.TOOL_RESULTS_COLLECTOR_APPLIED)
                        }
                        if (agentTiming != null &&
                            chunk.persistenceBarrier == GenerationPersistenceBarrier.PENDING_APPROVAL
                        ) {
                            val pendingCount = correlatedMessages.asSequence()
                                .flatMap { it.parts.asSequence() }
                                .filterIsInstance<UIMessagePart.Tool>()
                                .count(UIMessagePart.Tool::isPending)
                            agentTiming.approvalPending(pendingCount)
                        }

                        // Persist immediately when a tool transitions to "execution
                        // started but no output yet" �?this writes the executionStartedAt
                        // breadcrumb to disk so a process kill mid-execute leaves a clear
                        // signal for the next replay (see GenerationHandler.kt's replay
                        // safety pass: Approved + executionStartedAt + empty �?Denied
                        // interrupted_unknown_outcome). Without this, the marker stays in
                        // memory only and replay can't distinguish "freshly approved,
                        // never tried" from "interrupted mid-execute" �?silent re-run.
                        val latestMessage = correlatedMessages.lastOrNull()
                        val needsImmediatePersist = latestMessage?.parts?.any { p ->
                            p is UIMessagePart.Tool &&
                                p.executionStartedAt != null &&
                                p.output.isEmpty() &&
                                p.approvalState is ToolApprovalState.Approved
                        } == true || latestMessage?.annotations?.any { annotation ->
                            annotation is UIMessageAnnotation.FinalAnswerRecovery &&
                                annotation.status == FinalAnswerRecoveryStatus.STARTED
                        } == true
                        if (needsImmediatePersist) {
                            applyRunUpdate {
                                saveConversation(
                                    conversationId = conversationId,
                                    conversation = updatedConversation,
                                    sourceInvalidationMode =
                                        persistenceSourceInvalidationMode,
                                    sourceInvalidationNowMs =
                                        persistenceSourceInvalidationNowMs,
                                )
                            }
                        }

                        // 如果应用不在前台，发�?Live Update 通知
                        if (!isForeground.value && settings.displaySetting.enableNotificationOnMessageGeneration && settings.displaySetting.enableLiveUpdateNotification) {
                            sendLiveUpdateNotification(conversationId, chunk.messages, senderName)
                        }
                    }
                }
            }
        }
        var authorityFailure: Throwable? = null
        generationResult.onFailure {
            if (runControl?.isUpdateFenced() == true) return@onFailure
            if (it is CancellationException) throw it
            // 取消 Live Update 通知
            cancelLiveUpdateNotification(conversationId)

            // Persist the in-memory snapshot so the Auto/Pending �?Denied transitions
            // GenerationHandler did inside its try/catch (the "generation_failed" recovery
            // path) survive a process restart. Without this, the failure path only
            // updates memory and the persisted DB row keeps the stale Pending state
            // forever �?replay would re-run the loop against unrecoverable shape.
            runCatching {
                agentTiming?.mark(AgentTimingEventKind.FINAL_SAVE_STARTED)
                try {
                    applyRunUpdate {
                        val final = getConversationFlow(conversationId).value
                        if (authority != null && !waitingAuthorityCommitted) {
                            val assistantMessage = final.currentMessages
                                .lastOrNull { message -> message.role == MessageRole.ASSISTANT }
                            // A model or protocol failure closes the branch rather than leaving it
                            // unproven: the turn did not complete, and `FAILED_CLOSED` is the
                            // absorbing state that stops the next turn from resuming a session the
                            // Server may have half-applied.
                            val settledFinal = requireNotNull(
                                continuationCommit(
                                    conversation = final,
                                    runControl = runControl,
                                    terminalState = me.rerere.rikkahub.service.chat.DurableCommandState.FAILED,
                                    // A failed turn produced no variant, so there is no candidate
                                    // to name and no identity is consulted.
                                    generationId = null,
                                ),
                            ) { "claude_p_continuation_settlement_refused" }.conversation
                            authority.finish(
                                conversation = settledFinal,
                                terminalState = me.rerere.rikkahub.service.chat.DurableCommandState.FAILED,
                                kind = me.rerere.rikkahub.service.chat.RuntimeAuthorityTerminalKind
                                    .GENERATION_FINAL_SAVED,
                                resultAssistantMessageId = requireNotNull(assistantMessage).id,
                                errorCode = "GENERATION_FAILED",
                                executionIds = assistantMessage.persistedToolExecutionIds(runControl),
                                sourceInvalidationMode = persistenceSourceInvalidationMode,
                                occurredAtMs = persistenceSourceInvalidationNowMs,
                            )
                            updateConversation(conversationId, settledFinal)
                            conversationRepo.refreshSearchProjection(settledFinal)
                        } else if (!waitingAuthorityCommitted) {
                            saveConversation(
                                conversationId = conversationId,
                                conversation = final,
                                sourceInvalidationMode = persistenceSourceInvalidationMode,
                                sourceInvalidationNowMs = persistenceSourceInvalidationNowMs,
                            )
                        }
                    }
                } finally {
                    agentTiming?.mark(AgentTimingEventKind.FINAL_SAVE_FINISHED)
                }
            }.onFailure { saveErr ->
                if (authority != null && !waitingAuthorityCommitted && !authority.isTerminalCommitted()) {
                    runCatching { authority.finishAfterFinalSaveFailure() }
                }
                authorityFailure = saveErr
                Log.w(TAG, "handleMessageComplete: failure-path save failed", saveErr)
            }

            it.printStackTrace()
            addError(it, conversationId, title = context.getString(R.string.error_title_generation))
            Logging.log(TAG, "handleMessageComplete: $it")
            Logging.log(TAG, it.stackTraceToString())
        }.onSuccess {
            if (runControl?.isUpdateFenced() == true) return@onSuccess
            agentTiming?.mark(AgentTimingEventKind.FINAL_SAVE_STARTED)
            var deferredToBind:
                me.rerere.rikkahub.data.claudep.ClaudePSessionContinuationGate.DeferredPending? = null
            try {
                applyRunUpdate {
                    val finalConversation = getConversationFlow(conversationId).value
                    if (authority != null && !waitingAuthorityCommitted) {
                        val assistantMessage = finalConversation.currentMessages
                            .lastOrNull { message -> message.role == MessageRole.ASSISTANT }
                            ?: error("final_assistant_message_missing")
                        // The continuation terminal rides in the same transaction as the command's
                        // terminal: a turn that completed proves the branch's binding, and the two
                        // facts commit together or not at all. A refusal here is thrown, caught by
                        // the surrounding `catch (saveError)` and fenced as a failed save — it is a
                        // named domain outcome turned into a failed command, never an app crash.
                        val terminalCommit = requireNotNull(
                            continuationCommit(
                                conversation = finalConversation,
                                runControl = runControl,
                                terminalState = me.rerere.rikkahub.service.chat.DurableCommandState.COMPLETED,
                                generationId = claudePGenerationId,
                            ),
                        ) { "claude_p_continuation_settlement_refused" }
                        val settledConversation = terminalCommit.conversation
                        try {
                            authority.finish(
                                conversation = settledConversation,
                                terminalState = me.rerere.rikkahub.service.chat.DurableCommandState.COMPLETED,
                                kind = me.rerere.rikkahub.service.chat.RuntimeAuthorityTerminalKind
                                    .GENERATION_FINAL_SAVED,
                                resultAssistantMessageId = assistantMessage.id,
                                executionIds = assistantMessage.persistedToolExecutionIds(runControl),
                                sourceInvalidationMode = persistenceSourceInvalidationMode,
                                occurredAtMs = persistenceSourceInvalidationNowMs,
                            )
                            updateConversation(conversationId, settledConversation)
                            conversationRepo.refreshSearchProjection(settledConversation)
                            // Set only once the transaction above has committed, so the bind that
                            // follows cannot precede the record that obliges it.
                            deferredToBind = terminalCommit.deferred
                        } catch (saveError: Throwable) {
                            if (!authority.isTerminalCommitted()) {
                                runCatching { authority.finishAfterFinalSaveFailure() }
                            }
                            authorityFailure = saveError
                            throw saveError
                        }
                    } else if (!waitingAuthorityCommitted) {
                        saveConversation(
                            conversationId = conversationId,
                            conversation = finalConversation,
                            sourceInvalidationMode = persistenceSourceInvalidationMode,
                            sourceInvalidationNowMs = persistenceSourceInvalidationNowMs,
                        )
                    }

                    if (finalConversation.isEligibleForGenerationPostCommit()) {
                        val postCommit = DeferredGenerationPostCommit(
                            conversationId = conversationId,
                            commandOrigin = origin,
                            toolOrigin = callOrigin,
                            assistant = baseAssistant,
                            conversation = finalConversation,
                            isSubAgent = subAgentProfile != null,
                        )
                        if (deferPostCommitActions) {
                            onDeferredPostCommit?.invoke(postCommit)
                        } else {
                            scheduleGenerationPostCommit(postCommit)
                        }
                    }
                }
            } catch (saveError: Throwable) {
                authorityFailure = saveError
                Log.w(TAG, "handleMessageComplete: final authority save failed", saveError)
            } finally {
                agentTiming?.mark(AgentTimingEventKind.FINAL_SAVE_FINISHED)
            }
            // Exactly one `session.bind`, and only after the transaction above returned — which is
            // what makes "the obligation was durable before the RPC that discharges it was sent" a
            // property of the order rather than of a check.
            deferredToBind?.let { pending ->
                val claudePProvider = resolvedProvider as?
                    me.rerere.ai.provider.ProviderSetting.ClaudeP
                if (claudePProvider != null) {
                    bindDeferredSession(pending, claudePProvider, conversationId)
                }
            }
        }
        authorityFailure?.let { throw it }
        if (propagateFailure || authority != null) generationResult.getOrThrow()
    }

    private fun scheduleGenerationPostCommit(postCommit: DeferredGenerationPostCommit) {
        enqueueMemoryCapture(
            conversationId = postCommit.conversationId,
            commandOrigin = postCommit.commandOrigin,
            toolOrigin = postCommit.toolOrigin,
            assistant = postCommit.assistant,
            conversation = postCommit.conversation,
            isSubAgent = postCommit.isSubAgent,
        )
        launchWithConversationReference(postCommit.conversationId) {
            generateTitle(postCommit.conversationId, postCommit.conversation)
        }
        launchWithConversationReference(postCommit.conversationId) {
            generateSuggestion(postCommit.conversationId, postCommit.conversation)
        }
    }

    private fun enqueueMemoryCapture(
        conversationId: Uuid,
        commandOrigin: CommandOrigin,
        toolOrigin: ToolCallOrigin,
        assistant: Assistant,
        conversation: Conversation,
        isSubAgent: Boolean,
    ) {
        val messages = conversation.currentMessages
        val assistantIndex = messages.indexOfLast { it.role == MessageRole.ASSISTANT }
        if (assistantIndex <= 0) return
        val assistantMessage = messages[assistantIndex]
        val userMessage = messages.subList(0, assistantIndex)
            .lastOrNull { it.role == MessageRole.USER } ?: return
        val sourceMessages =
            me.rerere.rikkahub.memory.memoryCaptureSourcesForMessage(userMessage) +
                me.rerere.rikkahub.memory.memoryCaptureSourcesForMessage(assistantMessage)
        val userText = me.rerere.rikkahub.memory.memoryExtractionText(
            sourceMessages,
            setOf(me.rerere.rikkahub.memory.MemorySourceRole.USER),
        )
        val assistantText = me.rerere.rikkahub.memory.memoryExtractionText(
            sourceMessages,
            setOf(
                me.rerere.rikkahub.memory.MemorySourceRole.ASSISTANT,
                me.rerere.rikkahub.memory.MemorySourceRole.TOOL,
            ),
        )
        if (userText.isEmpty() || assistantText.isEmpty()) return

        val captureOrigin = when (toolOrigin) {
            ToolCallOrigin.LocalChat -> me.rerere.rikkahub.memory.MemoryCaptureOrigin.APP_UI
            ToolCallOrigin.SystemAssistant ->
                me.rerere.rikkahub.memory.MemoryCaptureOrigin.SYSTEM_ASSISTANT
            ToolCallOrigin.SystemAssistantKeyguard ->
                me.rerere.rikkahub.memory.MemoryCaptureOrigin.SYSTEM_ASSISTANT_KEYGUARD
            ToolCallOrigin.QuickCapture -> me.rerere.rikkahub.memory.MemoryCaptureOrigin.QUICK_CAPTURE
            ToolCallOrigin.Telegram -> me.rerere.rikkahub.memory.MemoryCaptureOrigin.TELEGRAM
            ToolCallOrigin.WebServer -> me.rerere.rikkahub.memory.MemoryCaptureOrigin.WEB_API
            ToolCallOrigin.TrustedWorkflow -> if (commandOrigin == CommandOrigin.CRON) {
                me.rerere.rikkahub.memory.MemoryCaptureOrigin.CRON
            } else {
                me.rerere.rikkahub.memory.MemoryCaptureOrigin.INTERNAL
            }
            ToolCallOrigin.MCP,
            ToolCallOrigin.ExternalIntent,
            ToolCallOrigin.PetInteraction,
            ToolCallOrigin.PetHandoffAuto,
            -> me.rerere.rikkahub.memory.MemoryCaptureOrigin.INTERNAL
            ToolCallOrigin.PetHandoffConfirmed ->
                me.rerere.rikkahub.memory.MemoryCaptureOrigin.APP_UI
        }
        val scopeId = if (assistant.useGlobalMemory) {
            MemoryRepository.GLOBAL_MEMORY_ID
        } else {
            assistant.id.toString()
        }
        val isHeadless = isSubAgent ||
            me.rerere.rikkahub.data.ai.tools.HeadlessConversations.isHeadless(conversationId)
        appScope.launch(Dispatchers.IO) {
            runCatching {
                memoryV2Coordinator.capture(
                    me.rerere.rikkahub.memory.CompletedMemoryTurn(
                        assistantId = assistant.id,
                        scopeId = scopeId,
                        conversationId = conversationId,
                        userMessageId = userMessage.id,
                        assistantMessageId = assistantMessage.id,
                        origin = captureOrigin,
                        userText = userText,
                        assistantText = assistantText,
                        sourceMessages = sourceMessages,
                        memoryEnabled = assistant.enableMemory,
                        autoSaveMode = assistant.memoryAutoSaveMode,
                        allowedOrigins = assistant.memoryCaptureOrigins,
                        isHeadless = isHeadless,
                        needsFinalAnswer = conversation.latestAssistantNeedsFinalAnswer(),
                        idleDelayMs = assistant.memoryIdleDelayMinutes
                            .coerceIn(1, 1_440) * 60_000L,
                        immediateCaptureThreshold = assistant.memoryImmediateCaptureThreshold
                            .coerceIn(1, 50),
                        // Freeze the selected context window on this capture. A later settings
                        // change must never alter the batch that this completed turn belongs to.
                        conversationContextTurns = assistant.memoryConversationContextTurns
                            .coerceIn(3, 30),
                        narrativeEventsEnabled = assistant.memoryNarrativeEventsEnabled,
                        insightsTheoriesEnabled = assistant.memoryInsightsTheoriesEnabled,
                    ),
                )
            }.onFailure { error ->
                Log.w(TAG, "Memory V2 capture failed after successful chat turn", error)
            }
        }
    }

    /**
     * Queues an explicit user selection for Memory V2. Assistant messages are context only; a
     * selection containing no user-authored text is rejected before anything is persisted.
     */
    suspend fun captureMemorySelection(
        conversationId: Uuid,
        selectedNodeIds: Set<Uuid>,
    ): me.rerere.rikkahub.memory.ManualMemorySelectionResult {
        val conversation = getConversationFlow(conversationId).value
        val assistant = settingsStore.settingsFlow.first()
            .getAssistantById(conversation.assistantId)
            ?: return me.rerere.rikkahub.memory.ManualMemorySelectionResult.FAILED
        if (!assistant.enableMemory) {
            return me.rerere.rikkahub.memory.ManualMemorySelectionResult.MEMORY_DISABLED
        }
        val selectedMessages = conversation.messageNodes
            .filter { it.id in selectedNodeIds }
            .map { it.currentMessage }
        val userMessages = selectedMessages.filter { it.role == MessageRole.USER }
        val sourceMessages = selectedMessages.flatMap { message ->
            me.rerere.rikkahub.memory.memoryCaptureSourcesForMessage(message)
        }
        val userText = me.rerere.rikkahub.memory.memoryExtractionText(
            sourceMessages,
            setOf(me.rerere.rikkahub.memory.MemorySourceRole.USER),
        )
        if (userText.isBlank()) {
            return me.rerere.rikkahub.memory.ManualMemorySelectionResult.NO_USER_TEXT
        }
        val assistantMessages = selectedMessages.filter { it.role == MessageRole.ASSISTANT }
        val assistantText = me.rerere.rikkahub.memory.memoryExtractionText(
            sourceMessages,
            setOf(
                me.rerere.rikkahub.memory.MemorySourceRole.ASSISTANT,
                me.rerere.rikkahub.memory.MemorySourceRole.TOOL,
            ),
        )
        if (sourceMessages.isEmpty() ||
            sourceMessages.size > me.rerere.rikkahub.memory.MAX_MEMORY_CAPTURE_SOURCE_IDENTITIES
        ) {
            return me.rerere.rikkahub.memory.ManualMemorySelectionResult.FAILED
        }
        val evidenceAnchor = assistantMessages.lastOrNull()?.id ?: userMessages.last().id
        val scopeId = if (assistant.useGlobalMemory) {
            MemoryRepository.GLOBAL_MEMORY_ID
        } else {
            assistant.id.toString()
        }
        return runCatching {
            memoryV2Coordinator.capture(
                me.rerere.rikkahub.memory.CompletedMemoryTurn(
                    assistantId = assistant.id,
                    scopeId = scopeId,
                    conversationId = conversationId,
                    userMessageId = userMessages.first().id,
                    assistantMessageId = evidenceAnchor,
                    origin = me.rerere.rikkahub.memory.MemoryCaptureOrigin.APP_UI,
                    userText = userText,
                    assistantText = assistantText,
                    sourceMessages = sourceMessages,
                    memoryEnabled = true,
                    autoSaveMode = assistant.memoryAutoSaveMode.takeUnless {
                        it == me.rerere.rikkahub.memory.MemoryAutoSaveMode.OFF
                    } ?: me.rerere.rikkahub.memory.MemoryAutoSaveMode.REVIEW_ALL,
                    allowedOrigins = setOf(me.rerere.rikkahub.memory.MemoryCaptureOrigin.APP_UI),
                    isHeadless = false,
                    needsFinalAnswer = false,
                    captureSource = me.rerere.rikkahub.memory.MemoryCaptureSource.MANUAL_SELECTION,
                    idleDelayMs = 0L,
                    immediateCaptureThreshold = 1,
                    conversationContextTurns = assistant.memoryConversationContextTurns
                        .coerceIn(3, 30),
                    narrativeEventsEnabled = assistant.memoryNarrativeEventsEnabled,
                    insightsTheoriesEnabled = assistant.memoryInsightsTheoriesEnabled,
                ),
            )
        }.fold(
            onSuccess = { result ->
                when (result) {
                    is me.rerere.rikkahub.memory.MemoryCaptureResult.Queued,
                    is me.rerere.rikkahub.memory.MemoryCaptureResult.Duplicate,
                    -> me.rerere.rikkahub.memory.ManualMemorySelectionResult.QUEUED

                    is me.rerere.rikkahub.memory.MemoryCaptureResult.Skipped ->
                        me.rerere.rikkahub.memory.ManualMemorySelectionResult.FAILED
                }
            },
            onFailure = {
                Log.w(TAG, "Manual Memory V2 selection capture failed", it)
                me.rerere.rikkahub.memory.ManualMemorySelectionResult.FAILED
            },
        )
    }

    private suspend fun createWorkspaceToolsIfReady(
        workspaceId: String?,
        cwd: String? = null,
        allowSharedStorage: Boolean = false,
    ): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        return createWorkspaceTools(
            workspaceId = workspaceId,
            workspaceRepository = workspaceRepository,
            cwd = cwd,
            allowSharedStorage = allowSharedStorage,
        )
    }

    // ---- 检查无效消�?----

    private fun checkInvalidMessages(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        var messagesNodes = conversation.messageNodes

        // 移除无效 tool (未执行的 Tool)
        messagesNodes = messagesNodes.mapIndexed { _, node ->
            // Check for Tool type with non-executed tools
            val hasPendingTools = node.currentMessage.getTools().any { !it.isExecuted }

            if (hasPendingTools) {
                // Keep messages that are ready to resume, such as approved/denied/answered tools.
                val hasResumableTool = node.currentMessage.getTools().any {
                    !it.isExecuted && it.approvalState.canResumeToolExecution()
                }
                if (hasResumableTool) {
                    return@mapIndexed node
                }

                // If all tools are executed, it's valid
                val allToolsExecuted = node.currentMessage.getTools().all { it.isExecuted }
                if (allToolsExecuted && node.currentMessage.getTools().isNotEmpty()) {
                    return@mapIndexed node
                }

                // Remove messages that still have unresolved tool approvals.
                return@mapIndexed node.copy(
                    messages = node.messages.filter { it.id != node.currentMessage.id },
                    selectIndex = node.selectIndex - 1
                )
            }
            node
        }

        // 更新index
        messagesNodes = messagesNodes.map { node ->
            if (node.messages.isNotEmpty() && node.selectIndex !in node.messages.indices) {
                node.copy(selectIndex = 0)
            } else {
                node
            }
        }

        // 移除无效消息
        messagesNodes = messagesNodes.filter { it.messages.isNotEmpty() }

        updateConversation(conversationId, conversation.copy(messageNodes = messagesNodes))
    }

    private fun cancelToolByUser(
        tool: UIMessagePart.Tool,
        cancellationResults: Map<String, CancelRequestResult>,
    ): UIMessagePart.Tool {
        val cancellationResult = cancellationResults[tool.toolCallId]
        val unknown = tool.isInterruptedAttempt &&
            cancellationResult !is CancelRequestResult.LocalWaitCancelledOnly
        return tool.copy(
            output = listOf(
                UIMessagePart.Text(
                    if (unknown) {
                        """{"status":"termination_unknown","error":"Tool execution was interrupted and its external side effect could not be confirmed."}"""
                    } else {
                        """{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}"""
                    }
                )
            ),
            approvalState = ToolApprovalState.Denied(
                if (unknown) "Tool termination could not be confirmed" else "Generation cancelled by user"
            )
        )
    }

    private suspend fun finishInterruptedPendingTools(
        conversationId: Uuid,
        cancellationResults: Map<String, CancelRequestResult> = emptyMap(),
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val lastMessageId = currentConversation.messageNodes.lastOrNull()?.currentMessage?.id
        var changed = false
        val updatedNodes = currentConversation.messageNodes.map { node ->
            node.copy(messages = node.messages.map { message ->
                var updated = message.finishPendingTools {
                    cancelToolByUser(it, cancellationResults)
                }
                if (
                    message.id == lastMessageId &&
                    message.role == MessageRole.ASSISTANT &&
                    (message.state != UIMessageState.COMPLETED || message.finishedAt == null) &&
                    message.state != UIMessageState.FAILED
                ) {
                    updated = updated.copy(state = UIMessageState.INTERRUPTED)
                }
                if (updated != message) changed = true
                updated
            })
        }
        if (changed) saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // ---- 生成标题 ----

    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false
    ) {
        val shouldGenerate = when {
            force -> true
            conversation.title.isBlank() -> true
            else -> false
        }
        if (!shouldGenerate) return

        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.titleModelId, fallback = settings.fastModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return
            // Same defence as handleLlmTurn: don't burn tokens on a disabled provider.
            if (!provider.enabled) return

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        prompt = settings.titlePrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(4).joinToString("\n\n") { it.summaryAsText(maxLength = 500) })
                    ),
                ),
                params = backgroundTextGenerationParams(model),
            )

            val generatedTitle = result.choices[0].message?.toText()?.trim().orEmpty()
            val updated = mergeConversationState(conversationId) { current ->
                if (!force && current.title.isNotBlank()) current
                else current.withGeneratedTitle(generatedTitle)
            }
            if (updated.title == generatedTitle) {
                conversationRepo.updateConversationTitle(conversationId, generatedTitle)
            }
        }.onFailure {
            if (it is CancellationException) throw it
            // Title generation is auxiliary �?a failure here doesn't block the chat
            // and surfaces visibly as a blank conversation title in the list. Don't
            // push it onto the user-facing error stream: when the title model 429s,
            // the next message sees title.isBlank()==true, tries again, 429s again,
            // and the user gets a popup per message until they switch models. Match
            // the generateSuggestion pattern (log only) to keep the surface quiet.
            Log.w(TAG, "generateTitle failed", it)
        }
    }

    // ---- 生成建议 ----

    suspend fun generateSuggestion(conversationId: Uuid, conversation: Conversation) {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            if (!settings.enableSuggestion) return
            val model = settings.findModelById(settings.suggestionModelId, fallback = settings.fastModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return
            // Same defence as handleLlmTurn: don't burn tokens on a disabled provider.
            if (!provider.enabled) return

            sessions[conversationId]?.updateState { current ->
                current.withGeneratedSuggestions(emptyList())
            }

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        settings.suggestionPrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(8).joinToString("\n\n") { it.summaryAsText(maxLength = 500) }),
                    )
                ),
                params = backgroundTextGenerationParams(model),
            )
            val suggestions =
                result.choices[0].message?.toText()?.split("\n")?.map { it.trim() }
                    ?.filter { it.isNotBlank() } ?: emptyList()

            val limitedSuggestions = suggestions.take(10)
            mergeConversationState(conversationId) { current ->
                current.withGeneratedSuggestions(limitedSuggestions)
            }
            conversationRepo.updateConversationSuggestions(conversationId, limitedSuggestions)
        }.onFailure {
            if (it is CancellationException) throw it
            // Suggestion generation is auxiliary �?log only, don't push onto the
            // user-facing error stream (mirrors the generateTitle failure handling).
            Log.w(TAG, "generateSuggestion failed", it)
        }
    }

    // ---- 压缩对话历史 ----

    suspend fun compressConversation(
        conversationId: Uuid,
        conversation: Conversation,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int = 32
    ): Result<Unit> {
        val foregroundLease = learningForegroundRegistry.enter(
            me.rerere.rikkahub.learning.resources.LearningForegroundWorkKind.MANUAL_COMPRESSION,
            kotlinx.coroutines.currentCoroutineContext()[Job],
        )
        return try {
        require(targetTokens in 100..32_000) { "Compression target must be between 100 and 32,000 tokens." }
        require(keepRecentMessages >= 0) { "Messages to keep cannot be negative." }

        val settings = settingsStore.settingsFlow.first()
        val configuredModel = settings.findModelById(settings.compressModelId)
        val configuredProvider = configuredModel?.findProvider(settings.providers)
        val conversationModel = settings.getChatModelForAssistant(conversation.assistantId)
        val conversationProvider = conversationModel?.findProvider(settings.providers)
        val binding = resolveCompressionModelBinding(
            configuredModel = configuredModel,
            configuredProvider = configuredProvider,
            configuredModelIsImplicitDefault = settings.compressModelId == DEFAULT_AUTO_MODEL_ID,
            conversationModel = conversationModel,
            conversationProvider = conversationProvider,
        )
        val model = binding.model
        val provider = binding.provider

        val providerHandler = providerManager.getProviderByType(provider)
        val allMessages = conversation.currentMessages

        // Split messages into those to compress and those to keep
        val retainedCount = keepRecentMessages.coerceAtMost(allMessages.size)
        val messagesToCompress = allMessages.dropLast(retainedCount)
        val messagesToKeep = allMessages.takeLast(retainedCount)
        if (messagesToCompress.isEmpty()) {
            throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
        }

        suspend fun compressMessages(messages: List<UIMessage>): String {
            val contentToCompress = messages.joinToString("\n\n") { it.summaryAsText() }
            val prompt = settings.compressPrompt.applyPlaceholders(
                "content" to contentToCompress,
                "target_tokens" to targetTokens.toString(),
                "additional_context" to if (additionalPrompt.isNotBlank()) {
                    "Additional instructions from user: $additionalPrompt"
                } else "",
                "locale" to Locale.getDefault().displayName
            )

            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = backgroundTextGenerationParams(model).copy(maxTokens = targetTokens),
            )

            return result.choices.firstOrNull()?.message?.toText()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: throw IllegalStateException("Compression model returned no usable summary.")
        }

        // Do not fan out manual compression requests concurrently. Some OpenAI-compatible
        // gateways accept ordinary chat but reject parallel large summary requests with 400/429.
        val compressedSummaries = buildList {
            splitManualCompressionMessages(
                messages = messagesToCompress,
                contextWindowTokens = model.userContextWindowTokens,
                targetTokens = targetTokens,
            ).forEach { chunk ->
                add(compressMessages(chunk))
            }
        }

        // Create a stable manual-compression prefix followed by the exact requested tail.
        val newMessageNodes = buildManualCompressionMessages(
            compressedSummaries = compressedSummaries,
            messagesToKeep = messagesToKeep,
        ).map { it.toMessageNode() }
        val newConversation = conversation.copy(
            messageNodes = newMessageNodes,
            chatSuggestions = emptyList(),
        )

        saveConversation(conversationId, newConversation)
        Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        } finally {
            runCatching { foregroundLease.close() }
        }
    }

    // ---- 通知 ----

    private fun sendGenerationDoneNotification(conversationId: Uuid, senderName: String) {
        // 先取�?Live Update 通知
        cancelLiveUpdateNotification(conversationId)

        val conversation = getConversationFlow(conversationId).value
        context.sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = 1
        ) {
            title = senderName
            content = conversation.currentMessages.lastOrNull()?.toText()?.take(50)?.trim() ?: ""
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = getPendingIntent(context, conversationId)
        }
    }

    private fun getLiveUpdateNotificationId(conversationId: Uuid): Int {
        return conversationId.hashCode() + 10000
    }

    private fun sendLiveUpdateNotification(
        conversationId: Uuid,
        messages: List<UIMessage>,
        senderName: String
    ) {
        val lastMessage = messages.lastOrNull() ?: return
        val parts = lastMessage.parts

        // 确定当前状�?
        val (chipText, statusText, contentText) = determineNotificationContent(parts)

        context.sendNotification(
            channelId = CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
            notificationId = getLiveUpdateNotificationId(conversationId)
        ) {
            title = senderName
            content = contentText
            subText = statusText
            ongoing = true
            onlyAlertOnce = true
            category = NotificationCompat.CATEGORY_PROGRESS
            useBigTextStyle = true
            contentIntent = getPendingIntent(context, conversationId)
            requestPromotedOngoing = true
            shortCriticalText = chipText
        }
    }

    private fun determineNotificationContent(parts: List<UIMessagePart>): Triple<String, String, String> {
        // 检查最近的 part 来确定状�?
        val lastReasoning = parts.filterIsInstance<UIMessagePart.Reasoning>().lastOrNull()
        val lastTool = parts.filterIsInstance<UIMessagePart.Tool>().lastOrNull()
        val lastText = parts.filterIsInstance<UIMessagePart.Text>().lastOrNull()

        return when {
            // 正在执行工具
            lastTool != null && !lastTool.isExecuted -> {
                // MCP tools are exposed as `mcp__<serverSlug>_<serverName>__<toolName>`; strip
                // both the prefix and the server segment so the notification shows the bare tool
                // name. Non-MCP tool names (no `mcp__` prefix) fall through unchanged via the
                // missingDelimiterValue, instead of being truncated at an embedded `__`.
                val toolName = lastTool.toolName
                    .removePrefix("mcp__")
                    .substringAfter("__", missingDelimiterValue = lastTool.toolName.removePrefix("mcp__"))
                Triple(
                    context.getString(R.string.notification_live_update_chip_tool),
                    context.getString(R.string.notification_live_update_tool, toolName),
                    lastTool.input.take(100)
                )
            }
            // 正在思考（Reasoning 未结束）
            lastReasoning != null && lastReasoning.finishedAt == null -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_thinking),
                    context.getString(R.string.notification_live_update_thinking),
                    lastReasoning.reasoning.takeLast(200)
                )
            }
            // 正在写回�?
            lastText != null -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_writing),
                    context.getString(R.string.notification_live_update_writing),
                    lastText.text.takeLast(200)
                )
            }
            // 默认状�?
            else -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_writing),
                    context.getString(R.string.notification_live_update_title),
                    ""
                )
            }
        }
    }

    private fun cancelLiveUpdateNotification(conversationId: Uuid) {
        context.cancelNotification(getLiveUpdateNotificationId(conversationId))
    }

    private fun getPendingIntent(context: Context, conversationId: Uuid): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", conversationId.toString())
        }
        return PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    // ---- 对话状态更�?----

    private fun updateConversation(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) return
        val session = getOrCreateSession(conversationId)
        checkFilesDelete(conversation, session.state.value)
        session.replaceState(conversation)
    }

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        mergeConversationState(conversationId, update)
    }

    private fun mergeConversationState(
        conversationId: Uuid,
        update: (Conversation) -> Conversation,
    ): Conversation {
        // ConversationSession serializes read-modify-write updates so concurrent writers
        // cannot overwrite each other. Also routes through checkFilesDelete so attached files keep
        // being garbage-collected when removed from the conversation.
        val session = getOrCreateSession(conversationId)
        return session.updateState { current ->
            val next = update(current)
            if (next.id != conversationId) current
            else {
                checkFilesDelete(next, current)
                next
            }
        }
    }

    private fun checkFilesDelete(newConversation: Conversation, oldConversation: Conversation) {
        val newFiles = newConversation.files
        val oldFiles = oldConversation.files
        val deletedFiles = oldFiles.filter { file ->
            newFiles.none { it == file }
        }
        if (deletedFiles.isNotEmpty()) {
            filesManager.deleteChatFiles(deletedFiles)
            Log.w(TAG, "checkFilesDelete: $deletedFiles")
        }
    }

    suspend fun saveConversation(
        conversationId: Uuid,
        conversation: Conversation,
        sourceInvalidationMode: ConversationSourceInvalidationMode =
            ConversationSourceInvalidationMode.APPLY,
        sourceInvalidationNowMs: Long = System.currentTimeMillis(),
    ) {
        val exists = conversationRepo.existsConversationById(conversation.id)
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) {
            return // 新会话且为空时不保存
        }
        // Refuse to overwrite a non-empty stored row with an empty in-memory snapshot.
        // This is the silent-data-loss guard: handleToolApproval / stopGeneration / etc.
        // could be called against an unhydrated session (post-restart), build an empty
        // updatedConversation, and call saveConversation. Without this guard we'd wipe
        // the Pending tool the user was trying to approve.
        if (exists && conversation.messageNodes.isEmpty()) {
            val storedHasContent = runCatching {
                conversationRepo.getConversationById(conversation.id)?.messageNodes?.isNotEmpty() == true
            }.getOrDefault(false)
            if (storedHasContent) {
                Log.w(TAG, "saveConversation: refusing to overwrite non-empty $conversationId with empty snapshot �?likely an unhydrated session")
                return
            }
        }

        val updatedConversation = conversation.copy()
        updateConversation(conversationId, updatedConversation)

        if (!exists) {
            conversationRepo.insertConversation(updatedConversation)
        } else {
            conversationRepo.updateConversation(
                conversation = updatedConversation,
                sourceInvalidationMode = sourceInvalidationMode,
                sourceInvalidationNowMs = sourceInvalidationNowMs,
            )
        }
    }

    // ---- 翻译消息 ----

    fun translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: Locale
    ) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.first()

                val messageText = message.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()

                if (messageText.isBlank()) return@launch

                // Set loading state for translation
                val loadingText = context.getString(R.string.translating)
                updateTranslationField(conversationId, message.id, loadingText)

                generationHandler.translateText(
                    settings = settings,
                    sourceText = messageText,
                    targetLanguage = targetLanguage
                ) { translatedText ->
                    // Update translation field in real-time
                    updateTranslationField(conversationId, message.id, translatedText)
                }.collect { /* Final translation already handled in onStreamUpdate */ }

                // Save the conversation after translation is complete
                saveConversation(conversationId, getConversationFlow(conversationId).value)
            } catch (e: Exception) {
                // Clear translation field on error
                clearTranslationField(conversationId, message.id)
                addError(e, conversationId, title = context.getString(R.string.error_title_translate_message))
            }
        }
    }

    private fun updateTranslationField(
        conversationId: Uuid,
        messageId: Uuid,
        translationText: String
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = translationText)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // ---- 消息操作 ----

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>
    ) {
        if (parts.isEmptyInputMessage()) return

        val currentConversation = getConversationFlow(conversationId).value
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(currentConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val processedParts = preprocessUserInputParts(parts, assistant)
        var edited = false

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (!node.messages.any { it.id == messageId }) {
                return@map node
            }
            edited = true

            node.copy(
                messages = node.messages + UIMessage(
                    role = node.role,
                    parts = processedParts,
                ),
                selectIndex = node.messages.size
            )
        }

        if (!edited) return

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            throw NotFoundException("Message not found")
        }

        val copiedNodes = currentConversation.messageNodes
            .subList(0, targetNodeIndex + 1)
            .map { node ->
                node.copy(
                    id = Uuid.random(),
                    messages = node.messages.map { message ->
                        message.copy(
                            parts = message.parts.map { part ->
                                part.copyWithForkedFileUrl()
                            }
                        )
                    }
                )
            }

        val forkConversation = Conversation(
            id = Uuid.random(),
            assistantId = currentConversation.assistantId,
            messageNodes = copiedNodes,
            customSystemPrompt = currentConversation.customSystemPrompt,
            modeInjectionIds = currentConversation.modeInjectionIds,
            lorebookIds = currentConversation.lorebookIds,
        )

        saveConversation(forkConversation.id, forkConversation)
        return forkConversation
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNode = currentConversation.messageNodes.firstOrNull { it.id == nodeId }
            ?: throw NotFoundException("Message node not found")

        if (selectIndex !in targetNode.messages.indices) {
            throw BadRequestException("Invalid selectIndex")
        }

        if (targetNode.selectIndex == selectIndex) {
            return
        }

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.id == nodeId) {
                node.copy(selectIndex = selectIndex)
            } else {
                node
            }
        }

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedConversation = buildConversationAfterMessageDelete(currentConversation, messageId)

        if (updatedConversation == null) {
            if (failIfMissing) {
                throw NotFoundException("Message not found")
            }
            return
        }

        saveConversation(conversationId, updatedConversation)
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) {
        deleteMessage(conversationId, message.id, failIfMissing = false)
    }

    private fun buildConversationAfterMessageDelete(
        conversation: Conversation,
        messageId: Uuid,
    ): Conversation? {
        val targetNodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            return null
        }

        val updatedNodes = conversation.messageNodes.mapIndexedNotNull { index, node ->
            if (index != targetNodeIndex) {
                return@mapIndexedNotNull node
            }

            val nextMessages = node.messages.filterNot { it.id == messageId }
            if (nextMessages.isEmpty()) {
                return@mapIndexedNotNull null
            }

            val nextSelectIndex = node.selectIndex.coerceAtMost(nextMessages.lastIndex)
            node.copy(
                messages = nextMessages,
                selectIndex = nextSelectIndex,
            )
        }

        return conversation.copy(messageNodes = updatedNodes)
    }

    private fun UIMessagePart.copyWithForkedFileUrl(): UIMessagePart {
        fun copyLocalFileIfNeeded(url: String): String {
            if (!url.startsWith("file:")) return url
            val copied = filesManager.createChatFilesByContents(listOf(url.toUri())).firstOrNull()
            return copied?.toString() ?: url
        }

        return when (this) {
            is UIMessagePart.Image -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Document -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Video -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Audio -> copy(url = copyLocalFileIfNeeded(url))
            else -> this
        }
    }

    fun clearTranslationField(conversationId: Uuid, messageId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = null)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // 停止当前会话生成任务（不清理会话缓存�?
    suspend fun stopGeneration(conversationId: Uuid): SubmitResult =
        submitEmergency(conversationId, StopCommand(), CommandOrigin.APP_UI)

}

private fun SubmitResult.toOwnerRunSubmission(): me.rerere.rikkahub.owner.OwnerRunSubmission = when (this) {
    is SubmitResult.Accepted -> me.rerere.rikkahub.owner.OwnerRunSubmission(true, "RUN_CONTROL_ACCEPTED", commandId)
    is SubmitResult.QueueFull -> me.rerere.rikkahub.owner.OwnerRunSubmission(false, "RUN_QUEUE_FULL")
    is SubmitResult.RuntimeUnavailable -> me.rerere.rikkahub.owner.OwnerRunSubmission(false, "RUN_RUNTIME_UNAVAILABLE")
    is SubmitResult.Rejected -> me.rerere.rikkahub.owner.OwnerRunSubmission(false, "RUN_CONTROL_REJECTED")
}

/**
 * Pure gate used by [ChatService] admission: should the very first user message on a
 * brand-new (still in-memory, never-persisted) ordinary conversation create the Room row
 * right now? Kept as a pure function so the regression can be unit-tested without the
 * full ChatService dependency graph.
 */
internal fun shouldMaterializeConversationAtFirstSend(
    origin: CommandOrigin,
    command: ChatCommand,
    isNewConversationDraft: Boolean,
    assistantExists: Boolean,
): Boolean =
    command is SendMessageCommand &&
        (origin == CommandOrigin.APP_UI || origin == CommandOrigin.WEB_API) &&
        isNewConversationDraft &&
        assistantExists
