package me.rerere.rikkahub.data.claudep

import kotlin.uuid.Uuid
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.claudep.ClaudePSessionBindingIntent
import me.rerere.ai.provider.claudep.ClaudePSessionContinuation
import me.rerere.ai.provider.claudep.ClaudePSessionContinuationResolution
import me.rerere.ai.provider.claudep.ClaudePSessionContinuationState as State
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.service.chat.DurableCommandState
import me.rerere.rikkahub.service.chat.RawUserContent
import me.rerere.rikkahub.service.chat.RegenerateCommand
import me.rerere.rikkahub.service.chat.SendMessageCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The admission-time half of the production wiring: where the barrier goes, what the run owes when
 * it ends, and how a stranded start is reconciled.
 *
 * Three properties are what these tests exist for, and each of them is the difference between a
 * branch that is usable and one that is not:
 *
 * - **A barrier is written where the resolver will read it.** Only the selected variant of a node
 *   is read, so a record attached to an unselected one is a record that does not exist.
 * - **A run that wrote a barrier and then ends owes a terminal.** The settlement is built from the
 *   decision rather than from an outcome, because it is attached before any outcome exists.
 * - **A stranded start is superseded, not resumed.** `START_IN_FLIGHT` is what makes the branch
 *   closed; the reconciliation replaces it in its own slot and leaves the branch closed.
 */
class ClaudePSessionAdmissionWiringTest {

    private val assistant = "00000000-0000-0000-0000-0000000000aa"
    private val assistantUuid = Uuid.parse(assistant)

    private fun uid(prefix: Int, n: Int) =
        Uuid.parse("%08d-0000-0000-0000-%012d".format(prefix, n))

    private fun message(
        n: Int,
        role: MessageRole = MessageRole.ASSISTANT,
        continuation: ClaudePSessionContinuation? = null,
    ) = UIMessage(
        id = uid(1, n),
        role = role,
        parts = listOf(UIMessagePart.Text("m$n")),
        createdAt = FIXED_CREATED_AT,
        claudePSessionContinuation = continuation,
    )

    private fun node(n: Int, messages: List<UIMessage>, selectIndex: Int = 0) = MessageNode(
        id = uid(2, n),
        messages = messages,
        selectIndex = selectIndex,
    )

    private fun conversation(nodes: List<MessageNode>) = Conversation(
        id = uid(3, 1),
        assistantId = assistantUuid,
        messageNodes = nodes,
    )

    private fun branchId(nodes: List<MessageNode>): String =
        (ClaudePSessionBranchPlanner.branchIdOf(nodes)
            as ClaudePSessionBranchPlanner.Branch.Known).id

    private fun record(state: State, revision: Long, branchId: String, generationId: String? = null) =
        ClaudePSessionContinuation(
            assistantId = assistant,
            branchId = branchId,
            revision = revision,
            state = state,
            generationId = generationId,
        )

    private fun sendCommand() = SendMessageCommand(RawUserContent(listOf(UIMessagePart.Text("hi"))))

    private fun regenerateCommand(target: Uuid) = RegenerateCommand(
        targetMessageId = target,
        expectedTargetVersion = 0L,
        expectedBranchHeadMessageId = target,
    )

    /**
     * [conversation] with [record] carried on the **first** node's selected message.
     *
     * Every stranded shape in this class is the same graph with a different record on its anchor,
     * so the write lives here once. The branch id is always computed from the graph *before* the
     * record is attached — a record does not change which variant is selected, so the two agree.
     */
    private fun carrying(
        conversation: Conversation,
        record: ClaudePSessionContinuation,
    ): Conversation = conversation.copy(
        messageNodes = conversation.messageNodes.mapIndexed { index, node ->
            if (index != 0) {
                node
            } else {
                node.copy(
                    messages = listOf(
                        node.messages.single().copy(claudePSessionContinuation = record),
                    ),
                )
            }
        },
    )

