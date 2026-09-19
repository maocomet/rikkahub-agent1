package me.rerere.rikkahub.sticker

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.TransformerContext

/**
 * Turns a sent sticker into a real image in the assistant's message.
 *
 * `sticker_send` produces an image part, and a tool's output is rendered inside the collapsible
 * tool step — which reads as *"the assistant ran a tool that happened to return a picture"*. What
 * the person actually did was receive a sticker, so the image belongs in the message itself.
 *
 * This moves it there and removes it from the tool output, in one pass, which is what makes it
 * appear **exactly once**: the image is either in the tool step or in the message, never both. The
 * tool output keeps only its acknowledgement.
 *
 * It runs on the existing output-transformer hook rather than through a new message protocol.
 * `Base64ImageToLocalFileTransformer` already uses the same `onGenerationFinish` seam to rewrite
 * message parts before they are persisted, so this is the established place for exactly this kind
 * of adjustment.
 *
 * **Idempotent by construction.** A second pass finds no image left in the tool output, so it
 * neither duplicates the image nor disturbs the message. That matters because the hook is applied
 * wherever the transformer list is used, and a transformer that could double an image on a re-run
 * would be a bug waiting for the second caller.
 *
 * Placed immediately after the tool part that produced it, so if further tool steps follow, the
 * sticker still reads as the consequence of the send rather than trailing the whole turn.
 */
object StickerSendToMessagePartTransformer : OutputMessageTransformer {

    override suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = messages.map { message ->
        // Only assistant turns carry tool calls, and only they should gain the image.
        if (message.role != MessageRole.ASSISTANT) return@map message

        var lifted = false
        val parts = mutableListOf<UIMessagePart>()
        message.parts.forEach { part ->
            if (part !is UIMessagePart.Tool || part.toolName != STICKER_SEND_TOOL_NAME) {
                parts += part
                return@forEach
            }
            val images = part.output.filterIsInstance<UIMessagePart.Image>()
            if (images.isEmpty()) {
                parts += part
                return@forEach
            }
            lifted = true
            // The tool's own record keeps everything that is not the picture, which is the short
            // acknowledgement the model itself sees in the next round.
            parts += part.copy(output = part.output.filterNot { it is UIMessagePart.Image })
            parts += images
        }

        if (lifted) message.copy(parts = parts) else message
    }
}
