package me.rerere.ai.ui

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.claudep.ClaudePSessionContinuation
import me.rerere.ai.util.json
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

// 公共消息抽象, 具体的Provider实现会转换为API接口需要的DTO
@Serializable
enum class UIMessageState {
    DRAFT,
    STREAMING,
    WAITING_TOOL,
    COMPLETED,
    INTERRUPTED,
    INCOMPLETE_NO_VISIBLE_ANSWER,
    FAILED,
}

@Serializable
enum class FinalAnswerRecoveryStatus {
    STARTED,
    SUCCEEDED,
    FAILED,
}

@Serializable
enum class ReasoningSource {
    PROVIDER_NATIVE,
    THINK_TAG,
    FINAL_ANSWER_RECOVERY,
}

@Serializable
data class UIMessage(
    val id: Uuid = Uuid.random(),
    val role: MessageRole,
    val parts: List<UIMessagePart>,
    val annotations: List<UIMessageAnnotation> = emptyList(),
    val createdAt: LocalDateTime = Clock.System.now()
        .toLocalDateTime(TimeZone.currentSystemDefault()),
    val finishedAt: LocalDateTime? = null,
    val modelId: Uuid? = null,
    val usage: TokenUsage? = null,
    val translation: String? = null,
    val state: UIMessageState = UIMessageState.COMPLETED,
    /**
     * The Claude P continuation state this message recorded for one branch, or `null`.
     *
     * ### Why it is here, and why it is not `@Transient`
     *
     * A Claude P branch has to be resumed under a session the Server bound to *this* message
     * graph, and whether that binding exists is a durable fact. The authority for the graph is
     * these rows, so the fact has to be durable in exactly the same place: it is written inside
     * the same Room transaction that commits the graph, and a graph rollback takes it with it.
     * A `@Transient` field — the obvious way to keep provider bookkeeping out of the message
     * model — would be erased by the first process restart, which is precisely the event the
     * state exists to survive.
     *
     * Nothing here is provider-visible. [ClaudePSessionContinuation] carries no session id, no
     * config hash and no credential, and the app never lets this field reach a prompt, a
     * `generation.start` body, a request fingerprint or a log line: it is read by the
     * continuation resolver and written by the continuation barrier, and nowhere else.
     *
     * ### Why it is nullable with a `null` default
     *
     * Almost every message in almost every conversation has no Claude P continuation — every
     * message of every other provider, and every Claude P message that predates this field.
     * `null` means "this message recorded nothing", which is the same answer for all of them and
     * is the correct one: the resolver treats an absent record as a branch with no history, and
     * never as a branch that was bound.
     */
    val claudePSessionContinuation: ClaudePSessionContinuation? = null,
) {
    private fun appendChunk(chunk: MessageChunk): UIMessage {
        val choice = chunk.choices.getOrNull(0)
        val message = choice?.delta ?: choice?.message
        return message?.let { delta ->
            // Handle Parts
            var newParts = delta.parts.fold(parts) { acc, deltaPart ->
                when (deltaPart) {
                    is UIMessagePart.Text -> {
                        // Skip empty text deltas
                        if (deltaPart.text.isEmpty()) {
                            acc
                        } else {
                            val lastPart = acc.lastOrNull()
                            if (lastPart is UIMessagePart.Text) {
                                // Append to the last Text part
                                acc.dropLast(1) + lastPart.copy(text = lastPart.text + deltaPart.text)
                            } else {
                                // Create new Text part
                                acc + deltaPart
                            }
                        }
                    }

                    is UIMessagePart.Image -> {
                        val lastPart = acc.lastOrNull()
                        if (lastPart is UIMessagePart.Image) {
                            // Append to the last Image part (for streaming base64)
                            acc.dropLast(1) + lastPart.copy(
                                url = lastPart.url + deltaPart.url,
                                metadata = deltaPart.metadata ?: lastPart.metadata
                            )
                        } else {
                            // Create new Image part
                            acc + UIMessagePart.Image(
                                url = "data:image/png;base64,${deltaPart.url}",
                                metadata = deltaPart.metadata,
                            )
                        }
                    }

                    is UIMessagePart.Reasoning -> {
                        val incomingReasoning = if (annotations.any { annotation ->
                                annotation is UIMessageAnnotation.FinalAnswerRecovery &&
                                    annotation.status == FinalAnswerRecoveryStatus.STARTED
                            }
                        ) {
                            deltaPart.copy(source = ReasoningSource.FINAL_ANSWER_RECOVERY)
                        } else {
                            deltaPart
                        }
                        // Skip empty reasoning deltas
                        if (incomingReasoning.reasoning.isEmpty() && incomingReasoning.metadata == null) {
                            acc
                        } else {
                            val lastPart = acc.lastOrNull()
                            if (lastPart is UIMessagePart.Reasoning &&
                                lastPart.source == incomingReasoning.source
                            ) {
                                // Append to the last Reasoning part
                                acc.dropLast(1) + lastPart.copy(
                                    reasoning = lastPart.reasoning + incomingReasoning.reasoning,
                                    finishedAt = null,
                                    metadata = incomingReasoning.metadata ?: lastPart.metadata,
                                )
                            } else {
                                // Create new Reasoning part
                                acc + incomingReasoning
                            }
                        }
                    }

                    is UIMessagePart.Tool -> {
                        if (deltaPart.toolCallId.isBlank()) {
                            // No ID yet - append to the last Tool if it also has no ID
                            val lastTool = acc.lastOrNull { it is UIMessagePart.Tool } as? UIMessagePart.Tool
                            if (lastTool != null) {
                                acc.map { part ->
                                    if (part === lastTool) part.merge(deltaPart) else part
                                }
                            } else {
                                acc + deltaPart.copy()
                            }
                        } else {
                            // Has ID - find and update by ID, or insert new
                            val existsPart = acc.find {
                                it is UIMessagePart.Tool && it.toolCallId == deltaPart.toolCallId
                            } as? UIMessagePart.Tool
                            if (existsPart == null) {
                                acc + deltaPart.copy()
                            } else {
                                acc.map { part ->
                                    if (part is UIMessagePart.Tool && part.toolCallId == deltaPart.toolCallId) {
                                        part.merge(deltaPart)
                                    } else part
                                }
                            }
                        }
                    }

                    else -> {
                        println("delta part append not supported: $deltaPart")
                        acc
                    }
                }
            }
            // Handle Reasoning End
            if (parts.filterIsInstance<UIMessagePart.Reasoning>()
                    .isNotEmpty() && delta.parts.filterIsInstance<UIMessagePart.Reasoning>()
                    .isEmpty()
            ) {
                newParts = newParts.map { part ->
                    if (part is UIMessagePart.Reasoning && part.finishedAt == null) {
                        part.copy(finishedAt = Clock.System.now())
                    } else part
                }
            }
            // Handle annotations
            val newAnnotations = delta.annotations.ifEmpty {
                annotations
            }
            copy(
                parts = newParts,
                annotations = newAnnotations,
            )
        } ?: this
    }

    fun summaryAsText(maxLength: Int = Int.MAX_VALUE): String {
        val text = "[${role.name}]: " + parts.joinToString(separator = "\n") { part ->
            when (part) {
                is UIMessagePart.Text -> part.text
                else -> ""
            }
        }
        return if (text.length > maxLength) text.take(maxLength) + "..." else text
    }

    fun toText() = parts.joinToString(separator = "\n") { part ->
        when (part) {
            is UIMessagePart.Text -> part.text
            else -> ""
        }
    }

    fun getTools() = parts.filterIsInstance<UIMessagePart.Tool>()

    fun isValidToUpload() = parts.any { part ->
        when (part) {
            is UIMessagePart.Text -> part.text.isNotBlank()
            is UIMessagePart.Image -> part.url.isNotBlank()
            is UIMessagePart.Video -> part.url.isNotBlank()
            is UIMessagePart.Audio -> part.url.isNotBlank()
            is UIMessagePart.Document -> part.url.isNotBlank()
            is UIMessagePart.Reasoning -> part.reasoning.isNotBlank()
            else -> true
        }
    }

    inline fun <reified P : UIMessagePart> hasPart(): Boolean {
        return parts.any {
            it is P
        }
    }

    fun hasBase64Part(): Boolean = parts.any {
        it is UIMessagePart.Image && it.url.startsWith("data:")
    }

    operator fun plus(chunk: MessageChunk): UIMessage {
        return this.appendChunk(chunk)
    }

    companion object {
        fun system(prompt: String) = UIMessage(
            role = MessageRole.SYSTEM,
            parts = listOf(UIMessagePart.Text(prompt))
        )

        fun user(prompt: String) = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text(prompt))
        )

        fun assistant(prompt: String) = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text(prompt))
        )
    }
}

