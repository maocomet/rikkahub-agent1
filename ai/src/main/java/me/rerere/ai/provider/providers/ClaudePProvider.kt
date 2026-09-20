package me.rerere.ai.provider.providers

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.claudep.ClaudePCancelReason
import me.rerere.ai.provider.claudep.ClaudePCatalogResultBody
import me.rerere.ai.provider.claudep.ClaudePClientHelloBody
import me.rerere.ai.provider.claudep.ClaudePErrorCode
import me.rerere.ai.provider.claudep.ClaudePGatewayClient
import me.rerere.ai.provider.claudep.ClaudePGatewayException
import me.rerere.ai.provider.claudep.ClaudePGenerationHandle
import me.rerere.ai.provider.claudep.ClaudePGenerationLimits
import me.rerere.ai.provider.claudep.ClaudePGenerationStartBody
import me.rerere.ai.provider.claudep.ClaudePGenerationState
import me.rerere.ai.provider.claudep.ClaudePInbound
import me.rerere.ai.provider.claudep.ClaudePModelEntry
import me.rerere.ai.provider.claudep.ClaudePProtocol
import me.rerere.ai.provider.claudep.ClaudePRequestFingerprint
import me.rerere.ai.provider.claudep.ClaudePResumeKind
import me.rerere.ai.provider.claudep.ClaudePResumeResult
import me.rerere.ai.provider.claudep.ClaudePServerEvent
import me.rerere.ai.provider.claudep.ClaudePServerHelloBody
import me.rerere.ai.provider.claudep.ClaudePStopReason
import me.rerere.ai.provider.claudep.ClaudePTerminalGate
import me.rerere.ai.provider.claudep.ClaudePTerminalKind
import me.rerere.ai.provider.claudep.ClaudePTurn
import me.rerere.ai.provider.claudep.ClaudePTurnPart
import me.rerere.ai.provider.claudep.ClaudePUsageBody
import me.rerere.ai.ui.FinishCategory
import me.rerere.ai.ui.GenerationTerminal
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.ReasoningSource
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart

/**
 * Claude P provider — a remote Claude Code runtime behind a user-owned Gateway.
 *
 * CP1-A scope: the provider is complete and testable against a deterministic fake gateway. It
 * performs **no** network, DNS, process or credential work. Its transport is a
 * [ClaudePGatewayClient], and the only implementation the app wires up at this stage is the
 * fail-closed `UnpairedClaudePGatewayClient`.
 *
 * ### Phase 1 capability ceiling
 *
 * Text plus a bounded reasoning summary. Nothing else. The provider never advertises
 * [ModelAbility.TOOL], never accepts an image/document/audio/video part and never sends a tool
 * snapshot. Unsupported input is rejected *before* dispatch rather than stripped and sent as
 * plain text, because silently dropping an attachment would leave the user believing Claude saw
 * it.
 */
