package me.rerere.ai.provider.claudep

import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow

/**
 * Transport boundary between [me.rerere.ai.provider.providers.ClaudePProvider] and a Claude P
 * Gateway.
 *
 * CP1-A ships exactly one implementation — [FakeClaudePGatewayClient] — plus
 * [UnpairedClaudePGatewayClient], the fail-closed stand-in the app wires up until CP1-B adds real
 * device pairing. **No implementation in this repository performs DNS, HTTP, WebSocket, TLS or
 * process work.** The WSS transport is CP1-B.
 *
 * The interface is deliberately narrow. Everything the doc assigns to the gateway — device
 * identity, replay ordering, receipt durability, request fingerprints — is expressed as a
 * request/response or a frame stream, so the provider never has to reason about sockets.
 */
interface ClaudePGatewayClient {
    /**
     * Performs `client.hello`. Throws [ClaudePGatewayException] when the handshake is refused.
     *
     * Callers must treat a failure as "zero dispatch": a connection that cannot be established
     * correctly must never reach a model.
     */
    suspend fun hello(request: ClaudePClientHelloBody): ClaudePServerHelloBody

    /**
     * `catalog.get`. Must not invoke a model — it reads a server-side allowlist.
     */
    suspend fun catalog(): ClaudePCatalogResultBody

    /**
     * `generation.start`.
     *
     * Exactly one remote dispatch results from the first call for a given
     * ([requestId], [fingerprint]) pair. Repeating the same pair returns the original handle
     * without a second dispatch; a different [fingerprint] for a known [requestId] throws
     * [ClaudePErrorCode.IDEMPOTENCY_CONFLICT].
     */
    suspend fun startGeneration(
        requestId: String,
        fingerprint: String,
        body: ClaudePGenerationStartBody,
    ): ClaudePGenerationHandle

    /**
     * `generation.cancel`. Idempotent: repeating it returns the same state and never appends an
     * event. When the generation already reached a terminal this returns that terminal unchanged.
     */
    suspend fun cancel(generationId: String, reason: ClaudePCancelReason): ClaudePCancelOutcome

    /** `receipt.query`. Returns a content-free terminal summary. */
    suspend fun receipt(generationId: String): ClaudePReceiptBody

    /**
     * `tool.result` — Android's answer to one `tool.invoke`.
     *
     * Fire-and-forget, and deliberately so. The Server applies the outcome to the call it is
     * holding and replies with nothing; a late one, one naming a generation it no longer holds,
     * and a repeat of an answer it already has are all dropped rather than punished. There is
     * therefore no return value to map onto a Kotlin type, and inventing one would suggest this
     * side could learn something from the reply that it cannot.
     *
     * The state must be one Android may report — [me.rerere.ai.provider.claudep.bridge.ANDROID_REPORTABLE_TOOL_CALL_STATES]
     * — and the caller is expected to have checked. A malformed outcome is a protocol violation
     * that ends the connection, which would take every *other* call this device is holding with
     * it, so it is not something to discover at the socket.
     */
    suspend fun sendToolResult(generationId: String, body: ClaudePToolResultBody)

    /**
     * `tool.query` — ask the Server what its ledger holds for one tool call id.
     *
     * Also fire-and-forget, because the answer is not a reply: it arrives as a
     * [ClaudePServerEvent.ToolQueryResult] on the generation's own stream, so that a reconnect
     * that replays frames cannot produce a query answer that bypassed the replay ordering.
     */
    suspend fun queryToolCall(generationId: String, body: ClaudePToolQueryBody)

    /**
     * `stream.resume`. Replays buffered events only. It must never call the model again — that is
     * the whole point of the receipt/idempotency design.
     */
    suspend fun resume(generationId: String, lastEventSeq: Long): ClaudePResumeResult

    /** Number of `generation.start` invocations, including idempotent reuse. */
    val startGenerationCallCount: Int

    /** Number of generations that actually reached the remote runtime. */
    val remoteDispatchCount: Int

    /** Number of `generation.cancel` RPCs issued. */
    val cancelCallCount: Int

    /** Number of `tool.result` frames written. Lets a test prove a replay sent nothing. */
    val toolResultCallCount: Int
}

/** Handle for one accepted generation. */
interface ClaudePGenerationHandle {
    val generationId: String
    val requestId: String

    /** `event_seq` of `generation.accepted`. Resume starts strictly after this. */
    val acceptedEventSeq: Long

    /**
     * Cold, single-shot stream of raw server frames in arrival order.
     *
     * Raw frames rather than parsed events on purpose: parsing happens in exactly one place
     * ([ClaudePProtocol.parseInbound]) so a transport can never smuggle an unvalidated body into
     * the provider. Collecting twice must not re-dispatch.
     */
    fun frames(): Flow<String>
}

/** Outcome of `generation.cancel`. */
sealed interface ClaudePCancelOutcome {
    /** A terminal was reached; [kind] is the original one, never a new one. */
    data class Terminal(val kind: ClaudePTerminalKind) : ClaudePCancelOutcome

    /**
     * The gateway could not yet prove the child process is gone. Never reported as cancelled —
     * `02-wire-protocol-v1.md` §8 forbids claiming a terminal we cannot prove.
     */
    data object Pending : ClaudePCancelOutcome
}

/** Outcome of `stream.resume`. */
sealed interface ClaudePResumeResult {
    val outcome: ClaudePResumeKind

    /** Buffered events follow and are replayed in order. */
    data class Replayed(
        override val outcome: ClaudePResumeKind,
        val frames: List<String>,
    ) : ClaudePResumeResult

    /** The buffer expired but a terminal exists; the generation is over. */
    data class Terminal(val receipt: ClaudePReceiptBody) : ClaudePResumeResult {
        override val outcome: ClaudePResumeKind = ClaudePResumeKind.TERMINAL
    }

