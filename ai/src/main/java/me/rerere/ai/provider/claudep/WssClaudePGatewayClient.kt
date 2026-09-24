package me.rerere.ai.provider.claudep

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The real Claude P transport: one WSS connection to one paired gateway.
 *
 * This is the CP1-B replacement for `UnpairedClaudePGatewayClient`. It implements the same
 * [ClaudePGatewayClient] contract, so the provider above it is unchanged — everything the provider
 * was written against (a hello, a catalog, a generation handle emitting raw frames, an at-most-once
 * cancel, a receipt) now happens over a socket.
 *
 * ### What this class is responsible for
 *
 * - **Negotiation.** It offers exactly one subprotocol and refuses to proceed unless the server
 *   selects exactly that one. A server that selects nothing, or something else, is not a Claude P
 *   gateway, and the connection is abandoned before hello.
 * - **The signed handshake.** The `client.hello` nonce and signature are produced here, because they
 *   must be derived from a Keystore key the caller cannot reach. A caller-supplied body contributes
 *   only `app_version`, `protocol_versions` and `capabilities`.
 * - **Fail-closed protocol handling.** Every rejection the router reports moves the client into
 *   [ClaudePConnectionState.PROTOCOL_ERROR], which is stable — it never reconnects on its own.
 * - **Bounded everything.** In-flight RPCs, buffered frames per generation, tracked generations and
 *   reconnect attempts all have hard caps. There is no path that retries forever or grows without
 *   limit.
 * - **Recovery that never re-dispatches.** A dropped socket is recovered by reconnecting and issuing
 *   `stream.resume` for each generation still in flight. `generation.start` is never sent again:
 *   `claudep/03-security-and-operations.md` §2 forbids re-running a model request, and the receipt,
 *   not a retry, is the authority on the terminal.
 *
 * ### What it deliberately does not do
 *
 * No local model calls, no tool relay, no attachment handling, no session resume/fork planning. Those
 * are CP2+. This class moves frames and enforces bounds; meaning lives in [ClaudePProtocol] and
 * [me.rerere.ai.provider.providers.ClaudePProvider].
 */
