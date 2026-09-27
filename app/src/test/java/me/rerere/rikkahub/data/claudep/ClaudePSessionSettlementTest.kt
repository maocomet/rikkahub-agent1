package me.rerere.rikkahub.data.claudep

import kotlin.uuid.Uuid
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.claudep.ClaudePSessionContinuation
import me.rerere.ai.provider.claudep.ClaudePSessionContinuationResolution
import me.rerere.ai.provider.claudep.ClaudePSessionContinuationState as State
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.service.chat.RawUserContent
import me.rerere.rikkahub.service.chat.SendMessageCommand
import me.rerere.rikkahub.service.chat.SteerCommand
import me.rerere.rikkahub.service.chat.ToolApprovalCommand
import me.rerere.rikkahub.service.chat.ToolDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one place a terminal becomes a durable state.
 *
 * A run ends in more than one place, and the property under test is that they all agree. The
 * failure mode this class exists against is not a wrong enum member in one branch — it is the
 * *absence* of a write: a branch left in `START_IN_FLIGHT` after its generation ended, which the
 * gate then reads as "a model may be running" and refuses forever.
 *
 * So most of these tests assert against a **re-resolution** of the settled graph rather than
 * against the record that was written. A record that is attached but not resolvable is the same as
 * no record at all, and only the resolver knows the difference.
 */
class ClaudePSessionSettlementTest {

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

    /**
     * The graph mid-turn: the barrier is on the anchor and the turn has produced its answer.
     *
     * [barrierRevision] must be the revision the barrier actually carries, because the terminal is
     * written one step past it and a fixture that disagreed with itself would fold as a gap.
     */
    private fun inFlightTurn(barrierRevision: Long = 1L): Pair<Conversation, String> {
        val nodes = listOf(
            node(1, listOf(message(1, MessageRole.USER))),
            node(2, listOf(message(2))),
        )
        val branch = branchId(nodes)
        val carried = conversation(nodes).let { base ->
            base.copy(
                messageNodes = base.messageNodes.mapIndexed { index, node ->
                    if (index != 0) node
                    else node.copy(
                        messages = listOf(
                            node.messages.single().copy(
                                claudePSessionContinuation =
                                    record(State.START_IN_FLIGHT, barrierRevision, branch),
                            ),
                        ),
                    )
                },
            )
        }
        return carried to branch
    }

    private fun resolve(conversation: Conversation, branch: String) =
        ClaudePSessionContinuationResolver.resolve(conversation, branch)

    private fun committed(settlement: ClaudePSessionContinuationGate.Settlement): Conversation {
        assertTrue("expected a commit, got $settlement", settlement is ClaudePSessionContinuationGate.Settlement.Commit)
        return (settlement as ClaudePSessionContinuationGate.Settlement.Commit).conversation
    }

    // ---------------------------------------------------------------------------------------
    // The terminal mapping
    // ---------------------------------------------------------------------------------------

    /**
     * A completed turn is the proof a `mode: "auto"` request gives: the Worker resolved the branch
     * and the session it chose is now this branch's session, so the next turn may send `immediate`
     * again.
     */
    @Test
    fun `a successful immediate turn settles to bound and stays resumable`() {
        val (carried, branch) = inFlightTurn()

        val settled = committed(
            ClaudePSessionContinuationGate.settleImmediate(
                conversation = carried,
                obligation = ClaudePSessionContinuationGate.Obligation.Immediate(assistant, branch, 1L),
                terminal = ClaudePSessionContinuationGate.Terminal.SUCCEEDED,
            ),
        )

        val read = resolve(settled, branch)
        assertEquals(State.BOUND, (read as ClaudePSessionContinuationResolution.Resolved).state)
        assertTrue(read.allowsNewGeneration)
    }

    /**
     * A failure closes the branch, and closing it is absorbing — nothing recovers automatically, so
     * a second generation cannot quietly start on a turn that did not complete.
     */
    @Test
    fun `a failed immediate turn settles to failed-closed and blocks`() {
        val (carried, branch) = inFlightTurn()

        val settled = committed(
            ClaudePSessionContinuationGate.settleImmediate(
                conversation = carried,
                obligation = ClaudePSessionContinuationGate.Obligation.Immediate(assistant, branch, 1L),
                terminal = ClaudePSessionContinuationGate.Terminal.FAILED,
            ),
        )

        val read = resolve(settled, branch)
        assertEquals(State.FAILED_CLOSED, (read as ClaudePSessionContinuationResolution.Resolved).state)
        assertFalse(read.allowsNewGeneration)
    }

