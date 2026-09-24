package me.rerere.ai.provider.claudep

import me.rerere.ai.provider.claudep.bridge.ToolCallOutcome
import me.rerere.ai.provider.claudep.bridge.ToolCallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules for the tool frames Android **sends**, and the wire vocabulary they are written in.
 *
 * Split from `ClaudePToolFrameTest` for a practical reason worth stating: decoding a frame goes
 * through `@Serializable` DTOs, which need the kotlinx-serialization *compiler plugin* to
 * generate their serializers, and that plugin is not available on every machine that runs this
 * suite. Everything in this file constructs its values directly and therefore runs anywhere —
 * including wherever the compiler plugin is missing.
 *
 * These are the rules that decide whether a frame leaves the device at all, so they are the
 * half that most deserves to be checkable in the cheapest possible environment.
 */
class ClaudePToolWireRulesTest {

    // -----------------------------------------------------------------------------------------
    // What Android may report, and what it may not
    // -----------------------------------------------------------------------------------------

    @Test
    fun `an outbound result is sendable only for a state Android may report`() {
        for (state in listOf(
            ClaudePToolCallState.COMPLETED,
            ClaudePToolCallState.DENIED,
            ClaudePToolCallState.FAILED,
            ClaudePToolCallState.CANCELLED,
            ClaudePToolCallState.TIMED_OUT,
        )) {
            assertEquals(
                "$state",
                ClaudePToolFrameVerdict.Sendable,
                ClaudePToolFrames.validateOutboundResult(ClaudePToolResultBody("call-1", state.wireValue)),
            )
        }

        // The four Android may not send are the Server's own verdicts about its own records.
        // `pending` would be a call that never terminates; `disconnected` is something only the
        // Server observes, because Android *is* the end that went away; `not_found` and
        // `conflict` are verdicts about a ledger Android does not hold.
        for (state in listOf(
            ClaudePToolCallState.PENDING,
            ClaudePToolCallState.NOT_FOUND,
            ClaudePToolCallState.CONFLICT,
            ClaudePToolCallState.DISCONNECTED,
            ClaudePToolCallState.UNKNOWN,
        )) {
            assertEquals(
                "$state is a verdict about the Server's ledger, not about a tool Android ran",
                ClaudePToolFrameVerdict.Refused(ClaudePToolFrameRefusal.STATE_NOT_REPORTABLE),
                ClaudePToolFrames.validateOutboundResult(ClaudePToolResultBody("call-1", state.wireValue)),
            )
        }
    }

    @Test
    fun `a body is legal only on completed`() {
        for (state in listOf(
            ClaudePToolCallState.DENIED,
            ClaudePToolCallState.FAILED,
            ClaudePToolCallState.CANCELLED,
            ClaudePToolCallState.TIMED_OUT,
        )) {
            assertEquals(
                "$state must not carry output",
                ClaudePToolFrameVerdict.Refused(ClaudePToolFrameRefusal.BODY_NOT_ALLOWED_FOR_STATE),
                ClaudePToolFrames.validateOutboundResult(
                    ClaudePToolResultBody("call-1", state.wireValue, body = "output"),
                ),
            )
        }

        assertEquals(
            ClaudePToolFrameVerdict.Sendable,
            ClaudePToolFrames.validateOutboundResult(
                ClaudePToolResultBody("call-1", ClaudePToolCallState.COMPLETED.wireValue, body = "output"),
            ),
        )
        // A `completed` with no body is a tool that returned nothing, which is legitimate.
        assertEquals(
            ClaudePToolFrameVerdict.Sendable,
            ClaudePToolFrames.validateOutboundResult(
                ClaudePToolResultBody("call-1", ClaudePToolCallState.COMPLETED.wireValue),
            ),
        )
    }

    @Test
    fun `a malformed call id and an oversized body are refused before sending`() {
        for (bad in listOf("", "x".repeat(129))) {
            assertEquals(
                "call id of length ${bad.length}",
                ClaudePToolFrameVerdict.Refused(ClaudePToolFrameRefusal.CALL_ID_INVALID),
                ClaudePToolFrames.validateOutboundResult(
                    ClaudePToolResultBody(bad, ClaudePToolCallState.COMPLETED.wireValue),
                ),
            )
        }

        assertEquals(
            ClaudePToolFrameVerdict.Refused(ClaudePToolFrameRefusal.RESULT_TOO_LARGE),
            ClaudePToolFrames.validateOutboundResult(
                ClaudePToolResultBody(
                    "call-1",
                    ClaudePToolCallState.COMPLETED.wireValue,
                    body = "x".repeat(65537),
                ),
            ),
        )
    }