/**
 * 处理MessageChunk合并
 *
 * @receiver 已有消息列表
 * @param chunk 消息chunk
 * @param model 模型, 可以不传，如果传了，会把模型id写入到消息，标记是哪个模型输出的消息
 * @return 新消息列表
 */
fun List<UIMessage>.handleMessageChunk(chunk: MessageChunk, model: Model? = null): List<UIMessage> {
    require(this.isNotEmpty()) {
        "messages must not be empty"
    }
    val choice = chunk.choices.getOrNull(0) ?: return this
    val message = choice.delta ?: choice.message ?: return this
    if (this.last().role != message.role) {
        return this + (UIMessage(modelId = model?.id, role = message.role, parts = emptyList()) + chunk)
    } else {
        val last = this.last() + chunk
        return this.dropLast(1) + last
    }
}

/**
 * 判断这个消息是否有有任何用户**可输入内容**
 *
 * 例如: 文本，图片, 文档
 */
fun List<UIMessagePart>.isEmptyInputMessage(): Boolean {
    if (this.isEmpty()) return true
    return this.all { message ->
        when (message) {
            is UIMessagePart.Text -> message.text.isBlank()
            is UIMessagePart.Image -> message.url.isBlank()
            is UIMessagePart.Document -> message.url.isBlank()
            is UIMessagePart.Video -> message.url.isBlank()
            is UIMessagePart.Audio -> message.url.isBlank()
            else -> true
        }
    }
}

