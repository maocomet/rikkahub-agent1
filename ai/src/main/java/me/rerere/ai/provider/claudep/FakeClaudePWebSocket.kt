package me.rerere.ai.provider.claudep

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/**
 * Deterministic, in-memory WebSocket transport.
 *
 * **It opens no socket, resolves no name and reads no clock.** Every value is a constructor parameter
 * derived from one, so a test never sleeps, never waits on a barrier and never races. That is the
 * whole reason the transport sits behind [ClaudePWebSocketConnector]: the state machine, the bounded
 * buffers, the reconnect budget and the cancel semantics can all be exercised exactly, without a
 * network, and without `kotlinx-coroutines-test` — which this repository does not have and which
 * CP1-B must not add.
 *
 * Frames are produced **synchronously, in response to a client send**, so the reply to an RPC is
 * already buffered by the time the client awaits it. Determinism here is not a convenience: it is
 * what lets "one logical request dispatches once" be asserted directly instead of inferred from a
 * timing-sensitive test.
 */
class FakeClaudePWebSocketConnector(
    /** Frames the fake server answers with. */
    val server: FakeClaudePFrameServer = FakeClaudePFrameServer(),
    /**
     * Subprotocol the fake reports as selected. `null` models a server (or a proxy) that selected
     * none, which the client must refuse before hello.
     */
    private val selectedSubprotocol: String? = ClaudePProtocol.SUBPROTOCOL,
    /** When set, `connect` fails with this reason instead of opening. */
    private val connectFailure: ClaudePTransportFailure? = null,
) : ClaudePWebSocketConnector {

    /** Every connect request, in order, so tests can assert the URL, credential and offers. */
    val connectRequests: MutableList<ClaudePConnectRequest> = mutableListOf()

    /** Connect attempts that never produced a session. */
    var connectFailureCount: Int = 0
        private set

    /** The most recently opened session, or `null`. */
    var lastSession: FakeClaudePWebSocketSession? = null
        private set

    val connectCount: Int get() = connectRequests.size

    /** All sessions opened so far, oldest first — used to prove a reconnect made a *new* socket. */
    val sessions: MutableList<FakeClaudePWebSocketSession> = mutableListOf()

    /**
     * Bounded inbound capacity for each new session.
     *
     * Set to `1` to exercise the socket-level backpressure path: the fake's `send` suspends rather
     * than dropping, exactly as a paced real socket does.
     */
    var inboundCapacity: Int = 64

    /**
     * Once set, connect attempts after this many successful sessions fail.
     *
     * Models a gateway that was reachable and then stopped being reachable — the scenario where a
     * reconnect budget actually runs out mid-generation.
     */
    var failConnectAfter: Int? = null

    override suspend fun connect(request: ClaudePConnectRequest): ClaudePConnectOutcome {
        connectRequests += request
        val networkDown = failConnectAfter?.let { sessions.size >= it } ?: false
        connectFailure?.let {
            connectFailureCount += 1
            return ClaudePConnectOutcome.Failed(it)
        }
        if (networkDown) {
            connectFailureCount += 1
            return ClaudePConnectOutcome.Failed(ClaudePTransportFailure.CONNECT_FAILED)
        }
        val session = FakeClaudePWebSocketSession(
            connector = this,
            selectedSubprotocol = selectedSubprotocol,
            inboundCapacity = inboundCapacity,
        )
        lastSession = session
        sessions += session
        return ClaudePConnectOutcome.Connected(session)
    }
}

