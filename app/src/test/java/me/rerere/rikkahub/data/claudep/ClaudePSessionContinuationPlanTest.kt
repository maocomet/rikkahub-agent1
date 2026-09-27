package me.rerere.rikkahub.data.claudep

import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.claudep.ClaudePSessionContinuation
import me.rerere.ai.provider.claudep.ClaudePSessionContinuationState as State
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.service.chat.RawUserContent
import me.rerere.rikkahub.service.chat.RegenerateCommand
import me.rerere.rikkahub.service.chat.SendMessageCommand
import me.rerere.rikkahub.service.chat.SteerCommand
import me.rerere.rikkahub.service.chat.StopCommand
import me.rerere.rikkahub.service.chat.ToolApprovalCommand
import me.rerere.rikkahub.service.chat.ToolDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The production input seam: what the dispatch path is told to send, and when it is told to send
 * nothing.
 *
 * The property that matters most here is what the seam does *not* do. It never falls back to the
 * pre-M3 `new` request shape, and it never answers "immediate" for a branch that may already have
 * a generation running — that is the answer that would silently open a second Claude session.
 */
class ClaudePSessionContinuationPlanTest {

    private val assistant = "00000000-0000-0000-0000-0000000000aa"
    private val assistantUuid = kotlin.uuid.Uuid.parse(assistant)

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

    private fun record(state: State, revision: Long, generationId: String? = null) =
        ClaudePSessionContinuation(
            assistantId = assistant,
            branchId = branchId(emptyList()),
            revision = revision,
            state = state,
            generationId = generationId,
        )

    private fun conversation(nodes: List<MessageNode>) = Conversation(
        id = uid(3, 1),
        assistantId = assistantUuid,
        messageNodes = nodes,
    )

    private fun branchId(nodes: List<MessageNode>): String =
        (ClaudePSessionBranchPlanner.branchIdOf(nodes)
            as ClaudePSessionBranchPlanner.Branch.Known).id

    private fun sendCommand() = SendMessageCommand(RawUserContent(listOf(UIMessagePart.Text("hi"))))

    private fun regenerateCommand() = RegenerateCommand(
        targetMessageId = uid(1, 1),
        expectedTargetVersion = 0L,
        expectedBranchHeadMessageId = uid(1, 1),
    )

    private fun plan(
        nodes: List<MessageNode>,
        command: me.rerere.rikkahub.service.chat.ChatCommand,
        targetRole: MessageRole? = null,
    ) = ClaudePSessionBranchPlanner.plan(conversation(nodes), command, targetRole)

    private fun refused(
        nodes: List<MessageNode>,
        command: me.rerere.rikkahub.service.chat.ChatCommand,
        targetRole: MessageRole? = null,
    ): ClaudePSessionContinuationPlan.Reason {
        val result = plan(nodes, command, targetRole)
        assertTrue("expected a refusal, got $result", result is ClaudePSessionContinuationPlan.Refused)
        return (result as ClaudePSessionContinuationPlan.Refused).reason
    }

    // ---------------------------------------------------------------------------------------
    // Immediate
    // ---------------------------------------------------------------------------------------

    /** A branch with no history is the one case that is genuinely new. */
    @Test
    fun `a send on a branch with no history plans an immediate generation at revision one`() {
        val nodes = listOf(node(1, listOf(message(1, MessageRole.USER))))

        val result = plan(nodes, sendCommand())

        assertEquals(
            ClaudePSessionContinuationPlan.Immediate(
                assistantId = assistant,
                branchId = branchId(nodes),
                revision = 1L,
            ),
            result,
        )
    }

    @Test
    fun `a send on a bound branch plans the next immediate revision`() {
        val nodes = listOf(
            node(1, listOf(message(1, MessageRole.USER, record(State.START_IN_FLIGHT, 1)))),
            node(2, listOf(message(2, continuation = record(State.BOUND, 2)))),
        )

        val result = plan(nodes, sendCommand())

        assertEquals(
            ClaudePSessionContinuationPlan.Immediate(assistant, branchId(nodes), 3L),
            result,
        )
    }

    /**
     * The permissive answer is the dangerous one: an in-flight branch may already have a model
     * running, and "immediate" would start a second one for it.
     */
    @Test
    fun `every unproven or closed branch refuses rather than planning a generation`() {
        val inFlight = listOf(
            node(1, listOf(message(1, MessageRole.USER, record(State.START_IN_FLIGHT, 1)))),
        )
        val pending = listOf(
            node(
                1,
                listOf(
                    message(1, continuation = record(State.BIND_PENDING, 1, "gen-1")),
                ),
            ),
        )
        val interrupted = listOf(
            node(1, listOf(message(1, continuation = record(State.INTERRUPTED, 1)))),
        )
        val closed = listOf(
            node(1, listOf(message(1, continuation = record(State.FAILED_CLOSED, 1)))),
        )

        for (nodes in listOf(inFlight, pending, interrupted, closed)) {
            assertEquals(
                ClaudePSessionContinuationPlan.Reason.CONTINUATION_BLOCKED,
                refused(nodes, sendCommand()),
            )
        }
    }

