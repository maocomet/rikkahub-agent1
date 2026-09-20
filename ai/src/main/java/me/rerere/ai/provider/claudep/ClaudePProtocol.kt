package me.rerere.ai.provider.claudep

import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Claude P wire protocol, version 1.
 *
 * This file is the Android-side contract for `claudep/02-wire-protocol-v1.md`. It is deliberately
 * transport-free: CP1-A ships a deterministic fake gateway only, so nothing here performs DNS,
 * HTTP, WebSocket or process work.
 *
 * Two rules dominate every decision below:
 *
 * 1. **Unknown *optional events* are tolerated; an unknown *protocol major* is not.** A newer
 *    gateway may add event types we have never seen, and dropping them is correct. A different
 *    major version may have changed the meaning of a type we *do* recognise, so parsing must stop
 *    rather than guess.
 * 2. **No payload text escapes into logs or `toString`.** Prompts, model output, reasoning, tool
 *    arguments and credentials all live in fields whose `toString` is redacted to a length or a
 *    short digest. Rejections carry an enum, never the offending bytes.
 */
object ClaudePProtocol {
    /** Exact wire identifier carried in every envelope's `protocol` field. */
    const val PROTOCOL_ID: String = "rikkahub.claude-p.v1"

    /** WebSocket subprotocol negotiated for the WSS transport (CP1-B). */
    const val SUBPROTOCOL: String = "rikkahub.claude-p.v1"

    /** The only major version this build understands. */
    const val MAJOR_VERSION: Int = 1

    /**
     * Lenient about fields (a newer minor may add any), strict about the envelope contract itself.
     * `explicitNulls = false` keeps encoded client frames free of `null` placeholders.
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val PROTOCOL_PATTERN = Regex("^([a-z0-9][a-z0-9.-]*)\\.v(\\d+)$")

    /**
     * Extracts the major version from a protocol identifier such as `rikkahub.claude-p.v1`.
     *
     * Returns `null` for anything that is not a well-formed versioned identifier, which callers
     * must treat exactly like a major mismatch: fail closed, never "assume v1".
     */
    fun majorVersionOf(protocolId: String?): Int? {
        val match = PROTOCOL_PATTERN.matchEntire(protocolId?.trim().orEmpty()) ?: return null
        return match.groupValues[2].toIntOrNull()
    }

    /** True when [protocolId] names this exact protocol family *and* the major version we speak. */
    fun isCompatible(protocolId: String?): Boolean {
        val match = PROTOCOL_PATTERN.matchEntire(protocolId?.trim().orEmpty()) ?: return false
        return match.groupValues[1] == PROTOCOL_FAMILY && match.groupValues[2].toIntOrNull() == MAJOR_VERSION
    }

    private const val PROTOCOL_FAMILY = "rikkahub.claude-p"

