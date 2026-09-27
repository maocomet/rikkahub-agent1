package me.rerere.rikkahub.data.claudep

import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.claudep.ClaudePConnectionEpoch
import me.rerere.ai.provider.claudep.ClaudePSessionBindOutcome
import me.rerere.ai.provider.claudep.ClaudePSessionBindState
import me.rerere.ai.provider.claudep.ClaudePSessionContinuation
import me.rerere.ai.provider.claudep.ClaudePSessionContinuationResolution
import me.rerere.ai.provider.claudep.ClaudePSessionContinuationState as State
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user-triggered bind replay, and the once-per-connection rule that bounds it.
 *
 * Two properties carry the design. A replay must **never** start a generation — it is bookkeeping
 * for a generation that already ran — and it must be attemptable exactly once per connection, while
 * remaining attemptable again on a *new* one. Both are asserted here rather than assumed, because a
 * recovery that fires twice is a repeat against an endpoint that owes it nothing, and one that
 * never fires again leaves a branch closed for no reason.
 */
class ClaudePSessionReplayTest {

    private val assistant = "00000000-0000-0000-0000-0000000000aa"
    private val assistantUuid = Uuid.parse(assistant)
    private val generationId = "gen-bound-1"

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

    private fun branchId(nodes: List<MessageNode>): String =
        (ClaudePSessionBranchPlanner.branchIdOf(nodes)
            as ClaudePSessionBranchPlanner.Branch.Known).id

    /** The shape a dropped bind leaves: `INTERRUPTED` on the variant, carrying its generation id. */
    private fun interrupted(): Pair<Conversation, String> {
        val nodes = listOf(
            node(1, listOf(message(1, MessageRole.USER))),
            node(2, listOf(message(2))),
        )
        val branch = branchId(nodes)
        val carried = nodes.mapIndexed { index, node ->
            if (index != 1) {
                node
            } else {
                node.copy(
                    messages = listOf(
                        node.messages.single().copy(
                            claudePSessionContinuation = ClaudePSessionContinuation(
                                assistantId = assistant,
                                branchId = branch,
                                revision = 2L,
                                state = State.INTERRUPTED,
                                generationId = generationId,
                            ),
                        ),
                    ),
                )
            }
        }
        return Conversation(
            id = uid(3, 1),
            assistantId = assistantUuid,
            messageNodes = carried,
        ) to branch
    }

    private fun resolve(conversation: Conversation, branch: String) =
        ClaudePSessionContinuationResolver.resolve(conversation, branch)

    // ---------------------------------------------------------------------------------------
    // What a replay is owed, and what it is not
    // ---------------------------------------------------------------------------------------

    /** The owed replay names the persisted identity and the slot it must supersede. */
    @Test
    fun `an interrupted bind is replayable and carries its own slot`() {
        val (conversation, _) = interrupted()

        val owed = requireNotNull(ClaudePSessionContinuationGate.replay(conversation))

        assertEquals(generationId, owed.generationId)
        assertEquals(2L, owed.revision)
        assertEquals(uid(1, 2).toString(), owed.messageId)
    }

    /**
     * An interrupted **start** is not replayable: it carries no generation id, because there is no
     * candidate to bind. Reading the state alone would make a dropped start look recoverable.
     */
    @Test
    fun `an interrupted start is not replayable`() {
        val nodes = listOf(
            node(1, listOf(message(1, MessageRole.USER))),
            node(2, listOf(message(2))),
        )
        val branch = branchId(nodes)
        val carried = Conversation(
            id = uid(3, 1),
            assistantId = assistantUuid,
            messageNodes = listOf(
                node(
                    1,
                    listOf(
                        message(
                            1,
                            MessageRole.USER,
                            ClaudePSessionContinuation(
                                assistantId = assistant,
                                branchId = branch,
                                revision = 1L,
                                state = State.START_IN_FLIGHT,
                                generationId = null,
                            ),
                        ),
                    ),
                ),
                node(2, listOf(message(2))),
            ),
        )

        assertNull(ClaudePSessionContinuationGate.replay(carried))
    }

