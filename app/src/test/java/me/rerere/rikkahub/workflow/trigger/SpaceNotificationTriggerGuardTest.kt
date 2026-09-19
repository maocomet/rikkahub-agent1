package me.rerere.rikkahub.workflow.trigger

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.space.FakeSpaceDao
import me.rerere.rikkahub.space.SpaceActorKind
import me.rerere.rikkahub.space.SpaceCausalDepth
import me.rerere.rikkahub.space.SpaceNotificationEntity
import me.rerere.rikkahub.space.SpaceRepository
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guards that make assistant-to-assistant recursion terminate, and keep one assistant's mail
 * out of another assistant's workflows.
 *
 * A and B both posting is a legitimate steady state; A and B waking each other forever is not. Each
 * guard is asserted on its own, because none of them is sufficient alone: depth alone would still
 * let one notification fire a workflow twice, exactly-once alone would let every hop of an
 * A -> B -> A cycle fire exactly once forever, and either of those would leave B's and C's
 * workflows racing for a notification addressed to A.
 *
 * The depth values here are the ones the PRODUCTION paths produce — see
 * [SpaceNotificationEndToEndTest], which drives the same guards through the real repository and
 * tool surface rather than constructing entities by hand.
 */
class SpaceNotificationTriggerGuardTest {

    private val dao = FakeSpaceDao()
    private val repository = SpaceRepository(dao, nowMs = { 1_000L }, newId = { "id" })
    private val fired = mutableListOf<String>()

    private val callback = TriggerFireCallback { workflowId, _ -> fired += workflowId }

    private var bound: SpaceNotificationTriggerFamily? = null

    private fun family() = SpaceNotificationTriggerFamily(
        scope = CoroutineScope(Dispatchers.Unconfined),
        repositoryProvider = { repository },
        nowMs = { 1_000L },
    ).also { bound = it }

    /**
     * The dispatcher is a process-wide singleton, so a family left bound by one test would receive
     * another test's notifications. Unbinding is not optional hygiene here — it is what keeps the
     * guard assertions about THIS family meaningful.
     */
    @After
    fun unbind() = runBlocking {
        bound?.shutdown()
        bound = null
    }

    private fun workflow(
        id: String,
        type: String? = null,
        authoringAssistantId: String? = "assistant-a",
    ) = WorkflowDefinition(
        id = id,
        name = "wf-$id",
        trigger = TriggerSpec.SpaceNotificationCreated(noticeType = type),
        actions = emptyList(),
        authoringAssistantId = authoringAssistantId,
    )

    private fun notification(
        id: String,
        depth: Int,
        type: String = "LIKE",
        recipientKind: String = SpaceActorKind.ASSISTANT.name,
        recipientId: String = "assistant-a",
    ) = SpaceNotificationEntity(
        notificationId = id,
        recipientKind = recipientKind,
        recipientId = recipientId,
        actorKind = SpaceActorKind.ASSISTANT.name,
        actorId = "assistant-b",
        type = type,
        postId = "post-1",
        createdAtMs = 1_000L,
        originDepth = depth,
    )

    /** Puts a row in the store and returns the event a live dispatcher would hand over. */
    private suspend fun deliver(entity: SpaceNotificationEntity): SpaceNotificationEntity {
        dao.notifications[entity.notificationId] = entity
        return entity
    }

    // ── Guard 1: causal depth ────────────────────────────────────────────────────────────────

    @Test
    fun `an automation-originated notification never wakes a workflow`() = runBlocking {
        val f = family()
        f.sync(listOf(workflow("wf-1")), callback)

        // Depth >= 1 means an automation run produced it, not the person. Acting on it is the
        // edge that would let A -> B -> A never stop.
        f.onSpaceNotificationCreated(
            deliver(notification("n-assistant", SpaceCausalDepth.AUTOMATION_DRIVEN)),
        )
        f.onSpaceNotificationCreated(deliver(notification("n-assistant-2", depth = 2)))

        assertTrue("an automation-originated notification must be inert", fired.isEmpty())
    }

    @Test
    fun `an out-of-contract negative depth never wakes a workflow`() = runBlocking {
        val f = family()
        f.sync(listOf(workflow("wf-1")), callback)

        // A negative depth cannot come from the repository — it refuses one — so this row could only
        // exist if something bypassed that. It is here because the consumer guard is the last line:
        // `depth <= 0` would have admitted it as "shallower than the person" and woken the workflow.
        f.onSpaceNotificationCreated(deliver(notification("n-negative", depth = -1)))
        f.onSpaceNotificationCreated(deliver(notification("n-min", depth = Int.MIN_VALUE)))

        assertTrue("a negative depth is out of contract, not user-originated", fired.isEmpty())
    }

    @Test
    fun `a user-originated notification wakes a matching workflow`() = runBlocking {
        val f = family()
        f.sync(listOf(workflow("wf-1")), callback)

        f.onSpaceNotificationCreated(
            deliver(notification("n-user", SpaceCausalDepth.USER_INITIATED)),
        )

        assertEquals(listOf("wf-1"), fired)
    }