    /** The two-node graph every stranded shape is built on. */
    private fun baseNodes() = listOf(
        node(1, listOf(message(1, MessageRole.USER))),
        node(2, listOf(message(2))),
    )

    /** A branch whose start was written and never settled — the stranded shape. */
    private fun stranded(): Pair<Conversation, String> {
        val nodes = baseNodes()
        val branch = branchId(nodes)
        return carrying(
            conversation(nodes),
            record(State.START_IN_FLIGHT, 1L, branch),
        ) to branch
    }

    private fun immediate(
        branch: String,
        revision: Long,
        admissionRecord: ClaudePSessionContinuation?,
    ) = ClaudePSessionContinuationGate.Decision.Immediate(
        assistantId = assistant,
        branchId = branch,
        revision = revision,
        admissionRecord = admissionRecord,
        request = me.rerere.ai.provider.claudep.ClaudePSessionBindingRequest(
            assistantId = assistant,
            intent = ClaudePSessionBindingIntent.IMMEDIATE,
            branchId = branch,
        ),
    )

    private fun committed(settlement: ClaudePSessionContinuationGate.Settlement): Conversation {
        assertTrue(
            "expected a commit, got $settlement",
            settlement is ClaudePSessionContinuationGate.Settlement.Commit,
        )
        return (settlement as ClaudePSessionContinuationGate.Settlement.Commit).conversation
    }

    private fun resolve(conversation: Conversation, branch: String) =
        ClaudePSessionContinuationResolver.resolve(conversation, branch)

    // ---------------------------------------------------------------------------------------
    // barricade
    // ---------------------------------------------------------------------------------------

    /**
     * The write the admission transaction performs, and the only thing that makes it a barrier:
     * the record is on the message the resolver reads, at the revision the plan named.
     */
    @Test
    fun `an immediate decision writes its barrier onto the anchor`() {
        val nodes = listOf(
            node(1, listOf(message(1, MessageRole.USER))),
            node(2, listOf(message(2))),
        )
        val base = conversation(nodes)
        val branch = branchId(nodes)
        val anchor = uid(1, 1).toString()

        val barricaded = present(
            ClaudePSessionContinuationGate.barricade(
                conversation = base,
                decision = immediate(branch, revision = 2L, admissionRecord = record(State.START_IN_FLIGHT, 2L, branch)),
                anchorMessageId = anchor,
            ),
        )

        val read = resolve(barricaded, branch) as ClaudePSessionContinuationResolution.Resolved
        assertEquals(State.START_IN_FLIGHT, read.state)
        assertEquals(2L, read.revision)
        // A branch whose model may be running is not one a second generation may start on.
        assertFalse(read.allowsNewGeneration)
    }

    /**
     * A continuation of an in-flight generation — the approved tool call — owes **no** barrier,
     * because the opening admission already wrote one for the same generation.
     */
    @Test
    fun `a decision with no admission record leaves the graph alone`() {
        val (base, branch) = stranded()

        val barricaded = ClaudePSessionContinuationGate.barricade(
            conversation = base,
            decision = immediate(branch, revision = 1L, admissionRecord = null),
            anchorMessageId = uid(1, 1).toString(),
        )

        assertSame(base, barricaded)
    }

    /**
     * Only the selected variant is read back, so a barrier written onto an unselected one would be
     * a barrier that exists nowhere. The write is refused rather than misplaced.
     */
    @Test
    fun `a barrier is refused when the anchor is not the selected variant`() {
        val selected = message(1, MessageRole.USER)
        val unselected = message(9, MessageRole.USER)
        val nodes = listOf(
            node(1, listOf(selected, unselected), selectIndex = 0),
            node(2, listOf(message(2))),
        )
        val branch = branchId(nodes)

        assertNull(
            ClaudePSessionContinuationGate.barricade(
                conversation = conversation(nodes),
                decision = immediate(branch, 1L, record(State.START_IN_FLIGHT, 1L, branch)),
                anchorMessageId = unselected.id.toString(),
            ),
        )
    }