    @Test
    fun `a contradictory branch refuses as a conflict`() {
        val nodes = listOf(
            node(1, listOf(message(1, continuation = record(State.BOUND, 1)))),
            node(2, listOf(message(2, continuation = record(State.BOUND, 2)))),
        )

        assertEquals(
            ClaudePSessionContinuationPlan.Reason.CONTINUATION_CONFLICTED,
            refused(nodes, sendCommand()),
        )
    }

    @Test
    fun `a branch the graph cannot describe refuses`() {
        assertEquals(
            ClaudePSessionContinuationPlan.Reason.BRANCH_NOT_DESCRIBABLE,
            refused(listOf(node(1, emptyList())), sendCommand()),
        )
    }

    /** A graph that can be walked is still refused when the vector it describes is not legal. */
    @Test
    fun `a malformed selection vector refuses`() {
        // Two distinct nodes carrying the same identity: walkable, but not a legal vector — the
        // digest would be over a graph that cannot exist.
        val nodes = listOf(
            node(1, listOf(message(1), message(2)), selectIndex = 1),
            node(1, listOf(message(3), message(4)), selectIndex = 1),
        )

        assertEquals(
            ClaudePSessionContinuationPlan.Reason.BRANCH_MALFORMED,
            refused(nodes, sendCommand()),
        )
    }

    // ---------------------------------------------------------------------------------------
    // Deferred
    // ---------------------------------------------------------------------------------------

    /**
     * A regenerate on an assistant message creates the variant this generation is about to
     * produce, so the branch does not exist yet — and the plan carries no branch id at all.
     */
    @Test
    fun `an assistant regenerate plans a deferred generation with no branch id`() {
        val nodes = listOf(
            node(1, listOf(message(1, MessageRole.USER))),
            node(2, listOf(message(2))),
        )

        val result = plan(nodes, regenerateCommand(), MessageRole.ASSISTANT)

        assertEquals(ClaudePSessionContinuationPlan.Deferred(assistant), result)
    }

    @Test
    fun `a user regenerate plans an immediate generation`() {
        val nodes = listOf(
            node(1, listOf(message(1, MessageRole.USER))),
            node(2, listOf(message(2))),
        )

        val result = plan(nodes, regenerateCommand(), MessageRole.USER)

        assertTrue("expected an immediate plan, got $result", result is ClaudePSessionContinuationPlan.Immediate)
    }

    /**
     * An unresolved target is not evidence of the harmless case. A deferred generation merely
     * costs a new session; an immediate one would run under the *old* branch identity while the
     * graph is about to move to a new one.
     */
    @Test
    fun `an unresolved regenerate target plans a deferred generation`() {
        val nodes = listOf(node(1, listOf(message(1, MessageRole.USER))))

        assertEquals(
            ClaudePSessionContinuationPlan.Deferred(assistant),
            plan(nodes, regenerateCommand(), null),
        )
    }

    /** A deferred plan is not gated on the current branch: it describes a different one. */
    @Test
    fun `an in-flight branch does not block a deferred regenerate`() {
        val nodes = listOf(
            node(1, listOf(message(1, MessageRole.USER, record(State.START_IN_FLIGHT, 1)))),
            node(2, listOf(message(2))),
        )

        assertEquals(
            ClaudePSessionContinuationPlan.Deferred(assistant),
            plan(nodes, regenerateCommand(), MessageRole.ASSISTANT),
        )
    }

    // ---------------------------------------------------------------------------------------
    // Commands that start nothing
    // ---------------------------------------------------------------------------------------

    @Test
    fun `commands that dispatch no generation plan nothing`() {
        val nodes = listOf(node(1, listOf(message(1, MessageRole.USER))))

        val commands = listOf(
            ToolApprovalCommand(toolCallId = "call-1", decision = ToolDecision.Approved),
            SteerCommand(text = "steer"),
            StopCommand(),
        )

        for (command in commands) {
            assertEquals(
                ClaudePSessionContinuationPlan.NotModelGeneration(assistant),
                plan(nodes, command),
            )
        }
    }

    /** The plan names the conversation's own assistant, never a caller's idea of one. */
    @Test
    fun `the plan carries the conversation's assistant`() {
        val nodes = listOf(node(1, listOf(message(1, MessageRole.USER))))

        val immediate = plan(nodes, sendCommand())

        assertEquals(assistant, immediate.assistantId)
    }
}
