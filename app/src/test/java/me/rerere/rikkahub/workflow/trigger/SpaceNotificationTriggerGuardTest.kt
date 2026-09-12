package me.rerere.rikkahub.workflow.trigger

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.space.FakeSpaceDao
import me.rerere.rikkahub.space.SpaceNotificationEntity
import me.rerere.rikkahub.space.SpaceRepository
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two guards that make assistant-to-assistant recursion terminate.
 *
 * A and B both posting is a legitimate steady state; A and B waking each other forever is not.
 * Each guard is asserted on its own, because either one alone is insufficient: depth alone would
 * still let one notification fire a workflow twice, and exactly-once alone would let every hop
 * of an A -> B -> A cycle fire exactly once, forever.
 */
class SpaceNotificationTriggerGuardTest {

    private val dao = FakeSpaceDao()
    private val repository = SpaceRepository(dao, nowMs = { 1_000L }, newId = { "id" })
    private val fired = mutableListOf<String>()

    private val callback = TriggerFireCallback { workflowId, _ -> fired += workflowId }

    private fun family() = SpaceNotificationTriggerFamily(
        scope = CoroutineScope(Dispatchers.Unconfined),
        repositoryProvider = { repository },
    )

    private fun workflow(id: String, type: String? = null) = WorkflowDefinition(
        id = id,
        name = "wf-$id",
        trigger = TriggerSpec.SpaceNotificationCreated(noticeType = type),
        actions = emptyList(),
    )

    private fun notification(
        id: String,
        depth: Int,
        type: String = "LIKE",
    ) = SpaceNotificationEntity(
        notificationId = id,
        recipientKind = "ASSISTANT",
        recipientId = "assistant-a",
        actorKind = "ASSISTANT",
        actorId = "assistant-b",
        type = type,
        postId = "post-1",
        createdAtMs = 1_000L,
        originDepth = depth,
    )

    @Test
    fun `an assistant-originated notification never wakes a workflow`() = runBlocking {
        val f = family()
        f.sync(listOf(workflow("wf-1")), callback)

        // depth >= 1 means an automation run produced it, not the person. Acting on it is the
        // edge that would let A -> B -> A never stop.
        f.onSpaceNotificationCreated(notification("n-assistant", depth = 1))
        f.onSpaceNotificationCreated(notification("n-assistant-2", depth = 2))

        assertTrue("an assistant-originated notification must be inert", fired.isEmpty())
    }

    @Test
    fun `a user-originated notification wakes a matching workflow`() = runBlocking {
        dao.notifications["n-user"] = notification("n-user", depth = 0)
        val f = family()
        f.sync(listOf(workflow("wf-1")), callback)

        f.onSpaceNotificationCreated(notification("n-user", depth = 0))

        assertEquals(listOf("wf-1"), fired)
    }

    @Test
    fun `the same notification is consumed exactly once`() = runBlocking {
        dao.notifications["n-user"] = notification("n-user", depth = 0)
        val f = family()
        f.sync(listOf(workflow("wf-1")), callback)

        val event = notification("n-user", depth = 0)
        f.onSpaceNotificationCreated(event)
        f.onSpaceNotificationCreated(event)
        f.onSpaceNotificationCreated(event)

        // A redelivered event must not run the workflow again.
        assertEquals(listOf("wf-1"), fired)
    }

    @Test
    fun `a type filter that does not match is ignored`() = runBlocking {
        val f = family()
        f.sync(listOf(workflow("wf-like", type = "LIKE")), callback)

        f.onSpaceNotificationCreated(notification("n-comment", depth = 0, type = "COMMENT"))
        assertTrue(fired.isEmpty())

        dao.notifications["n-like"] = notification("n-like", depth = 0, type = "LIKE")
        f.onSpaceNotificationCreated(notification("n-like", depth = 0, type = "LIKE"))
        assertEquals(listOf("wf-like"), fired)
    }

    @Test
    fun `every matching workflow fires, and a non-matching trigger is skipped`() = runBlocking {
        dao.notifications["n-user"] = notification("n-user", depth = 0)
        val f = family()
        f.sync(
            listOf(
                workflow("wf-1"),
                workflow("wf-2"),
                WorkflowDefinition(
                    id = "wf-manual",
                    name = "manual",
                    trigger = TriggerSpec.Manual,
                    actions = emptyList(),
                ),
            ),
            callback,
        )

        f.onSpaceNotificationCreated(notification("n-user", depth = 0))

        assertEquals(listOf("wf-1", "wf-2"), fired)
    }

    @Test
    fun `nothing fires while the family holds no matching workflows`() = runBlocking {
        val f = family()
        f.sync(emptyList(), callback)
        f.onSpaceNotificationCreated(notification("n-user", depth = 0))
        assertTrue(fired.isEmpty())
    }
}
