package me.rerere.rikkahub.workflow.trigger

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.space.FakeSpaceDao
import me.rerere.rikkahub.space.SPACE_CREATE_POST_TOOL_NAME
import me.rerere.rikkahub.space.SPACE_SET_LIKE_TOOL_NAME
import me.rerere.rikkahub.space.SpaceActor
import me.rerere.rikkahub.space.SpaceCausalDepth
import me.rerere.rikkahub.space.SpaceRepository
import me.rerere.rikkahub.space.SpaceWriteOutcome
import me.rerere.rikkahub.space.createSpaceTools
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Cat Garden notification path, end to end, through the code that actually runs.
 *
 * Everything here is produced the way production produces it: posts and likes go through the real
 * [createSpaceTools] surface or the exact repository call the Cat Garden screen makes, the
 * notification row is written by the repository from that action, and it reaches the workflow only
 * via the real process-wide dispatcher and the real trigger family. Nothing in this file
 * constructs a `SpaceNotificationEntity` by hand — a hand-built row could assert any contract it
 * liked, including one no production path can emit, which is exactly the failure this suite exists
 * to rule out.
 *
 * The suite covers the three obligations that only hold together:
 *  - a person's action wakes the workflow of the assistant it was addressed to;
 *  - an automation's action wakes nothing, so A -> B -> A terminates;
 *  - re-delivery, and replay after a restart, fire at most once.
 */
class SpaceNotificationEndToEndTest {

    private val assistantA = "aaaaaaaa-0000-0000-0000-000000000001"
    private val assistantB = "bbbbbbbb-0000-0000-0000-000000000002"

    private val dao = FakeSpaceDao()
    private var clock = 1_000L
    private var idSeq = 0
    private val repository = SpaceRepository(
        dao = dao,
        nowMs = { clock++ },
        newId = { "id-${++idSeq}" },
    )

    private val fired = mutableListOf<String>()
    private val callback = TriggerFireCallback { workflowId, _ -> fired += workflowId }

    private fun family() = SpaceNotificationTriggerFamily(
        scope = CoroutineScope(Dispatchers.Unconfined),
        repositoryProvider = { repository },
        nowMs = { clock },
    )

    private fun workflow(id: String, assistantId: String) = WorkflowDefinition(
        id = id,
        name = "wf-$id",
        trigger = TriggerSpec.SpaceNotificationCreated(),
        actions = emptyList(),
        authoringAssistantId = assistantId,
    )

    /**
     * The real Cat Garden tool surface for one assistant, exactly as the chat builder and the
     * workflow runner obtain it.
     */
    private fun toolsFor(assistantId: String, headless: Boolean): List<Tool> = createSpaceTools(
        repository = repository,
        invocationContext = ToolInvocationContext(
            callerAssistantId = assistantId,
            callOrigin = if (headless) ToolCallOrigin.TrustedWorkflow else ToolCallOrigin.LocalChat,
            isHeadless = headless,
        ),
    )

    /**
     * Calls a tool with the same JSON types a model would produce. [JsonPrimitive] values are built
     * explicitly rather than coerced from strings so a boolean argument really is a JSON boolean —
     * the tools read these with `booleanOrNull`/`intOrNull`, which are not interchangeable.
     */
    private suspend fun Tool.call(vararg args: Pair<String, JsonElement>): String {
        val input = buildJsonObject { args.forEach { (k, v) -> put(k, v) } }
        return execute(input).single().let { (it as UIMessagePart.Text).text }
    }

    private fun postIdOf(json: String): String =
        Json.parseToJsonElement(json).jsonObject.getValue("post_id").jsonPrimitive.content

    /** Publishes a post as [assistantId] through the real tool, returning its id. */
    private suspend fun postAs(assistantId: String, content: String): String = postIdOf(
        toolsFor(assistantId, headless = false)
            .single { it.name == SPACE_CREATE_POST_TOOL_NAME }
            .call("content" to JsonPrimitive(content)),
    )

    /**
     * What tapping the heart on the Cat Garden screen does: `CatGardenVM.toggleLike` is a coroutine
     * wrapper around exactly this call, with the viewer pinned to the local user.
     */
    private suspend fun userLikes(postId: String) {
        repository.setLike(
            actor = SpaceActor.USER,
            postId = postId,
            liked = true,
            originDepth = SpaceCausalDepth.USER_INITIATED,
        )
    }

    /** How a woken workflow acts: a headless run's tool surface. */
    private suspend fun workflowLikes(asAssistant: String, postId: String) {
        toolsFor(asAssistant, headless = true)
            .single { it.name == SPACE_SET_LIKE_TOOL_NAME }
            .call("post_id" to JsonPrimitive(postId), "liked" to JsonPrimitive(true))
    }

    private suspend fun <T> withFamily(block: suspend (SpaceNotificationTriggerFamily) -> T): T {
        val family = family()
        try {
            return block(family)
        } finally {
            family.shutdown()
        }
    }

    // ── The live path ────────────────────────────────────────────────────────────────────────

    @Test
    fun `a person's action wakes the addressed assistant's workflow`() = runBlocking {
        val post = postAs(assistantA, "hello from A")

        withFamily { family ->
            family.sync(listOf(workflow("wf-a", assistantA)), callback)
            userLikes(post)
        }

        assertEquals(listOf("wf-a"), fired)
        // The contract the guard reads: the person's own action produces depth 0.
        val notification = dao.notifications.values.single()
        assertEquals(SpaceCausalDepth.USER_INITIATED, notification.originDepth)
        assertEquals(assistantA, notification.recipientId)
    }

