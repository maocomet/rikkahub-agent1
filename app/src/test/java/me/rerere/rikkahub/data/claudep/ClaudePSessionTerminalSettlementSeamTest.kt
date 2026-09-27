package me.rerere.rikkahub.data.claudep

import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.ai.GenerationRunControl
import me.rerere.rikkahub.service.chat.DurableCommandState
import me.rerere.rikkahub.service.chat.GenerationTerminalGraphSettlement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seam that lets a generation settle its graph where a result-less finish happens.
 *
 * Two things are checked here, and they are the two the authority layer cannot check for itself:
 * that the run-local attachment is genuinely once-only in both directions, and that the authority's
 * terminal vocabulary maps onto the continuation's without a per-attachment copy that could drift.
 */
class ClaudePSessionTerminalSettlementSeamTest {

    private fun control() = GenerationRunControl(runId = Uuid.random())

    private fun settlement(): GenerationTerminalGraphSettlement =
        GenerationTerminalGraphSettlement { _, _ -> null }

    // ---------------------------------------------------------------------------------------
    // The run-local slot
    // ---------------------------------------------------------------------------------------

    /** Two settlements for one run would mean two barriers; the second attach is a defect. */
    @Test
    fun `a settlement can only be attached once`() {
        val control = control()
        control.attachTerminalGraphSettlement(settlement())

        val failure = assertThrows(IllegalStateException::class.java) {
            control.attachTerminalGraphSettlement(settlement())
        }
        assertEquals("generation_terminal_settlement_already_attached", failure.message)
    }

    /** One-shot: the second take ends the run through the plain fallback, writing nothing again. */
    @Test
    fun `a settlement is consumed exactly once`() {
        val control = control()
        control.attachTerminalGraphSettlement(settlement())

        assertTrue(control.consumeTerminalGraphSettlement() != null)
        assertNull(control.consumeTerminalGraphSettlement())
    }

    /**
     * A run that attached nothing — every control command, and every non-Claude-P generation —
     * consumes `null`, which is what routes it to the unchanged `finishFallback`.
     */
    @Test
    fun `a run with no settlement consumes nothing`() {
        assertNull(control().consumeTerminalGraphSettlement())
    }

    // ---------------------------------------------------------------------------------------
    // The terminal mapping
    // ---------------------------------------------------------------------------------------

    /**
     * The three ends, and the distinction that matters: a cancellation is not a failure and not a
     * completion. Mapping it to either would record a branch as closed, or as bound, on the
     * strength of an outcome nobody could establish.
     */
    @Test
    fun `the authority's terminal states map onto the continuation's`() {
        assertEquals(
            ClaudePSessionContinuationGate.Terminal.SUCCEEDED,
            ClaudePSessionContinuationGate.terminalFor(DurableCommandState.COMPLETED),
        )
        assertEquals(
            ClaudePSessionContinuationGate.Terminal.UNPROVEN,
            ClaudePSessionContinuationGate.terminalFor(DurableCommandState.CANCELLED),
        )
        assertEquals(
            ClaudePSessionContinuationGate.Terminal.FAILED,
            ClaudePSessionContinuationGate.terminalFor(DurableCommandState.FAILED),
        )
    }

    /**
     * States that are not ends map to nothing. A run still in flight must not be settled merely
     * because it passed through this seam.
     */
    @Test
    fun `a state that is not an end maps to no terminal`() {
        for (state in listOf(
            DurableCommandState.PENDING,
            DurableCommandState.RUNNING,
            DurableCommandState.WAITING_APPROVAL,
        )) {
            assertNull("$state is not an end", ClaudePSessionContinuationGate.terminalFor(state))
        }
    }

    /**
     * The whole reason the mapping is a function here rather than a decision inside the attached
     * closure: a cancellation must reach the gate as `UNPROVEN` so the branch is interrupted, and
     * the gate then refuses to read that as resumable.
     */
    @Test
    fun `a cancellation settles a branch to interrupted, never to bound`() {
        val terminal = requireNotNull(
            ClaudePSessionContinuationGate.terminalFor(DurableCommandState.CANCELLED),
        )

        val record = ClaudePSessionContinuationGate.immediateTerminal(
            assistantId = "00000000-0000-0000-0000-0000000000aa",
            branchId = "0".repeat(64),
            boundRevision = 2L,
            terminal = terminal,
        )

        assertEquals(
            me.rerere.ai.provider.claudep.ClaudePSessionContinuationState.INTERRUPTED,
            record.state,
        )
        assertTrue(record.generationId == null)
    }
}