    /** Only `INTERRUPTED` owes a replay. A settled or closed branch owes nothing. */
    @Test
    fun `only an interrupted bind is replayable`() {
        for (state in listOf(State.BOUND, State.FAILED_CLOSED, State.BIND_PENDING)) {
            val nodes = listOf(
                node(1, listOf(message(1, MessageRole.USER))),
                node(2, listOf(message(2))),
            )
            val branch = branchId(nodes)
            val carried = Conversation(
                id = uid(3, 1),
                assistantId = assistantUuid,
                messageNodes = listOf(
                    node(1, listOf(message(1, MessageRole.USER))),
                    node(
                        2,
                        listOf(
                            message(
                                2,
                                continuation = ClaudePSessionContinuation(
                                    assistantId = assistant,
                                    branchId = branch,
                                    revision = 2L,
                                    state = state,
                                    generationId = if (state == State.BIND_PENDING) {
                                        generationId
                                    } else {
                                        null
                                    },
                                ),
                            ),
                        ),
                    ),
                ),
            )

            assertNull("$state owes no replay", ClaudePSessionContinuationGate.replay(carried))
        }
    }

    // ---------------------------------------------------------------------------------------
    // The flow
    // ---------------------------------------------------------------------------------------

    private class ReplayRun(
        val outcome: ClaudePSessionContinuationGate.ReplayOutcome,
        val conversation: Conversation,
        val bindCalls: List<Triple<String, String, String>>,
    )

    private suspend fun runReplay(
        bindOutcome: ClaudePSessionBindOutcome,
        conversation: Conversation,
        owed: ClaudePSessionContinuationGate.Replay,
    ): ReplayRun {
        var current = conversation
        val binds = mutableListOf<Triple<String, String, String>>()

        val outcome = ClaudePSessionContinuationGate.replayBeforeGeneration(
            conversation = conversation,
            writeRecord = { record ->
                when (
                    val settlement = ClaudePSessionContinuationGate
                        .settleReplay(current, owed, record)
                ) {
                    is ClaudePSessionContinuationGate.Settlement.Commit -> {
                        current = settlement.conversation
                        true
                    }

                    else -> false
                }
            },
            bind = { generationId, branchId, assistantId ->
                binds += Triple(generationId, branchId, assistantId)
                bindOutcome
            },
        )
        return ReplayRun(outcome, current, binds)
    }

    /**
     * A replay sends **the persisted identity, exactly once**, and landing on `BOUND` is what makes
     * the branch resumable again.
     */
    @Test
    fun `a replay re-sends the persisted identity once and binds the branch`() = kotlinx.coroutines.runBlocking {
        val (conversation, branch) = interrupted()
        val owed = requireNotNull(ClaudePSessionContinuationGate.replay(conversation))

        val run = runReplay(ClaudePSessionBindOutcome.Bound, conversation, owed)

        assertEquals(ClaudePSessionContinuationGate.ReplayOutcome.Bound, run.outcome)
        assertEquals(1, run.bindCalls.size)
        assertEquals(Triple(generationId, branch, assistant), run.bindCalls.single())

        val read = resolve(run.conversation, branch) as ClaudePSessionContinuationResolution.Resolved
        assertEquals(State.BOUND, read.state)
        assertTrue(read.allowsNewGeneration)
        assertNull(read.generationId)
    }

    /** `already_bound` is the same answer: the identical bind was already applied. */
    @Test
    fun `an already-bound answer also binds the branch`() = kotlinx.coroutines.runBlocking {
        val (conversation, branch) = interrupted()
        val owed = requireNotNull(ClaudePSessionContinuationGate.replay(conversation))

        val run = runReplay(ClaudePSessionBindOutcome.AlreadyBound, conversation, owed)

        assertEquals(ClaudePSessionContinuationGate.ReplayOutcome.Bound, run.outcome)
        assertEquals(
            State.BOUND,
            (resolve(run.conversation, branch) as ClaudePSessionContinuationResolution.Resolved).state,
        )
    }