class ClaudePProvider(
    private val gateway: ClaudePGatewayClient,
    /** Opaque device identity. Never a credential — the private key stays in the Keystore (CP1-B). */
    private val deviceId: String = "unpaired-device",
    private val appVersion: String = "rikkahub-agent1",
    /**
     * Source of `request_id`. Injectable so idempotency can be exercised deterministically; the
     * default is a fresh v4 UUID per generation, which is the production behaviour.
     */
    private val requestIdFactory: () -> String = { Uuid.random().toString() },
    private val remoteThreadId: String = "local-thread",
    private val remoteBranchId: String = "local-branch",
) : Provider<ProviderSetting.ClaudeP> {

    private val handshakeMutex = Mutex()

    @Volatile
    private var cachedServerHello: ClaudePServerHelloBody? = null

    /** Test/diagnostic accessor for the negotiated handshake, if one has completed. */
    val negotiatedServerHello: ClaudePServerHelloBody?
        get() = cachedServerHello

    override suspend fun listModels(providerSetting: ProviderSetting.ClaudeP): List<Model> {
        ensureHandshake()
        return gateway.catalog().toModels()
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.ClaudeP,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> {
        // Ordering is the safety property: validate, then handshake, then dispatch. A rejected
        // input or an incompatible gateway must both produce zero dispatches.
        rejectUnsupportedInput(messages, params)
        ensureHandshake()

        val modelAlias = params.model.modelId
        if (modelAlias.isBlank()) {
            throw ClaudePUnsupportedInputException(ClaudePUnsupportedInput.MISSING_MODEL)
        }

        val turn = messages.lastUserTurn()
            ?: throw ClaudePUnsupportedInputException(ClaudePUnsupportedInput.EMPTY_TURN)
        val systemPrompt = messages.systemPromptOrNull()
        val requestId = requestIdFactory()
        val fingerprint = ClaudePRequestFingerprint.compute(
            deviceId = deviceId,
            remoteThreadId = remoteThreadId,
            remoteBranchId = remoteBranchId,
            mode = MODE_NEW,
            modelAlias = modelAlias,
            systemPrompt = systemPrompt,
            turn = turn,
        )
        val body = ClaudePGenerationStartBody(
            remoteThreadId = remoteThreadId,
            remoteBranchId = remoteBranchId,
            mode = MODE_NEW,
            modelAlias = modelAlias,
            systemPrompt = systemPrompt,
            turn = turn,
            rebuildHistory = null,
            toolSnapshot = null,
            limits = ClaudePGenerationLimits(maxOutputTokens = params.maxTokens),
        )

        // A provider stream owns exactly one remote dispatch. Collecting it a second time must
        // never create a second model request, so it fails loudly instead of silently re-running.
        val collected = AtomicBoolean(false)

        return flow {
            check(collected.compareAndSet(false, true)) {
                "ClaudePProvider.streamText() flow is single-shot; " +
                    "collect it once or start a new generation"
            }

            val attempt = ClaudePGenerationAttempt(gateway)
            try {
                val handle = gateway.startGeneration(requestId, fingerprint, body)
                attempt.bind(handle)
                pumpFrames(
                    frames = handle.frames(),
                    gate = ClaudePTerminalGate(),
                    generationId = handle.generationId,
                    onTerminal = attempt::markTerminal,
                    emit = { emit(it) },
                )
            } catch (cancelled: CancellationException) {
                // §8: an explicit cancel is the only thing that stops a remote generation. The
                // attempt tracker guarantees at most one RPC even if cancellation is observed
                // more than once.
                withContext(NonCancellable) { attempt.cancelOnce() }
                throw cancelled
            }
        }
    }

    /**
     * Aggregates one generation into a single chunk.
     *
     * It collects the *same* single-shot [streamText] flow rather than issuing its own request, so
     * it costs exactly one remote dispatch — never two.
     */
    override suspend fun generateText(
        providerSetting: ProviderSetting.ClaudeP,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk {
        var terminal: GenerationTerminal? = null
        var usage: TokenUsage? = null
        var model = params.model.modelId
        val parts = mutableListOf<UIMessagePart>()

        streamText(providerSetting, messages, params).collect { chunk ->
            chunk.usage?.let { usage = it }
            chunk.resolvedTerminal()?.let { terminal = it }
            model = chunk.model
            parts += chunk.choices.firstOrNull()?.delta?.parts.orEmpty()
        }

        val message = UIMessage(role = MessageRole.ASSISTANT, parts = parts)
        return MessageChunk(
            id = message.id.toString(),
            model = model,
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = null,
                    message = message,
                    finishReason = terminal?.providerReason,
                ),
            ),
            usage = usage,
            terminal = terminal,
        )
    }

    /**
     * Reconnect path: replays the gateway's bounded event buffer for an in-flight generation.
     *
     * It never calls `generation.start`. That is the entire point of `stream.resume` — a dropped
     * connection must not buy a second model invocation. When the buffer is gone the gateway
     * reports a terminal receipt or an explicitly unknown state, and the caller decides; nothing
     * here retries automatically.
     */
    suspend fun resumeStream(
        generationId: String,
        lastEventSeq: Long,
    ): Flow<ClaudePResumeOutcome> = flow {
        when (val resumed = gateway.resume(generationId, lastEventSeq)) {
            is ClaudePResumeResult.Terminal ->
                emit(ClaudePResumeOutcome.Terminal(resumed.receipt.safeState))

            ClaudePResumeResult.StateUnknown -> emit(ClaudePResumeOutcome.StateUnknown)

            is ClaudePResumeResult.Replayed -> {
                emit(ClaudePResumeOutcome.Replaying(resumed.outcome))
                pumpFrames(
                    frames = flow { resumed.frames.forEach { emit(it) } },
                    gate = ClaudePTerminalGate(),
                    generationId = generationId,
                    onTerminal = {},
                    emit = { emit(ClaudePResumeOutcome.Chunk(it)) },
                )
            }
        }
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> =
        throw ClaudePUnsupportedInputException(ClaudePUnsupportedInput.IMAGE_GENERATION)

    // -----------------------------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------------------------

    /**
     * Maps a server catalog onto [Model]s.
     *
     * Only enabled aliases survive, only `text` input is honoured, and reasoning is advertised
     * solely when the server lists `reasoning_summary`. Note what is *absent*: no alias is derived
     * from `server.hello.claude_code_version`, so a CLI build string can never become a model.
     */
    private fun ClaudePCatalogResultBody.toModels(): List<Model> = models
        .filter { it.enabled && it.alias.isNotBlank() }
        .map { it.toModel() }

    private fun ClaudePModelEntry.toModel(): Model {
        val abilities = buildList {
            if (features.contains(FEATURE_REASONING_SUMMARY)) add(ModelAbility.REASONING)
            // ModelAbility.TOOL is deliberately never added: Phase 1 has no tool bridge, and
            // advertising it would let the agent loop hand Claude tools nobody can execute.
        }
        return Model(
            modelId = alias,
            displayName = displayName.ifBlank { alias },
            type = ModelType.CHAT,
            inputModalities = listOf(Modality.TEXT),
            outputModalities = listOf(Modality.TEXT),
            abilities = abilities,
        )
    }

    /**
     * Rejects anything Phase 1 cannot faithfully send.
     *
     * Runs before the flow is constructed, so a rejected input cannot have reached the gateway.
     */
    private fun rejectUnsupportedInput(messages: List<UIMessage>, params: TextGenerationParams) {
        if (params.tools.isNotEmpty()) {
            throw ClaudePUnsupportedInputException(ClaudePUnsupportedInput.TOOL_DEFINITION)
        }
        messages.forEach { message ->
            message.parts.forEach { part ->
                when (part) {
                    is UIMessagePart.Text -> Unit
                    is UIMessagePart.Reasoning -> Unit
                    is UIMessagePart.Image ->
                        throw ClaudePUnsupportedInputException(ClaudePUnsupportedInput.IMAGE)

                    is UIMessagePart.Video ->
                        throw ClaudePUnsupportedInputException(ClaudePUnsupportedInput.VIDEO)

                    is UIMessagePart.Audio ->
                        throw ClaudePUnsupportedInputException(ClaudePUnsupportedInput.AUDIO)

                    is UIMessagePart.Document ->
                        throw ClaudePUnsupportedInputException(ClaudePUnsupportedInput.DOCUMENT)

                    is UIMessagePart.Tool,
                    is UIMessagePart.ToolCall,
                    is UIMessagePart.ToolResult,
                    ->
                        throw ClaudePUnsupportedInputException(ClaudePUnsupportedInput.TOOL_CALL)

                    is UIMessagePart.Search ->
                        throw ClaudePUnsupportedInputException(ClaudePUnsupportedInput.SEARCH_RESULT)
                }
            }
        }
    }

    private suspend fun ensureHandshake(): ClaudePServerHelloBody {
        cachedServerHello?.let { return it }
        return handshakeMutex.withLock {
            cachedServerHello?.let { return@withLock it }
            val hello = gateway.hello(
                ClaudePClientHelloBody(
                    appVersion = appVersion,
                    protocolVersions = listOf("v1"),
                    deviceId = deviceId,
                    nonce = "local-skeleton",
                    signature = "local-skeleton",
                    capabilities = listOf("text_stream", "cancel", "receipt_query"),
                ),
            )
            // A gateway speaking another major may have changed the meaning of events we think we
            // understand. Stop here: this must produce zero dispatches, not a best-effort run.
            if (!ClaudePProtocol.acceptsServerProtocolVersion(hello.protocolVersion)) {
                throw ClaudePGatewayException(ClaudePErrorCode.PROTOCOL_MISMATCH)
            }
            cachedServerHello = hello
            hello
        }
    }

    private companion object {
        const val MODE_NEW = "new"
        const val FEATURE_REASONING_SUMMARY = "reasoning_summary"
    }
}

// -------------------------------------------------------------------------------------------
// Frame routing
// -------------------------------------------------------------------------------------------

/** What one validated frame produced. */
private sealed interface ClaudePFrameRouting {
    /** Nothing user-visible: an acknowledgement, or content suppressed after a terminal. */
    data object Ignore : ClaudePFrameRouting

    data class Chunk(val chunk: MessageChunk) : ClaudePFrameRouting
}

/**
 * Parses and routes one raw server frame.
 *
 * This is the single place where transport bytes become content, which is what makes the
 * "unknown events are never text or a terminal" rule enforceable rather than merely documented.
 * A protocol violation throws; it must never be downgraded to a dropped frame.
 */
private fun routeFrame(
    raw: String,
    gate: ClaudePTerminalGate,
    generationId: String?,
): ClaudePFrameRouting = when (val inbound = ClaudePProtocol.parseInbound(raw)) {
    // Unknown optional events are neither text nor a terminal, so dropping them can never
    // fabricate an answer or end a generation early.
    ClaudePInbound.IgnoredUnknownEvent -> ClaudePFrameRouting.Ignore

    is ClaudePInbound.Rejected -> throw ClaudePGatewayException(
        ClaudePErrorCode.PROTOCOL_MISMATCH,
        rejection = inbound.reason,
        generationId = generationId,
    )

    is ClaudePInbound.Event -> gate.route(inbound.event)
}

/**
 * Turns one routed event into at most one chunk.
 *
 * [ClaudePTerminalGate] is the single local authority on terminals: the first
 * completed/cancelled/failed wins and everything after it is dropped, so a replayed duplicate or
 * a cancel racing a completion can never yield two terminals or trailing content.
 */
private fun ClaudePTerminalGate.route(event: ClaudePServerEvent): ClaudePFrameRouting = when (event) {
    is ClaudePServerEvent.ReasoningDelta -> {
        val text = event.body.summary
        if (!acceptsDeltas || text.isEmpty()) {
            ClaudePFrameRouting.Ignore
        } else {
            ClaudePFrameRouting.Chunk(
                event.deltaChunk(
                    UIMessagePart.Reasoning(
                        reasoning = text,
                        source = ReasoningSource.PROVIDER_NATIVE,
                    ),
                ),
            )
        }
    }

    is ClaudePServerEvent.TextDelta -> {
        val text = event.body.text
        if (!acceptsDeltas || text.isEmpty()) {
            ClaudePFrameRouting.Ignore
        } else {
            ClaudePFrameRouting.Chunk(event.deltaChunk(UIMessagePart.Text(text)))
        }
    }

    is ClaudePServerEvent.UsageUpdated -> {
        if (!acceptsDeltas) {
            ClaudePFrameRouting.Ignore
        } else {
            ClaudePFrameRouting.Chunk(event.usageChunk(event.body))
        }
    }

    is ClaudePServerEvent.Completed -> {
        if (!tryAccept(ClaudePTerminalKind.COMPLETED)) {
            ClaudePFrameRouting.Ignore
        } else {
            val stop = event.body.safeStopReason
            ClaudePFrameRouting.Chunk(
                event.terminalChunk(
                    category = when (stop) {
                        ClaudePStopReason.MAX_TOKENS -> FinishCategory.LENGTH
                        ClaudePStopReason.TIMEOUT -> FinishCategory.INCOMPLETE
                        else -> FinishCategory.STOP
                    },
                    providerReason = stop.wireValue,
                    usage = event.body.usage,
                    reasoningChars = event.body.reasoningChars,
                    answerChars = event.body.answerChars,
                ),
            )
        }
    }

    is ClaudePServerEvent.Cancelled -> {
        if (!tryAccept(ClaudePTerminalKind.CANCELLED)) {
            ClaudePFrameRouting.Ignore
        } else {
            ClaudePFrameRouting.Chunk(
                event.terminalChunk(
                    category = FinishCategory.CANCELLED,
                    providerReason = "cancelled",
                    usage = event.body.usage,
                ),
            )
        }
    }

    is ClaudePServerEvent.Failed -> {
        if (!tryAccept(ClaudePTerminalKind.FAILED)) {
            ClaudePFrameRouting.Ignore
        } else {
            ClaudePFrameRouting.Chunk(
                event.terminalChunk(
                    category = FinishCategory.FAILED,
                    providerReason = "failed",
                    usage = event.body.usage,
                    // Bounded enum name only. Raw gateway text is never surfaced or persisted.
                    incompleteDetail = "claude_p_error_${event.body.safeCode.name.lowercase()}",
                ),
            )
        }
    }

    // Handshake / catalog / acknowledgement events carry no user-visible content.
    is ClaudePServerEvent.ServerHello,
    is ClaudePServerEvent.CatalogResult,
    is ClaudePServerEvent.GenerationAccepted,
    is ClaudePServerEvent.GenerationStarted,
    is ClaudePServerEvent.MessageStarted,
    is ClaudePServerEvent.ReceiptResult,
    is ClaudePServerEvent.StreamResumeResult,
    -> ClaudePFrameRouting.Ignore
}

/** Collects a frame stream through [routeFrame], tracking terminal state as it goes. */
private suspend fun <T> pumpFrames(
    frames: Flow<String>,
    gate: ClaudePTerminalGate,
    generationId: String?,
    onTerminal: () -> Unit,
    emit: suspend (MessageChunk) -> Unit,
) {
    frames.collect { raw ->
        val routing = routeFrame(raw, gate, generationId)
        // Recorded before the chunk is emitted. A consumer that stops collecting *because* it saw
        // the terminal (a `take(n)`, an upstream timeout) is not a user cancel, and sending one
        // there would race a generation that had already finished.
        if (gate.terminalSeen) onTerminal()
        when (routing) {
            ClaudePFrameRouting.Ignore -> Unit
            is ClaudePFrameRouting.Chunk -> emit(routing.chunk)
        }
    }
}

// -------------------------------------------------------------------------------------------
// Chunk construction
// -------------------------------------------------------------------------------------------

private fun ClaudePServerEvent.deltaChunk(part: UIMessagePart): MessageChunk = MessageChunk(
    id = envelope.generationId ?: "claude-p",
    model = modelAliasHint(),
    choices = listOf(
        UIMessageChoice(
            index = 0,
            delta = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(part)),
            message = null,
            finishReason = null,
        ),
    ),
)

