package me.rerere.rikkahub.data.claudep

import kotlin.uuid.Uuid
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.claudep.ClaudePSessionBindOutcome
import me.rerere.ai.provider.claudep.ClaudePSessionBindState
import me.rerere.ai.provider.claudep.ClaudePSessionBindingIntent
import me.rerere.ai.provider.claudep.ClaudePSessionContinuation
import me.rerere.ai.provider.claudep.ClaudePSessionContinuationResolution
import me.rerere.ai.provider.claudep.ClaudePSessionContinuationState as State
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.service.chat.RawUserContent
import me.rerere.rikkahub.service.chat.RegenerateCommand
import me.rerere.rikkahub.service.chat.ResumeAfterApprovalCommand
import me.rerere.rikkahub.service.chat.SendMessageCommand
import me.rerere.rikkahub.service.chat.SteerCommand
import me.rerere.rikkahub.service.chat.ToolApprovalCommand
import me.rerere.rikkahub.service.chat.ToolDecision
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate's decision table.
 *
 * Two properties are the reason this class exists, and most of the tests below are one of them:
 *
 * - **No member that permits a dispatch withholds a reason.** A `Refused` is a visible failure; the
 *   answer it replaces — "send the pre-M3 `new` shape" — would silently open a second Claude
 *   session for a branch that may already have one.
 * - **A barrier is a value written inside the caller's transaction, never a side effect.** These
 *   tests never mock a database, because the gate never touches one: it returns the record the
 *   caller must commit, and a commit that does not happen is a dispatch that does not happen.
 */
class ClaudePSessionContinuationGateTest {

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

    private fun record(state: State, revision: Long, generationId: String? = null) =
        ClaudePSessionContinuation(
            assistantId = assistant,
            branchId = branchId(emptyList()),
            revision = revision,
            state = state,
            generationId = generationId,
        )

    private fun sendCommand() = SendMessageCommand(RawUserContent(listOf(UIMessagePart.Text("hi"))))

    private fun regenerateCommand() = RegenerateCommand(
        targetMessageId = uid(1, 1),
        expectedTargetVersion = 0L,
        expectedBranchHeadMessageId = uid(1, 1),
    )

    private fun admission(
        nodes: List<MessageNode>,
        command: me.rerere.rikkahub.service.chat.ChatCommand,
        targetRole: MessageRole? = null,
        anchorOrigin: ClaudePSessionContinuationGate.AnchorOrigin =
            ClaudePSessionContinuationGate.AnchorOrigin.CREATED_BY_ADMISSION,
    ) = ClaudePSessionContinuationGate.admission(
        conversation = conversation(nodes),
        command = command,
        targetRole = targetRole,
        anchorOrigin = anchorOrigin,
    )

    private fun refusalReason(
        nodes: List<MessageNode>,
        command: me.rerere.rikkahub.service.chat.ChatCommand,
        targetRole: MessageRole? = null,
        anchorOrigin: ClaudePSessionContinuationGate.AnchorOrigin =
            ClaudePSessionContinuationGate.AnchorOrigin.CREATED_BY_ADMISSION,
    ): ClaudePSessionContinuationGate.Reason {
        val decision = admission(nodes, command, targetRole, anchorOrigin)
        assertTrue("expected a refusal, got $decision", decision is ClaudePSessionContinuationGate.Decision.Refused)
        return (decision as ClaudePSessionContinuationGate.Decision.Refused).reason
    }

