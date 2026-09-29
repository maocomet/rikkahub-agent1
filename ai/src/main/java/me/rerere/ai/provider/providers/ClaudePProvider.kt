package me.rerere.ai.provider.providers

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.StableSystemPromptProvider
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.claudep.ClaudePCancelReason
import me.rerere.ai.provider.claudep.ClaudePConnectionEpoch
import me.rerere.ai.provider.claudep.ClaudePCachedModel
import me.rerere.ai.provider.claudep.ClaudePCatalogRecorder
import me.rerere.ai.provider.claudep.ClaudePCatalogResultBody
import me.rerere.ai.provider.claudep.ClaudePToolBridgeHost
import me.rerere.ai.provider.claudep.ClaudePToolCancelBody
import me.rerere.ai.provider.claudep.ClaudePToolFrames
import me.rerere.ai.provider.claudep.ClaudePToolInvokeBody
import me.rerere.ai.provider.claudep.ClaudePToolPreparation
import me.rerere.ai.provider.claudep.ClaudePToolCallStatus
import me.rerere.ai.provider.claudep.ClaudePToolQueryResultBody
import me.rerere.ai.provider.claudep.ClaudePToolStatusPublication
import me.rerere.ai.provider.claudep.ClaudePToolStatusSink
import me.rerere.ai.provider.claudep.ClaudePToolStatusUpdate
import me.rerere.ai.provider.claudep.toBridgeState
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.provider.claudep.bridge.BridgeCancelDecision
import me.rerere.ai.provider.claudep.bridge.BridgeCompletion
import me.rerere.ai.provider.claudep.bridge.BridgeContract
import me.rerere.ai.provider.claudep.bridge.BridgeGenerationRegistry
import me.rerere.ai.provider.claudep.bridge.BridgeInvokeDecision
import me.rerere.ai.provider.claudep.bridge.BridgeToolAdapter
import me.rerere.ai.provider.claudep.bridge.GenerationBinding
import me.rerere.ai.provider.claudep.bridge.ToolCallOutcome
import me.rerere.ai.provider.claudep.ClaudePClientHelloBody
import me.rerere.ai.provider.claudep.ClaudePErrorCode
import me.rerere.ai.provider.claudep.ClaudePGatewayClient
import me.rerere.ai.provider.claudep.ClaudePGatewayException
import me.rerere.ai.provider.claudep.ClaudePGenerationHandle
import me.rerere.ai.provider.claudep.ClaudePGenerationLimits
import me.rerere.ai.provider.claudep.ClaudePGenerationStartBody
import me.rerere.ai.provider.claudep.ClaudePGenerationState
import me.rerere.ai.provider.claudep.ClaudePInbound
import me.rerere.ai.provider.claudep.ClaudePModelEntry
import me.rerere.ai.provider.claudep.ClaudePProtocol
import me.rerere.ai.provider.claudep.ClaudePRequestFingerprint
import me.rerere.ai.provider.claudep.ClaudePResumeKind
import me.rerere.ai.provider.claudep.ClaudePResumeResult
import me.rerere.ai.provider.claudep.ClaudePSessionBindOutcome
import me.rerere.ai.provider.claudep.ClaudePSessionBindingIntent
import me.rerere.ai.provider.claudep.ClaudePSessionBindingRequest
import me.rerere.ai.provider.claudep.ClaudePSessionMode
import me.rerere.ai.provider.claudep.bindSessionOnce
import me.rerere.ai.provider.claudep.ClaudePServerEvent
import me.rerere.ai.provider.claudep.ClaudePServerHelloBody
import me.rerere.ai.provider.claudep.ClaudePStopReason
import me.rerere.ai.provider.claudep.ClaudePTerminalGate
import me.rerere.ai.provider.claudep.ClaudePTerminalKind
import me.rerere.ai.provider.claudep.ClaudePTurn
import me.rerere.ai.provider.claudep.ClaudePTurnPart
import me.rerere.ai.provider.claudep.ClaudePUsageBody
import me.rerere.ai.ui.FinishCategory
import me.rerere.ai.ui.GenerationTerminal
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.ReasoningSource
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart

/**
 * Claude P provider — a remote Claude Code runtime behind a user-owned Gateway.
 *
 * CP1-A scope: the provider is complete and testable against a deterministic fake gateway. It
 * performs **no** network, DNS, process or credential work. Its transport is a
 * [ClaudePGatewayClient], and the only implementation the app wires up at this stage is the
 * fail-closed `UnpairedClaudePGatewayClient`.
 *
 * ### Phase 1 capability ceiling
 *
 * Text plus a bounded reasoning summary. Nothing else. The provider never advertises
 * [ModelAbility.TOOL], never accepts an image/document/audio/video part and never sends a tool
 * snapshot. Unsupported input is rejected *before* dispatch rather than stripped and sent as
 * plain text, because silently dropping an attachment would leave the user believing Claude saw
 * it.
 */
