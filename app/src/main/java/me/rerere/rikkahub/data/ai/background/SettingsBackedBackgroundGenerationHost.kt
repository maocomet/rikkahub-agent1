package me.rerere.rikkahub.data.ai.background

import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.AttestedBackgroundTextGenerationProvider
import me.rerere.ai.provider.AttestedRemoteBackgroundTextGenerationProvider
import me.rerere.ai.provider.BackgroundProviderDispatchCallback
import me.rerere.ai.provider.BackgroundRuntimeAttestation
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.FencedTextGenerationProvider
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.PreparedBackgroundTextGeneration
import me.rerere.ai.provider.PreparedRemoteBackgroundTextGeneration
import me.rerere.ai.provider.RemoteBackgroundApiFamily
import me.rerere.ai.provider.RemoteBackgroundDispatchAttestation
import me.rerere.ai.provider.expectedRemoteBackgroundDispatchAttestationSha256
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.execution.ExecutionTokenProvider
import me.rerere.rikkahub.learning.model.LearningModelCandidate
import me.rerere.rikkahub.learning.model.LearningModelResolution
import me.rerere.rikkahub.learning.model.LearningModelResolutionFailure
import me.rerere.rikkahub.learning.model.LearningModelResolutionPolicy
import me.rerere.rikkahub.learning.model.LearningModelResolver
import me.rerere.rikkahub.learning.model.LearningProviderKind
import me.rerere.rikkahub.learning.model.ResolvedLearningModel
import kotlin.uuid.Uuid

private const val BACKGROUND_HOST_IDENTITY_ABI = "background-host-identity-v2"
private const val BACKGROUND_RUNTIME_IDENTITY_ABI = "background-runtime-identity-v1"
private const val MAX_CANDIDATE_LABEL_CHARS = 96
private const val DEFAULT_LOCAL_CANDIDATE_LABEL = "Local LiteRT model"
private const val DEFAULT_LOCAL_PROVIDER_LABEL = "Local / LiteRT"
private const val DEFAULT_REMOTE_PROVIDER_LABEL = "Official cloud provider"
private const val DEFAULT_REMOTE_MODEL_LABEL = "Cloud model"
private val SAFE_CAPABILITY_ABI = Regex("^[A-Za-z0-9._-]{1,64}$")
private val LOWER_SHA256 = Regex("^[0-9a-f]{64}$")

/**
 * Explicit, model-scoped user policy. There is intentionally no broad "all configured models"
 * fallback: a future UI must persist the exact model identity selected for background work.
 */
data class BackgroundGenerationUserPolicy(
    val backgroundWorkAuthorized: Boolean = false,
    val authorizedModelIdentityDigests: Set<String> = emptySet(),
    val allowRemoteReflection: Boolean = false,
    val remoteReflectionProviderIdentityDigest: String? = null,
    val remoteReflectionModelIdentityDigest: String? = null,
) {
    init {
        require(authorizedModelIdentityDigests.all(LOWER_SHA256::matches)) {
            "Invalid authorized background model identity"
        }
        require(
            (remoteReflectionProviderIdentityDigest == null &&
                remoteReflectionModelIdentityDigest == null) ||
                (remoteReflectionProviderIdentityDigest?.matches(LOWER_SHA256) == true &&
                    remoteReflectionModelIdentityDigest?.matches(LOWER_SHA256) == true),
        ) { "Remote Reflection target must be an exact provider/model pair" }
        require(
            !allowRemoteReflection ||
                remoteReflectionProviderIdentityDigest != null &&
                remoteReflectionModelIdentityDigest != null,
        ) { "Remote Reflection consent requires an exact disclosed target" }
    }
}

fun interface BackgroundGenerationUserPolicySource {
    fun current(): BackgroundGenerationUserPolicy
}

/** Production P0 policy. It cannot infer consent from Chat, Memory, or Dreaming settings. */
object DisabledBackgroundGenerationUserPolicySource : BackgroundGenerationUserPolicySource {
    override fun current(): BackgroundGenerationUserPolicy = BackgroundGenerationUserPolicy()
}

/**
 * One immutable host snapshot. Provider objects are referenced, not serialized or copied, so API
 * keys and custom headers never enter a second settings representation. Its string form is always
 * content-free.
 */
class BackgroundGenerationSettingsSnapshot internal constructor(
    internal val initialized: Boolean,
    internal val providers: List<ProviderSetting>,
    internal val userPolicy: BackgroundGenerationUserPolicy,
) {
    override fun toString(): String =
        "BackgroundGenerationSettingsSnapshot(initialized=$initialized, " +
            "providerCount=${providers.size}, policy=<redacted>)"
}

fun interface BackgroundGenerationSettingsSource {
    fun current(): BackgroundGenerationSettingsSnapshot
}

/** Reads the live SettingsStore value at claim, bind, authorization, and pre-dispatch time. */
class SettingsStoreBackgroundGenerationSettingsSource(
    private val settingsStore: SettingsStore,
    private val userPolicySource: BackgroundGenerationUserPolicySource,
) : BackgroundGenerationSettingsSource {
    override fun current(): BackgroundGenerationSettingsSnapshot {
        val settings = settingsStore.settingsFlow.value
        return BackgroundGenerationSettingsSnapshot(
            initialized = !settings.init,
            providers = settings.providers,
            userPolicy = userPolicySource.current(),
        )
    }
}

/** Content-free identities frozen into a durable Learning job. */
data class BackgroundGenerationHostIdentity(
    val providerKind: LearningProviderKind,
    val providerIdentityDigest: String,
    val modelIdentityDigest: String,
    /** Keyed digest: secrets cannot be tested offline from the durable value. */
    val configurationDigest: String,
) {
    init {
        require(providerIdentityDigest.matches(LOWER_SHA256))
        require(modelIdentityDigest.matches(LOWER_SHA256))
        require(configurationDigest.matches(LOWER_SHA256))
    }

    override fun toString(): String =
        "BackgroundGenerationHostIdentity(providerKind=$providerKind, identity=<redacted>)"
}

data class BackgroundGenerationPublicIdentity(
    val providerKind: LearningProviderKind,
    val providerIdentityDigest: String,
    val modelIdentityDigest: String,
)

/** User-visible class of a selectable background model. Remote kinds are origin allowlisted. */
enum class BackgroundAuthorizationCandidateKind(
    val isRemote: Boolean,
    /** The provider currently exposes the dispatch-fenced background adapter required by P1. */
    val backgroundAdapterReady: Boolean,
) {
    LOCAL_LITERT(isRemote = false, backgroundAdapterReady = true),
    OFFICIAL_OPENAI(isRemote = true, backgroundAdapterReady = true),
    OFFICIAL_OPENCODE_GO(isRemote = true, backgroundAdapterReady = true),
    OFFICIAL_GOOGLE(isRemote = true, backgroundAdapterReady = false),
    OFFICIAL_ANTHROPIC(isRemote = true, backgroundAdapterReady = false),
}

/**
 * Classifies only explicitly allowlisted official HTTPS API origins. Generic compatible gateways,
 * relays, Vertex AI, userinfo, non-default ports, query/fragment suffixes, and encoded path
 * spellings are excluded. OpenCode Go is a distinct official product endpoint, never inferred
 * from a provider name or another opencode.ai path.
 */
fun ProviderSetting.officialBackgroundRemoteKindOrNull():
    BackgroundAuthorizationCandidateKind? = when (this) {
    is ProviderSetting.OpenAI -> when {
        baseUrl.isExactOfficialHttpsEndpoint("api.openai.com", "/v1") &&
            (useResponseApi || chatCompletionsPath == "/chat/completions") ->
            BackgroundAuthorizationCandidateKind.OFFICIAL_OPENAI
        baseUrl.isExactOfficialHttpsEndpoint("opencode.ai", "/zen/go/v1") &&
            !useResponseApi && chatCompletionsPath == "/chat/completions" ->
            BackgroundAuthorizationCandidateKind.OFFICIAL_OPENCODE_GO
        else -> null
    }
    is ProviderSetting.Google -> if (
        !vertexAI && !useServiceAccount &&
        baseUrl.isExactOfficialHttpsEndpoint("generativelanguage.googleapis.com", "/v1beta")
    ) {
        BackgroundAuthorizationCandidateKind.OFFICIAL_GOOGLE
    } else {
        null
    }
    is ProviderSetting.Claude -> if (
        baseUrl.isExactOfficialHttpsEndpoint("api.anthropic.com", "/v1")
    ) {
        BackgroundAuthorizationCandidateKind.OFFICIAL_ANTHROPIC
    } else {
        null
    }
    is ProviderSetting.AICore,
    is ProviderSetting.LiteRtLocal,
    is ProviderSetting.Codex,
    // Claude P is excluded from background work by contract (claudep/06 D-010). Its runtime lives
    // on a user-operated VPS that may be offline, asleep or revoked, and it has no fenced
    // cancellation ABI on this side yet — so it must never be offered as an authorization
    // candidate, regardless of what its origin looks like.
    is ProviderSetting.ClaudeP,
    -> null
}

private fun String.isExactOfficialHttpsEndpoint(expectedHost: String, expectedPath: String): Boolean {
    val endpoint = runCatching { URI(this) }.getOrNull() ?: return false
    return endpoint.scheme?.lowercase(Locale.ROOT) == "https" &&
        endpoint.host?.lowercase(Locale.ROOT) == expectedHost &&
        endpoint.rawAuthority?.lowercase(Locale.ROOT) in setOf(expectedHost, "$expectedHost:443") &&
        endpoint.rawUserInfo == null &&
        endpoint.port in setOf(-1, 443) &&
        endpoint.rawPath in setOf(expectedPath, "$expectedPath/") &&
        endpoint.rawQuery == null &&
        endpoint.rawFragment == null
}