    /**
     * Validates the version chosen in `server.hello`.
     *
     * Accepts both the bare `v1` form and the fully qualified `rikkahub.claude-p.v1`, because the
     * handshake doc names the field without fixing its spelling. Anything else — including a
     * missing or unparseable value — is treated as incompatible rather than assumed to be v1.
     */
    fun acceptsServerProtocolVersion(raw: String?): Boolean {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith("v")) return trimmed.substring(1).toIntOrNull() == MAJOR_VERSION
        return isCompatible(trimmed)
    }

    // ---------------------------------------------------------------------------------------
    // Server -> client event types understood by this build.
    // ---------------------------------------------------------------------------------------

    /**
     * Types this build can *route*. Anything else is an unknown optional event and is ignored —
     * including Phase 3 `tool.*` events, which must never be mistaken for text or a terminal.
     */
    val KNOWN_SERVER_EVENT_TYPES: Set<String> = setOf(
        ClaudePEventType.SERVER_HELLO,
        ClaudePEventType.CATALOG_RESULT,
        ClaudePEventType.GENERATION_ACCEPTED,
        ClaudePEventType.GENERATION_STARTED,
        ClaudePEventType.MESSAGE_STARTED,
        ClaudePEventType.REASONING_DELTA,
        ClaudePEventType.TEXT_DELTA,
        ClaudePEventType.USAGE_UPDATED,
        ClaudePEventType.GENERATION_COMPLETED,
        ClaudePEventType.GENERATION_CANCELLED,
        ClaudePEventType.GENERATION_FAILED,
        ClaudePEventType.RECEIPT_RESULT,
        ClaudePEventType.STREAM_RESUME_RESULT,
    )

    /**
     * Parses one inbound server frame.
     *
     * Ordering is load-bearing: the protocol major is checked **before** the event type, so a frame
     * whose type we happen to recognise is still rejected when it arrives under a different major.
     */
    fun parseInbound(raw: String): ClaudePInbound {
        val envelope = try {
            json.decodeFromString<ClaudePEnvelope>(raw)
        } catch (_: Exception) {
            // The exception message can quote the offending JSON, so it is never surfaced.
            return ClaudePInbound.Rejected(ClaudePParseRejection.MALFORMED_FRAME)
        }

        val protocolId = envelope.protocol.trim()
        if (protocolId.isEmpty()) {
            return ClaudePInbound.Rejected(ClaudePParseRejection.MISSING_PROTOCOL)
        }
        val major = majorVersionOf(protocolId)
            ?: return ClaudePInbound.Rejected(ClaudePParseRejection.MALFORMED_PROTOCOL)
        if (major != MAJOR_VERSION) {
            return ClaudePInbound.Rejected(ClaudePParseRejection.PROTOCOL_MAJOR_MISMATCH)
        }

        val type = envelope.type.trim()
        if (type.isEmpty()) {
            return ClaudePInbound.Rejected(ClaudePParseRejection.MISSING_TYPE)
        }
        if (type !in KNOWN_SERVER_EVENT_TYPES) {
            return ClaudePInbound.IgnoredUnknownEvent
        }

        val event = try {
            ClaudePServerEvent.fromEnvelope(type, envelope)
        } catch (_: Exception) {
            return ClaudePInbound.Rejected(ClaudePParseRejection.MALFORMED_EVENT_BODY)
        } ?: return ClaudePInbound.Rejected(ClaudePParseRejection.MALFORMED_EVENT_BODY)

        return ClaudePInbound.Event(event)
    }
}

/** Canonical event-type strings. Shared so client and server frames cannot drift apart. */
object ClaudePEventType {
    // client -> server
    const val CLIENT_HELLO = "client.hello"
    const val CATALOG_GET = "catalog.get"
    const val GENERATION_START = "generation.start"
    const val GENERATION_CANCEL = "generation.cancel"
    const val STREAM_RESUME = "stream.resume"
    const val RECEIPT_QUERY = "receipt.query"

    // server -> client
    const val SERVER_HELLO = "server.hello"
    const val CATALOG_RESULT = "catalog.result"
    const val GENERATION_ACCEPTED = "generation.accepted"
    const val GENERATION_STARTED = "generation.started"
    const val MESSAGE_STARTED = "message.started"
    const val REASONING_DELTA = "reasoning.delta"
    const val TEXT_DELTA = "text.delta"
    const val USAGE_UPDATED = "usage.updated"
    const val GENERATION_COMPLETED = "generation.completed"
    const val GENERATION_CANCELLED = "generation.cancelled"
    const val GENERATION_FAILED = "generation.failed"
    const val RECEIPT_RESULT = "receipt.result"
    const val STREAM_RESUME_RESULT = "stream.resume.result"
}

/** Why a frame could not be routed. Never carries frame content. */
enum class ClaudePParseRejection {
    /** Not decodable as an envelope at all. */
    MALFORMED_FRAME,

    /** Envelope arrived without a `protocol` field. */
    MISSING_PROTOCOL,

    /** `protocol` is not a `<family>.v<major>` identifier. */
    MALFORMED_PROTOCOL,