class ClaudePProvider(
    private val gateway: ClaudePGatewayClient,
    /** Opaque device identity. Never a credential — the private key stays in the Keystore (CP1-B). */
    private val deviceId: String = "unpaired-device",
    private val appVersion: String = "rikkahub-agent1",
    /**
     * Source of `request_id`. Injectable so idempotency can be exercised deterministically; the
     * default is a fresh v4 UUID per generation, which is the production behaviour.
     */
    private val requestIdFactory: () -> String = { Uuid.random().toString() },
    private val remoteThreadId: String = "local-thread",
    private val remoteBranchId: String = "local-branch",
    /**
     * Resolves the **paired** device id at call time, or `null` when this device has none.
     *
     * ### Why this is nullable and suspend
     *
     * The device id is part of the request fingerprint *and* of `client.hello`, so it has to be the
     * device that is actually paired — not a startup-time placeholder. Two different devices sharing
     * `"unpaired-device"` would produce identical fingerprints for identical requests, and hello
     * would announce one identity while the fingerprint bound another.
     *
     * So a configured provider is authoritative and may return `null`. When it does, the request
     * **fails closed** before any dispatch rather than falling back to [deviceId]: a placeholder is
     * not a device identity, and silently substituting one is how a request gets bound to nothing.
     *
     * It is `suspend` because the production implementation re-validates durable pairing state
     * (credential, settings agreement, key loadability) rather than reading a cached value that a
     * revocation may have invalidated.
     *
     * Leaving it unset keeps CP1-A's static behaviour, including the constructor default and the
     * handshake cache.
     */
    private val deviceIdProvider: (suspend () -> String?)? = null,
    /**
     * The app's half of the tool bridge. Defaults to the host that has no tools, which is the
     * text path: no `tool_snapshot` is sent, so the Server registers nothing and no invocation
     * can arrive.
     */
    private val toolHost: ClaudePToolBridgeHost = ClaudePToolBridgeHost.NONE,
    /**
     * Where a successful catalog read is persisted, or `null` to persist nothing.
     *
     * The production wiring passes the settings authority that already owns this provider's
     * columns. It is optional rather than required so a provider built in a test records nothing
     * without having to stand up a settings store — and so the absence of a recorder can never turn
     * a catalog read into a failure. Persistence is a side effect of a successful read, not a
     * precondition for one.
     */
    private val catalogRecorder: ClaudePCatalogRecorder? = null,
) : Provider<ProviderSetting.ClaudeP>, StableSystemPromptProvider {

    /**
     * The per-generation tool adapters, and the only place one exists.
     *
     * One registry per provider instance, because a Claude P provider is one device's connection
     * to one Gateway: generations are sequential for a single user, and an adapter that outlived
     * its provider could answer a frame after the transport that carried it was gone.
     */
    private val toolRegistry = BridgeGenerationRegistry(executions = toolHost.executions)

    private val handshakeMutex = Mutex()

    @Volatile
    private var cachedServerHello: ClaudePServerHelloBody? = null

    /** Test/diagnostic accessor for the negotiated handshake, if one has completed. */
    val negotiatedServerHello: ClaudePServerHelloBody?
        get() = cachedServerHello

    override suspend fun listModels(providerSetting: ProviderSetting.ClaudeP): List<Model> {
        // The handshake first, always. `catalog.get` ahead of `client.hello` is precisely the frame
        // the Gateway refuses as `MALFORMED_EVENT_BODY`, and the ordering is this caller's to get
        // right — the transport cannot send a hello on the caller's behalf without changing what the
        // caller asked for.
        val hello = ensureHandshake(requireDeviceId())
        val catalog = gateway.catalog()

        // Persisted only after a successful read. A failed refresh leaves the previous cache in
        // place, so the screen can show what was last known instead of an empty list that reads like
        // "this gateway offers no models" — which is a different, and false, claim.
        catalogRecorder?.recordCatalog(
            entries = catalog.enabledModels().map { it.toCachedModel() },
            // The version from *this* connection's `server.hello`, read off the handshake this call
            // just used rather than from a cache that a reconnect may have invalidated.
            claudeCodeVersion = hello.claudeCodeVersion,
        )

        return catalog.toModels()
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.ClaudeP,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> {
        // Ordering is the safety property: validate, then handshake, then dispatch. A rejected
        // input or an incompatible gateway must both produce zero dispatches.
        val hasToolHost = toolHost !== ClaudePToolBridgeHost.NONE
        rejectUnsupportedInput(messages, params, toolsSupported = hasToolHost)

        // Resolved **once** and used for both the handshake and the fingerprint. Two separate
        // resolutions could observe different values — a revocation landing between them — and the
        // request would then bind a fingerprint to a device that never saw the handshake.
        val deviceId = requireDeviceId()
        ensureHandshake(deviceId)

        val modelAlias = params.model.modelId
        if (modelAlias.isBlank()) {
            throw ClaudePUnsupportedInputException(ClaudePUnsupportedInput.MISSING_MODEL)
        }

        val turn = messages.lastUserTurn()
            ?: throw ClaudePUnsupportedInputException(ClaudePUnsupportedInput.EMPTY_TURN)
        val systemPrompt = messages.systemPromptOrNull()
        // The last refusal before anything is built, and the one that makes this provider's
        // `StableSystemPromptProvider` claim true rather than aspirational — see the method.
        requireStableSystemPrompt(systemPrompt, params.stableSystemPromptExpectation)
        val requestId = requestIdFactory()
        val requestRemoteThreadId = params.claudePRemoteThreadId
            ?.takeIf(String::isNotBlank)
            ?: remoteThreadId

        // The catalog is frozen before dispatch, because it has to travel *in* `generation.start`.
        // With no tools — or no bridge host — this is [ClaudePToolPreparation.NONE], the snapshot
        // is null, and the frame is byte-for-byte what it was before the bridge existed.
        // The generation identity is the app's, and it is handed over rather than looked up: this
        // provider is handed messages and a model and knows nothing about which run it serves.
        // `params.tools` may be non-empty while the context is absent or incomplete, and the host
        // answers that with an empty catalog and a refusal — which is why the snapshot below is
        // keyed on the *catalog* and not on whether tools were declared. A generation whose calls
        // could not be bound sends no snapshot, so the Server registers no bridge tool and the
        // execution path is unreachable rather than merely unused.
        val preparation = toolHost.prepare(params.tools, params.claudePToolGenerationContext)
        val toolSnapshot = if (preparation.catalog.isEmpty) null else preparation.snapshot

        // Resolved **once**, before the fingerprint, and used for both the fingerprint and the
        // body. §7 requires every field a request carries to enter its fingerprint, so a shape
        // computed twice could be fingerprinted as one thing and sent as another — and the
        // Server would then deduplicate two genuinely different requests under one key.
        val shape = resolveRequestShape(params.claudePSessionBindingRequest)

        val fingerprint = ClaudePRequestFingerprint.compute(
            deviceId = deviceId,
            remoteThreadId = requestRemoteThreadId,
            remoteBranchId = shape.remoteBranchId,
            mode = shape.mode,
            modelAlias = modelAlias,
            systemPrompt = systemPrompt,
            turn = turn,
            // Part of the fingerprint for the same reason it is part of the generation's
            // identity: two requests that offer Claude different tools are not the same request,
            // and idempotency keyed without it would replay the first one's answer.
            toolSnapshot = toolSnapshot?.toString(),
            // Appended only when present, so a legacy request keeps the v1-r3 byte stream and
            // its frozen digest — which is what the conformance corpus pins.
            assistantId = shape.assistantId,
            bindingIntent = shape.bindingIntent,
        )
        val body = ClaudePGenerationStartBody(
            remoteThreadId = requestRemoteThreadId,
            remoteBranchId = shape.remoteBranchId,
            mode = shape.mode,
            modelAlias = modelAlias,
            systemPrompt = systemPrompt,
            turn = turn,
            rebuildHistory = null,
            toolSnapshot = toolSnapshot,
            limits = ClaudePGenerationLimits(maxOutputTokens = params.maxTokens),
            bindingIntent = shape.bindingIntent,
            assistantId = shape.assistantId,
        )

        // A provider stream owns exactly one remote dispatch. Collecting it a second time must
        // never create a second model request, so it fails loudly instead of silently re-running.
        val collected = AtomicBoolean(false)

        return flow {
            check(collected.compareAndSet(false, true)) {
                "ClaudePProvider.streamText() flow is single-shot; " +
                    "collect it once or start a new generation"
            }

            val attempt = ClaudePGenerationAttempt(gateway)
            try {
                val handle = gateway.startGeneration(requestId, fingerprint, body)
                attempt.bind(handle)

                // Opened before the first frame is pumped, so an invoke that arrives in the very
                // first batch already has a ledger to land in. A generation with no tools never
                // opens one, and `lookup` returning null is then the whole of the tool path.
                val toolFrames = if (preparation.catalog.isEmpty) {
                    null
                } else {
                    openToolGeneration(handle.generationId, requestId, preparation)
                }

                try {
                    pumpFrames(
                        frames = handle.frames(),
                        gate = ClaudePTerminalGate(),
                        generationId = handle.generationId,
                        onTerminal = attempt::markTerminal,
                        onToolFrame = { event -> toolFrames?.on(event) { emit(it) } },
                        // The generation id rides on the chunks of a *deferred* generation and
                        // nowhere else. The app needs it to write the `BIND_PENDING` record
                        // inside the transaction that commits the new branch variant — and that
                        // transaction happens after this stream ends, so the id has to reach the
                        // app before then. Nothing else consumes it: it is transient, so it
                        // cannot reach a request body or a stored message, and a legacy
                        // generation does not carry it at all, which keeps every non-M3 path
                        // byte-for-byte what it was.
                        emit = { chunk ->
                            emit(
                                if (shape.deferred) {
                                    chunk.copy(claudePGenerationId = handle.generationId)
                                } else {
                                    chunk
                                },
                            )
                        },
                    )
                } finally {
                    // Closed after the stream ends, whatever ended it: a terminal, a cancellation,
                    // or a disconnect. The close is what stops whatever the runtime is still doing
                    // for this generation and settles the calls by what it can **prove** — which
                    // is why it is here rather than in the happy path only.
                    //
                    // The execution binding is released here too, and for the same reason: a
                    // cancelled flow, a terminal and a disconnect are all "this generation is
                    // over", and a binding that outlived one would be a context still reachable
                    // by an id nothing should be able to name again. Released before the registry
                    // close, so a call the close is still settling is answered from a plan that is
                    // still in hand rather than from one that has already been dropped.
                    if (toolFrames != null) {
                        toolHost.closeGeneration(handle.generationId)
                        toolRegistry.close(handle.generationId)
                    }
                }
            } catch (cancelled: CancellationException) {
                // §8: an explicit cancel is the only thing that stops a remote generation. The
                // attempt tracker guarantees at most one RPC even if cancellation is observed
                // more than once.
                withContext(NonCancellable) { attempt.cancelOnce() }
                throw cancelled
            }
        }
    }

    /**
     * `session.bind` for one finished `deferred` generation (§6.1).
     *
     * The app calls this **after** it has committed the new branch variant and its
     * `BIND_PENDING` record, because the candidate must not become resumable before the graph it
     * describes exists — and because [branchId] is the digest of that committed graph, which
     * could not be computed any earlier.
     *
     * ## What this deliberately is not
     *
     * It is not a model call, not a generation, and not a retry of one. `generation.start` is
     * never reached from here, so a replay cannot buy a second CLI child or a second answer. The
     * only thing a second call does is re-send the same frame, which §6.1 makes idempotent.
     *
     * It is also not a loop. There is no polling, no timer and no background retry: the caller
     * decides when to re-send, and the two occasions it may are an explicit user action and a
     * reconnect boundary. A bind that re-sent itself would be a busy loop against a Server that
     * is under no obligation to answer.
     *
     * Every input is required to be usable rather than defaulted, because a bind built from a
     * placeholder would be a bind for a branch that does not exist — and the Server's answer to
     * that is `conflict`, which would close a branch that was in fact fine.
     */
    suspend fun bindSession(
        generationId: String,
        branchId: String,
        assistantId: String,
        requestRemoteThreadId: String = remoteThreadId,
    ): ClaudePSessionBindOutcome {
        require(generationId.isNotBlank()) { "A bind must name the generation it binds" }
        require(assistantId.isNotBlank()) { "A bind must name the assistant it binds" }
        require(branchId.matches(BRANCH_ID)) { "A bind must carry a canonical branch id" }
        require(requestRemoteThreadId.isNotBlank()) { "A bind must name the remote thread it binds" }

        // A bind travels on the same authenticated connection as everything else, so it goes
        // through the same handshake. Doing it here rather than assuming the caller already did
        // is what keeps a reconnect — the one occasion a replay is allowed — from re-sending a
        // bind onto a socket that has not re-introduced itself.
        ensureHandshake(requireDeviceId())

        return gateway.bindSessionOnce(
            generationId = generationId,
            remoteThreadId = requestRemoteThreadId,
            branchId = branchId,
            assistantId = assistantId,
        )
    }

    /**
     * Which connection the next call would travel on, or `null` when there is none.
     *
     * The app's only way to ask. A replay is a recovery for a bind whose answer was lost with a
     * connection, so "is this still the connection that attempt went out on?" has to be answerable
     * before the frame is sent — and this provider is the only thing the app holds that can reach
     * the transport. It creates no connection: a `null` here means the client is not carrying one,
     * not that one should be opened.
     */
    suspend fun connectionEpoch(): ClaudePConnectionEpoch? = gateway.connectionEpoch()

    /**
     * Aggregates one generation into a single chunk.
     *
     * It collects the *same* single-shot [streamText] flow rather than issuing its own request, so
     * it costs exactly one remote dispatch — never two.
     */
    override suspend fun generateText(
        providerSetting: ProviderSetting.ClaudeP,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk {
        var terminal: GenerationTerminal? = null
        var usage: TokenUsage? = null
        var model = params.model.modelId
        val parts = mutableListOf<UIMessagePart>()

        streamText(providerSetting, messages, params).collect { chunk ->
            chunk.usage?.let { usage = it }
            chunk.resolvedTerminal()?.let { terminal = it }
            model = chunk.model
            parts += chunk.choices.firstOrNull()?.delta?.parts.orEmpty()
        }

        val message = UIMessage(role = MessageRole.ASSISTANT, parts = parts)
        return MessageChunk(
            id = message.id.toString(),
            model = model,
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = null,
                    message = message,
                    finishReason = terminal?.providerReason,
                ),
            ),
            usage = usage,
            terminal = terminal,
        )
    }

    /**
     * Reconnect path: replays the gateway's bounded event buffer for an in-flight generation.
     *
     * It never calls `generation.start`. That is the entire point of `stream.resume` — a dropped
     * connection must not buy a second model invocation. When the buffer is gone the gateway
     * reports a terminal receipt or an explicitly unknown state, and the caller decides; nothing
     * here retries automatically.
     */
    suspend fun resumeStream(
        generationId: String,
        lastEventSeq: Long,
    ): Flow<ClaudePResumeOutcome> = flow {
        when (val resumed = gateway.resume(generationId, lastEventSeq)) {
            is ClaudePResumeResult.Terminal ->
                emit(ClaudePResumeOutcome.Terminal(resumed.receipt.safeState))

            ClaudePResumeResult.StateUnknown -> emit(ClaudePResumeOutcome.StateUnknown)

            is ClaudePResumeResult.Replayed -> {
                emit(ClaudePResumeOutcome.Replaying(resumed.outcome))
                // The **same** handler the live stream uses, found by this exact generation id.
                //
                // A replayed stream can carry `tool.invoke` frames — that is the whole reason a
                // bounded replay buffer exists — and leaving them unrouted meant a re-delivered
                // call was dropped on the floor and sat until its deadline. Routing them here is
                // safe precisely because the handler is the same one: it consults the ledger the
                // generation already has, so a call that settled is answered with its recorded
                // outcome and a call still running is left alone. Neither path reaches the
                // runtime, so a reconnect cannot buy a second side effect.
                //
                // `lookup` returning null is the fail-closed answer, not a gap to fill: an unknown
                // generation, one already closed, or one whose adapter this process no longer
                // holds must not have its frames executed — and, just as importantly, must not
                // have an adapter invented for it. Nothing here starts a generation; this method
                // never calls `generation.start`, and a reconnect still cannot buy a model
                // request.
                val toolFrames = toolRegistry.lookup(generationId)?.let { adapter ->
                    ClaudePToolFrameHandler(adapter = adapter, gateway = gateway, host = toolHost)
                }
                pumpFrames(
                    frames = flow { resumed.frames.forEach { emit(it) } },
                    gate = ClaudePTerminalGate(),
                    generationId = generationId,
                    onTerminal = {},
                    onToolFrame = { event ->
                        toolFrames?.on(event) { chunk -> emit(ClaudePResumeOutcome.Chunk(chunk)) }
                    },
                    emit = { emit(ClaudePResumeOutcome.Chunk(it)) },
                )
            }
        }
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> =
        throw ClaudePUnsupportedInputException(ClaudePUnsupportedInput.IMAGE_GENERATION)

    /**
     * Opens the tool ledger for one generation, or refuses the generation.
     *
     * A refusal here means the generation has tools Claude will be told about and no ledger to
     * answer them with — every invocation would then sit until its thirty-minute deadline. Failing
     * the generation is the loud version of that, and loud is what this path has to be: the
     * alternative is a turn that appears to work and silently answers nothing.
     */
    private suspend fun openToolGeneration(
        generationId: String,
        requestId: String,
        preparation: ClaudePToolPreparation,
    ): ClaudePToolFrameHandler {
        val binding = GenerationBinding(
            deviceRef = preparation.deviceRef,
            assistantId = preparation.assistantId,
            conversationId = preparation.conversationId,
            branchId = preparation.branchId,
            generationId = generationId,
            requestId = requestId,
            catalogDigest = preparation.catalog.digest,
            bridgeAbi = BridgeContract.BRIDGE_ABI,
            timeoutMs = preparation.timeoutMs,
        )

        val adapter = toolRegistry.open(binding, preparation.catalog).adapterOrNull
            ?: throw ClaudePGatewayException(ClaudePErrorCode.PROTOCOL_MISMATCH)

        // Bound here — after the ledger exists and before the first frame is pumped — so there is
        // no instant in which an invoke could be read for a generation whose execution plan is not
        // yet in hand. A host that cannot bind it is refusing to answer tools it would otherwise
        // have told Claude about, and a generation that cannot be answered is failed loudly rather
        // than run: the alternative is a peer blocked until the call's deadline.
        if (!toolHost.openGeneration(generationId, preparation)) {
            // The registry is closed as well as abandoned, not merely dropped: its tombstone is
            // what stops a re-delivered invoke from looking fresh to a later adapter.
            toolRegistry.close(generationId)
            throw ClaudePGatewayException(ClaudePErrorCode.PROTOCOL_MISMATCH)
        }

        return ClaudePToolFrameHandler(
            adapter = adapter,
            gateway = gateway,
            host = toolHost,
        )
    }

    // -----------------------------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------------------------

    /**
     * The three request fields that depend on whether the app resolved a branch.
     *
     * ## Why all three come from one function
     *
     * `mode`, `remote_branch_id` and `binding_intent` are only legal in the four combinations
     * §5.4's table lists. Deriving them separately — the mode here, the branch there — is how a
     * request ends up `auto` with no branch, or `deferred` with one, both of which the Server
     * refuses and neither of which the fingerprint would have caught. One function, one decision.
     *
     * ## The legacy shape
     *
     * A generation with no binding request is the CP1-A request: `mode: "new"`, the constructor's
     * opaque branch value, and **no** tail fields at all. The absence is load-bearing rather than
     * incidental — §12.12 appends `assistant_id` and `binding_intent` only when present, so a
     * request that carries neither produces the exact byte stream v1-r3 produced. That is what
     * keeps the frozen fingerprints in `claudep/conformance/` valid.
     */
    private fun resolveRequestShape(binding: ClaudePSessionBindingRequest?): RequestShape =
        when (binding?.intent) {
            null -> RequestShape(
                mode = MODE_NEW,
                remoteBranchId = remoteBranchId,
                assistantId = null,
                bindingIntent = null,
            )

            ClaudePSessionBindingIntent.IMMEDIATE -> RequestShape(
                mode = ClaudePSessionMode.AUTO,
                remoteBranchId = binding.branchId,
                assistantId = binding.assistantId,
                bindingIntent = ClaudePSessionBindingIntent.IMMEDIATE.wireValue,
            )

            ClaudePSessionBindingIntent.DEFERRED -> RequestShape(
                mode = ClaudePSessionMode.AUTO,
                // Absent, not empty: §5.3 encodes "this branch does not exist yet" with the
                // field's own presence flag and refuses `""` as a stand-in, because an empty
                // string is a *present but empty* identity and the two would share a fingerprint.
                remoteBranchId = null,
                assistantId = binding.assistantId,
                bindingIntent = ClaudePSessionBindingIntent.DEFERRED.wireValue,
            )
        }

    private data class RequestShape(
        val mode: String,
        val remoteBranchId: String?,
        val assistantId: String?,
        val bindingIntent: String?,
    ) {
        /** True when this generation will create its branch and therefore owes a bind. */
        val deferred: Boolean
            get() = bindingIntent == ClaudePSessionBindingIntent.DEFERRED.wireValue
    }

    /**
     * The entries a user may actually choose.
     *
     * One filter, applied by both the returned models and the persisted cache, so the list on screen
     * and the list in settings cannot come to disagree about which aliases the gateway offered.
     */
    private fun ClaudePCatalogResultBody.enabledModels(): List<ClaudePModelEntry> =
        models.filter { it.enabled && it.alias.isNotBlank() }

    /**
     * Maps a server catalog onto [Model]s.
     *
     * Only enabled aliases survive, only `text` input is honoured, and reasoning is advertised
     * solely when the server lists `reasoning_summary`. Note what is *absent*: no alias is derived
     * from `server.hello.claude_code_version`, so a CLI build string can never become a model.
     */
    private fun ClaudePCatalogResultBody.toModels(): List<Model> = enabledModels().map { it.toModel() }

    /**
     * The display half of a catalog entry.
     *
     * Narrower than [toModel] deliberately: this is rendered, never dispatched. The `enabled` flag is
     * not carried because [enabledModels] has already applied it — persisting it would invite a
     * later reader to treat the cache as the authority on what may run.
     */
    private fun ClaudePModelEntry.toCachedModel(): ClaudePCachedModel = ClaudePCachedModel(
        alias = alias,
        displayName = displayName.ifBlank { alias },
        reasoningSummary = features.contains(FEATURE_REASONING_SUMMARY),
    )

    private fun ClaudePModelEntry.toModel(): Model {
        val abilities = buildList {
            if (features.contains(FEATURE_REASONING_SUMMARY)) add(ModelAbility.REASONING)
            // ModelAbility.TOOL is deliberately never added: Phase 1 has no tool bridge, and
            // advertising it would let the agent loop hand Claude tools nobody can execute.
        }
        return Model(
            modelId = alias,
            displayName = displayName.ifBlank { alias },
            type = ModelType.CHAT,
            inputModalities = listOf(Modality.TEXT),
            outputModalities = listOf(Modality.TEXT),
            abilities = abilities,
        )
    }

    /**
     * Refuses to send a system instruction the app did not freeze as stable.
     *
     * ## Why this exists at all
     *
     * A Claude P session is continued later by a remote transport that can only do so safely if the
     * system instruction it continues under is the one the session was produced under. That makes
     * the instruction part of the session's identity, and an instruction that drifts turn to turn
     * makes continuation impossible — not "less cache-efficient", impossible.
     *
     * The app therefore computes its stable layout, freezes the result **before** any transformer
     * runs, and hands that value over on [TextGenerationParams.stableSystemPromptExpectation]. This
     * is the comparison, and it is deliberately made against a value that did not come from
     * `messages`: an expectation read back out of the messages being sent would agree with itself
     * no matter what the layout did, and would prove nothing.
     *
     * ## What a mismatch means
     *
     * It means something wrote to the system message that the app's stable layout does not account
     * for — a work-space prompt for an assistant that has one, a non-default message template, a
     * transformer added later. None of those can be shown to be stable from here, and guessing is
     * the one option this project refuses: a wrong guess leaks a drifting instruction into a session
     * identity, and the failure surfaces much later as a conversation that silently will not
     * continue. So the turn is refused, before the body is built, before any dispatch, and the
     * reason is a closed enum value rather than the content.
     *
     * ## The three outcomes
     *
     * | actual | expected | result |
     * |---|---|---|
     * | `null` | `null` | proceed — a request with no system instruction has nothing to drift |
     * | anything | `null` | refuse — "no expectation" is not evidence of stability |
     * | differs | present | refuse |
     */
    private fun requireStableSystemPrompt(actual: String?, expected: String?) {
        if (actual == expected) return
        throw ClaudePUnsupportedInputException(
            if (expected == null) {
                ClaudePUnsupportedInput.MISSING_SYSTEM_PROMPT_EXPECTATION
            } else {
                ClaudePUnsupportedInput.UNSTABLE_SYSTEM_PROMPT
            },
        )
    }

    /**
     * Rejects anything Phase 1 cannot faithfully send.
     *
     * Runs before the flow is constructed, so a rejected input cannot have reached the gateway.
     *
     * The deprecated `ToolCall` / `ToolResult` / `Search` branches are suppressed rather than
     * removed: `UIMessagePart` is sealed, so every subtype must be handled, and those variants are
     * still present in conversation history persisted by older builds. Dropping them would mean a
     * legacy tool turn silently reaching a provider that cannot execute tools.
     */
    @Suppress("DEPRECATION")
    private fun rejectUnsupportedInput(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        toolsSupported: Boolean,
    ) {
        // Declaring tools with no bridge host behind them is refused rather than silently
        // dropped: the user would otherwise believe Claude had tools this build cannot run.
        //
        // With a host it is not a refusal, and *not* a guarantee either — a host may still drop
        // every candidate (a name the frozen namespace cannot carry, an oversize description),
        // and the answer to that is an empty catalog rather than a failed turn. The catalog the
        // host actually produced is what is sent, so what Claude sees is always what the app
        // froze, never what the caller hoped for.
        if (!toolsSupported && params.tools.isNotEmpty()) {
            throw ClaudePUnsupportedInputException(ClaudePUnsupportedInput.TOOL_DEFINITION)
        }
        messages.forEach { message ->
            message.parts.forEach { part ->
                when (part) {
                    is UIMessagePart.Text -> Unit
                    is UIMessagePart.Reasoning -> Unit
                    is UIMessagePart.Image ->
                        throw ClaudePUnsupportedInputException(ClaudePUnsupportedInput.IMAGE)

                    is UIMessagePart.Video ->
                        throw ClaudePUnsupportedInputException(ClaudePUnsupportedInput.VIDEO)

                    is UIMessagePart.Audio ->
                        throw ClaudePUnsupportedInputException(ClaudePUnsupportedInput.AUDIO)

                    is UIMessagePart.Document ->
                        throw ClaudePUnsupportedInputException(ClaudePUnsupportedInput.DOCUMENT)

                    is UIMessagePart.Tool,
                    is UIMessagePart.ToolCall,
                    is UIMessagePart.ToolResult,
                    ->
                        throw ClaudePUnsupportedInputException(ClaudePUnsupportedInput.TOOL_CALL)

                    is UIMessagePart.Search ->
                        throw ClaudePUnsupportedInputException(ClaudePUnsupportedInput.SEARCH_RESULT)
                }
            }
        }
    }

    /**
     * The paired device id, or [ClaudePErrorCode.NOT_PAIRED].
     *
     * A configured provider returning `null` or a blank value is a *failure*, not a fallback: the
     * device has no provable identity, so there is nothing safe to dispatch.
     */
    private suspend fun requireDeviceId(): String {
        val provider = deviceIdProvider ?: return deviceId
        return provider()?.takeIf { it.isNotBlank() }
            ?: throw ClaudePGatewayException(ClaudePErrorCode.NOT_PAIRED)
    }

    /**
     * Performs `client.hello` for [deviceId].
     *
     * ### Caching, and why a dynamic resolver does not get it
     *
     * The static CP1-A path keeps its cache: the device id never changes, so a negotiated hello stays
     * valid.
     *
     * A dynamic resolver must **not** reuse one. A re-pairing can produce a different device
     * identity, and a cached hello would then be replayed under the new identity — hello announcing
     * device A's session while the request belongs to device B. Caching per device id would not fix
     * it either: nothing proves a re-pairing always yields a new id, so the cache key could collide
     * across two pairings.
     *
     * The bounded cost is one extra `client.hello` per request, which the gateway is required to
     * answer cheaply. Re-validating is the safe direction; reusing is the direction that silently
     * carries an old identity forward.
     */
    private suspend fun ensureHandshake(deviceId: String): ClaudePServerHelloBody {
        if (deviceIdProvider != null) return negotiateHello(deviceId)

        cachedServerHello?.let { return it }
        return handshakeMutex.withLock {
            cachedServerHello?.let { return@withLock it }
            negotiateHello(deviceId).also { cachedServerHello = it }
        }
    }

    private suspend fun negotiateHello(deviceId: String): ClaudePServerHelloBody {
        val hello = gateway.hello(
            ClaudePClientHelloBody(
                appVersion = appVersion,
                protocolVersions = listOf("v1"),
                deviceId = deviceId,
                // The transport owns the nonce and signature: they must come from a Keystore key the
                // caller cannot reach, so these are placeholders the real client replaces.
                nonce = "transport-signed",
                signature = "transport-signed",
                capabilities = listOf("text_stream", "cancel", "receipt_query"),
            ),
        )
        // A gateway speaking another major may have changed the meaning of events we think we
        // understand. Stop here: this must produce zero dispatches, not a best-effort run.
        if (!ClaudePProtocol.acceptsServerProtocolVersion(hello.protocolVersion)) {
            throw ClaudePGatewayException(ClaudePErrorCode.PROTOCOL_MISMATCH)
        }
        return hello
    }

    private companion object {
        /** §5.1's legacy mode, kept as the shape a generation with no resolved branch sends. */
        val MODE_NEW = ClaudePSessionMode.NEW
        const val FEATURE_REASONING_SUMMARY = "reasoning_summary"

        /** The 64-lowercase-hex shape every branch identity has. */
        val BRANCH_ID = Regex("^[0-9a-f]{64}$")
    }
}

// -------------------------------------------------------------------------------------------
// Frame routing
// -------------------------------------------------------------------------------------------

/** What one validated frame produced. */
private sealed interface ClaudePFrameRouting {
    /** Nothing user-visible: an acknowledgement, or content suppressed after a terminal. */
    data object Ignore : ClaudePFrameRouting

    data class Chunk(val chunk: MessageChunk) : ClaudePFrameRouting

    /**
     * A frame the bridge has to answer rather than render.
     *
     * Distinct from [Ignore] because answering one means suspending: an `tool.invoke` is a
     * *question*, and the peer is blocked on it until this side replies. A frame silently
     * dropped on the acknowledgement path would leave that peer waiting out the whole deadline.
     */
    data class Tool(val event: ClaudePServerEvent) : ClaudePFrameRouting
}

/**
 * Parses and routes one raw server frame.
 *
 * This is the single place where transport bytes become content, which is what makes the
 * "unknown events are never text or a terminal" rule enforceable rather than merely documented.
 * A protocol violation throws; it must never be downgraded to a dropped frame.
 */
private fun routeFrame(
    raw: String,
    gate: ClaudePTerminalGate,
    generationId: String?,
): ClaudePFrameRouting = when (val inbound = ClaudePProtocol.parseInbound(raw)) {
    // Unknown optional events are neither text nor a terminal, so dropping them can never
    // fabricate an answer or end a generation early.
    ClaudePInbound.IgnoredUnknownEvent -> ClaudePFrameRouting.Ignore

    is ClaudePInbound.Rejected -> throw ClaudePGatewayException(
        ClaudePErrorCode.PROTOCOL_MISMATCH,
        rejection = inbound.reason,
        generationId = generationId,
    )

    is ClaudePInbound.Event -> gate.route(inbound.event)
}

/**
 * Turns one routed event into at most one chunk.
 *
 * [ClaudePTerminalGate] is the single local authority on terminals: the first
 * completed/cancelled/failed wins and everything after it is dropped, so a replayed duplicate or
 * a cancel racing a completion can never yield two terminals or trailing content.
 */
private fun ClaudePTerminalGate.route(event: ClaudePServerEvent): ClaudePFrameRouting = when (event) {
    is ClaudePServerEvent.ReasoningDelta -> {
        val text = event.body.summary
        if (!acceptsDeltas || text.isEmpty()) {
            ClaudePFrameRouting.Ignore
        } else {
            ClaudePFrameRouting.Chunk(
                event.deltaChunk(
                    UIMessagePart.Reasoning(
                        reasoning = text,
                        source = ReasoningSource.PROVIDER_NATIVE,
                    ),
                ),
            )
        }
    }

    is ClaudePServerEvent.TextDelta -> {
        val text = event.body.text
        if (!acceptsDeltas || text.isEmpty()) {
            ClaudePFrameRouting.Ignore
        } else {
            ClaudePFrameRouting.Chunk(event.deltaChunk(UIMessagePart.Text(text)))
        }
    }

    is ClaudePServerEvent.UsageUpdated -> {
        if (!acceptsDeltas) {
            ClaudePFrameRouting.Ignore
        } else {
            ClaudePFrameRouting.Chunk(event.usageChunk(event.body))
        }
    }

    is ClaudePServerEvent.Completed -> {
        if (!tryAccept(ClaudePTerminalKind.COMPLETED)) {
            ClaudePFrameRouting.Ignore
        } else {
            val stop = event.body.safeStopReason
            ClaudePFrameRouting.Chunk(
                event.terminalChunk(
                    category = when (stop) {
                        ClaudePStopReason.MAX_TOKENS -> FinishCategory.LENGTH
                        ClaudePStopReason.TIMEOUT -> FinishCategory.INCOMPLETE
                        else -> FinishCategory.STOP
                    },
                    providerReason = stop.wireValue,
                    usage = event.body.usage,
                    reasoningChars = event.body.reasoningChars,
                    answerChars = event.body.answerChars,
                ),
            )
        }
    }

    is ClaudePServerEvent.Cancelled -> {
        if (!tryAccept(ClaudePTerminalKind.CANCELLED)) {
            ClaudePFrameRouting.Ignore
        } else {
            ClaudePFrameRouting.Chunk(
                event.terminalChunk(
                    category = FinishCategory.CANCELLED,
                    providerReason = "cancelled",
                    usage = event.body.usage,
                ),
            )
        }
    }

    is ClaudePServerEvent.Failed -> {
        if (!tryAccept(ClaudePTerminalKind.FAILED)) {
            ClaudePFrameRouting.Ignore
        } else {
            ClaudePFrameRouting.Chunk(
                event.terminalChunk(
                    category = FinishCategory.FAILED,
                    providerReason = "failed",
                    usage = event.body.usage,
                    // Bounded enum name only. Raw gateway text is never surfaced or persisted.
                    incompleteDetail = "claude_p_error_${event.body.safeCode.name.lowercase()}",
                ),
            )
        }
    }

    // Handshake / catalog / acknowledgement events carry no user-visible content.
    //
    // `session.bind.result` belongs here for the same reason `receipt.result` does: it is the
    // answer to an RPC the caller is already awaiting by `request_id`, so by the time a copy of
    // it reaches a generation's stream there is nothing left for it to mean. Routing it as
    // anything else would let a bind answer arrive twice — once as a return value, once as a
    // stream event — and only one of those may be allowed to settle a branch.
    is ClaudePServerEvent.ServerHello,
    is ClaudePServerEvent.CatalogResult,
    is ClaudePServerEvent.GenerationAccepted,
    is ClaudePServerEvent.GenerationStarted,
    is ClaudePServerEvent.MessageStarted,
    is ClaudePServerEvent.ReceiptResult,
    is ClaudePServerEvent.StreamResumeResult,
    is ClaudePServerEvent.SessionBindResult,
    -> ClaudePFrameRouting.Ignore

    // The three frames the bridge must answer. Note that they are routed **past** the terminal
    // gate: a cancel arriving after a terminal is still a fact about a call, and the gate's job
    // is to suppress content, not to decide what the ledger is allowed to hear.
    is ClaudePServerEvent.ToolInvoke,
    is ClaudePServerEvent.ToolCancel,
    is ClaudePServerEvent.ToolQueryResult,
    -> ClaudePFrameRouting.Tool(event)
}

/** Collects a frame stream through [routeFrame], tracking terminal state as it goes. */
private suspend fun pumpFrames(
    frames: Flow<String>,
    gate: ClaudePTerminalGate,
    generationId: String?,
    onTerminal: () -> Unit,
    onToolFrame: suspend (ClaudePServerEvent) -> Unit = {},
    emit: suspend (MessageChunk) -> Unit,
) {
    frames.collect { raw ->
        val routing = routeFrame(raw, gate, generationId)
        // Recorded before the chunk is emitted. A consumer that stops collecting *because* it saw
        // the terminal (a `take(n)`, an upstream timeout) is not a user cancel, and sending one
        // there would race a generation that had already finished.
        if (gate.terminalSeen) onTerminal()
        when (routing) {
            ClaudePFrameRouting.Ignore -> Unit
            is ClaudePFrameRouting.Chunk -> emit(routing.chunk)
            is ClaudePFrameRouting.Tool -> onToolFrame(routing.event)
        }
    }
}

// -------------------------------------------------------------------------------------------
// Tool frames
// -------------------------------------------------------------------------------------------

/**
 * Answers the three inbound tool frames for one generation.
 *
 * ## What this is, and the one thing it is not
 *
 * It is the join between the bridge's ledger and the app's runtime, and it is deliberately thin.
 * Every question of *policy* — may this tool run, does the user have to approve it, what does it
 * do — is answered by [ClaudePToolBridgeHost] behind [ClaudePToolBridgeHost.execute], which is
 * the app's own runtime. What lives here is only the order the frames are answered in, because
 * that order is the part the wire contract actually constrains.
 *
 * ## Why a repeat never executes twice
 *
 * [BridgeToolAdapter.onInvoke] records the call **before** returning `Execute`, so by the time the
 * runtime is asked to run anything there is already a record. A re-delivered invoke — after a
 * reconnect, or from a Server that never saw the answer — therefore finds that record: `Await`
 * while it is still running, `Replay` once it has settled. Neither reaches the runtime, which is
 * the whole of the double-execution defence and the reason this class does not need one of its
 * own.
 */
internal class ClaudePToolFrameHandler(
    private val adapter: BridgeToolAdapter,
    private val gateway: ClaudePGatewayClient,
    private val host: ClaudePToolBridgeHost,
) {
    private val generationId: String = adapter.generationId

    suspend fun on(event: ClaudePServerEvent, emit: suspend (MessageChunk) -> Unit) {
        when (event) {
            is ClaudePServerEvent.ToolInvoke -> onInvoke(event.body, emit)
            is ClaudePServerEvent.ToolCancel -> onCancel(event.body)
            is ClaudePServerEvent.ToolQueryResult -> onQueryResult(event.body)
            else -> Unit
        }
    }

    private suspend fun onInvoke(
        body: ClaudePToolInvokeBody,
        emit: suspend (MessageChunk) -> Unit,
    ) {
        when (val decision = adapter.onInvoke(body.toolCallId, body.toolName, body.arguments)) {
            is BridgeInvokeDecision.Execute -> {
                // The adapter is handed over as the **claimant**, and it is the only ledger the
                // app can reach: it is bound to the generation whose frame this is, so a call
                // belonging to any other generation cannot be claimed through it. That binding is
                // structural — the app is given a closure rather than a lookup key — which is why
                // the cross-generation search this design forbids has no expression on that side.
                val execution = host.execute(
                    invocation = decision.invocation,
                    status = publishingStatusTo(emit),
                    claims = adapter,
                )
                // Shown before the answer is sent, so the conversation already holds the call
                // when the terminal arrives and the two cannot be observed out of order.
                //
                // `null` continuation: this is the call's *outcome*, not a publication. There is
                // no card being raised here and therefore no barrier to raise for it.
                execution.part?.let { emit(toolCallChunk(it, pendingContinuation = null)) }

                val outcome = execution.outcome
                when (adapter.complete(outcome.toolCallId, outcome.state, outcome.body)) {
                    is BridgeCompletion.Settled -> report(outcome)
                    // Already announced, or superseded by a terminal that won the race. Either
                    // way the peer has an answer and this one would be a second.
                    is BridgeCompletion.Repeat, is BridgeCompletion.Superseded -> Unit
                }
            }

            // A repeat of a call that has settled. The recorded answer is the answer, and
            // re-sending it is the idempotent-recovery path the Server's own `apply` expects.
            is BridgeInvokeDecision.Replay -> report(decision.outcome)

            // Still in flight. The answer is already coming; starting a second wait would be
            // starting a second execution in every way that matters.
            BridgeInvokeDecision.Await -> Unit

            // Refused, and nothing ran. Nothing is sent because there is no outcome to report:
            // the Server's own deadline is what concludes a call Android declined.
            is BridgeInvokeDecision.Refused -> Unit

            // The generation is ending. The close is settling this call, and a second opinion
            // from here would race it.
            BridgeInvokeDecision.GenerationClosing -> Unit
        }
    }

    /**
     * Propagates a cancel to the runtime's **real** cancellation capability.
     *
     * The adapter's decision is what makes this idempotent: it returns `Propagate` only the first
     * time it sees a cancel for a call that has not settled, so asking the runtime to stop twice
     * — which is how one stop becomes two instructions — cannot happen here.
     *
     * Nothing is answered. A cancel is not a conclusion, and the conclusion is whatever the
     * runtime reports when it is done stopping.
     */
    private suspend fun onCancel(body: ClaudePToolCancelBody) {
        if (adapter.onCancel(body.toolCallId) === BridgeCancelDecision.Propagate) {
            host.executions.requestStop(generationId, listOf(body.toolCallId))
        }
    }

    /**
     * The Server's answer to a `tool.query`.
     *
     * Consulted and not merged: it is authoritative about the *Server's* ledger, and this side's
     * record is authoritative about its own. Nothing is written, because there is nothing this
     * answer could change — a call this generation holds is settled by its own runtime, and a
     * call it does not hold is one it must not adopt an opinion about.
     */
    private fun onQueryResult(body: ClaudePToolQueryResultBody) {
        adapter.onQueryResult(body.toolCallId, body.safeState.toBridgeState(), body.body)
    }

    /**
     * What **this generation** holds for one tool call, or `null` when it holds nothing.
     *
     * ## What a query is, and the three things it is not
     *
     * It is a read of one record, keyed by this generation's own adapter and an exact tool call id.
     * That is the whole of it:
     *
     * - it does **not** execute anything, and there is no path from here to a runtime;
     * - it does **not** re-dispatch — a call this side already answered answers with its recorded
     *   outcome, which is the idempotent-recovery answer the Server's own `apply` expects;
     * - it does **not** move a deadline. The instant stored on the record was fixed when the call
     *   was admitted, and nothing here reads the clock at all, so a caller cannot extend a call's
     *   life by asking about it repeatedly.
     *
     * A call that is still in flight answers `pending`, which is accurate: it says the answer is
     * coming, and it is what stops a reconnecting peer from re-running a tool that is already
     * running. A call this generation has no record of answers `not_found` — and after a process
     * restart that is the honest answer rather than an invitation to execute, which is why nothing
     * here may be turned into a retry.
     *
     * It is deliberately bound to one generation: the adapter is the generation, so there is no
     * way to ask this question *across* generations even by naming a conversation or an assistant.
     */
    fun queryToolCall(toolCallId: String?): ToolCallOutcome? = adapter.query(toolCallId)

    /**
     * Sends one terminal, if it is one Android may send.
     *
     * [ClaudePToolFrames.asOutboundResult] is the same gate the wire rules already enforce, and
     * the `null` is load-bearing: a state Android may not report produces **no frame at all**
     * rather than a guessed one, because the Server closes the connection on a malformed outcome
     * — which would conclude every other call this device is holding.
     */
    private suspend fun report(outcome: ToolCallOutcome) {
        val frame = ClaudePToolFrames.asOutboundResult(outcome) ?: return
        gateway.sendToolResult(generationId, frame)
    }

    /**
     * Puts a call's status into the conversation this generation is being streamed into.
     *
     * ## Why the host needs this at all
     *
     * A call that needs the user's approval cannot be run until they tap, and the card they tap has
     * to be in the conversation **before** the wait begins — so it has to be published from inside
     * `execute`, not returned from it. `BridgeToolExecution.part` is the *result*, and a result
     * that only arrives after the decision is a decision nobody could make.
     *
     * ## Why a failure is reported rather than swallowed
     *
     * An emit that fails means the card is not on screen, so nothing can be tapped, so the call
     * must not run. Returning [ClaudePToolStatusPublication.Refused] hands that fact back to the
     * host, which is the only side that can act on it. A cancellation is not a failure to publish
     * and is not converted into one: it is rethrown, because the generation is ending and the
     * close is settling the call.
     *
     * ## Why only three of the statuses become a part
     *
     * These are the states in which the **user** has something to do or see about a call that is
     * still open. The terminal ones are not rendered here — the call's outcome part comes back
     * through `BridgeToolExecution`, which the app shaped and which carries whatever it decided.
     */
    private fun publishingStatusTo(emit: suspend (MessageChunk) -> Unit): ClaudePToolStatusSink =
        ClaudePToolStatusSink { update ->
            val part = update.asInterimToolPart()
                ?: return@ClaudePToolStatusSink ClaudePToolStatusPublication.Accepted
            try {
                // The app's declared continuation rides with the card, so the conversation
                // authority can tell an in-flight publication from an ordinary pending approval
                // without asking which provider is running. Absent stays absent: this layer never
                // supplies a default, because a default here would be a decision it must not make.
                emit(toolCallChunk(part, update.pendingContinuation))
                ClaudePToolStatusPublication.Accepted
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ClaudePToolStatusPublication.Refused("tool_status_not_published")
            }
        }

    private fun toolCallChunk(
        part: UIMessagePart.Tool,
        pendingContinuation: String?,
    ): MessageChunk = MessageChunk(
        id = generationId,
        model = PROVIDER_MODEL_FALLBACK,
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(part)),
                message = null,
                finishReason = null,
            ),
        ),
        // Carried verbatim, and only ever for a card the app raised. The alternative part below
        // — the call's *outcome* — is not a publication and carries no continuation.
        pendingApprovalContinuation = pendingContinuation,
    )
}