/**
 * One exact local or official-cloud model that the user may authorize for Agent Learning.
 * Credentials and endpoint strings deliberately stay behind the host snapshot.
 */
data class BackgroundAuthorizationCandidate(
    val kind: BackgroundAuthorizationCandidateKind,
    val modelUuid: Uuid,
    val providerIdentityDigest: String,
    val modelIdentityDigest: String,
    val providerLabel: String,
    val modelLabel: String,
) {
    init {
        require(providerIdentityDigest.matches(LOWER_SHA256))
        require(modelIdentityDigest.matches(LOWER_SHA256))
        require(providerLabel.isNotBlank() && providerLabel.length <= MAX_CANDIDATE_LABEL_CHARS)
        require(modelLabel.isNotBlank() && modelLabel.length <= MAX_CANDIDATE_LABEL_CHARS)
    }

    val isRemote: Boolean get() = kind.isRemote

    val displayLabel: String
        get() = if (isRemote) "$providerLabel / $modelLabel" else modelLabel

    override fun toString(): String =
        "BackgroundAuthorizationCandidate(kind=$kind, labels=<redacted>, identity=<redacted>)"
}

/**
 * Public, content-free row that a settings surface may present for explicit authorization.
 * It intentionally carries no provider object, artifact path, endpoint, credential, or model ID
 * string. Listing a row never selects it and never changes [BackgroundGenerationUserPolicy].
 */
data class LocalBackgroundAuthorizationCandidate(
    val modelUuid: Uuid,
    val modelIdentityDigest: String,
    val displayLabel: String,
) {
    init {
        require(modelIdentityDigest.matches(LOWER_SHA256))
        require(displayLabel.isNotBlank() && displayLabel.length <= MAX_CANDIDATE_LABEL_CHARS)
    }

    override fun toString(): String =
        "LocalBackgroundAuthorizationCandidate(label=<redacted>, identity=<redacted>)"
}

/** Exact, content-free target shown before the user grants remote Reflection consent. */
data class RemoteReflectionDisclosureTarget(
    val providerIdentityDigest: String,
    val modelIdentityDigest: String,
    val providerLabel: String,
    val modelLabel: String,
) {
    init {
        require(providerIdentityDigest.matches(LOWER_SHA256))
        require(modelIdentityDigest.matches(LOWER_SHA256))
        require(providerLabel.isNotBlank() && providerLabel.length <= MAX_CANDIDATE_LABEL_CHARS)
        require(modelLabel.isNotBlank() && modelLabel.length <= MAX_CANDIDATE_LABEL_CHARS)
    }

    override fun toString(): String =
        "RemoteReflectionDisclosureTarget(labels=<redacted>, identities=<redacted>)"
}

/** Converts a secret-bearing canonical digest into a durable, app-keyed opaque digest. */
fun interface BackgroundGenerationConfigurationKeyer {
    fun key(
        canonicalConfigurationDigest: String,
        providerId: String,
        modelId: String,
    ): String
}

/**
 * Uses the existing Keystore-backed host token primitive with domain separation. The intermediate
 * SHA-256 over provider credentials/configuration never leaves this call; only the two keyed
 * 128-bit halves are persisted as the 256-bit configuration identity.
 */
class KeystoreBackgroundGenerationConfigurationKeyer(
    private val tokens: ExecutionTokenProvider,
) : BackgroundGenerationConfigurationKeyer {
    override fun key(
        canonicalConfigurationDigest: String,
        providerId: String,
        modelId: String,
    ): String {
        require(canonicalConfigurationDigest.matches(LOWER_SHA256))
        val domain = "bgcfg1_$canonicalConfigurationDigest"
        val left = tokens.ownerTokenFor(domain, providerId, modelId, "left")
        val right = tokens.ownerTokenFor(domain, providerId, modelId, "right")
        return (left + right).also { keyed ->
            require(keyed.matches(LOWER_SHA256)) { "Invalid keyed configuration identity" }
        }
    }
}

/**
 * Versioned canonical identity factory. Provider/model IDs are safe SHA-256 identities;
 * request-affecting configuration (including credentials and custom request fields) is fed
 * incrementally into a keyed digest and is never materialized as JSON or logged.
 */
class BackgroundGenerationHostIdentityFactory(
    private val configurationKeyer: BackgroundGenerationConfigurationKeyer,
) {
    fun publicIdentity(
        provider: ProviderSetting,
        model: Model,
    ): BackgroundGenerationPublicIdentity {
        val providerKind = provider.learningKind()
        val providerIdentity = CanonicalSha256()
            .string("abi", BACKGROUND_HOST_IDENTITY_ABI)
            .string("type", provider.typeTag())
            .string("provider_id", provider.id.toString())
            .publicProviderPolicyApplicability(provider)
            .finishHex()
        val modelIdentity = CanonicalSha256()
            .string("abi", BACKGROUND_HOST_IDENTITY_ABI)
            .string("provider_identity", providerIdentity)
            .string("model_id_uuid", model.id.toString())
            .string("provider_model_id", model.modelId)
            .publicModelPolicyApplicability(model)
            .finishHex()
        return BackgroundGenerationPublicIdentity(
            providerKind = providerKind,
            providerIdentityDigest = providerIdentity,
            modelIdentityDigest = modelIdentity,
        )
    }

    fun identify(
        provider: ProviderSetting,
        model: Model,
        runtimeAttestation: BackgroundRuntimeAttestation? = null,
    ): BackgroundGenerationHostIdentity {
        val public = publicIdentity(provider, model)
        val canonicalConfiguration = CanonicalSha256()
            .string("abi", BACKGROUND_HOST_IDENTITY_ABI)
            .providerConfiguration(provider)
            .modelConfiguration(model)
        runtimeAttestation?.let(canonicalConfiguration::runtimeAttestation)
        val canonicalConfigurationDigest = canonicalConfiguration.finishHex()
        val keyedConfiguration = configurationKeyer.key(
            canonicalConfigurationDigest = canonicalConfigurationDigest,
            providerId = provider.id.toString(),
            modelId = model.id.toString(),
        )
        return BackgroundGenerationHostIdentity(
            providerKind = public.providerKind,
            providerIdentityDigest = public.providerIdentityDigest,
            modelIdentityDigest = public.modelIdentityDigest,
            configurationDigest = keyedConfiguration,
        )
    }
}

/** ProviderManager/credential state stays behind this request-time handle. */
interface BackgroundTextProviderHandle {
    val cancellationFenceAbi: String?
    val backgroundRuntimeAttestationAbi: String? get() = null
    val remoteBackgroundDispatchAbi: String? get() = null
    val remoteBackgroundApiFamily: RemoteBackgroundApiFamily? get() = null

    suspend fun resolveTrustedContextWindowTokens(model: Model): Int?

    suspend fun attestBackgroundRuntime(model: Model): BackgroundRuntimeAttestation? = null

    fun prepareBackgroundTextGeneration(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        expectedAttestation: BackgroundRuntimeAttestation,
    ): PreparedBackgroundTextGeneration? = null

    fun prepareRemoteBackgroundTextGeneration(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        expectedAttestation: RemoteBackgroundDispatchAttestation,
    ): PreparedRemoteBackgroundTextGeneration? = null

