package me.rerere.rikkahub.data.claudep

import kotlin.uuid.Uuid
import me.rerere.ai.provider.claudep.ClaudePSessionBindingIntent
import me.rerere.ai.provider.claudep.ClaudePSessionBindingRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The run-local carrier for an admitted command's continuation decision.
 *
 * Two properties matter here and neither is about the decision's content: that the *first*
 * admission's decision survives a repeated admission, and that a stop tells its caller the truth
 * about whether the runtime may carry on. Both are the difference between a refusal the user can
 * retry and a queue that quietly stops, or vice versa.
 */
class ClaudePSessionContinuationAdmissionsTest {

    private val assistant = "00000000-0000-0000-0000-0000000000aa"

    private fun immediate(revision: Long) =
        ClaudePSessionContinuationGate.Decision.Immediate(
            assistantId = assistant,
            branchId = "1".repeat(64),
            revision = revision,
            admissionRecord = null,
            request = ClaudePSessionBindingRequest(
                assistantId = assistant,
                intent = ClaudePSessionBindingIntent.IMMEDIATE,
                branchId = "1".repeat(64),
            ),
        )

    private fun deferred() =
        ClaudePSessionContinuationGate.Decision.Deferred(
            assistantId = assistant,
            request = ClaudePSessionBindingRequest(
                assistantId = assistant,
                intent = ClaudePSessionBindingIntent.DEFERRED,
                branchId = null,
            ),
        )

    /**
     * A second admission of one command is the same admission. Overwriting would make the run that
     * is already executing settle a branch the later read named — and the later read is made
     * against a graph that now carries the barrier the first admission wrote.
     */
    @Test
    fun `the first decision recorded for a command is the one kept`() {
        val admissions = ClaudePSessionContinuationAdmissions()
        val commandId = Uuid.random()
        val first = immediate(1L)
        val second = deferred()

        assertEquals(first, admissions.record(commandId, first))
        assertEquals(first, admissions.record(commandId, second))
        assertEquals(first, admissions.find(commandId))
        assertEquals(1, admissions.size)
    }

    /** A run that was admitted under no decision finds none, which is not an error. */
    @Test
    fun `an unknown command carries no decision`() {
        val admissions = ClaudePSessionContinuationAdmissions()
        admissions.record(Uuid.random(), immediate(1L))

        assertNull(admissions.find(null))
        assertNull(admissions.find(Uuid.random()))
    }

    /** The map is bounded by the runs that are executing, which is what `forget` is for. */
    @Test
    fun `forgetting a command drops its decision`() {
        val admissions = ClaudePSessionContinuationAdmissions()
        val commandId = Uuid.random()
        admissions.record(commandId, immediate(1L))

        admissions.forget(commandId)

        assertNull(admissions.find(commandId))
        assertEquals(0, admissions.size)
        // Forgetting twice is not an error: the run-finished path and a release may both run.
        admissions.forget(commandId)
        admissions.forget(null)
    }

    /**
     * A reconciled stop is a **repair that succeeded**: the branch is readable again, the command
     * was refused, and the next attempt is a fresh admission. Pausing there would turn a recovered
     * state into a stopped queue.
     */
    @Test
    fun `a reconciled stop lets the runtime carry on`() {
        val stop = ClaudePSessionAdmissionStop.reconciled()

        assertFalse(stop.pauseQueue)
        assertEquals("claude_p_stale_start_reconciled", stop.message)
    }

    /**
     * A failed reconciliation is the opposite: the graph still claims a model may be running on a
     * branch no run owns, and dispatching onto it is exactly what the barrier prevents.
     */
    @Test
    fun `a failed reconciliation pauses the queue`() {
        val stop = ClaudePSessionAdmissionStop.reconciliationFailed("BARRIER_NOT_UNIQUELY_LOCATED")

        assertTrue(stop.pauseQueue)
        assertEquals(
            "claude_p_stale_start_reconciliation_failed:BARRIER_NOT_UNIQUELY_LOCATED",
            stop.message,
        )
    }

    /**
     * **M3-B is active, and this test is what makes turning it off deliberate.**
     *
     * It is not asserting a constant for its own sake. While it is `true`, production Claude P
     * dispatches under `mode: "auto"` with a binding intent, writes a barrier in the transaction
     * that admits the command, and settles a continuation record on the way out — which is only
     * safe because every exit settles, including a dropped connection and a process that never
     * returns. Flipping it back to `false` is a safe place to stand (the wire and the Server both
     * still accept `mode: "new"`), but it is a deliberate act and not a fallback.
     *
     * So this test failing is the intended signal: it means the switch moved, and the list in
     * [ClaudePSessionContinuationActivation] has to have moved with it.
     */
    @Test
    fun `production acts on a Claude P continuation`() {
        assertTrue(
            "M3-B activation moved: see ClaudePSessionContinuationActivation",
            ClaudePSessionContinuationActivation.ENABLED,
        )
    }
}
