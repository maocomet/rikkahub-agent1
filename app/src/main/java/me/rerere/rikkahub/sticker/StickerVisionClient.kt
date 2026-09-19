package me.rerere.rikkahub.sticker

import java.io.File
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider

/** What a successful recognition produced. */
data class StickerVisionMetadata(
    val description: String,
    val tags: List<String>,
)

/**
 * The outcome of one recognition attempt.
 *
 * A typed failure rather than a `Result`, because the reason is *stored*: [StickerVisionFailure]
 * becomes the row's `vision_error_code`, and the UI reads it back to say why a sticker has no
 * description. `Result`'s throwable would have to be classified at the call site and could carry a
 * provider's exception text into the database.
 */
sealed interface StickerVisionOutcome {
    data class Success(
        val metadata: StickerVisionMetadata,
        val providerLabel: String,
    ) : StickerVisionOutcome

    data class Failure(val reason: StickerVisionFailure) : StickerVisionOutcome
}

/**
 * One-shot sticker recognition.
 *
 * An interface so the import flow can be driven on the JVM without a provider, a network or an
 * API key — the real implementation wraps `ProviderManager`, which is a concrete class holding
 * live HTTP providers and cannot be substituted. Same seam, and same reason, as
 * `VisionDescriptionClient`.
 *
 * Implementations must not throw. Recognition is best-effort by contract: a sticker is stored
 * whether or not this succeeds, so an escaping exception would turn a cosmetic failure into a lost
 * import.
 */
fun interface StickerVisionClient {
    suspend fun describe(imageFile: File): StickerVisionOutcome
}

/**
 * The production client: one provider call, no chat, no tools, no memory capture, no caching.
 *
 * Reads `stickerVisionModelId`, deliberately **not** `ocrModelId`. The two tasks want different
 * models — OCR wants whatever reads small text most accurately, this wants whatever describes an
 * image's mood and content best — and a shared setting means tuning one silently degrades the
 * other. Keeping them apart is also what lets a person leave sticker recognition off entirely
 * while OCR keeps working, which is the default state.
 *
 * Nothing here inspects the provider's location. If the configured model is remote, the image is
 * sent to it; the UI wording is "AI 识别 / 表情包识图" for exactly that reason and never claims the
 * model is local.
 */
class ProviderStickerVisionClient(
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
) : StickerVisionClient {

    override suspend fun describe(imageFile: File): StickerVisionOutcome {
        if (!imageFile.isFile) return failure(StickerVisionFailure.REQUEST_FAILED)

        val settings = settingsStore.settingsFlow.value
        val modelId = settings.stickerVisionModelId
            ?: return failure(StickerVisionFailure.MODEL_NOT_CONFIGURED)
        val model = settings.findModelById(modelId)
            ?: return failure(StickerVisionFailure.MODEL_NOT_FOUND)

        // Checked before the call rather than after a rejection: a text-only model asked to look at
        // a picture either errors or, worse, answers confidently about nothing.
        if (Modality.IMAGE !in model.inputModalities) {
            return failure(StickerVisionFailure.IMAGE_INPUT_UNSUPPORTED)
        }

        val providerSetting = model.findProvider(settings.providers)
            ?: return failure(StickerVisionFailure.PROVIDER_UNAVAILABLE)

        val text = runCatching {
            val provider = providerManager.getProviderByType(providerSetting)
            val response = provider.generateText(
                providerSetting = providerSetting,
                messages = listOf(
                    UIMessage.system(StickerPrompt.SYSTEM),
                    UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(UIMessagePart.Image("file://${imageFile.absolutePath}")),
                    ),
                ),
                params = TextGenerationParams(
                    model = model,
                    // Some OpenCode-compatible vision models reject reasoning_effort="none"
                    // instead of treating it as disabled, and this is a one-shot extraction with
                    // no reasoning to configure.
                    omitReasoningConfigurationWhenOff = true,
                ),
            )
            response.choices.firstOrNull()?.message?.toText()?.trim().orEmpty()
        }.getOrElse {
            // The provider's exception is deliberately dropped rather than stored or shown: it
            // carries endpoint URLs and sometimes response bodies, and none of that belongs in a
            // row that outlives the request.
            return failure(StickerVisionFailure.REQUEST_FAILED)
        }

        if (text.isEmpty()) return failure(StickerVisionFailure.EMPTY_RESPONSE)

        val metadata = StickerVisionParser.parse(text)
            ?: return failure(StickerVisionFailure.MALFORMED_RESPONSE)

        return StickerVisionOutcome.Success(
            metadata = metadata,
            providerLabel = providerSetting.name.ifBlank {
                providerSetting::class.simpleName ?: "provider"
            },
        )
    }

    private fun failure(reason: StickerVisionFailure): StickerVisionOutcome.Failure =
        StickerVisionOutcome.Failure(reason)
}