    /** A different major version. Fail closed: never guess field meanings. */
    PROTOCOL_MAJOR_MISMATCH,

    /** Envelope arrived without a `type` field. */
    MISSING_TYPE,

    /** The event type is known but its body did not match the versioned schema. */
    MALFORMED_EVENT_BODY,
}

/** Result of routing one inbound frame. */
sealed interface ClaudePInbound {
    /** A known, well-formed event. */
    data class Event(val event: ClaudePServerEvent) : ClaudePInbound

    /**
     * A well-formed envelope whose type this build does not route. Safe to drop: it is neither
     * text nor a terminal, and dropping it can never end a generation.
     */
    data object IgnoredUnknownEvent : ClaudePInbound

    /** Protocol violation. The caller must stop the stream and must not dispatch anything. */
    data class Rejected(val reason: ClaudePParseRejection) : ClaudePInbound
}

/** Public, versioned error enum. */
@Serializable
enum class ClaudePErrorCode {
    @SerialName("authentication_required")
    AUTHENTICATION_REQUIRED,

    @SerialName("device_revoked")
    DEVICE_REVOKED,

    @SerialName("protocol_mismatch")
    PROTOCOL_MISMATCH,

    @SerialName("cli_version_mismatch")
    CLI_VERSION_MISMATCH,

    @SerialName("model_not_allowed")
    MODEL_NOT_ALLOWED,

    @SerialName("session_missing")
    SESSION_MISSING,

    @SerialName("session_conflict")
    SESSION_CONFLICT,

    @SerialName("worker_busy")
    WORKER_BUSY,

    @SerialName("quota_unavailable")
    QUOTA_UNAVAILABLE,

    @SerialName("timeout")
    TIMEOUT,

    @SerialName("cancelled")
    CANCELLED,

    @SerialName("stream_interrupted")
    STREAM_INTERRUPTED,

    @SerialName("tool_bridge_unavailable")
    TOOL_BRIDGE_UNAVAILABLE,

    /**
     * Same `request_id`, different request fingerprint. Named by
     * `claudep/02-wire-protocol-v1.md` §7.
     */
    @SerialName("idempotency_conflict")
    IDEMPOTENCY_CONFLICT,

    /** Also the fallback for any code this build does not know. */
    @SerialName("external_runtime_error")
    EXTERNAL_RUNTIME_ERROR,

    /** Client-side: the local skeleton is not paired yet (CP1-A). */
    @SerialName("not_paired")
    NOT_PAIRED;

    companion object {
        /**
         * Maps a wire string onto the enum.
         *
         * An unrecognised code becomes [EXTERNAL_RUNTIME_ERROR] rather than being retained. The raw
         * string is untrusted remote input and must never reach a UI, a log or a persisted message.
         */
        fun fromWire(raw: String?): ClaudePErrorCode = when (raw?.trim()) {
            "authentication_required" -> AUTHENTICATION_REQUIRED
            "device_revoked" -> DEVICE_REVOKED
            "protocol_mismatch" -> PROTOCOL_MISMATCH
            "cli_version_mismatch" -> CLI_VERSION_MISMATCH
            "model_not_allowed" -> MODEL_NOT_ALLOWED
            "session_missing" -> SESSION_MISSING
            "session_conflict" -> SESSION_CONFLICT
            "worker_busy" -> WORKER_BUSY
            "quota_unavailable" -> QUOTA_UNAVAILABLE
            "timeout" -> TIMEOUT
            "cancelled" -> CANCELLED
            "stream_interrupted" -> STREAM_INTERRUPTED
            "tool_bridge_unavailable" -> TOOL_BRIDGE_UNAVAILABLE
            "idempotency_conflict" -> IDEMPOTENCY_CONFLICT
            "external_runtime_error" -> EXTERNAL_RUNTIME_ERROR
            "not_paired" -> NOT_PAIRED
            else -> EXTERNAL_RUNTIME_ERROR
        }
    }
}

