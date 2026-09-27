package me.rerere.rikkahub.data.claudep

import kotlin.uuid.Uuid
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.claudep.ClaudePSessionBindingIntent
import me.rerere.ai.provider.claudep.ClaudePSessionBindingRequest
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.service.chat.CancelCurrentToolCommand
import me.rerere.rikkahub.service.chat.CancelQueuedCommand
import me.rerere.rikkahub.service.chat.ChatCommand
import me.rerere.rikkahub.service.chat.ClearPendingQueueCommand
import me.rerere.rikkahub.service.chat.InterruptCommand
import me.rerere.rikkahub.service.chat.InterruptRegenerateCommand
import me.rerere.rikkahub.service.chat.PetDialogueCommand
import me.rerere.rikkahub.service.chat.RawUserContent
import me.rerere.rikkahub.service.chat.RegenerateCommand
import me.rerere.rikkahub.service.chat.ResumeAfterApprovalCommand
import me.rerere.rikkahub.service.chat.SendMessageCommand
import me.rerere.rikkahub.service.chat.SteerCommand
import me.rerere.rikkahub.service.chat.StopCommand
import me.rerere.rikkahub.service.chat.ToolApprovalCommand
import me.rerere.rikkahub.service.chat.ToolDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The invariant that every Claude P model dispatch is accounted for.
 *
 * The failure this class guards is not a wrong enum member — it is a **dispatch that no one
 * decided**. A generation sent without a decision goes out as `mode: "new"` against a branch that
 * may already hold a session, which starts a second one silently, and it leaves any barrier it
 * should have settled unsettled. Neither is visible in the app, so the property has to be asserted
 * here, shape by shape.
 */
class ClaudePSessionContinuationDispatchTest {

    private val assistant = "00000000-0000-0000-0000-0000000000aa"
    private val branch = "1".repeat(64)

    private val immediate = ClaudePSessionContinuationGate.Decision.Immediate(
        assistantId = assistant,
        branchId = branch,
        revision = 1L,
        admissionRecord = null,
        request = ClaudePSessionBindingRequest(
            assistantId = assistant,
            intent = ClaudePSessionBindingIntent.IMMEDIATE,
            branchId = branch,
        ),
    )

    private val deferred = ClaudePSessionContinuationGate.Decision.Deferred(
        assistantId = assistant,
        request = ClaudePSessionBindingRequest(
            assistantId = assistant,
            intent = ClaudePSessionBindingIntent.DEFERRED,
            branchId = null,
        ),
    )

    private fun send() = SendMessageCommand(RawUserContent(listOf(UIMessagePart.Text("hi"))))

    private fun regenerate() = RegenerateCommand(
        targetMessageId = Uuid.random(),
        expectedTargetVersion = 0L,
        expectedBranchHeadMessageId = Uuid.random(),
    )

    private fun refusal(command: ChatCommand, targetRole: MessageRole?, decision: ClaudePSessionContinuationGate.Decision?) =
        ClaudePSessionContinuationDispatch.refusalFor(command, targetRole, decision)

    // ---------------------------------------------------------------------------------------
    // The ordinary path
    // ---------------------------------------------------------------------------------------

    /** An admitted generation is dispatched. Both intents are accounted for by their decision. */
    @Test
    fun `an admitted generation is not refused`() {
        assertNull(refusal(send(), null, immediate))
        assertNull(refusal(send(), null, deferred))
        assertNull(refusal(regenerate(), MessageRole.USER, immediate))
        assertNull(refusal(regenerate(), MessageRole.ASSISTANT, immediate))
        assertNull(refusal(ResumeAfterApprovalCommand, null, immediate))
    }

    /**
     * An ordinary command reaches admission unconditionally, so a missing decision is a defect
     * rather than a known shape — and it is still refused rather than dispatched.
     */
    @Test
    fun `a generation with no decision is refused`() {
        assertEquals(
            ClaudePSessionContinuationDispatch.Refusal.ADMISSION_MISSING,
            refusal(send(), null, null),
        )
        assertEquals(
            ClaudePSessionContinuationDispatch.Refusal.ADMISSION_MISSING,
            refusal(regenerate(), MessageRole.ASSISTANT, null),
        )
    }

    /**
     * A decision that describes no generation means the admission that refused it was bypassed.
     * Its own documentation calls that a wiring defect, and the only safe reading is to refuse.
     */
    @Test
    fun `a decision that is not a generation is refused`() {
        val refused = ClaudePSessionContinuationGate.Decision.Refused(
            assistant,
            ClaudePSessionContinuationGate.Reason.CONTINUATION_BLOCKED,
        )

        assertEquals(
            ClaudePSessionContinuationDispatch.Refusal.NOT_A_GENERATION,
            refusal(send(), null, refused),
        )
        assertEquals(
            ClaudePSessionContinuationDispatch.Refusal.NOT_A_GENERATION,
            refusal(send(), null, ClaudePSessionContinuationGate.Decision.NotModelGeneration),
        )
    }

    // ---------------------------------------------------------------------------------------
    // The emergency shape
    // ---------------------------------------------------------------------------------------

