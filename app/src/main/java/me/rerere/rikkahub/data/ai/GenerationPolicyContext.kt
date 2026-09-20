package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.learning.exposure.PolicyExposureBundle
import me.rerere.rikkahub.learning.exposure.PolicyExposurePolicyRef
import me.rerere.rikkahub.learning.exposure.PolicyExposureReservation
import me.rerere.rikkahub.learning.exposure.PolicyExposureReservationKey
import me.rerere.rikkahub.learning.exposure.PolicyExposureRuntimeAnchor
import me.rerere.rikkahub.learning.exposure.PolicyLearningCommandContext
import me.rerere.rikkahub.learning.retrieval.LearnedPolicyCandidatePacket
import me.rerere.rikkahub.learning.retrieval.MAX_POLICY_RAW_QUERY_CHARS
import me.rerere.rikkahub.learning.retrieval.applicabilityCohortDigest
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import me.rerere.rikkahub.memory.dreaming.runtime.DreamRuntimeCompileStatus
import me.rerere.rikkahub.toolcatalog.ToolCatalogSnapshot
import java.security.MessageDigest
import kotlin.uuid.Uuid

/** Pure preparation helpers for Dream/Policy Recall and provider applicability identity. */
internal fun isPolicyInjectionDispatchEligible(
    requestIsNormal: Boolean,
    isHeadless: Boolean,
    isSubAgent: Boolean,
    assistantPolicyOptIn: Boolean,
    callOrigin: ToolCallOrigin,
    command: PolicyLearningCommandContext?,
    expectedRunId: Uuid?,
    expectedAssistantId: Uuid,
    hasPriorExposure: Boolean,
): Boolean = requestIsNormal && !isHeadless && !isSubAgent && assistantPolicyOptIn &&
    callOrigin == ToolCallOrigin.LocalChat && command != null && expectedRunId != null &&
    command.logicalRunId == expectedRunId &&
    command.consumingAssistantId == expectedAssistantId && !hasPriorExposure

internal fun DreamGenerationContext.toRecallDreamItems(
    scopeId: DreamScopeId,
): List<RecallDreamContextItem> {
    val compiled = compileResult
        ?.takeIf { it.status == DreamRuntimeCompileStatus.COMPILED }
        ?: return emptyList()
    if (compiled.renderedSection.isBlank() || compiled.actualClaimRefs.isEmpty()) return emptyList()
    return listOf(
        RecallDreamContextItem(
            scopeId = scopeId.value,
            claims = compiled.actualClaimRefs.map { ref ->
                RecallDreamClaimIdentity(ref.claimId, ref.claimRevision)
            },
            renderedFragment = compiled.renderedSection,
            compilerRevision = compiled.compilerRevision,
        ),
    )
}

internal fun List<UIMessage>.toBoundedPolicyRetrievalQuery(): String =
    asReversed().asSequence()
        .filter { it.role == MessageRole.USER }
        .flatMap { it.parts.asSequence() }
        .filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .take(MAX_POLICY_RAW_QUERY_CHARS)

internal fun RecallPromptCompileResult.toPolicyExposureReservation(
    anchor: PolicyExposureRuntimeAnchor,
    packet: LearnedPolicyCandidatePacket,
): PolicyExposureReservation? {
    val byIdentity = packet.candidates.associateBy {
        Triple(it.policyId, it.policyRevision, it.artifactSha256)
    }
    val policies = manifest.actualPolicyItems.mapIndexedNotNull { index, actual ->
        val revision = actual.revision ?: return@mapIndexedNotNull null
        val artifact = actual.artifactSha256 ?: return@mapIndexedNotNull null
        val candidate = byIdentity[Triple(actual.id, revision, artifact)]
            ?: return@mapIndexedNotNull null
        PolicyExposurePolicyRef(
            policyId = actual.id,
            policyRevision = revision,
            artifactSha256 = artifact,
            scope = candidate.scope,
            rank = index + 1,
            estimatedTokens = candidate.estimatedTokens,
            applicabilityCohortDigest = candidate.applicabilityCohortDigest(),
        )
    }
    if (policies.size != manifest.actualPolicyItems.size || policies.isEmpty()) return null
    val bundle = runCatching { PolicyExposureBundle.create(policies) }.getOrNull() ?: return null
    return PolicyExposureReservation(
        key = PolicyExposureReservationKey(
            streamId = anchor.streamId,
            episodeId = anchor.episodeId,
            logicalRunId = anchor.logicalRunId,
            attemptOrdinal = 1,
            policySetDigest = bundle.policySetDigest,
        ),
        bundle = bundle,
    )
}

internal fun LearnedPolicyCandidatePacket.toPolicyDropObservationReservation(
    anchor: PolicyExposureRuntimeAnchor,
    policyIds: Set<String>,
): PolicyExposureReservation? {
    val selected = candidates.filter { it.policyId in policyIds }
    if (selected.isEmpty() || selected.size != policyIds.size) return null
    val bundle = runCatching {
        PolicyExposureBundle.create(
            selected.mapIndexed { index, policy ->
                PolicyExposurePolicyRef(
                    policyId = policy.policyId,
                    policyRevision = policy.policyRevision,
                    artifactSha256 = policy.artifactSha256,
                    scope = policy.scope,
                    rank = index + 1,
                    estimatedTokens = policy.estimatedTokens,
                    applicabilityCohortDigest = policy.applicabilityCohortDigest(),
                )
            },
        )
    }.getOrNull() ?: return null
    return PolicyExposureReservation(
        key = PolicyExposureReservationKey(
            streamId = anchor.streamId,
            episodeId = anchor.episodeId,
            logicalRunId = anchor.logicalRunId,
            attemptOrdinal = 1,
            policySetDigest = bundle.policySetDigest,
        ),
        bundle = bundle,
    )
}