class WssClaudePGatewayClient(
    private val connector: ClaudePWebSocketConnector,
    private val endpoint: ClaudePEndpoint,
    private val accessProvider: ClaudePDeviceAccessProvider,
    private val deviceKeyStore: ClaudePDeviceKeyStore,
    /** Alias of the Keystore key created during pairing. */
    private val keyAlias: String,
    private val scope: CoroutineScope,
    private val appVersion: String,
    private val reconnectPolicy: ClaudePReconnectPolicy = ClaudePReconnectPolicy(),
    /** Injectable so expiry is a deterministic input in tests rather than a wall-clock read. */
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    /**
     * Injectable so reconnect backoff costs no wall-clock time in tests. Production passes `delay`;
     * a test passes a recorder, which keeps the suite free of sleeps without weakening the policy
     * under test — the policy itself is a pure function and is asserted directly.
     */
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) : ClaudePGatewayClient {

    private val connectMutex = Mutex()
    private val router = ClaudePInboundRouter(::onInboundViolation)

    private val _connectionState = MutableStateFlow(ClaudePConnectionState.DISCONNECTED)

    /** Current lifecycle state. Drives the settings screen's "connecting / online / offline". */
    val connectionState: StateFlow<ClaudePConnectionState> = _connectionState.asStateFlow()

    private val _lastError = MutableStateFlow<ClaudePErrorCode?>(null)

    /** Most recent bounded failure, or `null` when the last outcome was not an error. */
    val lastError: StateFlow<ClaudePErrorCode?> = _lastError.asStateFlow()

    @Volatile
    private var session: ClaudePWebSocketSession? = null

    @Volatile
    private var readerJob: Job? = null

    @Volatile
    private var negotiatedHello: ClaudePServerHelloBody? = null

    /** Server-advertised outbound frame cap, clamped by our own hard ceiling. */
    @Volatile
    private var outboundFrameLimit: Int = ClaudePTransportLimits.HARD_MAX_OUTBOUND_FRAME_BYTES

    private val startGenerationCalls = AtomicInteger(0)
    private val remoteDispatches = AtomicInteger(0)
    private val cancelCalls = AtomicInteger(0)
    private val toolResultCalls = AtomicInteger(0)

    private val idempotencyLock = Any()
    private val idempotency = LinkedHashMap<String, IdempotencyEntry>()

    private val cancelLock = Any()
    private val cancelledGenerations = LinkedHashSet<String>()

    override val startGenerationCallCount: Int get() = startGenerationCalls.get()
    override val remoteDispatchCount: Int get() = remoteDispatches.get()
    override val cancelCallCount: Int get() = cancelCalls.get()
    override val toolResultCallCount: Int get() = toolResultCalls.get()

    /** The negotiated `server.hello`, once a handshake has completed. */
    val serverHello: ClaudePServerHelloBody? get() = negotiatedHello

    // -----------------------------------------------------------------------------------------
    // ClaudePGatewayClient
    // -----------------------------------------------------------------------------------------

    /**
     * Performs the handshake.
     *
     * [request]'s `nonce`, `signature` and `device_id` are **replaced**: those three must come from
     * the credential store and the Keystore key, and a caller has access to neither. The remaining
     * fields — app version, offered protocol versions, capabilities — are the caller's to choose.
     */
    override suspend fun hello(request: ClaudePClientHelloBody): ClaudePServerHelloBody {
        val ready = ensureSession()
        val key = deviceKeyStore.loadExisting(keyAlias)
            ?: throw failClosed(
                ClaudePConnectionState.CREDENTIAL_INVALID,
                ClaudePErrorCode.DEVICE_REVOKED,
            )
        val access = currentAccessOrNull()
            ?: throw failClosed(
                ClaudePConnectionState.CREDENTIAL_INVALID,
                ClaudePErrorCode.AUTHENTICATION_REQUIRED,
            )

        val nonce = ClaudePRandom.nonce()
        val transcript = ClaudePHandshakeTranscript.build(
            deviceId = access.deviceId,
            nonce = nonce,
            gatewayAuthority = endpoint.authority,
            appVersion = request.appVersion,
        )
        val signature = key.sign(transcript)
            ?: throw failClosed(
                ClaudePConnectionState.CREDENTIAL_INVALID,
                ClaudePErrorCode.DEVICE_REVOKED,
            )

        val body = request.copy(
            deviceId = access.deviceId,
            nonce = nonce,
            signature = ClaudePRandom.base64Url(signature),
        )

        val raw = sendRpc(
            ready = ready,
            type = ClaudePEventType.CLIENT_HELLO,
            body = ClaudePProtocol.json.encodeToJsonElement(body) as JsonObject,
        ) ?: throw ClaudePGatewayException(ClaudePErrorCode.STREAM_INTERRUPTED)

        val event = (ClaudePProtocol.parseInbound(raw) as? ClaudePInbound.Event)?.event
        // A refused handshake answers with a bounded error rather than a hello. Reporting its own
        // code — `device_revoked` rather than a generic protocol failure — is what lets the settings
        // screen tell the user to re-pair instead of to upgrade.
        if (event is ClaudePServerEvent.Failed) {
            throw failClosed(credentialOrProtocolState(event.body.safeCode), event.body.safeCode)
        }
        val helloEvent = event as? ClaudePServerEvent.ServerHello
            ?: throw failClosed(
                ClaudePConnectionState.PROTOCOL_ERROR,
                ClaudePErrorCode.PROTOCOL_MISMATCH,
            )

        // The gateway chooses the version; we only confirm it is one we understand. A mismatch is
        // stable and terminal — a gateway speaking another major may have changed the meaning of
        // event types we think we recognise, so there is nothing safe to do but stop.
        if (!ClaudePProtocol.acceptsServerProtocolVersion(helloEvent.body.protocolVersion)) {
            throw failClosed(
                ClaudePConnectionState.PROTOCOL_ERROR,
                ClaudePErrorCode.PROTOCOL_MISMATCH,
            )
        }

        negotiatedHello = helloEvent.body
        outboundFrameLimit = clampFrameLimit(helloEvent.body.maxFrameBytes)
        _connectionState.value = ClaudePConnectionState.READY
        _lastError.value = null
        return helloEvent.body
    }

    override suspend fun catalog(): ClaudePCatalogResultBody {
        val ready = ensureSession()
        val raw = sendRpc(ready, ClaudePEventType.CATALOG_GET, JsonObject(emptyMap()))
            ?: throw ClaudePGatewayException(ClaudePErrorCode.STREAM_INTERRUPTED)
        val event = (ClaudePProtocol.parseInbound(raw) as? ClaudePInbound.Event)?.event
            ?: throw ClaudePGatewayException(ClaudePErrorCode.PROTOCOL_MISMATCH)
        return (event as? ClaudePServerEvent.CatalogResult)?.body
            ?: throw ClaudePGatewayException(ClaudePErrorCode.PROTOCOL_MISMATCH)
    }

    override suspend fun startGeneration(
        requestId: String,
        fingerprint: String,
        body: ClaudePGenerationStartBody,
    ): ClaudePGenerationHandle {
        startGenerationCalls.incrementAndGet()

        // §7 idempotency, enforced locally as well as remotely. Reusing a request id with the same
        // fingerprint returns the original handle *without* touching the socket, which is what makes
        // "one logical request dispatches once" true even if a caller replays a start.
        synchronized(idempotencyLock) {
            idempotency[requestId]?.let { existing ->
                if (existing.fingerprint != fingerprint) {
                    throw ClaudePGatewayException(
                        ClaudePErrorCode.IDEMPOTENCY_CONFLICT,
                        generationId = existing.generationId,
                    )
                }
                return existing.handle
            }
        }

        val ready = ensureSession()
        val waiter = router.openStart(requestId)
            ?: throw ClaudePGatewayException(ClaudePErrorCode.WORKER_BUSY)

        val frame = clientFrame(
            type = ClaudePEventType.GENERATION_START,
            requestId = requestId,
            body = ClaudePProtocol.json.encodeToJsonElement(body) as JsonObject,
        )
        if (!sendSafely(ready, frame)) {
            router.abandonRpc(requestId)
            throw ClaudePGatewayException(ClaudePErrorCode.STREAM_INTERRUPTED)
        }

        val outcome = withTimeoutOrNull(ClaudePTransportLimits.RPC_TIMEOUT_MILLIS) { waiter.await() }
        if (outcome == null) {
            router.abandonRpc(requestId)
            throw ClaudePGatewayException(ClaudePErrorCode.TIMEOUT)
        }

        val accepted = when (outcome) {
            // A refusal is surfaced with its own bounded code. Collapsing it into a generic failure
            // would hide the difference between "this model is not allowed" and "the device was
            // revoked", which are the two errors a user can actually act on differently.
            is ClaudePStartOutcome.Rejected -> throw ClaudePGatewayException(outcome.code)
            is ClaudePStartOutcome.Accepted -> outcome.body
        }

        val stream = router.streamOf(accepted.generationId)
            ?: throw ClaudePGatewayException(ClaudePErrorCode.WORKER_BUSY)

        // Counted only after the gateway accepted: the counter is read by tests (and by the CP1-A
        // provider contract) as "how many model runs actually started".
        remoteDispatches.incrementAndGet()

        val handle = WssGenerationHandle(
            generationId = accepted.generationId,
            requestId = requestId,
            acceptedEventSeq = accepted.acceptedSeq,
            frames = stream.frames,
        )
        synchronized(idempotencyLock) {
            if (idempotency.size >= MAX_IDEMPOTENCY_ENTRIES) {
                idempotency.remove(idempotency.keys.first())
            }
            idempotency[requestId] = IdempotencyEntry(fingerprint, accepted.generationId, handle)
        }
        return handle
    }

    /**
     * `generation.cancel`.
     *
     * ### Why this does not await a reply
     *
     * `claudep/02-wire-protocol-v1.md` §8 defines no cancel-acknowledgement event: the answer to a
     * cancel *is* the generation terminal, which arrives on the generation's own stream where
     * [ClaudePTerminalGate] already adjudicates it. Inventing a reply type here would add wire
     * behaviour the frozen protocol does not have. So this method sends at most one cancel and then
     * reports what is *provable*:
     *
     * - the generation already reached a terminal → that terminal, unchanged, with no RPC sent
     *   (§8: "已终态返回原终态，不新增事件");
     * - otherwise → [ClaudePCancelOutcome.Pending], because §8 forbids claiming a cancellation we
     *   cannot prove just because we asked for one.
     *
     * The terminal still reaches the consumer through the stream, so nothing is lost by not blocking
     * here — and the caller is never held for a round trip it does not need.
     */
    override suspend fun cancel(
        generationId: String,
        reason: ClaudePCancelReason,
    ): ClaudePCancelOutcome {
        alreadyTerminalOrNull(generationId)?.let { return ClaudePCancelOutcome.Terminal(it) }

        // At most one cancel RPC per generation, enforced here as well as by the provider's
        // `ClaudePGenerationAttempt`. Belt and braces: this is the layer that owns the socket.
        val firstCancel = synchronized(cancelLock) {
            if (cancelledGenerations.size >= MAX_IDEMPOTENCY_ENTRIES) {
                cancelledGenerations.remove(cancelledGenerations.first())
            }
            cancelledGenerations.add(generationId)
        }
        if (!firstCancel) return ClaudePCancelOutcome.Pending

        val ready = ensureSession()
        cancelCalls.incrementAndGet()

        val body = buildJsonObject {
            put("generation_id", generationId)
            put("reason", reason.wireValue)
        }
        sendSafely(
            ready,
            clientFrame(type = ClaudePEventType.GENERATION_CANCEL, requestId = ClaudePRandom.nonce(), body = body),
        )

        return alreadyTerminalOrNull(generationId)?.let { ClaudePCancelOutcome.Terminal(it) }
            ?: ClaudePCancelOutcome.Pending
    }

    /**
     * The terminal this generation already reached, if any.
     *
     * Delegates to the router, which remembers recent endings even after a generation's buffer has
     * been retired — otherwise a cancel arriving just after a completion would find nothing and
     * wrongly report "pending" for a generation that had already finished.
     */
    private fun alreadyTerminalOrNull(generationId: String): ClaudePTerminalKind? =
        router.terminalKindOf(generationId)

    /**
     * `tool.result` — the answer to one `tool.invoke`.
     *
     * Sent, not awaited. The Server applies it to the call it is holding and replies with
     * nothing: there is no `tool.result.result`, so a request id here would open a waiter that
     * nothing will ever complete. That is also why this does not go through [sendRpc].
     *
     * The generation id travels in the **envelope**, which is where the Server reads it from —
     * a tool frame whose envelope carries no generation is closed with 1002 rather than guessed
     * at. [sendSafely] returning false (an oversized frame, or a socket that went away) is not
     * retried: the call's own deadline is the backstop, and re-sending a tool outcome on a
     * guessed-at connection is how one answer becomes two.
     *
     * ## What the counter means
     *
     * [toolResultCallCount] counts frames that **reached the socket**, not attempts. A send that
     * was refused as oversized and a send that threw because the connection went away both leave
     * it unchanged, which is what makes it usable as evidence that a tool outcome was actually
     * reported rather than merely attempted. A counter that moved on a failed send would let a
     * test prove "we tried" while the Server was never told anything — and the Server's deadline,
     * not this side, is what decides the call in that case.
     */
    override suspend fun sendToolResult(generationId: String, body: ClaudePToolResultBody) {
        val ready = ensureSession()
        val sent = sendSafely(
            ready,
            clientFrame(
                type = ClaudePEventType.TOOL_RESULT,
                requestId = null,
                generationId = generationId,
                body = ClaudePProtocol.json.encodeToJsonElement(body).jsonObject,
            ),
        )
        if (sent) toolResultCalls.incrementAndGet()
    }

    /**
     * `tool.query` — ask what the Server holds for one call.
     *
     * Also not an RPC. The answer comes back as a `tool.query.result` event on the generation's
     * own stream, so that it is ordered with everything else and so that a reconnect replaying
     * frames cannot deliver an answer that skipped the replay.
     *
     * It does **not** touch [toolResultCallCount]. A query asks what the Server holds; it is not
     * Android answering a call, and counting it as one would inflate the only number that says
     * how many tool outcomes this side actually reported.
     */
    override suspend fun queryToolCall(generationId: String, body: ClaudePToolQueryBody) {
        val ready = ensureSession()
        sendSafely(
            ready,
            clientFrame(
                type = ClaudePEventType.TOOL_QUERY,
                requestId = null,
                generationId = generationId,
                body = ClaudePProtocol.json.encodeToJsonElement(body).jsonObject,
            ),
        )
    }

    override suspend fun receipt(generationId: String): ClaudePReceiptBody {
        val ready = ensureSession()
        val body = buildJsonObject { put("generation_id", generationId) }
        val raw = sendRpc(ready, ClaudePEventType.RECEIPT_QUERY, body)
            ?: throw ClaudePGatewayException(ClaudePErrorCode.STREAM_INTERRUPTED)
        val event = (ClaudePProtocol.parseInbound(raw) as? ClaudePInbound.Event)?.event
        return (event as? ClaudePServerEvent.ReceiptResult)?.body
            ?: throw ClaudePGatewayException(ClaudePErrorCode.PROTOCOL_MISMATCH)
    }

    /**
     * `stream.resume` — reconnect and replay, never re-dispatch.
     *
     * **Replayed frames arrive on the generation's stream, not in the return value.** The frozen
     * `stream.resume.result` body (`claudep/02-wire-protocol-v1.md` §9) carries an outcome, a state
     * and counters — no frames — so the gateway pushes the buffered events as ordinary generation
     * events and they land in the [ClaudePBoundedGenerationStream] already open for that generation.
     * [ClaudePResumeResult.Replayed.frames] is therefore empty for this transport; the fake gateway,
     * which has no socket, returns them inline instead. Both satisfy the same interface, and the
     * difference is recorded as a CP2 integration note.
     */
    override suspend fun resume(generationId: String, lastEventSeq: Long): ClaudePResumeResult {
        val ready = ensureSession()
        val body = buildJsonObject {
            put("generation_id", generationId)
            put("last_event_seq", lastEventSeq)
        }
        val raw = sendRpc(
            ready = ready,
            type = ClaudePEventType.STREAM_RESUME,
            body = body,
            generationId = generationId,
        ) ?: return ClaudePResumeResult.StateUnknown

        val event = (ClaudePProtocol.parseInbound(raw) as? ClaudePInbound.Event)?.event
        val resumeEvent = event as? ClaudePServerEvent.StreamResumeResult
            ?: return ClaudePResumeResult.StateUnknown

        return when (resumeEvent.body.safeOutcome) {
            ClaudePResumeKind.REPLAYED ->
                ClaudePResumeResult.Replayed(ClaudePResumeKind.REPLAYED, emptyList())

            ClaudePResumeKind.TERMINAL ->
                ClaudePResumeResult.Terminal(
                    ClaudePReceiptBody(
                        generationId = generationId,
                        state = resumeEvent.body.state,
                        errorCode = resumeEvent.body.errorCode,
                        lastEventSeq = resumeEvent.body.lastEventSeq,
                        usage = resumeEvent.body.usage,
                    ),
                )

            // `active` means the stream is live again; `state_unknown` means the gateway cannot prove
            // a terminal. Neither is a replay, and neither may trigger an automatic retry.
            ClaudePResumeKind.ACTIVE,
            ClaudePResumeKind.STATE_UNKNOWN,
            -> ClaudePResumeResult.StateUnknown
        }
    }

    // -----------------------------------------------------------------------------------------
    // Connection lifecycle
    // -----------------------------------------------------------------------------------------

    /**
     * Returns a usable session, connecting (with the bounded retry budget) if necessary.
     *
     * A failure here has already moved the client into a stable failure state, so the exception it
     * throws is a report of that state rather than a prompt to try again.
     */
    private suspend fun ensureSession(): ClaudePWebSocketSession {
        session?.let { if (_connectionState.value == ClaudePConnectionState.READY) return it }

        if (_connectionState.value.isStableFailure) {
            throw ClaudePGatewayException(_lastError.value ?: ClaudePErrorCode.STREAM_INTERRUPTED)
        }

        return connectMutex.withLock {
            session?.let { if (_connectionState.value == ClaudePConnectionState.READY) return it }
            if (_connectionState.value.isStableFailure) {
                throw ClaudePGatewayException(_lastError.value ?: ClaudePErrorCode.STREAM_INTERRUPTED)
            }
            connectWithRetriesLocked()
        }
    }

    /** Caller must hold [connectMutex]. */
    private suspend fun connectWithRetriesLocked(): ClaudePWebSocketSession {
        var attempt = 0
        while (true) {
            when (val outcome = attemptConnectLocked()) {
                is ClaudePConnectOutcome.Connected -> {
                    _connectionState.value = ClaudePConnectionState.READY
                    _lastError.value = null
                    return outcome.session
                }

                is ClaudePConnectOutcome.Failed -> {
                    if (_connectionState.value.isStableFailure) {
                        throw ClaudePGatewayException(
                            _lastError.value ?: ClaudePErrorCode.STREAM_INTERRUPTED,
                        )
                    }
                    attempt += 1
                    val backoff = reconnectPolicy.delayMillisFor(attempt)
                    if (backoff == null) {
                        _connectionState.value = ClaudePConnectionState.OFFLINE
                        _lastError.value = ClaudePErrorCode.STREAM_INTERRUPTED
                        throw ClaudePGatewayException(ClaudePErrorCode.STREAM_INTERRUPTED)
                    }
                    _connectionState.value = ClaudePConnectionState.RECONNECTING
                    sleeper(backoff)
                }
            }
        }
    }

    /** Caller must hold [connectMutex]. Performs exactly one connect attempt plus handshake. */
    private suspend fun attemptConnectLocked(): ClaudePConnectOutcome {
        _connectionState.value = ClaudePConnectionState.CONNECTING

        val access = currentAccessOrNull()
            ?: return failConnect(
                ClaudePConnectionState.CREDENTIAL_INVALID,
                ClaudePErrorCode.AUTHENTICATION_REQUIRED,
            )
        val key = deviceKeyStore.loadExisting(keyAlias)
            ?: return failConnect(
                ClaudePConnectionState.CREDENTIAL_INVALID,
                ClaudePErrorCode.DEVICE_REVOKED,
            )
        // The key is only needed to prove possession during the handshake; holding it here means a
        // key that disappears between connections is caught before a socket is opened.
        if (key.publicKeyDer() == null) {
            return failConnect(
                ClaudePConnectionState.CREDENTIAL_INVALID,
                ClaudePErrorCode.DEVICE_REVOKED,
            )
        }

        val outcome = connector.connect(
            ClaudePConnectRequest(url = endpoint.streamUrl(), credential = access.credential),
        )
        if (outcome is ClaudePConnectOutcome.Failed) {
            return outcome
        }

        val connected = (outcome as ClaudePConnectOutcome.Connected).session

        // Strict subprotocol validation. OkHttp does not enforce that the server echoed one of the
        // offered protocols, and a server that selected nothing may be a proxy that stripped the
        // header rather than a Claude P gateway at all.
        if (connected.selectedSubprotocol != ClaudePProtocol.SUBPROTOCOL) {
            connected.close(WS_CLOSE_POLICY_VIOLATION)
            return failConnect(
                ClaudePConnectionState.PROTOCOL_ERROR,
                ClaudePErrorCode.PROTOCOL_MISMATCH,
            )
        }

        session = connected
        readerJob = scope.launch { readLoop(connected) }
        _connectionState.value = ClaudePConnectionState.HANDSHAKING

        // The handshake is driven by the caller (`hello`), so a connect that succeeds but is never
        // followed by a hello leaves the client in HANDSHAKING rather than pretending to be ready.
        return ClaudePConnectOutcome.Connected(connected)
    }

    private suspend fun readLoop(active: ClaudePWebSocketSession) {
        try {
            active.incoming().collect { event ->
                when (event) {
                    is ClaudePTransportEvent.Text -> router.route(event.text)

                    // An oversized, binary or non-UTF8 frame means the peer is not speaking the
                    // protocol. Frames the protocol layer rejects are handled inside `route`.
                    is ClaudePTransportEvent.FrameRejected -> onInboundViolation(
                        when (event.reason) {
                            ClaudePFrameRejection.TOO_LARGE -> ClaudePInboundViolation.MALFORMED_FRAME
                            ClaudePFrameRejection.BINARY_FRAME -> ClaudePInboundViolation.MALFORMED_FRAME
                            ClaudePFrameRejection.NOT_UTF8 -> ClaudePInboundViolation.MALFORMED_FRAME
                        },
                    )

                    is ClaudePTransportEvent.Closed -> Unit
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A throwing connector is a dead socket, not a crash of the app. The reconnect path
            // below is what turns it into a user-visible state.
        } finally {
            onSocketEnded(active)
        }
    }

    /**
     * Reacts to a socket ending.
     *
     * A generation still in flight is *not* abandoned here. `claudep/01-architecture-and-trust-boundaries.md`
     * §6 makes "Android disconnected" explicitly different from "cancelled", so instead of ending the
     * stream the client reconnects and replays it. Only when the reconnect budget is spent does the
     * generation end, and then with a bounded failure rather than by silently stopping.
     */
    private fun onSocketEnded(ended: ClaudePWebSocketSession) {
        if (session !== ended) return
        session = null
        readerJob = null

        if (_connectionState.value.isStableFailure) return

        if (router.trackedGenerationCount == 0) {
            _connectionState.value = ClaudePConnectionState.DISCONNECTED
            return
        }

        _connectionState.value = ClaudePConnectionState.RECONNECTING
        scope.launch { recoverGenerations() }
    }

    private suspend fun recoverGenerations() {
        val ready = try {
            ensureSession()
        } catch (_: ClaudePGatewayException) {
            null
        }
        if (ready == null) {
            _connectionState.value = ClaudePConnectionState.OFFLINE
            router.failEverything(ClaudePErrorCode.STREAM_INTERRUPTED)
            return
        }

        // Replay only. `generation.start` is never re-sent, so a reconnect cannot buy a second model
        // run no matter how many times the socket drops.
        router.trackedGenerationIds().forEach { generationId ->
            val stream = router.streamOf(generationId) ?: return@forEach
            when (val resumed = resume(generationId, stream.lastEventSeq)) {
                is ClaudePResumeResult.Replayed -> Unit

                is ClaudePResumeResult.Terminal -> router.closeGeneration(
                    generationId = generationId,
                    syntheticTerminal = claudePLocalTerminalFrame(
                        generationId = generationId,
                        sequence = stream.lastEventSeq + 1,
                        code = terminalCodeFor(resumed.receipt),
                    ),
                    terminalKind = resumed.receipt.safeState.toTerminalKindOrNull() ?: ClaudePTerminalKind.FAILED,
                )

                ClaudePResumeResult.StateUnknown -> router.closeGeneration(
                    generationId = generationId,
                    syntheticTerminal = claudePLocalTerminalFrame(
                        generationId = generationId,
                        sequence = stream.lastEventSeq + 1,
                        code = ClaudePErrorCode.STREAM_INTERRUPTED,
                    ),
                    // The gateway could not prove a terminal, so the honest local ending is a
                    // failure — never a fabricated success or cancellation.
                    terminalKind = ClaudePTerminalKind.FAILED,
                )
            }
            if (_connectionState.value.isStableFailure) return@forEach
        }
    }

    // -----------------------------------------------------------------------------------------
    // RPC plumbing
    // -----------------------------------------------------------------------------------------

    /**
     * Sends one RPC and awaits its result frame.
     *
     * Returns `null` on send failure or timeout rather than throwing, because every caller turns that
     * into a typed [ClaudePGatewayException] with a code appropriate to the operation.
     */
    private suspend fun sendRpc(
        ready: ClaudePWebSocketSession,
        type: String,
        body: JsonObject,
        generationId: String? = null,
    ): String? {
        val requestId = ClaudePRandom.nonce()
        val waiter = router.openRpc(requestId) ?: throw ClaudePGatewayException(ClaudePErrorCode.WORKER_BUSY)

        val frame = clientFrame(
            type = type,
            requestId = requestId,
            generationId = generationId,
            body = body,
        )
        if (!sendSafely(ready, frame)) {
            router.abandonRpc(requestId)
            return null
        }

        val raw = withTimeoutOrNull(ClaudePTransportLimits.RPC_TIMEOUT_MILLIS) { waiter.await() }
        if (raw == null) router.abandonRpc(requestId)
        return raw
    }

    /**
     * Sends a frame, applying the outbound size cap.
     *
     * The limit is the *smaller* of what the server advertised in `server.hello` and our own hard
     * ceiling. A server that advertises an absurdly large maximum does not get to raise the amount of
     * memory and bandwidth we are willing to spend on one frame.
     */
    private suspend fun sendSafely(ready: ClaudePWebSocketSession, frame: String): Boolean {
        if (frame.toByteArray(Charsets.UTF_8).size > outboundFrameLimit) return false
        return try {
            ready.send(frame)
            true
        } catch (_: ClaudePTransportException) {
            false
        }
    }

    private fun clientFrame(
        type: String,
        requestId: String?,
        generationId: String? = null,
        sequence: Long? = null,
        body: JsonObject = JsonObject(emptyMap()),
    ): String = ClaudePProtocol.json.encodeToString(
        ClaudePEnvelope(
            protocol = ClaudePProtocol.PROTOCOL_ID,
            type = type,
            connectionId = negotiatedHello?.connectionId,
            requestId = requestId,
            generationId = generationId,
            sequence = sequence,
            sentAt = null,
            body = body,
        ),
    )

    private suspend fun currentAccessOrNull(): ClaudePDeviceAccess? {
        val access = accessProvider.currentAccess(nowEpochSeconds()) ?: return null
        if (!ClaudePAccessCredential.isUsable(access.credential)) return null
        if (access.isExpired(nowEpochSeconds())) return null
        return access
    }

    /**
     * Handles a frame the client must not continue past.
     *
     * Every such frame is treated as fatal for the *connection*: the socket is closed and the state
     * moves to a stable failure, so nothing reconnects into a gateway that is speaking a protocol we
     * cannot reason about. In-flight generations end with a bounded terminal via
     * [ClaudePInboundRouter.failEverything] rather than hanging.
     */
    private fun onInboundViolation(violation: ClaudePInboundViolation) {
        val code = violation.toErrorCode()
        _lastError.value = code
        _connectionState.value = ClaudePConnectionState.PROTOCOL_ERROR

        val active = session
        session = null
        if (active != null) {
            scope.launch { active.close(WS_CLOSE_POLICY_VIOLATION) }
        }
        router.failEverything(code)
    }

    private fun failConnect(state: ClaudePConnectionState, code: ClaudePErrorCode): ClaudePConnectOutcome {
        _connectionState.value = state
        _lastError.value = code
        return ClaudePConnectOutcome.Failed(ClaudePTransportFailure.CONNECT_FAILED)
    }

    private fun failClosed(state: ClaudePConnectionState, code: ClaudePErrorCode): ClaudePGatewayException {
        _connectionState.value = state
        _lastError.value = code
        return ClaudePGatewayException(code)
    }

    /** Closes the socket and stops tracking everything. Leaves the client reusable. */
    suspend fun shutdown() {
        val active = session
        session = null
        readerJob = null
        negotiatedHello = null
        router.failEverything(ClaudePErrorCode.STREAM_INTERRUPTED)
        active?.close(WS_CLOSE_NORMAL)
        _connectionState.value = ClaudePConnectionState.DISCONNECTED
    }

    private fun clampFrameLimit(serverLimit: Int): Int = when {
        serverLimit <= 0 -> ClaudePTransportLimits.HARD_MAX_OUTBOUND_FRAME_BYTES
        serverLimit > ClaudePTransportLimits.HARD_MAX_OUTBOUND_FRAME_BYTES ->
            ClaudePTransportLimits.HARD_MAX_OUTBOUND_FRAME_BYTES

        else -> serverLimit
    }

    private data class IdempotencyEntry(
        val fingerprint: String,
        val generationId: String,
        val handle: ClaudePGenerationHandle,
    )

    private companion object {
        /** `claudep/02-wire-protocol-v1.md` §7 caps how much history we retain for idempotency. */
        const val MAX_IDEMPOTENCY_ENTRIES = 64

        const val WS_CLOSE_NORMAL = 1000
        const val WS_CLOSE_POLICY_VIOLATION = 1008
    }
}

/** Handle for one generation accepted over the wire. */
private class WssGenerationHandle(
    override val generationId: String,
    override val requestId: String,
    override val acceptedEventSeq: Long,
    private val frames: kotlinx.coroutines.flow.Flow<String>,
) : ClaudePGenerationHandle {
    override fun frames(): kotlinx.coroutines.flow.Flow<String> = frames
}

/**
 * Picks the stable connection state a handshake refusal leaves behind.
 *
 * Only the two codes that mean "this device is not welcome anymore" produce
 * [ClaudePConnectionState.CREDENTIAL_INVALID]; everything else is a protocol-level refusal. The
 * distinction matters because only the former tells the user to re-pair.
 */
private fun credentialOrProtocolState(code: ClaudePErrorCode): ClaudePConnectionState = when (code) {
    ClaudePErrorCode.DEVICE_REVOKED,
    ClaudePErrorCode.AUTHENTICATION_REQUIRED,
    -> ClaudePConnectionState.CREDENTIAL_INVALID

    else -> ClaudePConnectionState.PROTOCOL_ERROR
}

/** Maps a receipt state onto the terminal kind it implies, or `null` when it implies none. */
private fun ClaudePGenerationState.toTerminalKindOrNull(): ClaudePTerminalKind? = when (this) {
    ClaudePGenerationState.COMPLETED -> ClaudePTerminalKind.COMPLETED
    ClaudePGenerationState.CANCELLED -> ClaudePTerminalKind.CANCELLED
    ClaudePGenerationState.FAILED -> ClaudePTerminalKind.FAILED
    ClaudePGenerationState.ACCEPTED,
    ClaudePGenerationState.ACTIVE,
    ClaudePGenerationState.UNKNOWN,
    -> null
}

/** Maps a receipt to the bounded error a locally generated terminal should carry. */
private fun terminalCodeFor(receipt: ClaudePReceiptBody): ClaudePErrorCode = when (receipt.safeState) {
    ClaudePGenerationState.CANCELLED -> ClaudePErrorCode.CANCELLED
    ClaudePGenerationState.FAILED -> receipt.safeErrorCode ?: ClaudePErrorCode.EXTERNAL_RUNTIME_ERROR
    else -> ClaudePErrorCode.STREAM_INTERRUPTED
}

/** True for states nothing leaves without an explicit user action or a fresh request. */
internal val ClaudePConnectionState.isStableFailure: Boolean
    get() = this == ClaudePConnectionState.PROTOCOL_ERROR ||
        this == ClaudePConnectionState.CREDENTIAL_INVALID ||
        this == ClaudePConnectionState.OFFLINE