// -------------------------------------------------------------------------------------------
// Chunk construction
// -------------------------------------------------------------------------------------------

private fun ClaudePServerEvent.deltaChunk(part: UIMessagePart): MessageChunk = MessageChunk(
    id = envelope.generationId ?: "claude-p",
    model = modelAliasHint(),
    choices = listOf(
        UIMessageChoice(
            index = 0,
            delta = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(part)),
            message = null,
            finishReason = null,
        ),
    ),
)

private fun ClaudePServerEvent.usageChunk(usage: ClaudePUsageBody): MessageChunk = MessageChunk(
    id = envelope.generationId ?: "claude-p",
    model = modelAliasHint(),
    choices = emptyList(),
    usage = usage.toTokenUsage(),
    terminal = null,
)

private fun ClaudePServerEvent.terminalChunk(
    category: FinishCategory,
    providerReason: String,
    usage: ClaudePUsageBody?,
    reasoningChars: Int = 0,
    answerChars: Int = 0,
    incompleteDetail: String? = null,
): MessageChunk = MessageChunk(
    id = envelope.generationId ?: "claude-p",
    model = modelAliasHint(),
    choices = listOf(
        UIMessageChoice(
            index = 0,
            delta = null,
            message = null,
            finishReason = providerReason,
        ),
    ),
    usage = usage?.toTokenUsage(),
    terminal = GenerationTerminal(
        terminalSeen = true,
        category = category,
        providerReason = providerReason,
        incompleteDetail = incompleteDetail,
        reasoningChars = reasoningChars,
        answerChars = answerChars,
    ),
)

