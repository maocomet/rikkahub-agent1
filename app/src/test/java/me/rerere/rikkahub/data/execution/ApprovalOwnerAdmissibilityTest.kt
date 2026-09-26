package me.rerere.rikkahub.data.execution

import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.capability.SubjectType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Who a pending-approval barrier may be written for, per continuation mode.
 *
 * ## The product rule this encodes
 *
 * In RikkaHub, *which provider an assistant uses* and *whether the assistant is a second user* are
 * two independent settings. An ordinary assistant configured with Claude P must not lose its
 * approval-gated tools for the sole reason that it is not a `LOCAL_SECOND_USER`. The bridge's
 * tool calls arrive inside a live generation, so they use the `IN_FLIGHT` continuation, and that
 * continuation needs an exact approval identity — not a second-user profile.
 *
 * So the subject precondition is scoped to the mode that actually needs it:
 *
 * | mode | ordinary assistant | second user |
 * |---|---|---|
 * | `IN_FLIGHT` | **admitted** | admitted |
 * | `RESUME_COMMAND` | refused (unchanged) | admitted |
 *
 * ## Why this is tested at the predicate rather than through the database
 *
 * `persistPendingBarrier` needs an `AppDatabase` and five collaborators, and no in-memory Room or
 * Robolectric harness exists in this module's unit tests, so its body cannot be exercised here.
 * What *can* be exercised is the predicate that was doing the blocking — which is why it is a
 * function rather than an inline `require` at two call sites. The predicate is the whole of the
 * change: everything after it (the conversation agreement check, the schema fingerprint, the
 * exact-identity checks) is untouched.
 *
 * This is a narrower claim than "the barrier is written for an ordinary assistant", and it is
 * stated as such rather than implied: the persistence path that follows is unverified here.
 */
class ApprovalOwnerAdmissibilityTest {

    private fun owner(subjectType: SubjectType) = PendingApprovalOwner(
        runId = "run-1",
        commandId = "command-1",
        conversationId = "conversation-1",
        subjectId = "assistant-1:conversation-1",
        subjectType = subjectType,
        origin = ToolCallOrigin.LocalChat,
    )

    private fun admittance(subjectType: SubjectType, mode: ApprovalContinuationMode): Boolean =
        runCatching { requireAdmissibleApprovalOwner(owner(subjectType), mode) }.isSuccess

    // ---------------------------------------------------------------------------------------
    // 1. IN_FLIGHT is not second-user-only
    // ---------------------------------------------------------------------------------------

    /**
     * The correction, stated as a table so no row can be quietly dropped.
     *
     * Every subject type is enumerated rather than spot-checked: the bug being fixed was a
     * predicate that treated one type specially, and the way it would come back is a new type
     * being added to the enum and silently inheriting the refusal.
     */
    @Test
    fun `an in-flight barrier is admitted for every subject type`() {
        SubjectType.entries.forEach { subjectType ->
            assertEquals(
                "IN_FLIGHT must be admitted for $subjectType; approval is required either way, " +
                    "and a second-user profile is not what makes a pending card possible",
                true,
                admittance(subjectType, ApprovalContinuationMode.IN_FLIGHT),
            )
        }
    }

    /**
     * The specific case the product rule is about, named on its own so a reader sees it.
     */
    @Test
    fun `an ordinary assistant keeps its approval-gated tools in flight`() {
        assertEquals(
            true,
            admittance(SubjectType.LOCAL_ASSISTANT, ApprovalContinuationMode.IN_FLIGHT),
        )
    }

    // ---------------------------------------------------------------------------------------
    // 2. RESUME_COMMAND is untouched
    // ---------------------------------------------------------------------------------------

    /**
     * The old flow keeps exactly its old answer, including the message a caller may match on.
     *
     * This is the half of the change that must *not* move. `RESUME_COMMAND` approvals resume by
     * submitting a durable command, and that machinery's second-user requirement belongs to the
     * command and authority layer around it — not to approval as such. Widening it here would be
     * changing a flow this correction was never about.
     */
    @Test
    fun `a resume-command barrier still requires a second user`() {
        SubjectType.entries
            .filter { it != SubjectType.LOCAL_SECOND_USER }
            .forEach { subjectType ->
                val thrown = assertThrows(IllegalArgumentException::class.java) {
                    requireAdmissibleApprovalOwner(owner(subjectType), ApprovalContinuationMode.RESUME_COMMAND)
                }
                assertEquals("second_user_approval_owner_required", thrown.message)
            }
    }

    @Test
    fun `a resume-command barrier is still admitted for a second user`() {
        assertEquals(
            true,
            admittance(SubjectType.LOCAL_SECOND_USER, ApprovalContinuationMode.RESUME_COMMAND),
        )
    }

    // ---------------------------------------------------------------------------------------
    // 3. The mode is what decides — never the provider, never the subject alone
    // ---------------------------------------------------------------------------------------

    /**
     * The decision is a function of the mode, and the two modes disagree for an ordinary
     * assistant. One assertion, and it is the whole correction.
     *
     * If a later change made the subject decide instead — refusing `IN_FLIGHT` for a non-second
     * user, or admitting `RESUME_COMMAND` for one — this equality fails.
     */
    @Test
    fun `the two modes disagree for an ordinary assistant and agree for a second user`() {
        assertEquals(
            "for an ordinary assistant the continuation mode must decide",
            false to true,
            admittance(SubjectType.LOCAL_ASSISTANT, ApprovalContinuationMode.RESUME_COMMAND) to
                admittance(SubjectType.LOCAL_ASSISTANT, ApprovalContinuationMode.IN_FLIGHT),
        )
        assertEquals(
            "for a second user both modes are admitted",
            true to true,
            admittance(SubjectType.LOCAL_SECOND_USER, ApprovalContinuationMode.RESUME_COMMAND) to
                admittance(SubjectType.LOCAL_SECOND_USER, ApprovalContinuationMode.IN_FLIGHT),
        )
    }

    /**
     * Nothing about the owner's *identity* fields is relaxed, only the subject-type precondition.
     *
     * An admitted barrier is still an exact-identity barrier: the four fields [InFlightApprovalIdentity]
     * is built from are what a decision has to match, and a change that admitted a call while
     * dropping them would be the cross-conversation binding this whole mechanism exists to prevent.
     */
    @Test
    fun `admittance does not depend on or discard the owner's identity fields`() {
        val populated = owner(SubjectType.LOCAL_ASSISTANT)
        requireAdmissibleApprovalOwner(populated, ApprovalContinuationMode.IN_FLIGHT)

        // The fields the waiter is keyed by are still carried, unchanged, on the admitted owner.
        assertEquals("run-1", populated.runId)
        assertEquals("command-1", populated.commandId)
        assertEquals("conversation-1", populated.conversationId)
        assertEquals("assistant-1:conversation-1", populated.subjectId)
    }
}
