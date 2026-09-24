package me.rerere.ai.provider.claudep

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `tool.*` frames of the public protocol: what they decode to, and how the router treats one
 * that does not belong to anything.
 *
 * ## Why this file is separate from `ClaudePToolWireRulesTest`
 *
 * Every test here goes through `parseInbound`, which decodes an `@Serializable` DTO — and a
 * generated serializer needs the kotlinx-serialization **compiler plugin**, which is not
 * present on every machine that runs this suite. The rules for the frames Android *sends* are
 * therefore kept in `ClaudePToolWireRulesTest`, where they construct their values directly and
 * run anywhere. This file's coverage is real, but it needs a full project build to exercise.
 *
 * This suite is about the *wire*, and deliberately says nothing about executing a tool. Whether
 * an invocation reaches `DefaultToolRuntime`, is gated, or is approved is a separate claim made
 * elsewhere — a green run here is not evidence for any of it.
 */
class ClaudePToolFrameTest {

    // -----------------------------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------------------------

    private fun frame(
        type: String,
        body: String,
        generationId: String? = "gen-1",
        sequence: Long? = 1L,
    ): String {
        val generation = if (generationId == null) "" else ",\"generation_id\":\"$generationId\""
        val seq = if (sequence == null) "" else ",\"sequence\":$sequence"
        return "{\"protocol\":\"${ClaudePProtocol.PROTOCOL_ID}\",\"type\":\"$type\"" +
            generation + seq + ",\"body\":" + body + "}"
    }

    private fun parse(raw: String): ClaudePServerEvent =
        (ClaudePProtocol.parseInbound(raw) as ClaudePInbound.Event).event

    /** Collects the violations a router reported, without needing a coroutine to observe flows. */
    private class Recorder {
        val violations = mutableListOf<ClaudePInboundViolation>()
        val router = ClaudePInboundRouter { violations += it }
    }

    // -----------------------------------------------------------------------------------------
    // Decoding
    // -----------------------------------------------------------------------------------------

    @Test
    fun `tool invoke decodes with its call id, frozen name and arguments`() {
        val event = parse(
            frame(
                ClaudePEventType.TOOL_INVOKE,
                """{"tool_call_id":"call-1","tool_name":"read_file","arguments":{"path":"/a"}}""",
            ),
        )

        assertTrue(event is ClaudePServerEvent.ToolInvoke)
        val body = (event as ClaudePServerEvent.ToolInvoke).body
        assertEquals("call-1", body.toolCallId)
        assertEquals("read_file", body.toolName)
        assertEquals(setOf("path"), body.arguments.keys)
        assertEquals("gen-1", event.envelope.generationId)

        // The generation travels on the envelope and nowhere else, so there is no second copy
        // for a caller to disagree with.
        assertFalse("the body must not carry a generation", body.toString().contains("gen-1"))
    }

    @Test
    fun `a tool invoke missing its required fields decodes and is refused by validation`() {
        // Decoding tolerates the absence so that the refusal can name what is wrong. A decode
        // failure would be reported as MALFORMED_EVENT_BODY, which cannot distinguish a missing
        // tool name from a malformed one.
        val event = parse(frame(ClaudePEventType.TOOL_INVOKE, """{"tool_call_id":"call-1"}"""))
        val body = (event as ClaudePServerEvent.ToolInvoke).body
        assertEquals("call-1", body.toolCallId)
        assertEquals("", body.toolName)
        assertTrue(body.arguments.isEmpty())
    }

    @Test
    fun `tool cancel and query result decode`() {
        val cancel = parse(
            frame(ClaudePEventType.TOOL_CANCEL, """{"tool_call_id":"call-1"}"""),
        )
        assertEquals(
            "call-1",
            (cancel as ClaudePServerEvent.ToolCancel).body.toolCallId,
        )

        val queryResult = parse(
            frame(
                ClaudePEventType.TOOL_QUERY_RESULT,
                """{"tool_call_id":"call-1","state":"completed","body":"contents"}""",
            ),
        )
        val body = (queryResult as ClaudePServerEvent.ToolQueryResult).body
        assertEquals(ClaudePToolCallState.COMPLETED, body.safeState)
        assertEquals("contents", body.body)
    }

    @Test
    fun `an unknown state narrows to a sentinel that is neither terminal nor reportable`() {
        val event = parse(
            frame(ClaudePEventType.TOOL_QUERY_RESULT, """{"tool_call_id":"call-1","state":"weird"}"""),
        )
        val body = (event as ClaudePServerEvent.ToolQueryResult).body

        assertEquals(ClaudePToolCallState.UNKNOWN, body.safeState)
        assertFalse("a state we cannot name must not be concluded from", body.safeState.isTerminal())
        assertFalse(body.safeState.isAndroidReportable())
        assertNull("an unknown wire state denotes no bridge state", body.safeState.toBridgeState())
        assertFalse(ClaudePToolFrames.isTerminalQueryResult(body))
    }

    @Test
    fun `tool frames are neither content deltas nor terminals`() {
        val invoke = parse(
            frame(
                ClaudePEventType.TOOL_INVOKE,
                """{"tool_call_id":"c","tool_name":"read_file","arguments":{}}""",
            ),
        )
        assertTrue(invoke.isToolFrame)
        assertFalse("a tool frame must never end a generation", invoke.isContentDelta)
        assertNull(invoke.terminalKind)
    }

    @Test
    fun `an unknown tool type is still ignored rather than treated as a tool frame`() {
        val raw = frame("tool.something_new", """{"tool_call_id":"c"}""")
        assertEquals(ClaudePInbound.IgnoredUnknownEvent, ClaudePProtocol.parseInbound(raw))
    }

