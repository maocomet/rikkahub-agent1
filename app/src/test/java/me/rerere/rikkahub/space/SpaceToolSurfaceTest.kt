package me.rerere.rikkahub.space

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.long
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        assertEquals(9, built.size)
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
    fun `the delete tool accepts only a post id and refuses another assistant's post`() = runBlocking {
        val otherId = "bbbbbbbb-0000-0000-0000-000000000002"
        val foreignPost = (repository.createPost(
            SpaceActor(SpaceActorKind.ASSISTANT, otherId),
            "not yours",
            0,
        ) as SpaceWriteOutcome.Created).id

        val tool = toolsFor(context()).single { it.name == SPACE_DELETE_POST_TOOL_NAME }
        assertEquals(
            setOf("post_id"),
            (tool.parameters() as InputSchema.Obj).properties.keys,
        )

        // The model supplies identity-looking arguments; they must be ignored, not honoured, and
        // the write must still be refused because the runtime identity does not own the post.
        val payload = tool.execute(
            buildJsonObject {
                put("post_id", foreignPost)
                put("author_id", otherId)
                put("actor_id", otherId)
            },
        ).single().let { it as UIMessagePart.Text }.text

        val json = Json.parseToJsonElement(payload).jsonObject
        assertFalse(json.getValue("ok").jsonPrimitive.booleanOrNull ?: true)
        assertEquals("NOT_POST_OWNER", json.getValue("code").jsonPrimitive.content)
        assertNotNull(repository.getPost(foreignPost))
    }

    @Test
    fun `the delete tool removes the caller's own post`() = runBlocking {
        val mine = (repository.createPost(
            SpaceActor(SpaceActorKind.ASSISTANT, assistantId),
            "mine to delete",
            0,
        ) as SpaceWriteOutcome.Created).id

        val payload = toolsFor(context())
            .single { it.name == SPACE_DELETE_POST_TOOL_NAME }
            .execute(buildJsonObject { put("post_id", mine) })
            .single().let { it as UIMessagePart.Text }.text

        val json = Json.parseToJsonElement(payload).jsonObject
        assertTrue(json.getValue("ok").jsonPrimitive.booleanOrNull ?: false)
        assertNull(repository.getPost(mine))
    }

    @Test
    fun `a run with no trusted identity cannot delete anything`() = runBlocking {
        val mine = (repository.createPost(
            SpaceActor(SpaceActorKind.ASSISTANT, assistantId),
            "keep me",
            0,
        ) as SpaceWriteOutcome.Created).id

        val payload = toolsFor(context(callerAssistantId = null))
            .single { it.name == SPACE_DELETE_POST_TOOL_NAME }
            .execute(buildJsonObject { put("post_id", mine) })
            .single().let { it as UIMessagePart.Text }.text

        assertEquals(
            "SPACE_IDENTITY_UNAVAILABLE",
            Json.parseToJsonElement(payload).jsonObject.getValue("code").jsonPrimitive.content,
        )
        assertNotNull(repository.getPost(mine))
    }

    @Test
    fun `a full comment thread is reachable a page at a time`() = runBlocking {
        val postId = (repository.createPost(
            SpaceActor(SpaceActorKind.ASSISTANT, assistantId),
            "many comments",
            0,
        ) as SpaceWriteOutcome.Created).id
        repeat(5) { repository.createComment(SpaceActor.USER, postId, "c$it", 0) }

        val getPost = toolsFor(context()).single { it.name == SPACE_GET_POST_TOOL_NAME }
        val first = Json.parseToJsonElement(
            getPost.execute(buildJsonObject { put("post_id", postId) })
                .single().let { it as UIMessagePart.Text }.text,
        ).jsonObject

        // The reader must be told the truth about the thread's size, not handed a truncated list
        // that looks complete.
        assertEquals(5, first.getValue("comment_count").jsonPrimitive.int)
        assertEquals(5, first.getValue("comments").jsonArray.size)
        assertFalse(first.getValue("comments_has_more").jsonPrimitive.booleanOrNull ?: true)

        // A JSON integer, as a model would emit for an "integer" schema property — not the string
        // "2", which the tool's intOrNull read is not obliged to coerce.
        val page = commentPage(postId, limit = 2)

        assertEquals(2, page.count())
        assertEquals(5, page.getValue("comment_count").jsonPrimitive.int)
        assertTrue(page.hasMore())

        // And the cursor it hands back is the one the next page actually needs.
        val next = nextPage(postId, 20, page)
        assertEquals(listOf("c2", "c3", "c4"), next.contents())
        // Checking only the second page's CONTENT is what let the has_more bug through: three
        // comments against a page size of twenty is a complete read, and must say so.
        assertFalse("the rest of a five-comment thread is not 'more'", next.hasMore())
    }

    /** Runs `space_list_comments`, optionally resuming from an explicit cursor. */
    private suspend fun commentPage(
        postId: String,
        limit: Int? = null,
        afterCreatedAtMs: Long? = null,
        afterId: String? = null,
    ) = Json.parseToJsonElement(
        toolsFor(context())
            .single { it.name == SPACE_LIST_COMMENTS_TOOL_NAME }
            .execute(
                buildJsonObject {
                    put("post_id", postId)
                    limit?.let { put("limit", it) }
                    afterCreatedAtMs?.let { put("after_created_at_ms", it) }
                    afterId?.let { put("after_id", it) }
                },
            )
            .single().let { it as UIMessagePart.Text }.text,
    ).jsonObject

    private fun JsonObject.contents(): List<String> =
        getValue("comments").jsonArray.map { it.jsonObject.getValue("content").jsonPrimitive.content }

    private fun JsonObject.count(): Int = getValue("count").jsonPrimitive.int

    private fun JsonObject.hasMore(): Boolean = getValue("has_more").jsonPrimitive.booleanOrNull ?: false

    /** Resumes the walk from this page's own cursor. */
    private suspend fun nextPage(postId: String, limit: Int, from: JsonObject): JsonObject {
        assertTrue("cannot continue without a cursor", from.containsKey("next_after_id"))
        return commentPage(
            postId = postId,
            limit = limit,
            afterCreatedAtMs = from.getValue("next_after_created_at_ms").jsonPrimitive.long,
            afterId = from.getValue("next_after_id").jsonPrimitive.content,
        )
    }

    @Test
    fun `has_more is false on the last page of a forty-comment thread`() = runBlocking {
        val postId = postWithComments(40)

        val page1 = commentPage(postId, limit = 20)
        assertEquals(20, page1.count())
        assertTrue("page 1 of 40 has more", page1.hasMore())

        val page2 = nextPage(postId, 20, page1)
        assertEquals(20, page2.count())
        // The bug this pins: the old rule compared the page against the whole-post count, so it
        // still claimed more here and told the caller to fetch a page that does not exist.
        assertFalse("page 2 of 40 is the last page", page2.hasMore())
        assertFalse("and it must not offer a cursor", page2.containsKey("next_after_id"))
        assertEquals(40, page2.getValue("comment_count").jsonPrimitive.int)
    }

    @Test
    fun `has_more steps true true false across a forty-one-comment thread`() = runBlocking {
        val postId = postWithComments(41)

        val page1 = commentPage(postId, limit = 20)
        val page2 = nextPage(postId, 20, page1)
        val page3 = nextPage(postId, 20, page2)

        assertEquals(listOf(20, 20, 1), listOf(page1, page2, page3).map { it.count() })
        assertEquals(listOf(true, true, false), listOf(page1, page2, page3).map { it.hasMore() })
        // The last page has nowhere to go, so it must not hand back a cursor.
        assertFalse(page3.containsKey("next_after_id"))

        val walked = listOf(page1, page2, page3).flatMap { it.contents() }
        assertEquals(41, walked.size)
        assertEquals("no comment may repeat across a page boundary", 41, walked.toSet().size)
        assertEquals("c0", walked.first())
        assertEquals("c40", walked.last())
    }

    @Test
    fun `space_get_post says fifty comments are not the whole thread`() = runBlocking {
        val postId = postWithComments(51)

        val json = Json.parseToJsonElement(
            toolsFor(context())
                .single { it.name == SPACE_GET_POST_TOOL_NAME }
                .execute(buildJsonObject { put("post_id", postId) })
                .single().let { it as UIMessagePart.Text }.text,
        ).jsonObject

        assertEquals(50, json.getValue("comments").jsonArray.size)
        assertEquals("the exact total, not the page length", 51, json.getValue("comment_count").jsonPrimitive.int)
        assertTrue(
            "a caller must be told the page is not the whole thread",
            json.getValue("comments_has_more").jsonPrimitive.booleanOrNull ?: false,
        )

        // And handed the cursor that actually reads the rest — under this tool's own key names.
        val rest = commentPage(
            postId = postId,
            limit = 20,
            afterCreatedAtMs = json.getValue("comments_next_after_created_at_ms").jsonPrimitive.long,
            afterId = json.getValue("comments_next_after_id").jsonPrimitive.content,
        )
        assertEquals(listOf("c50"), rest.contents())
        assertFalse(rest.hasMore())
    }

    @Test
    fun `space_get_post reports no more when the whole thread fits in one page`() = runBlocking {
        val postId = postWithComments(3)
        val json = Json.parseToJsonElement(
            toolsFor(context())
                .single { it.name == SPACE_GET_POST_TOOL_NAME }
                .execute(buildJsonObject { put("post_id", postId) })
                .single().let { it as UIMessagePart.Text }.text,
        ).jsonObject

        assertEquals(3, json.getValue("comments").jsonArray.size)
        assertEquals(3, json.getValue("comment_count").jsonPrimitive.int)
        assertFalse(json.getValue("comments_has_more").jsonPrimitive.booleanOrNull ?: true)
        assertFalse(json.containsKey("comments_next_after_id"))
    }

    @Test
    fun `a thread that ends just short of a page reports no more`() = runBlocking {
        val postId = postWithComments(19)
        val page = commentPage(postId, limit = 20)
        assertEquals(19, page.count())
        assertFalse(page.hasMore())
        assertFalse("no cursor when there is no next page", page.containsKey("next_after_id"))
    }

    @Test
    fun `same-millisecond comments page without repeating or skipping through the tool`() = runBlocking {
        // The repository clock is frozen, so every comment shares a timestamp and only the cursor's
        // id half keeps the pages disjoint.
        val postId = postWithComments(45)

        val seen = mutableListOf<String>()
        var page = commentPage(postId, limit = 10)
        var pages = 1
        seen += page.contents()
        while (page.hasMore()) {
            page = nextPage(postId, 10, page)
            seen += page.contents()
            pages++
            check(pages <= 10) { "comment pagination did not terminate" }
        }

        assertEquals(5, pages)
        assertEquals(45, seen.size)
        assertEquals(45, seen.toSet().size)
    }

    private suspend fun postWithComments(count: Int): String {
        val postId = (repository.createPost(
            SpaceActor(SpaceActorKind.ASSISTANT, assistantId),
            "a thread of $count",
            0,
        ) as SpaceWriteOutcome.Created).id
        repeat(count) { repository.createComment(SpaceActor.USER, postId, "c$it", 0) }
        return postId
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
