package me.rerere.rikkahub.data.claudep

import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.claudep.ClaudePSessionBranchId
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.service.chat.CancelCurrentToolCommand
import me.rerere.rikkahub.service.chat.CancelQueuedCommand
import me.rerere.rikkahub.service.chat.CancelSteeringCommand
import me.rerere.rikkahub.service.chat.ClearPendingQueueCommand
import me.rerere.rikkahub.service.chat.InterruptCommand
import me.rerere.rikkahub.service.chat.InterruptRegenerateCommand
import me.rerere.rikkahub.service.chat.PetDialogueCommand
import me.rerere.rikkahub.service.chat.PromoteQueuedMessageToSteeringCommand
import me.rerere.rikkahub.service.chat.RawUserContent
import me.rerere.rikkahub.service.chat.RegenerateCommand
import me.rerere.rikkahub.service.chat.ResumeAfterApprovalCommand
import me.rerere.rikkahub.service.chat.ResumeQueueCommand
import me.rerere.rikkahub.service.chat.SendMessageCommand
import me.rerere.rikkahub.service.chat.SteerCommand
import me.rerere.rikkahub.service.chat.StopCommand
import me.rerere.rikkahub.service.chat.ToolApprovalCommand
import me.rerere.rikkahub.service.chat.ToolDecision
import me.rerere.rikkahub.service.chat.UpdateQueuedMessageCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapping from the authoritative message graph onto a Claude P branch identity.
 *
 * These are the properties M3-0 has to prove before any session key is built on them: that a
 * linear append is invisible to the identity, that a variant changes it, that switching back
 * restores it, that the identity is a pure function of the committed graph (so a failed or
 * cancelled regenerate cannot move it), and that a malformed graph is refused rather than
 * described.
 */
class ClaudePSessionBranchPlannerTest {

    private fun uid(prefix: Int, n: Int) =
        kotlin.uuid.Uuid.parse("%08d-0000-0000-0000-%012d".format(prefix, n))

