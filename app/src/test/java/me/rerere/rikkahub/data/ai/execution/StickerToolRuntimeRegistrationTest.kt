package me.rerere.rikkahub.data.ai.execution

import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.sticker.FakeConversationAttachmentStore
import me.rerere.rikkahub.sticker.FakeStickerDao
import me.rerere.rikkahub.sticker.STICKER_SEARCH_TOOL_NAME
import me.rerere.rikkahub.sticker.STICKER_SEND_TOOL_NAME
import me.rerere.rikkahub.sticker.STICKER_TOOL_NAMES
import me.rerere.rikkahub.sticker.StickerDelivery
import me.rerere.rikkahub.sticker.StickerFileStore
import me.rerere.rikkahub.sticker.StickerFixtures
import me.rerere.rikkahub.sticker.StickerRepository
import me.rerere.rikkahub.sticker.StickerToolSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Every sticker tool must be runnable, not merely visible.
 *
 * The same defence Cat Garden needed. A tool can be in the model-visible surface — so the model is
 * sent its schema and can call it — while being absent from [InternalToolSecurityCatalog], in which
 * case `DefaultToolRuntime.assess` finds no descriptor and rejects every call with
 * `tool_security_descriptor_missing` before the body runs. It happened to `space_delete_post` and
 * `space_list_comments`, and no tool-level test could see it, because those invoke `Tool.execute`
 * directly — the one path that skips the runtime entirely.
 *
 * So this asks the runtime, and then goes further than the Cat Garden test did: it drives each tool
 * through `DefaultToolRuntime.execute` for real, which is what turns "the descriptor exists" into
 * "the call actually completes".
 */
class StickerToolRuntimeRegistrationTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val descriptorResolver = DefaultToolSecurityDescriptorResolver()
    private val policyResolver = DefaultToolExecutionPolicyResolver()
    private val runtime = DefaultToolRuntime(policyResolver = policyResolver)

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

    private fun context() = ToolExecutionContext(
        runId = Uuid.random(),
        conversationId = Uuid.random(),
        assistantId = "aaaaaaaa-0000-0000-0000-000000000001",
        callOrigin = ToolCallOrigin.LocalChat,
    )

    private fun tools(): List<Tool> = StickerToolSurface.build(
        delivery = delivery,
        repository = repository,
        invocationContext = ToolInvocationContext(
            callerAssistantId = "aaaaaaaa-0000-0000-0000-000000000001",
            callOrigin = ToolCallOrigin.LocalChat,
        ),
        assistantEnabled = true,
    )

    private fun toolNamed(name: String): Tool = tools().single { it.name == name }

    private fun librarySticker(tag: Int): String {
        val id = "sticker-$tag"
        val relativePath = fileStore.commit(
            fileStore.stage(ByteArrayInputStream(StickerFixtures.png(tag = tag))),
            stickerId = id,
            extension = "png",
        )!!
        runBlocking {
            repository.insertSticker(
                stickerId = id,
                relativePath = relativePath,
                mimeType = "image/png",
                width = 64,
                height = 64,
                description = "一只委屈的猫",
                tags = listOf("委屈", "猫猫"),
                checksum = MessageDigest.getInstance("SHA-256")
                    .digest(StickerFixtures.png(tag = tag))
                    .joinToString("") { "%02x".format(it) },
            )
        }
        return id
    }

    /** The real path a model's tool call takes, end to end. */
    private suspend fun executeThroughRuntime(tool: Tool, args: JsonObject): ToolExecutionPlanResult =
        runtime.execute(
            ToolExecutionPlanRequest(
                toolCallId = "call-1",
                toolName = tool.name,
                args = args,
                executionContext = context(),
                startableTool = null,
                legacyExecute = { tool.execute(it) },
                runControl = null,
                wallClockBudgetMs = 5_000L,
            ),
        )

    // ── Catalog completeness ─────────────────────────────────────────────────────────────────

    @Test
    fun `every sticker tool has a runtime security descriptor`() {
        STICKER_TOOL_NAMES.forEach { name ->
            assertNotNull(
                "$name is offered to the model but has no runtime descriptor, so every call is " +
                    "rejected as tool_security_descriptor_missing before it executes",
                descriptorResolver.resolve(name, context()),
            )
        }
    }

    @Test
    fun `every sticker tool is classified in exactly one catalog set`() {
        STICKER_TOOL_NAMES.forEach { name ->
            val readOnly = name in InternalToolSecurityCatalog.READ_ONLY
            val mutating = name in InternalToolSecurityCatalog.MUTATING
            assertTrue("$name is in neither READ_ONLY nor MUTATING", readOnly || mutating)
            assertTrue("$name must not be classified as both", !(readOnly && mutating))
            assertTrue(
                "$name must not depend on its arguments",
                name !in InternalToolSecurityCatalog.ARGUMENT_DEPENDENT,
            )
        }
    }

    @Test
    fun `no sticker tool falls through to an unknown policy`() {
        STICKER_TOOL_NAMES.forEach { name ->
            val policy = policyResolver.resolve(name, JsonObject(emptyMap()), context())
            assertTrue("$name resolves to an UNKNOWN effect set", ToolEffect.UNKNOWN !in policy.effects)
        }
    }

    @Test
    fun `sending a sticker is a serial persistent-state write`() {
        val policy = policyResolver.resolve(STICKER_SEND_TOOL_NAME, JsonObject(emptyMap()), context())

        // It writes a conversation-scoped copy of a library file, so it mutates durable state and
        // is serialised rather than batched alongside reads.
        assertEquals(setOf(ToolEffect.PERSISTENT_STATE), policy.effects)
        assertEquals(ToolConcurrency.GLOBAL_SERIAL, policy.concurrency)
        assertEquals(
            ToolDescriptorSource.INTERNAL,
            descriptorResolver.resolve(STICKER_SEND_TOOL_NAME, context())?.source,
        )
    }

    @Test
    fun `searching is a parallel-safe read`() {
        val policy = policyResolver.resolve(STICKER_SEARCH_TOOL_NAME, JsonObject(emptyMap()), context())

        assertEquals(setOf(ToolEffect.LOCAL_READ), policy.effects)
        assertEquals(ToolConcurrency.PARALLEL_SAFE, policy.concurrency)
    }

    // ── The calls actually run ───────────────────────────────────────────────────────────────

    @Test
    fun `search completes through the runtime`() = runBlocking {
        val id = librarySticker(tag = 1)

        val result = executeThroughRuntime(
            toolNamed(STICKER_SEARCH_TOOL_NAME),
            buildJsonObject { put("query", "委屈") },
        )

        val completed = result as? ToolExecutionPlanResult.Completed
        assertNotNull(
            "the runtime rejected the call before it ran: " +
                (result as? ToolExecutionPlanResult.Rejected)?.errorCode,
            completed,
        )
        val text = completed!!.output.filterIsInstance<UIMessagePart.Text>().single().text
        assertTrue("the search did not return the stored sticker", text.contains(id))
        assertFalse("a tool result must never carry a local path", text.contains("file://"))
    }

    @Test
    fun `send completes through the runtime and reaches the conversation store`() = runBlocking {
        val id = librarySticker(tag = 1)

        val result = executeThroughRuntime(
            toolNamed(STICKER_SEND_TOOL_NAME),
            buildJsonObject { put("sticker_id", id) },
        )

        val completed = result as? ToolExecutionPlanResult.Completed
        assertNotNull(
            "the runtime rejected the call before it ran: " +
                (result as? ToolExecutionPlanResult.Rejected)?.errorCode,
            completed,
        )
        assertTrue("the runtime call copied nothing", attachments.copiedFiles.isNotEmpty())
        assertEquals(
            "the image part must survive the runtime",
            1,
            completed!!.output.filterIsInstance<UIMessagePart.Image>().size,
        )
    }

    @Test
    fun `send of an unknown id completes with a refusal rather than being rejected by the runtime`() =
        runBlocking {
            val result = executeThroughRuntime(
                toolNamed(STICKER_SEND_TOOL_NAME),
                buildJsonObject { put("sticker_id", "no-such-sticker") },
            )

            val completed = result as? ToolExecutionPlanResult.Completed
            assertNotNull(
                "a bad sticker_id is the tool's answer, not the runtime's",
                completed,
            )
            val text = completed!!.output.filterIsInstance<UIMessagePart.Text>().single().text
            assertTrue(text.contains("STICKER_NOT_FOUND"))
            assertTrue(attachments.copiedFiles.isEmpty())
        }
}