private fun ClaudePServerEvent.modelAliasHint(): String = when (this) {
    is ClaudePServerEvent.GenerationStarted -> body.modelAlias.ifBlank { PROVIDER_MODEL_FALLBACK }
    else -> PROVIDER_MODEL_FALLBACK
}

private const val PROVIDER_MODEL_FALLBACK = "claude_p"

private fun ClaudePUsageBody.toTokenUsage(): TokenUsage = TokenUsage(
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    cachedTokens = cachedTokens,
    totalTokens = if (totalTokens > 0) totalTokens else promptTokens + completionTokens,
    latestPromptTokens = promptTokens,
    latestCompletionTokens = completionTokens,
    latestCachedTokens = cachedTokens,
    providerCallCount = 1,
)

// -------------------------------------------------------------------------------------------
// Cancellation
// -------------------------------------------------------------------------------------------

/**
 * Sends `generation.cancel` at most once for one generation attempt.
 *
 * Cancellation can be observed more than once (flow cancellation plus an upstream timeout, for
 * example). The gateway treats cancel as idempotent, but the client still must not spam it.
 */
internal class ClaudePGenerationAttempt(private val gateway: ClaudePGatewayClient) {
    private val cancelSent = AtomicBoolean(false)

    @Volatile
    private var generationId: String? = null