    private fun message(n: Int) = UIMessage(
        id = uid(1, n),
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text("m$n")),
    )

    private fun node(n: Int, messages: List<UIMessage>, selectIndex: Int = 0) = MessageNode(
        id = uid(2, n),
        messages = messages,
        selectIndex = selectIndex,
    )

    private fun branchId(nodes: List<MessageNode>): String {
        val result = ClaudePSessionBranchPlanner.branchIdOf(nodes)
        assertTrue("expected a known branch id, got $result", result is ClaudePSessionBranchPlanner.Branch.Known)
        return (result as ClaudePSessionBranchPlanner.Branch.Known).id
    }

    private fun sendCommand() = SendMessageCommand(
        RawUserContent(listOf(UIMessagePart.Text("hi"))),
    )

    private fun regenerateCommand() = RegenerateCommand(
        targetMessageId = uid(1, 1),
        expectedTargetVersion = 0L,
        expectedBranchHeadMessageId = uid(1, 1),
    )

    // ---------------------------------------------------------------------------------------
    // The selection vector
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a graph with only default selections describes the root branch`() {
        val nodes = listOf(node(1, listOf(message(1))), node(2, listOf(message(2))))

        val selections = ClaudePSessionBranchPlanner.selectionsOf(nodes)

        assertEquals(
            ClaudePSessionBranchPlanner.Selections.Valid(emptyList<ClaudePSessionBranchId.Selection>()),
            selections,
        )
        assertEquals(branchId(emptyList()), branchId(nodes))
    }

    /**
     * The property continuation depends on: a normal linear append adds a node at `selectIndex == 0`,
     * which is not part of the selection vector, so the branch identity does not move.
     */
    @Test
    fun `a linear append does not change the branch identity`() {
        val before = listOf(node(1, listOf(message(1))))
        val after = listOf(node(1, listOf(message(1))), node(2, listOf(message(2))))

        assertEquals(branchId(before), branchId(after))
        assertEquals(branchId(emptyList()), branchId(after))
    }

    @Test
    fun `selecting a non-default variant changes the branch identity`() {
        val root = listOf(node(1, listOf(message(1))))
        val edited = listOf(node(1, listOf(message(1), message(2)), selectIndex = 1))

        assertNotEquals(branchId(root), branchId(edited))
    }

    @Test
    fun `switching back to the default variant restores the original identity`() {
        val root = listOf(node(1, listOf(message(1))))
        val edited = listOf(node(1, listOf(message(1), message(2)), selectIndex = 1))

        // The graph is the authority: the same graph must give the same identity, so returning to
        // index 0 returns to the root identity rather than to a third, new one.
        assertEquals(branchId(root), branchId(listOf(node(1, listOf(message(1), message(2)), selectIndex = 0))))
        assertNotEquals(branchId(root), branchId(edited))
    }

    @Test
    fun `two different variants of one node do not share an identity`() {
        val first = listOf(node(1, listOf(message(1), message(2)), selectIndex = 1))
        val second = listOf(node(1, listOf(message(1), message(3)), selectIndex = 1))

        assertNotEquals(branchId(first), branchId(second))
    }

    /**
     * The identity is over the whole combination, not over any single node. Selecting variants on
     * two nodes is a third branch, distinct from either node alone and from the root.
     */
    @Test
    fun `combined selections are distinct from each part and from the root`() {
        val onlyFirst = listOf(
            node(1, listOf(message(1), message(2)), selectIndex = 1),
            node(2, listOf(message(3))),
        )
        val onlySecond = listOf(
            node(1, listOf(message(1))),
            node(2, listOf(message(3), message(4)), selectIndex = 1),
        )
        val both = listOf(
            node(1, listOf(message(1), message(2)), selectIndex = 1),
            node(2, listOf(message(3), message(4)), selectIndex = 1),
        )

        val ids = setOf(branchId(onlyFirst), branchId(onlySecond), branchId(both), branchId(emptyList()))
        assertEquals(4, ids.size)
    }

    /**
     * Only the *selected* variant is part of the identity. A non-selected variant is not what the
     * user is continuing from, and letting it in would make an unrelated future edit change an
     * existing branch's identity.
     */
    @Test
    fun `non-selected variants do not affect the identity`() {
        val withSpare = listOf(node(1, listOf(message(1), message(9)), selectIndex = 0))
        val withoutSpare = listOf(node(1, listOf(message(1)), selectIndex = 0))

        assertEquals(branchId(withoutSpare), branchId(withSpare))
    }

    @Test
    fun `the identity depends only on the committed graph`() {
        val nodes = listOf(node(1, listOf(message(1), message(2)), selectIndex = 1))

        assertEquals(branchId(nodes), branchId(nodes))
    }

    // ---------------------------------------------------------------------------------------
    // Malformed graphs
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a node with no messages is rejected`() {
        val result = ClaudePSessionBranchPlanner.selectionsOf(listOf(node(1, emptyList())))

        assertEquals(
            ClaudePSessionBranchPlanner.Selections.Rejected(
                ClaudePSessionBranchPlanner.GraphRejection.EMPTY_NODE,
            ),
            result,
        )
    }

    @Test
    fun `an out of range select index is rejected`() {
        val tooHigh = ClaudePSessionBranchPlanner.selectionsOf(
            listOf(node(1, listOf(message(1)), selectIndex = 4)),
        )
        val negative = ClaudePSessionBranchPlanner.selectionsOf(
            listOf(node(1, listOf(message(1)), selectIndex = -1)),
        )

        assertEquals(
            ClaudePSessionBranchPlanner.Selections.Rejected(
                ClaudePSessionBranchPlanner.GraphRejection.SELECT_INDEX_OUT_OF_RANGE,
            ),
            tooHigh,
        )
        assertEquals(
            ClaudePSessionBranchPlanner.Selections.Rejected(
                ClaudePSessionBranchPlanner.GraphRejection.SELECT_INDEX_OUT_OF_RANGE,
            ),
            negative,
        )
    }

    /**
     * A graph that cannot be described yields no identity rather than a partial one, and the reason
     * survives to the caller: an empty node is reported as an empty node, not collapsed into some
     * other failure that would send a reader looking in the wrong place.
     */
    @Test
    fun `a malformed graph yields no branch identity rather than a partial one`() {
        val nodes = listOf(
            node(1, listOf(message(1), message(2)), selectIndex = 1),
            node(2, emptyList()),
        )

        assertEquals(
            ClaudePSessionBranchPlanner.Branch.Rejected(
                ClaudePSessionBranchPlanner.GraphRejection.EMPTY_NODE,
            ),
            ClaudePSessionBranchPlanner.branchIdOf(nodes),
        )
    }

    /**
     * A describable graph can still describe something illegal: two nodes cannot both be continuing
     * from the same selected message. That is a different defect from an undescribable graph, and it
     * must be reported as its own, so this also proves the second failure arm is reachable.
     */
    @Test
    fun `a selection vector that reuses one selected message is malformed`() {
        val nodes = listOf(
            node(1, listOf(message(1), message(9)), selectIndex = 1),
            node(2, listOf(message(8), message(9)), selectIndex = 1),
        )

        assertEquals(
            ClaudePSessionBranchPlanner.Branch.Malformed(
                ClaudePSessionBranchId.Reason.DUPLICATE_SELECTED_MESSAGE_ID,
            ),
            ClaudePSessionBranchPlanner.branchIdOf(nodes),
        )
    }

    // ---------------------------------------------------------------------------------------
    // Round trip
    // ---------------------------------------------------------------------------------------

    /**
     * The identity must survive persistence. The graph is stored as JSON inside the node rows, so a
     * codec round trip must not change what the branch is.
     */
    @Test
    fun `the identity survives a json round trip`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val nodes = listOf(
            node(1, listOf(message(1), message(2)), selectIndex = 1),
            node(2, listOf(message(3))),
        )

        val encoded = json.encodeToString(nodes)
        val decoded = json.decodeFromString<List<MessageNode>>(encoded)

        assertEquals(branchId(nodes), branchId(decoded))
    }

    // ---------------------------------------------------------------------------------------
    // Classification
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a plain send is known before dispatch`() {
        val command = SendMessageCommand(RawUserContent(listOf(UIMessagePart.Text("hi"))))

        assertEquals(
            ClaudePSessionBranchPlanner.Mode.KNOWN_BEFORE_DISPATCH,
            ClaudePSessionBranchPlanner.classify(command, MessageRole.USER),
        )
    }

    /**
     * Regenerating an assistant response appends a variant and selects it, so the branch the
     * generation belongs to only exists once it succeeds.
     */
    @Test
    fun `regenerating an assistant message is deferred`() {
        val command = RegenerateCommand(
            targetMessageId = uid(1, 1),
            expectedTargetVersion = 0L,
            expectedBranchHeadMessageId = uid(1, 1),
        )

        assertEquals(
            ClaudePSessionBranchPlanner.Mode.NEW_DEFERRED_BIND,
            ClaudePSessionBranchPlanner.classify(command, MessageRole.ASSISTANT),
        )
    }

    /**
     * Regenerating a *user* message truncates the nodes after the target before dispatch and appends
     * a fresh assistant node at the default index, so the identity is already determined.
     */
    @Test
    fun `regenerating a user message is known before dispatch`() {
        val command = RegenerateCommand(
            targetMessageId = uid(1, 1),
            expectedTargetVersion = 0L,
            expectedBranchHeadMessageId = uid(1, 1),
        )

        assertEquals(
            ClaudePSessionBranchPlanner.Mode.KNOWN_BEFORE_DISPATCH,
            ClaudePSessionBranchPlanner.classify(command, MessageRole.USER),
        )
    }

    /**
     * An unresolved target is exactly the case where a wrong "known" would be unrecoverable, so it
     * must fall to the deferred side. Deferring costs a session; mis-claiming costs a generation
     * bound to a branch that is about to change.
     */
    @Test
    fun `a regenerate whose target cannot be resolved is deferred rather than assumed known`() {
        val command = RegenerateCommand(
            targetMessageId = uid(1, 1),
            expectedTargetVersion = 0L,
            expectedBranchHeadMessageId = uid(1, 1),
        )

        assertEquals(
            ClaudePSessionBranchPlanner.Mode.NEW_DEFERRED_BIND,
            ClaudePSessionBranchPlanner.classify(command, null),
        )
    }

    /**
     * An interrupt carries a `SendMessageCommand` and behaves exactly like one — it is not a second
     * kind of operation with its own rules.
     */
    @Test
    fun `interrupting with a send is known before dispatch`() {
        val command = InterruptCommand(replacement = sendCommand())

        assertEquals(
            ClaudePSessionBranchPlanner.Mode.KNOWN_BEFORE_DISPATCH,
            ClaudePSessionBranchPlanner.classify(command, MessageRole.USER),
        )
    }

    /**
     * `InterruptRegenerateCommand` must delegate to the *same* regenerate rule, using its inner
     * `regeneration`. A copy of the rule here is how the two would silently drift apart.
     */
    @Test
    fun `interrupting a regenerate delegates to the same rule`() {
        val assistant = InterruptRegenerateCommand(regeneration = regenerateCommand())
        assertEquals(
            ClaudePSessionBranchPlanner.Mode.NEW_DEFERRED_BIND,
            ClaudePSessionBranchPlanner.classify(assistant, MessageRole.ASSISTANT),
        )

        val user = InterruptRegenerateCommand(regeneration = regenerateCommand())
        assertEquals(
            ClaudePSessionBranchPlanner.Mode.KNOWN_BEFORE_DISPATCH,
            ClaudePSessionBranchPlanner.classify(user, MessageRole.USER),
        )

        val unresolved = InterruptRegenerateCommand(regeneration = regenerateCommand())
        assertEquals(
            ClaudePSessionBranchPlanner.Mode.NEW_DEFERRED_BIND,
            ClaudePSessionBranchPlanner.classify(unresolved, null),
        )
    }

    /**
     * The approval continuation runs inside the branch that opened the generation: it resumes the
     * existing assistant message and selects nothing new, so the selection vector is already
     * committed when it is admitted.
     */
    @Test
    fun `an approval continuation is known before dispatch`() {
        assertEquals(
            ClaudePSessionBranchPlanner.Mode.KNOWN_BEFORE_DISPATCH,
            ClaudePSessionBranchPlanner.classify(ResumeAfterApprovalCommand, null),
        )
    }

    /**
     * These commands start no model generation. Reporting them as known would make a command that
     * dispatched nothing look like a generation whose branch had been settled.
     */
    @Test
    fun `commands that start no model generation are reported as such`() {
        val id = uid(1, 1)
        val nonModel = listOf(
            "StopCommand" to StopCommand(),
            "ToolApprovalCommand" to ToolApprovalCommand(
                toolCallId = "call-1",
                decision = ToolDecision.Approved,
            ),
            "SteerCommand" to SteerCommand(text = "steer"),
            "CancelCurrentToolCommand" to CancelCurrentToolCommand(toolCallId = "call-1"),
            "ResumeQueueCommand" to ResumeQueueCommand(),
            "ClearPendingQueueCommand" to ClearPendingQueueCommand(),
            "CancelQueuedCommand" to CancelQueuedCommand(targetCommandId = id),
            "CancelSteeringCommand" to CancelSteeringCommand(targetCommandId = id),
            "UpdateQueuedMessageCommand" to UpdateQueuedMessageCommand(
                targetCommandId = id,
                content = RawUserContent(listOf(UIMessagePart.Text("updated"))),
            ),
            "PromoteQueuedMessageToSteeringCommand" to
                PromoteQueuedMessageToSteeringCommand(targetCommandId = id),
            "PetDialogueCommand" to PetDialogueCommand(
                assistantId = id,
                privilegedConversationId = uid(1, 2),
                input = "hi",
            ),
        )

        for ((name, command) in nonModel) {
            assertEquals(
                "$name must not be reported as a model generation",
                ClaudePSessionBranchPlanner.Mode.NOT_MODEL_GENERATION,
                ClaudePSessionBranchPlanner.classify(command, null),
            )
        }
    }

    /**
     * The structural half of the guarantee.
     *
     * The primary guarantee is the compiler's: `classify` is an exhaustive `when` over the sealed
     * command hierarchy with no `else`, so a new command type fails to compile until it is
     * classified deliberately. This asserts the thing that mechanical guarantee depends on — that
     * nobody has quietly reintroduced a catch-all, which would answer for the new type with the
     * permissive branch and restore the bug the exhaustiveness removed.
     */
    @Test
    fun `the classifier has no permissive default`() {
        val source = plannerSource()
        val start = source.indexOf("fun classify(command: ChatCommand")
        assertTrue("classify() was not found in the planner source", start >= 0)
        val end = source.indexOf("\n    }", start)
        assertTrue("classify() body could not be delimited", end > start)
        val body = source.substring(start, end)

        assertFalse(
            "classify() must stay exhaustive and must not contain an `else ->` branch",
            body.contains("else ->"),
        )
    }

    private fun plannerSource(): String {
        // `:app` unit tests run with the working directory set to `app/`, but be tolerant of being
        // run from the repository root as well.
        val relative = "src/main/java/me/rerere/rikkahub/data/claudep/ClaudePSessionBranchPlanner.kt"
        val candidates = listOf(relative, "app/$relative", "../app/$relative")
        for (candidate in candidates) {
            val file = java.io.File(candidate)
            if (file.isFile) return file.readText()
        }
        throw AssertionError(
            "Could not locate ClaudePSessionBranchPlanner.kt from ${java.io.File(".").absolutePath}",
        )
    }

    /**
     * The deferred commit, end to end at the identity level: before the generation the graph still
     * describes the old branch; once the new variant is committed and selected it describes the new
     * one; and a generation that fails without touching the graph leaves the old identity intact.
     */
    @Test
    fun `a deferred regenerate moves the identity only once the variant is committed`() {
        val before = listOf(node(1, listOf(message(1)), selectIndex = 0))
        val beforeId = branchId(before)

        // Failure or cancellation does not mutate the graph.
        assertEquals(beforeId, branchId(before))

        // Success commits a new variant and selects it.
        val committed = listOf(node(1, listOf(message(1), message(2)), selectIndex = 1))
        val committedId = branchId(committed)

        assertNotEquals(beforeId, committedId)
        assertEquals(committedId, branchId(committed))
    }
}
