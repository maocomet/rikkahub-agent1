package me.rerere.rikkahub.data.claudep

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.claudep.ClaudePSessionContinuation
import me.rerere.ai.provider.claudep.ClaudePSessionContinuationResolution
import me.rerere.ai.provider.claudep.ClaudePSessionContinuationState as State
import me.rerere.ai.provider.claudep.ClaudePSessionContinuationTransitions.Conflict
import me.rerere.ai.provider.claudep.ClaudePSessionContinuationTransitions.Refusal
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading one branch's continuation state out of the authoritative message graph, and the codec
 * that has to keep doing so across an upgrade.
 *
 * The graph walk is where the design's four rules either hold or do not: only the selected
 * variant is read, only an exact `(assistantId, branchId)` match counts, a missing record is
 * never answered from anywhere else, and a record that cannot be used refuses the resolution
 * rather than being skipped.
 */
class ClaudePSessionContinuationResolverTest {

    private val branch = "a".repeat(64)
    private val otherBranch = "b".repeat(64)
    private val assistant = "00000000-0000-0000-0000-0000000000aa"
    private val otherAssistant = "00000000-0000-0000-0000-0000000000bb"

    private fun uid(prefix: Int, n: Int) =
        kotlin.uuid.Uuid.parse("%08d-0000-0000-0000-%012d".format(prefix, n))

    private fun message(
        n: Int,
        role: MessageRole = MessageRole.ASSISTANT,
        continuation: ClaudePSessionContinuation? = null,
    ) = UIMessage(
        id = uid(1, n),
        role = role,
        parts = listOf(UIMessagePart.Text("m$n")),
        claudePSessionContinuation = continuation,
    )

    private fun node(n: Int, messages: List<UIMessage>, selectIndex: Int = 0) = MessageNode(
        id = uid(2, n),
        messages = messages,
        selectIndex = selectIndex,
    )

    private fun record(
        state: State?,
        revision: Long,
        generationId: String? = null,
        branchId: String = branch,
        assistantId: String = assistant,
    ) = ClaudePSessionContinuation(
        assistantId = assistantId,
        branchId = branchId,
        revision = revision,
        state = state,
        generationId = generationId,
    )

    private fun conversation(nodes: List<MessageNode>) = Conversation(
        id = uid(3, 1),
        assistantId = kotlin.uuid.Uuid.parse(assistant),
        messageNodes = nodes,
    )

    private fun resolve(nodes: List<MessageNode>): ClaudePSessionContinuationResolution =
        ClaudePSessionContinuationResolver.resolve(nodes, assistant, branch)

    private fun resolved(nodes: List<MessageNode>): ClaudePSessionContinuationResolution.Resolved {
        val result = resolve(nodes)
        assertTrue("expected a resolved state, got $result", result is ClaudePSessionContinuationResolution.Resolved)
        return result as ClaudePSessionContinuationResolution.Resolved
    }