private fun ClaudePServerEvent.usageChunk(usage: ClaudePUsageBody): MessageChunk = MessageChunk(
    id = envelope.generationId ?: "claude-p",
    model = modelAliasHint(),
    choices = emptyList(),
    usage = usage.toTokenUsage(),
    terminal = null,
)

private fun ClaudePServerEvent.terminalChunk(
    category: FinishCategory,
    providerReason: String,
    usage: ClaudePUsageBody?,
    reasoningChars: Int = 0,
    answerChars: Int = 0,
    incompleteDetail: String? = null,
): MessageChunk = MessageChunk(
    id = envelope.generationId ?: "claude-p",
    model = modelAliasHint(),
    choices = listOf(
        UIMessageChoice(
            index = 0,
            delta = null,
            message = null,
            finishReason = providerReason,
        ),
    ),
    usage = usage?.toTokenUsage(),
    terminal = GenerationTerminal(
        terminalSeen = true,
        category = category,
        providerReason = providerReason,
        incompleteDetail = incompleteDetail,
        reasoningChars = reasoningChars,
        answerChars = answerChars,
    ),
)

private fun ClaudePServerEvent.modelAliasHint(): String = when (this) {
    is ClaudePServerEvent.GenerationStarted -> body.modelAlias.ifBlank { PROVIDER_MODEL_FALLBACK }
    else -> PROVIDER_MODEL_FALLBACK
}

