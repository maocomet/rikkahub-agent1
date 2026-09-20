package me.rerere.ai.provider.claudep

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

// ---------------------------------------------------------------------------------------------
// Handshake
// ---------------------------------------------------------------------------------------------

/** `client.hello` — the client proves possession of its device key and offers protocol versions. */
@Serializable
data class ClaudePClientHelloBody(
    @SerialName("app_version") val appVersion: String,
    @SerialName("protocol_versions") val protocolVersions: List<String> = listOf("v1"),
    @SerialName("device_id") val deviceId: String,
    val nonce: String,
    val signature: String,
    val capabilities: List<String> = emptyList(),
) {
    override fun toString(): String =
        "ClaudePClientHelloBody(appVersion=$appVersion, protocolVersions=$protocolVersions, " +
            "deviceId=${deviceId.redactedRef()}, nonce=<redacted>, signature=<redacted>, " +
            "capabilities=$capabilities)"
}

/** `server.hello` — freezes everything the client is allowed to do on this connection. */
@Serializable
data class ClaudePServerHelloBody(
    @SerialName("protocol_version") val protocolVersion: String,
    @SerialName("gateway_build") val gatewayBuild: String = "",
    @SerialName("worker_abi") val workerAbi: String = "",
    @SerialName("claude_code_version") val claudeCodeVersion: String = "",
    @SerialName("max_frame_bytes") val maxFrameBytes: Int = 0,
    @SerialName("max_prompt_bytes") val maxPromptBytes: Int = 0,
    @SerialName("max_concurrent_generations") val maxConcurrentGenerations: Int = 0,
    val features: List<String> = emptyList(),
    @SerialName("server_time") val serverTime: String = "",
    @SerialName("connection_id") val connectionId: String = "",
) {
    override fun toString(): String =
        "ClaudePServerHelloBody(protocolVersion=$protocolVersion, gatewayBuild=$gatewayBuild, " +
            "workerAbi=$workerAbi, claudeCodeVersion=$claudeCodeVersion, " +
            "maxFrameBytes=$maxFrameBytes, maxPromptBytes=$maxPromptBytes, " +
            "maxConcurrentGenerations=$maxConcurrentGenerations, features=$features, " +
            "connectionId=${connectionId.redactedRef()})"
}

// ---------------------------------------------------------------------------------------------
// Model catalog
// ---------------------------------------------------------------------------------------------

/** One server-allowlisted model alias. Never a Claude Code CLI version string. */
@Serializable
data class ClaudePModelEntry(
    val alias: String = "",
    @SerialName("display_name") val displayName: String = "",
    val input: List<String> = emptyList(),
    val features: List<String> = emptyList(),
    val enabled: Boolean = false,
)

/** `catalog.result`. Contains aliases only — the call behind it never invokes a model. */
@Serializable
data class ClaudePCatalogResultBody(
    val models: List<ClaudePModelEntry> = emptyList(),
)

// ---------------------------------------------------------------------------------------------
// Generation start
// ---------------------------------------------------------------------------------------------

@Serializable
data class ClaudePTurnPart(
    val type: String = "text",
    val text: String = "",
) {
    override fun toString(): String =
        "ClaudePTurnPart(type=$type, text=<redacted:${text.length} chars>)"
}

@Serializable
data class ClaudePTurn(
    val role: String,
    val parts: List<ClaudePTurnPart> = emptyList(),
) {
    override fun toString(): String =
        "ClaudePTurn(role=$role, parts=<redacted:${parts.size} parts>)"
}

@Serializable
data class ClaudePGenerationLimits(
    @SerialName("max_output_tokens") val maxOutputTokens: Int? = null,
)

/**
 * `generation.start`. Everything semantic about the request lives here, which is exactly why
 * `toString` redacts the prompt, the turn and the tool snapshot: this object is the most likely
 * thing in the codebase to be accidentally logged.
 */
@Serializable
data class ClaudePGenerationStartBody(
    @SerialName("remote_thread_id") val remoteThreadId: String,
    @SerialName("remote_branch_id") val remoteBranchId: String,
    val mode: String,
    @SerialName("model_alias") val modelAlias: String,
    @SerialName("system_prompt") val systemPrompt: String? = null,
    val turn: ClaudePTurn,
    @SerialName("rebuild_history") val rebuildHistory: List<ClaudePTurn>? = null,
    @SerialName("tool_snapshot") val toolSnapshot: JsonElement? = null,
    val limits: ClaudePGenerationLimits? = null,
) {
    override fun toString(): String =
        "ClaudePGenerationStartBody(thread=${remoteThreadId.redactedRef()}, " +
            "branch=${remoteBranchId.redactedRef()}, mode=$mode, modelAlias=$modelAlias, " +
            "systemPrompt=<redacted:${systemPrompt?.length ?: 0} chars>, " +
            "turn=<redacted:${turn.parts.size} parts>, " +
            "rebuildHistory=<redacted:${rebuildHistory?.size ?: 0} turns>, " +
            "toolSnapshot=<redacted:${if (toolSnapshot == null) 0 else 1}>, limits=$limits)"
}

