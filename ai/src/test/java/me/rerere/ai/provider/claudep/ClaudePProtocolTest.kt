package me.rerere.ai.provider.claudep

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-protocol contract tests.
 *
 * These are the tests that decide whether a hostile or merely newer gateway can make this client
 * do something unsafe, so they assert on the *rejection* path as much as the happy one.
 */
class ClaudePProtocolTest {

    private fun frame(
        type: String,
        protocol: String = ClaudePProtocol.PROTOCOL_ID,
        sequence: Long = 1,
        body: JsonObject = JsonObject(emptyMap()),
    ): String = ClaudePProtocol.json.encodeToString(
        ClaudePEnvelope(
            protocol = protocol,
            type = type,
            generationId = "gen-1",
            sequence = sequence,
            body = body,
        ),
    )

    @Test
    fun `a v1 frame with a known type is routed`() {
        val inbound = ClaudePProtocol.parseInbound(
            frame(
                ClaudePEventType.TEXT_DELTA,
                body = buildJsonObject {
                    put("text", "hi")
                    put("index", 0)
                },
            ),
        )

        assertTrue(inbound is ClaudePInbound.Event)
        val event = (inbound as ClaudePInbound.Event).event
        assertTrue(event is ClaudePServerEvent.TextDelta)
        assertEquals("hi", (event as ClaudePServerEvent.TextDelta).body.text)
    }

    @Test
    fun `a different protocol major is rejected even for a known type`() {
        val inbound = ClaudePProtocol.parseInbound(
            frame(ClaudePEventType.TEXT_DELTA, protocol = "rikkahub.claude-p.v2"),
        )

        assertEquals(
            ClaudePParseRejection.PROTOCOL_MAJOR_MISMATCH,
            (inbound as ClaudePInbound.Rejected).reason,
        )
    }

    /**
     * The doc allows unknown *events* and forbids unknown *majors*. Checking the type first would
     * silently accept a v2 frame whose type happens to be spelled the same, so this ordering is a
     * real safety property rather than a style choice.
     */
    @Test
    fun `protocol major is checked before the event type is trusted`() {
        val inbound = ClaudePProtocol.parseInbound(
            frame("tool.requested", protocol = "rikkahub.claude-p.v9"),
        )

        assertEquals(
            ClaudePParseRejection.PROTOCOL_MAJOR_MISMATCH,
            (inbound as ClaudePInbound.Rejected).reason,
        )
    }

    @Test
    fun `a missing protocol field is rejected`() {
        val inbound = ClaudePProtocol.parseInbound(
            frame(ClaudePEventType.TEXT_DELTA, protocol = ""),
        )

        assertEquals(
            ClaudePParseRejection.MISSING_PROTOCOL,
            (inbound as ClaudePInbound.Rejected).reason,
        )
    }

    @Test
    fun `an unversioned protocol identifier is rejected rather than assumed to be v1`() {
        val inbound = ClaudePProtocol.parseInbound(
            frame(ClaudePEventType.TEXT_DELTA, protocol = "rikkahub.claude-p"),
        )

        assertEquals(
            ClaudePParseRejection.MALFORMED_PROTOCOL,
            (inbound as ClaudePInbound.Rejected).reason,
        )
    }

    @Test
    fun `undecodable json is rejected with a content-free reason`() {
        val inbound = ClaudePProtocol.parseInbound("{ this is not json")

        assertEquals(
            ClaudePParseRejection.MALFORMED_FRAME,
            (inbound as ClaudePInbound.Rejected).reason,
        )
    }

    @Test
    fun `an unknown optional event is ignored, not treated as text or a terminal`() {
        // tool.* belongs to Phase 3. Treating it as content would leak tool arguments into the
        // transcript; treating it as a terminal would end the generation early.
        val inbound = ClaudePProtocol.parseInbound(
            frame(
                "tool.requested",
                body = buildJsonObject {
                    put("call_id", "call-1")
                    put("name", "write_file")
                },
            ),
        )

        assertEquals(ClaudePInbound.IgnoredUnknownEvent, inbound)
    }

    @Test
    fun `an empty type is rejected`() {
        val inbound = ClaudePProtocol.parseInbound(frame(""))

        assertEquals(
            ClaudePParseRejection.MISSING_TYPE,
            (inbound as ClaudePInbound.Rejected).reason,
        )
    }

    @Test
    fun `a known type with a body that does not match the schema is rejected`() {
        val inbound = ClaudePProtocol.parseInbound(
            frame(
                ClaudePEventType.TEXT_DELTA,
                body = buildJsonObject { put("index", "not-a-number") },
            ),
        )

        assertEquals(
            ClaudePParseRejection.MALFORMED_EVENT_BODY,
            (inbound as ClaudePInbound.Rejected).reason,
        )
    }

    @Test
    fun `server protocol version accepts both the bare and qualified spelling`() {
        assertTrue(ClaudePProtocol.acceptsServerProtocolVersion("v1"))
        assertTrue(ClaudePProtocol.acceptsServerProtocolVersion("rikkahub.claude-p.v1"))
        assertFalse(ClaudePProtocol.acceptsServerProtocolVersion("v2"))
        assertFalse(ClaudePProtocol.acceptsServerProtocolVersion("rikkahub.claude-p.v2"))
        // Missing or unparseable must fail closed, never default to "assume v1".
        assertFalse(ClaudePProtocol.acceptsServerProtocolVersion(null))
        assertFalse(ClaudePProtocol.acceptsServerProtocolVersion(""))
        assertFalse(ClaudePProtocol.acceptsServerProtocolVersion("garbage"))
    }