    // ---------------------------------------------------------------------------------------
    // Planner / admission
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a send on a fresh branch dispatches immediately behind a start barrier`() {
        val nodes = listOf(node(1, listOf(message(1, MessageRole.USER))))

        val decision = admission(nodes, sendCommand())

        val immediate = decision as ClaudePSessionContinuationGate.Decision.Immediate
        assertEquals(assistant, immediate.assistantId)
        assertEquals(branchId(nodes), immediate.branchId)
        assertEquals(1L, immediate.revision)
        assertEquals(
            ClaudePSessionContinuation(
                assistantId = assistant,
                branchId = branchId(nodes),
                revision = 1L,
                state = State.START_IN_FLIGHT,
                generationId = null,
            ),
            immediate.admissionRecord,
        )
        assertEquals(ClaudePSessionBindingIntent.IMMEDIATE, immediate.request.intent)
        assertEquals(branchId(nodes), immediate.request.branchId)
    }

    /**
     * A start must not carry a generation id, because a start has no candidate to bind and an id
     * here is exactly what a later reader would mistake for something replayable.
     */
    @Test
    fun `the start barrier never carries a generation id`() {
        val nodes = listOf(node(1, listOf(message(1, MessageRole.USER))))

        val immediate = admission(nodes, sendCommand())
            as ClaudePSessionContinuationGate.Decision.Immediate

        assertNull(requireNotNull(immediate.admissionRecord).generationId)
    }

    @Test
    fun `a send on a bound branch plans the next revision`() {
        val nodes = listOf(
            node(1, listOf(message(1, MessageRole.USER, record(State.START_IN_FLIGHT, 1)))),
            node(2, listOf(message(2, continuation = record(State.BOUND, 2)))),
        )

        val immediate = admission(nodes, sendCommand())
            as ClaudePSessionContinuationGate.Decision.Immediate

        assertEquals(3L, immediate.revision)
        assertEquals(3L, requireNotNull(immediate.admissionRecord).revision)
    }

    /**
     * The planner says `immediate` for a user-target regenerate and the gate refuses it, and the
     * gap between the two is the point of this test.
     *
     * A regenerate's branch anchor is a user message that has been committed for a while, so its
     * source revision is already recorded — in the approval lineage of every command that descends
     * from it. Writing the barrier onto that message would move the revision and make the next
     * child command's admission fail with `COMMAND_BRANCH_ANCHOR_REVISION_CONFLICT`. The gate
     * therefore refuses rather than dispatching a generation whose barrier is not atomic with its
     * admission, which is the state the barrier exists to make impossible.
     */
    @Test
    fun `a user regenerate refuses when the barrier cannot be atomic with its admission`() {
        val nodes = listOf(
            node(1, listOf(message(1, MessageRole.USER))),
            node(2, listOf(message(2))),
        )

        assertEquals(
            ClaudePSessionContinuationGate.Reason.BARRIER_NOT_ATOMIC,
            refusalReason(
                nodes,
                regenerateCommand(),
                MessageRole.USER,
                ClaudePSessionContinuationGate.AnchorOrigin.ALREADY_COMMITTED,
            ),
        )
    }

    @Test
    fun `an assistant regenerate dispatches deferred with no branch id on the request`() {
        val nodes = listOf(
            node(1, listOf(message(1, MessageRole.USER))),
            node(2, listOf(message(2))),
        )

        val deferred = admission(nodes, regenerateCommand(), MessageRole.ASSISTANT)
            as ClaudePSessionContinuationGate.Decision.Deferred

        assertEquals(assistant, deferred.assistantId)
        assertEquals(ClaudePSessionBindingIntent.DEFERRED, deferred.request.intent)
        // §5.3: absent, never empty. An empty string is a present-but-empty identity.
        assertNull(deferred.request.branchId)
    }

    /** A deferred dispatch writes no start: §5.2 stores none for this path. */
    @Test
    fun `a deferred decision carries no admission record`() {
        val nodes = listOf(node(1, listOf(message(1, MessageRole.USER))))

        val decision = admission(nodes, regenerateCommand(), null)

        assertTrue(decision is ClaudePSessionContinuationGate.Decision.Deferred)
    }

    @Test
    fun `an unresolved regenerate target is deferred rather than claimed known`() {
        val nodes = listOf(node(1, listOf(message(1, MessageRole.USER))))

        val decision = admission(nodes, regenerateCommand(), null)

        assertTrue(decision is ClaudePSessionContinuationGate.Decision.Deferred)
    }

    /**
     * The approval resume is the one immediate dispatch that writes no new barrier, because it is
     * the same generation continuing. Writing a second `START_IN_FLIGHT` is not a transition the
     * table has, and the request must name the same branch so the Worker resumes what it started.
     */
    @Test
    fun `a replayed approval continues the in-flight generation behind the existing barrier`() {
        val nodes = listOf(
            node(1, listOf(message(1, MessageRole.USER, record(State.START_IN_FLIGHT, 1)))),
        )

        val immediate = admission(nodes, ResumeAfterApprovalCommand)
            as ClaudePSessionContinuationGate.Decision.Immediate

        assertNull("a continuation must not write a second start", immediate.admissionRecord)
        assertEquals(1L, immediate.revision)
        assertEquals(branchId(nodes), immediate.branchId)
        assertEquals(ClaudePSessionBindingIntent.IMMEDIATE, immediate.request.intent)
    }

    /**
     * An approval whose branch has already settled is not a continuation of anything. Reading the
     * command type alone would dispatch a fresh generation here, which is why the decision is read
     * out of the graph.
     */
    @Test
    fun `an approval resume on a settled branch refuses`() {
        val nodes = listOf(
            node(1, listOf(message(1, MessageRole.USER, record(State.START_IN_FLIGHT, 1)))),
            node(2, listOf(message(2, continuation = record(State.BOUND, 2)))),
        )

        assertEquals(
            ClaudePSessionContinuationGate.Reason.CONTINUATION_BLOCKED,
            refusalReason(nodes, ResumeAfterApprovalCommand),
        )
    }

    @Test
    fun `an approval resume on a branch with no history refuses`() {
        val nodes = listOf(node(1, listOf(message(1, MessageRole.USER))))

        assertEquals(
            ClaudePSessionContinuationGate.Reason.CONTINUATION_BLOCKED,
            refusalReason(nodes, ResumeAfterApprovalCommand),
        )
    }

    /** The approval decision itself dispatches nothing; the resume that follows is its own command. */
    @Test
    fun `an in-flight approval decision produces no plan`() {
        val nodes = listOf(node(1, listOf(message(1, MessageRole.USER))))

        val decision = admission(
            nodes,
            ToolApprovalCommand(toolCallId = "call-1", decision = ToolDecision.Approved),
        )

        assertEquals(ClaudePSessionContinuationGate.Decision.NotModelGeneration, decision)
    }

    @Test
    fun `commands that dispatch no generation produce no plan`() {
        val nodes = listOf(node(1, listOf(message(1, MessageRole.USER))))

        assertEquals(
            ClaudePSessionContinuationGate.Decision.NotModelGeneration,
            admission(nodes, SteerCommand(text = "steer")),
        )
    }

    /**
     * The three states that describe a branch something may already be happening on. Each must stop
     * a dispatch, and each must stop it for its own reason rather than by collapsing into one.
     */
    @Test
    fun `in-flight, pending and closed branches all block a dispatch`() {
        val cases = mapOf(
            listOf(node(1, listOf(message(1, MessageRole.USER, record(State.START_IN_FLIGHT, 1))))) to
                State.START_IN_FLIGHT,
            listOf(node(1, listOf(message(1, continuation = record(State.BIND_PENDING, 1, "gen-1"))))) to
                State.BIND_PENDING,
            listOf(node(1, listOf(message(1, continuation = record(State.FAILED_CLOSED, 1))))) to
                State.FAILED_CLOSED,
        )

        for ((nodes, state) in cases) {
            assertEquals(
                "state $state must block",
                ClaudePSessionContinuationGate.Reason.CONTINUATION_BLOCKED,
                refusalReason(nodes, sendCommand()),
            )
        }
    }

    /**
     * A refusal is a decision the caller cannot dispatch on, so it must not carry a request or a
     * record that a careless caller could use anyway. The type makes that structural: `Refused` has
     * neither field, and this test is what would fail if one were added.
     */
    @Test
    fun `a refusal carries nothing a caller could dispatch with`() {
        val nodes = listOf(
            node(1, listOf(message(1, MessageRole.USER, record(State.START_IN_FLIGHT, 1)))),
        )

        val decision = admission(nodes, sendCommand())

        assertTrue(decision is ClaudePSessionContinuationGate.Decision.Refused)
        assertEquals(assistant, decision.assistantId)
    }

    @Test
    fun `a contradictory branch refuses as a conflict`() {
        val nodes = listOf(
            node(1, listOf(message(1, continuation = record(State.BOUND, 1)))),
            node(2, listOf(message(2, continuation = record(State.BOUND, 2)))),
        )

        assertEquals(
            ClaudePSessionContinuationGate.Reason.CONTINUATION_CONFLICTED,
            refusalReason(nodes, sendCommand()),
        )
    }

    @Test
    fun `a graph that cannot be described refuses`() {
        assertEquals(
            ClaudePSessionContinuationGate.Reason.BRANCH_NOT_DESCRIBABLE,
            refusalReason(listOf(node(1, emptyList())), sendCommand()),
        )
    }

    // ---------------------------------------------------------------------------------------
    // Immediate: the barrier and the settle
    // ---------------------------------------------------------------------------------------

    /**
     * The whole immediate turn, end to end at the value level: the barrier goes onto the message
     * the admission transaction creates, and the terminal supersedes it with `BOUND` at the next
     * revision. The fold then reads back a proven branch, which is what the next turn needs.
     */
    @Test
    fun `an absent branch runs start then bound and reads back as resumable`() {
        val user = message(1, MessageRole.USER)
        val immediate = admission(listOf(node(1, listOf(user))), sendCommand())
            as ClaudePSessionContinuationGate.Decision.Immediate

        // The admission transaction appends the anchor *carrying* the barrier. Nothing is written
        // onto the empty graph it started from.
        val admitted = requireNotNull(
            ClaudePSessionContinuationGate.appendWithBarrier(
                conversation(emptyList()),
                user,
                requireNotNull(immediate.admissionRecord),
            ),
        )
        val branch = branchId(admitted.messageNodes)

        // The graph now carries the start on the message the admission created.
        val inFlight = ClaudePSessionContinuationResolver.resolve(admitted, branch)
        assertEquals(State.START_IN_FLIGHT, (inFlight as ClaudePSessionContinuationResolution.Resolved).state)
        assertFalse(inFlight.allowsNewGeneration)

        val withAnswer = admitted.copy(
            messageNodes = admitted.messageNodes + node(2, listOf(message(2))),
        )
        val settled = requireNotNull(
            ClaudePSessionContinuationGate.attach(
                withAnswer,
                requireNotNull(
                    ClaudePSessionContinuationGate.terminalTarget(withAnswer, assistant, branch),
                ),
                ClaudePSessionContinuationGate.immediateTerminal(
                    assistantId = assistant,
                    branchId = branch,
                    boundRevision = immediate.revision + 1L,
                    terminal = ClaudePSessionContinuationGate.Terminal.SUCCEEDED,
                ),
            ),
        )

        val bound = ClaudePSessionContinuationResolver.resolve(settled, branch)
        assertEquals(State.BOUND, (bound as ClaudePSessionContinuationResolution.Resolved).state)
        assertTrue(bound.allowsNewGeneration)
    }

    /**
     * A successful terminal that does not commit must not leave `BOUND` behind. Here the write is
     * aimed at a message the graph does not contain — the rollback case — and the answer is `null`
     * rather than an unchanged conversation, because "I could not write it" and "I wrote it" must
     * not read the same.
     */
    @Test
    fun `a terminal against a message the graph does not hold does not leave a bound`() {
        val user = message(1, MessageRole.USER, record(State.START_IN_FLIGHT, 1))
        val nodes = listOf(node(1, listOf(user)))

        val attempted = ClaudePSessionContinuationGate.attach(
            conversation(nodes),
            uid(1, 99).toString(),
            ClaudePSessionContinuationGate.immediateTerminal(
                assistantId = assistant,
                branchId = branchId(nodes),
                boundRevision = 2L,
                terminal = ClaudePSessionContinuationGate.Terminal.SUCCEEDED,
            ),
        )

        assertNull(attempted)
        // And the un-written graph still resolves to the barrier, not to a binding.
        assertEquals(
            State.START_IN_FLIGHT,
            (
                ClaudePSessionContinuationResolver.resolve(conversation(nodes), branchId(nodes))
                    as ClaudePSessionContinuationResolution.Resolved
                ).state,
        )
    }

    /**
     * The second turn's barrier lands on the *new* anchor, not on the first turn's. Putting it back
     * on the earlier message would fold as a revision regression — the path is read in node order —
     * and the branch would become unreadable at exactly the moment it was being continued.
     */
    @Test
    fun `a second turn on a bound branch appends its barrier and reads back bound`() {
        val nodes = listOf(
            node(1, listOf(message(1, MessageRole.USER, record(State.START_IN_FLIGHT, 1)))),
            node(2, listOf(message(2, continuation = record(State.BOUND, 2)))),
        )
        val branch = branchId(nodes)
        val immediate = admission(nodes, sendCommand())
            as ClaudePSessionContinuationGate.Decision.Immediate
        assertEquals(3L, immediate.revision)

        val nextUser = message(3, MessageRole.USER)
        val next = requireNotNull(
            ClaudePSessionContinuationGate.appendWithBarrier(
                conversation(nodes),
                nextUser,
                requireNotNull(immediate.admissionRecord),
            ),
        )
        assertEquals(
            State.START_IN_FLIGHT,
            (
                ClaudePSessionContinuationResolver.resolve(next, branch)
                    as ClaudePSessionContinuationResolution.Resolved
                ).state,
        )

        // The terminal lands on the message the turn produced, not back on the barrier.
        val withAnswer = next.copy(
            messageNodes = next.messageNodes + node(4, listOf(message(4))),
        )
        val target = requireNotNull(
            ClaudePSessionContinuationGate.terminalTarget(withAnswer, assistant, branch),
        )
        assertEquals(uid(1, 4).toString(), target)

        val settled = requireNotNull(
            ClaudePSessionContinuationGate.attach(
                withAnswer,
                target,
                ClaudePSessionContinuationGate.immediateTerminal(
                    assistantId = assistant,
                    branchId = branch,
                    boundRevision = immediate.revision + 1L,
                    terminal = ClaudePSessionContinuationGate.Terminal.SUCCEEDED,
                ),
            ),
        )

        val read = ClaudePSessionContinuationResolver.resolve(settled, branch)
        assertEquals(State.BOUND, (read as ClaudePSessionContinuationResolution.Resolved).state)
        assertEquals(4L, read.revision)
    }

    /**
     * The terminal target is the newest message the branch gained, which is what keeps the fold
     * ordered. `null` is the rollback case: the graph grew nothing for this branch, so there is
     * nothing a settled terminal could be about.
     */
    @Test
    fun `a branch that gained no message offers no terminal target`() {
        val nodes = listOf(
            node(1, listOf(message(1, MessageRole.USER, record(State.START_IN_FLIGHT, 1)))),
        )

        assertNull(
            ClaudePSessionContinuationGate.terminalTarget(
                conversation(nodes),
                assistant,
                branchId(nodes),
            ),
        )
    }

    /** A new turn's terminal must never be written back over the barrier that opened it. */
    @Test
    fun `the terminal target skips every message that already carries the branch record`() {
        val nodes = listOf(
            node(1, listOf(message(1, MessageRole.USER, record(State.START_IN_FLIGHT, 1)))),
            node(2, listOf(message(2))),
        )

        assertEquals(
            uid(1, 2).toString(),
            ClaudePSessionContinuationGate.terminalTarget(
                conversation(nodes),
                assistant,
                branchId(nodes),
            ),
        )
    }

    /**
     * Failure, cancellation and a dropped connection are three different facts and they must not
     * collapse into one. Only the last is allowed to look recoverable, and only when it descends
     * from a bind.
     */
    @Test
    fun `terminal outcomes map onto three distinct durable states`() {
        val branch = branchId(emptyList())

        val failed = ClaudePSessionContinuationGate.immediateTerminal(
            assistant,
            branch,
            2L,
            ClaudePSessionContinuationGate.Terminal.FAILED,
        )
        val unproven = ClaudePSessionContinuationGate.immediateTerminal(
            assistant,
            branch,
            2L,
            ClaudePSessionContinuationGate.Terminal.UNPROVEN,
        )

        assertEquals(State.FAILED_CLOSED, failed.state)
        assertEquals(State.INTERRUPTED, unproven.state)
        // An interrupted *start* has nothing to replay, so it carries no identity — which is what
        // makes `replay` fail closed on it.
        assertNull(unproven.generationId)
    }

    /**
     * A restart must not read a settled branch as a fresh one. The check is a real JSON round trip
     * through the exact codec the message column uses, not a copy of the object.
     */
    @Test
    fun `a settled branch survives a storage round trip and does not decay to absent`() {
        val user = message(1, MessageRole.USER)
        val admitted = requireNotNull(
            ClaudePSessionContinuationGate.appendWithBarrier(
                conversation(emptyList()),
                user,
                requireNotNull(
                    (admission(listOf(node(1, listOf(user))), sendCommand())
                        as ClaudePSessionContinuationGate.Decision.Immediate).admissionRecord,
                ),
            ),
        )

        val stored = JsonInstant.decodeFromString<Conversation>(JsonInstant.encodeToString(admitted))

        val read = ClaudePSessionContinuationResolver.resolve(stored, branchId(admitted.messageNodes))
        assertEquals(
            State.START_IN_FLIGHT,
            (read as ClaudePSessionContinuationResolution.Resolved).state,
        )
        assertFalse(read.allowsNewGeneration)
    }

    // ---------------------------------------------------------------------------------------
    // Deferred: the pending record and the bind settle
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a pending record carries the generation id exactly once`() {
        val pending = ClaudePSessionContinuationGate.pendingRecord(
            assistantId = assistant,
            branchId = branchId(emptyList()),
            revision = 1L,
            generationId = "gen-deferred-1",
        )

        assertEquals(State.BIND_PENDING, pending.state)
        assertEquals("gen-deferred-1", pending.generationId)
        assertEquals(1L, pending.revision)
    }