    /** A message the graph does not contain cannot carry anything. */
    @Test
    fun `a barrier is refused when the anchor is absent`() {
        val (base, branch) = stranded()

        assertNull(
            ClaudePSessionContinuationGate.barricade(
                conversation = base,
                decision = immediate(branch, 1L, record(State.START_IN_FLIGHT, 1L, branch)),
                anchorMessageId = uid(1, 77).toString(),
            ),
        )
    }

    // ---------------------------------------------------------------------------------------
    // settlementFor
    // ---------------------------------------------------------------------------------------

    /**
     * The result-less terminal of a run that wrote a barrier: the state the authority recorded is
     * mapped once, and the branch stops being closed *only* when the turn actually completed.
     */
    @Test
    fun `an immediate run settles its barrier from the authority's terminal state`() {
        val (base, branch) = stranded()
        val settlement = present(
            ClaudePSessionContinuationGate.settlementFor(
                immediate(branch, revision = 1L, admissionRecord = null),
            ),
        )

        val interrupted = present(settlement.settle(base, DurableCommandState.CANCELLED))
        assertEquals(
            State.INTERRUPTED,
            (resolve(interrupted, branch) as ClaudePSessionContinuationResolution.Resolved).state,
        )

        val closed = present(settlement.settle(base, DurableCommandState.FAILED))
        assertEquals(
            State.FAILED_CLOSED,
            (resolve(closed, branch) as ClaudePSessionContinuationResolution.Resolved).state,
        )
    }

    /**
     * A state that is not an end settles nothing, and the settlement says so by refusing rather
     * than by writing something arbitrary. A run that passed through this seam still running must
     * not have moved its branch.
     */
    @Test
    fun `a settlement refuses a state that is not an end`() {
        val (base, _) = stranded()
        val settlement = present(
            ClaudePSessionContinuationGate.settlementFor(
                immediate("0".repeat(64), revision = 1L, admissionRecord = null),
            ),
        )

        assertNull(settlement.settle(base, DurableCommandState.RUNNING))
        assertNull(settlement.settle(base, DurableCommandState.PENDING))
        assertNull(settlement.settle(base, DurableCommandState.WAITING_APPROVAL))
    }

    /**
     * A `deferred` run owes no generation terminal — it owes the `BIND_PENDING` that follows its
     * variant commit — so attaching one would make a failed deferred run write a terminal for a
     * branch that does not exist yet.
     */
    @Test
    fun `a deferred or refused decision carries no settlement`() {
        assertNull(
            ClaudePSessionContinuationGate.settlementFor(
                ClaudePSessionContinuationGate.Decision.Deferred(
                    assistantId = assistant,
                    request = me.rerere.ai.provider.claudep.ClaudePSessionBindingRequest(
                        assistantId = assistant,
                        intent = ClaudePSessionBindingIntent.DEFERRED,
                        branchId = null,
                    ),
                ),
            ),
        )
        assertNull(
            ClaudePSessionContinuationGate.settlementFor(
                ClaudePSessionContinuationGate.Decision.Refused(
                    assistant,
                    ClaudePSessionContinuationGate.Reason.CONTINUATION_BLOCKED,
                ),
            ),
        )
        assertNull(
            ClaudePSessionContinuationGate.settlementFor(
                ClaudePSessionContinuationGate.Decision.NotModelGeneration,
            ),
        )
    }

    // ---------------------------------------------------------------------------------------
    // staleInFlight and supersedeStale
    // ---------------------------------------------------------------------------------------

