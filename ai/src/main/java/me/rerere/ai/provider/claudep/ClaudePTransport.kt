package me.rerere.ai.provider.claudep

import kotlinx.coroutines.flow.Flow

/**
 * Transport seam between the Claude P client and an actual WebSocket implementation.
 *
 * The client's whole safety story — subprotocol validation, the hello handshake, bounded buffering,
 * sequence monotonicity, reconnect budgets, cancel-once — lives *above* this interface, so all of it
 * is testable against [FakeClaudePWebSocketConnector] without a socket, a clock or a sleep. Only
 * [OkHttpClaudePWebSocketConnector] is real, and it is deliberately thin: it maps OkHttp callbacks
 * onto [ClaudePTransportEvent] and does no interpretation of its own.
 */
interface ClaudePWebSocketConnector {
    /**
     * Opens the socket and suspends until the upgrade completes.
     *
     * Never throws for an expected failure — a refused connection, a TLS error and a dead network are
     * ordinary outcomes, not exceptions, and returning them as data keeps error handling out of
     * `catch` blocks where a security check could be accidentally skipped.
     */
    suspend fun connect(request: ClaudePConnectRequest): ClaudePConnectOutcome
}

/**
 * Everything the connector needs to open one socket.
 *
 * [credential] travels as the `Authorization` header of the upgrade request rather than as a query
 * parameter, because `claudep/02-wire-protocol-v1.md` §1 fixes the stream URL with no query at all
 * and the hello body has no credential field. A header is also the one place
 * `claudep/03-security-and-operations.md` §7 already anticipates secrets, and its rule that audit
 * output must never contain an `Authorization` header is only meaningful if the header is where
 * credentials live.
 */
data class ClaudePConnectRequest(
    /** Always a `wss://` URL produced by [ClaudePEndpoint.streamUrl]. */
    val url: String,
    /** Scoped, short-lived device access credential. */
    val credential: ClaudePAccessCredential,
    /** Offered subprotocols, most preferred first. */
    val subprotocols: List<String> = listOf(ClaudePProtocol.SUBPROTOCOL),
    /** Largest inbound text frame this client will accept, in UTF-8 bytes. */
    val maxInboundFrameBytes: Int = ClaudePTransportLimits.DEFAULT_MAX_INBOUND_FRAME_BYTES,
)

/** Result of opening a socket. */
sealed interface ClaudePConnectOutcome {
    data class Connected(val session: ClaudePWebSocketSession) : ClaudePConnectOutcome

    data class Failed(val reason: ClaudePTransportFailure) : ClaudePConnectOutcome
}

/**
 * One open socket.
 *
 * Implementations must guarantee that [incoming] is a cold flow which emits every frame exactly once
 * and in arrival order, and that collecting it twice does not duplicate frames. Closing is
 * idempotent.
 */
interface ClaudePWebSocketSession {
    /**
     * The subprotocol the server actually selected, or `null` when it selected none.
     *
     * The client — not the connector — decides whether this is acceptable. A connector that silently
     * accepted a missing or unexpected subprotocol would be exactly the fail-open the client's
     * negotiation check exists to prevent.
     */
    val selectedSubprotocol: String?

    /** Sends one text frame. Throws [ClaudePTransportException] when the socket is already gone. */
    suspend fun send(text: String)

    /** Frames in arrival order. Terminates after a [ClaudePTransportEvent.Closed]/`Failed`. */
    fun incoming(): Flow<ClaudePTransportEvent>

    /** Best-effort close. Must not throw, and must be safe to call more than once. */
    suspend fun close(code: Int)
}

/** One inbound frame, or the end of the stream. */
sealed interface ClaudePTransportEvent {
    data class Text(val text: String) : ClaudePTransportEvent

    /**
     * A frame larger than the accepted cap.
     *
     * Terminal for the connection. The frame is reported by size only — it is never buffered, never
     * parsed and never forwarded, so an oversized frame cannot be a memory-amplification vector.
     */
    data class FrameRejected(val reason: ClaudePFrameRejection) : ClaudePTransportEvent

    /** The socket is gone. Always terminal. */
    data class Closed(val reason: ClaudePTransportFailure) : ClaudePTransportEvent
}

/** Why an inbound frame was refused before reaching any consumer. */
enum class ClaudePFrameRejection {
    /** Larger than [ClaudePTransportLimits.DEFAULT_MAX_INBOUND_FRAME_BYTES]. */
    TOO_LARGE,

    /** A binary frame. The protocol is UTF-8 JSON only (`claudep/02-wire-protocol-v1.md` §1). */
    BINARY_FRAME,

    /** Text that is not valid UTF-8. */
    NOT_UTF8,
}

/** Why a connection ended or never started. Stable enum — never a server-supplied string. */
enum class ClaudePTransportFailure {
    /** The upgrade never completed (DNS, refused, timeout). */
    CONNECT_FAILED,

    /** TLS could not be established, or the peer's certificate was rejected. */
    TLS_FAILED,

    /** The socket died while in use. */
    CONNECTION_LOST,

    /** The peer or the local stack closed the socket cleanly. */
    CLOSED,

    /** The HTTP upgrade returned a non-101 status. */
    UPGRADE_REJECTED,
}

/** Thrown by [ClaudePWebSocketSession.send] when the socket is no longer usable. */
class ClaudePTransportException(
    val reason: ClaudePTransportFailure,
) : Exception("claude-p transport failure: ${reason.name}")

