package me.rerere.ai.provider.providers

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.EmbeddingGenerationResult
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.claudep.ClaudePCancelReason
import me.rerere.ai.provider.claudep.ClaudePCatalogRecorder
import me.rerere.ai.provider.claudep.ClaudePClientHelloBody
import me.rerere.ai.provider.claudep.ClaudePErrorCode
import me.rerere.ai.provider.claudep.ClaudePGatewayClient
import me.rerere.ai.provider.claudep.ClaudePGatewayException
import me.rerere.ai.provider.claudep.ClaudePGenerationLimits
import me.rerere.ai.provider.claudep.ClaudePGenerationStartBody
import me.rerere.ai.provider.claudep.ClaudePInbound
import me.rerere.ai.provider.claudep.ClaudePProtocol
import me.rerere.ai.provider.claudep.ClaudePRequestFingerprint
import me.rerere.ai.provider.claudep.ClaudePServerEvent
import me.rerere.ai.provider.claudep.ClaudePSessionBindingIntent
import me.rerere.ai.provider.claudep.ClaudePSessionMode
import me.rerere.ai.provider.claudep.ClaudePTurn
import me.rerere.ai.provider.claudep.ClaudePTurnPart
import me.rerere.ai.provider.claudep.ClaudePToolResultBody
import me.rerere.ai.provider.claudep.ClaudePUsageBody
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * Minimal Claude P provider for the upstream RikkaHub generation loop.
 *
 * Conversation identity is the existing [TextGenerationParams.sessionId]. The Worker binds that
 * exact RikkaHub conversation to the Claude session obtained from Claude Code's result event and
 * resolves later turns with `--resume`; Android sends only the newest user turn.
 */
