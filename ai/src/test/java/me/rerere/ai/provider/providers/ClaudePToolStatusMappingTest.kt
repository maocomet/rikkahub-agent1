package me.rerere.ai.provider.providers

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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

    /**
     * One status for one call.
     *
     * [arguments] is a [JsonElement] rather than a `JsonObject` because the production statuses are
     * not all the same shape: the pending publication restates the call's arguments, and the
     * decision publishes [JsonNull] instead, meaning "this status does not restate them". A helper
     * that could only express the first shape is what let the mapping's treatment of the second go
     * unnoticed.
     */
    private fun update(
        status: ClaudePToolCallStatus,
        arguments: JsonElement = JsonObject(mapOf("path" to JsonPrimitive("/tmp/x"))),
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
     * A published card states the whole call, so every later statement about it replaces the card's
     * state rather than being appended to it.
     *
     * This is the bug in one test. The three statements a Claude P approval produces — the pending
     * card, the decision, the result — are each a complete description of the same call, and the
     * appending merge treated them as string fragments: the tool name appeared once per statement,
     * the arguments were repeated, and the approval state stayed at `Pending` while the output
     * already held the result. The card on the phone showed exactly that.
     */
    @Test
    fun `a decision and a result replace the published card instead of appending to it`() {
        val pending = update(
            ClaudePToolCallStatus.PENDING_APPROVAL,
            toolSchemaFingerprint = frozenFingerprint,
        ).asInterimToolPart()!!
        val approved = update(ClaudePToolCallStatus.APPROVED).asInterimToolPart()!!

        assertEquals(
            "an approval carries no schema identity of its own",
            null,
            approved.toolSchemaFingerprint,
        )
        val decided = pending.merge(approved)
        assertEquals(
            "the decision is the incoming statement, not the card's request",
            ToolApprovalState.Approved,
            decided.approvalState,
        )
        assertEquals("the name is stated once", "workspace_write", decided.toolName)
        assertEquals(
            "and the arguments are not repeated",
            pending.input,
            decided.input,
        )
        assertEquals(
            "the merge with the published card keeps the card's fingerprint",
            frozenFingerprint,
            decided.toolSchemaFingerprint,
        )

        // The executing status is a `RUNNING` publication, which produces no part at all — the
        // call's progress is published, not rendered. The result part is the app's own, built by
        // the host that ran the call, and it likewise states no fingerprint.
        val result = UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = "workspace_write",
            input = pending.input,
            output = listOf(UIMessagePart.Text("done")),
            approvalState = ToolApprovalState.Approved,
        )
        val completed = decided.merge(result)
        assertEquals("the name is still stated once", "workspace_write", completed.toolName)
        assertEquals("the arguments are still stated once", pending.input, completed.input)
        assertEquals(
            "the result appears once, which is to say it is not a second copy of itself",
            listOf(UIMessagePart.Text("done")),
            completed.output,
        )
        assertEquals(
            "and the decision is not undone by the statement that follows it",
            ToolApprovalState.Approved,
            completed.approvalState,
        )
        assertEquals(
            "a result that states no identity must not erase the published one",
            frozenFingerprint,
            completed.toolSchemaFingerprint,
        )
        assertEquals(
            "nor may a foreign identity on a later statement replace the published one",
            frozenFingerprint,
            decided.merge(result.copy(toolSchemaFingerprint = "a".repeat(64)))
                .toolSchemaFingerprint,
        )
    }

    /**
     * The decision publishes no arguments, and the card must keep the ones it was published with.
     *
     * ## The production shape this pins
     *
     * `ClaudePToolBridgeHostImpl.awaitDecision` publishes the approval with `arguments = JsonNull`:
     * the decision is not a new source for the call's payload, and re-sending a potentially
     * sensitive one has no purpose. The mapping used to render that as the string `"null"` — a
     * *statement of arguments*, and a false one — which the snapshot merge then wrote over the
     * real ones, replacing what the user approved with the text `null`.
     *
     * So the two halves are asserted together, because either alone passes while the pair is
     * broken: the unstated input must be empty, and the merge must therefore keep the card's.
     */
    @Test
    fun `an approval that restates no arguments leaves the card's arguments alone`() {
        val pending = update(ClaudePToolCallStatus.PENDING_APPROVAL).asInterimToolPart()!!
        val approved = update(
            ClaudePToolCallStatus.APPROVED,
            arguments = JsonNull,
        ).asInterimToolPart()!!

        assertEquals(
            "an unstated input is empty, not the text `null`",
            "",
            approved.input,
        )
        assertEquals(
            "the approved card keeps the arguments the user was shown",
            pending.input,
            pending.merge(approved).input,
        )

        val result = UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = "workspace_write",
            input = pending.input,
            output = listOf(UIMessagePart.Text("done")),
            approvalState = ToolApprovalState.Approved,
        )
        assertEquals(
            "and the result that follows keeps them too",
            pending.input,
            pending.merge(approved).merge(result).input,
        )
    }

    /** A denial states no arguments either, and the refusal must not cost the card its payload. */
    @Test
    fun `a denial that restates no arguments leaves the card's arguments alone`() {
        val pending = update(ClaudePToolCallStatus.PENDING_APPROVAL).asInterimToolPart()!!
        val denied = update(ClaudePToolCallStatus.DENIED, arguments = JsonNull).asInterimToolPart()!!

        assertEquals("", denied.input)
        val merged = pending.merge(denied)
        assertEquals(ToolApprovalState.Denied(), merged.approvalState)
        assertEquals(pending.input, merged.input)
        assertEquals("workspace_write", merged.toolName)
    }

    /**
     * A pending card *is* the statement of the arguments, so it always carries them.
     *
     * The other half of the rule above: if this mapping ever translated a real payload to the empty
     * string, the approval barrier would freeze empty arguments for a call the user was shown real
     * ones for.
     */
    @Test
    fun `the card that is approved always states the arguments it was shown`() {
        for (status in listOf(
            ClaudePToolCallStatus.PENDING_APPROVAL,
            ClaudePToolCallStatus.APPROVED,
        )) {
            val arguments = JsonObject(mapOf("path" to JsonPrimitive("/tmp/x")))
            assertEquals(
                "$status carries the arguments it was given",
                arguments.toString(),
                update(status, arguments).asInterimToolPart()!!.input,
            )
        }
        assertEquals(
            "an empty object is a statement of no arguments, not an absent one",
            "{}",
            update(ClaudePToolCallStatus.PENDING_APPROVAL, JsonObject(emptyMap()))
                .asInterimToolPart()!!
                .input,
        )
    }

    /**
     * A refusal reaches the card as a refusal, and the call is never stated as approved.
     *
     * `Denied` is what stops the tool: the bridge reports the refusal instead of running anything,
     * and the card has to say so rather than keep asking the user to decide something they
     * already decided.
     */
    @Test
    fun `a denial replaces the pending card with the refusal`() {
        val pending = update(ClaudePToolCallStatus.PENDING_APPROVAL).asInterimToolPart()!!
        val denied = update(ClaudePToolCallStatus.DENIED).asInterimToolPart()!!

        val merged = pending.merge(denied)
        assertEquals(ToolApprovalState.Denied(), merged.approvalState)
        assertEquals("workspace_write", merged.toolName)
        assertEquals(pending.input, merged.input)
        assertEquals("nothing ran", emptyList<Any>(), merged.output)
    }

    /**
     * A re-stated pending card does not un-decide a call that was already decided.
     *
     * The pending publication is a statement about the call, and a repeat of it is a statement
     * about the call as it was — a slow frame, a replayed turn, a retried publish. It must not
     * replace a decision the user already made, which would put the approval prompt back in front
     * of them for a call that is already running.
     */
    @Test
    fun `a repeated pending statement cannot undo a decision`() {
        val pending = update(ClaudePToolCallStatus.PENDING_APPROVAL).asInterimToolPart()!!
        val approved = update(ClaudePToolCallStatus.APPROVED).asInterimToolPart()!!

        assertEquals(
            ToolApprovalState.Approved,
            pending.merge(approved).merge(pending).approvalState,
        )
        assertEquals(
            ToolApprovalState.Denied(),
            pending.merge(update(ClaudePToolCallStatus.DENIED).asInterimToolPart()!!)
                .merge(pending)
                .approvalState,
        )
    }

    /**
     * A published card is marked as a whole-call statement; nothing else is.
     *
     * The flag is the boundary between the two merge shapes, so it is asserted directly: a card
     * that lost it would silently go back to being appended, and a part from any other provider
     * that acquired one would start being replaced.
     */
    @Test
    fun `only a published card is marked as a whole-call statement`() {
        assertTrue(
            update(ClaudePToolCallStatus.PENDING_APPROVAL).asInterimToolPart()!!.isCallSnapshot,
        )
        assertTrue(
            update(ClaudePToolCallStatus.DENIED).asInterimToolPart()!!.isCallSnapshot,
        )
        assertTrue(
            "a part with no flag keeps the appending merge",
            !UIMessagePart.Tool(toolCallId = "call-2", toolName = "workspace_write", input = "{}")
                .isCallSnapshot,
        )
    }

    /**
     * A provider that streams a call is unaffected: its fragments still concatenate.
     *
     * This is the property the fix must not break, and the reason the merge shape is a flag rather
     * than a rule about fingerprints. A tool name that arrives in two pieces is still one name, and
     * arguments that arrive in three pieces are still one argument list — for every provider that
     * publishes fragments instead of whole calls.
     */
    @Test
    fun `a streaming provider's fragments still concatenate`() {
        val first = UIMessagePart.Tool(
            toolCallId = "call-3",
            toolName = "workspace_",
            input = "{\"pa",
        )
        val second = UIMessagePart.Tool(
            toolCallId = "call-3",
            toolName = "write",
            input = "th\":\"/tmp/x\"}",
        )
        val third = UIMessagePart.Tool(
            toolCallId = "call-3",
            toolName = "",
            input = "",
            output = listOf(UIMessagePart.Text("done")),
            approvalState = ToolApprovalState.Approved,
        )

        val merged = first.merge(second).merge(third)
        assertEquals("workspace_write", merged.toolName)
        assertEquals("{\"path\":\"/tmp/x\"}", merged.input)
        assertEquals(listOf(UIMessagePart.Text("done")), merged.output)
    }

    @Test
    fun `the inert sink accepts without showing anything`() = runBlocking {
        assertEquals(
            ClaudePToolStatusPublication.Accepted,
            ClaudePToolStatusSink.NONE.publish(update(ClaudePToolCallStatus.PENDING_APPROVAL)),
        )
    }
}