    /**
     * `BIND_PENDING -> BOUND` supersedes the record in its own slot rather than advancing past it:
     * §5.2 stores no start for a deferred path, so the branch's path shows one record either way.
     */
    @Test
    fun `a settled deferred bind supersedes the pending record in place and reads back`() {
        val pending = ClaudePSessionContinuationGate.pendingRecord(
            assistantId = assistant,
            branchId = branchId(emptyList()),
            revision = 1L,
            generationId = "gen-1",
        )
        val variant = message(1, continuation = pending)
        val nodes = listOf(node(1, listOf(variant)))

        val settled = requireNotNull(
            ClaudePSessionContinuationGate.attach(
                conversation(nodes),
                uid(1, 1).toString(),
                ClaudePSessionContinuationGate.deferredSettlement(
                    assistantId = assistant,
                    branchId = branchId(nodes),
                    revision = 1L,
                    generationId = "gen-1",
                    outcome = ClaudePSessionBindOutcome.Bound,
                ),
            ),
        )

        val read = ClaudePSessionContinuationResolver.resolve(settled, branchId(nodes))
        assertEquals(State.BOUND, (read as ClaudePSessionContinuationResolution.Resolved).state)
        assertTrue(read.allowsNewGeneration)
        // One record, not two: the pending one was replaced.
        assertEquals(1, settled.messageNodes.single().messages.size)
    }

