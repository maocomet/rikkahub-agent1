package me.rerere.ai.provider.claudep

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.decodeFromString

/**
 * Correlates inbound frames to the request or generation waiting for them.
 *
 * The client multiplexes every RPC and every generation stream over one socket, so a frame is only
 * meaningful once it has been matched to a pending waiter. Frames that match nothing are not errors —
 * a late response to a timed-out RPC, or a generation this connection no longer tracks, is simply not
 * ours — and are dropped rather than treated as a protocol violation.
 *
 * ### Three correlation buckets
 *
 * - **RPC results** (`server.hello`, `catalog.result`, `receipt.result`, `stream.resume.result`) are
 *   matched by `request_id`. Only these types are ever treated as a reply, which is deliberate: a
 *   generation event that happens to carry a `request_id` must not be swallowed as a reply, or the
 *   stream would silently lose its own first frame.
 * - **`generation.accepted`** is matched against a pending `generation.start` by `request_id`, and at
 *   the same time opens the generation's buffer and is delivered into it. It is both the reply to the
 *   start RPC *and* the first event of the stream, and `claudep/02-wire-protocol-v1.md` §9 makes the
 *   client's recovery position (`accepted_event_seq`) depend on it.
 * - **Every other generation event** is matched by `generation_id` alone.
 *
 * ### Unknown events: optional versus required
 *
 * `claudep/02-wire-protocol-v1.md` §1 permits ignoring unknown *optional* events and forbids ignoring
 * an unknown *required* one, but does not label which is which. This router draws the line by
 * namespace:
 *
 * - An unknown type in the `generation.` namespace is **refused**. Those events carry generation
 *   lifecycle, so an unrecognised one could be the terminal; dropping it would leave a generation
 *   that never ends, and a silent hang is strictly worse than a visible failure.
 * - An unknown type anywhere else is **dropped**. It can neither end a generation nor produce
 *   content, so ignoring it is safe, and the relative order of the events around it is preserved
 *   either way. Phase 3's `tool.*` events land here, which is the intent: they are additive.
 */