    /**
     * **The named refusal, and the reason M3-B cannot claim every dispatch shape.**
     *
     * An interrupt preempts a running command, so it bypasses the durable admission transaction
     * that writes the barrier — and then dispatches a model anyway. Refusing it costs a visible
     * error; letting it through would send `mode: "new"` for a branch the Server may already hold a
     * session for, and nothing in the app would show it.
     */
    @Test
    fun `an emergency dispatch with no decision is refused by name`() {
        assertEquals(
            ClaudePSessionContinuationDispatch.Refusal.EMERGENCY_NOT_ADMITTED,
            refusal(InterruptCommand(send()), null, null),
        )
        assertEquals(
            ClaudePSessionContinuationDispatch.Refusal.EMERGENCY_NOT_ADMITTED,
            refusal(InterruptRegenerateCommand(regenerate()), MessageRole.USER, null),
        )
    }

    /**
     * The refusal is about the **missing** decision, not about the command's type. A later batch
     * that routes an emergency command through the same admission seam arrives here holding a
     * decision, and refusing it then would refuse a dispatch that is fully accounted for.
     */
    @Test
    fun `an emergency dispatch that was admitted is not refused`() {
        assertNull(refusal(InterruptCommand(send()), null, immediate))
        assertNull(refusal(InterruptRegenerateCommand(regenerate()), MessageRole.USER, immediate))
    }

    // ---------------------------------------------------------------------------------------
    // The commands that dispatch nothing
    // ---------------------------------------------------------------------------------------

    /**
     * Everything that starts no model generation is left alone, including every emergency command
     * that only stops work. Refusing a stop would be the worst possible answer to a conservative
     * check: the user's cancel would become an error.
     */
    @Test
    fun `a command that dispatches no model is never refused`() {
        val quiet = listOf(
            StopCommand(),
            CancelCurrentToolCommand("call-1"),
            SteerCommand("more detail"),
            ClearPendingQueueCommand(),
            CancelQueuedCommand(Uuid.random()),
            ToolApprovalCommand(toolCallId = "call-1", decision = ToolDecision.Approved),
            PetDialogueCommand(Uuid.random(), Uuid.random(), "hello"),
        )

        for (command in quiet) {
            assertNull("$command dispatches nothing", refusal(command, null, null))
        }
    }

    // ---------------------------------------------------------------------------------------
    // The audit
    // ---------------------------------------------------------------------------------------

    /**
     * **No command shape reaches a model without either a decision or a named refusal.**
     *
     * This is the property the whole file exists for, asserted over the shapes that exist rather
     * than over a list of the ones that were remembered: every entry is run with **no** decision,
     * which is the state a bypassed admission leaves behind, and not one may answer `null`.
     */
    @Test
    fun `every model dispatch shape is either admitted or refused`() {
        val shapes: List<Pair<ChatCommand, MessageRole?>> = listOf(
            send() to null,
            InterruptCommand(send()) to null,
            regenerate() to MessageRole.USER,
            regenerate() to MessageRole.ASSISTANT,
            regenerate() to null,
            InterruptRegenerateCommand(regenerate()) to MessageRole.USER,
            InterruptRegenerateCommand(regenerate()) to MessageRole.ASSISTANT,
            ResumeAfterApprovalCommand to null,
        )

        for ((command, targetRole) in shapes) {
            val withoutDecision = refusal(command, targetRole, null)
            assertNotNull("$command reached a model with no decision", withoutDecision)
            // And each refusal carries a code from the closed set, because the caller returns it
            // upward as the reason the command was rejected rather than crashing on it.
            assertTrue(
                "$withoutDecision has no code",
                withoutDecision!!.code in CODES,
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // The boundary the caller relies on
    // ---------------------------------------------------------------------------------------

    /**
     * **A refusal is a code, never an assertion message.**
     *
     * Two properties are pinned here and both were brief requirements: the *mechanism* is the
     * named refusal [ClaudePSessionContinuationDispatch.refusalFor] returns to the command boundary,
     * and the *backstop* at the provider call — which the mechanism makes unreachable — throws the
     * same code rather than a `check`/`assert` whose message nobody enumerated. A refusal that a
     * user could reach as a crash is the failure mode this shape avoids entirely.
     */
    @Test
    fun `a refusal is a stable named code in both channels`() {
        val refusal = requireNotNull(
            ClaudePSessionContinuationDispatch.refusalFor(InterruptCommand(send()), null, null),
        )

        assertEquals("claude_p_continuation_emergency_not_admitted", refusal.code)
        assertEquals(
            refusal.code,
            ClaudePSessionContinuationDispatch.Refused(refusal).message,
        )
        // And the emergency refusal is *not* the legacy answer: nothing about it can produce the
        // pre-M3 `mode: "new"` shape, because the caller returns before the provider is reached.
        assertTrue(refusal.code.startsWith("claude_p_continuation_"))
    }

    private companion object {
        /** Every code a caller may surface to a user. A rename here is a user-visible change. */
        val CODES = setOf(
            "claude_p_continuation_emergency_not_admitted",
            "claude_p_continuation_admission_missing",
            "claude_p_continuation_not_a_generation",
        )
    }
}
