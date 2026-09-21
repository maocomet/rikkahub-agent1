package me.rerere.ai.provider.claudep

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The bounded frame buffer behind one accepted generation.
 *
 * Two independent guarantees live here, both of which the protocol doc treats as client-side
 * obligations rather than things a gateway can be trusted to provide:
 *
 * 1. **Bounded memory.** The buffer has a hard capacity. A gateway that streams faster than the UI
 *    consumes — or a hostile one that simply never stops — fills it and is then refused, instead of
 *    growing the heap until the process dies.
 * 2. **Monotonic sequence.** `claudep/02-wire-protocol-v1.md` §2 requires a strictly increasing
 *    `event_seq` in one direction. A regression means we can no longer tell a replay from a new
 *    event, so the stream stops rather than guessing.
 *
 * Refusal is never silent. Both failures end the stream with a locally generated terminal frame
 * carrying a bounded [ClaudePErrorCode], so the consumer sees "this stream ended abnormally" instead
 * of a stream that merely stops — and the client can then recover the real state through
 * `stream.resume`/`receipt.query`, which is the recovery path §9 defines.
 */
internal class ClaudePBoundedGenerationStream(
    val generationId: String,
    capacity: Int = ClaudePTransportLimits.GENERATION_BUFFER_CAPACITY,
) {
    private val channel = Channel<String>(capacity = capacity.coerceAtLeast(1))
    private val lock = Any()

    private var lastAcceptedSeq: Long = 0
    private var finished = false

    @Volatile
    private var deliveredTerminal: ClaudePTerminalKind? = null

    /** Frames in arrival order. Ends after [finish] or after a refusal terminal. */
    val frames: Flow<String> = channel.receiveAsFlow()

    /** Highest `event_seq` accepted so far. Recovery resumes strictly after this. */
    val lastEventSeq: Long
        get() = synchronized(lock) { lastAcceptedSeq }

    /**
     * The terminal already handed to the consumer, if any.
     *
     * Read by `cancel` so that cancelling an already-finished generation returns the original
     * terminal without sending an RPC and without appending an event, which is what
     * `claudep/02-wire-protocol-v1.md` §8 requires. Tracked separately from the consumer's own
     * [ClaudePTerminalGate] because two different generations are never both in play, but two
     * different *observers* of one generation are.
     */
    val terminalKind: ClaudePTerminalKind? get() = deliveredTerminal

    /**
     * Offers one frame.
     *
     * [sequence] is the envelope's `sequence`, or `0` when the frame omits it. Omitting it is itself a
     * contract violation — every server event carries `event_seq` — so an absent sequence shows up as
     * a regression rather than being waved through as "unknown".
     */
    fun offer(
        raw: String,
        sequence: Long,
        terminalKind: ClaudePTerminalKind? = null,
    ): ClaudePFrameAcceptance {
        val refused = synchronized(lock) {
            when {
                finished -> ClaudePFrameAcceptance.ALREADY_FINISHED
                sequence <= lastAcceptedSeq -> ClaudePFrameAcceptance.SEQUENCE_REGRESSION
                else -> null
            }
        }
        if (refused != null) return refused

        val sent = channel.trySend(raw)
        if (sent.isFailure) {
            return ClaudePFrameAcceptance.BUFFER_OVERFLOW
        }

        synchronized(lock) {
            lastAcceptedSeq = sequence
            if (terminalKind != null) deliveredTerminal = terminalKind
        }
        return ClaudePFrameAcceptance.ACCEPTED
    }

    /**
     * Ends the stream, optionally after a locally generated terminal.
     *
     * Idempotent. The synthetic terminal is appended only when the buffer still has room; if it does
     * not, the stream simply ends. Emitting a terminal is a courtesy to the consumer, never a
     * guarantee — the authoritative terminal always comes from the gateway's receipt.
     */
    fun finish(syntheticTerminal: String? = null) {
        synchronized(lock) {
            if (finished) return
            finished = true
        }
        if (syntheticTerminal != null) {
            channel.trySend(syntheticTerminal)
        }
        channel.close()
    }
}

/** Why a frame was, or was not, buffered. */
enum class ClaudePFrameAcceptance {
    ACCEPTED,

    /** `event_seq` did not advance. The peer is replaying, reordering or lying. */
    SEQUENCE_REGRESSION,

    /** The consumer is behind and the bounded buffer is full. */
    BUFFER_OVERFLOW,

    /** The stream already ended. */
    ALREADY_FINISHED,
}

/**
 * Builds the terminal frame the client emits locally when it must end a stream it can no longer
 * trust.
 *
 * This is a bounded error report, not fabricated model output: it carries an enum from the frozen
 * vocabulary and no content, and it exists so a truncated stream surfaces as a failure the user can
 * act on rather than as a generation that quietly stops. The provider's terminal gate accepts it as
 * the single terminal, and `receipt.query` remains the authority on what the gateway actually did.
 */
internal fun claudePLocalTerminalFrame(
    generationId: String,
    sequence: Long,
    code: ClaudePErrorCode,
): String = ClaudePProtocol.json.encodeToString(
    ClaudePEnvelope(
        protocol = ClaudePProtocol.PROTOCOL_ID,
        type = ClaudePEventType.GENERATION_FAILED,
        connectionId = null,
        requestId = null,
        generationId = generationId,
        sequence = sequence,
        sentAt = null,
        body = buildJsonObject {
            put("error_code", code.wireName())
        },
    ),
)

/**
 * Wire spelling of an error code.
 *
 * Kept next to the enum it mirrors; a reflective lookup would work but would also silently return
 * the wrong thing if a `@SerialName` were ever mistyped.
 */
internal fun ClaudePErrorCode.wireName(): String = when (this) {
    ClaudePErrorCode.AUTHENTICATION_REQUIRED -> "authentication_required"
    ClaudePErrorCode.DEVICE_REVOKED -> "device_revoked"
    ClaudePErrorCode.PROTOCOL_MISMATCH -> "protocol_mismatch"
    ClaudePErrorCode.CLI_VERSION_MISMATCH -> "cli_version_mismatch"
    ClaudePErrorCode.MODEL_NOT_ALLOWED -> "model_not_allowed"
    ClaudePErrorCode.SESSION_MISSING -> "session_missing"
    ClaudePErrorCode.SESSION_CONFLICT -> "session_conflict"
    ClaudePErrorCode.WORKER_BUSY -> "worker_busy"
    ClaudePErrorCode.QUOTA_UNAVAILABLE -> "quota_unavailable"
    ClaudePErrorCode.TIMEOUT -> "timeout"
    ClaudePErrorCode.CANCELLED -> "cancelled"
    ClaudePErrorCode.STREAM_INTERRUPTED -> "stream_interrupted"
    ClaudePErrorCode.TOOL_BRIDGE_UNAVAILABLE -> "tool_bridge_unavailable"
    ClaudePErrorCode.IDEMPOTENCY_CONFLICT -> "idempotency_conflict"
    ClaudePErrorCode.EXTERNAL_RUNTIME_ERROR -> "external_runtime_error"
    ClaudePErrorCode.NOT_PAIRED -> "not_paired"
}
