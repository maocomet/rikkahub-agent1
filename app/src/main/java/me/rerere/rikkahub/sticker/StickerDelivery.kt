package me.rerere.rikkahub.sticker

import androidx.core.net.toUri
import java.io.File
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager

/**
 * Stable failure codes for the sticker tools.
 *
 * Short, stable identifiers rather than prose, because the model reads them and because
 * `sticker_send` is refused for reasons that must stay distinguishable: "you are not allowed to
 * send" and "that sticker is gone" call for different follow-ups, and collapsing them into one
 * message would make the model retry the wrong thing.
 */
enum class StickerToolError {
    /** The assistant has not opted in to sticker tools. */
    STICKER_TOOLS_DISABLED,

    /** The invocation origin may not carry sticker tools. */
    STICKER_SURFACE_NOT_ALLOWED,

    /** No sticker with that id exists. */
    STICKER_NOT_FOUND,

    /** The sticker exists but the person switched it off. */
    STICKER_DISABLED,

    /** The row exists but its image file does not. */
    STICKER_FILE_MISSING,

    /** The image could not be copied into the conversation's storage. */
    STICKER_COPY_FAILED,
}

sealed interface StickerSendOutcome {
    /**
     * The sticker reached the conversation.
     *
     * [image] points at the conversation's **own copy**, never at the library file, and [sticker]
     * is the library row it came from.
     */
    data class Sent(
        val sticker: Sticker,
        val image: UIMessagePart.Image,
    ) : StickerSendOutcome

    data class Rejected(val error: StickerToolError) : StickerSendOutcome
}

/**
 * Turns a library sticker into an image part belonging to one conversation.
 *
 * ## Why the bytes are copied
 *
 * The obvious implementation — hand back `file://<filesDir>/stickers/<id>.png` — is a data-loss
 * bug, not a shortcut. `Conversation.files` walks every part recursively *including tool output*,
 * and `FilesManager.deleteChatFiles` deletes every `file://` URI under `filesDir` it is handed,
 * with no reference counting. So a tool output naming the library's own file means **deleting the
 * conversation deletes the person's sticker from the library**.
 *
 * Copying into the managed `upload/` folder puts a sent sticker on exactly the same footing as
 * every other image in a conversation: one copy per send, owned by that conversation, removed with
 * it. Deleting the library sticker afterwards leaves every already-sent copy alone, which is the
 * behaviour the person expects — what was sent was sent.
 *
 * One copy per send rather than one shared copy, because the delete path is unconditional: two
 * conversations pointing at one file would mean deleting either one breaks the other.
 *
 * ## Why the checks are repeated here
 *
 * The tool surface already withholds the schema when the assistant has not opted in or the origin
 * is not allowed, so reaching this code with either false means something went wrong upstream.
 * It is checked again anyway: a tool that can be invoked is a tool whose guards should not depend
 * on the caller having honoured a gate it never had to prove it honoured.
 */
/**
 * Puts a library image into a conversation's own storage and returns the `file://` URL of the copy.
 *
 * A seam rather than `FilesManager` used directly, because the property that matters here — a
 * conversation's copy is independent of the library file — is exactly the property a JVM test
 * cannot observe through a concrete Android service. `null` means the copy was not made, and the
 * send is refused rather than falling back to the library file.
 */
fun interface ConversationAttachmentStore {
    suspend fun importForConversation(
        source: File,
        displayName: String,
        mimeType: String,
    ): String?
}

/** The production store: the managed `upload/` folder and its index. */
class FilesManagerConversationAttachmentStore(
    private val filesManager: FilesManager,
) : ConversationAttachmentStore {
    override suspend fun importForConversation(
        source: File,
        displayName: String,
        mimeType: String,
    ): String? = runCatching {
        val entity = filesManager.saveManagedFromFile(
            folder = FileFolders.UPLOAD,
            source = source,
            // Keeps the format extension, which the managed store derives the stored filename from.
            // The library's own uuid name means nothing to the conversation.
            displayName = displayName,
            mimeType = mimeType,
        )
        filesManager.getFile(entity).toUri().toString()
    }.getOrNull()
}

class StickerDelivery(
    private val repository: StickerRepository,
    private val attachments: ConversationAttachmentStore,
) {

    suspend fun send(
        stickerId: String,
        assistantEnabled: Boolean,
        callOrigin: ToolCallOrigin?,
    ): StickerSendOutcome {
        if (!assistantEnabled) return StickerSendOutcome.Rejected(StickerToolError.STICKER_TOOLS_DISABLED)
        // A null origin is denied, not defaulted: an invocation that cannot say where it came from
        // has not earned the local-chat surface.
        if (callOrigin == null || callOrigin !in StickerToolSurface.ALLOWED_ORIGINS) {
            return StickerSendOutcome.Rejected(StickerToolError.STICKER_SURFACE_NOT_ALLOWED)
        }

        // Resolved by id and nothing else. The tool takes no path, no URI and no URL, so there is
        // no argument through which a model could name a file it should not reach; the lookup goes
        // row -> stored relative path -> path-safe resolution, all of it app-owned.
        val sticker = repository.getSticker(stickerId)
            ?: return StickerSendOutcome.Rejected(StickerToolError.STICKER_NOT_FOUND)
        if (!sticker.enabled) {
            return StickerSendOutcome.Rejected(StickerToolError.STICKER_DISABLED)
        }
        val source = repository.absolutePathOf(sticker.relativePath)
            ?: return StickerSendOutcome.Rejected(StickerToolError.STICKER_FILE_MISSING)

        val url = attachments.importForConversation(
            source = source,
            displayName = source.name,
            mimeType = sticker.mimeType,
        ) ?: return StickerSendOutcome.Rejected(StickerToolError.STICKER_COPY_FAILED)

        return StickerSendOutcome.Sent(
            sticker = sticker,
            image = UIMessagePart.Image(url = url),
        )
    }
}