    /**
     * A cancellation and a dropped connection are the same durable fact: the outcome cannot be
     * proven, so the branch is not resumable and is not closed either. Carrying no generation id is
     * what keeps `replay` from offering to re-send a bind this run never had.
     */
    @Test
    fun `an unproven immediate turn settles to interrupted and blocks`() {
        val (carried, branch) = inFlightTurn()

        val settled = committed(
            ClaudePSessionContinuationGate.settleImmediate(
                conversation = carried,
                obligation = ClaudePSessionContinuationGate.Obligation.Immediate(assistant, branch, 1L),
                terminal = ClaudePSessionContinuationGate.Terminal.UNPROVEN,
            ),
        )

        val read = resolve(settled, branch) as ClaudePSessionContinuationResolution.Resolved
        assertEquals(State.INTERRUPTED, read.state)
        assertFalse(read.allowsNewGeneration)
        assertNull(read.generationId)
        assertNull(ClaudePSessionContinuationGate.replay(settled))
    }

    /**
     * The three terminals produce three different durable states. Asserted as a set because the
     * failure this guards against is two of them collapsing into one — which is how "cancelled"
     * would come to look like "failed", or worse, like "fine".
     */
    @Test
    fun `no two terminals collapse onto one durable state`() {
        val (carried, branch) = inFlightTurn()
        val states = listOf(
            ClaudePSessionContinuationGate.Terminal.SUCCEEDED,
            ClaudePSessionContinuationGate.Terminal.FAILED,
            ClaudePSessionContinuationGate.Terminal.UNPROVEN,
        ).map { terminal ->
            val settled = committed(
                ClaudePSessionContinuationGate.settleImmediate(
                    carried,
                    ClaudePSessionContinuationGate.Obligation.Immediate(assistant, branch, 1L),
                    terminal,
                ),
            )
            (resolve(settled, branch) as ClaudePSessionContinuationResolution.Resolved).state
        }

        assertEquals(listOf(State.BOUND, State.FAILED_CLOSED, State.INTERRUPTED), states)
    }

    /**
     * A turn whose graph grew nothing has nowhere legal to put a terminal, and the honest answer is
     * "nothing written" rather than a record bolted onto the barrier — which would fold as a
     * revision regression and make the branch unreadable.
     *
     * The branch then stays `START_IN_FLIGHT`, which is closed. That is the fail-closed direction:
     * a turn that cannot be settled must not look resumable.
     */
    @Test
    fun `a failed run with no new message supersedes its own barrier`() {
        val (carried, branch) = barrierOnlyTurn()

        val settled = committed(
            ClaudePSessionContinuationGate.settleImmediate(
                carried,
                ClaudePSessionContinuationGate.Obligation.Immediate(assistant, branch, 1L),
                ClaudePSessionContinuationGate.Terminal.FAILED,
            ),
        )

        val read = resolve(settled, branch) as ClaudePSessionContinuationResolution.Resolved
        assertEquals(State.FAILED_CLOSED, read.state)
        // The terminal keeps the barrier's own revision: the record is superseded in its slot, not
        // written after it. A first turn therefore shows a lone `FAILED_CLOSED(1)`.
        assertEquals(1L, read.revision)
        assertFalse(read.allowsNewGeneration)
        // One record, not two: the barrier was superseded, not shadowed.
        assertEquals(1, settled.messageNodes.size)
    }

    /**
     * The case the table change exists for: a **later** turn on an already-bound branch, ending
     * with nothing to write to. Its barrier sits after the previous turn's `BOUND`, so superseding
     * is the only option — and with the compaction it is legal and contiguous, leaving
     * `BOUND(n) -> FAILED_CLOSED(n+1)`.
     */
    @Test
    fun `a bound branch closes when a later turn ends with no message`() {
        val branch = branchId(emptyList())
        val nodes = listOf(
            node(2, listOf(message(2, continuation = record(State.BOUND, 2, branch)))),
            node(3, listOf(message(3, continuation = record(State.START_IN_FLIGHT, 3, branch)))),
        )

        val settled = committed(
            ClaudePSessionContinuationGate.settleImmediate(
                conversation(nodes),
                ClaudePSessionContinuationGate.Obligation.Immediate(assistant, branch, 3L),
                ClaudePSessionContinuationGate.Terminal.FAILED,
            ),
        )

        val read = resolve(settled, branch) as ClaudePSessionContinuationResolution.Resolved
        assertEquals(State.FAILED_CLOSED, read.state)
        // Contiguous with the bound record it follows: 2, then 3 — never a jump to 4.
        assertEquals(3L, read.revision)
        assertFalse(read.allowsNewGeneration)
    }