private const val PROVIDER_MODEL_FALLBACK = "claude_p"

private fun ClaudePUsageBody.toTokenUsage(): TokenUsage = TokenUsage(
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    cachedTokens = cachedTokens,
    totalTokens = if (totalTokens > 0) totalTokens else promptTokens + completionTokens,
    latestPromptTokens = promptTokens,
    latestCompletionTokens = completionTokens,
    latestCachedTokens = cachedTokens,
    providerCallCount = 1,
)

// -------------------------------------------------------------------------------------------
// Cancellation
// -------------------------------------------------------------------------------------------

/**
 * Sends `generation.cancel` at most once for one generation attempt.
 *
 * Cancellation can be observed more than once (flow cancellation plus an upstream timeout, for
 * example). The gateway treats cancel as idempotent, but the client still must not spam it.
 */
internal class ClaudePGenerationAttempt(private val gateway: ClaudePGatewayClient) {
    private val cancelSent = AtomicBoolean(false)

    @Volatile
    private var generationId: String? = null

    @Volatile
    private var terminalSeen: Boolean = false

    fun bind(handle: ClaudePGenerationHandle) {
        generationId = handle.generationId
    }

    /** Records that a terminal was already observed, so cancelling would be pointless. */
    fun markTerminal() {
        terminalSeen = true
    }