    @Test
    fun `every bind outcome maps onto its own durable state`() {
        val branch = branchId(emptyList())
        fun settle(outcome: ClaudePSessionBindOutcome) =
            ClaudePSessionContinuationGate.deferredSettlement(
                assistantId = assistant,
                branchId = branch,
                revision = 2L,
                generationId = "gen-1",
                outcome = outcome,
            )

        assertEquals(State.BOUND, settle(ClaudePSessionBindOutcome.Bound).state)
        assertEquals(State.BOUND, settle(ClaudePSessionBindOutcome.AlreadyBound).state)
        assertEquals(
            State.FAILED_CLOSED,
            settle(ClaudePSessionBindOutcome.Refused(ClaudePSessionBindState.CONFLICT)).state,
        )
        assertEquals(
            State.FAILED_CLOSED,
            settle(ClaudePSessionBindOutcome.Refused(ClaudePSessionBindState.CANDIDATE_UNAVAILABLE)).state,
        )
        assertEquals(
            State.FAILED_CLOSED,
            settle(ClaudePSessionBindOutcome.Refused(ClaudePSessionBindState.REFUSED)).state,
        )
        // An answer naming a different generation is not an answer about this one.
        assertEquals(State.FAILED_CLOSED, settle(ClaudePSessionBindOutcome.Malformed).state)
        // Not a failure: the Server may have applied the bind before the socket died.
        assertEquals(State.INTERRUPTED, settle(ClaudePSessionBindOutcome.Unproven).state)
    }