/** One fake socket. Handles exactly what a real one would, minus the network. */
class FakeClaudePWebSocketSession internal constructor(
    private val connector: FakeClaudePWebSocketConnector,
    override val selectedSubprotocol: String?,
    inboundCapacity: Int,
) : ClaudePWebSocketSession {

    private val inbound = Channel<ClaudePTransportEvent>(capacity = inboundCapacity.coerceAtLeast(1))

    /** Frames the client sent, in order, as raw JSON. */
    val sent: MutableList<String> = mutableListOf()

    /** Close codes the client requested. */
    val closeCodes: MutableList<Int> = mutableListOf()

    @Volatile
    private var closed = false

    val isClosed: Boolean get() = closed

    /** Number of frames the client sent with this envelope `type`. */
    fun sentCountOfType(type: String): Int = sent.count { typeOf(it) == type }

    /** The last frame the client sent with this `type`, or `null`. */
    fun lastSentOfType(type: String): ClaudePEnvelope? =
        sent.lastOrNull { typeOf(it) == type }?.let(::decodeEnvelope)

    override fun incoming(): Flow<ClaudePTransportEvent> = inbound.receiveAsFlow()

    override suspend fun send(text: String) {
        if (closed) throw ClaudePTransportException(ClaudePTransportFailure.CLOSED)
        sent += text
        // The fake server answers synchronously. Because `send` suspends when the inbound buffer is
        // full, this also gives the client real backpressure rather than a silent drop.
        connector.server.handle(this, text)
    }

    override suspend fun close(code: Int) {
        closeCodes += code
        end(ClaudePTransportFailure.CLOSED)
    }

    /** Pushes one inbound frame. Suspends when the consumer is behind. */
    suspend fun deliver(text: String) {
        if (closed) return
        inbound.send(ClaudePTransportEvent.Text(text))
    }

    /** Pushes a raw frame the fake server did not generate — for malformed/oversized tests. */
    suspend fun deliverRaw(text: String) = deliver(text)

    /** Pushes an oversized-frame event, without allocating the frame itself. */
    suspend fun deliverFrameRejected(reason: ClaudePFrameRejection) {
        if (closed) return
        inbound.send(ClaudePTransportEvent.FrameRejected(reason))
    }

    /** Simulates the peer dropping the socket. */
    suspend fun dropConnection(reason: ClaudePTransportFailure = ClaudePTransportFailure.CONNECTION_LOST) {
        end(reason)
    }

    private suspend fun end(reason: ClaudePTransportFailure) {
        if (closed) return
        closed = true
        inbound.send(ClaudePTransportEvent.Closed(reason))
        inbound.close()
    }
}

/**
 * A scripted Claude P gateway, at the frame level.
 *
 * It answers `client.hello`, `catalog.get`, `generation.start`, `generation.cancel`, `receipt.query`
 * and `stream.resume`, stamps monotonically increasing `event_seq` values, and never consults a
 * clock. Generation *content* is emitted explicitly by the test through
 * [emitText]/[emitReasoning]/[emitTerminal] and friends, so ordering is something a test states
 * rather than something it waits for.
 *
 * It refuses as loudly as it accepts: an unknown request type, or a `generation.start` for an alias
 * outside the catalog, produces a bounded failure frame rather than silence.
 */