/** `generation.cancel`. Idempotent on the gateway side; the client still sends it at most once. */
@Serializable
data class ClaudePGenerationCancelBody(
    @SerialName("generation_id") val generationId: String,
    val reason: String = ClaudePCancelReason.USER_REQUESTED.wireValue,
) {
    override fun toString(): String =
        "ClaudePGenerationCancelBody(generationId=${generationId.redactedRef()}, reason=$reason)"
}

enum class ClaudePCancelReason(val wireValue: String) {
    USER_REQUESTED("user_requested"),
    APP_SHUTDOWN("app_shutdown"),
    CONNECTION_LOST("connection_lost"),
}

/** `stream.resume` — reconnect and replay, never re-dispatch. */
@Serializable
data class ClaudePStreamResumeBody(
    @SerialName("generation_id") val generationId: String,
    @SerialName("last_event_seq") val lastEventSeq: Long,
) {
    override fun toString(): String =
        "ClaudePStreamResumeBody(generationId=${generationId.redactedRef()}, " +
            "lastEventSeq=$lastEventSeq)"
}

/** `receipt.query`. */
@Serializable
data class ClaudePReceiptQueryBody(
    @SerialName("generation_id") val generationId: String,
) {
    override fun toString(): String =
        "ClaudePReceiptQueryBody(generationId=${generationId.redactedRef()})"
}

// ---------------------------------------------------------------------------------------------
// Server lifecycle / content events
// ---------------------------------------------------------------------------------------------

@Serializable
data class ClaudePGenerationAcceptedBody(
    @SerialName("generation_id") val generationId: String = "",
    @SerialName("request_id") val requestId: String = "",
    @SerialName("accepted_seq") val acceptedSeq: Long = 0,
) {
    override fun toString(): String =
        "ClaudePGenerationAcceptedBody(generationId=${generationId.redactedRef()}, " +
            "requestId=${requestId.redactedRef()}, acceptedSeq=$acceptedSeq)"
}

@Serializable
data class ClaudePGenerationStartedBody(
    @SerialName("generation_id") val generationId: String = "",
    @SerialName("claude_code_version") val claudeCodeVersion: String = "",
    @SerialName("model_alias") val modelAlias: String = "",
)

@Serializable
data class ClaudePMessageStartedBody(
    @SerialName("message_id") val messageId: String = "",
    val role: String = "assistant",
    val index: Int = 0,
) {
    override fun toString(): String =
        "ClaudePMessageStartedBody(messageId=${messageId.redactedRef()}, role=$role, index=$index)"
}

/** `text.delta`. Holds model output — redacted in `toString`. */
@Serializable
data class ClaudePTextDeltaBody(
    @SerialName("message_id") val messageId: String? = null,
    val index: Int = 0,
    val text: String = "",
) {
    override fun toString(): String =
        "ClaudePTextDeltaBody(messageId=${messageId.redactedRef()}, index=$index, " +
            "text=<redacted:${text.length} chars>)"
}

/** `reasoning.delta`. A safe summary only; redacted in `toString`. */
@Serializable
data class ClaudePReasoningDeltaBody(
    @SerialName("message_id") val messageId: String? = null,
    val index: Int = 0,
    val summary: String = "",
) {
    override fun toString(): String =
        "ClaudePReasoningDeltaBody(messageId=${messageId.redactedRef()}, index=$index, " +
            "summary=<redacted:${summary.length} chars>)"
}

/** Reported counters only — never prompt or completion text. */
@Serializable
data class ClaudePUsageBody(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("cached_tokens") val cachedTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
)

@Serializable
data class ClaudePCompletedBody(
    @SerialName("stop_reason") val stopReason: String = "completed",
    val usage: ClaudePUsageBody? = null,
    @SerialName("reasoning_chars") val reasoningChars: Int = 0,
    @SerialName("answer_chars") val answerChars: Int = 0,
) {
    val safeStopReason: ClaudePStopReason
        get() = ClaudePStopReason.fromWire(stopReason)

    override fun toString(): String =
        "ClaudePCompletedBody(stopReason=${safeStopReason.wireValue}, usage=$usage, " +
            "reasoningChars=$reasoningChars, answerChars=$answerChars)"
}

@Serializable
data class ClaudePCancelledBody(
    val reason: String = ClaudePCancelReason.USER_REQUESTED.wireValue,
    val usage: ClaudePUsageBody? = null,
)

