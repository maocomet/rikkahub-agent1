package me.rerere.ai.provider.claudep

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Deterministic, in-memory Claude P Gateway.
 *
 * **Local skeleton only.** It opens no socket, resolves no name, spawns no process, reads no
 * credential and calls no model. Every value it returns is either a constructor parameter or
 * derived from one, and it never consults a clock — so tests need no sleeps, no barriers and no
 * timeout tuning to be reproducible.
 *
 * It is built to *reject* as loudly as it accepts. The handshake is enforced before any
 * generation, the model alias is checked against the catalog allowlist, protocol-major
 * compatibility is checked on every RPC, and request fingerprints are enforced for idempotency.
 * A provider bug that skips a gate shows up as a failed assertion rather than a silently
 * permissive test.
 */
class FakeClaudePGatewayClient(
    /** Fixed catalog. Entries are server aliases; a CLI version string is never one of them. */
    val catalogModels: List<ClaudePModelEntry> = DEFAULT_CATALOG,

    /** Reasoning summary deltas emitted before the answer, in order. */
    val reasoningDeltas: List<String> = listOf("Considering the request."),

    /** Text deltas emitted as the answer, in order. */
    val textDeltas: List<String> = listOf("Hello", ", ", "world"),

    /** Terminal the scripted generation reaches. */
    val terminalKind: ClaudePTerminalKind = ClaudePTerminalKind.COMPLETED,

    /** Error code used when [terminalKind] is [ClaudePTerminalKind.FAILED]. */
    val failureCode: ClaudePErrorCode = ClaudePErrorCode.EXTERNAL_RUNTIME_ERROR,

    /** Usage counters reported by `usage.updated` and echoed in receipts. */
    val usage: ClaudePUsageBody = ClaudePUsageBody(
        promptTokens = 11,
        completionTokens = 7,
        cachedTokens = 3,
        totalTokens = 18,
    ),

    /** Protocol identifier stamped into every frame. Override to simulate a malformed family. */
    val frameProtocolId: String = ClaudePProtocol.PROTOCOL_ID,

    /**
     * Protocol version the gateway claims during `server.hello`. The provider owns the decision to
     * reject it; setting this to e.g. `"v2"` must produce zero dispatches.
     */
    val serverProtocolVersion: String = "v1",

    /** Handshake outcome. [FakeHandshake.Reject] makes `client.hello` fail closed. */
    val handshake: FakeHandshake = FakeHandshake.Accept,

    /** When true, every RPC before `client.hello` fails with [ClaudePErrorCode.AUTHENTICATION_REQUIRED]. */
    val requireHandshake: Boolean = true,

    /**
     * When set, collecting a generation's frames emits this many frames and then fails with
     * [ClaudePErrorCode.STREAM_INTERRUPTED] — a deterministic disconnect.
     */
    val disconnectAfterFrames: Int? = null,

    /** Bounded replay window, mirroring the gateway's short-TTL event buffer. */
    val replayBufferSize: Int = 64,

    /** Extra frames appended after the scripted terminal, for post-terminal rejection tests. */
    val extraFramesAfterTerminal: List<FakeFrame> = emptyList(),

    /**
     * Frames injected mid-stream, immediately after `message.started`.
     *
     * Used to prove that an unknown optional event — a Phase 3 `tool.requested`, say — passes
     * through without becoming text, ending the generation, or disturbing the surrounding deltas.
     */
    val midStreamFrames: List<FakeFrame> = emptyList(),

    /** Claude Code version reported by `server.hello`. Never usable as a model alias. */
    val claudeCodeVersion: String = "2.0.1",
) : ClaudePGatewayClient {

    private val lock = Any()
    private var handshakeCompleted = false

    private var startGenerationCalls = 0
    private var remoteDispatches = 0
    private var cancelCalls = 0

    /** request_id -> (fingerprint, generation). Enforces §7 idempotency. */
    private val idempotency = mutableMapOf<String, IdempotencyEntry>()

    private val generations = mutableMapOf<String, FakeGeneration>()

    private var nextGenerationSuffix = 0

    override val startGenerationCallCount: Int
        get() = synchronized(lock) { startGenerationCalls }

    override val remoteDispatchCount: Int
        get() = synchronized(lock) { remoteDispatches }

    override val cancelCallCount: Int
        get() = synchronized(lock) { cancelCalls }

    /** Convenience for tests: the single generation created by a one-shot script. */
    val lastGenerationId: String?
        get() = synchronized(lock) { generations.keys.lastOrNull() }

    override suspend fun hello(request: ClaudePClientHelloBody): ClaudePServerHelloBody {
        val outcome = handshake
        if (outcome is FakeHandshake.Reject) {
            throw ClaudePGatewayException(outcome.code)
        }
        synchronized(lock) { handshakeCompleted = true }
        return ClaudePServerHelloBody(
            protocolVersion = serverProtocolVersion,
            gatewayBuild = "fake-gateway-1",
            workerAbi = "fake-worker-abi-1",
            claudeCodeVersion = claudeCodeVersion,
            maxFrameBytes = 262_144,
            maxPromptBytes = 1_048_576,
            maxConcurrentGenerations = 1,
            features = listOf("text_stream", "cancel", "receipt_query"),
            serverTime = "1970-01-01T00:00:00Z",
            connectionId = "fake-connection",
        )
    }

    override suspend fun catalog(): ClaudePCatalogResultBody {
        requireHandshakeCompleted()
        return ClaudePCatalogResultBody(models = catalogModels)
    }

    override suspend fun startGeneration(
        requestId: String,
        fingerprint: String,
        body: ClaudePGenerationStartBody,
    ): ClaudePGenerationHandle {
        requireHandshakeCompleted()

        synchronized(lock) {
            startGenerationCalls++

            // §7: same id + same fingerprint replays the original; same id + different
            // fingerprint is a hard conflict. Never create a second model request.
            val existing = idempotency[requestId]
            if (existing != null) {
                if (existing.fingerprint != fingerprint) {
                    throw ClaudePGatewayException(
                        ClaudePErrorCode.IDEMPOTENCY_CONFLICT,
                        generationId = existing.generationId,
                    )
                }
                val generation = generations.getValue(existing.generationId)
                return FakeGenerationHandle(generation, requestId, acceptedEventSeq = ACCEPTED_SEQ)
            }

            val entry = catalogModels.firstOrNull { it.alias == body.modelAlias && it.enabled }
                ?: throw ClaudePGatewayException(ClaudePErrorCode.MODEL_NOT_ALLOWED)

            remoteDispatches++
            val generationId = "gen-${++nextGenerationSuffix}-${entry.alias}"
            val generation = FakeGeneration(
                generationId = generationId,
                frames = buildScript(generationId),
                client = this,
            )
            generations[generationId] = generation
            idempotency[requestId] = IdempotencyEntry(fingerprint, generationId)

            return FakeGenerationHandle(generation, requestId, acceptedEventSeq = ACCEPTED_SEQ)
        }
    }

    override suspend fun cancel(
        generationId: String,
        reason: ClaudePCancelReason,
    ): ClaudePCancelOutcome {
        requireHandshakeCompleted()
        val generation = synchronized(lock) {
            cancelCalls++
            generations[generationId]
        } ?: throw ClaudePGatewayException(
            ClaudePErrorCode.SESSION_MISSING,
            generationId = generationId,
        )

        return synchronized(generation) {
            val existingTerminal = generation.terminalKind
            when {
                // §8: already terminal -> return the original terminal, add nothing.
                existingTerminal != null -> ClaudePCancelOutcome.Terminal(existingTerminal)
                else -> {
                    generation.cancelRequested = true
                    ClaudePCancelOutcome.Terminal(ClaudePTerminalKind.CANCELLED)
                }
            }
        }
    }

    override suspend fun receipt(generationId: String): ClaudePReceiptBody {
        requireHandshakeCompleted()
        val generation = synchronized(lock) { generations[generationId] }
            ?: throw ClaudePGatewayException(
                ClaudePErrorCode.SESSION_MISSING,
                generationId = generationId,
            )

        return synchronized(generation) { buildReceipt(generationId, generation) }
    }

    /** Caller must hold the generation's monitor. */
    private fun buildReceipt(generationId: String, generation: FakeGeneration): ClaudePReceiptBody {
        val kind = generation.terminalKind
        return ClaudePReceiptBody(
            generationId = generationId,
            state = when (kind) {
                ClaudePTerminalKind.COMPLETED -> ClaudePGenerationState.COMPLETED.wireValue
                ClaudePTerminalKind.CANCELLED -> ClaudePGenerationState.CANCELLED.wireValue
                ClaudePTerminalKind.FAILED -> ClaudePGenerationState.FAILED.wireValue
                null -> ClaudePGenerationState.ACTIVE.wireValue
            },
            errorCode = if (kind == ClaudePTerminalKind.FAILED) failureCode.wireName() else null,
            lastEventSeq = generation.lastEmittedSeq,
            usage = if (kind == null) null else usage,
        )
    }

    override suspend fun resume(generationId: String, lastEventSeq: Long): ClaudePResumeResult {
        requireHandshakeCompleted()
        val generation = synchronized(lock) { generations[generationId] }
            ?: throw ClaudePGatewayException(
                ClaudePErrorCode.SESSION_MISSING,
                generationId = generationId,
            )

        // Built under the generation's own monitor rather than by calling receipt(): taking the
        // client lock here would invert the lock order used by cancel()/receipt().
        val (retained, terminal, safeReceipt) = synchronized(generation) {
            Triple(
                generation.retainedFrames(replayBufferSize),
                generation.terminalKind,
                buildReceipt(generationId, generation),
            )
        }

        // A gap means the short-TTL buffer already dropped events. The gateway must not pretend
        // the stream is intact — §9 requires an explicit unknown/receipt outcome instead.
        val hasGap = retained.isNotEmpty() && retained.first().seq > lastEventSeq + 1
        if (retained.isEmpty() || hasGap) {
            return if (terminal != null) {
                ClaudePResumeResult.Terminal(safeReceipt)
            } else {
                ClaudePResumeResult.StateUnknown
            }
        }

        val replay = retained.filter { it.seq > lastEventSeq }
        return ClaudePResumeResult.Replayed(
            outcome = ClaudePResumeKind.REPLAYED,
            frames = replay.map { it.json },
        )
    }

    // -----------------------------------------------------------------------------------------
    // Scripting
    // -----------------------------------------------------------------------------------------

    private fun requireHandshakeCompleted() {
        if (requireHandshake && !handshakeCompleted) {
            throw ClaudePGatewayException(ClaudePErrorCode.AUTHENTICATION_REQUIRED)
        }
    }

    private fun buildScript(generationId: String): List<SeqFrame> {
        val frames = mutableListOf<SeqFrame>()
        var seq = 0L

        fun add(type: String, body: JsonObject = JsonObject(emptyMap())) {
            seq += 1
            frames += SeqFrame(
                seq = seq,
                json = encodeFrame(
                    protocolId = frameProtocolId,
                    type = type,
                    sequence = seq,
                    generationId = generationId,
                    body = body,
                ),
            )
        }

        add(
            ClaudePEventType.GENERATION_ACCEPTED,
            buildJsonObject {
                put("generation_id", generationId)
                put("accepted_seq", ACCEPTED_SEQ)
            },
        )
        add(
            ClaudePEventType.GENERATION_STARTED,
            buildJsonObject {
                put("generation_id", generationId)
                put("claude_code_version", claudeCodeVersion)
            },
        )
        add(
            ClaudePEventType.MESSAGE_STARTED,
            buildJsonObject {
                put("message_id", "msg-1")
                put("role", "assistant")
                put("index", 0)
            },
        )
        midStreamFrames.forEach { extra -> add(extra.type, extra.body) }
        reasoningDeltas.forEach { summary ->
            add(
                ClaudePEventType.REASONING_DELTA,
                buildJsonObject {
                    put("message_id", "msg-1")
                    put("index", 0)
                    put("summary", summary)
                },
            )
        }
        textDeltas.forEach { text ->
            add(
                ClaudePEventType.TEXT_DELTA,
                buildJsonObject {
                    put("message_id", "msg-1")
                    put("index", 0)
                    put("text", text)
                },
            )
        }
        add(
            ClaudePEventType.USAGE_UPDATED,
            buildJsonObject {
                put("prompt_tokens", usage.promptTokens)
                put("completion_tokens", usage.completionTokens)
                put("cached_tokens", usage.cachedTokens)
                put("total_tokens", usage.totalTokens)
            },
        )

        when (terminalKind) {
            ClaudePTerminalKind.COMPLETED -> add(
                ClaudePEventType.GENERATION_COMPLETED,
                buildJsonObject {
                    put("stop_reason", "completed")
                    put("reasoning_chars", reasoningDeltas.sumOf { it.length })
                    put("answer_chars", textDeltas.sumOf { it.length })
                },
            )

            ClaudePTerminalKind.CANCELLED -> add(
                ClaudePEventType.GENERATION_CANCELLED,
                buildJsonObject { put("reason", ClaudePCancelReason.USER_REQUESTED.wireValue) },
            )

            ClaudePTerminalKind.FAILED -> add(
                ClaudePEventType.GENERATION_FAILED,
                buildJsonObject { put("error_code", failureCode.wireName()) },
            )
        }

        extraFramesAfterTerminal.forEach { extra ->
            seq += 1
            frames += SeqFrame(
                seq = seq,
                json = encodeFrame(
                    protocolId = frameProtocolId,
                    type = extra.type,
                    sequence = seq,
                    generationId = generationId,
                    body = extra.body,
                ),
            )
        }

        return frames
    }

    companion object {
        /** Sequence of `generation.accepted`; resume starts after it. */
        const val ACCEPTED_SEQ: Long = 1L

        /**
         * A fixed catalog. Aliases only — note that no entry resembles a Claude Code CLI version,
         * and [ClaudePProtocol] never derives a model from one.
         */
        val DEFAULT_CATALOG: List<ClaudePModelEntry> = listOf(
            ClaudePModelEntry(
                alias = "sonnet",
                displayName = "Claude Sonnet",
                input = listOf("text"),
                features = listOf("streaming", "reasoning_summary"),
                enabled = true,
            ),
            ClaudePModelEntry(
                alias = "haiku",
                displayName = "Claude Haiku",
                input = listOf("text"),
                features = listOf("streaming"),
                enabled = true,
            ),
            ClaudePModelEntry(
                alias = "opus",
                displayName = "Claude Opus",
                input = listOf("text"),
                features = listOf("streaming", "reasoning_summary"),
                enabled = true,
            ),
            ClaudePModelEntry(
                alias = "retired",
                displayName = "Retired alias",
                input = listOf("text"),
                features = emptyList(),
                enabled = false,
            ),
        )
    }
}

