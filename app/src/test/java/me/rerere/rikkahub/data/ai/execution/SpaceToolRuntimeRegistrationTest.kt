package me.rerere.rikkahub.data.ai.execution

import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext
import me.rerere.rikkahub.space.SPACE_TOOL_NAMES
import me.rerere.rikkahub.space.SPACE_DELETE_POST_TOOL_NAME
import me.rerere.rikkahub.space.SPACE_LIST_COMMENTS_TOOL_NAME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every Cat Garden tool must be runnable, not merely visible.
 *
 * This exists because of a real defect: `space_delete_post` was in `SPACE_TOOL_NAMES` — so the model
 * was sent its schema and could call it — but absent from [InternalToolSecurityCatalog], so
 * `DefaultToolRuntime.assess` found no descriptor and rejected every call with
 * `tool_security_descriptor_missing` before the tool body ran. Publishing worked, deleting never
 * did. `space_list_comments` had the same gap.
 *
 * No tool-level test could see it: those invoke `Tool.execute` directly, which is the one path that
 * skips the runtime entirely. What makes the defect visible is asking the RUNTIME's own resolvers,
 * which is what this does.
 */
class SpaceToolRuntimeRegistrationTest {

    private val descriptorResolver = DefaultToolSecurityDescriptorResolver()
    private val policyResolver = DefaultToolExecutionPolicyResolver()

    private fun context() = ToolExecutionContext(
        runId = Uuid.random(),
        conversationId = Uuid.random(),
        assistantId = "aaaaaaaa-0000-0000-0000-000000000001",
        callOrigin = ToolCallOrigin.LocalChat,
    )

    @Test
    fun `every Cat Garden tool has a runtime security descriptor`() {
        SPACE_TOOL_NAMES.forEach { name ->
            assertNotNull(
                "$name is offered to the model but has no runtime descriptor, so every call is " +
                    "rejected as tool_security_descriptor_missing before it executes",
                descriptorResolver.resolve(name, context()),
            )
        }
    }

    @Test
    fun `every Cat Garden tool is classified in exactly one catalog set`() {
        SPACE_TOOL_NAMES.forEach { name ->
            val readOnly = name in InternalToolSecurityCatalog.READ_ONLY
            val mutating = name in InternalToolSecurityCatalog.MUTATING
            assertTrue("$name is in neither READ_ONLY nor MUTATING", readOnly || mutating)
            assertTrue("$name must not be classified as both", !(readOnly && mutating))
            assertTrue("$name must not depend on its arguments", name !in InternalToolSecurityCatalog.ARGUMENT_DEPENDENT)
        }
    }

    @Test
    fun `no Cat Garden tool falls through to an unknown policy`() {
        SPACE_TOOL_NAMES.forEach { name ->
            val policy = policyResolver.resolve(name, JsonObject(emptyMap()), context())
            assertTrue(
                "$name resolves to an UNKNOWN effect set",
                ToolEffect.UNKNOWN !in policy.effects,
            )
        }
    }

    @Test
    fun `deleting a post is a serial persistent-state write`() {
        val policy = policyResolver.resolve(
            SPACE_DELETE_POST_TOOL_NAME,
            JsonObject(emptyMap()),
            context(),
        )

        // The same bucket as its sibling writes: it mutates durable, other-visible state, so it is
        // serialised rather than batched alongside reads.
        assertEquals(setOf(ToolEffect.PERSISTENT_STATE), policy.effects)
        assertEquals(ToolConcurrency.GLOBAL_SERIAL, policy.concurrency)
        assertEquals(
            ToolDescriptorSource.INTERNAL,
            descriptorResolver.resolve(SPACE_DELETE_POST_TOOL_NAME, context())?.source,
        )
    }

    @Test
    fun `reading a comment thread is a parallel-safe read`() {
        val policy = policyResolver.resolve(
            SPACE_LIST_COMMENTS_TOOL_NAME,
            JsonObject(emptyMap()),
            context(),
        )

        // Like every other Cat Garden read: it takes no lock and can be batched with its siblings.
        assertEquals(setOf(ToolEffect.LOCAL_READ), policy.effects)
        assertEquals(ToolConcurrency.PARALLEL_SAFE, policy.concurrency)
    }
}