    /** Nothing can be proven. The user decides; we never auto-retry. */
    data object StateUnknown : ClaudePResumeResult {
        override val outcome: ClaudePResumeKind = ClaudePResumeKind.STATE_UNKNOWN
    }
}

/**
 * Typed gateway failure.
 *
 * The message is assembled from enum names only, so an exception can be logged or shown without
 * carrying a prompt, a token or raw stderr.
 */
class ClaudePGatewayException(
    val code: ClaudePErrorCode,
    val rejection: ClaudePParseRejection? = null,
    val generationId: String? = null,
) : Exception(
    buildString {
        append("claude-p gateway failure: ")
        append(code.name)
        rejection?.let { append(" (").append(it.name).append(')') }
    },
) {
    override fun toString(): String =
        "ClaudePGatewayException(code=${code.name}, rejection=${rejection?.name}, " +
            "generationId=${generationId.redactedRef()})"
}

/**
 * Durable, content-free identity of one generation request.
 *
 * `02-wire-protocol-v1.md` §7 requires the fingerprint to bind device, thread, branch, mode,
 * model, system prompt, turn/history, tool snapshot and attachment manifest. Fields are
 * length-prefixed before hashing so that concatenation ambiguities cannot collide two different
 * requests — the same discipline the repo already uses for background dispatch attestations.
 */
object ClaudePRequestFingerprint {
    private const val DOMAIN = "rikkahub-claude-p-request-fingerprint-v1"

    fun compute(
        deviceId: String,
        remoteThreadId: String,
        remoteBranchId: String,
        mode: String,
        modelAlias: String,
        systemPrompt: String?,
        turn: ClaudePTurn,
        rebuildHistory: List<ClaudePTurn>? = null,
        toolSnapshot: String? = null,
        attachmentManifest: String? = null,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(label: String, value: String?) {
            digest.updateLengthPrefixed(label.toByteArray(Charsets.UTF_8))
            // Presence is encoded explicitly. Folding `null` into `""` would let an absent system
            // prompt and an empty one share a fingerprint, which is exactly the collision the
            // idempotency key must not have.
            if (value == null) {
                digest.update(0)
            } else {
                digest.update(1)
                digest.updateLengthPrefixed(value.toByteArray(Charsets.UTF_8))
            }
        }

        field("domain", DOMAIN)
        field("device_id", deviceId)
        field("remote_thread_id", remoteThreadId)
        field("remote_branch_id", remoteBranchId)
        field("mode", mode)
        field("model_alias", modelAlias)
        field("system_prompt", systemPrompt)
        field("turn_role", turn.role)
        field("turn_parts", turn.parts.size.toString())
        turn.parts.forEachIndexed { index, part ->
            field("turn_part_$index.type", part.type)
            field("turn_part_$index.text", part.text)
        }
        val history = rebuildHistory.orEmpty()
        field("rebuild_history_turns", history.size.toString())
        history.forEachIndexed { index, entry ->
            field("rebuild_$index.role", entry.role)
            entry.parts.forEachIndexed { partIndex, part ->
                field("rebuild_$index.$partIndex.type", part.type)
                field("rebuild_$index.$partIndex.text", part.text)
            }
        }
        field("tool_snapshot", toolSnapshot)
        field("attachment_manifest", attachmentManifest)

        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private fun MessageDigest.updateLengthPrefixed(bytes: ByteArray) {
        val size = bytes.size
        update((size ushr 24).toByte())
        update((size ushr 16).toByte())
        update((size ushr 8).toByte())
        update(size.toByte())
        update(bytes)
    }
}

/**
 * Fail-closed client used until real device pairing exists.
 *
 * This is the CP1-A production binding. It has no endpoint, no credential and no socket: every
 * call reports [ClaudePErrorCode.NOT_PAIRED]. The Provider therefore appears in settings as
 * "not yet paired" and cannot silently start talking to anything.
 */
object UnpairedClaudePGatewayClient : ClaudePGatewayClient {
    override suspend fun hello(request: ClaudePClientHelloBody): ClaudePServerHelloBody =
        throw ClaudePGatewayException(ClaudePErrorCode.NOT_PAIRED)

    override suspend fun catalog(): ClaudePCatalogResultBody =
        throw ClaudePGatewayException(ClaudePErrorCode.NOT_PAIRED)

    override suspend fun startGeneration(
        requestId: String,
        fingerprint: String,
        body: ClaudePGenerationStartBody,
    ): ClaudePGenerationHandle = throw ClaudePGatewayException(ClaudePErrorCode.NOT_PAIRED)

    override suspend fun cancel(generationId: String, reason: ClaudePCancelReason): ClaudePCancelOutcome =
        throw ClaudePGatewayException(ClaudePErrorCode.NOT_PAIRED)

    override suspend fun receipt(generationId: String): ClaudePReceiptBody =
        throw ClaudePGatewayException(ClaudePErrorCode.NOT_PAIRED)

    override suspend fun sendToolResult(generationId: String, body: ClaudePToolResultBody) =
        throw ClaudePGatewayException(ClaudePErrorCode.NOT_PAIRED)

    override suspend fun queryToolCall(generationId: String, body: ClaudePToolQueryBody) =
        throw ClaudePGatewayException(ClaudePErrorCode.NOT_PAIRED)

    override suspend fun resume(generationId: String, lastEventSeq: Long): ClaudePResumeResult =
        throw ClaudePGatewayException(ClaudePErrorCode.NOT_PAIRED)

    override val startGenerationCallCount: Int = 0
    override val remoteDispatchCount: Int = 0
    override val cancelCallCount: Int = 0
    override val toolResultCallCount: Int = 0
}