/** Bounded stop-reason vocabulary for `generation.completed`. */
enum class ClaudePStopReason(val wireValue: String) {
    COMPLETED("completed"),
    STOP_SEQUENCE("stop_sequence"),
    MAX_TOKENS("max_tokens"),
    TIMEOUT("timeout"),
    UNKNOWN("unknown");

    companion object {
        fun fromWire(raw: String?): ClaudePStopReason = when (raw?.trim()) {
            "completed" -> COMPLETED
            "stop_sequence" -> STOP_SEQUENCE
            "max_tokens" -> MAX_TOKENS
            "timeout" -> TIMEOUT
            else -> UNKNOWN
        }
    }
}

/** Lifecycle state of a generation, as reported by a receipt. */
enum class ClaudePGenerationState(val wireValue: String) {
    ACCEPTED("accepted"),
    ACTIVE("active"),
    COMPLETED("completed"),
    CANCELLED("cancelled"),
    FAILED("failed"),

    /** The gateway cannot prove a terminal. The user decides; we never auto-retry. */
    UNKNOWN("unknown");

    val isTerminal: Boolean
        get() = this == COMPLETED || this == CANCELLED || this == FAILED

    companion object {
        fun fromWire(raw: String?): ClaudePGenerationState = when (raw?.trim()) {
            "accepted" -> ACCEPTED
            "active" -> ACTIVE
            "completed" -> COMPLETED
            "cancelled" -> CANCELLED
            "failed" -> FAILED
            else -> UNKNOWN
        }
    }
}

/** The three mutually exclusive terminals a generation may reach. */
enum class ClaudePTerminalKind {
    COMPLETED,
    CANCELLED,
    FAILED,
}

/**
 * Enforces "终态必须唯一" for one generation.
 *
 * The gateway promises exactly one terminal, but a reconnect can replay history and a cancel can
 * race a completion. Reading a decision from the wire is therefore not enough — this tracker is
 * the single local authority, and it also gates the "no content after terminal" rule.
 */
class ClaudePTerminalGate {
    @Volatile
    private var seen: ClaudePTerminalKind? = null

    val terminal: ClaudePTerminalKind?
        get() = seen

    val terminalSeen: Boolean
        get() = seen != null

    /** True while content deltas are still legal. */
    val acceptsDeltas: Boolean
        get() = seen == null

    /**
     * Records a terminal. Returns `false` when one was already recorded, which the caller must
     * treat as "ignore this late/duplicate terminal" rather than as an error to surface.
     */
    fun tryAccept(kind: ClaudePTerminalKind): Boolean {
        if (seen != null) return false
        seen = kind
        return true
    }
}

/**
 * Short, content-free reference for an opaque identifier.
 *
 * Used by `toString` implementations so a log line can correlate two events without carrying the
 * raw session/generation/device identifier.
 */
internal fun String?.redactedRef(): String {
    if (this.isNullOrEmpty()) return "-"
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    return buildString(8) {
        for (i in 0 until 4) {
            append(HEX[(digest[i].toInt() and 0xff) ushr 4])
            append(HEX[digest[i].toInt() and 0x0f])
        }
    }
}

private const val HEX = "0123456789abcdef"

/** Envelope shared by every frame in both directions. */
@Serializable
data class ClaudePEnvelope(
    val protocol: String = ClaudePProtocol.PROTOCOL_ID,
    val type: String,
    @SerialName("connection_id") val connectionId: String? = null,
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("generation_id") val generationId: String? = null,
    val sequence: Long? = null,
    @SerialName("sent_at") val sentAt: String? = null,
    val body: JsonObject = JsonObject(emptyMap()),
) {
    /** Redacted: `body` may hold a prompt, model output or tool arguments. */
    override fun toString(): String =
        "ClaudePEnvelope(protocol=$protocol, type=$type, " +
            "connectionId=${connectionId.redactedRef()}, requestId=${requestId.redactedRef()}, " +
            "generationId=${generationId.redactedRef()}, sequence=$sequence, " +
            "body=<redacted:${body.size} fields>)"
}