    /** A bound branch has no outstanding bind, so it must not keep an id a reader would replay. */
    @Test
    fun `a settled bind drops the generation id`() {
        val settled = ClaudePSessionContinuationGate.deferredSettlement(
            assistantId = assistant,
            branchId = branchId(emptyList()),
            revision = 2L,
            generationId = "gen-1",
            outcome = ClaudePSessionBindOutcome.Bound,
        )

        assertNull(settled.generationId)
    }

    /** An unproven bind keeps the identity, because that identity is the whole of a replay. */
    @Test
    fun `an unproven bind keeps the generation id for the replay`() {
        val settled = ClaudePSessionContinuationGate.deferredSettlement(
            assistantId = assistant,
            branchId = branchId(emptyList()),
            revision = 2L,
            generationId = "gen-1",
            outcome = ClaudePSessionBindOutcome.Unproven,
        )

        assertEquals("gen-1", settled.generationId)
    }

    // ---------------------------------------------------------------------------------------
    // Replay
    // ---------------------------------------------------------------------------------------

    @Test
    fun `an interrupted bind offers a replay that reuses its own identity`() {
        val nodes = listOf(
            node(1, listOf(message(1, continuation = record(State.INTERRUPTED, 5, "gen-7")))),
        )

        val owed = requireNotNull(ClaudePSessionContinuationGate.replay(conversation(nodes)))

        assertEquals("gen-7", owed.generationId)
        assertEquals(branchId(nodes), owed.branchId)
        assertEquals(5L, owed.revision)
        assertEquals(6L, owed.pendingRevision)
        assertEquals(7L, owed.boundRevision)
        assertEquals("gen-7", owed.pendingRecord().generationId)
    }

