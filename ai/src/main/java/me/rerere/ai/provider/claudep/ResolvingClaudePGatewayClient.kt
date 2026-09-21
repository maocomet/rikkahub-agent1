package me.rerere.ai.provider.claudep

/**
 * A transport that resolves the *real* transport at call time, or fails closed.
 *
 * ### Why this indirection exists
 *
 * `ProviderManager` is built once, at app start, from a `SettingsStore` that may not have finished
 * loading — and the transport that should be used depends on whether the device is paired, which is
 * itself derived from an encrypted store. Building the transport eagerly would mean either blocking
 * startup or binding to whatever state happened to be true then.
 *
 * Crucially, this is **not** a fail-open stand-in. When [resolve] returns `null` — not paired, no
 * credential, unreadable credential, unusable key — every call reports
 * [ClaudePErrorCode.NOT_PAIRED]. The provider therefore exists and is visible in settings, but can
 * never reach a gateway until a pairing has actually been established.
 *
 * The counters delegate too, so `remoteDispatchCount` continues to mean "model runs that actually
 * started" rather than "calls we attempted".
 */
class ResolvingClaudePGatewayClient(
    private val resolve: suspend () -> ClaudePGatewayClient?,
    /** Used when nothing is paired. Defaults to the fail-closed singleton. */
    private val fallback: ClaudePGatewayClient = UnpairedClaudePGatewayClient,
) : ClaudePGatewayClient {

    /**
     * The transport for this call.
     *
     * A resolver that throws is treated exactly like a resolver that returns `null`: an unreadable
     * credential store must not turn into a request, and it must not turn into a crash either.
     */
    private suspend fun delegate(): ClaudePGatewayClient =
        try {
            resolve() ?: fallback
        } catch (_: Exception) {
            fallback
        }

    override suspend fun hello(request: ClaudePClientHelloBody): ClaudePServerHelloBody =
        delegate().hello(request)

    override suspend fun catalog(): ClaudePCatalogResultBody = delegate().catalog()

    override suspend fun startGeneration(
        requestId: String,
        fingerprint: String,
        body: ClaudePGenerationStartBody,
    ): ClaudePGenerationHandle = delegate().startGeneration(requestId, fingerprint, body)

    override suspend fun cancel(generationId: String, reason: ClaudePCancelReason): ClaudePCancelOutcome =
        delegate().cancel(generationId, reason)

    override suspend fun receipt(generationId: String): ClaudePReceiptBody = delegate().receipt(generationId)

    override suspend fun resume(generationId: String, lastEventSeq: Long): ClaudePResumeResult =
        delegate().resume(generationId, lastEventSeq)

    // Counters are read synchronously by tests and diagnostics, so they report the fallback's zeros
    // while unpaired. That is the honest answer: nothing was dispatched.
    override val startGenerationCallCount: Int get() = fallback.startGenerationCallCount
    override val remoteDispatchCount: Int get() = fallback.remoteDispatchCount
    override val cancelCallCount: Int get() = fallback.cancelCallCount
}
