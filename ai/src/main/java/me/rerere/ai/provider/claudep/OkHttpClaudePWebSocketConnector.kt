package me.rerere.ai.provider.claudep

import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * The real WebSocket transport, over OkHttp.
 *
 * Deliberately thin. It maps OkHttp's callback API onto [ClaudePTransportEvent] and enforces the two
 * things that must be enforced at the socket: **the URL is `wss://`** and **no frame larger than the
 * accepted cap is ever buffered**. Everything else — subprotocol policy, the handshake, ordering,
 * sequence monotonicity, reconnects — is decided above this class, where it is testable without a
 * network.
 *
 * ### The client it uses
 *
 * See [ClaudePOkHttp.hardened]: interceptors are **removed** from the injected client, redirects are
 * disabled (a redirect would replay the device's `Authorization` header against another host), and
 * automatic retry is disabled (the client above owns the reconnect budget).
 *
 * An earlier revision claimed no logging interceptor was "inherited". That was wrong —
 * `newBuilder()` copies interceptor lists — which is exactly why the stripping is now explicit and
 * tested rather than assumed.
 */
class OkHttpClaudePWebSocketConnector(
    client: OkHttpClient,
) : ClaudePWebSocketConnector {

    // Interceptors are stripped here rather than trusted to be absent upstream: `newBuilder()`
    // copies them from the source, so a shared logging interceptor would otherwise run against the
    // `Authorization` header this connector sets.
    private val client: OkHttpClient = ClaudePOkHttp.hardened(client)

    override suspend fun connect(request: ClaudePConnectRequest): ClaudePConnectOutcome {
        // Checked here as well as in ClaudePEndpoint, because this is the last point before a socket
        // exists. A plaintext upgrade would put the device credential on the wire in the clear.
        if (!request.url.startsWith(WSS_SCHEME)) {
            return ClaudePConnectOutcome.Failed(ClaudePTransportFailure.UPGRADE_REJECTED)
        }

        val builder = Request.Builder()
            .url(request.url)
            .header(HEADER_AUTHORIZATION, "$BEARER_PREFIX${request.credential.value}")
        // Offered in preference order. OkHttp echoes whatever the server selects back through the
        // response headers; it does not check that the selection was one we offered, so the client
        // above performs that check itself.
        if (request.subprotocols.isNotEmpty()) {
            builder.header(HEADER_SUBPROTOCOL, request.subprotocols.joinToString(", "))
        }

        // Built before the socket so every callback below has a session to talk to, no matter which
        // thread OkHttp invokes it from.
        val session = OkHttpClaudePSession(maxInboundFrameBytes = request.maxInboundFrameBytes)

        val webSocket = client.newWebSocket(
            builder.build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    session.attach(
                        webSocket = webSocket,
                        // `null` when the server selected nothing, which the client above must treat
                        // as a failed negotiation rather than as "no preference expressed".
                        selectedSubprotocol = response.header(HEADER_SUBPROTOCOL),
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) = session.onText(text)

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) = session.onBinary()

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    session.end(ClaudePTransportFailure.CLOSED)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    session.fail(t)
                }
            },
        )

        return when (val opened = session.awaitOpen()) {
            is OpenResult.Opened -> ClaudePConnectOutcome.Connected(session)

            is OpenResult.Refused -> {
                // Keeps a half-completed upgrade from outliving the attempt.
                webSocket.cancel()
                ClaudePConnectOutcome.Failed(opened.reason)
            }
        }
    }

    private sealed interface OpenResult {
        data object Opened : OpenResult

        data class Refused(val reason: ClaudePTransportFailure) : OpenResult
    }

    /**
     * One live OkHttp socket.
     *
     * ### Backpressure, and an honest limit
     *
     * OkHttp 4 exposed `WebSocket.request(n)`, which let a caller pace inbound delivery and made true
     * socket-level backpressure possible. **OkHttp 5 removed it** — the interface is now only
     * `request`, `queueSize`, `send`, `close` and `cancel` — so no public API exists to slow a peer
     * down. What is left is a bounded buffer plus a fail-closed rule: once the buffer is full the
     * connection ends, and the client above recovers through reconnect + `stream.resume` rather than
     * letting memory grow.
     *
     * That means the guarantee here is "bounded", not "paced": a gateway that outruns the consumer
     * costs a reconnect, not unbounded heap. The finer-grained and *recoverable* bound — per
     * generation, resumable from its last acknowledged `event_seq` — is
     * [ClaudePBoundedGenerationStream], which is where the protocol's own recovery mechanism lives.
     */
    private class OkHttpClaudePSession(
        private val maxInboundFrameBytes: Int,
    ) : ClaudePWebSocketSession {

        @Volatile
        private var webSocket: WebSocket? = null

        @Volatile
        private var subprotocol: String? = null

        @Volatile
        private var open = false

        @Volatile
        private var ended = false

        /**
         * A full buffer means the consumer is behind; that is reported by ending the connection
         * rather than by dropping frames, because a silently dropped frame would break the
         * sequence-monotonicity guarantee the layer above depends on.
         */
        private val events = Channel<ClaudePTransportEvent>(
            capacity = ClaudePTransportLimits.SOCKET_INBOUND_BUFFER_CAPACITY,
        )

        private val openResult = Channel<OpenResult>(capacity = 1)

        override val selectedSubprotocol: String? get() = subprotocol

        fun attach(webSocket: WebSocket, selectedSubprotocol: String?) {
            this.webSocket = webSocket
            this.subprotocol = selectedSubprotocol
            this.open = true
            openResult.trySend(OpenResult.Opened)
        }

        suspend fun awaitOpen(): OpenResult = openResult.receive()

        fun onText(text: String) {
            if (!open) return
            val size = text.toByteArray(Charsets.UTF_8).size
            if (size > maxInboundFrameBytes) {
                // Reported by size only. The frame is never parsed, never forwarded and never
                // retained, so an oversized frame cannot be a memory-amplification vector.
                deliver(ClaudePTransportEvent.FrameRejected(ClaudePFrameRejection.TOO_LARGE))
                return
            }
            deliver(ClaudePTransportEvent.Text(text))
        }

        fun onBinary() {
            if (!open) return
            // The protocol is UTF-8 JSON; a binary frame is a protocol violation, not a payload.
            deliver(ClaudePTransportEvent.FrameRejected(ClaudePFrameRejection.BINARY_FRAME))
        }

        fun fail(t: Throwable) {
            val reason = t.toTransportFailure(opened = open)
            if (!open && !ended) {
                // Never opened: the awaiting caller needs an answer, not a closed event channel.
                ended = true
                openResult.trySend(OpenResult.Refused(reason))
            }
            end(reason)
        }

        fun end(reason: ClaudePTransportFailure) {
            if (ended) return
            ended = true
            open = false
            events.trySend(ClaudePTransportEvent.Closed(reason))
            events.close()
        }

        override fun incoming(): Flow<ClaudePTransportEvent> = events.receiveAsFlow()

        override suspend fun send(text: String) {
            val socket = webSocket
            if (!open || socket == null) {
                throw ClaudePTransportException(ClaudePTransportFailure.CLOSED)
            }
            if (!socket.send(text)) {
                throw ClaudePTransportException(ClaudePTransportFailure.CLOSED)
            }
        }

        override suspend fun close(code: Int) {
            val socket = webSocket
            ended = true
            open = false
            events.close()
            openResult.trySend(OpenResult.Refused(ClaudePTransportFailure.CLOSED))
            socket?.close(code, null)
        }

        private fun deliver(event: ClaudePTransportEvent) {
            if (events.trySend(event).isFailure) {
                // The consumer is not keeping up.
                end(ClaudePTransportFailure.CONNECTION_LOST)
            }
        }
    }

    private companion object {
        const val WSS_SCHEME = "wss://"
        const val HEADER_AUTHORIZATION = "Authorization"
        const val HEADER_SUBPROTOCOL = "Sec-WebSocket-Protocol"
        const val BEARER_PREFIX = "Bearer "
    }
}

/** Maps a JVM/OkHttp throwable onto the bounded failure vocabulary. Never carries its message. */
private fun Throwable.toTransportFailure(opened: Boolean): ClaudePTransportFailure = when (this) {
    is SSLException, is CertificateException -> ClaudePTransportFailure.TLS_FAILED
    is IOException -> if (opened) ClaudePTransportFailure.CONNECTION_LOST else ClaudePTransportFailure.CONNECT_FAILED
    else -> if (opened) ClaudePTransportFailure.CONNECTION_LOST else ClaudePTransportFailure.CONNECT_FAILED
}