    /**
     * An interrupted *start* is the permissive reading this refuses. It carries no generation id,
     * so there is nothing to re-send, and a branch that looked replayable here would be one that
     * quietly bought a second Claude session.
     */
    @Test
    fun `an interrupted start offers no replay and stays closed`() {
        val nodes = listOf(
            node(1, listOf(message(1, MessageRole.USER, record(State.INTERRUPTED, 5)))),
        )

        assertNull(ClaudePSessionContinuationGate.replay(conversation(nodes)))
    }

    @Test
    fun `a bound branch offers no replay`() {
        val nodes = listOf(
            node(1, listOf(message(1, continuation = record(State.BOUND, 2)))),
        )

        assertNull(ClaudePSessionContinuationGate.replay(conversation(nodes)))
    }

    /**
     * The next user action drives the replay: one bind, with the persisted identity, and the
     * generation count stays zero until the answer proves a binding.
     */
    @Test
    fun `a replay writes pending, sends exactly one identical bind, and settles to bound`() = kotlinx.coroutines.runBlocking {
        val nodes = listOf(
            node(1, listOf(message(1, continuation = record(State.INTERRUPTED, 5, "gen-7")))),
        )
        val written = ArrayList<ClaudePSessionContinuation>()
        val binds = ArrayList<Triple<String, String, String>>()

        val outcome = ClaudePSessionContinuationGate.replayBeforeGeneration(
            conversation = conversation(nodes),
            writeRecord = { written += it; true },
            bind = { generationId, branchId, assistantId ->
                binds += Triple(generationId, branchId, assistantId)
                ClaudePSessionBindOutcome.Bound
            },
        )

        assertEquals(ClaudePSessionContinuationGate.ReplayOutcome.Bound, outcome)
        assertEquals(1, binds.size)
        assertEquals("gen-7", binds.single().first)
        assertEquals(branchId(nodes), binds.single().second)
        assertEquals(assistant, binds.single().third)
        assertEquals(listOf(State.BIND_PENDING, State.BOUND), written.map { it.state })
        assertEquals(listOf(6L, 7L), written.map { it.revision })
    }