/**
 * `generation.failed`. Only the versioned enum is retained: an unrecognised code collapses to
 * [ClaudePErrorCode.EXTERNAL_RUNTIME_ERROR], and any human-readable `message` a gateway might send
 * is dropped by `ignoreUnknownKeys` rather than displayed.
 */
@Serializable
data class ClaudePFailedBody(
    @SerialName("error_code") val errorCode: String = "",
    val usage: ClaudePUsageBody? = null,
) {
    val safeCode: ClaudePErrorCode
        get() = ClaudePErrorCode.fromWire(errorCode)

    override fun toString(): String =
        "ClaudePFailedBody(code=${safeCode.name}, usage=$usage)"
}

/** Safe terminal summary returned by a receipt. Counts and enums only — no content. */
@Serializable
data class ClaudePReceiptBody(
    @SerialName("generation_id") val generationId: String = "",
    val state: String = "",
    @SerialName("error_code") val errorCode: String? = null,
    @SerialName("last_event_seq") val lastEventSeq: Long = 0,
    val usage: ClaudePUsageBody? = null,
    @SerialName("reasoning_chars") val reasoningChars: Int = 0,
    @SerialName("answer_chars") val answerChars: Int = 0,
) {
    val safeState: ClaudePGenerationState
        get() = ClaudePGenerationState.fromWire(state)

    val safeErrorCode: ClaudePErrorCode?
        get() = errorCode?.let { ClaudePErrorCode.fromWire(it) }

    override fun toString(): String =
        "ClaudePReceiptBody(generationId=${generationId.redactedRef()}, " +
            "state=${safeState.wireValue}, error=${safeErrorCode?.name}, " +
            "lastEventSeq=$lastEventSeq, reasoningChars=$reasoningChars, " +
            "answerChars=$answerChars)"
}

/** Outcome vocabulary for `stream.resume.result`. */
enum class ClaudePResumeKind(val wireValue: String) {
    /** Buffered events follow and will be replayed. */
    REPLAYED("replayed"),

    /** The event buffer expired, but a terminal receipt exists. */
    TERMINAL("terminal"),

    /** The generation is still running; a live stream resumes. */
    ACTIVE("active"),

    /** The gateway cannot prove a terminal. The user decides; never auto-retry. */
    STATE_UNKNOWN("state_unknown");

    companion object {
        fun fromWire(raw: String?): ClaudePResumeKind = when (raw?.trim()) {
            "replayed" -> REPLAYED
            "terminal" -> TERMINAL
            "active" -> ACTIVE
            else -> STATE_UNKNOWN
        }
    }
}

@Serializable
data class ClaudePStreamResumeResultBody(
    @SerialName("generation_id") val generationId: String = "",
    val outcome: String = "",
    @SerialName("last_event_seq") val lastEventSeq: Long = 0,
    val state: String = "",
    @SerialName("error_code") val errorCode: String? = null,
    val usage: ClaudePUsageBody? = null,
) {
    val safeOutcome: ClaudePResumeKind
        get() = ClaudePResumeKind.fromWire(outcome)

    val safeState: ClaudePGenerationState
        get() = ClaudePGenerationState.fromWire(state)

    override fun toString(): String =
        "ClaudePStreamResumeResultBody(generationId=${generationId.redactedRef()}, " +
            "outcome=${safeOutcome.wireValue}, state=${safeState.wireValue}, " +
            "lastEventSeq=$lastEventSeq)"
}

// ---------------------------------------------------------------------------------------------
// Typed server events
// ---------------------------------------------------------------------------------------------

/**
 * A routed server event.
 *
 * This sealed type is what the provider maps onto `MessageChunk`, so anything absent here can
 * never become visible text or a terminal — the compiler enforces the boundary that the protocol
 * doc describes.
 */
sealed interface ClaudePServerEvent {
    val envelope: ClaudePEnvelope

    /** Monotonic server-side ordering. Absent sequence is treated as 0. */
    val eventSeq: Long
        get() = envelope.sequence ?: 0L

    data class ServerHello(
        override val envelope: ClaudePEnvelope,
        val body: ClaudePServerHelloBody,
    ) : ClaudePServerEvent

    data class CatalogResult(
        override val envelope: ClaudePEnvelope,
        val body: ClaudePCatalogResultBody,
    ) : ClaudePServerEvent

    data class GenerationAccepted(
        override val envelope: ClaudePEnvelope,
        val body: ClaudePGenerationAcceptedBody,
    ) : ClaudePServerEvent

    data class GenerationStarted(
        override val envelope: ClaudePEnvelope,
        val body: ClaudePGenerationStartedBody,
    ) : ClaudePServerEvent

