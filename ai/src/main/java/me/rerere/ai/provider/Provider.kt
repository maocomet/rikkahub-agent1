package me.rerere.ai.provider

import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.claudep.ClaudePToolGenerationContext
import me.rerere.ai.ui.ImageAspectRatio
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage

// 提供商实现
// 采用无状态设计，使用时除了需要传入需要的参数外，还需要传入provider setting作为参数
interface Provider<T : ProviderSetting> {
    suspend fun listModels(providerSetting: T): List<Model>

    /**
     * Returns a provider-owned hard capability only when it is locally/versionedly trusted.
     * Remote catalog metadata must remain in [Model.contextLength] and must not be promoted by
     * the default implementation. Providers whose capability depends on local runtime settings
     * may resolve it at request time.
     */
    suspend fun resolveTrustedContextWindowTokens(
        providerSetting: T,
        model: Model,
    ): Int? = model.trustedContextWindowTokens?.takeIf { it > 0 }

    suspend fun getBalance(providerSetting: T): String {
        return "TODO"
    }

    suspend fun generateText(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk

    suspend fun streamText(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk>

    suspend fun generateEmbedding(
        providerSetting: T,
        params: EmbeddingGenerationParams,
    ): EmbeddingGenerationResult {
        error("Embedding generation is not supported")
    }

    suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem>

    suspend fun editImage(
        providerSetting: ProviderSetting,
        params: ImageEditParams,
    ): Flow<ImageGenerationItem> {
        error("Image edit is not supported")
    }
}

/**
 * Deliberate host capability marker for provider transports whose cancellation boundary has a
 * versioned fence. Merely returning a cancellable [Flow] is not sufficient: background work may
 * be pre-empted by foreground Chat, so late native/network callbacks must be unable to publish
 * output into a later request.
 *
 * Implementations must only advertise an ABI after that fence has deterministic cancellation and
 * late-callback tests. Background callers fail closed when this marker is absent or blank.
 */
interface FencedTextGenerationProvider {
    val cancellationFenceAbi: String
}

/** Content-free proof observed at the last provider-owned boundary before any request bytes. */
interface BackgroundDispatchAttestation {
    fun opaqueDigestSha256(): String
}

/**
 * Content-free identity of the exact local runtime that may execute one background request.
 *
 * This is deliberately stricter than a model/catalog identity. Every field below can change the
 * bytes interpreted by the native runtime, its context boundary, or its decoding behaviour. The
 * app folds this value into the durable provider-configuration digest; a provider must reproduce
 * the same value immediately before native dispatch or fail closed.
 *
 * [artifactSha256] is the digest of the model file, never its path. ABI labels and the accelerator
 * label must be versioned, privacy-safe identifiers. Background generation is text-only,
 * tool-free, cache-isolated, and non-speculative by contract rather than by caller convention.
 */
data class BackgroundRuntimeAttestation(
    val schemaVersion: Int = SCHEMA_VERSION,
    val providerRuntimeAbi: String,
    val sdkAbi: String,
    val cancellationFenceAbi: String,
    val artifactSha256: String,
    val forceCpu: Boolean,
    val accelerator: String,
    val contextWindowTokens: Int,
    val topK: Int,
    val topP: Double,
    val temperature: Double,
    val promptRendererAbi: String,
    val nativeToolAbi: String,
    val textOnly: Boolean = true,
    val toolsEmpty: Boolean = true,
    val constrainedDecoding: Boolean = false,
    val speculativeDecoding: Boolean = false,
    val providerCacheDisabled: Boolean = true,
) : BackgroundDispatchAttestation {
    init {
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported background runtime attestation" }
        require(SAFE_ABI.matches(providerRuntimeAbi)) { "Invalid provider runtime ABI" }
        require(SAFE_ABI.matches(sdkAbi)) { "Invalid runtime SDK ABI" }
        require(SAFE_ABI.matches(cancellationFenceAbi)) { "Invalid cancellation ABI" }
        require(SHA256.matches(artifactSha256)) { "Invalid model artifact identity" }
        require(SAFE_ABI.matches(accelerator)) { "Invalid accelerator identity" }
        require(contextWindowTokens > 0) { "Background context window must be positive" }
        require(topK > 0) { "Background topK must be positive" }
        require(topP.isFinite() && topP > 0.0 && topP <= 1.0) { "Invalid background topP" }
        require(temperature.isFinite() && temperature >= 0.0) {
            "Invalid background temperature"
        }
        require(SAFE_ABI.matches(promptRendererAbi)) { "Invalid prompt renderer ABI" }
        require(SAFE_ABI.matches(nativeToolAbi)) { "Invalid native tool ABI" }
        require(textOnly) { "Background local execution must be text-only" }
        require(toolsEmpty) { "Background local execution must not expose tools" }
        require(!constrainedDecoding) { "Background local execution must not constrain tools" }
        require(!speculativeDecoding) { "Background local execution must not speculate" }
        require(providerCacheDisabled) { "Background local execution must use isolated cache state" }
    }

    override fun toString(): String =
        "BackgroundRuntimeAttestation(schema=$schemaVersion, runtime=$providerRuntimeAbi, " +
            "sdk=$sdkAbi, cancellation=$cancellationFenceAbi, artifact=<redacted>, " +
            "forceCpu=$forceCpu, accelerator=$accelerator, context=$contextWindowTokens, " +
            "sampler=<redacted>, promptAbi=$promptRendererAbi, nativeAbi=$nativeToolAbi, " +
            "textOnly=$textOnly, toolsEmpty=$toolsEmpty, speculative=$speculativeDecoding)"

    /**
     * Standalone, content-free identity suitable for a durable provider-input manifest. Fields are
     * length-prefixed and ordered explicitly; floating-point values use their exact IEEE-754 bit
     * representation. Adding a field requires a schema/version change and digest-vector update.
     */
    override fun opaqueDigestSha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(label: String, value: String) {
            digest.updateLengthPrefixed(label.toByteArray(Charsets.UTF_8))
            digest.updateLengthPrefixed(value.toByteArray(Charsets.UTF_8))
        }
        field("domain", "rikkahub-background-runtime-attestation-v1")
        field("schema_version", schemaVersion.toString())
        field("provider_runtime_abi", providerRuntimeAbi)
        field("sdk_abi", sdkAbi)
        field("cancellation_fence_abi", cancellationFenceAbi)
        field("artifact_sha256", artifactSha256)
        field("force_cpu", forceCpu.canonicalBit())
        field("accelerator", accelerator)
        field("context_window_tokens", contextWindowTokens.toString())
        field("top_k", topK.toString())
        field("top_p_bits", topP.toBits().toString())
        field("temperature_bits", temperature.toBits().toString())
        field("prompt_renderer_abi", promptRendererAbi)
        field("native_tool_abi", nativeToolAbi)
        field("text_only", textOnly.canonicalBit())
        field("tools_empty", toolsEmpty.canonicalBit())
        field("constrained_decoding", constrainedDecoding.canonicalBit())
        field("speculative_decoding", speculativeDecoding.canonicalBit())
        field("provider_cache_disabled", providerCacheDisabled.canonicalBit())
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    companion object {
        const val SCHEMA_VERSION: Int = 1
        private val SHA256 = Regex("^[0-9a-f]{64}$")
        private val SAFE_ABI = Regex("^[A-Za-z0-9._-]{1,96}$")
    }
}

/** Exact first-party API shape supported by the prepared remote background transport. */
enum class RemoteBackgroundApiFamily(
    val canonicalOrigin: String,
    val canonicalPath: String,
) {
    OPENAI_CHAT_COMPLETIONS_V1(
        canonicalOrigin = "https://api.openai.com",
        canonicalPath = "/v1/chat/completions",
    ),
    OPENAI_RESPONSES_V1(
        canonicalOrigin = "https://api.openai.com",
        canonicalPath = "/v1/responses",
    ),
    OPENCODE_GO_CHAT_COMPLETIONS_V1(
        canonicalOrigin = "https://opencode.ai",
        canonicalPath = "/zen/go/v1/chat/completions",
    ),
}

/**
 * Durable, content-free inputs shared by the planner and the prepared provider transport.
 * Prompt bytes and credentials are represented only by already-frozen one-way identities.
 */
data class RemoteBackgroundDispatchContext(
    val providerIdentitySha256: String,
    val modelIdentitySha256: String,
    val configurationIdentitySha256: String,
    val templateVersion: String,
    val inputIdentitySha256: String,
    val providerRequestKey: String,
    val maxOutputTokens: Int,
) {
    init {
        listOf(
            providerIdentitySha256,
            modelIdentitySha256,
            configurationIdentitySha256,
            inputIdentitySha256,
        ).forEach { require(LOWER_SHA256.matches(it)) { "Invalid remote dispatch identity" } }
        require(SAFE_REMOTE_LABEL.matches(templateVersion)) { "Invalid template version" }
        require(SAFE_PROVIDER_REQUEST_KEY.matches(providerRequestKey)) {
            "Invalid provider request key"
        }
        require(maxOutputTokens > 0) { "Remote output cap must be positive" }
    }

    override fun toString(): String =
        "RemoteBackgroundDispatchContext(template=$templateVersion, " +
            "maxOutputTokens=$maxOutputTokens, identities=<redacted>)"
}

/**
 * Versioned remote dispatch proof. It deliberately has no local artifact/runtime fields: a
 * network request must never masquerade as a [BackgroundRuntimeAttestation].
 */
data class RemoteBackgroundDispatchAttestation(
    val schemaVersion: Int = SCHEMA_VERSION,
    val apiFamily: RemoteBackgroundApiFamily,
    val context: RemoteBackgroundDispatchContext,
    val transportAbi: String = TRANSPORT_ABI,
    val cancellationFenceAbi: String = CANCELLATION_FENCE_ABI,
    val streaming: Boolean = true,
    val storeResponse: Boolean = false,
    val toolsEnabled: Boolean = false,
    val automaticRetryEnabled: Boolean = false,
) : BackgroundDispatchAttestation {
    init {
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported remote dispatch attestation" }
        require(transportAbi == TRANSPORT_ABI) { "Unsupported remote transport ABI" }
        require(cancellationFenceAbi == CANCELLATION_FENCE_ABI) {
            "Unsupported remote cancellation ABI"
        }
        require(streaming && !storeResponse && !toolsEnabled && !automaticRetryEnabled) {
            "Unsafe remote background request shape"
        }
    }

    override fun opaqueDigestSha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(label: String, value: String) {
            digest.updateLengthPrefixed(label.toByteArray(Charsets.UTF_8))
            digest.updateLengthPrefixed(value.toByteArray(Charsets.UTF_8))
        }
        field("domain", "rikkahub-remote-background-dispatch-attestation-v1")
        field("schema_version", schemaVersion.toString())
        field("api_family", apiFamily.name)
        field("canonical_origin", apiFamily.canonicalOrigin)
        field("canonical_path", apiFamily.canonicalPath)
        field("transport_abi", transportAbi)
        field("cancellation_fence_abi", cancellationFenceAbi)
        field("provider_identity_sha256", context.providerIdentitySha256)
        field("model_identity_sha256", context.modelIdentitySha256)
        field("configuration_identity_sha256", context.configurationIdentitySha256)
        field("template_version", context.templateVersion)
        field("input_identity_sha256", context.inputIdentitySha256)
        field("provider_request_key", context.providerRequestKey)
        field("max_output_tokens", context.maxOutputTokens.toString())
        field("streaming", streaming.canonicalBit())
        field("store_response", storeResponse.canonicalBit())
        field("tools_enabled", toolsEnabled.canonicalBit())
        field("automatic_retry_enabled", automaticRetryEnabled.canonicalBit())
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    override fun toString(): String =
        "RemoteBackgroundDispatchAttestation(schema=$schemaVersion, family=$apiFamily, " +
            "transport=$transportAbi, cancellation=$cancellationFenceAbi, context=$context)"

    companion object {
        const val SCHEMA_VERSION: Int = 1
        const val TRANSPORT_ABI: String = "okhttp-sse-prepared-v1"
        const val CANCELLATION_FENCE_ABI: String = "okhttp-sse-cancel-v1"
    }
}

fun expectedRemoteBackgroundDispatchAttestationSha256(
    providerIdentitySha256: String,
    modelIdentitySha256: String,
    configurationIdentitySha256: String,
    templateVersion: String,
    inputIdentitySha256: String,
    providerRequestKey: String,
    maxOutputTokens: Int,
    apiFamily: RemoteBackgroundApiFamily,
): String = RemoteBackgroundDispatchAttestation(
    apiFamily = apiFamily,
    context = RemoteBackgroundDispatchContext(
        providerIdentitySha256 = providerIdentitySha256,
        modelIdentitySha256 = modelIdentitySha256,
        configurationIdentitySha256 = configurationIdentitySha256,
        templateVersion = templateVersion,
        inputIdentitySha256 = inputIdentitySha256,
        providerRequestKey = providerRequestKey,
        maxOutputTokens = maxOutputTokens,
    ),
).opaqueDigestSha256()

private val LOWER_SHA256 = Regex("^[0-9a-f]{64}$")
private val SAFE_REMOTE_LABEL = Regex("^[A-Za-z0-9._-]{1,64}$")
private val SAFE_PROVIDER_REQUEST_KEY = Regex("^[A-Za-z0-9._:-]{1,192}$")

private fun MessageDigest.updateLengthPrefixed(bytes: ByteArray) {
    val size = bytes.size
    update((size ushr 24).toByte())
    update((size ushr 16).toByte())
    update((size ushr 8).toByte())
    update(size.toByte())
    update(bytes)
}

private fun Boolean.canonicalBit(): String = if (this) "1" else "0"

/**
 * A lazily prepared, single-use background execution. Preparation, final attestation, native
 * dispatch, cancellation, and cleanup happen while collecting [streamText]. Implementations must
 * reject a second collection and must close/wipe request-owned conversation and KV state in a
 * `finally` block.
 */
interface PreparedBackgroundTextGeneration {
    val expectedAttestation: BackgroundRuntimeAttestation

    /**
     * [onDispatchStarted] runs exactly once after the provider's final runtime/artifact check and
     * immediately before entering native transport. It is suspending so a caller can durably
     * fence the attempt. If it fails or is cancelled, provider bytes must not be sent.
     */
    fun streamText(
        onDispatchStarted: BackgroundProviderDispatchCallback =
            BackgroundProviderDispatchCallback.NO_OP,
    ): Flow<MessageChunk>
}

fun interface BackgroundProviderDispatchCallback {
    suspend fun onDispatchStarted(attestation: BackgroundDispatchAttestation)

    companion object {
        val NO_OP = BackgroundProviderDispatchCallback { }
    }
}

interface PreparedRemoteBackgroundTextGeneration {
    val expectedAttestation: RemoteBackgroundDispatchAttestation

    /** Callback failure/cancellation must occur before the transport creates a network call. */
    fun streamText(
        onDispatchStarted: BackgroundProviderDispatchCallback =
            BackgroundProviderDispatchCallback.NO_OP,
    ): Flow<MessageChunk>
}

/** Explicit opt-in for a versioned, prepared, cancellation-fenced remote transport. */
interface AttestedRemoteBackgroundTextGenerationProvider<T : ProviderSetting> {
    val remoteBackgroundDispatchAbi: String

    fun prepareRemoteBackgroundTextGeneration(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        expectedAttestation: RemoteBackgroundDispatchAttestation,
    ): PreparedRemoteBackgroundTextGeneration
}

/**
 * Explicit opt-in for a provider that can attest and isolate local background execution.
 * Ordinary [Provider.streamText] calls are unaffected. A background host must never infer this
 * capability from [FencedTextGenerationProvider] alone.
 */
interface AttestedBackgroundTextGenerationProvider<T : ProviderSetting> {
    val backgroundRuntimeAttestationAbi: String

    suspend fun attestBackgroundRuntime(
        providerSetting: T,
        model: Model,
    ): BackgroundRuntimeAttestation

    fun prepareBackgroundTextGeneration(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        expectedAttestation: BackgroundRuntimeAttestation,
    ): PreparedBackgroundTextGeneration
}

/**
 * Opaque, privacy-safe namespace for provider/local prefix caches.
 *
 * The app must hash conversation, assistant, memory-scope, and final injected-memory identities
 * before crossing this API. Raw UUIDs and memory IDs are deliberately rejected here and must never
 * appear in cache traces. [compilerRevision] invalidates a prefix when prompt rendering changes.
 */
class ProviderCacheIdentity private constructor(
    private val opaqueDigest: String,
    private val compilerRevision: String,
) {
    fun redactedPrefix(): String = opaqueDigest.take(12)

    override fun equals(other: Any?): Boolean =
        other is ProviderCacheIdentity &&
            opaqueDigest == other.opaqueDigest &&
            compilerRevision == other.compilerRevision

    override fun hashCode(): Int = 31 * opaqueDigest.hashCode() + compilerRevision.hashCode()

    override fun toString(): String =
        "ProviderCacheIdentity(${redactedPrefix()}..., revision=$compilerRevision)"

    companion object {
        private val SHA256_HEX = Regex("^[0-9a-fA-F]{64}$")
        private val REVISION = Regex("^[A-Za-z0-9._-]{1,64}$")

        fun fromOpaqueDigest(
            opaqueSha256: String,
            compilerRevision: String,
        ): ProviderCacheIdentity {
            require(SHA256_HEX.matches(opaqueSha256)) {
                "Provider cache identity must be an opaque SHA-256 hex digest, never a raw ID"
            }
            require(REVISION.matches(compilerRevision)) {
                "compilerRevision must be a non-empty, privacy-safe revision label"
            }
            return ProviderCacheIdentity(
                opaqueDigest = opaqueSha256.lowercase(),
                compilerRevision = compilerRevision,
            )
        }
    }
}

@Serializable
data class TextGenerationParams(
    val model: Model,
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    val tools: List<Tool> = emptyList(),
    val reasoningLevel: ReasoningLevel = ReasoningLevel.OFF,
    /**
     * For non-chat system calls, omit a disabled reasoning field instead of sending a
     * provider-specific disabled value. The default preserves ordinary chat request shapes.
     */
    @Transient
    val omitReasoningConfigurationWhenOff: Boolean = false,
    /**
     * Retry hint for network-backed providers. A provider may use an isolated connection pool
     * for this attempt so a stream that was pinned to a degraded keep-alive connection is not
     * retried on that same transport. It is deliberately transient and never enters a request
     * body.
     */
    @Transient
    val freshConnection: Boolean = false,
    /**
     * Stable, content-free provider-effect identity for a durable background attempt. It is never
     * serialized into an ordinary request body by default: only a remote transport that explicitly
     * implements a versioned idempotency capability may map it to its provider header/protocol.
     */
    @Transient
    val stableProviderIdempotencyKey: String? = null,
    /** Prepared official-cloud background dispatch contract; never serialized into provider JSON. */
    @Transient
    val remoteBackgroundDispatchContext: RemoteBackgroundDispatchContext? = null,
    /** Local/provider cache namespace only. It is never serialized into an API request. */
    @Transient
    val providerCacheIdentity: ProviderCacheIdentity? = null,
    /**
     * The app-side run identity a Claude P tool call is bound to.
     *
     * Transient for the same reason as the two above: those are Android-internal identities and
     * must not reach a request body. `null` means the app did not supply one, which the bridge
     * treats as "no tools" rather than as permission to guess a generation.
     */
    @Transient
    val claudePToolGenerationContext: ClaudePToolGenerationContext? = null,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
) {
    init {
        require(
            stableProviderIdempotencyKey == null ||
                stableProviderIdempotencyKey.matches(Regex("^[A-Za-z0-9._:-]{1,192}$")),
        ) { "Invalid stable provider idempotency key" }
        require(
            remoteBackgroundDispatchContext == null ||
                stableProviderIdempotencyKey == remoteBackgroundDispatchContext.providerRequestKey,
        ) { "Remote dispatch context and provider request key disagree" }
    }
}

@Serializable
data class ImageGenerationParams(
    val model: Model,
    val prompt: String,
    val numOfImages: Int = 1,
    val aspectRatio: ImageAspectRatio = ImageAspectRatio.SQUARE,
    val partialImages: Int = 2,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
)

@Serializable
data class ImageEditParams(
    val model: Model,
    val prompt: String,
    val images: List<String>,
    val numOfImages: Int = 1,
    val aspectRatio: ImageAspectRatio = ImageAspectRatio.SQUARE,
    val partialImages: Int = 2,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
)

@Serializable
data class EmbeddingGenerationParams(
    val model: Model,
    val input: List<String>,
    val dimensions: Int? = null,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
)

@Serializable
data class EmbeddingGenerationResult(
    val model: String,
    val embeddings: List<List<Float>>,
)

@Serializable
data class CustomHeader(
    val name: String,
    val value: String
)

@Serializable
data class CustomBody(
    val key: String,
    val value: JsonElement
)
