package me.rerere.ai.provider.providers

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.claudep.ClaudePToolCallStatus
import me.rerere.ai.provider.claudep.ClaudePToolStatusPublication
import me.rerere.ai.provider.claudep.ClaudePToolStatusSink
import me.rerere.ai.provider.claudep.ClaudePToolStatusUpdate
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapping from a protocol-agnostic status to something a conversation can show.
 *
 * The two mistakes worth catching here both look like success at runtime:
 *
 * - **A card for a state the user cannot act on.** A terminal status rendered as a `Pending` part
 *   would put a tap target on screen for a call that is already over.
 * - **A decision rendered as a request.** `Pending`, `Approved` and `Denied` are three different
 *   things, and a mapping that collapsed them would show the user a prompt for a call that was
 *   already approved or refused.
 *
 * The arguments are checked verbatim because the card is what the user approves: a card that
 * restated the arguments would be asking for consent to something other than what will run.
 */
class ClaudePToolStatusMappingTest {

    /**
     * A whole SHA-256 digest as the app renders one, which is the only shape a frozen schema
     * identity ever has. Written out rather than computed so the assertion below is about the value
     * *travelling*, not about the mapping having recomputed something.
     */
    private val frozenFingerprint = "8f2c1a09d4e7b3650c1f8a29be47d03561c9ae2b784df0136ab5e8c27d940f31"

    private fun update(
        status: ClaudePToolCallStatus,
        arguments: JsonObject = JsonObject(mapOf("path" to JsonPrimitive("/tmp/x"))),
        toolSchemaFingerprint: String? = null,
    ) = ClaudePToolStatusUpdate(
        toolCallId = "call-1",
        toolName = "workspace_write",
        arguments = arguments,
        status = status,
        toolSchemaFingerprint = toolSchemaFingerprint,
    )

    @Test
    fun `the actionable statuses become parts with the matching approval state`() {
        assertEquals(
            ToolApprovalState.Pending,
            update(ClaudePToolCallStatus.PENDING_APPROVAL).asInterimToolPart()?.approvalState,
        )
        assertEquals(
            ToolApprovalState.Approved,
            update(ClaudePToolCallStatus.APPROVED).asInterimToolPart()?.approvalState,
        )
        assertEquals(
            ToolApprovalState.Denied(),
            update(ClaudePToolCallStatus.DENIED).asInterimToolPart()?.approvalState,
        )
    }

    @Test
    fun `no card is produced for a status the user cannot act on`() {
        for (status in listOf(
            ClaudePToolCallStatus.RUNNING,
            ClaudePToolCallStatus.COMPLETED,
            ClaudePToolCallStatus.FAILED,
            ClaudePToolCallStatus.CANCELLED,
        )) {
            assertNull("$status has no interim part", update(status).asInterimToolPart())
        }
    }

    @Test
    fun `the part carries the call's identity and its arguments verbatim`() {
        val arguments = JsonObject(mapOf("path" to JsonPrimitive("/tmp/x"), "n" to JsonPrimitive(3)))
        val part = update(ClaudePToolCallStatus.PENDING_APPROVAL, arguments).asInterimToolPart()!!

        assertEquals("call-1", part.toolCallId)
        assertEquals("workspace_write", part.toolName)
        assertEquals(arguments.toString(), part.input)
        assertEquals("nothing has run yet", emptyList<Any>(), part.output)
    }

    @Test
    fun `an announced part is a request for a decision and claims nothing was executed`() {
        val pending = update(ClaudePToolCallStatus.PENDING_APPROVAL).asInterimToolPart()!!

        assertTrue("the user has something to answer", pending.isPending)
        assertTrue("no output has been produced", !pending.isExecuted)
    }

    /**
     * The frozen schema identity crosses the mapping unchanged, and stays absent when absent.
     *
     * This is the field a later approval is committed against, and it cannot be re-derived on the
     * committing side: that side holds the app's current tool surface, not the catalog this
     * generation was offered. So the mapping must be a pure carrier in both directions — a value
     * that was dropped here, or one invented where none was published, would each be a card the
     * authority commits against an identity that never described the call.
     */
    @Test
    fun `the frozen schema fingerprint crosses the mapping unchanged`() {
        assertEquals(
            frozenFingerprint,
            update(ClaudePToolCallStatus.PENDING_APPROVAL, toolSchemaFingerprint = frozenFingerprint)
                .asInterimToolPart()
                ?.toolSchemaFingerprint,
        )
        assertNull(
            "a status that carried no fingerprint must not acquire one here",
            update(ClaudePToolCallStatus.PENDING_APPROVAL).asInterimToolPart()?.toolSchemaFingerprint,
        )
    }

    /**
     * The card's fingerprint survives the merge that folds every later delta into it.
     *
     * The pending card is one delta among several for the same call — the approval, the run, the
     * result all arrive afterwards and none of them carries a fingerprint, because none of them was
     * a publication. `Tool.merge` keeps the first non-null, which is what leaves the part the
     * authority reads holding the identity it was published under rather than null.
     */
    @Test
    fun `the fingerprint survives approved and executing deltas`() {
        val pending = update(
            ClaudePToolCallStatus.PENDING_APPROVAL,
            toolSchemaFingerprint = frozenFingerprint,
        ).asInterimToolPart()!!
        val approved = update(ClaudePToolCallStatus.APPROVED).asInterimToolPart()!!

        assertEquals(
            "an approval delta carries no identity of its own",
            null,
            approved.toolSchemaFingerprint,
        )
        assertEquals(
            "and the merge with the published card keeps the card's",
            frozenFingerprint,
            pending.merge(approved).toolSchemaFingerprint,
        )
        assertEquals(
            "the decision the merge keeps is still the card's, not the delta's",
            ToolApprovalState.Pending,
            pending.merge(approved).approvalState,
        )

        // The executing delta is a `RUNNING` status, which produces no part at all — the call's
        // progress is published, not rendered. The result part is the app's own, built by the host
        // that ran the call, and it likewise states no fingerprint.
        val result = UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = "workspace_write",
            input = pending.input,
            output = listOf(UIMessagePart.Text("done")),
            approvalState = ToolApprovalState.Approved,
        )
        assertEquals(
            "a result that states no identity must not erase the published one",
            frozenFingerprint,
            pending.merge(result).toolSchemaFingerprint,
        )
        assertEquals(
            "nor may a stale identity on the card be replaced by one from a later delta",
            frozenFingerprint,
            pending.merge(result.copy(toolSchemaFingerprint = "a".repeat(64))).toolSchemaFingerprint,
        )
    }

    @Test
    fun `the inert sink accepts without showing anything`() = runBlocking {
        assertEquals(
            ClaudePToolStatusPublication.Accepted,
            ClaudePToolStatusSink.NONE.publish(update(ClaudePToolCallStatus.PENDING_APPROVAL)),
        )
    }
}