    data class MessageStarted(
        override val envelope: ClaudePEnvelope,
        val body: ClaudePMessageStartedBody,
    ) : ClaudePServerEvent

    data class ReasoningDelta(
        override val envelope: ClaudePEnvelope,
        val body: ClaudePReasoningDeltaBody,
    ) : ClaudePServerEvent

    data class TextDelta(
        override val envelope: ClaudePEnvelope,
        val body: ClaudePTextDeltaBody,
    ) : ClaudePServerEvent

    data class UsageUpdated(
        override val envelope: ClaudePEnvelope,
        val body: ClaudePUsageBody,
    ) : ClaudePServerEvent

    data class Completed(
        override val envelope: ClaudePEnvelope,
        val body: ClaudePCompletedBody,
    ) : ClaudePServerEvent

    data class Cancelled(
        override val envelope: ClaudePEnvelope,
        val body: ClaudePCancelledBody,
    ) : ClaudePServerEvent

    data class Failed(
        override val envelope: ClaudePEnvelope,
        val body: ClaudePFailedBody,
    ) : ClaudePServerEvent

    data class ReceiptResult(
        override val envelope: ClaudePEnvelope,
        val body: ClaudePReceiptBody,
    ) : ClaudePServerEvent

    data class StreamResumeResult(
        override val envelope: ClaudePEnvelope,
        val body: ClaudePStreamResumeResultBody,
    ) : ClaudePServerEvent

    /** True only for the three terminals. Used by the provider to drive [ClaudePTerminalGate]. */
    val terminalKind: ClaudePTerminalKind?
        get() = when (this) {
            is Completed -> ClaudePTerminalKind.COMPLETED
            is Cancelled -> ClaudePTerminalKind.CANCELLED
            is Failed -> ClaudePTerminalKind.FAILED
            else -> null
        }

    /** True for events that carry model output and are therefore illegal after a terminal. */
    val isContentDelta: Boolean
        get() = this is ReasoningDelta || this is TextDelta || this is UsageUpdated

    companion object {
        /**
         * Decodes a body for an already-vetted [type].
         *
         * Returns `null` when the body does not match the versioned schema; the caller turns that
         * into [ClaudePParseRejection.MALFORMED_EVENT_BODY] rather than guessing.
         */
        internal fun fromEnvelope(type: String, envelope: ClaudePEnvelope): ClaudePServerEvent? {
            val body = envelope.body
            return when (type) {
                ClaudePEventType.SERVER_HELLO -> ServerHello(
                    envelope,
                    ClaudePProtocol.json.decodeFromJsonElement(body),
                )

                ClaudePEventType.CATALOG_RESULT -> CatalogResult(
                    envelope,
                    ClaudePProtocol.json.decodeFromJsonElement(body),
                )

                ClaudePEventType.GENERATION_ACCEPTED -> GenerationAccepted(
                    envelope,
                    ClaudePProtocol.json.decodeFromJsonElement(body),
                )

                ClaudePEventType.GENERATION_STARTED -> GenerationStarted(
                    envelope,
                    ClaudePProtocol.json.decodeFromJsonElement(body),
                )

                ClaudePEventType.MESSAGE_STARTED -> MessageStarted(
                    envelope,
                    ClaudePProtocol.json.decodeFromJsonElement(body),
                )

                ClaudePEventType.REASONING_DELTA -> ReasoningDelta(
                    envelope,
                    ClaudePProtocol.json.decodeFromJsonElement(body),
                )

                ClaudePEventType.TEXT_DELTA -> TextDelta(
                    envelope,
                    ClaudePProtocol.json.decodeFromJsonElement(body),
                )

                ClaudePEventType.USAGE_UPDATED -> UsageUpdated(
                    envelope,
                    ClaudePProtocol.json.decodeFromJsonElement(body),
                )

                ClaudePEventType.GENERATION_COMPLETED -> Completed(
                    envelope,
                    ClaudePProtocol.json.decodeFromJsonElement(body),
                )

                ClaudePEventType.GENERATION_CANCELLED -> Cancelled(
                    envelope,
                    ClaudePProtocol.json.decodeFromJsonElement(body),
                )

                ClaudePEventType.GENERATION_FAILED -> Failed(
                    envelope,
                    ClaudePProtocol.json.decodeFromJsonElement(body),
                )

                ClaudePEventType.RECEIPT_RESULT -> ReceiptResult(
                    envelope,
                    ClaudePProtocol.json.decodeFromJsonElement(body),
                )

                ClaudePEventType.STREAM_RESUME_RESULT -> StreamResumeResult(
                    envelope,
                    ClaudePProtocol.json.decodeFromJsonElement(body),
                )

                else -> null
            }
        }
    }
}