    @Test
    fun `an unrecognised error code collapses to the generic safe enum`() {
        // The raw string is untrusted remote input; it must never be retained or displayed.
        assertEquals(
            ClaudePErrorCode.EXTERNAL_RUNTIME_ERROR,
            ClaudePErrorCode.fromWire("something_new_from_a_future_gateway"),
        )
        assertEquals(
            ClaudePErrorCode.AUTHENTICATION_REQUIRED,
            ClaudePErrorCode.fromWire("authentication_required"),
        )
    }

    @Test
    fun `a failed body exposes only the safe enum`() {
        val body = ClaudePFailedBody(errorCode = "definitely_not_a_real_code")

        assertEquals(ClaudePErrorCode.EXTERNAL_RUNTIME_ERROR, body.safeCode)
        assertFalse(body.toString().contains("definitely_not_a_real_code"))
    }

    @Test
    fun `a receipt state that is not recognised becomes unknown rather than terminal`() {
        assertEquals(ClaudePGenerationState.UNKNOWN, ClaudePGenerationState.fromWire("wat"))
        assertFalse(ClaudePGenerationState.fromWire("wat").isTerminal)
        assertTrue(ClaudePGenerationState.COMPLETED.isTerminal)
    }

    @Test
    fun `envelope toString never exposes its body`() {
        val secret = "sk-ant-oat01-super-secret-prompt"
        val envelope = ClaudePEnvelope(
            type = ClaudePEventType.TEXT_DELTA,
            body = buildJsonObject { put("text", secret) },
        )

        val rendered = envelope.toString()
        assertFalse(rendered.contains(secret))
        assertTrue(rendered.contains("<redacted:1 fields>"))
    }

    @Test
    fun `content-bearing DTOs redact their payload in toString`() {
        val promptText = "the user's private question"
        val answer = "the model's private answer"
        val summary = "the model's private reasoning"

        val start = ClaudePGenerationStartBody(
            remoteThreadId = "thread-1",
            remoteBranchId = "branch-1",
            mode = "new",
            modelAlias = "sonnet",
            systemPrompt = promptText,
            turn = ClaudePTurn(
                role = "user",
                parts = listOf(ClaudePTurnPart(type = "text", text = promptText)),
            ),
        )

        val deltas = listOf(
            ClaudePTextDeltaBody(text = answer).toString(),
            ClaudePReasoningDeltaBody(summary = summary).toString(),
            start.toString(),
        )

        deltas.forEach { rendered ->
            assertFalse(rendered.contains(promptText))
            assertFalse(rendered.contains(answer))
            assertFalse(rendered.contains(summary))
        }
    }

    @Test
    fun `client hello redacts the nonce and signature`() {
        val rendered = ClaudePClientHelloBody(
            appVersion = "1.0",
            deviceId = "device-abc",
            nonce = "nonce-value",
            signature = "signature-value",
        ).toString()

        assertFalse(rendered.contains("nonce-value"))
        assertFalse(rendered.contains("signature-value"))
        // The device id is an opaque identifier, but it is still reduced rather than printed.
        assertFalse(rendered.contains("device-abc"))
    }

    @Test
    fun `the terminal gate admits exactly one terminal`() {
        val gate = ClaudePTerminalGate()

        assertTrue(gate.acceptsDeltas)
        assertTrue(gate.tryAccept(ClaudePTerminalKind.COMPLETED))
        assertFalse(gate.tryAccept(ClaudePTerminalKind.CANCELLED))
        assertFalse(gate.tryAccept(ClaudePTerminalKind.FAILED))

        assertEquals(ClaudePTerminalKind.COMPLETED, gate.terminal)
        assertFalse(gate.acceptsDeltas)
    }
}

/**
 * Fingerprint tests.
 *
 * §7 makes the fingerprint the thing that distinguishes "retry the same request" from "a different
 * request wearing the same id", so a collision here would silently replay the wrong generation.
 */
class ClaudePRequestFingerprintTest {

    private fun fingerprint(
        systemPrompt: String? = "system",
        turnText: String = "hello",
        thread: String = "thread-1",
        branch: String = "branch-1",
    ): String = ClaudePRequestFingerprint.compute(
        deviceId = "device-1",
        remoteThreadId = thread,
        remoteBranchId = branch,
        mode = "new",
        modelAlias = "sonnet",
        systemPrompt = systemPrompt,
        turn = ClaudePTurn(role = "user", parts = listOf(ClaudePTurnPart("text", turnText))),
    )

    @Test
    fun `the same request produces a stable digest`() {
        assertEquals(fingerprint(), fingerprint())
    }

    @Test
    fun `changing any bound field changes the digest`() {
        val base = fingerprint()

        assertNotEquals(base, fingerprint(systemPrompt = "different system"))
        assertNotEquals(base, fingerprint(turnText = "different turn"))
        assertNotEquals(base, fingerprint(thread = "thread-2"))
        assertNotEquals(base, fingerprint(branch = "branch-2"))
    }

    @Test
    fun `field boundaries are unambiguous`() {
        // Length-prefixing means these two different requests cannot collide by concatenation.
        val a = fingerprint(thread = "ab", branch = "c")
        val b = fingerprint(thread = "a", branch = "bc")

        assertNotEquals(a, b)
    }

    @Test
    fun `a missing system prompt is distinct from an empty one`() {
        assertNotEquals(fingerprint(systemPrompt = null), fingerprint(systemPrompt = ""))
    }
}