    /**
     * An outcome that cannot be reported produces no frame at all.
     *
     * `null` rather than a verdict is the shape the call sites want: a caller that had to handle
     * a sealed verdict at every site would eventually handle it by sending anyway.
     */
    @Test
    fun `an unreportable outcome produces no frame`() {
        assertNull(
            ClaudePToolFrames.asOutboundResult(ToolCallOutcome("call-1", ToolCallState.NOT_FOUND)),
        )
        assertNull(
            ClaudePToolFrames.asOutboundResult(ToolCallOutcome("call-1", ToolCallState.CONFLICT)),
        )
        assertNotNull(
            ClaudePToolFrames.asOutboundResult(
                ToolCallOutcome("call-1", ToolCallState.COMPLETED, "contents"),
            ),
        )
    }

    @Test
    fun `a completed outcome maps to the wire spelling and back`() {
        val body = ClaudePToolFrames.asOutboundResult(
            ToolCallOutcome("call-1", ToolCallState.COMPLETED, "contents"),
        )!!
        assertEquals("completed", body.state)
        assertEquals("contents", body.body)
        assertEquals("call-1", body.toolCallId)
        assertEquals(
            ToolCallState.COMPLETED,
            ClaudePToolCallState.fromWire(body.state).toBridgeState(),
        )
    }

    @Test
    fun `every protocol state round-trips through the wire spelling`() {
        for (state in ToolCallState.entries) {
            assertEquals("$state", state, state.toWireState().toBridgeState())
        }
    }

    // -----------------------------------------------------------------------------------------
    // The wire vocabulary narrows, and an unknown value fails closed
    // -----------------------------------------------------------------------------------------

    @Test
    fun `every contract state has a distinct wire spelling`() {
        val spellings = ClaudePToolCallState.entries
            .filter { it != ClaudePToolCallState.UNKNOWN }
            .map { it.wireValue }
        assertEquals("no two states may share a spelling", spellings.size, spellings.toSet().size)
        assertEquals(9, spellings.size)
    }

    @Test
    fun `an unknown wire state narrows to a sentinel that is neither terminal nor reportable`() {
        val state = ClaudePToolCallState.fromWire("weird")
        assertEquals(ClaudePToolCallState.UNKNOWN, state)
        assertFalse("a state we cannot name must not be concluded from", state.isTerminal())
        assertFalse("nor reported back as if we had concluded it", state.isAndroidReportable())
        assertNull(state.toBridgeState())
    }

    @Test
    fun `an absent wire state is unknown rather than a default`() {
        assertEquals(ClaudePToolCallState.UNKNOWN, ClaudePToolCallState.fromWire(null))
        assertFalse(ClaudePToolCallState.fromWire(null).isTerminal())
    }

    /**
     * A state this build cannot name is not terminal, so a query answered with one keeps the app
     * waiting instead of concluding from a word nobody recognised.
     */
    @Test
    fun `a query result is terminal only for a state this build can name`() {
        assertFalse(
            ClaudePToolFrames.isTerminalQueryResult(
                ClaudePToolQueryResultBody("call-1", ClaudePToolCallState.PENDING.wireValue),
            ),
        )
        assertFalse(
            ClaudePToolFrames.isTerminalQueryResult(ClaudePToolQueryResultBody("call-1", "weird")),
        )
        assertTrue(
            ClaudePToolFrames.isTerminalQueryResult(
                ClaudePToolQueryResultBody("call-1", ClaudePToolCallState.DENIED.wireValue),
            ),
        )
        assertTrue(
            ClaudePToolFrames.isTerminalQueryResult(
                ClaudePToolQueryResultBody("call-1", ClaudePToolCallState.NOT_FOUND.wireValue),
            ),
        )
    }

    @Test
    fun `the app and the contract agree on which states are reportable`() {
        for (state in ClaudePToolCallState.entries) {
            val bridge = state.toBridgeState()
            if (bridge == null) {
                assertFalse("an unnamed state is not reportable", state.isAndroidReportable())
                continue
            }
            assertEquals(
                "$state must mean the same thing on both sides of the wire mapping",
                bridge in me.rerere.ai.provider.claudep.bridge.ANDROID_REPORTABLE_TOOL_CALL_STATES,
                state.isAndroidReportable(),
            )
        }
    }

    /**
     * A frame must never carry a result body into a log line or a snapshot by accident.
     *
     * The `toString` of every tool frame names the call by a redacted reference and reports only
     * *whether* a body is present, because these strings end up in logs and in test failure
     * output, and a result body is opaque tool output that may be anything the tool returned.
     */
    @Test
    fun `a tool frame's toString never carries the result body`() {
        val secret = "SECRET-BODY-CONTENT"
        val rendered = listOf(
            ClaudePToolResultBody("call-1", "completed", secret).toString(),
            ClaudePToolInvokeBody("call-1", "read_file", kotlinx.serialization.json.JsonObject(emptyMap()))
                .toString(),
            ClaudePToolQueryResultBody("call-1", "completed", secret).toString(),
            ClaudePToolCancelBody("call-1").toString(),
            ClaudePToolQueryBody("call-1").toString(),
        )

        for (text in rendered) {
            assertFalse("a frame's toString leaked its body: $text", text.contains(secret))
        }
    }
}