    /**
     * Cancels exactly once.
     *
     * If a terminal was already observed there is nothing left to stop, so no RPC is issued —
     * which is what keeps a cancel/completion race from producing a second terminal.
     */
    suspend fun cancelOnce(): Boolean {
        val id = generationId ?: return false
        if (terminalSeen) return false
        if (!cancelSent.compareAndSet(false, true)) return false
        return try {
            gateway.cancel(id, ClaudePCancelReason.USER_REQUESTED)
            true
        } catch (_: ClaudePGatewayException) {
            // The generation may have ended underneath us. That is not a client-visible failure:
            // the receipt, not the cancel, is the authority on the terminal.
            false
        }
    }
}

// -------------------------------------------------------------------------------------------
// Input gating
// -------------------------------------------------------------------------------------------

/** Input shapes Phase 1 must refuse rather than silently narrow. */
enum class ClaudePUnsupportedInput {
    IMAGE,
    DOCUMENT,
    AUDIO,
    VIDEO,
    TOOL_CALL,
    TOOL_DEFINITION,
    SEARCH_RESULT,
    IMAGE_GENERATION,
    MISSING_MODEL,
    EMPTY_TURN,
}

/** Thrown before any dispatch. Carries a bounded enum, never the rejected content. */
class ClaudePUnsupportedInputException(
    val input: ClaudePUnsupportedInput,
) : IllegalArgumentException("claude_p_unsupported_input: ${input.name}")

