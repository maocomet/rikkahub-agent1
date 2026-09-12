package me.rerere.rikkahub.space

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.data.ai.tools.SecondUserToolAllowlist
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Cat Garden gate: assistant opt-in plus origin decide whether the model is sent a space
 * schema at all, and a run's writes are attributed by the runtime rather than by arguments.
 */
class SpaceToolSurfaceTest {

    private var idSeq = 0
    private val dao = FakeSpaceDao()
    private val repository = SpaceRepository(
        dao = dao,
        nowMs = { 1_000L },
        newId = { "id-${++idSeq}" },
    )

    private val assistantId = "aaaaaaaa-0000-0000-0000-000000000001"

    private fun context(
        callerAssistantId: String? = assistantId,
        callOrigin: ToolCallOrigin? = ToolCallOrigin.LocalChat,
        isHeadless: Boolean = false,
    ) = ToolInvocationContext(
        callerAssistantId = callerAssistantId,
        callOrigin = callOrigin,
        isHeadless = isHeadless,
    )

    private fun toolsFor(context: ToolInvocationContext) =
        createSpaceTools(repository, context)

    // ── The gate ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a disabled assistant is exposed no space tool on any origin`() {
        ToolCallOrigin.entries.forEach { origin ->
            assertEquals(
                "origin $origin leaked space tools for a disabled assistant",
                emptySet<String>(),
                CatGardenToolSurface.toolNamesFor(assistantEnabled = false, callOrigin = origin),
            )
        }
    }

    @Test
    fun `an enabled assistant on local chat is exposed exactly the designed surface`() {
        assertEquals(
            SPACE_TOOL_NAMES,
            CatGardenToolSurface.toolNamesFor(assistantEnabled = true, callOrigin = ToolCallOrigin.LocalChat),
        )
    }

    @Test
    fun `an enabled assistant is exposed nothing outside the allowed origins`() {
        val allowed = CatGardenToolSurface.ALLOWED_ORIGINS
        ToolCallOrigin.entries.filterNot { it in allowed }.forEach { origin ->
            assertEquals(
                "origin $origin must not carry the space surface",
                emptySet<String>(),
                CatGardenToolSurface.toolNamesFor(assistantEnabled = true, callOrigin = origin),
            )
        }
    }

    @Test
    fun `an absent origin fails closed even when the assistant is enabled`() {
        assertEquals(
            emptySet<String>(),
            CatGardenToolSurface.toolNamesFor(assistantEnabled = true, callOrigin = null),
        )
    }

    @Test
    fun `the built surface is exactly the designed set`() {
        val built = toolsFor(context()).map { it.name }.toSet()
        assertEquals(SPACE_TOOL_NAMES, built)
        assertEquals(7, built.size)
    }

    @Test
    fun `space tool names never collide with a local tool option wire token`() {
        // Cat Garden is a defaulted Assistant field, not a LocalToolOption, precisely so the
        // second-user allowlist cannot be widened by adding it. This is that rule, executable:
        // compared on the wire tokens the allowlist actually matches on, not on Kotlin names.
        val optionTokens = LocalToolOption.PRIVILEGED_IMPLEMENTED.mapTo(linkedSetOf()) { option ->
            me.rerere.rikkahub.utils.JsonInstant
                .encodeToJsonElement(LocalToolOption.serializer(), option)
                .jsonObject.getValue("type").jsonPrimitive.content
        }
        assertTrue(
            "a space tool name collided with a LocalToolOption wire token: " +
                (SPACE_TOOL_NAMES intersect optionTokens),
            (SPACE_TOOL_NAMES intersect optionTokens).isEmpty(),
        )
        // The canonical second-user surface is unchanged by this feature.
        assertEquals(
            LocalToolOption.PRIVILEGED_IMPLEMENTED,
            SecondUserToolAllowlist.resolveLocalOptions(null),
        )
    }

    @Test
    fun `enabling cat garden does not alter the assistant local tool list`() {
        val off = Assistant(name = "A", catGardenEnabled = false)
        val on = off.copy(catGardenEnabled = true)
        assertEquals(off.localTools, on.localTools)
    }

    // ── Identity ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `no space tool declares an author or actor parameter`() {
        val forbidden = setOf("author_id", "actor_id", "author", "actor", "as_assistant")
        toolsFor(context()).forEach { tool ->
            val properties = (tool.parameters() as InputSchema.Obj).properties.keys
            val leaked = properties intersect forbidden
            assertTrue("${tool.name} exposes $leaked", leaked.isEmpty())
        }
    }

    @Test
    fun `a post is attributed to the calling assistant even when arguments claim otherwise`() = runBlocking {
        val tool = toolsFor(context()).single { it.name == SPACE_CREATE_POST_TOOL_NAME }

        // The model supplies extra identity-looking arguments; they must be ignored outright.
        val result = tool.execute(
            buildJsonObject {
                put("content", "hello garden")
                put("author_id", "somebody-else")
                put("actor_id", "somebody-else")
                put("author_kind", "USER")
            },
        )

        val payload = result.single().let { it as UIMessagePart.Text }.text
        val postId = Json.parseToJsonElement(payload).jsonObject
            .getValue("post_id").jsonPrimitive.content
        val stored = requireNotNull(repository.getPost(postId))

        assertEquals(SpaceActorKind.ASSISTANT.name, stored.authorKind)
        assertEquals(assistantId, stored.authorId)
        assertEquals("hello garden", stored.content)
    }

    @Test
    fun `a run with no trusted assistant identity fails closed instead of writing`() = runBlocking {
        val tool = toolsFor(context(callerAssistantId = null))
            .single { it.name == SPACE_CREATE_POST_TOOL_NAME }

        val payload = tool.execute(buildJsonObject { put("content", "x") })
            .single().let { it as UIMessagePart.Text }.text

        val json = Json.parseToJsonElement(payload).jsonObject
        assertFalse(json.getValue("ok").jsonPrimitive.booleanOrNull ?: true)
        assertEquals("SPACE_IDENTITY_UNAVAILABLE", json.getValue("code").jsonPrimitive.content)
        assertEquals(0, dao.posts.size)
    }

    @Test
    fun `headless runs record a causal depth the notification loop guard can refuse`() = runBlocking {
        val interactive = toolsFor(context(isHeadless = false))
            .single { it.name == SPACE_CREATE_POST_TOOL_NAME }
        val headless = toolsFor(context(isHeadless = true))
            .single { it.name == SPACE_CREATE_POST_TOOL_NAME }

        interactive.execute(buildJsonObject { put("content", "from a person") })
        headless.execute(buildJsonObject { put("content", "from an automation") })

        val depths = dao.posts.values.associate { it.content to it.originDepth }
        assertEquals(0, depths.getValue("from a person"))
        assertEquals(1, depths.getValue("from an automation"))
    }

    @Test
    fun `a rejected write reports a code rather than throwing`() = runBlocking {
        val tool = toolsFor(context()).single { it.name == SPACE_CREATE_COMMENT_TOOL_NAME }
        val payload = tool.execute(
            buildJsonObject {
                put("post_id", "no-such-post")
                put("content", "hi")
            },
        ).single().let { it as UIMessagePart.Text }.text

        val json = Json.parseToJsonElement(payload).jsonObject
        assertFalse(json.getValue("ok").jsonPrimitive.booleanOrNull ?: true)
        assertEquals("POST_NOT_FOUND", json.getValue("code").jsonPrimitive.content)
    }

    // ── Assistant field serialization ────────────────────────────────────────────────────────

    @Test
    fun `an assistant saved before cat garden existed decodes as disabled`() {
        val decoded = JsonInstant.decodeFromString<Assistant>(
            """{"name":"Legacy","localTools":[{"type":"time_info"}]}""",
        )
        assertFalse(decoded.catGardenEnabled)
    }

    @Test
    fun `the cat garden flag round-trips`() {
        assertTrue(
            JsonInstant
                .decodeFromString<Assistant>(
                    JsonInstant.encodeToString(Assistant(name = "A", catGardenEnabled = true)),
                )
                .catGardenEnabled,
        )
        assertFalse(
            JsonInstant
                .decodeFromString<Assistant>(
                    JsonInstant.encodeToString(Assistant(name = "A")),
                )
                .catGardenEnabled,
        )
    }

    // ── Shared resolver: chat and workflow ───────────────────────────────────────────────────

    @Test
    fun `the trusted workflow runner may carry the space surface`() {
        assertEquals(
            SPACE_TOOL_NAMES,
            CatGardenToolSurface.toolNamesFor(
                assistantEnabled = true,
                callOrigin = ToolCallOrigin.TrustedWorkflow,
            ),
        )
    }

    @Test
    fun `remote and external origins stay denied`() {
        listOf(
            ToolCallOrigin.Telegram,
            ToolCallOrigin.WebServer,
            ToolCallOrigin.MCP,
            ToolCallOrigin.ExternalIntent,
        ).forEach { origin ->
            assertEquals(
                "origin $origin must not carry the space surface",
                emptySet<String>(),
                CatGardenToolSurface.toolNamesFor(assistantEnabled = true, callOrigin = origin),
            )
        }
    }

    @Test
    fun `switching cat garden off empties the workflow surface too`() {
        // Both surfaces narrow from this one answer, which is what makes an existing workflow
        // fail closed instead of silently continuing to post.
        assertEquals(
            emptySet<String>(),
            CatGardenToolSurface.toolNamesFor(
                assistantEnabled = false,
                callOrigin = ToolCallOrigin.TrustedWorkflow,
            ),
        )
    }

    @Test
    fun `build reads the origin from the runtime context rather than a separate argument`() {
        // A caller cannot evaluate the gate against one origin and build with another.
        assertTrue(
            CatGardenToolSurface
                .build(repository, context(callOrigin = ToolCallOrigin.Telegram), assistantEnabled = true)
                .isEmpty(),
        )
        assertEquals(
            SPACE_TOOL_NAMES,
            CatGardenToolSurface
                .build(repository, context(callOrigin = ToolCallOrigin.LocalChat), assistantEnabled = true)
                .map { it.name }
                .toSet(),
        )
        assertEquals(
            SPACE_TOOL_NAMES,
            CatGardenToolSurface
                .build(repository, context(callOrigin = ToolCallOrigin.TrustedWorkflow), assistantEnabled = true)
                .map { it.name }
                .toSet(),
        )
    }

    @Test
    fun `a blank assistant id is treated as no identity`() {
        assertTrue(SpaceActor.fromAssistant("") == null)
        assertTrue(SpaceActor.fromAssistant("   ") == null)
        assertTrue(SpaceActor.fromAssistant(null) == null)
        val resolved = SpaceActor.fromAssistant(assistantId)
        assertEquals(SpaceActorKind.ASSISTANT, resolved?.kind)
        assertEquals(assistantId, resolved?.id)
    }
}