internal class ClaudePInboundRouter(
    private val onViolation: (ClaudePInboundViolation) -> Unit,
) {
    private val lock = Any()
    private val pendingRpcs = mutableMapOf<String, CompletableDeferred<String>>()
    private val pendingStarts = mutableMapOf<String, CompletableDeferred<ClaudePStartOutcome>>()
    private val generations = mutableMapOf<String, ClaudePBoundedGenerationStream>()

    /**
     * Terminals of generations whose buffer has already been retired.
     *
     * A generation is removed from [generations] the moment its terminal is delivered, so the
     * stream can no longer answer "what did it end as?". `claudep/02-wire-protocol-v1.md` §8 still
     * requires a cancel that arrives afterwards to return the *original* terminal and append no
     * event, so that answer has to outlive the buffer. Bounded, and evicted oldest-first: this is a
     * short-lived recall of recent endings, not a history.
     */
    private val recentTerminals = LinkedHashMap<String, ClaudePTerminalKind>()

    val pendingRpcCount: Int
        get() = synchronized(lock) { pendingRpcs.size + pendingStarts.size }

    val trackedGenerationCount: Int
        get() = synchronized(lock) { generations.size }

    fun trackedGenerationIds(): List<String> = synchronized(lock) { generations.keys.toList() }

    /**
     * Registers a waiter for a plain RPC result.
     *
     * Returns `null` when the in-flight budget is spent or the id is already registered — both mean
     * "do not send". Bounding in-flight RPCs is what stops a client that retries in a loop from
     * growing a map without limit.
     */
    fun openRpc(requestId: String): CompletableDeferred<String>? = synchronized(lock) {
        if (requestId.isBlank()) return null
        if (pendingRpcs.size + pendingStarts.size >= ClaudePTransportLimits.MAX_PENDING_RPCS) return null
        if (pendingRpcs.containsKey(requestId) || pendingStarts.containsKey(requestId)) return null
        CompletableDeferred<String>().also { pendingRpcs[requestId] = it }
    }

    /**
     * Registers a waiter for the outcome of a `generation.start`.
     *
     * The outcome is a sealed type rather than a bare accepted-body because a start can legitimately
     * be *refused* — an unknown model alias, a revoked device, a busy worker — and a refusal must
     * reach the caller as its bounded error code instead of being dropped as an unroutable frame.
     */
    fun openStart(requestId: String): CompletableDeferred<ClaudePStartOutcome>? =
        synchronized(lock) {
            if (requestId.isBlank()) return null
            if (pendingRpcs.size + pendingStarts.size >= ClaudePTransportLimits.MAX_PENDING_RPCS) return null
            if (pendingRpcs.containsKey(requestId) || pendingStarts.containsKey(requestId)) return null
            CompletableDeferred<ClaudePStartOutcome>().also { pendingStarts[requestId] = it }
        }

    /** Removes a waiter without completing it, e.g. after a timeout. */
    fun abandonRpc(requestId: String) {
        synchronized(lock) {
            pendingRpcs.remove(requestId)
            pendingStarts.remove(requestId)
        }
    }

    /**
     * Registers a bounded buffer for [generationId].
     *
     * Returns `null` when the tracked-generation budget is spent, or when the id is already tracked —
     * a gateway reusing a live generation id is refused rather than merged into the existing stream.
     */
    fun openGeneration(generationId: String): ClaudePBoundedGenerationStream? = synchronized(lock) {
        if (generationId.isBlank()) return null
        if (generations.size >= ClaudePTransportLimits.MAX_TRACKED_GENERATIONS) return null
        if (generations.containsKey(generationId)) return null
        // A reused id starts clean: a stale recall of a previous ending must not answer for it.
        recentTerminals.remove(generationId)
        ClaudePBoundedGenerationStream(generationId).also { generations[generationId] = it }
    }

    fun streamOf(generationId: String): ClaudePBoundedGenerationStream? =
        synchronized(lock) { generations[generationId] }

    /**
     * The terminal [generationId] reached, live buffer first, retired recall second.
     *
     * Used by `cancel` to answer "was this already over?" without sending anything.
     */
    fun terminalKindOf(generationId: String): ClaudePTerminalKind? =
        streamOf(generationId)?.terminalKind ?: synchronized(lock) { recentTerminals[generationId] }

    /** Stops tracking a generation and ends its stream. */
    fun closeGeneration(
        generationId: String,
        syntheticTerminal: String? = null,
        terminalKind: ClaudePTerminalKind? = null,
    ) {
        terminalKind?.let { recordTerminal(generationId, it) }
        val stream = synchronized(lock) { generations.remove(generationId) } ?: return
        stream.finish(syntheticTerminal)
    }

    private fun recordTerminal(generationId: String, kind: ClaudePTerminalKind) {
        synchronized(lock) {
            if (recentTerminals.size >= MAX_REMEMBERED_TERMINALS) {
                recentTerminals.remove(recentTerminals.keys.first())
            }
            recentTerminals[generationId] = kind
        }
    }

    /**
     * Ends every stream and fails every waiter.
     *
     * Called when the connection can no longer recover. Generation streams end with a bounded
     * terminal so a consumer sees a failure instead of a stream that merely stops; RPC waiters are
     * failed with [ClaudePTransportException] so no coroutine is left suspended forever.
     */
    fun failEverything(code: ClaudePErrorCode) {
        val (streams, rpcs, starts) = synchronized(lock) {
            val s = generations.values.toList()
            generations.clear()
            val r = pendingRpcs.values.toList()
            pendingRpcs.clear()
            val st = pendingStarts.values.toList()
            pendingStarts.clear()
            Triple(s, r, st)
        }
        streams.forEach { stream ->
            // Recorded so a cancel arriving after the connection died still gets "this is over"
            // rather than an indefinite "pending" for a generation that can never continue.
            recordTerminal(stream.generationId, ClaudePTerminalKind.FAILED)
            stream.finish(
                claudePLocalTerminalFrame(
                    generationId = stream.generationId,
                    sequence = stream.lastEventSeq + 1,
                    code = code,
                ),
            )
        }
        val failure = ClaudePTransportException(ClaudePTransportFailure.CLOSED)
        rpcs.forEach { it.completeExceptionally(failure) }
        starts.forEach { it.completeExceptionally(failure) }
    }

    /**
     * Routes one raw frame.
     *
     * A frame the protocol layer rejects is reported through [onViolation] and otherwise discarded;
     * the caller decides whether that ends the connection. A frame whose `event_seq` does not advance
     * ends **that generation** with a bounded terminal rather than the whole connection, because one
     * misbehaving stream should not take down unrelated in-flight work — the user recovers the true
     * state through `receipt.query`.
     */
    fun route(raw: String) {
        when (val inbound = ClaudePProtocol.parseInbound(raw)) {
            is ClaudePInbound.Rejected -> onViolation(ClaudePInboundViolation.of(inbound.reason))

            ClaudePInbound.IgnoredUnknownEvent -> routeUnknownOptional(raw)

            is ClaudePInbound.Event -> routeEvent(inbound.event, raw)
        }
    }

    private fun routeEvent(event: ClaudePServerEvent, raw: String) {
        if (event is ClaudePServerEvent.GenerationAccepted) {
            routeAccepted(event, raw)
            return
        }

        // A tool frame is transport between Android and the Gateway, not model output, and it is
        // the one family of frames that carries its whole meaning on the envelope's generation.
        // The Server refuses an inbound tool frame without one rather than guessing which run a
        // call belongs to; requiring the same here keeps the two ends symmetric, and it is a
        // violation rather than a drop because a frame addressed to nobody is malformed rather
        // than merely not ours.
        if (event.isToolFrame && event.envelope.generationId.isNullOrEmpty()) {
            onViolation(ClaudePInboundViolation.MISSING_TOOL_BINDING)
            return
        }

        val requestId = event.envelope.requestId

        // A `generation.start` refused by the gateway answers with `generation.failed` carrying the
        // request id. Without this branch the refusal would be dropped and `startGeneration` would
        // hang until its timeout instead of reporting `model_not_allowed` or `device_revoked`.
        if (event is ClaudePServerEvent.Failed && requestId != null) {
            val startWaiter = synchronized(lock) { pendingStarts.remove(requestId) }
            if (startWaiter != null) {
                startWaiter.complete(ClaudePStartOutcome.Rejected(event.body.safeCode))
                return
            }
        }

        // Any event carrying a request id we are waiting on is that RPC's reply. This is checked
        // after the accepted case so a generation's first frame is never swallowed as a reply, and
        // the client never issues an RPC whose answer is a generation-content event.
        if (requestId != null) {
            val waiter = synchronized(lock) { pendingRpcs.remove(requestId) }
            if (waiter != null) {
                waiter.complete(raw)
                return
            }
        }

        val generationId = event.envelope.generationId ?: return
        deliver(generationId, raw, event.eventSeq, terminalKind = event.terminalKind)
    }

    private fun routeAccepted(event: ClaudePServerEvent.GenerationAccepted, raw: String) {
        val body = event.body
        val generationId = body.generationId

        // The buffer is opened — and the frame delivered into it — before the start waiter is
        // completed, so that by the time `startGeneration` returns, no event following `accepted`
        // can arrive, miss an as-yet-unopened buffer, and be dropped. `openGeneration` returning
        // null means the generation was already tracked (a replay), so fall back to the existing
        // stream rather than discarding the frame.
        val stream = if (generationId.isNotBlank()) {
            openGeneration(generationId) ?: streamOf(generationId)
        } else {
            null
        }
        stream?.offer(raw, event.eventSeq)

        val requestId = event.envelope.requestId
        val waiter = requestId?.let { synchronized(lock) { pendingStarts.remove(it) } }
        waiter?.complete(ClaudePStartOutcome.Accepted(body))
    }

    /**
     * Handles a well-formed frame whose type this build does not route.
     *
     * The envelope is decoded a second time *only* on this path, purely to recover the correlation
     * ids. `ClaudePProtocol.parseInbound` remains the single validator of frame meaning; this decode
     * can no more fail than the one inside it did.
     */
    private fun routeUnknownOptional(raw: String) {
        val envelope = try {
            ClaudePProtocol.json.decodeFromString<ClaudePEnvelope>(raw)
        } catch (_: Exception) {
            onViolation(ClaudePInboundViolation.MALFORMED_FRAME)
            return
        }

        val type = envelope.type.trim()
        // Unknown `generation.*`: possibly a terminal we cannot honour. Refuse rather than hang.
        if (type.startsWith(GENERATION_NAMESPACE)) {
            envelope.generationId?.let { generationId ->
                terminateGeneration(
                    generationId = generationId,
                    code = ClaudePErrorCode.PROTOCOL_MISMATCH,
                    atSeq = envelope.sequence ?: 0L,
                )
            }
            onViolation(ClaudePInboundViolation.UNKNOWN_REQUIRED_EVENT)
            return
        }

        // Unknown optional: forwarded to a tracked generation so ordering is preserved for the
        // provider, which will drop it. A frame for a generation we do not track is not ours.
        val generationId = envelope.generationId ?: return
        if (streamOf(generationId) == null) return
        deliver(generationId, raw, envelope.sequence ?: 0L, terminalKind = null)
    }

    private fun deliver(
        generationId: String,
        raw: String,
        sequence: Long,
        terminalKind: ClaudePTerminalKind?,
    ) {
        val stream = streamOf(generationId) ?: return
        when (stream.offer(raw, sequence, terminalKind)) {
            ClaudePFrameAcceptance.ACCEPTED ->
                // The terminal frame stays in the buffer so the consumer still receives it; the
                // stream is closed immediately after, which is what stops a generation from being
                // tracked — and therefore "recoverable" — once it is over.
                if (terminalKind != null) closeGeneration(generationId, terminalKind = terminalKind)

            ClaudePFrameAcceptance.ALREADY_FINISHED -> Unit

            ClaudePFrameAcceptance.SEQUENCE_REGRESSION ->
                terminateGeneration(generationId, ClaudePErrorCode.PROTOCOL_MISMATCH, sequence)

            ClaudePFrameAcceptance.BUFFER_OVERFLOW ->
                terminateGeneration(generationId, ClaudePErrorCode.STREAM_INTERRUPTED, sequence)
        }
    }

    /**
     * Ends a generation the client refuses to keep reading, with a locally generated terminal.
     *
     * Recorded as [ClaudePTerminalKind.FAILED] so a later cancel still gets a definite answer rather
     * than "pending" for a stream that can never produce anything else.
     */
    private fun terminateGeneration(generationId: String, code: ClaudePErrorCode, atSeq: Long) {
        val stream = synchronized(lock) { generations.remove(generationId) } ?: return
        recordTerminal(generationId, ClaudePTerminalKind.FAILED)
        stream.finish(
            claudePLocalTerminalFrame(
                generationId = generationId,
                sequence = if (atSeq > stream.lastEventSeq) atSeq else stream.lastEventSeq + 1,
                code = code,
            ),
        )
    }

    private companion object {
        const val GENERATION_NAMESPACE = "generation."

        /** How many retired generations can still answer "how did it end?". */
        const val MAX_REMEMBERED_TERMINALS = 64
    }
}