    /** The same for a cancellation or a dropped connection. */
    @Test
    fun `a bound branch is interrupted when a later turn ends with no message`() {
        val branch = branchId(emptyList())
        val nodes = listOf(
            node(2, listOf(message(2, continuation = record(State.BOUND, 2, branch)))),
            node(3, listOf(message(3, continuation = record(State.START_IN_FLIGHT, 3, branch)))),
        )

        val settled = committed(
            ClaudePSessionContinuationGate.settleImmediate(
                conversation(nodes),
                ClaudePSessionContinuationGate.Obligation.Immediate(assistant, branch, 3L),
                ClaudePSessionContinuationGate.Terminal.UNPROVEN,
            ),
        )

        val read = resolve(settled, branch) as ClaudePSessionContinuationResolution.Resolved
        assertEquals(State.INTERRUPTED, read.state)
        assertEquals(3L, read.revision)
        assertFalse(read.allowsNewGeneration)
    }

    /**
     * The one thing an unproven outcome must never do. Whatever the graph looks like, a settled
     * `INTERRUPTED` or `FAILED_CLOSED` branch does not read back as resumable — "we could not find
     * out" is not "it is still bound".
     */
    @Test
    fun `no terminal ever settles back to bound`() {
        val (carried, branch) = barrierOnlyTurn()

        for (terminal in listOf(
            ClaudePSessionContinuationGate.Terminal.FAILED,
            ClaudePSessionContinuationGate.Terminal.UNPROVEN,
        )) {
            val settled = committed(
                ClaudePSessionContinuationGate.settleImmediate(
                    carried,
                    ClaudePSessionContinuationGate.Obligation.Immediate(assistant, branch, 1L),
                    terminal,
                ),
            )
            val read = resolve(settled, branch) as ClaudePSessionContinuationResolution.Resolved
            assertNotEquals(State.BOUND, read.state)
            assertFalse(read.allowsNewGeneration)
        }
    }

    /**
     * Cancellation and a dropped connection take the same path, and land on `INTERRUPTED` — which
     * carries no generation id, so `replay` offers nothing for a start that has nothing to re-send.
     */
    @Test
    fun `a cancelled run with no new message supersedes its barrier to interrupted`() {
        val (carried, branch) = barrierOnlyTurn()

        val settled = committed(
            ClaudePSessionContinuationGate.settleImmediate(
                carried,
                ClaudePSessionContinuationGate.Obligation.Immediate(assistant, branch, 1L),
                ClaudePSessionContinuationGate.Terminal.UNPROVEN,
            ),
        )

        val read = resolve(settled, branch) as ClaudePSessionContinuationResolution.Resolved
        assertEquals(State.INTERRUPTED, read.state)
        assertFalse(read.allowsNewGeneration)
        assertNull(ClaudePSessionContinuationGate.replay(settled))
    }

    /**
     * Success has no supersede fallback and must not get one. A turn that produced no answer did
     * not succeed, and writing `BOUND` for it would credit the branch with a binding no model turn
     * ever proved — which the next turn would then send `immediate` on the strength of.
     */
    @Test
    fun `a successful run with no new message refuses rather than faking a bound`() {
        val (carried, branch) = barrierOnlyTurn()

        val settlement = ClaudePSessionContinuationGate.settleImmediate(
            carried,
            ClaudePSessionContinuationGate.Obligation.Immediate(assistant, branch, 1L),
            ClaudePSessionContinuationGate.Terminal.SUCCEEDED,
        )

        assertEquals(
            ClaudePSessionContinuationGate.Settlement.Refused(
                ClaudePSessionContinuationGate.Reason.SUCCESS_WITHOUT_TERMINAL_MESSAGE,
            ),
            settlement,
        )
        // And the barrier is untouched — still in flight, still blocking.
        val read = resolve(carried, branch) as ClaudePSessionContinuationResolution.Resolved
        assertEquals(State.START_IN_FLIGHT, read.state)
    }