    @Test
    fun `the same notification is consumed exactly once`() = runBlocking {
        val f = family()
        f.sync(listOf(workflow("wf-1")), callback)

        val event = deliver(notification("n-user", SpaceCausalDepth.USER_INITIATED))
        f.onSpaceNotificationCreated(event)
        f.onSpaceNotificationCreated(event)
        f.onSpaceNotificationCreated(event)

        // A redelivered event must not run the workflow again.
        assertEquals(listOf("wf-1"), fired)
    }

    // ── Guard 2: recipient ownership ─────────────────────────────────────────────────────────

    @Test
    fun `another assistant's workflow is not woken by this assistant's mail`() = runBlocking {
        val f = family()
        f.sync(
            listOf(
                workflow("wf-a", authoringAssistantId = "assistant-a"),
                workflow("wf-b", authoringAssistantId = "assistant-b"),
                workflow("wf-c", authoringAssistantId = "assistant-c"),
            ),
            callback,
        )

        // The notification is addressed to A. B and C share the trigger TYPE, which is exactly why
        // trigger type alone cannot be the mailbox.
        f.onSpaceNotificationCreated(
            deliver(notification("n-a", SpaceCausalDepth.USER_INITIATED, recipientId = "assistant-a")),
        )

        assertEquals(listOf("wf-a"), fired)
    }

    @Test
    fun `a user-addressed notification never wakes an assistant workflow`() = runBlocking {
        val f = family()
        f.sync(listOf(workflow("wf-a", authoringAssistantId = "assistant-a")), callback)

        f.onSpaceNotificationCreated(
            deliver(
                notification(
                    "n-user-inbox",
                    SpaceCausalDepth.USER_INITIATED,
                    recipientKind = SpaceActorKind.USER.name,
                    recipientId = "local_user",
                ),
            ),
        )

        assertTrue("the person's inbox is not an assistant's trigger source", fired.isEmpty())
    }

    @Test
    fun `a recipient that is not a known actor kind fails closed`() = runBlocking {
        val f = family()
        f.sync(listOf(workflow("wf-a")), callback)

        f.onSpaceNotificationCreated(
            deliver(
                notification(
                    "n-garbage",
                    SpaceCausalDepth.USER_INITIATED,
                    recipientKind = "TEAM",
                    recipientId = "assistant-a",
                ),
            ),
        )

        assertTrue(fired.isEmpty())
    }

    @Test
    fun `a legacy workflow with no authoring assistant fails closed rather than guessing`() =
        runBlocking {
            val f = family()
            f.sync(
                listOf(
                    workflow("wf-legacy", authoringAssistantId = null),
                    workflow("wf-blank", authoringAssistantId = "   "),
                ),
                callback,
            )

            f.onSpaceNotificationCreated(
                deliver(notification("n-a", SpaceCausalDepth.USER_INITIATED)),
            )

            // A workflow with no recorded inbox has no inbox. Firing it would hand it mail that was
            // addressed to somebody else.
            assertTrue(fired.isEmpty())
        }

    // ── Filters and fan-out ──────────────────────────────────────────────────────────────────

    @Test
    fun `a type filter that does not match is ignored`() = runBlocking {
        val f = family()
        f.sync(listOf(workflow("wf-like", type = "LIKE")), callback)

        f.onSpaceNotificationCreated(
            deliver(notification("n-comment", SpaceCausalDepth.USER_INITIATED, type = "COMMENT")),
        )
        assertTrue(fired.isEmpty())

        f.onSpaceNotificationCreated(
            deliver(notification("n-like", SpaceCausalDepth.USER_INITIATED, type = "LIKE")),
        )
        assertEquals(listOf("wf-like"), fired)
    }

    @Test
    fun `every workflow of the addressed assistant fires, and the others are skipped`() =
        runBlocking {
            val f = family()
            f.sync(
                listOf(
                    workflow("wf-1"),
                    workflow("wf-2"),
                    workflow("wf-elsewhere", authoringAssistantId = "assistant-z"),
                    WorkflowDefinition(
                        id = "wf-manual",
                        name = "manual",
                        trigger = TriggerSpec.Manual,
                        actions = emptyList(),
                        authoringAssistantId = "assistant-a",
                    ),
                ),
                callback,
            )

            f.onSpaceNotificationCreated(
                deliver(notification("n-user", SpaceCausalDepth.USER_INITIATED)),
            )

            assertEquals(listOf("wf-1", "wf-2"), fired)
        }

    @Test
    fun `nothing fires while the family holds no matching workflows`() = runBlocking {
        val f = family()
        f.sync(emptyList(), callback)
        f.onSpaceNotificationCreated(
            deliver(notification("n-user", SpaceCausalDepth.USER_INITIATED)),
        )
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `nothing fires while the family is unbound`() = runBlocking {
        val f = family()
        f.onSpaceNotificationCreated(deliver(notification("n-user", 0)))
        assertTrue("an unbound family has no callback to fire", fired.isEmpty())
    }
}