/** How a `generation.start` ended as far as the client is concerned. */
sealed interface ClaudePStartOutcome {
    /** The gateway accepted the request and named the generation. */
    data class Accepted(val body: ClaudePGenerationAcceptedBody) : ClaudePStartOutcome

    /** The gateway refused before any model run. [code] is bounded and safe to surface. */
    data class Rejected(val code: ClaudePErrorCode) : ClaudePStartOutcome
}

/** A frame the client must not simply continue past. */
enum class ClaudePInboundViolation {
    /** Not decodable as an envelope. */
    MALFORMED_FRAME,

    /** Empty or unparseable `protocol`. */
    MISSING_OR_MALFORMED_PROTOCOL,

    /** A protocol major this build does not speak — including "we could not tell". */
    PROTOCOL_MAJOR_MISMATCH,

    /** A known type whose body did not match the versioned schema. */
    MALFORMED_EVENT_BODY,

    /**
     * An unknown event in the `generation.` namespace, which could have been the terminal.
     * Ignoring it would risk a generation that never ends.
     */
    UNKNOWN_REQUIRED_EVENT,

    /**
     * A tool frame whose envelope carried no `generation_id`.
     *
     * The Server requires the generation on both `tool.result` and `tool.query` for exactly
     * this reason — without it, it would have to guess which run a call belongs to, and every
     * way of guessing answers with *a* record rather than *the* record. The same rule holds in
     * the other direction: a tool frame with no generation is not "addressed to nobody", it is
     * malformed, and dropping it quietly would leave a tool call the Server believes is running
     * that no screen will ever show.
     */
    MISSING_TOOL_BINDING;