/** Handshake behaviour for [FakeClaudePGatewayClient]. */
sealed interface FakeHandshake {
    data object Accept : FakeHandshake

    /** Fail `client.hello` with [code], producing zero dispatch. */
    data class Reject(val code: ClaudePErrorCode) : FakeHandshake
}

/** An extra frame a test can append to a script. */
data class FakeFrame(
    val type: String,
    val body: JsonObject = JsonObject(emptyMap()),
)

private data class IdempotencyEntry(val fingerprint: String, val generationId: String)

private data class SeqFrame(val seq: Long, val json: String)

private fun encodeFrame(
    protocolId: String,
    type: String,
    sequence: Long,
    generationId: String?,
    body: JsonObject,
    requestId: String? = null,
): String = ClaudePProtocol.json.encodeToString(
    ClaudePEnvelope(
        protocol = protocolId,
        type = type,
        connectionId = "fake-connection",
        requestId = requestId,
        generationId = generationId,
        sequence = sequence,
        // Fixed, not "now": a clock would make frames non-reproducible.
        sentAt = "1970-01-01T00:00:00Z",
        body = body,
    ),
)

private class FakeGeneration(
    val generationId: String,
    private val frames: List<SeqFrame>,
    private val client: FakeClaudePGatewayClient,
) {
    /** Set once a terminal frame has actually been emitted. */
    var terminalKind: ClaudePTerminalKind? = null
        private set

    /** Set by `cancel()` before a terminal was emitted; the stream then ends cancelled. */
    var cancelRequested: Boolean = false

    var lastEmittedSeq: Long = 0
        private set

    fun retainedFrames(window: Int): List<SeqFrame> = frames.takeLast(window)

    /**
     * Emits frames in order. Honours a cancel that arrived mid-flight and a scripted disconnect.
     * Frames produced after the scripted terminal are still emitted, so the provider's
     * post-terminal rejection is exercised rather than assumed.
     */
    fun emit(): Flow<String> = flow {
        val disconnectAfter = client.disconnectAfterFrames
        var emitted = 0

        for (frame in frames) {
            if (disconnectAfter != null && emitted >= disconnectAfter) {
                throw ClaudePGatewayException(
                    ClaudePErrorCode.STREAM_INTERRUPTED,
                    generationId = generationId,
                )
            }

            terminalKindFor(frame.json)?.let { terminalKind = it }
            lastEmittedSeq = frame.seq
            emit(frame.json)
            emitted += 1

            // Checked after the frame is emitted, so a cancel that arrived before collection
            // still produces the acknowledged prefix a real gateway would have sent before the
            // worker noticed the cancellation.
            if (cancelRequested && terminalKind == null) {
                terminalKind = ClaudePTerminalKind.CANCELLED
                lastEmittedSeq = frame.seq + 1
                emit(
                    encodeFrame(
                        protocolId = client.frameProtocolId,
                        type = ClaudePEventType.GENERATION_CANCELLED,
                        sequence = lastEmittedSeq,
                        generationId = generationId,
                        body = buildJsonObject {
                            put("reason", ClaudePCancelReason.USER_REQUESTED.wireValue)
                        },
                    ),
                )
                return@flow
            }
        }
    }

    private fun terminalKindFor(json: String): ClaudePTerminalKind? {
        val envelope = try {
            ClaudePProtocol.json.decodeFromString<ClaudePEnvelope>(json)
        } catch (_: Exception) {
            return null
        }
        return when (envelope.type) {
            ClaudePEventType.GENERATION_COMPLETED -> ClaudePTerminalKind.COMPLETED
            ClaudePEventType.GENERATION_CANCELLED -> ClaudePTerminalKind.CANCELLED
            ClaudePEventType.GENERATION_FAILED -> ClaudePTerminalKind.FAILED
            else -> null
        }
    }
}

private class FakeGenerationHandle(
    private val generation: FakeGeneration,
    override val requestId: String,
    override val acceptedEventSeq: Long,
) : ClaudePGenerationHandle {
    override val generationId: String
        get() = generation.generationId

    override fun frames(): Flow<String> = generation.emit()
}

/** Wire value for an enum constant, via its `@SerialName`, without a reflective lookup. */
private fun ClaudePErrorCode.wireName(): String = when (this) {
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
