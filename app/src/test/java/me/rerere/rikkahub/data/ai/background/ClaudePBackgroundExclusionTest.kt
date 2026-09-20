package me.rerere.rikkahub.data.ai.background

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.claudep.ClaudePPairingState
import me.rerere.rikkahub.learning.model.LearningModelResolution
import me.rerere.rikkahub.learning.model.LearningModelResolutionFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Claude P must never be reachable from an unattended path.
 *
 * `claudep/04-rikkahub-integration-map.md` §7 lists scheduled jobs, dreaming/learning, sub-agents,
 * Telegram and background fallback as paths Claude P must fail closed in. The reason is concrete:
 * those paths run with the device offline, the user absent and nobody to approve anything, while
 * Claude P's runtime is a user-operated VPS that may be asleep or revoked, and every request spends
 * the user's subscription.
 *
 * These tests enable and "pair" the provider first. Falling back to "it is disabled by default"
 * would be a much weaker guarantee — the exclusion has to hold for a fully configured provider.
 *
 * ### What these tests can and cannot prove
 *
 * The decisive guard is the classifier: [ProviderSetting.officialBackgroundRemoteKindOrNull] is
 * what decides eligibility, so it is asserted directly. The host-level test is deliberately a
 * *positive control* — it checks that a conventional official provider still reaches the candidate
 * list while Claude P does not, which rules out "the host simply returned nothing". It cannot, on
 * its own, distinguish the Claude P exclusion from the generic `backgroundAdapterReady` filter, so
 * it is not relied on for that claim.
 */
class ClaudePBackgroundExclusionTest {

    private fun chatModel(id: String, modelId: String) = Model(
        id = Uuid.parse(id),
        modelId = modelId,
        displayName = modelId,
        type = ModelType.CHAT,
        inputModalities = listOf(Modality.TEXT),
        outputModalities = listOf(Modality.TEXT),
    )

    private fun claudePModel() = chatModel("20000000-0000-4000-8000-000000000001", "sonnet")

    private fun configuredClaudeP(model: Model = claudePModel()) = ProviderSetting.ClaudeP(
        enabled = true,
        models = listOf(model),
        pairedOrigin = "https://claude.example.com",
        gatewayFingerprint = "SHA256:abc",
        pairingState = ClaudePPairingState.PAIRED,
    )

    private fun officialOpenAi(model: Model) = ProviderSetting.OpenAI(
        enabled = true,
        name = "Official OpenAI",
        models = listOf(model),
        baseUrl = "https://api.openai.com/v1",
        chatCompletionsPath = "/chat/completions",
        useResponseApi = false,
    )

    private fun host(vararg providers: ProviderSetting) = SettingsBackedBackgroundGenerationHost(
        settingsSource = {
            BackgroundGenerationSettingsSnapshot(
                initialized = true,
                providers = providers.toList(),
                userPolicy = BackgroundGenerationUserPolicy(),
            )
        },
        identityFactory = BackgroundGenerationHostIdentityFactory { canonical, _, _ -> canonical },
        providerResolver = { null },
    )

    // -----------------------------------------------------------------------------------------
    // The decisive guard: the classifier
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the origin classifier refuses claude p even for a plausible origin`() {
        // A paired Claude P origin is a self-hosted gateway, never an official API endpoint, so it
        // must not be classified as one no matter how it is spelled.
        assertNull(classify("https://claude.example.com"))
        assertNull(classify("https://api.anthropic.com/v1"))
        assertNull(classify(null))
    }

    private fun classify(origin: String?) =
        ProviderSetting.ClaudeP(pairedOrigin = origin).officialBackgroundRemoteKindOrNull()

    @Test
    fun `claude p is excluded by the classifier rather than by its disabled flag`() {
        // Guard against the exclusion silently degrading into "it happens to be off".
        val enabled = configuredClaudeP()
        assertTrue(enabled.enabled)
        assertEquals(ClaudePPairingState.PAIRED, enabled.pairingState)
        assertNull(enabled.officialBackgroundRemoteKindOrNull())
    }

    /**
     * The control. If this ever fails, every "excluded" assertion above passes for the wrong
     * reason — a classifier that rejects everything would look identical.
     */
    @Test
    fun `an unrelated official provider is still classified so the exclusion is specific`() {
        val official = ProviderSetting.Claude(
            enabled = true,
            baseUrl = "https://api.anthropic.com/v1",
        )

        assertEquals(
            BackgroundAuthorizationCandidateKind.OFFICIAL_ANTHROPIC,
            official.officialBackgroundRemoteKindOrNull(),
        )

        val openAi = officialOpenAi(chatModel("20000000-0000-4000-8000-000000000009", "gpt"))
        assertEquals(
            BackgroundAuthorizationCandidateKind.OFFICIAL_OPENAI,
            openAi.officialBackgroundRemoteKindOrNull(),
        )
    }

    // -----------------------------------------------------------------------------------------
    // The host
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the host lists a conventional provider but never claude p`() {
        val openAiModel = chatModel("20000000-0000-4000-8000-000000000009", "gpt-official")
        val claudePModel = claudePModel()

        val candidates = host(
            officialOpenAi(openAiModel),
            configuredClaudeP(claudePModel),
        ).listAuthorizationCandidates()

        // Positive control first: the host is working, not simply returning an empty list.
        assertTrue(
            "the official provider must still be offered, otherwise this test proves nothing",
            candidates.any { it.modelUuid == openAiModel.id },
        )
        assertTrue(
            "Claude P must never be a background candidate",
            candidates.none { it.modelUuid == claudePModel.id },
        )
    }

    // -----------------------------------------------------------------------------------------
    // Claim-time resolution
    // -----------------------------------------------------------------------------------------

    @Test
    fun `claim time resolution rejects claude p with a dedicated reason`() {
        val model = claudePModel()
        val resolution = host(configuredClaudeP(model)).resolveForClaim(model.id)

        assertEquals(
            LearningModelResolution.Unavailable(LearningModelResolutionFailure.CLAUDEP_EXCLUDED),
            resolution,
        )
    }

    @Test
    fun `the claude p exclusion is distinguishable from the aicore one`() {
        // A shared reason would make the two indistinguishable in diagnostics and would hide a
        // Claude P regression behind AICore's expected exclusion.
        assertTrue(
            LearningModelResolutionFailure.CLAUDEP_EXCLUDED !=
                LearningModelResolutionFailure.AICORE_EXCLUDED,
        )
    }
}