    /** Zero matches: the obligation names a barrier the selected path does not carry. */
    @Test
    fun `a barrier that is not on the path refuses`() {
        val (carried, _) = barrierOnlyTurn()
        val branch = branchId(carried.messageNodes)

        val settlement = ClaudePSessionContinuationGate.settleImmediate(
            carried,
            ClaudePSessionContinuationGate.Obligation.Immediate(assistant, branch, 9L),
            ClaudePSessionContinuationGate.Terminal.FAILED,
        )

        assertEquals(
            ClaudePSessionContinuationGate.Settlement.Refused(
                ClaudePSessionContinuationGate.Reason.BARRIER_NOT_UNIQUELY_LOCATED,
            ),
            settlement,
        )
    }

    /** More than one match: the same step recorded twice, which no writer produces. */
    @Test
    fun `a barrier recorded twice on the path refuses`() {
        val (carried, branch) = barrierOnlyTurn()
        val doubled = carried.copy(
            messageNodes = carried.messageNodes + carried.messageNodes.first().copy(id = uid(2, 9)),
        )

        val settlement = ClaudePSessionContinuationGate.settleImmediate(
            doubled,
            ClaudePSessionContinuationGate.Obligation.Immediate(assistant, branch, 1L),
            ClaudePSessionContinuationGate.Terminal.FAILED,
        )

        assertEquals(
            ClaudePSessionContinuationGate.Settlement.Refused(
                ClaudePSessionContinuationGate.Reason.BARRIER_NOT_UNIQUELY_LOCATED,
            ),
            settlement,
        )
    }

    /**
     * The table still has limits, and they are the ones that matter: an illegal *step* is refused
     * here rather than written, because a record no reader can resolve is worse than none.
     *
     * `BOUND -> BIND_PENDING` is the live example — a bound branch does not acquire a bind it never
     * asked for — and the same check is what would refuse a revision gap or a regression.
     */
    @Test
    fun `a supersede the transition table refuses is refused, not written`() {
        val branch = branchId(emptyList())
        val nodes = listOf(
            // A branch that already failed closed, and a barrier after it.
            node(2, listOf(message(2, continuation = record(State.FAILED_CLOSED, 2, branch, )))),
            node(3, listOf(message(3, continuation = record(State.START_IN_FLIGHT, 3, branch)))),
        )

        val settlement = ClaudePSessionContinuationGate.settleImmediate(
            conversation(nodes),
            ClaudePSessionContinuationGate.Obligation.Immediate(assistant, branch, 3L),
            ClaudePSessionContinuationGate.Terminal.FAILED,
        )

        assertEquals(
            ClaudePSessionContinuationGate.Settlement.Refused(
                ClaudePSessionContinuationGate.Reason.SETTLEMENT_NOT_READABLE,
            ),
            settlement,
        )
    }

    /**
     * A general revision gap is still a gap. The compaction does not relax the contiguity rule — it
     * only lets a terminal occupy the slot its own barrier vacated, which keeps the chain
     * contiguous rather than skipping a step.
     */
    @Test
    fun `a supersede that would leave a revision gap is refused`() {
        val branch = branchId(emptyList())
        val nodes = listOf(
            node(2, listOf(message(2, continuation = record(State.BOUND, 1, branch)))),
            // Revision 5 after revision 1: the barrier is real, but the chain it belongs to has a
            // hole in it, so the terminal would land on a path no writer produces.
            node(3, listOf(message(3, continuation = record(State.START_IN_FLIGHT, 5, branch)))),
        )

        assertEquals(
            ClaudePSessionContinuationGate.Settlement.Refused(
                ClaudePSessionContinuationGate.Reason.SETTLEMENT_NOT_READABLE,
            ),
            ClaudePSessionContinuationGate.settleImmediate(
                conversation(nodes),
                ClaudePSessionContinuationGate.Obligation.Immediate(assistant, branch, 5L),
                ClaudePSessionContinuationGate.Terminal.FAILED,
            ),
        )
    }

    /**
     * The entry point every exit calls. With no obligation it reports nothing to write — and that
     * is the **only** thing that member means.
     */
    @Test
    fun `settle reports nothing to write only when the decision carries no obligation`() {
        val (carried, _) = barrierOnlyTurn()

        assertEquals(
            ClaudePSessionContinuationGate.Settlement.NothingToWrite,
            ClaudePSessionContinuationGate.settle(
                carried,
                ClaudePSessionContinuationGate.Decision.NotModelGeneration,
                ClaudePSessionContinuationGate.Terminal.SUCCEEDED,
            ),
        )
    }