    companion object {
        fun of(reason: ClaudePParseRejection): ClaudePInboundViolation = when (reason) {
            ClaudePParseRejection.MALFORMED_FRAME -> MALFORMED_FRAME
            ClaudePParseRejection.MISSING_PROTOCOL,
            ClaudePParseRejection.MALFORMED_PROTOCOL,
            -> MISSING_OR_MALFORMED_PROTOCOL

            ClaudePParseRejection.PROTOCOL_MAJOR_MISMATCH -> PROTOCOL_MAJOR_MISMATCH
            ClaudePParseRejection.MISSING_TYPE -> MALFORMED_EVENT_BODY
            ClaudePParseRejection.MALFORMED_EVENT_BODY -> MALFORMED_EVENT_BODY
        }
    }
}

/**
 * Maps a client-side violation onto the bounded error the user is shown.
 *
 * Everything meaning "the peer is not speaking our protocol" collapses onto
 * [ClaudePErrorCode.PROTOCOL_MISMATCH], which `claudep/00-scope-and-product-contract.md` §6 maps to
 * "stop requesting and prompt an upgrade" rather than to a guess.
 */
internal fun ClaudePInboundViolation.toErrorCode(): ClaudePErrorCode = when (this) {
    ClaudePInboundViolation.MALFORMED_FRAME,
    ClaudePInboundViolation.MISSING_OR_MALFORMED_PROTOCOL,
    ClaudePInboundViolation.PROTOCOL_MAJOR_MISMATCH,
    ClaudePInboundViolation.MALFORMED_EVENT_BODY,
    ClaudePInboundViolation.UNKNOWN_REQUIRED_EVENT,
    ClaudePInboundViolation.MISSING_TOOL_BINDING,
    -> ClaudePErrorCode.PROTOCOL_MISMATCH
}