    @Volatile
    private var terminalSeen: Boolean = false

    fun bind(handle: ClaudePGenerationHandle) {
        generationId = handle.generationId
    }

    /** Records that a terminal was already observed, so cancelling would be pointless. */
    fun markTerminal() {
        terminalSeen = true
    }

    /**
     * Cancels exactly once.
     *
     * If a terminal was already observed there is nothing left to stop, so no RPC is issued —
     * which is what keeps a cancel/completion race from producing a second terminal.
     */
    suspend fun cancelOnce(): Boolean {
        val id = generationId ?: return false
        if (terminalSeen) return false
        if (!cancelSent.compareAndSet(false, true)) return false
        return try {
            gateway.cancel(id, ClaudePCancelReason.USER_REQUESTED)
            true
        } catch (_: ClaudePGatewayException) {
            // The generation may have ended underneath us. That is not a client-visible failure:
            // the receipt, not the cancel, is the authority on the terminal.
            false
        }
    }
}

// -------------------------------------------------------------------------------------------
// Input gating
// -------------------------------------------------------------------------------------------

/** Input shapes Phase 1 must refuse rather than silently narrow. */
enum class ClaudePUnsupportedInput {
    IMAGE,
    DOCUMENT,
    AUDIO,
    VIDEO,
    TOOL_CALL,
    TOOL_DEFINITION,
    SEARCH_RESULT,
    IMAGE_GENERATION,
    MISSING_MODEL,
    EMPTY_TURN,