    /** A rollback before the send is a replay that never happened on the wire. */
    @Test
    fun `a replay whose pending record does not commit sends nothing`() = kotlinx.coroutines.runBlocking {
        val nodes = listOf(
            node(1, listOf(message(1, continuation = record(State.INTERRUPTED, 5, "gen-7")))),
        )
        var binds = 0

        val outcome = ClaudePSessionContinuationGate.replayBeforeGeneration(
            conversation = conversation(nodes),
            writeRecord = { false },
            bind = { _, _, _ -> binds++; ClaudePSessionBindOutcome.Bound },
        )

        assertEquals(0, binds)
        assertEquals(
            ClaudePSessionContinuationGate.ReplayOutcome.Unproven(
                ClaudePSessionContinuationGate.ReplayFailure.PENDING_NOT_PERSISTED,
            ),
            outcome,
        )
    }

    @Test
    fun `an unproven replay answer sends nothing further and stays unproven`() = kotlinx.coroutines.runBlocking {
        val nodes = listOf(
            node(1, listOf(message(1, continuation = record(State.INTERRUPTED, 5, "gen-7")))),
        )
        val written = ArrayList<ClaudePSessionContinuation>()
        var binds = 0

        val outcome = ClaudePSessionContinuationGate.replayBeforeGeneration(
            conversation = conversation(nodes),
            writeRecord = { written += it; true },
            bind = { _, _, _ -> binds++; ClaudePSessionBindOutcome.Unproven },
        )

        assertEquals(1, binds)
        assertEquals(
            ClaudePSessionContinuationGate.ReplayOutcome.Unproven(
                ClaudePSessionContinuationGate.ReplayFailure.OUTCOME_UNPROVEN,
            ),
            outcome,
        )
        // The settle writes `INTERRUPTED` again with the same id, so a later action replays it.
        assertEquals(State.INTERRUPTED, written.last().state)
        assertEquals("gen-7", written.last().generationId)
    }