    /**
     * The stranded shape, reached through the planner exactly as admission reaches it: a start with
     * no terminal is what makes a send refuse, and the reconciliation is anchored to that refusal.
     */
    @Test
    fun `a stranded start is reported when the plan refuses because of it`() {
        val (base, _) = stranded()

        val stale = present(
            ClaudePSessionContinuationGate.staleInFlight(base, sendCommand(), targetRole = null),
        )

        assertEquals(assistant, stale.assistantId)
        assertEquals(1L, stale.revision)
    }

    /**
     * The three other blocked states are not stranded starts. Each waits for something that is not
     * a reconciliation — an answer, a user-triggered replay — or does not recover at all, and
     * rewriting any of them would replace a correct state with an invented one.
     */
    @Test
    fun `only a start in flight is reported as stranded`() {
        val shapes = listOf(
            State.BIND_PENDING to "gen-1",
            State.INTERRUPTED to "gen-1",
            State.INTERRUPTED to null,
            State.FAILED_CLOSED to null,
        )
        for ((shape, generationId) in shapes) {
            val nodes = baseNodes()
            val carried = carrying(
                conversation(nodes),
                record(shape, 1L, branchId(nodes), generationId),
            )

            assertNull(
                "$shape is not a stranded start",
                ClaudePSessionContinuationGate.staleInFlight(carried, sendCommand(), null),
            )
        }
    }

    /** A branch that permits a generation was never refused, so there is nothing to reconcile. */
    @Test
    fun `a bound branch is not stranded`() {
        val nodes = baseNodes()
        val carried = carrying(
            conversation(nodes),
            record(State.BOUND, 2L, branchId(nodes)),
        )

        assertNull(ClaudePSessionContinuationGate.staleInFlight(carried, sendCommand(), null))
    }

    /**
     * A deferred command creates a different branch and is deliberately not gated on this one, so
     * a stranded start elsewhere must not stop it. Reporting one here would refuse a regenerate
     * because of a branch it is not about to use.
     */
    @Test
    fun `a deferred command is not stopped by a stranded start`() {
        val (base, _) = stranded()

        val plan = ClaudePSessionBranchPlanner.plan(
            conversation = base,
            command = regenerateCommand(uid(1, 2)),
            targetRole = MessageRole.ASSISTANT,
        )
        assertTrue("expected a deferred plan, got $plan", plan is ClaudePSessionContinuationPlan.Deferred)

        assertNull(
            ClaudePSessionContinuationGate.staleInFlight(
                base,
                regenerateCommand(uid(1, 2)),
                targetRole = MessageRole.ASSISTANT,
            ),
        )
    }

    /**
     * The reconciliation itself: the barrier is **replaced in its own slot**, so the branch reads
     * back as one unproven terminal rather than as a start followed by a terminal it never had.
     */
    @Test
    fun `a stranded start is superseded in the barrier's own slot`() {
        val (base, branch) = stranded()
        val stale = present(
            ClaudePSessionContinuationGate.staleInFlight(base, sendCommand(), null),
        )

        val settled = committed(ClaudePSessionContinuationGate.supersedeStale(base, stale))
        val read = resolve(settled, branch) as ClaudePSessionContinuationResolution.Resolved

        assertEquals(State.INTERRUPTED, read.state)
        // Superseded, not advanced: the terminal occupies the start's revision.
        assertEquals(stale.revision, read.revision)
        // And it carries no generation id, because an interrupted *start* has nothing to replay.
        assertNull(read.generationId)
        // Still closed: the whole point is that a reconciled branch does not become dispatchable.
        assertFalse(read.allowsNewGeneration)
    }

    /**
     * A barrier that cannot be located exactly is refused, and the refusal must stop the caller.
     * Writing a terminal somewhere else would settle a branch at a step it never reached.
     */
    @Test
    fun `superseding refuses when the barrier is not uniquely located`() {
        val (base, branch) = stranded()
        val elsewhere = ClaudePSessionContinuationGate.Obligation.Immediate(
            assistantId = assistant,
            branchId = branch,
            revision = 9L,
        )

        val settlement = ClaudePSessionContinuationGate.supersedeStale(base, elsewhere)

        assertEquals(
            ClaudePSessionContinuationGate.Settlement.Refused(
                ClaudePSessionContinuationGate.Reason.BARRIER_NOT_UNIQUELY_LOCATED,
            ),
            settlement,
        )
    }