/** Outcome of a reconnect, so callers can distinguish replay from an unprovable state. */
sealed interface ClaudePResumeOutcome {
    data class Replaying(val kind: ClaudePResumeKind) : ClaudePResumeOutcome

    data class Chunk(val chunk: MessageChunk) : ClaudePResumeOutcome

    data class Terminal(val state: ClaudePGenerationState) : ClaudePResumeOutcome

    /** The gateway cannot prove a terminal. The user decides; we never auto-retry. */
    data object StateUnknown : ClaudePResumeOutcome
}

// -------------------------------------------------------------------------------------------
// Message -> turn compilation
// -------------------------------------------------------------------------------------------

private fun List<UIMessage>.lastUserTurn(): ClaudePTurn? {
    val message = lastOrNull { it.role == MessageRole.USER } ?: return null
    val parts = message.parts.filterIsInstance<UIMessagePart.Text>()
        .filter { it.text.isNotEmpty() }
        .map { ClaudePTurnPart(type = "text", text = it.text) }
    if (parts.isEmpty()) return null
    return ClaudePTurn(role = "user", parts = parts)
}

private fun List<UIMessage>.systemPromptOrNull(): String? {
    val system = filter { it.role == MessageRole.SYSTEM }
        .flatMap { it.parts.filterIsInstance<UIMessagePart.Text>() }
        .map { it.text }
        .filter { it.isNotEmpty() }
    return system.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
}