/**
 * A scoped device access credential.
 *
 * Redacted in `toString` so it cannot reach a log through an object graph that happens to hold one.
 * `claudep/03-security-and-operations.md` §2 requires the credential to be short-lived and
 * device-bound; that it is *also* impossible to print is what keeps it from spreading.
 */
@JvmInline
value class ClaudePAccessCredential(val value: String) {
    override fun toString(): String = "ClaudePAccessCredential(<redacted:${value.length} chars>)"

    companion object {
        /** No credential. Never sent, never accepted as an authorization. */
        val NONE: ClaudePAccessCredential = ClaudePAccessCredential("")

        fun isUsable(credential: ClaudePAccessCredential?): Boolean =
            credential != null && credential.value.isNotBlank()
    }
}

/** Client-side bounds. Every one of them exists to stop unbounded growth on a hostile peer. */
object ClaudePTransportLimits {
    /**
     * Largest inbound frame accepted, in UTF-8 bytes.
     *
     * Matches the value the fake gateway advertises in `server.hello`. A gateway may advertise a
     * *smaller* frame limit for what we may send; it can never raise what we accept, because the cap
     * protects us from the gateway.
     */
    const val DEFAULT_MAX_INBOUND_FRAME_BYTES: Int = 262_144

    /**
     * Hard ceiling on any outbound frame, applied on top of the server's advertised
     * `max_frame_bytes`. The server's number is untrusted input, so it is clamped rather than obeyed.
     */
    const val HARD_MAX_OUTBOUND_FRAME_BYTES: Int = 1_048_576

    /** RPCs that may be in flight at once before further sends are refused. */
    const val MAX_PENDING_RPCS: Int = 32

    /** Frames buffered per generation while its consumer is behind. */
    const val GENERATION_BUFFER_CAPACITY: Int = 256

    /**
     * Frames buffered by the socket reader before the connection is dropped.
     *
     * OkHttp 5 has no `request(n)` flow control, so this cannot pace the peer — it can only bound
     * what a fast or hostile gateway is allowed to accumulate before the connection is refused.
     */
    const val SOCKET_INBOUND_BUFFER_CAPACITY: Int = 128

    /** Generations tracked at once. Beyond this, `generation.start` is refused. */
    const val MAX_TRACKED_GENERATIONS: Int = 8

    /**
     * Wall-clock budget for one RPC response.
     *
     * A gateway that accepts a request and never answers must not pin a coroutine forever.
     */
    const val RPC_TIMEOUT_MILLIS: Long = 60_000

    /** Budget for the whole connect-plus-hello sequence. */
    const val HANDSHAKE_TIMEOUT_MILLIS: Long = 30_000
}

/**
 * Bounded reconnect schedule.
 *
 * Pure and clock-free so that "the budget is finite and the delay is capped" is a property that can
 * be asserted directly, instead of being inferred from a test that waits. `claudep/02-wire-protocol-v1.md`
 * §9 requires a dropped connection to recover without ever re-dispatching a model request; a
 * reconnect policy that could retry forever would make that guarantee meaningless in practice,
 * because it would keep a dead gateway in the user's request path indefinitely.
 */
data class ClaudePReconnectPolicy(
    /** Total reconnect attempts after the initial connect fails or a live socket drops. */
    val maxAttempts: Int = 5,
    val initialBackoffMillis: Long = 500,
    val maxBackoffMillis: Long = 30_000,
    val multiplier: Int = 2,
) {
    init {
        require(maxAttempts >= 0) { "maxAttempts must not be negative" }
        require(initialBackoffMillis > 0) { "initialBackoffMillis must be positive" }
        require(maxBackoffMillis >= initialBackoffMillis) {
            "maxBackoffMillis must not be below initialBackoffMillis"
        }
        require(multiplier >= 1) { "multiplier must be at least 1" }
    }

    /**
     * Delay before attempt [attempt] (1-based), or `null` once the budget is spent.
     *
     * Returning `null` rather than a very large delay is deliberate: the caller must transition to a
     * stable offline state, not schedule one more attempt that will never come.
     */
    fun delayMillisFor(attempt: Int): Long? {
        if (attempt <= 0 || attempt > maxAttempts) return null
        var delay = initialBackoffMillis
        repeat(attempt - 1) {
            delay *= multiplier
            if (delay >= maxBackoffMillis) return maxBackoffMillis
        }
        return delay.coerceAtMost(maxBackoffMillis)
    }
}

/**
 * Connection lifecycle of one [ClaudePGatewayClient] instance.
 *
 * [OFFLINE] and the two failure states are *stable*: nothing leaves them without an explicit user
 * action or a new request. Only [DISCONNECTED] reconnects on its own. That distinction is what stops
 * a misconfigured or revoked gateway from being hammered forever by a provider that keeps trying.
 */
enum class ClaudePConnectionState {
    /** Idle. A connect may be started. */
    DISCONNECTED,

    /** Socket upgrade in progress. */
    CONNECTING,

    /** Socket is up; `client.hello` is in flight. */
    HANDSHAKING,

    /** Hello negotiated. RPCs may be issued. */
    READY,

    /** Socket was lost and a bounded retry is scheduled. */
    RECONNECTING,

    /** Reconnect budget spent, or the user is not paired. Stable until user action. */
    OFFLINE,

    /** The gateway speaks an incompatible protocol, or sent a frame we must refuse. Stable. */
    PROTOCOL_ERROR,

    /** The device credential was rejected or is missing. Stable until re-pairing. */
    CREDENTIAL_INVALID,
}