    @Test
    fun `a refused replay closes the branch and sends nothing further`() = kotlinx.coroutines.runBlocking {
        val nodes = listOf(
            node(1, listOf(message(1, continuation = record(State.INTERRUPTED, 5, "gen-7")))),
        )
        var binds = 0

        val outcome = ClaudePSessionContinuationGate.replayBeforeGeneration(
            conversation = conversation(nodes),
            writeRecord = { true },
            bind = { _, _, _ ->
                binds++
                ClaudePSessionBindOutcome.Refused(ClaudePSessionBindState.CANDIDATE_UNAVAILABLE)
            },
        )

        assertEquals(1, binds)
        assertEquals(
            ClaudePSessionContinuationGate.ReplayOutcome.Closed(State.FAILED_CLOSED),
            outcome,
        )
    }

    /** A branch with nothing to replay must let its caller proceed without sending anything. */
    @Test
    fun `a branch with nothing to replay sends no bind`() = kotlinx.coroutines.runBlocking {
        val nodes = listOf(
            node(1, listOf(message(1, continuation = record(State.BOUND, 2)))),
        )
        var binds = 0

        val outcome = ClaudePSessionContinuationGate.replayBeforeGeneration(
            conversation = conversation(nodes),
            writeRecord = { true },
            bind = { _, _, _ -> binds++; ClaudePSessionBindOutcome.Bound },
        )

        assertEquals(0, binds)
        assertEquals(ClaudePSessionContinuationGate.ReplayOutcome.NoReplayNeeded, outcome)
    }

    /**
     * A settled bind that cannot be recorded leaves the branch unproven from here, and the caller
     * must not dispatch on it — the Server may hold a binding this device has no record of.
     */
    @Test
    fun `a replay whose settlement does not commit stays unproven`() = kotlinx.coroutines.runBlocking {
        val nodes = listOf(
            node(1, listOf(message(1, continuation = record(State.INTERRUPTED, 5, "gen-7")))),
        )
        var writes = 0

        val outcome = ClaudePSessionContinuationGate.replayBeforeGeneration(
            conversation = conversation(nodes),
            writeRecord = { writes++; writes == 1 },
            bind = { _, _, _ -> ClaudePSessionBindOutcome.Bound },
        )

        assertEquals(
            ClaudePSessionContinuationGate.ReplayOutcome.Unproven(
                ClaudePSessionContinuationGate.ReplayFailure.SETTLEMENT_NOT_PERSISTED,
            ),
            outcome,
        )
    }

    // ---------------------------------------------------------------------------------------
    // The barrier writer
    // ---------------------------------------------------------------------------------------

    /**
     * The barrier is created with the message rather than written onto it afterwards: a message
     * inserted carrying the record has revision one and no earlier revision to invalidate, which is
     * what keeps an approval lineage's recorded anchor revision valid.
     */
    @Test
    fun `the barrier is inserted with the message, never bolted onto an existing one`() {
        val user = message(1, MessageRole.USER)
        val empty = conversation(listOf(node(1, emptyList()))).copy(messageNodes = emptyList())

        val appended = ClaudePSessionContinuationGate.appendWithBarrier(
            empty,
            user,
            record(State.START_IN_FLIGHT, 1),
        )

        assertNotNull(appended)
        assertEquals(State.START_IN_FLIGHT, appended!!.messageNodes.single().messages.single().claudePSessionContinuation?.state)
    }

    /**
     * A pre-existing identity is not overwritten. Either it already carries the record — the same
     * command admitted twice — or it carries something else, and in both cases the caller must read
     * what is there instead of having a fact it did not write quietly replaced.
     */
    @Test
    fun `a barrier is not written onto a message the graph already holds`() {
        val user = message(1, MessageRole.USER)
        val existing = conversation(listOf(node(1, listOf(user))))

        val attempted = ClaudePSessionContinuationGate.appendWithBarrier(
            existing,
            user,
            record(State.START_IN_FLIGHT, 1),
        )

        assertNull(attempted)
    }

    /** The record is redacted, so a log line can correlate two of them without disclosing either. */
    @Test
    fun `a continuation record does not print its identities`() {
        val text = ClaudePSessionContinuationGate
            .pendingRecord(assistant, branchId(emptyList()), 1L, "gen-secret")
            .toString()

        assertFalse(text.contains(assistant))
        assertFalse(text.contains(branchId(emptyList())))
        assertFalse(text.contains("gen-secret"))
    }
}