/**
 * 判断这个消息在UI上是否显示任何内容
 */
fun List<UIMessagePart>.isEmptyUIMessage(): Boolean {
    if (this.isEmpty()) return true
    return this.all { message ->
        when (message) {
            is UIMessagePart.Text -> message.text.isBlank()
            is UIMessagePart.Image -> message.url.isBlank()
            is UIMessagePart.Document -> message.url.isBlank()
            is UIMessagePart.Reasoning -> message.reasoning.isBlank()
            is UIMessagePart.Video -> message.url.isBlank()
            is UIMessagePart.Audio -> message.url.isBlank()
            else -> true
        }
    }
}

fun List<UIMessage>.limitContext(size: Int): List<UIMessage> {
    if (size <= 0 || this.size <= size) return this

    // Move the boundary in large deterministic strides instead of sliding it by one message on
    // every turn. For limit=40 the first overflow keeps roughly 20 messages, then the same prefix
    // remains cacheable for the next ~20 appended messages.
    val retainedFloor = (size / 2).coerceAtLeast(1)
    val stride = (size - retainedFloor).coerceAtLeast(1)
    val overflow = this.size - size
    val steps = (overflow + stride - 1) / stride
    val startIndex = (steps * stride).coerceAtMost(lastIndex)
    return subList(alignContextStart(startIndex), this.size)
}

private fun List<UIMessage>.alignContextStart(startIndex: Int): Int {
    var adjustedStartIndex = startIndex

    // 循环往前查找，直到满足所有依赖条件
    var needsAdjustment = true
    val visitedIndices = mutableSetOf<Int>()

    while (needsAdjustment && adjustedStartIndex > 0) {
        needsAdjustment = false

        // 防止无限循环
        if (adjustedStartIndex in visitedIndices) break
        visitedIndices.add(adjustedStartIndex)

        val currentMessage = this[adjustedStartIndex]

        // 如果当前消息包含已执行的tool（有output），往前查找对应的tool call
        if (currentMessage.getTools().any { it.isExecuted }) {
            for (i in adjustedStartIndex - 1 downTo 0) {
                if (this[i].getTools().any { !it.isExecuted }) {
                    adjustedStartIndex = i
                    needsAdjustment = true
                    break
                }
            }
        }

        // 如果当前消息包含未执行的tool call，往前查找对应的用户消息
        if (currentMessage.getTools().any { !it.isExecuted }) {
            for (i in adjustedStartIndex - 1 downTo 0) {
                if (this[i].role == MessageRole.USER) {
                    adjustedStartIndex = i
                    needsAdjustment = true
                    break
                }
            }
        }
    }

    // A context window starts at a complete user turn. This also keeps any assistant/tool
    // messages belonging to that turn together instead of exposing an orphaned response.
    if (this[adjustedStartIndex].role != MessageRole.USER) {
        adjustedStartIndex = (adjustedStartIndex - 1 downTo 0)
            .firstOrNull { this[it].role == MessageRole.USER }
            ?: 0
    }

    return adjustedStartIndex
}

@Serializable
sealed class ToolApprovalState {
    @Serializable
    @SerialName("auto")
    data object Auto : ToolApprovalState()

    @Serializable
    @SerialName("pending")
    data object Pending : ToolApprovalState()

    @Serializable
    @SerialName("approved")
    data object Approved : ToolApprovalState()

    @Serializable
    @SerialName("denied")
    data class Denied(val reason: String = "") : ToolApprovalState()

    @Serializable
    @SerialName("answered")
    data class Answered(val answer: String) : ToolApprovalState()
}

fun ToolApprovalState.canResumeToolExecution(): Boolean {
    return when (this) {
        ToolApprovalState.Approved -> true
        is ToolApprovalState.Denied -> true
        is ToolApprovalState.Answered -> true
        ToolApprovalState.Auto,
        ToolApprovalState.Pending,
            -> false
    }
}

@Serializable
sealed class UIMessagePart {
    abstract val metadata: JsonObject?