    // ---------------------------------------------------------------------------------------
    // The consequence of a terminal, asserted rather than assumed
    // ---------------------------------------------------------------------------------------

    /**
     * **A turn that is cancelled or fails closes the branch to the next ordinary message.**
     *
     * This is stated as a test because it is a product-level consequence that is easy to miss when
     * reading the state machine one state at a time. A send appends its node at `selectIndex == 0`
     * and therefore does **not** change the branch identity, so the next message a user types lands
     * on the very branch the terminal just closed — and `INTERRUPTED` and `FAILED_CLOSED` both
     * answer `allowsNewGeneration = false`.
     *
     * The design says so on purpose: a cancelled or failed turn leaves the Server potentially
     * holding something this device cannot prove, and the permissive reading of "we could not find
     * out" is precisely what buys a second Claude session for a branch that may already have one.
     * Continuing past it is meant to be a *new* branch — a regenerate or a fork.
     *
     * It is recorded here as a finding, not as a defect this layer may fix on its own: changing it
     * means changing what a terminal means, which is the state machine's decision and not a wiring
     * one. It is also why `auto` cannot be turned on until the product owner has confirmed that a
     * closed branch after a cancel is the intended behaviour.
     */
    @Test
    fun `a cancelled or failed turn closes the branch to the next ordinary message`() {
        for (terminal in listOf(
            ClaudePSessionContinuationGate.Terminal.UNPROVEN,
            ClaudePSessionContinuationGate.Terminal.FAILED,
        )) {
            val (base, branch) = stranded()
            val settled = committed(
                ClaudePSessionContinuationGate.settleImmediate(
                    conversation = base,
                    obligation = ClaudePSessionContinuationGate.Obligation.Immediate(
                        assistantId = assistant,
                        branchId = branch,
                        revision = 1L,
                    ),
                    terminal = terminal,
                ),
            )

            // The branch identity is unchanged by a send, which is why the next message lands here.
            assertEquals(branch, branchId(settled.messageNodes))

            val decision = ClaudePSessionContinuationGate.admission(settled, sendCommand(), targetRole = null)
            assertTrue(
                "$terminal should close the branch, got $decision",
                decision is ClaudePSessionContinuationGate.Decision.Refused,
            )
            assertEquals(
                ClaudePSessionContinuationGate.Reason.CONTINUATION_BLOCKED,
                (decision as ClaudePSessionContinuationGate.Decision.Refused).reason,
            )
        }
    }

    /** A completed turn is the one terminal that does **not** close anything. */
    @Test
    fun `a completed turn leaves the branch continuable`() {
        val (base, branch) = stranded()
        val settled = committed(
            ClaudePSessionContinuationGate.settleImmediate(
                conversation = base,
                obligation = ClaudePSessionContinuationGate.Obligation.Immediate(
                    assistantId = assistant,
                    branchId = branch,
                    revision = 1L,
                ),
                terminal = ClaudePSessionContinuationGate.Terminal.SUCCEEDED,
            ),
        )
        assertEquals(
            State.BOUND,
            (resolve(settled, branch) as ClaudePSessionContinuationResolution.Resolved).state,
        )

        val decision = ClaudePSessionContinuationGate.admission(settled, sendCommand(), targetRole = null)
        assertTrue(
            "a bound branch admits the next turn, got $decision",
            decision is ClaudePSessionContinuationGate.Decision.Immediate,
        )
    }

    private fun <T : Any> present(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    private companion object {
        val FIXED_CREATED_AT = kotlinx.datetime.LocalDateTime(2019, 6, 1, 12, 0, 0)
    }
}
