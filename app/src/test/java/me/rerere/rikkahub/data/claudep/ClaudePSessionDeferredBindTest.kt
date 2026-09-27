package me.rerere.rikkahub.data.claudep

import kotlin.uuid.Uuid
import me.rerere.ai.core.MessageRole
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `deferred` bind, from the committed variant to the state the Server's answer leaves.
 *
 * The property under test is the one §6.1 exists for: a branch that was created by a generation
 * becomes resumable **only** when the Server confirms it, and every other answer says something
 * different and has to be recorded differently. So the assertions are made against a
 * **re-resolution** of the settled graph, never against the record that was written — a record that
 * is attached but unreadable is the same as no record at all.
 */
class ClaudePSessionDeferredBindTest {

    private val assistant = "00000000-0000-0000-0000-0000000000aa"
    private val assistantUuid = Uuid.parse(assistant)
    private val generationId = "gen-deferred-1"

    private fun uid(prefix: Int, n: Int) =
        Uuid.parse("%08d-0000-0000-0000-%012d".format(prefix, n))

    private fun message(n: Int, role: MessageRole = MessageRole.ASSISTANT) = UIMessage(
        id = uid(1, n),
        role = role,
        parts = listOf(UIMessagePart.Text("m$n")),
        createdAt = FIXED_CREATED_AT,
    )

    private fun node(n: Int, messages: List<UIMessage>, selectIndex: Int = 0) = MessageNode(
        id = uid(2, n),
        messages = messages,
        selectIndex = selectIndex,
    )

    /**
     * The graph a `deferred` generation leaves behind: the new variant is appended to the node it
     * targets and selected, which is why the branch identity is only knowable now.
     */
    private fun committedVariant(): Conversation = Conversation(
        id = uid(3, 1),
        assistantId = assistantUuid,
        messageNodes = listOf(
            node(1, listOf(message(1, MessageRole.USER))),
            node(2, listOf(message(2), message(3)), selectIndex = 1),
        ),
    )

    private fun pending() = requireNotNull(
        ClaudePSessionContinuationGate.deferredPending(
            conversation = committedVariant(),
            assistantId = assistant,
            generationId = generationId,
        ),
    )

    private fun resolve(conversation: Conversation, branch: String) =
        ClaudePSessionContinuationResolver.resolve(conversation, branch)

    private fun committed(settlement: ClaudePSessionContinuationGate.Settlement): Conversation {
        assertTrue(
            "expected a commit, got $settlement",
            settlement is ClaudePSessionContinuationGate.Settlement.Commit,
        )
        return (settlement as ClaudePSessionContinuationGate.Settlement.Commit).conversation
    }

    private fun settledBy(outcome: ClaudePSessionBindOutcome): Pair<Conversation, String> {
        val pending = pending()
        val settled = committed(
            ClaudePSessionContinuationGate.settleDeferred(pending.conversation, pending, outcome),
        )
        return settled to pending.branchId
    }

    // ---------------------------------------------------------------------------------------
    // The pending write
    // ---------------------------------------------------------------------------------------

    /**
     * The pending record goes on the **new variant**, and the value that carries it says so.
     *
     * The caller cannot re-derive that slot afterwards — once the record is on it, "the last
     * message with no record of this branch" is a different message — so the identity of the write
     * travels with the write. Without it the settled state would land somewhere the pending one
     * never was, leaving the path showing two records where §5.2 leaves room for one.
     */
    @Test
    fun `the pending record carries the message it was written to`() {
        val pending = pending()
        val newest = uid(1, 3).toString()

        assertEquals(newest, pending.messageId)

        val read = resolve(pending.conversation, pending.branchId)
            as ClaudePSessionContinuationResolution.Resolved
        assertEquals(State.BIND_PENDING, read.state)
        assertEquals(generationId, read.generationId)
        assertEquals(pending.revision, read.revision)
    }

    // ---------------------------------------------------------------------------------------
    // The bind vocabulary
    // ---------------------------------------------------------------------------------------

    /**
     * `bound` and `already_bound` are the same answer to this device: the Server holds the binding.
     * `already_bound` is what a repeat of an identical bind returns, which is why the two must
     * settle identically — a difference here would make an idempotent replay look like a conflict.
     */
    @Test
    fun `an accepted bind makes the branch resumable`() {
        for (outcome in listOf(
            ClaudePSessionBindOutcome.Bound,
            ClaudePSessionBindOutcome.AlreadyBound,
        )) {
            val (settled, branch) = settledBy(outcome)
            val read = resolve(settled, branch) as ClaudePSessionContinuationResolution.Resolved

            assertEquals(State.BOUND, read.state)
            assertEquals(1L, read.revision)
            assertTrue("$outcome must make the branch resumable", read.allowsNewGeneration)
            // A settled binding has no outstanding obligation, so the id is dropped.
            assertEquals(null, read.generationId)
        }
    }