    @Serializable
    @SerialName("text")
    data class Text(
        val text: String,
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("image")
    data class Image(
        val url: String,
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("video")
    data class Video(
        val url: String,
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("audio")
    data class Audio(
        val url: String,
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("document")
    data class Document(
        val url: String,
        val fileName: String,
        val mime: String = "text/*",
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("reasoning")
    data class Reasoning(
        val reasoning: String,
        val createdAt: Instant = Clock.System.now(),
        val finishedAt: Instant? = Clock.System.now(),
        val source: ReasoningSource = ReasoningSource.PROVIDER_NATIVE,
        val malformed: Boolean = false,
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Deprecated("Deprecated")
    @Serializable
    @SerialName("search")
    data object Search : UIMessagePart() {
        override var metadata: JsonObject? = null
    }

    @Deprecated("Use UIMessagePart.Tool instead")
    @Serializable
    @SerialName("tool_call")
    data class ToolCall(
        val toolCallId: String,
        val toolName: String,
        val arguments: String,
        val approvalState: ToolApprovalState = ToolApprovalState.Auto,
        override var metadata: JsonObject? = null
    ) : UIMessagePart() {
        @Suppress("DEPRECATION")  // self-reference inside a deprecated class is unavoidable
        fun merge(other: ToolCall): ToolCall {
            return ToolCall(
                toolCallId = toolCallId,
                toolName = toolName + other.toolName,
                arguments = arguments + other.arguments,
                approvalState = approvalState,
                metadata = if (other.metadata != null) other.metadata else metadata,
            )
        }
    }

    @Deprecated("Use UIMessagePart.Tool instead")
    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        val toolCallId: String,
        val toolName: String,
        val content: JsonElement,
        val arguments: JsonElement,
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("tool")
    data class Tool(
        val toolCallId: String,
        val toolName: String,
        val input: String,
        val output: List<UIMessagePart> = emptyList(),
        val approvalState: ToolApprovalState = ToolApprovalState.Auto,
        /**
         * Unix-millisecond timestamp set by [GenerationHandler] right before it actually
         * starts running the tool's `execute` body. Persisted before execution begins so
         * that on a process kill mid-execute, the post-restart replay can detect that a
         * previous attempt started but didn't complete (output is empty + this is set)
         * and refuse to silently re-run the tool — re-running could double-charge a
         * remote, double-send a message, or duplicate any other side effect. Null means
         * "never started" (Approved-but-not-yet-tried).
         */
        val executionStartedAt: Long? = null,
        /**
         * The schema identity of this tool as the generation that raised the call froze it, when
         * the publisher supplied one.
         *
         * Carried on the part because the part is what the conversation authority commits a
         * pending approval against, and the authority cannot re-derive it: it holds the app's
         * *current* tool surface, which is a different fact from the frozen catalog the model was
         * shown. Null for every part from any other source, including all pre-existing messages —
         * those keep the authority's own lookup, and a value that is not a 64-character lowercase
         * hexadecimal digest is not treated as frozen identity.
         *
         * Never opened by a provider message converter: this is the app's own bookkeeping, and it
         * is not sent anywhere.
         */
        val toolSchemaFingerprint: String? = null,
        /**
         * Whether this part states the **whole** call rather than a fragment of it.
         *
         * ## Why the distinction has to be stated rather than inferred
         *
         * Most providers stream a tool call: the name arrives in pieces, then the arguments in
         * pieces, and [merge] exists to glue those pieces together. Claude P does the opposite. A
         * call arrives inside a live generation, and everything the app is told about it — the
         * pending card, the decision, the result — is a complete statement of that call at that
         * moment. Appending those to each other is what produced a card whose tool name appeared
         * once per publication and whose approval state was stuck at `Pending` while its output
         * already held the result.
         *
         * So a part that states a whole call is merged by **replacement**: the incoming statement
         * becomes the call's state, and fields it does not state are kept. Two parts of a call
         * never disagree about which call they are — [toolCallId] is what pairs them — so a
         * replacement cannot mix two calls together.
         *
         * ## Why not the fingerprint
         *
         * [toolSchemaFingerprint] is non-null on exactly the Claude P approval publications today,
         * so it would work as a marker. It is not used as one: that field is the identity of the
         * *tool schema*, consumed by the approval authority, and a second, unrelated meaning
         * inferred from its presence is the kind of implicit coupling whose failure looks like
         * success. A flag says what it means.
         *
         * ## Who sets it
         *
         * Exactly one site: the Claude P approval card `ClaudePToolStatusUpdate.asInterimToolPart`
         * maps. Once a call's card is in the conversation, that card is the part the later
         * statements for the same call — the decision, the result — are merged into, so marking the
         * card is enough to give the whole call replacement semantics. Every other provider leaves
         * it `false` and keeps the appending merge it has always had.
         */
        val isCallSnapshot: Boolean = false,
        override var metadata: JsonObject? = null
    ) : UIMessagePart() {
        /** Whether the tool has been executed (has output) */
        val isExecuted: Boolean get() = output.isNotEmpty()

        /** Whether the tool is pending user approval */
        val isPending: Boolean get() = approvalState is ToolApprovalState.Pending

        /** Whether generation can resume and handle this tool immediately */
        val canResumeExecution: Boolean get() = !isExecuted && approvalState.canResumeToolExecution()

        /**
         * True iff a previous execution attempt was interrupted: approvalState is Approved,
         * output is empty, and executionStartedAt is set. The resume path uses this to
         * synthesise a "we don't know whether the side effect happened" Denied envelope
         * instead of re-running.
         */
        val isInterruptedAttempt: Boolean
            get() = approvalState is ToolApprovalState.Approved &&
                output.isEmpty() && executionStartedAt != null

        /** Parse input string as JsonElement */
        fun inputAsJson(): JsonElement = runCatching {
            json.parseToJsonElement(input.ifBlank { "{}" })
        }.getOrElse { JsonObject(emptyMap()) }

        /**
         * Folds [other] into this part for the same call.
         *
         * Two shapes, and which one applies is stated by [isCallSnapshot] rather than guessed:
         *
         * - **Snapshots** (Claude P) *replace*: the incoming statement is the call's state, and a
         *   field it does not state keeps the value already held. A call that is published three
         *   times therefore has one name, one input and one output — and its decision, once made, is
         *   a decision.
         * - **Deltas** (everything else) *append*, exactly as before: a name that arrives in pieces
         *   is still glued together, because for those providers a later part really is a
         *   continuation of a string.
         */
        fun merge(other: Tool): Tool {
            if (isCallSnapshot || other.isCallSnapshot) {
                return Tool(
                    toolCallId = toolCallId,
                    // Stated-wins, unstated-keeps: a snapshot that carried no name must not erase
                    // the one the call was published under.
                    toolName = other.toolName.ifBlank { toolName },
                    // Blank means *unstated*, which is a real answer for a statement that does not
                    // restate the arguments — the decision above all. It is the sender's job to say
                    // so with an empty string: a sender that put the text `null` here would be
                    // making a claim about the arguments, and this line would dutifully write it
                    // over the ones the user actually approved. See
                    // `ClaudePToolStatusUpdate.asInterimToolPart`, which is that sender.
                    input = other.input.ifBlank { input },
                    // Empty output means "no result yet", which is a statement about *progress*
                    // rather than a result to write, so it never clears one that already exists.
                    output = other.output.ifEmpty { output },
                    // A decision is a fact about what the user did, and a repeated or re-delivered
                    // pending statement may not un-make it. Anything else is the incoming state.
                    approvalState = if (
                        other.approvalState is ToolApprovalState.Pending && approvalState.isDecided
                    ) {
                        approvalState
                    } else {
                        other.approvalState
                    },
                    // Both of these are written once, by whoever first knows them, and never
                    // restated: first non-null wins in the same direction as the appending path
                    // below, so a snapshot cannot drop the frozen schema identity the approval
                    // authority commits against.
                    executionStartedAt = executionStartedAt ?: other.executionStartedAt,
                    toolSchemaFingerprint = toolSchemaFingerprint ?: other.toolSchemaFingerprint,
                    metadata = if (other.metadata != null) other.metadata else metadata,
                    isCallSnapshot = true,
                )
            }
            return Tool(
                toolCallId = toolCallId,
                toolName = toolName + other.toolName,
                input = input + other.input,
                output = output + other.output,
                approvalState = approvalState,
                executionStartedAt = executionStartedAt ?: other.executionStartedAt,
                // First non-null wins, in the same direction as `executionStartedAt`: a published
                // card supplies it once, and every later delta for the same call — the approval,
                // the run, the result — carries no fingerprint of its own. Dropping it on merge
                // would leave the card that is actually committed without the identity it was
                // published under.
                toolSchemaFingerprint = toolSchemaFingerprint ?: other.toolSchemaFingerprint,
                metadata = if (other.metadata != null) other.metadata else metadata,
            )
        }
    }
}

/**
 * Whether a decision has been made about this call.
 *
 * `Auto` is not a decision — it means no approval was required — so only the three states a user
 * or an answer produced count. Used by [UIMessagePart.Tool.merge] to keep a re-stated `Pending`
 * from overwriting one.
 */
internal val ToolApprovalState.isDecided: Boolean
    get() = when (this) {
        is ToolApprovalState.Approved,
        is ToolApprovalState.Denied,
        is ToolApprovalState.Answered,
            -> true
        ToolApprovalState.Auto,
        ToolApprovalState.Pending,
            -> false
    }

/**
 * Sort message parts by type priority:
 * - Reasoning (-1): shown first
 * - Text, Tool, ToolCall, ToolResult, Search (0): middle
 * - Image, Video, Audio, Document (1): shown last
 *
 * WARNING: This function is intended for migration only.
 * Do not use for new messages as it may break the semantic order
 * when a message contains multiple Reasoning/Text parts.
 */
@Deprecated(
    message = "Only use for migration. May break semantic order for messages with multiple Reasoning/Text parts.",
    level = DeprecationLevel.WARNING
)
@Suppress("DEPRECATION")  // when must enumerate deprecated UIMessagePart variants for exhaustiveness
fun List<UIMessagePart>.toSortedMessageParts(): List<UIMessagePart> {
    // Skip sorting if multiple Reasoning or Text parts exist to preserve semantic order
    val reasoningCount = count { it is UIMessagePart.Reasoning }
    val textCount = count { it is UIMessagePart.Text }
    if (reasoningCount > 1 || textCount > 1) {
        return this
    }
    return sortedBy { part ->
        when (part) {
            is UIMessagePart.Reasoning -> -1
            is UIMessagePart.Text -> 0
            is UIMessagePart.Tool -> 0
            is UIMessagePart.ToolCall -> 0
            is UIMessagePart.ToolResult -> 0
            is UIMessagePart.Search -> 0
            is UIMessagePart.Image -> 1
            is UIMessagePart.Video -> 1
            is UIMessagePart.Audio -> 1
            is UIMessagePart.Document -> 1
        }
    }
}

fun UIMessage.finishReasoning(): UIMessage {
    return copy(
        parts = parts.map { part ->
            when (part) {
                is UIMessagePart.Reasoning -> {
                    if (part.finishedAt == null) {
                        part.copy(
                            finishedAt = Clock.System.now()
                        )
                    } else {
                        part
                    }
                }

                else -> part
            }
        }
    )
}

fun UIMessage.finishPendingTools(
    transform: (UIMessagePart.Tool) -> UIMessagePart.Tool
): UIMessage {
    val updatedParts = parts.map { part ->
        // Skip tools whose approvalState is ALREADY in a terminal state (Denied, Answered)
        // even though `!isExecuted` is true. Without this skip, a hardline-blocked tool
        // (Denied with empty output, set by GenerationHandler at hardline-check time)
        // gets its reason overwritten with "Generation cancelled by user" — losing the
        // safety-floor explanation. Approved+empty stays cancellable: it represents an
        // approval the user granted but the tool never finished executing, so `/stop`
        // should still flip it to Denied("cancelled by user").
        if (part is UIMessagePart.Tool && !part.isExecuted &&
            part.approvalState !is ToolApprovalState.Denied &&
            part.approvalState !is ToolApprovalState.Answered
        ) {
            transform(part)
        } else {
            part
        }
    }

    if (updatedParts == parts) {
        return this
    }

    return copy(
        parts = updatedParts,
        finishedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    ).finishReasoning()
}

/**
 * Migrate legacy ToolCall parts to new Tool type within a single message.
 * This converts ToolCall parts to Tool parts with empty output.
 */
@Suppress("DEPRECATION")
private fun UIMessage.migrateToolParts(): UIMessage {
    val toolCalls = parts.filterIsInstance<UIMessagePart.ToolCall>()
    if (toolCalls.isEmpty()) {
        // Even if no ToolCall migration needed, ensure parts are sorted
        val sortedParts = parts.toSortedMessageParts()
        return if (sortedParts != parts) copy(parts = sortedParts) else this
    }

    val migratedParts = parts.map { part ->
        if (part is UIMessagePart.ToolCall) {
            UIMessagePart.Tool(
                toolCallId = part.toolCallId,
                toolName = part.toolName,
                input = part.arguments,
                output = emptyList(),
                approvalState = part.approvalState,
                metadata = part.metadata
            )
        } else {
            part
        }
    }
    return copy(parts = migratedParts.toSortedMessageParts())
}

/**
 * Migrate TOOL role messages into previous ASSISTANT messages by
 * merging ToolResult parts into corresponding Tool parts.
 * Returns the migrated list with TOOL messages removed.
 */
@Suppress("DEPRECATION")
fun List<UIMessage>.migrateToolMessages(): List<UIMessage> {
    val result = mutableListOf<UIMessage>()
    var i = 0

    while (i < size) {
        val message = this[i]

        // If this is a TOOL role message, merge its results into previous ASSISTANT message
        if (message.role == MessageRole.TOOL) {
            val toolResults = message.parts.filterIsInstance<UIMessagePart.ToolResult>()
            if (result.isNotEmpty() && result.last().role == MessageRole.ASSISTANT) {
                // Find the last ASSISTANT message and update its Tool parts with results
                val lastAssistant = result.removeAt(result.lastIndex)
                val updatedParts = lastAssistant.parts.map { part ->
                    if (part is UIMessagePart.Tool && !part.isExecuted) {
                        val matchingResult = toolResults.find { result -> result.toolCallId == part.toolCallId }
                        if (matchingResult != null) {
                            part.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(matchingResult.content)
                                    )
                                )
                            )
                        } else {
                            part
                        }
                    } else if (part is UIMessagePart.ToolCall) {
                        // Also handle legacy ToolCall parts
                        val matchingResult = toolResults.find { result -> result.toolCallId == part.toolCallId }
                        if (matchingResult != null) {
                            UIMessagePart.Tool(
                                toolCallId = part.toolCallId,
                                toolName = part.toolName,
                                input = part.arguments,
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(matchingResult.content)
                                    )
                                ),
                                approvalState = part.approvalState,
                                metadata = part.metadata
                            )
                        } else {
                            UIMessagePart.Tool(
                                toolCallId = part.toolCallId,
                                toolName = part.toolName,
                                input = part.arguments,
                                output = emptyList(),
                                approvalState = part.approvalState,
                                metadata = part.metadata
                            )
                        }
                    } else {
                        part
                    }
                }
                result.add(lastAssistant.copy(parts = updatedParts.toSortedMessageParts()))
            }
            // Skip the TOOL message (don't add it to result)
            i++
            continue
        }

        // For other messages, migrate their tool parts first
        result.add(message.migrateToolParts())
        i++
    }

    return result
}

/**
 * Migrate legacy TOOL role messages at the MessageNode level.
 * This handles the case where TOOL messages are stored in separate MessageNodes
 * by merging ToolResult parts into the previous ASSISTANT node's Tool parts.
 *
 * @param MessageNode A container holding one or more UIMessages for branching.
 * @return Migrated list with TOOL nodes removed and their results merged into ASSISTANT nodes.
 */
@Suppress("DEPRECATION")
fun <T> List<T>.migrateToolNodes(
    getMessages: (T) -> List<UIMessage>,
    setMessages: (T, List<UIMessage>) -> T
): List<T> {
    val result = mutableListOf<T>()
    var i = 0

    while (i < size) {
        val node = this[i]
        val messages = getMessages(node)

        // Check if this node contains TOOL role messages
        val isToolNode = messages.any { it.role == MessageRole.TOOL }

        if (isToolNode && result.isNotEmpty()) {
            // Find the previous ASSISTANT node
            val lastIndex = result.lastIndex
            val lastNode = result[lastIndex]
            val lastMessages = getMessages(lastNode)
            val isAssistantNode = lastMessages.any { it.role == MessageRole.ASSISTANT }

            if (isAssistantNode) {
                // Collect all ToolResults from the TOOL node
                val toolResults = messages.flatMap { msg ->
                    msg.parts.filterIsInstance<UIMessagePart.ToolResult>()
                }

                // Update the ASSISTANT node's messages by merging ToolResults
                val updatedMessages = lastMessages.map { assistantMsg ->
                    if (assistantMsg.role != MessageRole.ASSISTANT) return@map assistantMsg

                    val updatedParts = assistantMsg.parts.map { part ->
                        when (part) {
                            is UIMessagePart.Tool -> {
                                if (!part.isExecuted) {
                                    val matchingResult = toolResults.find { it.toolCallId == part.toolCallId }
                                    if (matchingResult != null) {
                                        part.copy(
                                            output = listOf(
                                                UIMessagePart.Text(
                                                    json.encodeToString(matchingResult.content)
                                                )
                                            )
                                        )
                                    } else part
                                } else part
                            }

                            is UIMessagePart.ToolCall -> {
                                val matchingResult = toolResults.find { it.toolCallId == part.toolCallId }
                                if (matchingResult != null) {
                                    UIMessagePart.Tool(
                                        toolCallId = part.toolCallId,
                                        toolName = part.toolName,
                                        input = part.arguments,
                                        output = listOf(
                                            UIMessagePart.Text(
                                                json.encodeToString(matchingResult.content)
                                            )
                                        ),
                                        approvalState = part.approvalState,
                                        metadata = part.metadata
                                    )
                                } else {
                                    UIMessagePart.Tool(
                                        toolCallId = part.toolCallId,
                                        toolName = part.toolName,
                                        input = part.arguments,
                                        output = emptyList(),
                                        approvalState = part.approvalState,
                                        metadata = part.metadata
                                    )
                                }
                            }

                            else -> part
                        }
                    }
                    assistantMsg.copy(parts = updatedParts.toSortedMessageParts())
                }

                result[lastIndex] = setMessages(lastNode, updatedMessages)
                // Skip the TOOL node (don't add it to result)
                i++
                continue
            }
        }

        // For non-TOOL nodes, migrate their internal tool parts
        val migratedMessages = messages.migrateToolMessages()
        result.add(setMessages(node, migratedMessages))
        i++
    }

    return result
}

@Serializable
sealed class UIMessageAnnotation {
    @Serializable
    @SerialName("url_citation")
    data class UrlCitation(
        val title: String,
        val url: String
    ) : UIMessageAnnotation()