class ClaudePProvider(
    private val gateway: ClaudePGatewayClient,
    private val deviceIdProvider: suspend () -> String?,
    private val appVersion: String,
    private val catalogRecorder: ClaudePCatalogRecorder? = null,
) : Provider<ProviderSetting.ClaudeP> {

    override suspend fun listModels(providerSetting: ProviderSetting.ClaudeP): List<Model> {
        val deviceId = requireDeviceId()
        val hello = gateway.hello(helloBody(deviceId))
        val entries = gateway.catalog().enabledModels().filter(::isAllowedSonnet)
        catalogRecorder?.recordCatalog(entries.map { it.toCachedModel() }, hello.claudeCodeVersion)
        return entries.map { entry ->
            Model(
                modelId = entry.alias,
                displayName = entry.displayName.ifBlank { entry.alias },
                type = ModelType.CHAT,
                inputModalities = listOf(Modality.TEXT),
                outputModalities = listOf(Modality.TEXT),
                abilities = listOf(ModelAbility.TOOL),
            )
        }
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.ClaudeP,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = flow {
        val conversationId = params.sessionId?.takeIf { it.isNotBlank() }
            ?: throw ClaudePInputException("missing conversation id")
        val modelId = params.model.modelId.takeIf { it.isNotBlank() }
            ?: throw ClaudePInputException("missing model id")
        if (!isAllowedSonnetId(modelId)) throw ClaudePInputException("model is not in the Sonnet 4.5/4.6 allowlist")

        val turnText = messages.lastOrNull { it.role == MessageRole.USER }
            ?.parts?.joinToString("\n") { part ->
                when (part) {
                    is UIMessagePart.Text -> part.text
                    else -> throw ClaudePInputException("Claude P MVP supports text input only")
                }
            }?.takeIf { it.isNotBlank() }
            ?: throw ClaudePInputException("empty user turn")
        val systemPrompt = messages.firstOrNull { it.role == MessageRole.SYSTEM }?.toText()?.takeIf { it.isNotBlank() }
        val deviceId = requireDeviceId()
        gateway.hello(helloBody(deviceId))

        val turn = ClaudePTurn("user", listOf(ClaudePTurnPart(text = turnText)))
        val frozenTools = freezeTools(params.tools)
        val toolSnapshot = frozenTools.snapshot
        val bindingIntent = ClaudePSessionBindingIntent.IMMEDIATE.wireValue
        val assistantId = providerSetting.id.toString()
        val body = ClaudePGenerationStartBody(
            remoteThreadId = conversationId,
            remoteBranchId = conversationId,
            mode = ClaudePSessionMode.AUTO,
            modelAlias = modelId,
            systemPrompt = systemPrompt,
            turn = turn,
            rebuildHistory = null,
            toolSnapshot = toolSnapshot,
            limits = ClaudePGenerationLimits(params.maxTokens),
            bindingIntent = bindingIntent,
            assistantId = assistantId,
        )
        val requestId = Uuid.random().toString()
        val fingerprint = ClaudePRequestFingerprint.compute(
            deviceId = deviceId,
            remoteThreadId = conversationId,
            remoteBranchId = conversationId,
            mode = ClaudePSessionMode.AUTO,
            modelAlias = modelId,
            systemPrompt = systemPrompt,
            turn = turn,
            toolSnapshot = toolSnapshot?.toString(),
            assistantId = assistantId,
            bindingIntent = bindingIntent,
        )
        val handle = gateway.startGeneration(requestId, fingerprint, body)
        val terminal = AtomicBoolean(false)
        var textStarted = false
        var reasoningStarted = false
        var actualModel: String? = null
        var textId: String? = null
        var reasoningId: String? = null
        try {
            handle.frames().collect { raw ->
                val inbound = ClaudePProtocol.parseInbound(raw)
                val event = (inbound as? ClaudePInbound.Event)?.event ?: return@collect
                when (event) {
                    is ClaudePServerEvent.GenerationStarted -> actualModel = event.body.modelAlias
                    is ClaudePServerEvent.ReasoningDelta -> {
                        val id = event.body.messageId ?: "reasoning"
                        reasoningId = reasoningId ?: id
                        if (!reasoningStarted) {
                            emit(StreamChunk.ReasoningStart(id))
                            reasoningStarted = true
                        }
                        if (event.body.summary.isNotEmpty()) emit(StreamChunk.ReasoningDelta(id, event.body.summary))
                    }
                    is ClaudePServerEvent.TextDelta -> {
                        val id = event.body.messageId ?: "answer"
                        textId = textId ?: id
                        if (!textStarted) {
                            emit(StreamChunk.TextStart(id))
                            textStarted = true
                        }
                        if (event.body.text.isNotEmpty()) emit(StreamChunk.TextDelta(id, event.body.text))
                    }
                    is ClaudePServerEvent.ToolInvoke -> {
                        val tool = frozenTools.byFrozenName[event.body.toolName]
                        val result = when {
                            tool == null -> ClaudePToolResultBody(
                                event.body.toolCallId,
                                "failed",
                            )
                            tool.needsApproval(event.body.arguments) -> ClaudePToolResultBody(
                                event.body.toolCallId,
                                "denied",
                            )
                            else -> try {
                                val output = tool.execute(event.body.arguments).joinToString("\n") { part ->
                                    when (part) {
                                        is UIMessagePart.Text -> part.text
                                        else -> ClaudePProtocol.json.encodeToString(UIMessagePart.serializer(), part)
                                    }
                                }
                                ClaudePToolResultBody(event.body.toolCallId, "completed", output)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Throwable) {
                                ClaudePToolResultBody(event.body.toolCallId, "failed")
                            }
                        }
                        gateway.sendToolResult(handle.generationId, result)
                    }
                    is ClaudePServerEvent.Completed -> {
                        terminal.set(true)
                        reasoningId?.let { emit(StreamChunk.ReasoningEnd(it)) }
                        textId?.let { emit(StreamChunk.TextEnd(it)) }
                        event.body.usage?.let { emit(StreamChunk.Usage(it.toTokenUsage())) }
                        emit(StreamChunk.Finish(event.body.stopReason, handle.generationId, actualModel ?: modelId))
                    }
                    is ClaudePServerEvent.Failed -> {
                        terminal.set(true)
                        throw ClaudePGatewayException(event.body.safeCode, generationId = handle.generationId)
                    }
                    is ClaudePServerEvent.Cancelled -> {
                        terminal.set(true)
                        throw CancellationException("Claude P generation cancelled")
                    }
                    else -> Unit
                }
            }
            if (!terminal.get()) throw ClaudePGatewayException(ClaudePErrorCode.STREAM_INTERRUPTED, generationId = handle.generationId)
        } catch (cancelled: CancellationException) {
            if (!terminal.getAndSet(true)) runCatching { gateway.cancel(handle.generationId, ClaudePCancelReason.USER_REQUESTED) }
            throw cancelled
        }
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.ClaudeP,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult {
        val text = StringBuilder()
        var usage: TokenUsage? = null
        var responseId = ""
        var actualModel = params.model.modelId
        streamText(providerSetting, messages, params).collect { chunk ->
            when (chunk) {
                is StreamChunk.TextDelta -> text.append(chunk.text)
                is StreamChunk.Usage -> usage = chunk.usage
                is StreamChunk.Finish -> {
                    responseId = chunk.responseId.orEmpty()
                    actualModel = chunk.model ?: actualModel
                }
                else -> Unit
            }
        }
        return TextGenerationResult(
            id = responseId,
            model = actualModel,
            message = UIMessage.assistant(text.toString()),
            usage = usage,
        )
    }

    override suspend fun generateEmbedding(
        providerSetting: ProviderSetting.ClaudeP,
        params: EmbeddingGenerationParams,
    ): EmbeddingGenerationResult = error("Claude P does not support embeddings")

    private suspend fun requireDeviceId(): String = deviceIdProvider()?.takeIf { it.isNotBlank() }
        ?: throw ClaudePGatewayException(ClaudePErrorCode.NOT_PAIRED)

    private fun helloBody(deviceId: String) = ClaudePClientHelloBody(
        appVersion = appVersion,
        deviceId = deviceId,
        nonce = "",
        signature = "",
        capabilities = listOf("catalog", "generation", "session_resume", "tool_bridge"),
    )

    private fun isAllowedSonnet(entry: me.rerere.ai.provider.claudep.ClaudePModelEntry): Boolean =
        isAllowedSonnetId(entry.alias)

    private fun isAllowedSonnetId(value: String): Boolean {
        val normalized = value.lowercase()
        return normalized.startsWith("claude-sonnet-4-5") || normalized.startsWith("claude-sonnet-4-6")
    }

    private fun freezeTools(tools: List<Tool>): FrozenTools {
        val candidates = tools.mapNotNull { tool ->
            val frozen = tool.name.lowercase().takeIf { name ->
                name.matches(Regex("[a-z][a-z0-9_-]*"))
            } ?: return@mapNotNull null
            frozen to tool
        }
        val unique = candidates.groupBy { it.first }.filterValues { it.size == 1 }
            .values.map { it.single() }.sortedBy { it.first }
        if (unique.isEmpty()) return FrozenTools(null, emptyMap())

        val entries = unique.map { (frozen, tool) ->
            val schema: JsonElement = tool.parameters()?.let {
                ClaudePProtocol.json.encodeToJsonElement(InputSchema.serializer(), it)
            } ?: buildJsonObject {
                put("type", "object")
                put("properties", JsonObject(emptyMap()))
            }
            JsonObject(linkedMapOf(
                "name" to JsonPrimitive(frozen),
                "displayName" to JsonPrimitive(tool.name),
                "description" to JsonPrimitive(tool.description),
                "inputSchema" to schema,
                "readOnly" to JsonPrimitive(false),
                "source" to JsonPrimitive(if (tool.name.startsWith("mcp__")) "mcp" else "local"),
            ))
        }
        return FrozenTools(
            snapshot = JsonObject(linkedMapOf("tools" to JsonArray(entries))),
            byFrozenName = unique.associate { it.first to it.second },
        )
    }
}

private data class FrozenTools(
    val snapshot: JsonObject?,
    val byFrozenName: Map<String, Tool>,
)

private fun ClaudePUsageBody.toTokenUsage() = TokenUsage(
    promptTokens = inputTokens ?: promptTokens,
    completionTokens = completionTokens,
    cachedTokens = cachedTokens,
    cacheCreationTokens = cacheCreationInputTokens,
    totalTokens = totalTokens,
)

private fun me.rerere.ai.provider.claudep.ClaudePCatalogResultBody.enabledModels() =
    models.filter { it.enabled && it.alias.isNotBlank() }

private fun me.rerere.ai.provider.claudep.ClaudePModelEntry.toCachedModel() =
    me.rerere.ai.provider.claudep.ClaudePCachedModel(
        alias = alias,
        displayName = displayName,
        reasoningSummary = "reasoning_summary" in features,
    )

class ClaudePInputException(message: String) : IllegalArgumentException(message)