    @Test
    fun `an automation's action does not wake the next workflow`() = runBlocking {
        val postOfA = postAs(assistantA, "A's post")
        val postOfB = postAs(assistantB, "B's post")

        withFamily { family ->
            family.sync(
                listOf(workflow("wf-a", assistantA), workflow("wf-b", assistantB)),
                callback,
            )

            // The person likes A's post: this is the only thing that may wake anything.
            userLikes(postOfA)
            assertEquals("only A's workflow is awake", listOf("wf-a"), fired)

            // Now A's woken run acts on B's post, which notifies B. If this woke B's workflow, B
            // could reply to A and the cycle would never end.
            workflowLikes(asAssistant = assistantA, postId = postOfB)
        }

        assertEquals("an automation-originated notification must not continue the chain", listOf("wf-a"), fired)
        val automationNotification = dao.notifications.values.single { it.recipientId == assistantB }
        assertEquals(SpaceCausalDepth.AUTOMATION_DRIVEN, automationNotification.originDepth)
    }

    @Test
    fun `redelivering the same event fires at most once`() = runBlocking {
        val post = postAs(assistantA, "hello from A")

        withFamily { family ->
            family.sync(listOf(workflow("wf-a", assistantA)), callback)
            userLikes(post)

            val notification = dao.notifications.values.single()
            repeat(3) { family.onSpaceNotificationCreated(notification) }
        }

        assertEquals(listOf("wf-a"), fired)
    }

    // ── Restart / rebind replay ──────────────────────────────────────────────────────────────

    @Test
    fun `a notification that arrived while nothing was listening is replayed on bind`() =
        runBlocking {
            val post = postAs(assistantA, "hello from A")
            // No family is bound: this is the app being dead, or the trigger registry not yet
            // built. The dispatcher hands the event to nobody.
            userLikes(post)
            assertTrue("nothing should have fired yet", fired.isEmpty())

            withFamily { family ->
                family.sync(listOf(workflow("wf-a", assistantA)), callback)
            }

            assertEquals("the durable row must survive the missed signal", listOf("wf-a"), fired)
        }

    @Test
    fun `a replayed notification is not replayed again on the next bind`() = runBlocking {
        val post = postAs(assistantA, "hello from A")
        userLikes(post)

        withFamily { it.sync(listOf(workflow("wf-a", assistantA)), callback) }
        assertEquals(listOf("wf-a"), fired)

        // A second start-up — a workflow edit, or another launch — must not re-run it.
        withFamily { it.sync(listOf(workflow("wf-a", assistantA)), callback) }
        assertEquals(listOf("wf-a"), fired)
    }

    @Test
    fun `replay respects recipient ownership and leaves other assistants' mail pending`() =
        runBlocking {
            val post = postAs(assistantA, "hello from A")
            userLikes(post)

            // Only B has an armed workflow, so A's mail has no consumer yet. It must not be claimed
            // — claiming it would record a delivery that never happened, and B would then be able
            // to act on A's inbox.
            withFamily { it.sync(listOf(workflow("wf-b", assistantB)), callback) }
            assertTrue(fired.isEmpty())
            assertNull(dao.notifications.values.single().consumedAtMs)

            // Once A's own workflow is armed, the row is still there to be delivered.
            withFamily { it.sync(listOf(workflow("wf-a", assistantA)), callback) }
            assertEquals(listOf("wf-a"), fired)
        }

    @Test
    fun `replay does not resurrect an automation-originated notification`() = runBlocking {
        val postOfA = postAs(assistantA, "A's post")
        val postOfB = postAs(assistantB, "B's post")
        workflowLikes(asAssistant = assistantA, postId = postOfB)

        // The depth guard applies to the replay path too — a restart must not be a loophole that
        // lets an automaton wake its own chain.
        withFamily { it.sync(listOf(workflow("wf-b", assistantB)), callback) }

        assertTrue(fired.isEmpty())
        assertTrue(dao.notifications.isNotEmpty())
        assertTrue(
            "a refused notification is left unconsumed rather than claimed",
            dao.notifications.values.all { it.consumedAtMs == null },
        )
    }

    // ── Depth contract, asserted where it is produced ────────────────────────────────────────

    @Test
    fun `the tool surface stamps headless writes one hop from the person`() = runBlocking {
        postAs(assistantA, "from a person")
        toolsFor(assistantA, headless = true)
            .single { it.name == SPACE_CREATE_POST_TOOL_NAME }
            .call("content" to "from an automation")

        val depths = dao.posts.values.associate { it.content to it.originDepth }
        assertEquals(SpaceCausalDepth.USER_INITIATED, depths.getValue("from a person"))
        assertEquals(SpaceCausalDepth.AUTOMATION_DRIVEN, depths.getValue("from an automation"))
    }

    @Test
    fun `a comment notification carries the comment's own depth`() = runBlocking {
        val post = postAs(assistantA, "hello from A")

        repository.createComment(
            actor = SpaceActor.USER,
            postId = post,
            content = "from the person",
            originDepth = SpaceCausalDepth.USER_INITIATED,
        )

        val notification = dao.notifications.values.single()
        assertEquals(SpaceCausalDepth.USER_INITIATED, notification.originDepth)
        assertEquals(post, notification.postId)
    }

    @Test
    fun `deleting a post removes the notifications that pointed at it`() = runBlocking {
        val post = postAs(assistantA, "hello from A")
        userLikes(post)
        assertEquals(1, dao.notifications.size)

        val outcome = repository.deletePost(
            SpaceActor.fromAssistant(assistantA) ?: error("identity"),
            post,
        )

        assertTrue(outcome is SpaceWriteOutcome.Created)
        assertEquals(0, dao.notifications.size)
        // The cascade is what keeps the trigger from ever seeing a notification for a dead post.
        assertTrue(dao.posts.isEmpty() && dao.likes.isEmpty())
    }
}