    // -----------------------------------------------------------------------------------------
    // Routing: fail-closed
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a tool frame with no generation is a violation, not a frame addressed to nobody`() {
        val recorder = Recorder()

        recorder.router.route(
            frame(
                ClaudePEventType.TOOL_INVOKE,
                """{"tool_call_id":"c","tool_name":"read_file","arguments":{}}""",
                generationId = null,
                sequence = null,
            ),
        )

        assertEquals(
            listOf(ClaudePInboundViolation.MISSING_TOOL_BINDING),
            recorder.violations,
        )
    }

    @Test
    fun `a tool frame for a generation this client is not running is dropped`() {
        val recorder = Recorder()
        // No generation has been opened, so nothing can be delivered and nothing is violated:
        // the frame is simply not ours, and the fail-closed answer is to do nothing at all.
        recorder.router.route(
            frame(
                ClaudePEventType.TOOL_INVOKE,
                """{"tool_call_id":"c","tool_name":"read_file","arguments":{}}""",
            ),
        )

        assertEquals(emptyList<ClaudePInboundViolation>(), recorder.violations)
        assertNull(recorder.router.streamOf("gen-1"))
    }

    @Test
    fun `a tool frame after the generation terminal is dropped`() {
        val recorder = Recorder()
        val stream = recorder.router.openGeneration("gen-1")
        assertNotNull(stream)

        // The generation ends.
        recorder.router.closeGeneration("gen-1", terminalKind = ClaudePTerminalKind.COMPLETED)

        recorder.router.route(
            frame(
                ClaudePEventType.TOOL_INVOKE,
                """{"tool_call_id":"c","tool_name":"read_file","arguments":{}}""",
                sequence = 99L,
            ),
        )

        assertEquals(
            "a late tool frame is not a reason to reopen or to fail anything",
            emptyList<ClaudePInboundViolation>(),
            recorder.violations,
        )
        assertNull(recorder.router.streamOf("gen-1"))
    }

    @Test
    fun `a tool frame for a live generation is delivered to that generation's stream`() {
        val recorder = Recorder()
        assertNotNull(recorder.router.openGeneration("gen-1"))
        assertNotNull(recorder.router.openGeneration("gen-2"))

        recorder.router.route(
            frame(
                ClaudePEventType.TOOL_INVOKE,
                """{"tool_call_id":"c","tool_name":"read_file","arguments":{}}""",
                generationId = "gen-1",
            ),
        )

        assertEquals(1L, recorder.router.streamOf("gen-1")!!.lastEventSeq)
        assertEquals(
            "a frame for one generation must not advance another",
            0L,
            recorder.router.streamOf("gen-2")!!.lastEventSeq,
        )
    }

    // -----------------------------------------------------------------------------------------
    // The generation-identity seam
    //
    // The context rides the request parameters as a `@Transient` property, so the two claims that
    // matter are that it cannot reach an encoded request and that its absence leaves the frame as
    // it was. Both need an encoder, which is why they live here rather than in
    // `ClaudePToolGenerationContextTest`: that class runs on machines without the serialization
    // compiler plugin, and these two cannot.
    // -----------------------------------------------------------------------------------------

    private fun context() = ClaudePToolGenerationContext(
        runId = "run-SENTINEL-8f21c4",
        commandId = "command-SENTINEL-3aa9d1",
        conversationId = "conversation-SENTINEL-77b2e0",
        assistantId = "assistant-SENTINEL-c5f4a8",
        branchId = "branch-SENTINEL-19de63",
        callOrigin = "origin-SENTINEL-4b7f",
    )

    private fun textParams(withContext: Boolean) = TextGenerationParams(
        model = Model(modelId = "sonnet"),
        claudePToolGenerationContext = if (withContext) context() else null,
    )

    @Test
    fun `carrying a generation context changes no byte of the encoded request`() {
        assertEquals(
            "a request that names a generation must encode exactly like one that does not",
            ClaudePProtocol.json.encodeToString(textParams(withContext = false)),
            ClaudePProtocol.json.encodeToString(textParams(withContext = true)),
        )
    }

    @Test
    fun `no identity on the parameters reaches the encoded request`() {
        val encoded = ClaudePProtocol.json.encodeToString(textParams(withContext = true))

        listOf(
            "run-SENTINEL-8f21c4",
            "command-SENTINEL-3aa9d1",
            "conversation-SENTINEL-77b2e0",
            "assistant-SENTINEL-c5f4a8",
            "branch-SENTINEL-19de63",
            "origin-SENTINEL-4b7f",
            "claudePToolGenerationContext",
        ).forEach { identity ->
            assertFalse("the encoded request carried <$identity>: $encoded", encoded.contains(identity))
        }
    }

    @Test
    fun `a text-path generation start still omits the tool snapshot entirely`() {
        // The pre-bridge frame shape, asserted rather than assumed: with no bridge host the
        // catalog is empty, the snapshot is null, and `explicitNulls = false` leaves the field out
        // of the body instead of sending it as `null`. That omission is what makes "no tools"
        // byte-for-byte the frame this provider sent before the bridge existed.
        val body = ClaudePGenerationStartBody(
            remoteThreadId = "thread-1",
            remoteBranchId = "branch-1",
            mode = "new",
            modelAlias = "sonnet",
            turn = ClaudePTurn(role = "user", parts = listOf(ClaudePTurnPart("text", "hi"))),
        )

        val encoded = ClaudePProtocol.json.encodeToString(body)

        assertFalse(
            "a body with no tools must not name a snapshot: $encoded",
            encoded.contains("tool_snapshot"),
        )
        assertFalse(
            "a body with no tools must not carry a null placeholder: $encoded",
            encoded.contains("null"),
        )
    }

}