    @Serializable
    @SerialName("steering")
    data class Steering(
        val commandId: String,
        val persistent: Boolean,
    ) : UIMessageAnnotation()

    /** Audit identity for a user message submitted by a privileged conversation. */
    @Serializable
    @SerialName("second_user")
    data class SecondUser(
        val sourceAssistantId: Uuid,
        val sourceConversationId: Uuid,
        val displayName: String,
    ) : UIMessageAnnotation()

    /**
     * Marks provider-visible history produced by the explicit manual-compression command.
     * The marker is internal and is not rendered in chat; it keeps the resulting summary and
     * retained tail as one stable prefix instead of applying a per-turn sliding message window.
     */
    @Serializable
    @SerialName("manual_compression_summary")
    data class ManualCompressionSummary(
        val batchIndex: Int,
        val batchCount: Int,
    ) : UIMessageAnnotation()

    /** Internal correlation marker for a QuickCapture user message and its generated answer. */
    @Serializable
    @SerialName("quick_capture")
    data class QuickCapture(
        val commandId: String,
        val captureSessionId: String,
    ) : UIMessageAnnotation()

    /** Internal correlation marker for a pet handoff and the answer generated for it. */
    @Serializable
    @SerialName("pet_handoff")
    data class PetHandoff(
        val commandId: String,
        val requestId: String,
    ) : UIMessageAnnotation()