    suspend fun streamText(
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk>
}

fun interface BackgroundTextProviderResolver {
    fun resolve(providerSetting: ProviderSetting): BackgroundTextProviderHandle?
}

/** Real ProviderManager adapter; it never copies ProviderSetting credentials. */
class ProviderManagerBackgroundTextProviderResolver(
    private val providerManager: ProviderManager,
) : BackgroundTextProviderResolver {
    override fun resolve(providerSetting: ProviderSetting): BackgroundTextProviderHandle? =
        try {
            when (providerSetting) {
                is ProviderSetting.OpenAI -> wrap(
                    providerSetting,
                    providerManager.getProviderByType(providerSetting),
                )
                is ProviderSetting.Google -> wrap(
                    providerSetting,
                    providerManager.getProviderByType(providerSetting),
                )
                is ProviderSetting.Claude -> wrap(
                    providerSetting,
                    providerManager.getProviderByType(providerSetting),
                )
                is ProviderSetting.AICore -> wrap(
                    providerSetting,
                    providerManager.getProviderByType(providerSetting),
                )
                is ProviderSetting.LiteRtLocal -> wrap(
                    providerSetting,
                    providerManager.getProviderByType(providerSetting),
                )
                is ProviderSetting.Codex -> wrap(
                    providerSetting,
                    providerManager.getProviderByType(providerSetting),
                )
                // Explicitly fail closed rather than wrapping. Resolving a provider here would
                // make Claude P *reachable* by background scheduling, dreaming, learning and
                // sub-agents; the exclusion is a product contract (claudep/04 §7), not an
                // accident of a missing capability marker.
                is ProviderSetting.ClaudeP -> null
            }
        } catch (_: Exception) {
            null
        }

    private fun <T : ProviderSetting> wrap(
        setting: T,
        provider: Provider<T>,
    ): BackgroundTextProviderHandle = object : BackgroundTextProviderHandle {
        private val attested =
            provider as? AttestedBackgroundTextGenerationProvider<T>
        private val remoteAttested =
            provider as? AttestedRemoteBackgroundTextGenerationProvider<T>

        override val cancellationFenceAbi: String? =
            (provider as? FencedTextGenerationProvider)?.cancellationFenceAbi
                ?.takeIf(SAFE_CAPABILITY_ABI::matches)

        override val backgroundRuntimeAttestationAbi: String? =
            attested?.backgroundRuntimeAttestationAbi
                ?.takeIf(SAFE_CAPABILITY_ABI::matches)

        override val remoteBackgroundDispatchAbi: String? =
            remoteAttested?.remoteBackgroundDispatchAbi?.takeIf(SAFE_CAPABILITY_ABI::matches)

        override val remoteBackgroundApiFamily: RemoteBackgroundApiFamily? =
            (setting as? ProviderSetting.OpenAI)?.takeIf {
                it.officialBackgroundRemoteKindOrNull() in setOf(
                    BackgroundAuthorizationCandidateKind.OFFICIAL_OPENAI,
                    BackgroundAuthorizationCandidateKind.OFFICIAL_OPENCODE_GO,
                ) &&
                    (it.useResponseApi || it.chatCompletionsPath == "/chat/completions")
            }?.let {
                when (it.officialBackgroundRemoteKindOrNull()) {
                    BackgroundAuthorizationCandidateKind.OFFICIAL_OPENCODE_GO ->
                        RemoteBackgroundApiFamily.OPENCODE_GO_CHAT_COMPLETIONS_V1
                    BackgroundAuthorizationCandidateKind.OFFICIAL_OPENAI ->
                        if (it.useResponseApi) RemoteBackgroundApiFamily.OPENAI_RESPONSES_V1
                        else RemoteBackgroundApiFamily.OPENAI_CHAT_COMPLETIONS_V1
                    else -> null
                }
            }

        override suspend fun resolveTrustedContextWindowTokens(model: Model): Int? =
            provider.resolveTrustedContextWindowTokens(setting, model)

        override suspend fun attestBackgroundRuntime(
            model: Model,
        ): BackgroundRuntimeAttestation? = attested?.attestBackgroundRuntime(setting, model)

        override fun prepareBackgroundTextGeneration(
            messages: List<UIMessage>,
            params: TextGenerationParams,
            expectedAttestation: BackgroundRuntimeAttestation,
        ): PreparedBackgroundTextGeneration? = attested?.prepareBackgroundTextGeneration(
            providerSetting = setting,
            messages = messages,
            params = params,
            expectedAttestation = expectedAttestation,
        )

        override fun prepareRemoteBackgroundTextGeneration(
            messages: List<UIMessage>,
            params: TextGenerationParams,
            expectedAttestation: RemoteBackgroundDispatchAttestation,
        ): PreparedRemoteBackgroundTextGeneration? = remoteAttested
            ?.prepareRemoteBackgroundTextGeneration(
                providerSetting = setting,
                messages = messages,
                params = params,
                expectedAttestation = expectedAttestation,
            )

        override suspend fun streamText(
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<MessageChunk> = provider.streamText(setting, messages, params)
    }
}

/**
 * Shared production host for claim-time resolution, execution-time binding, live authorization,
 * and the final dispatch fence. P0's policy source is disabled, so merely registering this host
 * cannot issue a provider request or enable any Learning feature flag.
 */
class SettingsBackedBackgroundGenerationHost(
    private val settingsSource: BackgroundGenerationSettingsSource,
    private val identityFactory: BackgroundGenerationHostIdentityFactory,
    private val providerResolver: BackgroundTextProviderResolver,
) : BackgroundGenerationBinder, BackgroundGenerationAuthorizationGate {

    /**
     * Planner-side digest for the exact live official transport selected by [frozenModel].
     * This reads no prompt or credential bytes and grants no authority by itself.
     */
    fun expectedRemoteDispatchAttestationSha256(
        frozenModel: ResolvedLearningModel,
        templateVersion: String,
        inputIdentitySha256: String,
        providerRequestKey: String,
        maxOutputTokens: Int,
    ): String? {
        if (
            frozenModel.providerKind != LearningProviderKind.REMOTE ||
            !inputIdentitySha256.matches(LOWER_SHA256)
        ) return null
        val snapshot = safeSnapshot()?.takeIf { it.initialized } ?: return null
        val (provider, model) = snapshot.findByPublicIdentity(frozenModel, identityFactory)
            ?: return null
        if (
            provider !is ProviderSetting.OpenAI ||
            provider.officialBackgroundRemoteKindOrNull() !in setOf(
                BackgroundAuthorizationCandidateKind.OFFICIAL_OPENAI,
                BackgroundAuthorizationCandidateKind.OFFICIAL_OPENCODE_GO,
            ) ||
            !provider.enabled || model.providerOverwrite != null ||
            !snapshot.userPolicy.backgroundWorkAuthorized ||
            frozenModel.modelIdentityDigest !in
            snapshot.userPolicy.authorizedModelIdentityDigests ||
            !snapshot.userPolicy.authorizesExactRemoteReflection(frozenModel)
        ) return null
        val handle = providerResolver.resolve(provider) ?: return null
        if (
            handle.cancellationFenceAbi !=
            RemoteBackgroundDispatchAttestation.CANCELLATION_FENCE_ABI ||
            handle.remoteBackgroundDispatchAbi !=
            RemoteBackgroundDispatchAttestation.TRANSPORT_ABI
        ) return null
        val apiFamily = handle.remoteBackgroundApiFamily ?: return null
        val identity = runCatching { identityFactory.identify(provider, model) }.getOrNull()
            ?: return null
        if (
            identity.providerKind != frozenModel.providerKind ||
            identity.providerIdentityDigest != frozenModel.providerIdentityDigest ||
            identity.modelIdentityDigest != frozenModel.modelIdentityDigest ||
            !constantTimeDigestEquals(
                identity.configurationDigest,
                frozenModel.configurationDigest,
            )
        ) return null
        return runCatching {
            expectedRemoteBackgroundDispatchAttestationSha256(
                providerIdentitySha256 = frozenModel.providerIdentityDigest,
                modelIdentitySha256 = frozenModel.modelIdentityDigest,
                configurationIdentitySha256 = frozenModel.configurationDigest,
                templateVersion = templateVersion,
                inputIdentitySha256 = inputIdentitySha256,
                providerRequestKey = providerRequestKey,
                maxOutputTokens = maxOutputTokens,
                apiFamily = apiFamily,
            )
        }.getOrNull()
    }

    /**
     * Lists the unified local/official-cloud choices which the current background adapters can
     * execute. The projection is content-free and never resolves a provider or reads a key.
     */
    fun listAuthorizationCandidates(): List<BackgroundAuthorizationCandidate> {
        val snapshot = safeSnapshot()?.takeIf { it.initialized } ?: return emptyList()
        return listAuthorizationCandidates(snapshot)
    }

    private fun listAuthorizationCandidates(
        snapshot: BackgroundGenerationSettingsSnapshot,
    ): List<BackgroundAuthorizationCandidate> {
        val uniquelyAddressable = snapshot.providers.asSequence()
            .filter(ProviderSetting::enabled)
            .flatMap { provider ->
                provider.models.asSequence()
                    .filter { model ->
                        model.providerOverwrite == null && model.supportsBackgroundText()
                    }
                    .map { model -> provider to model }
            }
            .toList()
            .groupBy { (_, model) -> model.id }
            .values
            .mapNotNull { matches -> matches.singleOrNull() }
        val candidates = uniquelyAddressable.mapNotNull { (provider, model) ->
            val kind = when (provider) {
                is ProviderSetting.LiteRtLocal ->
                    BackgroundAuthorizationCandidateKind.LOCAL_LITERT
                else -> provider.officialBackgroundRemoteKindOrNull()
            }?.takeIf(BackgroundAuthorizationCandidateKind::backgroundAdapterReady)
                ?: return@mapNotNull null
            val identity = runCatching { identityFactory.publicIdentity(provider, model) }
                .getOrNull() ?: return@mapNotNull null
            val expectedProviderKind = if (kind.isRemote) {
                LearningProviderKind.REMOTE
            } else {
                LearningProviderKind.LOCAL_LITERT
            }
            if (identity.providerKind != expectedProviderKind) return@mapNotNull null
            BackgroundAuthorizationCandidate(
                kind = kind,
                modelUuid = model.id,
                providerIdentityDigest = identity.providerIdentityDigest,
                modelIdentityDigest = identity.modelIdentityDigest,
                providerLabel = kind.safeProviderLabel(provider.name),
                modelLabel = model.safeAuthorizationLabel(
                    if (kind.isRemote) DEFAULT_REMOTE_MODEL_LABEL else DEFAULT_LOCAL_CANDIDATE_LABEL,
                ),
            )
        }
        // A digest pair is the persisted selection identity. Any collision or duplicate is omitted.
        return candidates
            .groupBy { it.providerIdentityDigest to it.modelIdentityDigest }
            .values
            .mapNotNull { matches -> matches.singleOrNull() }
            .sortedWith(
                compareBy(
                    { it.kind.ordinal },
                    { it.providerLabel.lowercase(Locale.ROOT) },
                    { it.modelLabel.lowercase(Locale.ROOT) },
                    { it.modelUuid.toString() },
                ),
            )
    }

    /**
     * Lists only uniquely addressable, enabled LiteRT models that can be explicitly selected.
     * This is a public-identity projection: it does not resolve a provider, key configuration,
     * attest/hash an artifact, inspect credentials, or infer authorization from availability.
     */
    fun listLocalAuthorizationCandidates(): List<LocalBackgroundAuthorizationCandidate> {
        return listAuthorizationCandidates()
            .filter { candidate ->
                candidate.kind == BackgroundAuthorizationCandidateKind.LOCAL_LITERT
            }
            .map { candidate ->
                LocalBackgroundAuthorizationCandidate(
                    modelUuid = candidate.modelUuid,
                    modelIdentityDigest = candidate.modelIdentityDigest,
                    displayLabel = candidate.modelLabel,
                )
            }
    }

    /**
     * Resolves the one already-authorized remote model for the disclosure surface. Availability
     * never grants consent: ambiguous, disabled, overwritten, local, or stale selections yield
     * null. Labels are sanitized UI metadata; exact public digests remain the dispatch identity.
     */
    fun remoteReflectionDisclosureTarget(): RemoteReflectionDisclosureTarget? {
        val snapshot = safeSnapshot()?.takeIf { it.initialized } ?: return null
        val authorizedProvider = snapshot.userPolicy.remoteReflectionProviderIdentityDigest
            ?: return null
        val authorizedModel = snapshot.userPolicy.remoteReflectionModelIdentityDigest ?: return null
        return listRemoteReflectionDisclosureTargets(snapshot).singleOrNull { target ->
            target.providerIdentityDigest == authorizedProvider &&
                target.modelIdentityDigest == authorizedModel
        }
    }

    /** Read-only live proof used by planners before snapshotting an exact remote consent pair. */
    fun isExactOfficialRemoteReflectionTarget(
        providerIdentityDigest: String,
        modelIdentityDigest: String,
    ): Boolean {
        if (
            !providerIdentityDigest.matches(LOWER_SHA256) ||
            !modelIdentityDigest.matches(LOWER_SHA256)
        ) return false
        return listAuthorizationCandidates().singleOrNull { candidate ->
            candidate.isRemote &&
                candidate.providerIdentityDigest == providerIdentityDigest &&
                candidate.modelIdentityDigest == modelIdentityDigest
        } != null
    }

    /** Lists exact remote targets for explicit selection without resolving credentials/providers. */
    fun listRemoteReflectionDisclosureTargets(): List<RemoteReflectionDisclosureTarget> {
        val snapshot = safeSnapshot()?.takeIf { it.initialized } ?: return emptyList()
        return listRemoteReflectionDisclosureTargets(snapshot)
    }

    private fun listRemoteReflectionDisclosureTargets(
        snapshot: BackgroundGenerationSettingsSnapshot,
    ): List<RemoteReflectionDisclosureTarget> {
        // Read through a host with this same immutable snapshot so the legacy disclosure surface
        // cannot become broader than the unified, transport-ready candidate list.
        val matches = listAuthorizationCandidates(snapshot)
            .filter(BackgroundAuthorizationCandidate::isRemote)
            .map { candidate ->
                RemoteReflectionDisclosureTarget(
                    providerIdentityDigest = candidate.providerIdentityDigest,
                    modelIdentityDigest = candidate.modelIdentityDigest,
                    providerLabel = candidate.providerLabel,
                    modelLabel = candidate.modelLabel,
                )
            }
        return matches
            .groupBy { it.providerIdentityDigest to it.modelIdentityDigest }
            .values
            .mapNotNull { rows -> rows.singleOrNull() }
            .sortedWith(compareBy({ it.providerLabel }, { it.modelLabel }, { it.modelIdentityDigest }))
    }

    /**
     * Claim-time selection for Learning. Ambiguous authorization is fail-closed: a future UI may
     * persist a preferred model, but the runtime never chooses one heuristically.
     */
    fun resolveSingleAuthorizedForClaim(): LearningModelResolution {
        val snapshot = safeSnapshot()
            ?: return LearningModelResolution.Unavailable(
                LearningModelResolutionFailure.BACKGROUND_NOT_AUTHORIZED,
            )
        if (!snapshot.initialized) {
            return LearningModelResolution.Unavailable(LearningModelResolutionFailure.NO_CONFIGURATION)
        }
        val matches = snapshot.providers.flatMap { provider ->
            provider.models.mapNotNull { model ->
                val public = runCatching { identityFactory.publicIdentity(provider, model) }
                    .getOrNull() ?: return@mapNotNull null
                if (public.modelIdentityDigest in snapshot.userPolicy.authorizedModelIdentityDigests) {
                    model.id
                } else {
                    null
                }
            }
        }.distinct()
        val selected = matches.singleOrNull()
            ?: return LearningModelResolution.Unavailable(
                LearningModelResolutionFailure.NO_CONFIGURATION,
            )
        return resolveForClaim(selected)
    }

    /**
     * Claim-time path for a local runtime whose content SHA/configuration must be frozen. The
     * legacy synchronous method cannot perform filesystem IO and therefore deliberately does not
     * authorize LiteRT. P1 local claimers must call this suspending variant.
     */
    suspend fun resolveSingleAuthorizedForAttestedClaim(): LearningModelResolution {
        val snapshot = safeSnapshot()
            ?: return LearningModelResolution.Unavailable(
                LearningModelResolutionFailure.BACKGROUND_NOT_AUTHORIZED,
            )
        if (!snapshot.initialized) {
            return LearningModelResolution.Unavailable(LearningModelResolutionFailure.NO_CONFIGURATION)
        }
        val matches = snapshot.providers.flatMap { provider ->
            provider.models.mapNotNull { model ->
                val public = runCatching { identityFactory.publicIdentity(provider, model) }
                    .getOrNull() ?: return@mapNotNull null
                if (public.modelIdentityDigest in snapshot.userPolicy.authorizedModelIdentityDigests) {
                    model.id
                } else {
                    null
                }
            }
        }.distinct()
        val selected = matches.singleOrNull()
            ?: return LearningModelResolution.Unavailable(
                LearningModelResolutionFailure.NO_CONFIGURATION,
            )
        return resolveForAttestedClaim(selected)
    }

    /** Future P1 claimers use this; P0 receives BACKGROUND_NOT_AUTHORIZED without keying secrets. */
    fun resolveForClaim(modelId: Uuid): LearningModelResolution {
        val snapshot = safeSnapshot()
            ?: return LearningModelResolution.Unavailable(
                LearningModelResolutionFailure.BACKGROUND_NOT_AUTHORIZED,
            )
        if (!snapshot.initialized) {
            return LearningModelResolution.Unavailable(LearningModelResolutionFailure.NO_CONFIGURATION)
        }
        val match = snapshot.findByModelId(modelId)
            ?: return LearningModelResolution.Unavailable(LearningModelResolutionFailure.NO_CONFIGURATION)
        val (provider, model) = match
        if (provider is ProviderSetting.AICore) {
            return LearningModelResolution.Unavailable(LearningModelResolutionFailure.AICORE_EXCLUDED)
        }
        // Explicit Claude P exclusion, mirroring AICore. The origin classifier already drops it,
        // so this is defence in depth: enabling the provider in settings must not be enough to
        // make it reachable from an unattended background path.
        if (provider is ProviderSetting.ClaudeP) {
            return LearningModelResolution.Unavailable(LearningModelResolutionFailure.CLAUDEP_EXCLUDED)
        }
        if (!provider.enabled || model.providerOverwrite != null) {
            return LearningModelResolution.Unavailable(LearningModelResolutionFailure.NO_CONFIGURATION)
        }
        val public = identityFactory.publicIdentity(provider, model)
        val policy = snapshot.userPolicy
        if (
            !policy.backgroundWorkAuthorized ||
            public.modelIdentityDigest !in policy.authorizedModelIdentityDigests
        ) {
            return LearningModelResolution.Unavailable(
                LearningModelResolutionFailure.BACKGROUND_NOT_AUTHORIZED,
            )
        }
        if (
            public.providerKind == LearningProviderKind.REMOTE &&
            (
                provider.officialBackgroundRemoteKindOrNull()
                    ?.backgroundAdapterReady != true ||
                    !policy.allowRemoteReflection ||
                    policy.remoteReflectionProviderIdentityDigest !=
                    public.providerIdentityDigest ||
                    policy.remoteReflectionModelIdentityDigest != public.modelIdentityDigest
            )
        ) return LearningModelResolution.Unavailable(
            LearningModelResolutionFailure.BACKGROUND_NOT_AUTHORIZED,
        )
        val handle = providerResolver.resolve(provider)
            ?: return LearningModelResolution.Unavailable(
                LearningModelResolutionFailure.CANCELLATION_UNSAFE,
            )
        if (handle.cancellationFenceAbi?.matches(SAFE_CAPABILITY_ABI) != true) {
            return LearningModelResolution.Unavailable(
                LearningModelResolutionFailure.CANCELLATION_UNSAFE,
            )
        }
        if (
            public.providerKind == LearningProviderKind.REMOTE &&
            (
                handle.remoteBackgroundDispatchAbi !=
                    RemoteBackgroundDispatchAttestation.TRANSPORT_ABI ||
                    handle.remoteBackgroundApiFamily == null
            )
        ) return LearningModelResolution.Unavailable(
            LearningModelResolutionFailure.CANCELLATION_UNSAFE,
        )
        // A synchronous caller cannot hash/attest a local artifact. Returning a base-only
        // configuration digest here would let a same-path model replacement reuse an old job.
        if (provider is ProviderSetting.LiteRtLocal) {
            return LearningModelResolution.Unavailable(
                LearningModelResolutionFailure.NO_CONFIGURATION,
            )
        }
        val identity = try {
            identityFactory.identify(provider, model)
        } catch (_: Exception) {
            return LearningModelResolution.Unavailable(
                LearningModelResolutionFailure.INVALID_IDENTITY,
            )
        }
        return LearningModelResolver.resolve(
            candidate = LearningModelCandidate(
                providerKind = identity.providerKind,
                providerIdentityDigest = identity.providerIdentityDigest,
                modelIdentityDigest = identity.modelIdentityDigest,
                configurationDigest = identity.configurationDigest,
                userExplicitlyAuthorizedForBackground = true,
            ),
            policy = LearningModelResolutionPolicy(
                allowRemoteReflection = policy.allowRemoteReflection,
                providerIdentityDigestsWithProvenCancellation = setOf(
                    identity.providerIdentityDigest,
                ),
            ),
        )
    }

    suspend fun resolveForAttestedClaim(modelId: Uuid): LearningModelResolution {
        val snapshot = safeSnapshot()
            ?: return LearningModelResolution.Unavailable(
                LearningModelResolutionFailure.BACKGROUND_NOT_AUTHORIZED,
            )
        if (!snapshot.initialized) {
            return LearningModelResolution.Unavailable(LearningModelResolutionFailure.NO_CONFIGURATION)
        }
        val match = snapshot.findByModelId(modelId)
            ?: return LearningModelResolution.Unavailable(LearningModelResolutionFailure.NO_CONFIGURATION)
        val (provider, model) = match
        if (provider is ProviderSetting.AICore) {
            return LearningModelResolution.Unavailable(LearningModelResolutionFailure.AICORE_EXCLUDED)
        }
        // Explicit Claude P exclusion, mirroring AICore. The origin classifier already drops it,
        // so this is defence in depth: enabling the provider in settings must not be enough to
        // make it reachable from an unattended background path.
        if (provider is ProviderSetting.ClaudeP) {
            return LearningModelResolution.Unavailable(LearningModelResolutionFailure.CLAUDEP_EXCLUDED)
        }
        if (!provider.enabled || model.providerOverwrite != null) {
            return LearningModelResolution.Unavailable(LearningModelResolutionFailure.NO_CONFIGURATION)
        }
        val public = identityFactory.publicIdentity(provider, model)
        val policy = snapshot.userPolicy
        if (
            !policy.backgroundWorkAuthorized ||
            public.modelIdentityDigest !in policy.authorizedModelIdentityDigests
        ) {
            return LearningModelResolution.Unavailable(
                LearningModelResolutionFailure.BACKGROUND_NOT_AUTHORIZED,
            )
        }
        if (
            public.providerKind == LearningProviderKind.REMOTE &&
            (
                provider.officialBackgroundRemoteKindOrNull()
                    ?.backgroundAdapterReady != true ||
                    !policy.allowRemoteReflection ||
                    policy.remoteReflectionProviderIdentityDigest !=
                    public.providerIdentityDigest ||
                    policy.remoteReflectionModelIdentityDigest != public.modelIdentityDigest
            )
        ) return LearningModelResolution.Unavailable(
            LearningModelResolutionFailure.BACKGROUND_NOT_AUTHORIZED,
        )
        val handle = providerResolver.resolve(provider)
            ?: return LearningModelResolution.Unavailable(
                LearningModelResolutionFailure.CANCELLATION_UNSAFE,
            )
        val cancellationAbi = handle.cancellationFenceAbi
        if (cancellationAbi?.matches(SAFE_CAPABILITY_ABI) != true) {
            return LearningModelResolution.Unavailable(
                LearningModelResolutionFailure.CANCELLATION_UNSAFE,
            )
        }
        if (
            public.providerKind == LearningProviderKind.REMOTE &&
            (
                handle.remoteBackgroundDispatchAbi !=
                    RemoteBackgroundDispatchAttestation.TRANSPORT_ABI ||
                    handle.remoteBackgroundApiFamily == null
            )
        ) return LearningModelResolution.Unavailable(
            LearningModelResolutionFailure.CANCELLATION_UNSAFE,
        )
        val attestation = if (provider is ProviderSetting.LiteRtLocal) {
            if (handle.backgroundRuntimeAttestationAbi?.matches(SAFE_CAPABILITY_ABI) != true) {
                return LearningModelResolution.Unavailable(
                    LearningModelResolutionFailure.INVALID_IDENTITY,
                )
            }
            try {
                handle.attestBackgroundRuntime(model)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: return LearningModelResolution.Unavailable(
                LearningModelResolutionFailure.INVALID_IDENTITY,
            )
        } else {
            null
        }
        if (attestation != null && attestation.cancellationFenceAbi != cancellationAbi) {
            return LearningModelResolution.Unavailable(
                LearningModelResolutionFailure.CANCELLATION_UNSAFE,
            )
        }
        val identity = try {
            identityFactory.identify(provider, model, attestation)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return LearningModelResolution.Unavailable(
                LearningModelResolutionFailure.INVALID_IDENTITY,
            )
        }
        return LearningModelResolver.resolve(
            candidate = LearningModelCandidate(
                providerKind = identity.providerKind,
                providerIdentityDigest = identity.providerIdentityDigest,
                modelIdentityDigest = identity.modelIdentityDigest,
                configurationDigest = identity.configurationDigest,
                runtimeAttestationDigest = attestation?.opaqueDigestSha256(),
                userExplicitlyAuthorizedForBackground = true,
            ),
            policy = LearningModelResolutionPolicy(
                allowRemoteReflection = policy.allowRemoteReflection,
                providerIdentityDigestsWithProvenCancellation = setOf(
                    identity.providerIdentityDigest,
                ),
            ),
        )
    }

    override suspend fun bind(
        frozenModel: ResolvedLearningModel,
    ): BackgroundGenerationBindingResult = when (
        val resolution = resolveExactAttested(frozenModel)
    ) {
        is HostResolution.Unavailable -> BackgroundGenerationBindingResult.Unavailable(
            resolution.reason,
        )
        is HostResolution.Ready -> {
            val liveValidation = {
                validateBoundStatic(
                    frozenModel = frozenModel,
                    expectedStaticIdentity = resolution.staticIdentity,
                )
            }
            val execution = resolution.runtimeAttestation?.let { attestation ->
                AttestedHostExecution(
                    frozenModel = frozenModel,
                    model = resolution.model,
                    handle = resolution.handle,
                    expectedRuntimeAttestation = attestation,
                    liveValidation = liveValidation,
                )
            } ?: resolution.remoteBackgroundApiFamily?.let { apiFamily ->
                RemoteAttestedHostExecution(
                    frozenModel = frozenModel,
                    model = resolution.model,
                    handle = resolution.handle,
                    apiFamily = apiFamily,
                    liveValidation = liveValidation,
                )
            } ?: HostExecution(
                frozenModel = frozenModel,
                model = resolution.model,
                handle = resolution.handle,
                liveValidation = liveValidation,
            )
            BackgroundGenerationBindingResult.Bound(execution)
        }
    }

    override fun isAuthorized(frozenModel: ResolvedLearningModel): Boolean =
        validateAuthorizationOnly(frozenModel) == null

    private suspend fun resolveExactAttested(
        frozenModel: ResolvedLearningModel,
    ): HostResolution {
        val snapshot = safeSnapshot()
            ?: return HostResolution.Unavailable(
                BackgroundBindingUnavailableReason.BACKGROUND_NOT_AUTHORIZED,
            )
        if (!snapshot.initialized) {
            return HostResolution.Unavailable(BackgroundBindingUnavailableReason.NO_CONFIGURATION)
        }
        if (frozenModel.providerKind == LearningProviderKind.AICORE) {
            return HostResolution.Unavailable(BackgroundBindingUnavailableReason.UNSUPPORTED_PROVIDER)
        }
        val match = snapshot.findByPublicIdentity(frozenModel, identityFactory)
            ?: return HostResolution.Unavailable(
                BackgroundBindingUnavailableReason.CONFIGURATION_CHANGED,
            )
        val (provider, model) = match
        val policy = snapshot.userPolicy
        if (
            !policy.backgroundWorkAuthorized ||
            frozenModel.modelIdentityDigest !in policy.authorizedModelIdentityDigests
        ) {
            return HostResolution.Unavailable(
                BackgroundBindingUnavailableReason.BACKGROUND_NOT_AUTHORIZED,
            )
        }
        if (!provider.enabled) {
            return HostResolution.Unavailable(BackgroundBindingUnavailableReason.PROVIDER_DISABLED)
        }
        if (
            provider is ProviderSetting.AICore ||
            provider is ProviderSetting.ClaudeP ||
            model.providerOverwrite != null
        ) {
            return HostResolution.Unavailable(BackgroundBindingUnavailableReason.UNSUPPORTED_PROVIDER)
        }
        if (
            frozenModel.providerKind == LearningProviderKind.REMOTE &&
            !policy.authorizesExactRemoteReflection(frozenModel)
        ) {
            return HostResolution.Unavailable(
                BackgroundBindingUnavailableReason.BACKGROUND_NOT_AUTHORIZED,
            )
        }
        val handle = providerResolver.resolve(provider)
            ?: return HostResolution.Unavailable(
                BackgroundBindingUnavailableReason.UNSUPPORTED_PROVIDER,
            )
        if (handle.cancellationFenceAbi?.matches(SAFE_CAPABILITY_ABI) != true) {
            return HostResolution.Unavailable(
                BackgroundBindingUnavailableReason.CANCELLATION_UNSAFE,
            )
        }
        val remoteBackgroundApiFamily = if (provider is ProviderSetting.OpenAI) {
            if (
                provider.officialBackgroundRemoteKindOrNull() !in setOf(
                    BackgroundAuthorizationCandidateKind.OFFICIAL_OPENAI,
                    BackgroundAuthorizationCandidateKind.OFFICIAL_OPENCODE_GO,
                ) ||
                handle.remoteBackgroundDispatchAbi !=
                RemoteBackgroundDispatchAttestation.TRANSPORT_ABI ||
                handle.cancellationFenceAbi !=
                RemoteBackgroundDispatchAttestation.CANCELLATION_FENCE_ABI
            ) {
                return HostResolution.Unavailable(
                    BackgroundBindingUnavailableReason.CANCELLATION_UNSAFE,
                )
            }
            handle.remoteBackgroundApiFamily ?: return HostResolution.Unavailable(
                BackgroundBindingUnavailableReason.UNSUPPORTED_PROVIDER,
            )
        } else if (frozenModel.providerKind == LearningProviderKind.REMOTE) {
            return HostResolution.Unavailable(
                BackgroundBindingUnavailableReason.UNSUPPORTED_PROVIDER,
            )
        } else {
            null
        }
        val runtimeAttestation = if (provider is ProviderSetting.LiteRtLocal) {
            if (handle.backgroundRuntimeAttestationAbi?.matches(SAFE_CAPABILITY_ABI) != true) {
                return HostResolution.Unavailable(
                    BackgroundBindingUnavailableReason.UNSUPPORTED_PROVIDER,
                )
            }
            try {
                handle.attestBackgroundRuntime(model)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: return HostResolution.Unavailable(
                BackgroundBindingUnavailableReason.CONFIGURATION_CHANGED,
            )
        } else {
            null
        }
        if (
            runtimeAttestation != null &&
            runtimeAttestation.cancellationFenceAbi != handle.cancellationFenceAbi
        ) {
            return HostResolution.Unavailable(
                BackgroundBindingUnavailableReason.CANCELLATION_UNSAFE,
            )
        }
        val currentRuntimeDigest = runtimeAttestation?.opaqueDigestSha256()
        val frozenRuntimeDigest = frozenModel.runtimeAttestationDigest
        if (
            (frozenRuntimeDigest == null) != (currentRuntimeDigest == null) ||
            (
                frozenRuntimeDigest != null && currentRuntimeDigest != null &&
                    !constantTimeDigestEquals(frozenRuntimeDigest, currentRuntimeDigest)
            ) ||
            (provider is ProviderSetting.LiteRtLocal && currentRuntimeDigest == null) ||
            (provider !is ProviderSetting.LiteRtLocal && currentRuntimeDigest != null)
        ) {
            return HostResolution.Unavailable(
                BackgroundBindingUnavailableReason.CONFIGURATION_CHANGED,
            )
        }
        val staticIdentity = try {
            identityFactory.identify(provider, model)
        } catch (_: Exception) {
            return HostResolution.Unavailable(
                BackgroundBindingUnavailableReason.CONFIGURATION_CHANGED,
            )
        }
        val currentIdentity = try {
            identityFactory.identify(provider, model, runtimeAttestation)
        } catch (_: Exception) {
            return HostResolution.Unavailable(
                BackgroundBindingUnavailableReason.CONFIGURATION_CHANGED,
            )
        }
        if (
            currentIdentity.providerKind != frozenModel.providerKind ||
            currentIdentity.providerIdentityDigest != frozenModel.providerIdentityDigest ||
            currentIdentity.modelIdentityDigest != frozenModel.modelIdentityDigest ||
            !constantTimeDigestEquals(
                currentIdentity.configurationDigest,
                frozenModel.configurationDigest,
            )
        ) {
            return HostResolution.Unavailable(
                BackgroundBindingUnavailableReason.CONFIGURATION_CHANGED,
            )
        }
        return HostResolution.Ready(
            model = model,
            handle = handle,
            staticIdentity = staticIdentity,
            runtimeAttestation = runtimeAttestation,
            remoteBackgroundApiFamily = remoteBackgroundApiFamily,
        )
    }

    /**
     * Synchronous authorization deliberately proves policy/capability only. Exact LiteRT file
     * identity requires IO and is proved by suspending bind plus the prepared provider execution.
     */
    private fun validateAuthorizationOnly(
        frozenModel: ResolvedLearningModel,
    ): BackgroundBindingUnavailableReason? {
        val snapshot = safeSnapshot()
            ?: return BackgroundBindingUnavailableReason.BACKGROUND_NOT_AUTHORIZED
        if (!snapshot.initialized) return BackgroundBindingUnavailableReason.NO_CONFIGURATION
        if (frozenModel.providerKind == LearningProviderKind.AICORE) {
            return BackgroundBindingUnavailableReason.UNSUPPORTED_PROVIDER
        }
        if (
            (frozenModel.providerKind == LearningProviderKind.LOCAL_LITERT &&
                frozenModel.runtimeAttestationDigest?.matches(LOWER_SHA256) != true) ||
            (frozenModel.providerKind != LearningProviderKind.LOCAL_LITERT &&
                frozenModel.runtimeAttestationDigest != null)
        ) {
            return BackgroundBindingUnavailableReason.CONFIGURATION_CHANGED
        }
        val (provider, model) = snapshot.findByPublicIdentity(frozenModel, identityFactory)
            ?: return BackgroundBindingUnavailableReason.CONFIGURATION_CHANGED
        val policy = snapshot.userPolicy
        if (
            !policy.backgroundWorkAuthorized ||
            frozenModel.modelIdentityDigest !in policy.authorizedModelIdentityDigests
        ) {
            return BackgroundBindingUnavailableReason.BACKGROUND_NOT_AUTHORIZED
        }
        if (!provider.enabled) return BackgroundBindingUnavailableReason.PROVIDER_DISABLED
        if (
            provider is ProviderSetting.AICore ||
            provider is ProviderSetting.ClaudeP ||
            model.providerOverwrite != null
        ) {
            return BackgroundBindingUnavailableReason.UNSUPPORTED_PROVIDER
        }
        if (
            frozenModel.providerKind == LearningProviderKind.REMOTE &&
            !policy.authorizesExactRemoteReflection(frozenModel)
        ) {
            return BackgroundBindingUnavailableReason.BACKGROUND_NOT_AUTHORIZED
        }
        val handle = providerResolver.resolve(provider)
            ?: return BackgroundBindingUnavailableReason.UNSUPPORTED_PROVIDER
        if (handle.cancellationFenceAbi?.matches(SAFE_CAPABILITY_ABI) != true) {
            return BackgroundBindingUnavailableReason.CANCELLATION_UNSAFE
        }
        if (
            frozenModel.providerKind == LearningProviderKind.REMOTE &&
            (
                provider.officialBackgroundRemoteKindOrNull()
                    ?.backgroundAdapterReady != true ||
                    handle.remoteBackgroundDispatchAbi !=
                    RemoteBackgroundDispatchAttestation.TRANSPORT_ABI ||
                    handle.cancellationFenceAbi !=
                    RemoteBackgroundDispatchAttestation.CANCELLATION_FENCE_ABI ||
                    handle.remoteBackgroundApiFamily == null
            )
        ) return BackgroundBindingUnavailableReason.CANCELLATION_UNSAFE
        if (
            provider is ProviderSetting.LiteRtLocal &&
            handle.backgroundRuntimeAttestationAbi?.matches(SAFE_CAPABILITY_ABI) != true
        ) {
            return BackgroundBindingUnavailableReason.UNSUPPORTED_PROVIDER
        }
        return null
    }

    /** The dispatch fence is the exact provider/model pair that the disclosure named. */
    private fun BackgroundGenerationUserPolicy.authorizesExactRemoteReflection(
        frozenModel: ResolvedLearningModel,
    ): Boolean = allowRemoteReflection &&
        remoteReflectionProviderIdentityDigest == frozenModel.providerIdentityDigest &&
        remoteReflectionModelIdentityDigest == frozenModel.modelIdentityDigest

    private fun validateBoundStatic(
        frozenModel: ResolvedLearningModel,
        expectedStaticIdentity: BackgroundGenerationHostIdentity,
    ): BackgroundBindingUnavailableReason? {
        validateAuthorizationOnly(frozenModel)?.let { return it }
        val snapshot = safeSnapshot()
            ?: return BackgroundBindingUnavailableReason.BACKGROUND_NOT_AUTHORIZED
        val (provider, model) = snapshot.findByPublicIdentity(frozenModel, identityFactory)
            ?: return BackgroundBindingUnavailableReason.CONFIGURATION_CHANGED
        val currentStatic = try {
            identityFactory.identify(provider, model)
        } catch (_: Exception) {
            return BackgroundBindingUnavailableReason.CONFIGURATION_CHANGED
        }
        if (
            currentStatic.providerKind != expectedStaticIdentity.providerKind ||
            currentStatic.providerIdentityDigest != expectedStaticIdentity.providerIdentityDigest ||
            currentStatic.modelIdentityDigest != expectedStaticIdentity.modelIdentityDigest ||
            !constantTimeDigestEquals(
                currentStatic.configurationDigest,
                expectedStaticIdentity.configurationDigest,
            )
        ) {
            return BackgroundBindingUnavailableReason.CONFIGURATION_CHANGED
        }
        return null
    }

    private fun safeSnapshot(): BackgroundGenerationSettingsSnapshot? = try {
        settingsSource.current()
    } catch (_: Exception) {
        null
    }
}

private sealed interface HostResolution {
    data class Ready(
        val model: Model,
        val handle: BackgroundTextProviderHandle,
        val staticIdentity: BackgroundGenerationHostIdentity,
        val runtimeAttestation: BackgroundRuntimeAttestation?,
        val remoteBackgroundApiFamily: RemoteBackgroundApiFamily?,
    ) : HostResolution

    data class Unavailable(
        val reason: BackgroundBindingUnavailableReason,
    ) : HostResolution
}

/**
 * Optional stronger execution seam for callers with a durable attempt ledger. The existing
 * [BackgroundGenerationExecution] method remains source-compatible and uses a no-op callback;
 * P1 may opt into this subtype to stamp DISPATCH_STARTED at the true provider boundary.
 */
interface AttestedBackgroundGenerationExecution : BackgroundGenerationExecution {
    suspend fun streamText(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        onDispatchStarted: BackgroundProviderDispatchCallback,
    ): Flow<MessageChunk>
}

private class HostExecution(
    override val frozenModel: ResolvedLearningModel,
    override val model: Model,
    private val handle: BackgroundTextProviderHandle,
    private val liveValidation: () -> BackgroundBindingUnavailableReason?,
) : BackgroundGenerationExecution {
    override suspend fun resolveTrustedContextWindowTokens(): Int? =
        handle.resolveTrustedContextWindowTokens(model)

    override fun validateBeforeDispatch(): BackgroundBindingUnavailableReason? =
        liveValidation()

    override suspend fun streamText(
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> {
        check(liveValidation() == null) { "Background binding changed before dispatch" }
        return handle.streamText(messages, params)
    }
}

private class AttestedHostExecution(
    override val frozenModel: ResolvedLearningModel,
    override val model: Model,
    private val handle: BackgroundTextProviderHandle,
    private val expectedRuntimeAttestation: BackgroundRuntimeAttestation,
    private val liveValidation: () -> BackgroundBindingUnavailableReason?,
) : AttestedBackgroundGenerationExecution {
    override suspend fun resolveTrustedContextWindowTokens(): Int? =
        expectedRuntimeAttestation.contextWindowTokens

    override fun validateBeforeDispatch(): BackgroundBindingUnavailableReason? =
        liveValidation()

    override suspend fun streamText(
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> = streamText(
        messages = messages,
        params = params,
        onDispatchStarted = BackgroundProviderDispatchCallback.NO_OP,
    )

    override suspend fun streamText(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        onDispatchStarted: BackgroundProviderDispatchCallback,
    ): Flow<MessageChunk> {
        check(liveValidation() == null) { "Background binding changed before dispatch" }
        val prepared = handle.prepareBackgroundTextGeneration(
            messages = messages,
            params = params,
            expectedAttestation = expectedRuntimeAttestation,
        ) ?: error("Attested background provider lost its prepare capability")
        check(prepared.expectedAttestation == expectedRuntimeAttestation) {
            "Prepared background runtime does not match the frozen attestation"
        }
        return prepared.streamText(
            BackgroundProviderDispatchCallback { observed ->
                check(liveValidation() == null) {
                    "Background binding changed at provider dispatch"
                }
                onDispatchStarted.onDispatchStarted(observed)
            },
        )
    }
}

private class RemoteAttestedHostExecution(
    override val frozenModel: ResolvedLearningModel,
    override val model: Model,
    private val handle: BackgroundTextProviderHandle,
    private val apiFamily: RemoteBackgroundApiFamily,
    private val liveValidation: () -> BackgroundBindingUnavailableReason?,
) : AttestedBackgroundGenerationExecution {
    override suspend fun resolveTrustedContextWindowTokens(): Int? =
        handle.resolveTrustedContextWindowTokens(model)

    override fun validateBeforeDispatch(): BackgroundBindingUnavailableReason? =
        liveValidation()

    override suspend fun streamText(
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> = streamText(
        messages = messages,
        params = params,
        onDispatchStarted = BackgroundProviderDispatchCallback.NO_OP,
    )

    override suspend fun streamText(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        onDispatchStarted: BackgroundProviderDispatchCallback,
    ): Flow<MessageChunk> {
        check(liveValidation() == null) { "Background binding changed before dispatch" }
        val context = requireNotNull(params.remoteBackgroundDispatchContext) {
            "Remote background dispatch context is missing"
        }
        val expected = RemoteBackgroundDispatchAttestation(
            apiFamily = apiFamily,
            context = context,
        )
        val prepared = handle.prepareRemoteBackgroundTextGeneration(
            messages = messages,
            params = params,
            expectedAttestation = expected,
        ) ?: error("Remote background provider lost its prepare capability")
        check(prepared.expectedAttestation == expected) {
            "Prepared remote transport does not match the frozen dispatch contract"
        }
        return prepared.streamText(
            BackgroundProviderDispatchCallback { observed ->
                check(liveValidation() == null) {
                    "Background binding changed at remote dispatch"
                }
                onDispatchStarted.onDispatchStarted(observed)
            },
        )
    }
}

private fun BackgroundGenerationSettingsSnapshot.findByModelId(
    modelId: Uuid,
): Pair<ProviderSetting, Model>? {
    val matches = providers.flatMap { provider ->
        provider.models.filter { it.id == modelId }.map { provider to it }
    }
    return matches.singleOrNull()
}

private fun Model.safeAuthorizationLabel(
    fallback: String = DEFAULT_REMOTE_MODEL_LABEL,
): String {
    val safeModelId = modelId.safeAuthorizationLabel(fallback)
    return displayName.safeAuthorizationLabel(safeModelId)
}

private fun Model.supportsBackgroundText(): Boolean =
    modelId.isNotBlank() && type == ModelType.CHAT &&
        Modality.TEXT in inputModalities && Modality.TEXT in outputModalities

private fun BackgroundAuthorizationCandidateKind.safeProviderLabel(
    configuredName: String,
): String = when (this) {
    BackgroundAuthorizationCandidateKind.LOCAL_LITERT -> DEFAULT_LOCAL_PROVIDER_LABEL
    BackgroundAuthorizationCandidateKind.OFFICIAL_OPENAI -> "OpenAI (official)"
    BackgroundAuthorizationCandidateKind.OFFICIAL_OPENCODE_GO -> "OpenCode Go (official)"
    BackgroundAuthorizationCandidateKind.OFFICIAL_GOOGLE -> "Google (official)"
    BackgroundAuthorizationCandidateKind.OFFICIAL_ANTHROPIC -> "Anthropic (official)"
}.safeAuthorizationLabel(
    configuredName.safeAuthorizationLabel(DEFAULT_REMOTE_PROVIDER_LABEL),
)

private fun String.safeAuthorizationLabel(fallback: String): String {
    val safe = buildString {
        var pendingSpace = false
        for (char in this@safeAuthorizationLabel) {
            if (char.isWhitespace()) {
                pendingSpace = isNotEmpty()
                continue
            }
            val type = Character.getType(char)
            if (
                type == Character.CONTROL.toInt() ||
                type == Character.FORMAT.toInt() ||
                type == Character.PRIVATE_USE.toInt() ||
                type == Character.SURROGATE.toInt() ||
                type == Character.UNASSIGNED.toInt()
            ) continue
            if (pendingSpace && length < MAX_CANDIDATE_LABEL_CHARS) append(' ')
            pendingSpace = false
            if (length < MAX_CANDIDATE_LABEL_CHARS) append(char)
            if (length >= MAX_CANDIDATE_LABEL_CHARS) break
        }
    }.trim()
    return safe.ifEmpty { fallback }
}

private fun BackgroundGenerationSettingsSnapshot.findByPublicIdentity(
    frozen: ResolvedLearningModel,
    identityFactory: BackgroundGenerationHostIdentityFactory,
): Pair<ProviderSetting, Model>? {
    val matches = providers.flatMap { provider ->
        provider.models.mapNotNull { model ->
            val identity = try {
                identityFactory.publicIdentity(provider, model)
            } catch (_: Exception) {
                null
            }
            if (
                identity?.providerKind == frozen.providerKind &&
                identity.providerIdentityDigest == frozen.providerIdentityDigest &&
                identity.modelIdentityDigest == frozen.modelIdentityDigest
            ) {
                provider to model
            } else {
                null
            }
        }
    }
    return matches.singleOrNull()
}

private fun ProviderSetting.learningKind(): LearningProviderKind = when (this) {
    is ProviderSetting.AICore -> LearningProviderKind.AICORE
    is ProviderSetting.LiteRtLocal -> LearningProviderKind.LOCAL_LITERT
    // Claude P intentionally has no kind of its own. LearningModelResolver rejects it before this
    // classification is ever consulted, so a dedicated member would only imply it is selectable.
    else -> LearningProviderKind.REMOTE
}

private fun ProviderSetting.typeTag(): String = when (this) {
    is ProviderSetting.OpenAI -> "openai"
    is ProviderSetting.Google -> "google"
    is ProviderSetting.Claude -> "claude"
    is ProviderSetting.AICore -> "aicore"
    is ProviderSetting.LiteRtLocal -> "local_litert"
    is ProviderSetting.Codex -> "codex"
    is ProviderSetting.ClaudeP -> "claude_p"
}

private fun CanonicalSha256.providerConfiguration(
    provider: ProviderSetting,
): CanonicalSha256 {
    string("provider_type", provider.typeTag())
    string("provider_id", provider.id.toString())
    bool("provider_enabled", provider.enabled)
    when (provider) {
        is ProviderSetting.OpenAI -> {
            string("openai_api_key", provider.apiKey)
            string("openai_base_url", provider.baseUrl)
            string("openai_chat_path", provider.chatCompletionsPath)
            bool("openai_responses", provider.useResponseApi)
            bool("openai_prompt_cache", provider.promptCaching)
            bool("openai_history_reasoning", provider.includeHistoryReasoning)
            nullableString("openrouter_sort", provider.routing.sort)
            strings("openrouter_order", provider.routing.order)
            strings("openrouter_only", provider.routing.only)
            strings("openrouter_ignore", provider.routing.ignore)
            bool("openrouter_fallbacks", provider.routing.allowFallbacks)
            bool("openrouter_require_params", provider.routing.requireParameters)
            nullableString("openrouter_data_collection", provider.routing.dataCollection)
            bool("openrouter_zdr", provider.routing.zdr)
            strings("openrouter_quantizations", provider.routing.quantizations)
            nullableDouble("openrouter_max_prompt", provider.routing.maxPricePrompt)
            nullableDouble("openrouter_max_completion", provider.routing.maxPriceCompletion)
        }
        is ProviderSetting.Google -> {
            string("google_api_key", provider.apiKey)
            string("google_base_url", provider.baseUrl)
            bool("google_vertex", provider.vertexAI)
            bool("google_service_account", provider.useServiceAccount)
            string("google_private_key", provider.privateKey)
            string("google_service_email", provider.serviceAccountEmail)
            string("google_location", provider.location)
            string("google_project", provider.projectId)
        }
        is ProviderSetting.Claude -> {
            string("claude_api_key", provider.apiKey)
            string("claude_base_url", provider.baseUrl)
            bool("claude_prompt_cache", provider.promptCaching)
            string("claude_cache_ttl", provider.promptCacheTtl.name)
        }
        is ProviderSetting.AICore -> string("aicore_stage", provider.releaseStage.name)
        is ProviderSetting.LiteRtLocal -> Unit
        is ProviderSetting.Codex -> Unit
        // Non-secret request-affecting surface only. No credential has an analogue here: the
        // device private key never leaves the Keystore and is unreachable to this digest.
        is ProviderSetting.ClaudeP -> {
            nullableString("claude_p_paired_origin", provider.pairedOrigin)
            nullableString("claude_p_gateway_fingerprint", provider.gatewayFingerprint)
            nullableString("claude_p_installation", provider.gatewayInstallationId)
            string("claude_p_pairing_state", provider.pairingState.name)
            nullableString("claude_p_claude_code_version", provider.claudeCodeVersion)
        }
    }
    return this
}

/** Non-secret, request-affecting provider surface used by learned Policy applicability. */
private fun CanonicalSha256.publicProviderPolicyApplicability(
    provider: ProviderSetting,
): CanonicalSha256 {
    bool("public_provider_enabled", provider.enabled)
    when (provider) {
        is ProviderSetting.OpenAI -> {
            bool("public_openai_responses", provider.useResponseApi)
            bool("public_openai_prompt_cache", provider.promptCaching)
            bool("public_openai_history_reasoning", provider.includeHistoryReasoning)
            nullableString("public_openrouter_sort", provider.routing.sort)
            strings("public_openrouter_order", provider.routing.order)
            strings("public_openrouter_only", provider.routing.only)
            strings("public_openrouter_ignore", provider.routing.ignore)
            bool("public_openrouter_fallbacks", provider.routing.allowFallbacks)
            bool("public_openrouter_require_params", provider.routing.requireParameters)
            nullableString("public_openrouter_data_collection", provider.routing.dataCollection)
            bool("public_openrouter_zdr", provider.routing.zdr)
            strings("public_openrouter_quantizations", provider.routing.quantizations)
        }
        is ProviderSetting.Google -> {
            bool("public_google_vertex", provider.vertexAI)
            bool("public_google_service_account", provider.useServiceAccount)
            string("public_google_location", provider.location)
        }
        is ProviderSetting.Claude -> {
            bool("public_claude_prompt_cache", provider.promptCaching)
            string("public_claude_cache_ttl", provider.promptCacheTtl.name)
        }
        is ProviderSetting.AICore -> string("public_aicore_stage", provider.releaseStage.name)
        is ProviderSetting.LiteRtLocal,
        is ProviderSetting.Codex,
        -> Unit
        // Claude P contributes nothing to learned-Policy applicability: it is never a background
        // candidate, so no background policy can be conditioned on it.
        is ProviderSetting.ClaudeP -> Unit
    }
    return this
}

/** Non-secret model capability/configuration surface; custom header/body values are excluded. */
private fun CanonicalSha256.publicModelPolicyApplicability(model: Model): CanonicalSha256 {
    string("public_model_type", model.type.name)
    strings("public_model_input_modalities", model.inputModalities.map { it.name }.sorted())
    strings("public_model_output_modalities", model.outputModalities.map { it.name }.sorted())
    strings("public_model_abilities", model.abilities.map { it.name }.sorted())
    strings("public_model_tools", model.tools.map { it.backgroundIdentityTag() }.sorted())
    nullableInt("public_model_catalog_context", model.contextLength)
    int("public_model_user_context", model.userContextWindowTokens)
    nullableInt("public_model_trusted_context", model.trustedContextWindowTokens)
    strings("public_model_supported_parameters", model.supportedParameters.sorted())
    strings("public_model_header_names", model.customHeaders.map { it.name }.sorted())
    strings("public_model_body_keys", model.customBodies.map { it.key }.sorted())
    bool("public_model_provider_overwrite", model.providerOverwrite != null)
    model.providerOverwrite?.let { overwrite ->
        string("public_model_overwrite_type", overwrite.typeTag())
        string("public_model_overwrite_id", overwrite.id.toString())
    }
    return this
}

private fun CanonicalSha256.modelConfiguration(model: Model): CanonicalSha256 {
    string("model_uuid", model.id.toString())
    string("model_id", model.modelId)
    string("model_type", model.type.name)
    strings("model_input_modalities", model.inputModalities.map { it.name })
    strings("model_output_modalities", model.outputModalities.map { it.name })
    strings("model_abilities", model.abilities.map { it.name })
    strings("model_tools", model.tools.map { it.backgroundIdentityTag() }.sorted())
    nullableInt("model_catalog_context", model.contextLength)
    int("model_user_context", model.userContextWindowTokens)
    nullableInt("model_trusted_context", model.trustedContextWindowTokens)
    strings("model_supported_parameters", model.supportedParameters)
    int("model_custom_header_count", model.customHeaders.size)
    model.customHeaders.forEachIndexed { index, header ->
        string("model_header_${index}_name", header.name)
        string("model_header_${index}_value", header.value)
    }
    int("model_custom_body_count", model.customBodies.size)
    model.customBodies.forEachIndexed { index, body ->
        string("model_body_${index}_key", body.key)
        json("model_body_${index}_value", body.value)
    }
    bool("model_provider_overwrite", model.providerOverwrite != null)
    model.providerOverwrite?.let { overwrite ->
        string("model_overwrite_type", overwrite.typeTag())
        string("model_overwrite_id", overwrite.id.toString())
    }
    return this
}

private fun CanonicalSha256.runtimeAttestation(
    attestation: BackgroundRuntimeAttestation,
): CanonicalSha256 {
    string("runtime_identity_abi", BACKGROUND_RUNTIME_IDENTITY_ABI)
    int("runtime_schema", attestation.schemaVersion)
    string("runtime_provider_abi", attestation.providerRuntimeAbi)
    string("runtime_sdk_abi", attestation.sdkAbi)
    string("runtime_cancellation_abi", attestation.cancellationFenceAbi)
    string("runtime_artifact_sha256", attestation.artifactSha256)
    bool("runtime_force_cpu", attestation.forceCpu)
    string("runtime_accelerator", attestation.accelerator)
    int("runtime_context_tokens", attestation.contextWindowTokens)
    int("runtime_top_k", attestation.topK)
    double("runtime_top_p", attestation.topP)
    double("runtime_temperature", attestation.temperature)
    string("runtime_prompt_renderer_abi", attestation.promptRendererAbi)
    string("runtime_native_tool_abi", attestation.nativeToolAbi)
    bool("runtime_text_only", attestation.textOnly)
    bool("runtime_tools_empty", attestation.toolsEmpty)
    bool("runtime_constrained_decoding", attestation.constrainedDecoding)
    bool("runtime_speculative_decoding", attestation.speculativeDecoding)
    bool("runtime_provider_cache_disabled", attestation.providerCacheDisabled)
    return this
}

private class CanonicalSha256 {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var finished = false

    fun string(label: String, value: String): CanonicalSha256 = apply {
        require(!finished)
        updateUtf8(label)
        updateInt(1)
        updateUtf8(value)
    }

    fun nullableString(label: String, value: String?): CanonicalSha256 = apply {
        if (value == null) {
            updateUtf8(label)
            updateInt(0)
        } else {
            string(label, value)
        }
    }

    fun bool(label: String, value: Boolean): CanonicalSha256 =
        string(label, if (value) "1" else "0")

    fun int(label: String, value: Int): CanonicalSha256 = string(label, value.toString())

    fun nullableInt(label: String, value: Int?): CanonicalSha256 =
        nullableString(label, value?.toString())

    fun nullableDouble(label: String, value: Double?): CanonicalSha256 =
        nullableString(label, value?.toBits()?.toString())

    fun double(label: String, value: Double): CanonicalSha256 =
        string(label, value.toBits().toString())

    fun strings(label: String, values: List<String>): CanonicalSha256 = apply {
        int("${label}_count", values.size)
        values.forEachIndexed { index, value -> string("${label}_$index", value) }
    }

    fun json(label: String, element: JsonElement): CanonicalSha256 = apply {
        string("${label}_kind", when (element) {
            JsonNull -> "null"
            is JsonObject -> "object"
            is JsonArray -> "array"
            is JsonPrimitive -> if (element.isString) "string" else "primitive"
        })
        when (element) {
            JsonNull -> Unit
            is JsonObject -> {
                val entries = element.entries.sortedBy { it.key }
                int("${label}_size", entries.size)
                entries.forEachIndexed { index, (key, value) ->
                    string("${label}_${index}_key", key)
                    json("${label}_${index}_value", value)
                }
            }
            is JsonArray -> {
                int("${label}_size", element.size)
                element.forEachIndexed { index, value -> json("${label}_$index", value) }
            }
            is JsonPrimitive -> string("${label}_content", element.content)
        }
    }

    fun finishHex(): String {
        require(!finished)
        finished = true
        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private fun updateUtf8(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        try {
            updateInt(bytes.size)
            digest.update(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun updateInt(value: Int) {
        digest.update((value ushr 24).toByte())
        digest.update((value ushr 16).toByte())
        digest.update((value ushr 8).toByte())
        digest.update(value.toByte())
    }
}

private fun constantTimeDigestEquals(left: String, right: String): Boolean =
    MessageDigest.isEqual(
        left.toByteArray(Charsets.US_ASCII),
        right.toByteArray(Charsets.US_ASCII),
    )

private fun BuiltInTools.backgroundIdentityTag(): String = when (this) {
    BuiltInTools.Search -> "search"
    BuiltInTools.UrlContext -> "url_context"
    BuiltInTools.ImageGeneration -> "image_generation"
}
