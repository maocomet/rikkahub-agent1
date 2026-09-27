package me.rerere.ai.provider.claudep

import me.rerere.ai.provider.claudep.ClaudePSessionContinuationResolution.Conflicted
import me.rerere.ai.provider.claudep.ClaudePSessionContinuationResolution.Refused
import me.rerere.ai.provider.claudep.ClaudePSessionContinuationResolution.Resolved
import me.rerere.ai.provider.claudep.ClaudePSessionContinuationTransitions.Conflict
import me.rerere.ai.provider.claudep.ClaudePSessionContinuationTransitions.Refusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The closed transition table, and the shape it refuses.
 *
 * These are the properties the continuation barrier rests on: that a branch's records have to
 * chain, that a pair which could not have happened in that order is refused rather than
 * resolved to whichever record came last, and that the two states which permit a new generation
 * are exactly the two that have proven there is nothing in flight.
 */
class ClaudePSessionContinuationTest {

    private val branch = "a".repeat(64)
    private val otherBranch = "b".repeat(64)
    private val assistant = "assistant-1"

    private fun record(
        state: ClaudePSessionContinuationState?,
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

    private fun resolved(vararg records: ClaudePSessionContinuation): Resolved {
        val result = ClaudePSessionContinuationTransitions.fold(records.toList())
        assertTrue("expected a resolved fold, got $result", result is Resolved)
        return result as Resolved
    }

    private fun conflicted(vararg records: ClaudePSessionContinuation): Conflict {
        val result = ClaudePSessionContinuationTransitions.fold(records.toList())
        assertTrue("expected a conflict, got $result", result is Conflicted)
        return (result as Conflicted).reason
    }

    private fun refused(vararg records: ClaudePSessionContinuation): Refusal {
        val result = ClaudePSessionContinuationTransitions.fold(records.toList())
        assertTrue("expected a refusal, got $result", result is Refused)
        return (result as Refused).reason
    }

    private val start = ClaudePSessionContinuationState.START_IN_FLIGHT
    private val pending = ClaudePSessionContinuationState.BIND_PENDING
    private val bound = ClaudePSessionContinuationState.BOUND
    private val closed = ClaudePSessionContinuationState.FAILED_CLOSED
    private val interrupted = ClaudePSessionContinuationState.INTERRUPTED

    // ---------------------------------------------------------------------------------------
    // What "no record" means
    // ---------------------------------------------------------------------------------------

    /**
     * An empty path is not a stored state and not a failure. It is the branch's first generation,
     * and the only thing that may be concluded from it is that nothing is in flight.
     */
    @Test
    fun `no record resolves to uninitialized and allows a generation`() {
        val result = ClaudePSessionContinuationTransitions.fold(emptyList())

        assertEquals(ClaudePSessionContinuationResolution.UNINITIALIZED, result)
        assertTrue(result.allowsNewGeneration)
    }

    // ---------------------------------------------------------------------------------------
    // The legal histories
    // ---------------------------------------------------------------------------------------

    @Test
    fun `an immediate turn is a start followed by a bound`() {
        val result = resolved(record(start, 1), record(bound, 2))

        assertEquals(bound, result.state)
        assertEquals(2L, result.revision)
        assertEquals(branch, result.branchId)
        assertEquals(assistant, result.assistantId)
        assertTrue(result.allowsNewGeneration)
    }

    @Test
    fun `a second turn on the same branch chains from bound`() {
        val result = resolved(
            record(start, 1),
            record(bound, 2),
            record(start, 3),
            record(bound, 4),
        )

        assertEquals(bound, result.state)
        assertEquals(4L, result.revision)
    }

    @Test
    fun `a deferred generation starts at pending and settles at bound`() {
        assertEquals(pending, resolved(record(pending, 1, "gen-1")).state)

        val settled = resolved(record(pending, 1, "gen-1"), record(bound, 2))
        assertEquals(bound, settled.state)
        assertEquals(null, settled.generationId)
    }

    /**
     * §6.1's only recovery: a dropped bind is re-sent with the same identity, so the interrupted
     * record and the retry carry one generation id between them.
     */
    @Test
    fun `an interrupted bind replays into pending under the same generation`() {
        val result = resolved(
            record(pending, 1, "gen-1"),
            record(interrupted, 2, "gen-1"),
            record(pending, 3, "gen-1"),
            record(bound, 4),
        )

        assertEquals(bound, result.state)
    }

    /** Each of the five states is reachable as the only record on a path — see the class doc. */
    @Test
    fun `every state is a legal first record`() {
        assertEquals(start, resolved(record(start, 1)).state)
        assertEquals(pending, resolved(record(pending, 1, "gen-1")).state)
        assertEquals(bound, resolved(record(bound, 1)).state)
        assertEquals(closed, resolved(record(closed, 1)).state)
        assertEquals(interrupted, resolved(record(interrupted, 1)).state)
    }

    /** A start that failed on a rolled-back graph leaves the closed record as the only one. */
    @Test
    fun `a start that fails or drops may leave only its terminal`() {
        assertEquals(closed, resolved(record(start, 1), record(closed, 2)).state)
        assertEquals(interrupted, resolved(record(start, 1), record(interrupted, 2)).state)
    }

    @Test
    fun `the in-flight start of a deferred generation may settle into a pending bind`() {
        assertEquals(pending, resolved(record(start, 1), record(pending, 2, "gen-1")).state)
    }

    // ---------------------------------------------------------------------------------------
    // The refusals
    // ---------------------------------------------------------------------------------------

    /**
     * `FAILED_CLOSED` is absorbing. Nothing recovers from it automatically, so a branch that
     * appears to have moved on from one did not come from this state machine.
     */
    @Test
    fun `a closed branch never reopens in place`() {
        assertEquals(
            Conflict.ILLEGAL_TRANSITION,
            conflicted(record(closed, 1), record(start, 2)),
        )
        assertEquals(
            Conflict.ILLEGAL_TRANSITION,
            conflicted(record(closed, 1), record(bound, 2)),
        )
    }

    /**
     * `INTERRUPTED` is not a synonym for absent. Reading it as one is how a dropped connection
     * would quietly buy a second Claude session for a branch that may already have one.
     */
    @Test
    fun `an interrupted branch leads only back to a bind replay`() {
        assertEquals(
            Conflict.ILLEGAL_TRANSITION,
            conflicted(record(interrupted, 1), record(start, 2)),
        )
        assertEquals(
            Conflict.ILLEGAL_TRANSITION,
            conflicted(record(interrupted, 1), record(bound, 2)),
        )
    }

    @Test
    fun `a bound branch starts the next turn but never a second pending bind`() {
        assertEquals(
            Conflict.ILLEGAL_TRANSITION,
            conflicted(record(bound, 1), record(pending, 2, "gen-1")),
        )
        assertEquals(
            Conflict.ILLEGAL_TRANSITION,
            conflicted(record(bound, 1), record(bound, 2)),
        )
    }

    @Test
    fun `a pending bind does not go back to a start or to another pending`() {
        assertEquals(
            Conflict.ILLEGAL_TRANSITION,
            conflicted(record(pending, 1, "gen-1"), record(start, 2)),
        )
        assertEquals(
            Conflict.ILLEGAL_TRANSITION,
            conflicted(record(pending, 1, "gen-1"), record(pending, 2, "gen-2")),
        )
    }

    @Test
    fun `two in-flight starts cannot follow one another on a branch`() {
        assertEquals(
            Conflict.ILLEGAL_TRANSITION,
            conflicted(record(start, 1), record(start, 2)),
        )
    }

    // ---------------------------------------------------------------------------------------
    // Ordering: never last-write-wins
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a revision that goes backwards is a conflict`() {
        assertEquals(
            Conflict.REVISION_REGRESSION,
            conflicted(record(start, 5), record(bound, 4)),
        )
    }

    @Test
    fun `two records claiming one step and disagreeing is a conflict`() {
        assertEquals(
            Conflict.REVISION_DISAGREEMENT,
            conflicted(record(start, 1), record(bound, 1)),
        )
    }

    /**
     * Every writer advances the revision by exactly one per record, so a step with nothing on it
     * means a record was lost — a half-applied rollback, or a restore from a partial backup. The
     * fail-closed reading is to refuse the branch rather than to resume across the hole.
     */
    @Test
    fun `two consecutive records that skip a step are a conflict`() {
        assertEquals(
            Conflict.REVISION_GAP,
            conflicted(record(start, 1), record(bound, 3)),
        )
    }

    /**
     * The first record is exempt, and deliberately: a rollback can legitimately leave a lone record
     * at a revision above one, which is why `null -> any` is a legal first step. Only a gap *after*
     * a folded record is evidence of a lost write.
     */
    @Test
    fun `a lone record at a revision above one is not a gap`() {
        assertEquals(bound, resolved(record(bound, 7)).state)
    }

    /** And contiguity is measured from that first record, not from revision one. */
    @Test
    fun `contiguity is measured from the first record, not from revision one`() {
        assertEquals(start, resolved(record(bound, 7), record(start, 8)).state)
        assertEquals(
            Conflict.REVISION_GAP,
            conflicted(record(bound, 7), record(start, 9)),
        )
    }

    /** The same step observed twice with the same content is one record persisted twice. */
    @Test
    fun `two identical records claiming one step collapse to one`() {
        val result = resolved(record(start, 1), record(start, 1), record(bound, 2))

        assertEquals(bound, result.state)
    }

    // ---------------------------------------------------------------------------------------
    // The generation id is part of the bind's identity
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a pending bind must carry the generation it will replay`() {
        assertEquals(Refusal.GENERATION_ID_REQUIRED, refused(record(pending, 1)))
        assertEquals(Refusal.BLANK_GENERATION_ID, refused(record(pending, 1, "")))
    }

    @Test
    fun `a settled or in-flight state carries no generation id`() {
        assertEquals(Refusal.GENERATION_ID_NOT_PERMITTED, refused(record(bound, 1, "gen-1")))
        assertEquals(Refusal.GENERATION_ID_NOT_PERMITTED, refused(record(start, 1, "gen-1")))
        assertEquals(Refusal.GENERATION_ID_NOT_PERMITTED, refused(record(closed, 1, "gen-1")))
    }

    /** A replay re-sends the same bind, so any change to its id is a different binding. */
    @Test
    fun `a bind that changes generation across the fold is a conflict`() {
        assertEquals(
            Conflict.GENERATION_ID_INCONSISTENT,
            conflicted(record(pending, 1, "gen-1"), record(interrupted, 2, "gen-2")),
        )
        assertEquals(
            Conflict.GENERATION_ID_INCONSISTENT,
            conflicted(record(pending, 1, "gen-1"), record(interrupted, 2)),
        )
        assertEquals(
            Conflict.GENERATION_ID_INCONSISTENT,
            conflicted(
                record(pending, 1, "gen-1"),
                record(interrupted, 2, "gen-1"),
                record(pending, 3, "gen-2"),
            ),
        )
    }

    /**
     * An interrupted *start* has no candidate to bind, so a generation id on it could only be
     * mistaken for something replayable.
     */
    @Test
    fun `an interrupted start carries no generation id`() {
        assertEquals(
            Conflict.GENERATION_ID_INCONSISTENT,
            conflicted(record(start, 1), record(interrupted, 2, "gen-1")),
        )
        assertEquals(interrupted, resolved(record(start, 1), record(interrupted, 2)).state)
    }

    // ---------------------------------------------------------------------------------------
    // Shape
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a record that lost a field is refused, never defaulted`() {
        assertEquals(Refusal.MISSING_STATE, refused(record(null, 1)))
        assertEquals(Refusal.BLANK_ASSISTANT_ID, refused(record(start, 1, assistantId = "")))
        assertEquals(Refusal.NON_POSITIVE_REVISION, refused(record(start, 0)))
        assertEquals(Refusal.NON_POSITIVE_REVISION, refused(record(start, -1)))
    }

    @Test
    fun `a branch id that is not a canonical digest is refused`() {
        assertEquals(Refusal.MALFORMED_BRANCH_ID, refused(record(start, 1, branchId = "")))
        assertEquals(
            Refusal.MALFORMED_BRANCH_ID,
            refused(record(start, 1, branchId = "A".repeat(64))),
        )
        assertEquals(
            Refusal.MALFORMED_BRANCH_ID,
            refused(record(start, 1, branchId = "a".repeat(63))),
        )
    }

    /** A damaged record refuses the fold wherever it sits, including after a legal prefix. */
    @Test
    fun `a refusal anywhere in the path refuses the whole fold`() {
        assertEquals(
            Refusal.MALFORMED_BRANCH_ID,
            refused(record(start, 1), record(bound, 2, branchId = "nope")),
        )
    }

    /** The resolution reports the branch it describes, so a caller never re-derives it. */
    @Test
    fun `the resolution carries the branch and assistant it folded`() {
        val result = resolved(
            record(start, 1, branchId = otherBranch, assistantId = "assistant-2"),
            record(bound, 2, branchId = otherBranch, assistantId = "assistant-2"),
        )

        assertEquals(otherBranch, result.branchId)
        assertEquals("assistant-2", result.assistantId)
    }

    // ---------------------------------------------------------------------------------------
    // The gate
    // ---------------------------------------------------------------------------------------

    /**
     * The single rule the dispatch path reads. Only a settled branch may start again; every other
     * state is running, unproven, or closed.
     */
    @Test
    fun `only a bound branch permits a new generation, and an uninitialized path does too`() {
        assertTrue(ClaudePSessionContinuationResolution.UNINITIALIZED.allowsNewGeneration)
        assertTrue(resolved(record(bound, 1)).allowsNewGeneration)
        assertFalse(resolved(record(start, 1)).allowsNewGeneration)
        assertFalse(resolved(record(pending, 1, "gen-1")).allowsNewGeneration)
        assertFalse(resolved(record(closed, 1)).allowsNewGeneration)
        assertFalse(resolved(record(interrupted, 1)).allowsNewGeneration)
    }

    /** A branch whose state cannot be established is not a branch anything may be dispatched on. */
    @Test
    fun `a refusal or a conflict permits nothing`() {
        assertFalse(
            ClaudePSessionContinuationTransitions
                .fold(listOf(record(null, 1)))
                .allowsNewGeneration,
        )
        assertFalse(
            ClaudePSessionContinuationTransitions
                .fold(listOf(record(closed, 1), record(start, 2)))
                .allowsNewGeneration,
        )
    }
}