    /** A settled negative answer closes the branch, and re-sending would not change it. */
    @Test
    fun `a refused replay closes the branch`() = kotlinx.coroutines.runBlocking {
        val (conversation, branch) = interrupted()
        val owed = requireNotNull(ClaudePSessionContinuationGate.replay(conversation))

        val run = runReplay(
            ClaudePSessionBindOutcome.Refused(ClaudePSessionBindState.CANDIDATE_UNAVAILABLE),
            conversation,
            owed,
        )

        assertTrue(run.outcome is ClaudePSessionContinuationGate.ReplayOutcome.Closed)
        assertEquals(
            State.FAILED_CLOSED,
            (resolve(run.conversation, branch) as ClaudePSessionContinuationResolution.Resolved).state,
        )
    }

    /**
     * **A replay that drops again keeps the branch replayable.** The branch stays `INTERRUPTED`
     * carrying the *same* generation id, which is the whole of what the next connection re-sends.
     */
    @Test
    fun `a replay that drops again stays interrupted with the same identity`() = kotlinx.coroutines.runBlocking {
        val (conversation, branch) = interrupted()
        val owed = requireNotNull(ClaudePSessionContinuationGate.replay(conversation))

        val run = runReplay(ClaudePSessionBindOutcome.Unproven, conversation, owed)

        assertTrue(run.outcome is ClaudePSessionContinuationGate.ReplayOutcome.Unproven)
        val read = resolve(run.conversation, branch) as ClaudePSessionContinuationResolution.Resolved
        assertEquals(State.INTERRUPTED, read.state)
        assertEquals(generationId, read.generationId)
        // Still replayable — on the *next* connection, which is the whole point.
        assertEquals(generationId, requireNotNull(ClaudePSessionContinuationGate.replay(run.conversation)).generationId)
    }

    // ---------------------------------------------------------------------------------------
    // The once-per-connection rule
    // ---------------------------------------------------------------------------------------

    private val first = ClaudePConnectionEpoch(1L)
    private val second = ClaudePConnectionEpoch(2L)

    /** The first attempt on a connection is permitted; the second on that connection is not. */
    @Test
    fun `a bind is replayed at most once per connection`() {
        val replays = ClaudePSessionBindReplays()

        assertTrue(replays.claim(generationId, first))
        assertFalse(replays.claim(generationId, first))
        assertFalse(replays.claim(generationId, first))
    }

    /** A new connection is exactly what replay is for: the first attempt's answer was lost with it. */
    @Test
    fun `a new connection may replay the same bind again`() {
        val replays = ClaudePSessionBindReplays()

        assertTrue(replays.claim(generationId, first))
        assertTrue(replays.claim(generationId, second))
        assertFalse(replays.claim(generationId, second))
    }

    /** Different binds are different obligations, and each gets its own single attempt. */
    @Test
    fun `the rule is per generation id`() {
        val replays = ClaudePSessionBindReplays()

        assertTrue(replays.claim("gen-a", first))
        assertTrue(replays.claim("gen-b", first))
        assertFalse(replays.claim("gen-a", first))
    }

    /**
     * No connection, no decision. Replaying anyway would risk a repeat on a connection nobody can
     * name; refusing costs one recovery attempt that the next connection makes again.
     */
    @Test
    fun `an unnamed connection permits no replay`() {
        val replays = ClaudePSessionBindReplays()

        assertFalse(replays.claim(generationId, null))
        // And it did not consume the attempt: a real connection may still make it.
        assertTrue(replays.claim(generationId, first))
    }

    @Test
    fun `forgetting a bind releases its slot`() {
        val replays = ClaudePSessionBindReplays()
        assertTrue(replays.claim(generationId, first))

        replays.forget(generationId)

        assertEquals(0, replays.size)
        assertTrue(replays.claim(generationId, first))
    }

    /**
     * The epoch identifies a connection; it does not disclose one. It carries no origin, device id,
     * credential or Server connection id — and its `toString` redacts even the local counter, so a
     * stray log line cannot leak it.
     */
    @Test
    fun `a connection epoch is opaque in a log line`() {
        val rendered = ClaudePConnectionEpoch(7L).toString()

        assertFalse("the counter must not be rendered", rendered.contains("7"))
        assertTrue(rendered.contains("ClaudePConnectionEpoch"))
        assertNotNull(ClaudePConnectionEpoch(7L))
    }

    private companion object {
        val FIXED_CREATED_AT = kotlinx.datetime.LocalDateTime(2019, 6, 1, 12, 0, 0)
    }
}