class FakeClaudePFrameServer(
    val catalogModels: List<ClaudePModelEntry> = DEFAULT_CATALOG,
    /** Protocol identifier stamped on every frame. Override to model a malformed family. */
    val protocolId: String = ClaudePProtocol.PROTOCOL_ID,
    /** Protocol version claimed in `server.hello`. Set to `"v2"` to model a version mismatch. */
    val serverProtocolVersion: String = "v1",
    val claudeCodeVersion: String = "2.0.1",
    /** Outbound frame cap advertised to the client. */
    val maxFrameBytes: Int = ClaudePTransportLimits.DEFAULT_MAX_INBOUND_FRAME_BYTES,
    /** When set, `client.hello` is answered with this bounded error instead of a hello. */
    val handshakeRejection: ClaudePErrorCode? = null,
    /** When true, `generation.start` is answered with this bounded error instead of an accept. */
    val startRejection: ClaudePErrorCode? = null,
    /**
     * When true, a cancel is received but *no* terminal follows.
     *
     * Models the case `claudep/02-wire-protocol-v1.md` §8 describes: the gateway has asked the
     * worker to stop but cannot yet prove the child process is gone. It must not claim `cancelled`
     * for something it cannot verify, and neither may the client.
     */
    val cancelAcknowledgesWithoutTerminal: Boolean = false,
    /** When true, the generation is accepted but the alias is echoed back as unknown. */
    val echoUnknownModelAlias: Boolean = false,
) {
    /** `client.hello` frames received. */
    var helloCount: Int = 0
        private set

    /** `generation.start` frames received — the count that proves no second dispatch happened. */
    var startCount: Int = 0
        private set

    /** Generations actually accepted (accepted a model run). */
    var dispatchCount: Int = 0
        private set

    /** `generation.cancel` frames received. */
    var cancelCount: Int = 0
        private set

    var resumeCount: Int = 0
        private set

    var receiptCount: Int = 0
        private set

    /** generation id -> highest `event_seq` emitted. */
    private val sequence = mutableMapOf<String, Long>()

    /** generation id -> the terminal already emitted, if any. */
    private val terminals = mutableMapOf<String, ClaudePTerminalKind>()

    /** generation id -> every frame emitted, so `stream.resume` can replay them. */
    private val history = mutableMapOf<String, MutableList<Pair<Long, String>>>()

    private var generationSuffix = 0

    /** The single generation this server accepted, for one-generation scripts. */
    var lastGenerationId: String? = null
        private set

    internal suspend fun handle(session: FakeClaudePWebSocketSession, raw: String) {
        val envelope = decodeEnvelope(raw) ?: return
        when (envelope.type) {
            ClaudePEventType.CLIENT_HELLO -> handleHello(session, envelope)
            ClaudePEventType.CATALOG_GET -> handleCatalog(session, envelope)
            ClaudePEventType.GENERATION_START -> handleStart(session, envelope)
            ClaudePEventType.GENERATION_CANCEL -> handleCancel(session, envelope)
            ClaudePEventType.RECEIPT_QUERY -> handleReceipt(session, envelope)
            ClaudePEventType.STREAM_RESUME -> handleResume(session, envelope)
            else -> Unit
        }
    }

    private suspend fun handleHello(session: FakeClaudePWebSocketSession, envelope: ClaudePEnvelope) {
        helloCount += 1
        val rejection = handshakeRejection
        if (rejection != null) {
            session.deliver(
                frame(
                    type = ClaudePEventType.GENERATION_FAILED,
                    requestId = envelope.requestId,
                    body = buildJsonObject { put("error_code", rejection.wireName()) },
                ),
            )
            return
        }
        session.deliver(
            frame(
                type = ClaudePEventType.SERVER_HELLO,
                requestId = envelope.requestId,
                body = buildJsonObject {
                    put("protocol_version", serverProtocolVersion)
                    put("gateway_build", "fake-gateway-1")
                    put("worker_abi", "fake-worker-abi-1")
                    put("claude_code_version", claudeCodeVersion)
                    put("max_frame_bytes", maxFrameBytes)
                    put("max_prompt_bytes", 1_048_576)
                    put("max_concurrent_generations", 1)
                    put(
                        "features",
                        buildJsonArray {
                            add("text_stream")
                            add("cancel")
                            add("receipt_query")
                        },
                    )
                    put("server_time", "1970-01-01T00:00:00Z")
                    put("connection_id", "fake-connection")
                },
            ),
        )
    }

    private suspend fun handleCatalog(session: FakeClaudePWebSocketSession, envelope: ClaudePEnvelope) {
        session.deliver(
            frame(
                type = ClaudePEventType.CATALOG_RESULT,
                requestId = envelope.requestId,
                body = buildJsonObject {
                    put("models", ClaudePProtocol.json.encodeToJsonElement(catalogModels))
                },
            ),
        )
    }

    private suspend fun handleStart(session: FakeClaudePWebSocketSession, envelope: ClaudePEnvelope) {
        startCount += 1
        val requestId = envelope.requestId

        val rejection = startRejection
        if (rejection != null) {
            session.deliver(
                frame(
                    type = ClaudePEventType.GENERATION_FAILED,
                    requestId = requestId,
                    body = buildJsonObject { put("error_code", rejection.wireName()) },
                ),
            )
            return
        }

        val modelAlias = envelope.body["model_alias"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        val allowed = catalogModels.any { it.alias == modelAlias && it.enabled }
        if (!allowed) {
            session.deliver(
                frame(
                    type = ClaudePEventType.GENERATION_FAILED,
                    requestId = requestId,
                    body = buildJsonObject {
                        put("error_code", ClaudePErrorCode.MODEL_NOT_ALLOWED.wireName())
                    },
                ),
            )
            return
        }

        dispatchCount += 1
        val generationId = "gen-${++generationSuffix}"
        lastGenerationId = generationId

        emitTo(
            session = session,
            generationId = generationId,
            type = ClaudePEventType.GENERATION_ACCEPTED,
            requestId = requestId,
            body = buildJsonObject {
                put("generation_id", generationId)
                put("request_id", requestId.orEmpty())
                put("accepted_seq", 1)
            },
        )
    }

    private suspend fun handleCancel(session: FakeClaudePWebSocketSession, envelope: ClaudePEnvelope) {
        cancelCount += 1
        val generationId = envelope.body["generation_id"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?: return
        // §8: an already-terminal generation adds nothing.
        if (terminals.containsKey(generationId)) return
        // The stream has not ended and the gateway cannot yet prove it has, so it reports nothing
        // rather than inventing a terminal it cannot back.
        if (cancelAcknowledgesWithoutTerminal) return
        emitTerminal(session, generationId, ClaudePTerminalKind.CANCELLED)
    }

    private suspend fun handleReceipt(session: FakeClaudePWebSocketSession, envelope: ClaudePEnvelope) {
        receiptCount += 1
        val generationId = envelope.body["generation_id"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?: return
        val terminal = terminals[generationId]
        session.deliver(
            frame(
                type = ClaudePEventType.RECEIPT_RESULT,
                requestId = envelope.requestId,
                generationId = generationId,
                body = buildJsonObject {
                    put("generation_id", generationId)
                    put("state", terminal?.let { stateOf(it) } ?: ClaudePGenerationState.ACTIVE.wireValue)
                    put("last_event_seq", sequence[generationId] ?: 0L)
                },
            ),
        )
    }

    private suspend fun handleResume(session: FakeClaudePWebSocketSession, envelope: ClaudePEnvelope) {
        resumeCount += 1
        val generationId = envelope.body["generation_id"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?: return
        val lastEventSeq = envelope.body["last_event_seq"]
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() } ?: 0L

        val terminal = terminals[generationId]
        val buffered = history[generationId].orEmpty().filter { it.first > lastEventSeq }

        // Replay is push-only, matching the frozen `stream.resume.result` body, which carries an
        // outcome and counters rather than frames.
        buffered.forEach { (_, json) -> session.deliver(json) }

        session.deliver(
            frame(
                type = ClaudePEventType.STREAM_RESUME_RESULT,
                requestId = envelope.requestId,
                generationId = generationId,
                body = buildJsonObject {
                    put("generation_id", generationId)
                    put(
                        "outcome",
                        when {
                            terminal != null -> ClaudePResumeKind.TERMINAL.wireValue
                            else -> ClaudePResumeKind.REPLAYED.wireValue
                        },
                    )
                    put("last_event_seq", sequence[generationId] ?: 0L)
                    put("state", terminal?.let { stateOf(it) } ?: ClaudePGenerationState.ACTIVE.wireValue)
                },
            ),
        )
    }

    // ---------------------------------------------------------------------------------------
    // Explicit content emission
    // ---------------------------------------------------------------------------------------

    suspend fun emitReasoning(session: FakeClaudePWebSocketSession, generationId: String, summary: String) =
        emitTo(
            session = session,
            generationId = generationId,
            type = ClaudePEventType.REASONING_DELTA,
            body = buildJsonObject {
                put("message_id", "msg-1"); put("index", 0); put("summary", summary)
            },
        )

    suspend fun emitText(session: FakeClaudePWebSocketSession, generationId: String, text: String) =
        emitTo(
            session = session,
            generationId = generationId,
            type = ClaudePEventType.TEXT_DELTA,
            body = buildJsonObject { put("message_id", "msg-1"); put("index", 0); put("text", text) },
        )

    suspend fun emitUsage(session: FakeClaudePWebSocketSession, generationId: String) = emitTo(
        session = session,
        generationId = generationId,
        type = ClaudePEventType.USAGE_UPDATED,
        body = buildJsonObject {
            put("prompt_tokens", 11); put("completion_tokens", 7)
            put("cached_tokens", 3); put("total_tokens", 18)
        },
    )

    suspend fun emitTerminal(
        session: FakeClaudePWebSocketSession,
        generationId: String,
        kind: ClaudePTerminalKind,
        failureCode: ClaudePErrorCode = ClaudePErrorCode.EXTERNAL_RUNTIME_ERROR,
    ) = emitTo(
        session = session,
        generationId = generationId,
        type = when (kind) {
            ClaudePTerminalKind.COMPLETED -> ClaudePEventType.GENERATION_COMPLETED
            ClaudePTerminalKind.CANCELLED -> ClaudePEventType.GENERATION_CANCELLED
            ClaudePTerminalKind.FAILED -> ClaudePEventType.GENERATION_FAILED
        },
        body = when (kind) {
            ClaudePTerminalKind.COMPLETED -> buildJsonObject { put("stop_reason", "completed") }
            ClaudePTerminalKind.CANCELLED -> buildJsonObject { put("reason", "user_requested") }
            ClaudePTerminalKind.FAILED -> buildJsonObject { put("error_code", failureCode.wireName()) }
        },
    )

    /**
     * Emits an arbitrary generation frame, for unknown-event and post-terminal tests.
     *
     * [bumpSequence] is false when the test wants to inject a frame *without* advancing the
     * sequence — the only way to build a distinct, correctly-numbered unknown event.
     */
    suspend fun emitRaw(
        session: FakeClaudePWebSocketSession,
        generationId: String,
        type: String,
        body: JsonObject = JsonObject(emptyMap()),
    ) = emitTo(session, generationId, type, body = body)

    /**
     * Emits the same `event_seq` twice.
     *
     * Not reachable through the normal emitters, which is the point: a sequence regression is a
     * gateway misbehaving, so the test has to force it.
     */
    suspend fun emitWithRepeatedSequence(
        session: FakeClaudePWebSocketSession,
        generationId: String,
        text: String,
    ) {
        val seq = sequence[generationId] ?: 0L
        val raw = frame(
            type = ClaudePEventType.TEXT_DELTA,
            generationId = generationId,
            sequence = seq,
            body = buildJsonObject { put("message_id", "msg-1"); put("text", text) },
        )
        session.deliver(raw)
    }

    private suspend fun emitTo(
        session: FakeClaudePWebSocketSession,
        generationId: String,
        type: String,
        requestId: String? = null,
        body: JsonObject,
    ) {
        val next = (sequence[generationId] ?: 0L) + 1
        sequence[generationId] = next
        terminalsOf(type)?.let { terminals[generationId] = it }
        val raw = frame(
            type = type,
            requestId = requestId,
            generationId = generationId,
            sequence = next,
            body = body,
        )
        history.getOrPut(generationId) { mutableListOf() } += next to raw
        session.deliver(raw)
    }

    private fun frame(
        type: String,
        body: JsonObject,
        requestId: String? = null,
        generationId: String? = null,
        sequence: Long? = null,
    ): String = ClaudePProtocol.json.encodeToString(
        ClaudePEnvelope(
            protocol = protocolId,
            type = type,
            connectionId = "fake-connection",
            requestId = requestId,
            generationId = generationId,
            sequence = sequence,
            // Fixed rather than "now": a clock would make frames non-reproducible.
            sentAt = "1970-01-01T00:00:00Z",
            body = body,
        ),
    )

    companion object {
        /** Aliases only — no entry resembles a Claude Code CLI version. */
        val DEFAULT_CATALOG: List<ClaudePModelEntry> = listOf(
            ClaudePModelEntry(
                alias = "sonnet",
                displayName = "Claude Sonnet",
                input = listOf("text"),
                features = listOf("streaming", "reasoning_summary"),
                enabled = true,
            ),
            ClaudePModelEntry(
                alias = "retired",
                displayName = "Retired",
                input = listOf("text"),
                features = emptyList(),
                enabled = false,
            ),
        )

        private fun terminalsOf(type: String): ClaudePTerminalKind? = when (type) {
            ClaudePEventType.GENERATION_COMPLETED -> ClaudePTerminalKind.COMPLETED
            ClaudePEventType.GENERATION_CANCELLED -> ClaudePTerminalKind.CANCELLED
            ClaudePEventType.GENERATION_FAILED -> ClaudePTerminalKind.FAILED
            else -> null
        }

        private fun stateOf(kind: ClaudePTerminalKind): String = when (kind) {
            ClaudePTerminalKind.COMPLETED -> ClaudePGenerationState.COMPLETED.wireValue
            ClaudePTerminalKind.CANCELLED -> ClaudePGenerationState.CANCELLED.wireValue
            ClaudePTerminalKind.FAILED -> ClaudePGenerationState.FAILED.wireValue
        }
    }
}

/** Decodes an envelope, or `null` when the frame is not one. */
internal fun decodeEnvelope(raw: String): ClaudePEnvelope? = try {
    ClaudePProtocol.json.decodeFromString<ClaudePEnvelope>(raw)
} catch (_: Exception) {
    null
}

private fun typeOf(raw: String): String? = decodeEnvelope(raw)?.type