    // ---------------------------------------------------------------------------------------
    // The walk
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a graph with no record at all is uninitialized`() {
        assertEquals(
            ClaudePSessionContinuationResolution.UNINITIALIZED,
            resolve(listOf(node(1, listOf(message(1))), node(2, listOf(message(2))))),
        )
    }

    /**
     * A linear turn writes its start on the user message and its terminal on the assistant one,
     * and both are read — the state is the fold of the branch's history, not the last message.
     */
    @Test
    fun `the start and the terminal of one turn fold together`() {
        val nodes = listOf(
            node(1, listOf(message(1, MessageRole.USER, record(State.START_IN_FLIGHT, 1)))),
            node(2, listOf(message(2, MessageRole.ASSISTANT, record(State.BOUND, 2)))),
        )

        val result = resolved(nodes)

        assertEquals(State.BOUND, result.state)
        assertEquals(2L, result.revision)
        assertTrue(result.allowsNewGeneration)
    }

    @Test
    fun `an in-flight branch does not permit a new generation`() {
        val nodes = listOf(
            node(1, listOf(message(1, MessageRole.USER, record(State.START_IN_FLIGHT, 1)))),
        )

        val result = resolved(nodes)

        assertEquals(State.START_IN_FLIGHT, result.state)
        assertTrue(!result.allowsNewGeneration)
    }

    /** Only `messages[selectIndex]` is read: an unselected variant is not the branch. */
    @Test
    fun `an unselected variant is never scanned`() {
        val node = node(
            n = 1,
            messages = listOf(
                message(1, continuation = record(State.BOUND, 2)),
                message(2, continuation = record(State.START_IN_FLIGHT, 3)),
            ),
            selectIndex = 0,
        )

        assertEquals(State.BOUND, resolved(listOf(node)).state)
    }

    /**
     * A fork copies messages wholesale, so the copy carries the original's record. It is about a
     * branch the fork does not have, and the exact match is what stops it being inherited.
     */
    @Test
    fun `a record inherited by a fork does not match the forked branch`() {
        val nodes = listOf(
            node(1, listOf(message(1, continuation = record(State.BOUND, 2)))),
        )

        assertEquals(
            ClaudePSessionContinuationResolution.UNINITIALIZED,
            ClaudePSessionContinuationResolver.resolve(nodes, assistant, otherBranch),
        )
        // The same node read as the branch it does describe still resolves.
        assertEquals(State.BOUND, resolved(nodes).state)
    }

    /** The assistant is half the identity: a record for another assistant is not this branch's. */
    @Test
    fun `a record for another assistant does not match`() {
        val nodes = listOf(
            node(
                1,
                listOf(
                    message(
                        1,
                        continuation = record(State.BOUND, 2, assistantId = otherAssistant),
                    ),
                ),
            ),
        )

        assertEquals(
            ClaudePSessionContinuationResolution.UNINITIALIZED,
            resolve(nodes),
        )
    }

    /**
     * There is no fallback. A record for a *different* branch is not a weaker match for this one —
     * answering with it would bind a session belonging to something else.
     */
    @Test
    fun `a record for another branch is ignored, never used as a fallback`() {
        val nodes = listOf(
            node(1, listOf(message(1, continuation = record(State.BOUND, 2, branchId = otherBranch)))),
            node(2, listOf(message(2))),
        )

        assertEquals(ClaudePSessionContinuationResolution.UNINITIALIZED, resolve(nodes))
    }

    @Test
    fun `records on the path are folded in node order`() {
        val nodes = listOf(
            node(1, listOf(message(1, continuation = record(State.START_IN_FLIGHT, 1)))),
            node(2, listOf(message(2, continuation = record(State.BOUND, 2)))),
            node(3, listOf(message(3, continuation = record(State.START_IN_FLIGHT, 3)))),
        )

        val result = resolved(nodes)

        assertEquals(State.START_IN_FLIGHT, result.state)
        assertEquals(3L, result.revision)
    }

    // ---------------------------------------------------------------------------------------
    // Fail-closed
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a contradictory path is a conflict, never a last-write-wins`() {
        val nodes = listOf(
            node(1, listOf(message(1, continuation = record(State.BOUND, 1)))),
            node(2, listOf(message(2, continuation = record(State.BOUND, 2)))),
        )

        val result = resolve(nodes)

        assertTrue("expected a conflict, got $result", result is ClaudePSessionContinuationResolution.Conflicted)
        assertEquals(
            Conflict.ILLEGAL_TRANSITION,
            (result as ClaudePSessionContinuationResolution.Conflicted).reason,
        )
        assertTrue(!result.allowsNewGeneration)
    }

    /**
     * A damaged record refuses the whole path, not merely the branch it names. The records on one
     * path are written by one state machine, so one it could not have produced is evidence about
     * the path — and the permissive alternative leaves the caller acting on a history with a hole.
     */
    @Test
    fun `a damaged record refuses the resolution even when it names another branch`() {
        val nodes = listOf(
            node(1, listOf(message(1, continuation = record(State.BOUND, 2)))),
            node(
                2,
                listOf(
                    message(
                        2,
                        continuation = record(State.BOUND, 3, branchId = otherBranch)
                            .copy(revision = 0L),
                    ),
                ),
            ),
        )

        val result = resolve(nodes)

        assertTrue("expected a refusal, got $result", result is ClaudePSessionContinuationResolution.Refused)
        assertEquals(
            Refusal.NON_POSITIVE_REVISION,
            (result as ClaudePSessionContinuationResolution.Refused).reason,
        )
    }

    @Test
    fun `a record that lost its state is refused, never read as a default`() {
        val nodes = listOf(node(1, listOf(message(1, continuation = record(null, 1)))))

        assertEquals(
            Refusal.MISSING_STATE,
            (resolve(nodes) as ClaudePSessionContinuationResolution.Refused).reason,
        )
    }