    /**
     * A `deferred` obligation reaching the generation-terminal seam is a wiring defect, not a
     * branch state: the branch is settled by the bind, and answering "nothing to write" would
     * silently drop the bind this run owes.
     */
    @Test
    fun `a deferred obligation is refused at the terminal seam, not ignored`() {
        val (carried, _) = barrierOnlyTurn()

        val refused = ClaudePSessionContinuationGate.settle(
            carried,
            ClaudePSessionContinuationGate.Decision.Deferred(
                assistantId = assistant,
                request = me.rerere.ai.provider.claudep.ClaudePSessionBindingRequest(
                    assistantId = assistant,
                    intent = me.rerere.ai.provider.claudep.ClaudePSessionBindingIntent.DEFERRED,
                    branchId = null,
                ),
            ),
            ClaudePSessionContinuationGate.Terminal.SUCCEEDED,
        )

        assertEquals(
            ClaudePSessionContinuationGate.Settlement.Refused(
                ClaudePSessionContinuationGate.Reason.DEFERRED_SETTLES_THROUGH_BIND,
            ),
            refused,
        )
    }

    /**
     * The graph immediately after a barrier was written and before anything answered: one user
     * message carrying `START_IN_FLIGHT`, and nothing else.
     */
    private fun barrierOnlyTurn(barrierRevision: Long = 1L): Pair<Conversation, String> {
        val nodes = listOf(node(1, listOf(message(1, MessageRole.USER))))
        val branch = branchId(nodes)
        val carried = conversation(nodes).copy(
            messageNodes = listOf(
                nodes.single().copy(
                    messages = listOf(
                        nodes.single().messages.single().copy(
                            claudePSessionContinuation =
                                record(State.START_IN_FLIGHT, barrierRevision, branch),
                        ),
                    ),
                ),
            ),
        )
        return carried to branch
    }

    /** The terminal moves the branch forward exactly one step from the barrier. */
    @Test
    fun `the terminal revision follows the barrier revision, not a fixed number`() {
        val (carried, branch) = inFlightTurn(barrierRevision = 6L)

        val settled = committed(
            ClaudePSessionContinuationGate.settleImmediate(
                carried,
                ClaudePSessionContinuationGate.Obligation.Immediate(assistant, branch, 6L),
                ClaudePSessionContinuationGate.Terminal.SUCCEEDED,
            ),
        )

        assertEquals(
            7L,
            (resolve(settled, branch) as ClaudePSessionContinuationResolution.Resolved).revision,
        )
    }

