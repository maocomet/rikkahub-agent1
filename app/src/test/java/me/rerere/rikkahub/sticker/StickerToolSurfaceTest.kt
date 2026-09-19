package me.rerere.rikkahub.sticker

import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Who may see the sticker tools, and what those tools say.
 *
 * The gate is asserted on both sides of the same rule — the name set, and the built list — because
 * the surface is the only thing standing between a switched-off assistant and a schema it must
 * never be sent.
 */
class StickerToolSurfaceTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val dao = FakeStickerDao()
    private var ids = 0

    private val libraryRoot: File by lazy { File(temporaryFolder.root, "library") }
    private val fileStore: StickerFileStore by lazy { StickerFileStore(libraryRoot) }
    private val repository: StickerRepository by lazy {
        StickerRepository(
            dao = dao,
            fileStore = fileStore,
            nowMs = { 1_000L },
            newId = { "generated-${ids++}" },
        )
    }
    private val attachments: FakeConversationAttachmentStore by lazy {
        FakeConversationAttachmentStore(temporaryFolder.root)
    }
    private val delivery: StickerDelivery by lazy {
        StickerDelivery(repository = repository, attachments = attachments)
    }

    private fun context(callOrigin: ToolCallOrigin?) = ToolInvocationContext(
        callerAssistantId = "aaaaaaaa-0000-0000-0000-000000000001",
        callOrigin = callOrigin,
    )

    private fun toolsFor(enabled: Boolean, callOrigin: ToolCallOrigin?) =
        StickerToolSurface.build(
            delivery = delivery,
            repository = repository,
            invocationContext = context(callOrigin),
            assistantEnabled = enabled,
        )

    private fun searchTool(enabled: Boolean = true) =
        toolsFor(enabled, ToolCallOrigin.LocalChat).single { it.name == STICKER_SEARCH_TOOL_NAME }

    private fun sendTool(enabled: Boolean = true) =
        toolsFor(enabled, ToolCallOrigin.LocalChat).single { it.name == STICKER_SEND_TOOL_NAME }

    private fun librarySticker(tag: Int, enabled: Boolean = true, description: String = "一只委屈的猫"): Sticker {
        val id = "sticker-$tag"
        val relativePath = fileStore.commit(
            fileStore.stage(ByteArrayInputStream(StickerFixtures.png(tag = tag))),
            stickerId = id,
            extension = "png",
        )!!
        return runBlocking {
            repository.insertSticker(
                stickerId = id,
                relativePath = relativePath,
                mimeType = "image/png",
                width = 64,
                height = 64,
                description = description,
                tags = listOf("委屈", "猫猫"),
                checksum = MessageDigest.getInstance("SHA-256")
                    .digest(StickerFixtures.png(tag = tag))
                    .joinToString("") { "%02x".format(it) },
                enabled = enabled,
            )
        }
    }

    // ── The gate ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an assistant that has not opted in carries no sticker schema on any origin`() {
        ToolCallOrigin.entries.forEach { origin ->
            assertTrue(
                "origin $origin was allowed for an assistant that never opted in",
                StickerToolSurface.toolNamesFor(assistantEnabled = false, callOrigin = origin).isEmpty(),
            )
            assertTrue(toolsFor(enabled = false, callOrigin = origin).isEmpty())
        }
        assertTrue(StickerToolSurface.toolNamesFor(false, null).isEmpty())
        assertTrue(toolsFor(enabled = false, callOrigin = null).isEmpty())
    }

    @Test
    fun `an opted-in assistant carries exactly the two sticker tools on local chat`() {
        assertEquals(
            STICKER_TOOL_NAMES,
            StickerToolSurface.toolNamesFor(assistantEnabled = true, callOrigin = ToolCallOrigin.LocalChat),
        )
        assertEquals(
            STICKER_TOOL_NAMES,
            toolsFor(enabled = true, callOrigin = ToolCallOrigin.LocalChat).map { it.name }.toSet(),
        )
    }

    @Test
    fun `every origin other than local chat is denied even when opted in`() {
        // TrustedWorkflow is deliberately in this list. Cat Garden admits it; this phase is about
        // ordinary chat, and a workflow must not gain access to local files by other means.
        ToolCallOrigin.entries
            .filter { it != ToolCallOrigin.LocalChat }
            .forEach { origin ->
                assertTrue(
                    "origin $origin must not carry the sticker surface",
                    StickerToolSurface.toolNamesFor(assistantEnabled = true, callOrigin = origin).isEmpty(),
                )
                assertTrue(toolsFor(enabled = true, callOrigin = origin).isEmpty())
            }
    }

    @Test
    fun `an absent origin fails closed`() {
        assertTrue(StickerToolSurface.toolNamesFor(true, null).isEmpty())
        assertTrue(toolsFor(enabled = true, callOrigin = null).isEmpty())
    }

    // ── sticker_search output ────────────────────────────────────────────────────────────────

    @Test
    fun `search returns candidates with no local path of any kind`() = runBlocking {
        val sticker = librarySticker(tag = 1)

        val payload = searchTool().execute(buildJsonObject { put("query", "委屈") })
            .single().let { it as UIMessagePart.Text }.text

        assertTrue(payload.contains(sticker.id))
        assertTrue(payload.contains("一只委屈的猫"))
        // The model must never be handed something it could try to open.
        assertFalse("no absolute path", payload.contains(libraryRoot.absolutePath))
        assertFalse("no file uri", payload.contains("file://"))
        assertFalse("no library directory name", payload.contains("/stickers/"))
    }

    @Test
    fun `search excludes a disabled sticker`() = runBlocking {
        val disabled = librarySticker(tag = 1, enabled = false)
        val enabled = librarySticker(tag = 2)

        val payload = searchTool().execute(buildJsonObject { put("query", "委屈") })
            .single().let { it as UIMessagePart.Text }.text

        assertFalse(payload.contains(disabled.id))
        assertTrue(payload.contains(enabled.id))
    }

    @Test
    fun `search excludes a sticker whose file is gone`() = runBlocking {
        val missing = librarySticker(tag = 1)
        fileStore.delete(missing.relativePath)

        val payload = searchTool().execute(buildJsonObject { put("query", "委屈") })
            .single().let { it as UIMessagePart.Text }.text

        assertEquals("NO_MATCHES", Json.parseToJsonElement(payload).jsonObject.getValue("code").jsonPrimitive.content)
        assertTrue(Json.parseToJsonElement(payload).jsonObject.getValue("candidates").jsonArray.isEmpty())
    }

    @Test
    fun `an empty query is reported rather than treated as a request for everything`() = runBlocking {
        librarySticker(tag = 1)

        val payload = searchTool().execute(buildJsonObject { put("query", "  ") })
            .single().let { it as UIMessagePart.Text }.text

        val json = Json.parseToJsonElement(payload).jsonObject
        assertEquals("EMPTY_QUERY", json.getValue("code").jsonPrimitive.content)
        assertTrue(json.getValue("candidates").jsonArray.isEmpty())
    }

    @Test
    fun `a query with no matches is not an error`() = runBlocking {
        librarySticker(tag = 1)

        val payload = searchTool().execute(buildJsonObject { put("query", "宇航员") })
            .single().let { it as UIMessagePart.Text }.text

        val json = Json.parseToJsonElement(payload).jsonObject
        assertTrue(json.getValue("ok").jsonPrimitive.content.toBoolean())
        assertEquals("NO_MATCHES", json.getValue("code").jsonPrimitive.content)
    }

    @Test
    fun `the limit parameter is honoured and clamped`() = runBlocking {
        (1..5).forEach { librarySticker(tag = it) }

        fun candidatesFor(limit: Int): Int {
            val payload = runBlocking {
                searchTool().execute(buildJsonObject {
                    put("query", "委屈")
                    put("limit", limit)
                }).single().let { it as UIMessagePart.Text }.text
            }
            return Json.parseToJsonElement(payload).jsonObject.getValue("candidates").jsonArray.size
        }

        assertEquals(2, candidatesFor(2))
        // Above the maximum the tool advertises, so it clamps rather than trusting the model.
        assertEquals(5, candidatesFor(1000))
    }

    // ── sticker_send output ──────────────────────────────────────────────────────────────────

    @Test
    fun `send acknowledges with no path in the acknowledgement`() = runBlocking {
        val sticker = librarySticker(tag = 1)

        val parts = sendTool().execute(buildJsonObject { put("sticker_id", sticker.id) })

        val ack = parts.filterIsInstance<UIMessagePart.Text>().single().text
        assertTrue(ack.contains("STICKER_SENT"))
        assertFalse(ack.contains("file://"))
        assertFalse(ack.contains(libraryRoot.absolutePath))
        assertEquals(
            "the image is carried as a part and lifted into the message by the transformer",
            1,
            parts.filterIsInstance<UIMessagePart.Image>().size,
        )
    }

    @Test
    fun `send of an unknown id reports a stable code rather than throwing`() = runBlocking {
        val payload = sendTool().execute(buildJsonObject { put("sticker_id", "no-such-id") })
            .single().let { it as UIMessagePart.Text }.text

        val json = Json.parseToJsonElement(payload).jsonObject
        assertFalse(json.getValue("ok").jsonPrimitive.content.toBoolean())
        assertEquals("STICKER_NOT_FOUND", json.getValue("code").jsonPrimitive.content)
    }

    @Test
    fun `send ignores anything that is not a sticker_id`() = runBlocking {
        val sticker = librarySticker(tag = 1)

        // A model trying to name a file instead of a sticker gets nowhere: the only key read is
        // `sticker_id`, and a path is not one.
        val payload = sendTool().execute(buildJsonObject { put("sticker_id", "/etc/passwd") })
            .single().let { it as UIMessagePart.Text }.text

        assertEquals(
            "STICKER_NOT_FOUND",
            Json.parseToJsonElement(payload).jsonObject.getValue("code").jsonPrimitive.content,
        )
        assertTrue(sticker.id.isNotBlank())
    }

    // ── Tool schema shape ────────────────────────────────────────────────────────────────────

    @Test
    fun `the send schema accepts only a sticker_id`() {
        val schema = sendTool().parameters() as InputSchema.Obj

        assertEquals(setOf("sticker_id"), schema.properties.keys)
        assertEquals(listOf("sticker_id"), schema.required)
    }

    @Test
    fun `the search schema takes a query and an optional limit`() {
        val schema = searchTool().parameters() as InputSchema.Obj

        assertEquals(setOf("query", "limit"), schema.properties.keys)
        assertEquals(listOf("query"), schema.required)
    }

    // ── Assistant field serialization ────────────────────────────────────────────────────────

    @Test
    fun `an assistant saved before sticker tools existed decodes as disabled`() {
        val decoded = JsonInstant.decodeFromString<Assistant>(
            """{"name":"Legacy","localTools":[{"type":"time_info"}]}""",
        )

        assertFalse(
            "a stored assistant must not gain a capability it was never granted",
            decoded.stickerToolsEnabled,
        )
    }

    @Test
    fun `the sticker tools flag round-trips`() {
        assertTrue(
            JsonInstant.decodeFromString<Assistant>(
                JsonInstant.encodeToString(Assistant(name = "A", stickerToolsEnabled = true)),
            ).stickerToolsEnabled,
        )
        assertFalse(
            JsonInstant.decodeFromString<Assistant>(
                JsonInstant.encodeToString(Assistant(name = "A")),
            ).stickerToolsEnabled,
        )
    }
}