    /** A node with nothing selected cannot be skipped: it is exactly where a record would be. */
    @Test
    fun `a node with no selected message refuses the resolution`() {
        val nodes = listOf(
            node(1, listOf(message(1, continuation = record(State.BOUND, 1)))),
            node(2, emptyList()),
        )

        assertEquals(
            Refusal.SELECTED_MESSAGE_UNREADABLE,
            (resolve(nodes) as ClaudePSessionContinuationResolution.Refused).reason,
        )
    }

    /** A query that cannot match anything is a wiring defect, not a fresh branch. */
    @Test
    fun `an unusable query identity is refused, not reported as uninitialized`() {
        val nodes = listOf(node(1, listOf(message(1))))

        assertEquals(
            Refusal.BLANK_ASSISTANT_ID,
            (
                ClaudePSessionContinuationResolver.resolve(nodes, "", branch)
                    as ClaudePSessionContinuationResolution.Refused
                ).reason,
        )
        assertEquals(
            Refusal.MALFORMED_BRANCH_ID,
            (
                ClaudePSessionContinuationResolver.resolve(nodes, assistant, "not-a-branch")
                    as ClaudePSessionContinuationResolution.Refused
                ).reason,
        )
    }

    @Test
    fun `resolving for a conversation uses the conversation's own assistant`() {
        val nodes = listOf(node(1, listOf(message(1, continuation = record(State.BOUND, 1)))))

        assertEquals(
            State.BOUND,
            (
                ClaudePSessionContinuationResolver.resolve(conversation(nodes), branch)
                    as ClaudePSessionContinuationResolution.Resolved
                ).state,
        )
        // The same graph read for the other assistant has no record of its own.
        assertEquals(
            ClaudePSessionContinuationResolution.UNINITIALIZED,
            ClaudePSessionContinuationResolver.resolve(
                conversation(nodes).copy(assistantId = kotlin.uuid.Uuid.parse(otherAssistant)),
                branch,
            ),
        )
    }

    // ---------------------------------------------------------------------------------------
    // The codec
    // ---------------------------------------------------------------------------------------

    /**
     * Every conversation already on disk was written before this field existed. It has to keep
     * decoding, and the message it decodes to has to read as "no record" rather than as a state.
     */
    @Test
    fun `an older message without the field decodes to no record`() {
        val legacy = """{"id":"00000001-0000-0000-0000-000000000001","role":"assistant",""" +
            """"parts":[{"type":"text","text":"hello"}],"annotations":[],""" +
            """"createdAt":"2026-01-01T00:00:00","state":"COMPLETED"}"""

        val decoded = JsonInstant.decodeFromString<UIMessage>(legacy)

        assertNull(decoded.claudePSessionContinuation)
        assertEquals("hello", (decoded.parts.single() as UIMessagePart.Text).text)
    }

    /** The same, for a record written by a build that predates the field entirely. */
    @Test
    fun `a message encoded without the field decodes back to no record`() {
        val encoded = JsonInstant.encodeToJsonElement(message(1)).jsonObject
        val withoutField = JsonObject(encoded - "claudePSessionContinuation")

        assertNull(
            JsonInstant.decodeFromString<UIMessage>(withoutField.toString())
                .claudePSessionContinuation,
        )
    }

    @Test
    fun `a continuation record round-trips through the storage codec`() {
        val original = message(
            1,
            continuation = record(State.BIND_PENDING, 3, generationId = "gen-1"),
        )

        val decoded = JsonInstant.decodeFromString<UIMessage>(
            JsonInstant.encodeToString(original),
        )

        assertEquals(original.claudePSessionContinuation, decoded.claudePSessionContinuation)
        assertEquals(
            "gen-1",
            decoded.claudePSessionContinuation?.generationId,
        )
        assertEquals(State.BIND_PENDING, decoded.claudePSessionContinuation?.state)
    }

    /** A state this build does not know is not silently folded into one it does. */
    @Test
    fun `an unknown state fails to decode rather than defaulting`() {
        val encoded = JsonInstant.encodeToJsonElement(
            message(1, continuation = record(State.BOUND, 1)),
        ).jsonObject
        val continuation = (encoded["claudePSessionContinuation"] as JsonObject).toMutableMap()
        continuation["state"] = JsonPrimitive("RESUMED")

        val mutated = JsonObject(
            encoded + ("claudePSessionContinuation" to JsonObject(continuation)),
        )

        val decoded = runCatching {
            JsonInstant.decodeFromString<UIMessage>(mutated.toString())
        }

        assertTrue("an unknown state must not decode", decoded.isFailure)
    }
}
