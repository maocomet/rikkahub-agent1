package me.rerere.rikkahub.sticker

import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Sending a sticker, and the file lifecycle that makes it safe.
 *
 * Two properties matter here beyond the obvious validation, and both are what the copy exists for:
 * a history entry survives the library item being deleted, and deleting a conversation cannot
 * reach the library. Neither can be observed through a fake that shares one directory, so the
 * library and the conversation's storage are genuinely separate folders throughout.
 */
class StickerDeliveryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val dao = FakeStickerDao()
    private var ids = 0

    // `by lazy`, not plain initialisers and not accessors. A JUnit rule has not created the
    // temporary folder when the test class is constructed, so reading `temporaryFolder.root` from a
    // field initialiser throws — and an accessor would hand out a *new* store on every read, so the
    // files a test inspects would not be the files the send wrote.
    private val libraryRoot: File by lazy { File(temporaryFolder.root, "library") }
    private val fileStore: StickerFileStore by lazy { StickerFileStore(libraryRoot) }
    private val repository: StickerRepository by lazy {
        StickerRepository(
            dao = dao,
            fileStore = fileStore,
            nowMs = { 1_000L },
            newId = { "sticker-${ids++}" },
        )
    }
    private val attachments: FakeConversationAttachmentStore by lazy {
        FakeConversationAttachmentStore(temporaryFolder.root)
    }
    private val delivery: StickerDelivery by lazy {
        StickerDelivery(repository = repository, attachments = attachments)
    }

    private fun checksumOf(tag: Int): String = MessageDigest.getInstance("SHA-256")
        .digest(StickerFixtures.png(tag = tag))
        .joinToString("") { "%02x".format(it) }

    /** A library row with a real image on disk. */
    private fun librarySticker(
        tag: Int,
        enabled: Boolean = true,
        withFile: Boolean = true,
    ): Sticker {
        val id = "sticker-$tag"
        val relativePath = fileStore.commit(
            fileStore.stage(ByteArrayInputStream(StickerFixtures.png(tag = tag))),
            stickerId = id,
            extension = "png",
        )!!
        if (!withFile) fileStore.delete(relativePath)
        return runBlocking {
            repository.insertSticker(
                stickerId = id,
                relativePath = relativePath,
                mimeType = "image/png",
                width = 64,
                height = 64,
                description = "一只猫缩成一团，委屈又有点生气",
                tags = listOf("委屈", "猫猫"),
                checksum = checksumOf(tag),
                enabled = enabled,
            )
        }
    }

    private suspend fun send(
        stickerId: String,
        assistantEnabled: Boolean = true,
        origin: ToolCallOrigin? = ToolCallOrigin.LocalChat,
    ) = delivery.send(
        stickerId = stickerId,
        assistantEnabled = assistantEnabled,
        callOrigin = origin,
    )

    // ── The happy path ───────────────────────────────────────────────────────────────────────

    @Test
    fun `sending copies the image into the conversation's own storage`() = runBlocking {
        val sticker = librarySticker(tag = 1)

        val outcome = send(sticker.id)

        val sent = outcome as StickerSendOutcome.Sent
        val copied = sent.image.url.removePrefix("file://")
        assertTrue("the image must point at a file", File(copied).isFile)
        assertTrue(
            "the conversation must own a copy, not point at the library",
            File(copied).canonicalPath.startsWith(
                attachments.directory.canonicalPath + File.separator,
            ),
        )
        assertNotEquals(
            fileStore.resolve(sticker.relativePath)!!.canonicalPath,
            File(copied).canonicalPath,
        )
    }

    @Test
    fun `sending leaves the library copy untouched`() = runBlocking {
        val sticker = librarySticker(tag = 1)

        send(sticker.id)

        assertTrue(fileStore.resolve(sticker.relativePath)!!.isFile)
    }

    @Test
    fun `the sent image url never names the library`() = runBlocking {
        val sticker = librarySticker(tag = 1)

        val sent = send(sticker.id) as StickerSendOutcome.Sent

        assertFalse(
            "a tool output naming the library file would be deleted with the conversation",
            sent.image.url.contains("/stickers/"),
        )
    }

    // ── Validation ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `an id that does not exist is refused`() = runBlocking {
        assertEquals(
            StickerSendOutcome.Rejected(StickerToolError.STICKER_NOT_FOUND),
            send("no-such-sticker"),
        )
    }

    @Test
    fun `a disabled sticker is refused even when its id is known`() = runBlocking {
        val sticker = librarySticker(tag = 1, enabled = false)

        assertEquals(
            StickerSendOutcome.Rejected(StickerToolError.STICKER_DISABLED),
            send(sticker.id),
        )
        assertTrue("nothing may be copied for a refusal", attachments.copiedFiles.isEmpty())
    }

    @Test
    fun `a sticker whose file is gone is refused rather than sent as a dead path`() = runBlocking {
        val sticker = librarySticker(tag = 1, withFile = false)

        assertEquals(
            StickerSendOutcome.Rejected(StickerToolError.STICKER_FILE_MISSING),
            send(sticker.id),
        )
    }

    @Test
    fun `an assistant that has not opted in is refused`() = runBlocking {
        val sticker = librarySticker(tag = 1)

        assertEquals(
            StickerSendOutcome.Rejected(StickerToolError.STICKER_TOOLS_DISABLED),
            send(sticker.id, assistantEnabled = false),
        )
    }

    @Test
    fun `a non-local origin is refused`() = runBlocking {
        val sticker = librarySticker(tag = 1)

        listOf(
            ToolCallOrigin.Telegram,
            ToolCallOrigin.WebServer,
            ToolCallOrigin.MCP,
            ToolCallOrigin.ExternalIntent,
            ToolCallOrigin.TrustedWorkflow,
            ToolCallOrigin.QuickCapture,
            ToolCallOrigin.SystemAssistant,
            ToolCallOrigin.PetInteraction,
            null,
        ).forEach { origin ->
            assertEquals(
                "origin $origin must not be able to send from the local library",
                StickerSendOutcome.Rejected(StickerToolError.STICKER_SURFACE_NOT_ALLOWED),
                send(sticker.id, origin = origin),
            )
        }
    }

    @Test
    fun `a copy that fails is refused rather than falling back to the library file`() = runBlocking {
        val sticker = librarySticker(tag = 1)
        attachments.failNextImport = true

        val outcome = send(sticker.id)

        assertEquals(
            StickerSendOutcome.Rejected(StickerToolError.STICKER_COPY_FAILED),
            outcome,
        )
    }

    // ── History stability ────────────────────────────────────────────────────────────────────

    @Test
    fun `a sent sticker still displays after the library item is deleted`() = runBlocking {
        val sticker = librarySticker(tag = 1)
        val sent = send(sticker.id) as StickerSendOutcome.Sent
        val copied = File(sent.image.url.removePrefix("file://"))

        assertTrue(repository.deleteSticker(sticker.id))

        assertTrue(
            "what was sent was sent — deleting the library item must not break history",
            copied.isFile,
        )
        assertTrue(sent.image.url.isNotBlank())
    }

    @Test
    fun `deleting the conversation removes only the conversation's copies`() = runBlocking {
        val kept = librarySticker(tag = 1)
        val alsoKept = librarySticker(tag = 2)
        val sent = send(kept.id) as StickerSendOutcome.Sent
        val copied = File(sent.image.url.removePrefix("file://"))
        assertTrue(copied.isFile)

        // What deleting the conversation does to the files it owns.
        attachments.deleteEverything()

        assertFalse(copied.isFile)
        assertTrue(
            "deleting a conversation must not touch the shared library",
            fileStore.resolve(kept.relativePath)!!.isFile,
        )
        assertTrue(fileStore.resolve(alsoKept.relativePath)!!.isFile)
    }

    // ── One copy per send ────────────────────────────────────────────────────────────────────

    @Test
    fun `sending the same sticker twice produces two independent copies`() = runBlocking {
        val sticker = librarySticker(tag = 1)

        val first = send(sticker.id) as StickerSendOutcome.Sent
        val second = send(sticker.id) as StickerSendOutcome.Sent

        assertNotEquals(
            "a shared copy would be deleted by whichever conversation went first",
            first.image.url,
            second.image.url,
        )
        assertEquals(2, attachments.copiedFiles.size)
        assertTrue(File(first.image.url.removePrefix("file://")).isFile)
        assertTrue(File(second.image.url.removePrefix("file://")).isFile)
    }

    @Test
    fun `deleting one conversation's copies leaves the other's alone`() = runBlocking {
        val sticker = librarySticker(tag = 1)
        val first = send(sticker.id) as StickerSendOutcome.Sent
        val second = send(sticker.id) as StickerSendOutcome.Sent
        val firstCopy = File(first.image.url.removePrefix("file://"))

        firstCopy.delete()

        assertFalse(firstCopy.isFile)
        assertTrue(
            "the other conversation's copy must survive",
            File(second.image.url.removePrefix("file://")).isFile,
        )
    }
}