    /**
     * The app froze no system-prompt expectation, but there is a system instruction to send.
     *
     * Distinct from [UNSTABLE_SYSTEM_PROMPT] because the two have different causes: this one means
     * the layout never ran for this request (or ran for a provider that does not need one), which is
     * a wiring fault, while the other means the layout ran and something afterwards disagreed with it.
     */
    MISSING_SYSTEM_PROMPT_EXPECTATION,

    /**
     * The system instruction being sent is not the one the app froze.
     *
     * Something wrote to the system message after the stable layout was frozen and the app has no
     * proof that what it wrote is stable. See `requireStableSystemPrompt` for why refusing beats
     * guessing.
     */
    UNSTABLE_SYSTEM_PROMPT,
}

/** Thrown before any dispatch. Carries a bounded enum, never the rejected content. */
class ClaudePUnsupportedInputException(
    val input: ClaudePUnsupportedInput,
) : IllegalArgumentException("claude_p_unsupported_input: ${input.name}")

/** Outcome of a reconnect, so callers can distinguish replay from an unprovable state. */
sealed interface ClaudePResumeOutcome {
    data class Replaying(val kind: ClaudePResumeKind) : ClaudePResumeOutcome

    data class Chunk(val chunk: MessageChunk) : ClaudePResumeOutcome