    /**
     * Every settled *negative* answer closes the branch. They are different reasons on the wire and
     * the same fact here: the candidate is not going to become a binding, so the branch is over.
     */
    @Test
    fun `every refused bind closes the branch`() {
        val refusals = listOf(
            ClaudePSessionBindOutcome.Refused(ClaudePSessionBindState.CONFLICT),
            ClaudePSessionBindOutcome.Refused(ClaudePSessionBindState.CANDIDATE_UNAVAILABLE),
            ClaudePSessionBindOutcome.Refused(ClaudePSessionBindState.REFUSED),
            ClaudePSessionBindOutcome.Malformed,
        )

        for (outcome in refusals) {
            val (settled, branch) = settledBy(outcome)
            val read = resolve(settled, branch) as ClaudePSessionContinuationResolution.Resolved

            assertEquals("$outcome", State.FAILED_CLOSED, read.state)
            assertFalse("$outcome must not leave the branch resumable", read.allowsNewGeneration)
        }
    }

    /**
     * **A dropped connection is not a failure.** The Server may have applied the bind before the
     * socket died, so the honest record is `INTERRUPTED` — carrying the *same* generation id, which
     * is the whole of what a later replay re-sends. Reading it as a refusal would close a branch
     * that is in fact fine, and dropping the id would leave nothing to replay.
     */
    @Test
    fun `an unproven bind interrupts the branch and keeps its generation id`() {
        val (settled, branch) = settledBy(ClaudePSessionBindOutcome.Unproven)
        val read = resolve(settled, branch) as ClaudePSessionContinuationResolution.Resolved

        assertEquals(State.INTERRUPTED, read.state)
        assertEquals(generationId, read.generationId)
        assertFalse(read.allowsNewGeneration)
    }

    /**
     * The settled state **supersedes the pending one in its own slot**: the revision does not move,
     * and the path shows one record rather than a pending followed by a bound.
     */
    @Test
    fun `a settled bind replaces the pending record rather than advancing past it`() {
        val pending = pending()
        val settled = committed(
            ClaudePSessionContinuationGate.settleDeferred(
                pending.conversation,
                pending,
                ClaudePSessionBindOutcome.Bound,
            ),
        )

        val records = settled.messageNodes
            .mapNotNull { it.messages.getOrNull(it.selectIndex) }
            .mapNotNull { it.claudePSessionContinuation }
            .filter { it.assistantId == assistant }
        assertEquals(1, records.size)
        assertEquals(pending.revision, records.single().revision)
    }

    /**
     * A settle whose message is no longer in the graph is refused. It is the same class of answer as
     * a missing terminal target: the graph is not the one the obligation was written against, and
     * writing the outcome somewhere else would settle a branch at a step it never reached.
     */
    @Test
    fun `a settle refuses when the pending message is gone`() {
        val pending = pending()
        val elsewhere = Conversation(
            id = uid(3, 1),
            assistantId = assistantUuid,
            messageNodes = listOf(node(1, listOf(message(1, MessageRole.USER)))),
        )

        val settlement = ClaudePSessionContinuationGate.settleDeferred(
            elsewhere,
            pending,
            ClaudePSessionBindOutcome.Bound,
        )

        assertTrue(
            "expected a refusal, got $settlement",
            settlement is ClaudePSessionContinuationGate.Settlement.Refused,
        )
        assertEquals(
            ClaudePSessionContinuationGate.Reason.SETTLEMENT_TARGET_MISSING,
            (settlement as ClaudePSessionContinuationGate.Settlement.Refused).reason,
        )
    }

    /**
     * A `deferred` obligation must not be settled through the generation-terminal seam: it is
     * settled by the bind that follows its variant commit, and a terminal written here would claim
     * an outcome the bind has not produced.
     */
    @Test
    fun `a deferred obligation is refused by the generation-terminal seam`() {
        val pending = pending()
        val decision = ClaudePSessionContinuationGate.Decision.Deferred(
            assistantId = assistant,
            request = me.rerere.ai.provider.claudep.ClaudePSessionBindingRequest(
                assistantId = assistant,
                intent = me.rerere.ai.provider.claudep.ClaudePSessionBindingIntent.DEFERRED,
                branchId = null,
            ),
        )

        val settlement = ClaudePSessionContinuationGate.settle(
            conversation = pending.conversation,
            decision = decision,
            terminal = ClaudePSessionContinuationGate.Terminal.SUCCEEDED,
        )

        assertEquals(
            ClaudePSessionContinuationGate.Settlement.Refused(
                ClaudePSessionContinuationGate.Reason.DEFERRED_SETTLES_THROUGH_BIND,
            ),
            settlement,
        )
    }

    /** A deferred obligation produces no generation-terminal settlement at all. */
    @Test
    fun `a deferred decision attaches no generation settlement`() {
        val decision = ClaudePSessionContinuationGate.Decision.Deferred(
            assistantId = assistant,
            request = me.rerere.ai.provider.claudep.ClaudePSessionBindingRequest(
                assistantId = assistant,
                intent = me.rerere.ai.provider.claudep.ClaudePSessionBindingIntent.DEFERRED,
                branchId = null,
            ),
        )

        assertEquals(null, ClaudePSessionContinuationGate.settlementFor(decision))
    }

    private fun <T : Any> present(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    private companion object {
        val FIXED_CREATED_AT = kotlinx.datetime.LocalDateTime(2019, 6, 1, 12, 0, 0)
    }
}