internal fun RecallPromptCompileResult.requirePresentOnFinalWire(
    messages: List<UIMessage>,
): Boolean {
    if (text.isEmpty()) return false
    val present = messages.asSequence()
        .flatMap { it.parts.asSequence() }
        .filterIsInstance<UIMessagePart.Text>()
        .any { text in it.text }
    check(present) { "Compiled Recall projection was not preserved by the final provider gate" }
    return true
}

internal fun RecallPromptCompileResult.policyProjectionDigestOrNull(): String? {
    val policies = manifest.actualPolicyItems
    if (policies.isEmpty()) return null
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(RECALL_PROMPT_COMPILER_REVISION.toByteArray(Charsets.UTF_8))
    policies.forEach { policy ->
        listOf(
            policy.id,
            policy.revision?.toString().orEmpty(),
            policy.artifactSha256.orEmpty(),
            policy.scopeKind.orEmpty(),
            policy.scopeId.orEmpty(),
            policy.applicabilityCohortDigest.orEmpty(),
        ).forEach { field ->
            val bytes = field.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
            digest.update(0)
            digest.update(bytes)
            digest.update(0)
        }
    }
    return digest.digest().toHexString()
}

internal fun generationModelIdentity(model: Model): String = sha256GenerationIdentity(
    listOf(
        "model-v2",
        model.id.toString(),
        model.modelId,
        model.type.name,
        model.contextLength?.toString().orEmpty(),
        model.userContextWindowTokens.toString(),
        model.trustedContextWindowTokens?.toString().orEmpty(),
        model.inputModalities.map { it.name }.sorted().joinToString(","),
        model.outputModalities.map { it.name }.sorted().joinToString(","),
        model.abilities.map { it.name }.sorted().joinToString(","),
        model.tools.map { it.toString() }.sorted().joinToString(","),
        model.supportedParameters.sorted().joinToString(","),
    ).joinToString("\u0000"),
)

internal fun generationProviderIdentity(provider: ProviderSetting): String =
    sha256GenerationIdentity(
        buildList {
            add("provider-v2")
            add(provider.id.toString())
            add(provider::class.qualifiedName.orEmpty())
            when (provider) {
                is ProviderSetting.OpenAI -> {
                    add(provider.baseUrl)
                    add(provider.chatCompletionsPath)
                    add(provider.useResponseApi.toString())
                    add(provider.promptCaching.toString())
                    add(provider.includeHistoryReasoning.toString())
                    add(provider.routing.toString())
                }
                is ProviderSetting.Google -> {
                    add(provider.baseUrl)
                    add(provider.vertexAI.toString())
                    add(provider.useServiceAccount.toString())
                    add(provider.location)
                }
                is ProviderSetting.Claude -> {
                    add(provider.baseUrl)
                    add(provider.promptCaching.toString())
                    add(provider.promptCacheTtl.name)
                }
                is ProviderSetting.AICore -> add(provider.releaseStage.name)
                is ProviderSetting.LiteRtLocal,
                is ProviderSetting.Codex,
                -> Unit
                // Request-affecting, non-secret surface only. Claude P's device credential is
                // deliberately unreachable here, so rotating a token cannot silently change a
                // generation identity that durable state was keyed on.
                is ProviderSetting.ClaudeP -> {
                    add(provider.pairedOrigin.orEmpty())
                    add(provider.gatewayFingerprint.orEmpty())
                    add(provider.gatewayInstallationId.orEmpty())
                    add(provider.pairingState.name)
                    add(provider.claudeCodeVersion.orEmpty())
                }
            }
        }.joinToString("\u0000"),
    )

internal fun generationProviderGeneration(
    providerIdentity: String,
    modelIdentity: String,
): Long {
    val bytes = MessageDigest.getInstance("SHA-256")
        .digest("provider-generation-v1\u0000$providerIdentity\u0000$modelIdentity".toByteArray())
    return bytes.take(8).fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xffL) } and
        Long.MAX_VALUE
}

internal fun generationToolsetFingerprint(tools: List<Tool>): String {
    val snapshot = ToolCatalogSnapshot.fromDefinitions(tools)
    return sha256GenerationIdentity(
        snapshot.entries.joinToString("\u0000") { entry ->
            "${entry.toolName}\u0000${entry.schemaFingerprint}"
        },
    )
}

internal fun LearnedPolicyCandidatePacket.filterFinalApplicability(
    providerIdentity: String,
    modelIdentity: String,
    templateIdentity: String,
    configurationIdentity: String,
    configurationGeneration: Long,
    availableToolSchemas: Set<String>,
    capabilityDigest: String? = null,
    authorityDigest: String? = null,
): LearnedPolicyCandidatePacket = copy(
    candidates = candidates.filter { policy ->
        val modelMatches = policy.applicableModelIdentity == "EXACT_V1:$modelIdentity"
        val providerMatches = policy.applicableProviderIdentity == "EXACT_V1:$providerIdentity"
        modelMatches && providerMatches &&
            policy.applicableTemplateIdentity == templateIdentity &&
            policy.applicableConfigurationIdentity == configurationIdentity &&
            policy.applicableConfigurationGeneration == configurationGeneration &&
            policy.applicableCapabilityDigest == capabilityDigest &&
            policy.applicableAuthorityDigest == authorityDigest &&
            policy.applicableToolSchemaFingerprints.all { it in availableToolSchemas }
    },
)

private fun sha256GenerationIdentity(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .toHexString()

private fun ByteArray.toHexString(): String = joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}