    data class Terminal(val state: ClaudePGenerationState) : ClaudePResumeOutcome

    /** The gateway cannot prove a terminal. The user decides; we never auto-retry. */
    data object StateUnknown : ClaudePResumeOutcome
}

// -------------------------------------------------------------------------------------------
// Message -> turn compilation
// -------------------------------------------------------------------------------------------

private fun List<UIMessage>.lastUserTurn(): ClaudePTurn? {
    val message = lastOrNull { it.role == MessageRole.USER } ?: return null
    val parts = message.parts.filterIsInstance<UIMessagePart.Text>()
        .filter { it.text.isNotEmpty() }
        .map { ClaudePTurnPart(type = "text", text = it.text) }
    if (parts.isEmpty()) return null
    return ClaudePTurn(role = "user", parts = parts)
}

private fun List<UIMessage>.systemPromptOrNull(): String? {
    val system = filter { it.role == MessageRole.SYSTEM }
        .flatMap { it.parts.filterIsInstance<UIMessagePart.Text>() }
        .map { it.text }
        .filter { it.isNotEmpty() }
    return system.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
}

/**
 * The status as a tool part, for the states the user can act on, or `null` for the rest.
 *
 * File-level and `internal` so it can be tested directly: the mapping is the seam where a
 * protocol-agnostic status becomes something a conversation can show, and a mistake in it is the
 * kind that looks fine — a card that never appears, or one that claims a decision nobody made.
 *
 * The arguments are carried **verbatim** so the card shows what the peer actually asked for rather
 * than a restatement of it. The approval state is set from the status alone: this decides nothing
 * about whether the call may run, and a `Pending` part here is a request for a decision, never a
 * decision. The terminal statuses have no part because the call's outcome part comes back through
 * `BridgeToolExecution`, shaped by the app that ran it.
 */
internal fun ClaudePToolStatusUpdate.asInterimToolPart(): UIMessagePart.Tool? {
    val approvalState = when (status) {
        ClaudePToolCallStatus.PENDING_APPROVAL -> ToolApprovalState.Pending
        ClaudePToolCallStatus.APPROVED -> ToolApprovalState.Approved
        ClaudePToolCallStatus.DENIED -> ToolApprovalState.Denied()
        ClaudePToolCallStatus.RUNNING,
        ClaudePToolCallStatus.COMPLETED,
        ClaudePToolCallStatus.FAILED,
        ClaudePToolCallStatus.CANCELLED,
        -> return null
    }
    return UIMessagePart.Tool(
        toolCallId = toolCallId,
        toolName = toolName,
        input = arguments.toString(),
        output = emptyList(),
        approvalState = approvalState,
    )
}