    @Serializable
    @SerialName("final_answer_recovery")
    data class FinalAnswerRecovery(
        val commandId: String,
        val reason: String,
        val status: FinalAnswerRecoveryStatus,
        val attempt: Int = 1,
    ) : UIMessageAnnotation()
}

@Serializable
data class MessageChunk(
    val id: String,
    val model: String,
    val choices: List<UIMessageChoice>,
    val usage: TokenUsage? = null,
    val terminal: GenerationTerminal? = null,
    /**
     * The approval-continuation the app declared for a pending card this chunk carries, as a token.
     *
     * ## Why this rides on the chunk
     *
     * A Claude P tool call that needs approval is published as a tool part *inside the provider's
     * own stream*, and the turn that raised it has not ended — so the barrier the conversation
     * authority has to write is not the ordinary one. The only thing that distinguishes the two is
     * what the app declared when it raised the card
     * ([me.rerere.ai.provider.claudep.ClaudePToolStatusUpdate.pendingContinuation]), and the only
     * place that declaration is still attached to the part is here.
     *
     * Carrying it rather than re-deriving it downstream is deliberate: a later reader could infer
     * "this came from a stream, so it must be in-flight" or "the provider is Claude P, so it must
     * be in-flight", and both are the same mistake — a rule that is right today and silently wrong
     * for the next provider. The token is opaque in this module; the app maps it by exact match and
     * refuses what it does not recognise.
     *
     * [Transient] so "this never enters a serialized form" is a property of the declaration rather
     * than a rule someone has to remember. It is `null` for every chunk that carries no pending
     * card, which is nearly all of them.
     */
    @Transient
    val pendingApprovalContinuation: String? = null,
    /**
     * The Server's generation id, on the chunks of a `deferred` Claude P generation.
     *
     * ## Why the id has to ride here
     *
     * A `deferred` generation creates the branch it belongs to, so the app can only bind that
     * branch *after* it commits the new variant — and §6.1 requires the commit and the
     * `BIND_PENDING` record to be one transaction, which means the app must already hold the
     * generation id when that transaction runs. The transaction happens after this stream ends,
     * so this is the last moment the id can be handed over. There is no request/response channel
     * between the provider and the conversation layer for it to travel on instead.
     *
     * [Transient] for the same reason as the field above: it is correlation, not content, and it
     * must never be persisted or serialized. A generation that carries no bind obligation does
     * not stamp it at all.
     */
    @Transient
    val claudePGenerationId: String? = null,
) {
    fun resolvedTerminal(): GenerationTerminal? {
        terminal?.let { return it }
        val reason = choices.firstNotNullOfOrNull { it.finishReason }
            ?.takeUnless { it.equals("unknown", ignoreCase = true) }
            ?: return null
        return GenerationTerminal.fromProviderReason(reason)
    }
}

@Serializable
data class UIMessageChoice(
    val index: Int,
    val delta: UIMessage?,
    val message: UIMessage?,
    val finishReason: String?
)
