package me.rerere.ai.provider.providers

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.claudep.ClaudePToolCallStatus
import me.rerere.ai.provider.claudep.ClaudePToolStatusPublication
import me.rerere.ai.provider.claudep.ClaudePToolStatusSink
import me.rerere.ai.provider.claudep.ClaudePToolStatusUpdate
import me.rerere.ai.ui.ToolApprovalState
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

    private fun update(
        status: ClaudePToolCallStatus,
        arguments: JsonObject = JsonObject(mapOf("path" to JsonPrimitive("/tmp/x"))),
    ) = ClaudePToolStatusUpdate(
        toolCallId = "call-1",
        toolName = "workspace_write",
        arguments = arguments,
        status = status,
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

    @Test
    fun `the inert sink accepts without showing anything`() = runBlocking {
        assertEquals(
            ClaudePToolStatusPublication.Accepted,
            ClaudePToolStatusSink.NONE.publish(update(ClaudePToolCallStatus.PENDING_APPROVAL)),
        )
    }
}