    // ---------------------------------------------------------------------------------------
    // The obligation a decision carries
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a dispatch obligation is derived from the decision, and nothing else is`() {
        val nodes = listOf(node(1, listOf(message(1, MessageRole.USER))))
        val branch = branchId(nodes)

        assertNull(
            ClaudePSessionContinuationGate.Obligation.of(
                ClaudePSessionContinuationGate.Decision.NotModelGeneration,
            ),
        )
        assertNull(
            ClaudePSessionContinuationGate.Obligation.of(
                ClaudePSessionContinuationGate.Decision.Refused(assistant, ClaudePSessionContinuationGate.Reason.BRANCH_MALFORMED),
            ),
        )
        assertEquals(
            ClaudePSessionContinuationGate.Obligation.Immediate(assistant, branch, 3L),
            ClaudePSessionContinuationGate.Obligation.of(
                ClaudePSessionContinuationGate.Decision.Immediate(
                    assistantId = assistant,
                    branchId = branch,
                    revision = 3L,
                    admissionRecord = null,
                    request = me.rerere.ai.provider.claudep.ClaudePSessionBindingRequest(
                        assistantId = assistant,
                        intent = me.rerere.ai.provider.claudep.ClaudePSessionBindingIntent.IMMEDIATE,
                        branchId = branch,
                    ),
                ),
            ),
        )
        assertEquals(
            ClaudePSessionContinuationGate.Obligation.Deferred(assistant),
            ClaudePSessionContinuationGate.Obligation.of(
                ClaudePSessionContinuationGate.Decision.Deferred(
                    assistantId = assistant,
                    request = me.rerere.ai.provider.claudep.ClaudePSessionBindingRequest(
                        assistantId = assistant,
                        intent = me.rerere.ai.provider.claudep.ClaudePSessionBindingIntent.DEFERRED,
                        branchId = null,
                    ),
                ),
            ),
        )
    }

    /** End to end through the gate: a send produces a decision whose obligation settles. */
    @Test
    fun `the obligation of a send settles the barrier that same decision wrote`() {
        val user = message(1, MessageRole.USER)
        val decision = ClaudePSessionContinuationGate.admission(
            conversation = conversation(emptyList()),
            command = SendMessageCommand(RawUserContent(listOf(UIMessagePart.Text("hi")))),
            targetRole = null,
        ) as ClaudePSessionContinuationGate.Decision.Immediate

        val admitted = present(
            ClaudePSessionContinuationGate.appendWithBarrier(
                conversation(emptyList()),
                user,
                present(decision.admissionRecord),
            ),
        )

        val obligation = present(ClaudePSessionContinuationGate.Obligation.of(decision))
            as ClaudePSessionContinuationGate.Obligation.Immediate

        // The turn has to have produced a message for the terminal to have somewhere legal to go;
        // a send that never answered has no terminal to write.
        val answered = admitted.copy(
            messageNodes = admitted.messageNodes + node(2, listOf(message(2))),
        )

        val settled = committed(
            ClaudePSessionContinuationGate.settleImmediate(
                answered,
                obligation,
                ClaudePSessionContinuationGate.Terminal.SUCCEEDED,
            ),
        )

        assertTrue(
            resolve(settled, decision.branchId).allowsNewGeneration,
        )
    }

    /** A command that dispatches nothing produces no obligation, on any of its shapes. */
    @Test
    fun `commands that dispatch no generation carry no obligation`() {
        val nodes = listOf(node(1, listOf(message(1, MessageRole.USER))))

        for (command in listOf(
            SteerCommand(text = "steer"),
            ToolApprovalCommand(toolCallId = "call-1", decision = ToolDecision.Approved),
        )) {
            val decision = ClaudePSessionContinuationGate.admission(conversation(nodes), command, null)
            assertNull(ClaudePSessionContinuationGate.Obligation.of(decision))
        }
    }

    // ---------------------------------------------------------------------------------------
    // The deferred pending write
    // ---------------------------------------------------------------------------------------

    /**
     * The `BIND_PENDING` identity is read from the graph **as it will be committed**. A variant
     * that changes the selection vector changes the digest, and the digest that goes on the wire
     * must be the committed one.
     */
    @Test
    fun `a deferred pending record names the branch the committed variant creates`() {
        // The variant is appended to the assistant node and selected, which moves the identity.
        val nodes = listOf(
            node(1, listOf(message(1, MessageRole.USER))),
            node(2, listOf(message(2), message(3)), selectIndex = 1),
        )
        val committedBranch = branchId(nodes)
        val beforeBranch = branchId(listOf(nodes[0], node(2, listOf(message(2)))))

        val pending = present(
            ClaudePSessionContinuationGate.deferredPending(
                conversation = conversation(nodes),
                assistantId = assistant,
                generationId = "gen-1",
            ),
        )

        assertEquals(committedBranch, pending.branchId)
        assertTrue("the variant must have moved the identity", committedBranch != beforeBranch)
        assertEquals(State.BIND_PENDING, pending.record.state)
        assertEquals("gen-1", pending.record.generationId)
    }

    /**
     * The record and the conversation are returned together so the caller cannot commit one and
     * bind the other. Reading the branch id back out of the committed graph must equal the id the
     * pending record carries.
     */
    @Test
    fun `the pending record resolves once its conversation is committed`() {
        val nodes = listOf(
            node(1, listOf(message(1, MessageRole.USER))),
            node(2, listOf(message(2), message(3)), selectIndex = 1),
        )

        val pending = present(
            ClaudePSessionContinuationGate.deferredPending(
                conversation = conversation(nodes),
                assistantId = assistant,
                generationId = "gen-1",
            ),
        )

        val read = resolve(pending.conversation, pending.branchId)
            as ClaudePSessionContinuationResolution.Resolved
        assertEquals(State.BIND_PENDING, read.state)
        assertEquals("gen-1", read.generationId)
        // A pending bind is not resumable — the Worker holds an uncommitted candidate.
        assertFalse(read.allowsNewGeneration)
    }

    /**
     * A non-null assertion usable as an expression.
     *
     * JUnit's `assertNotNull` returns `Unit`, so `val x = assertNotNull(y)` does not compile — and
     * the interesting failures in this class are exactly the "it returned null" ones, so they need
     * to be assertions rather than `!!`. This keeps the failure message.
     */
    private fun <T : Any> present(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    private companion object {
        val FIXED_CREATED_AT = kotlinx.datetime.LocalDateTime(2019, 6, 1, 12, 0, 0)
    }
}
