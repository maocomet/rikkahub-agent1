package me.rerere.ai.provider.claudep

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the deterministic fake gateway.
 *
 * The fake is the only transport this milestone ships, so it carries real weight: if it is
 * permissive, every provider test built on it is permissive too. Several tests below therefore
 * check that it *refuses* things — an unhandshaken call, an unlisted model alias, a conflicting
 * idempotency key — rather than only that it streams happily.
 */
class ClaudePFakeGatewayTest {

    private val hello = ClaudePClientHelloBody(
        appVersion = "test",
        deviceId = "device-1",
        nonce = "nonce",
        signature = "signature",
    )

    private fun startBody(alias: String = "sonnet") = ClaudePGenerationStartBody(
        remoteThreadId = "thread-1",
        remoteBranchId = "branch-1",
        mode = "new",
        modelAlias = alias,
        turn = ClaudePTurn(role = "user", parts = listOf(ClaudePTurnPart("text", "hi"))),
    )

    private fun typesOf(frames: List<String>): List<String> = frames.map { raw ->
        ClaudePProtocol.json.decodeFromString<ClaudePEnvelope>(raw).type
    }

    @Test
    fun `the catalog is fixed and never derived from a CLI version`() = runBlocking {
        val gateway = FakeClaudePGatewayClient()
        gateway.hello(hello)

        val catalog = gateway.catalog()

        assertEquals(listOf("sonnet", "haiku", "opus", "retired"), catalog.models.map { it.alias })
        catalog.models.forEach { entry ->
            assertNotEquals("2.0.1", entry.alias)
            assertTrue(
                "no catalog alias may look like a CLI version: ${entry.alias}",
                !entry.alias.contains("claude-code"),
            )
        }
    }

    @Test
    fun `a generation streams text in order and ends with one terminal`() = runBlocking {
        val gateway = FakeClaudePGatewayClient()
        gateway.hello(hello)
        val handle = gateway.startGeneration("req-1", "fp-1", startBody())

        val frames = handle.frames().toList()
        val types = typesOf(frames)

        assertEquals("generation.accepted", types.first())
        assertEquals("generation.completed", types.last())
        assertEquals(1, types.count { it == "generation.completed" })

        val text = frames.mapNotNull { raw ->
            val envelope = ClaudePProtocol.json.decodeFromString<ClaudePEnvelope>(raw)
            if (envelope.type != ClaudePEventType.TEXT_DELTA) {
                null
            } else {
                (ClaudePProtocol.parseInbound(raw) as ClaudePInbound.Event)
                    .event.let { it as ClaudePServerEvent.TextDelta }
                    .body.text
            }
        }.joinToString("")

        assertEquals("Hello, world", text)
    }

    @Test
    fun `reasoning summaries precede the answer and usage is reported`() = runBlocking {
        val gateway = FakeClaudePGatewayClient()
        gateway.hello(hello)
        val handle = gateway.startGeneration("req-1", "fp-1", startBody())

        val types = typesOf(handle.frames().toList())

        assertTrue(types.indexOf("reasoning.delta") < types.indexOf("text.delta"))
        assertTrue(types.contains("usage.updated"))
    }

    @Test
    fun `a failed script reports a versioned error code`() = runBlocking {
        val gateway = FakeClaudePGatewayClient(
            terminalKind = ClaudePTerminalKind.FAILED,
            failureCode = ClaudePErrorCode.WORKER_BUSY,
        )
        gateway.hello(hello)
        val handle = gateway.startGeneration("req-1", "fp-1", startBody())

        val frames = handle.frames().toList()
        val last = ClaudePProtocol.parseInbound(frames.last()) as ClaudePInbound.Event
        val failed = last.event as ClaudePServerEvent.Failed

        assertEquals(ClaudePErrorCode.WORKER_BUSY, failed.body.safeCode)
        assertEquals("generation.failed", typesOf(frames).last())
    }

    @Test
    fun `cancel after a completed generation returns the original terminal`() = runBlocking {
        val gateway = FakeClaudePGatewayClient()
        gateway.hello(hello)
        val handle = gateway.startGeneration("req-1", "fp-1", startBody())
        val frames = handle.frames().toList()

        val outcome = gateway.cancel(handle.generationId, ClaudePCancelReason.USER_REQUESTED)

        assertEquals(ClaudePCancelOutcome.Terminal(ClaudePTerminalKind.COMPLETED), outcome)
        // §8: an already-terminal generation must not gain a new event.
        assertEquals(frames, handle.frames().toList())
    }

    @Test
    fun `cancel before the terminal ends the generation as cancelled`() = runBlocking {
        val gateway = FakeClaudePGatewayClient()
        gateway.hello(hello)
        val handle = gateway.startGeneration("req-1", "fp-1", startBody())

        val outcome = gateway.cancel(handle.generationId, ClaudePCancelReason.USER_REQUESTED)
        assertEquals(ClaudePCancelOutcome.Terminal(ClaudePTerminalKind.CANCELLED), outcome)

        val types = typesOf(handle.frames().toList())
        assertEquals("generation.cancelled", types.last())
        // The scripted completion must never appear after the cancel won.
        assertTrue(types.none { it == "generation.completed" })
    }

    @Test
    fun `cancel is idempotent and never appends a second terminal`() = runBlocking {
        val gateway = FakeClaudePGatewayClient()
        gateway.hello(hello)
        val handle = gateway.startGeneration("req-1", "fp-1", startBody())

        val first = gateway.cancel(handle.generationId, ClaudePCancelReason.USER_REQUESTED)
        val second = gateway.cancel(handle.generationId, ClaudePCancelReason.USER_REQUESTED)

        assertEquals(first, second)
        val types = typesOf(handle.frames().toList())
        assertEquals(1, types.count { it.startsWith("generation.") && it != "generation.accepted" && it != "generation.started" })
    }

    @Test
    fun `a receipt summarizes a terminal without exposing content`() = runBlocking {
        val gateway = FakeClaudePGatewayClient()
        gateway.hello(hello)
        val handle = gateway.startGeneration("req-1", "fp-1", startBody())
        handle.frames().toList()

        val receipt = gateway.receipt(handle.generationId)

        assertEquals(ClaudePGenerationState.COMPLETED, receipt.safeState)
        assertTrue(receipt.safeState.isTerminal)
        // A receipt is a bounded summary: counts and enums, never answer or reasoning text.
        val rendered = receipt.toString()
        assertFalse(rendered.contains("Hello"))
        assertFalse(rendered.contains("world"))
        assertFalse(rendered.contains("Considering"))
    }

    @Test
    fun `resume replays only events after the acknowledged sequence`() = runBlocking {
        val gateway = FakeClaudePGatewayClient()
        gateway.hello(hello)
        val handle = gateway.startGeneration("req-1", "fp-1", startBody())
        val frames = handle.frames().toList()

        val resumed = gateway.resume(handle.generationId, lastEventSeq = 3)

        val replayed = (resumed as ClaudePResumeResult.Replayed).frames
        assertTrue(replayed.isNotEmpty())
        assertEquals(frames.drop(3), replayed)
        assertEquals(1, gateway.remoteDispatchCount)
    }

    /**
     * The buffer is bounded, so a client that reconnects too late must be told the truth rather
     * than handed an event stream with a hole in it.
     */
    @Test
    fun `resume past the bounded buffer reports a terminal instead of a partial replay`() = runBlocking {
        val gateway = FakeClaudePGatewayClient(replayBufferSize = 2)
        gateway.hello(hello)
        val handle = gateway.startGeneration("req-1", "fp-1", startBody())
        handle.frames().toList()

        val resumed = gateway.resume(handle.generationId, lastEventSeq = 0)

        val terminal = resumed as ClaudePResumeResult.Terminal
        assertEquals(ClaudePGenerationState.COMPLETED, terminal.receipt.safeState)
    }

    @Test
    fun `resume with no provable terminal reports an unknown state`() = runBlocking {
        val gateway = FakeClaudePGatewayClient(replayBufferSize = 1)
        gateway.hello(hello)
        val handle = gateway.startGeneration("req-1", "fp-1", startBody())
        // Frames are never collected, so no terminal has been observed.

        assertEquals(
            ClaudePResumeResult.StateUnknown,
            gateway.resume(handle.generationId, lastEventSeq = 0),
        )
    }

    @Test
    fun `a disconnect surfaces as a typed interruption rather than a silent end`() = runBlocking {
        val gateway = FakeClaudePGatewayClient(disconnectAfterFrames = 2)
        gateway.hello(hello)
        val handle = gateway.startGeneration("req-1", "fp-1", startBody())

        val failure = assertThrows(ClaudePGatewayException::class.java) {
            runBlocking { handle.frames().toList() }
        }

        assertEquals(ClaudePErrorCode.STREAM_INTERRUPTED, failure.code)
    }

    @Test
    fun `the same request id and fingerprint reuses the dispatch`() = runBlocking {
        val gateway = FakeClaudePGatewayClient()
        gateway.hello(hello)

        val first = gateway.startGeneration("req-1", "fp-1", startBody())
        val second = gateway.startGeneration("req-1", "fp-1", startBody())

        assertEquals(first.generationId, second.generationId)
        assertEquals(2, gateway.startGenerationCallCount)
        // The whole point of §7: the retry must not become a second model invocation.
        assertEquals(1, gateway.remoteDispatchCount)
    }

    @Test
    fun `the same request id with a different fingerprint conflicts`() = runBlocking {
        val gateway = FakeClaudePGatewayClient()
        gateway.hello(hello)
        gateway.startGeneration("req-1", "fp-1", startBody())

        val failure = assertThrows(ClaudePGatewayException::class.java) {
            runBlocking { gateway.startGeneration("req-1", "fp-different", startBody()) }
        }

        assertEquals(ClaudePErrorCode.IDEMPOTENCY_CONFLICT, failure.code)
        assertEquals(1, gateway.remoteDispatchCount)
    }

    @Test
    fun `an alias outside the catalog never reaches the runtime`() = runBlocking {
        val gateway = FakeClaudePGatewayClient()
        gateway.hello(hello)

        // "retired" exists but is disabled; the CLI version is not an alias at all.
        listOf("retired", "2.0.1", "claude-code-2.0.1").forEach { alias ->
            val failure = assertThrows(ClaudePGatewayException::class.java) {
                runBlocking { gateway.startGeneration("req-$alias", "fp-1", startBody(alias)) }
            }
            assertEquals(ClaudePErrorCode.MODEL_NOT_ALLOWED, failure.code)
        }
        assertEquals(0, gateway.remoteDispatchCount)
    }

    @Test
    fun `a rejected handshake produces zero dispatch`() = runBlocking {
        val gateway = FakeClaudePGatewayClient(
            handshake = FakeHandshake.Reject(ClaudePErrorCode.AUTHENTICATION_REQUIRED),
        )

        val failure = assertThrows(ClaudePGatewayException::class.java) {
            runBlocking { gateway.hello(hello) }
        }

        assertEquals(ClaudePErrorCode.AUTHENTICATION_REQUIRED, failure.code)
        assertEquals(0, gateway.remoteDispatchCount)
    }

    @Test
    fun `calls before the handshake are refused`() = runBlocking {
        val gateway = FakeClaudePGatewayClient()

        val failure = assertThrows(ClaudePGatewayException::class.java) {
            runBlocking { gateway.catalog() }
        }

        assertEquals(ClaudePErrorCode.AUTHENTICATION_REQUIRED, failure.code)
    }

    @Test
    fun `a malformed protocol frame is produced on demand for rejection tests`() = runBlocking {
        val gateway = FakeClaudePGatewayClient(frameProtocolId = "rikkahub.claude-p.v2")
        gateway.hello(hello)
        val handle = gateway.startGeneration("req-1", "fp-1", startBody())

        val first = handle.frames().toList().first()

        assertEquals(
            ClaudePParseRejection.PROTOCOL_MAJOR_MISMATCH,
            (ClaudePProtocol.parseInbound(first) as ClaudePInbound.Rejected).reason,
        )
    }

    @Test
    fun `extra frames after the terminal are emitted so rejection can be exercised`() = runBlocking {
        val gateway = FakeClaudePGatewayClient(
            extraFramesAfterTerminal = listOf(
                FakeFrame(ClaudePEventType.TEXT_DELTA, body = buildTextDelta("late")),
            ),
        )
        gateway.hello(hello)
        val handle = gateway.startGeneration("req-1", "fp-1", startBody())

        val types = typesOf(handle.frames().toList())

        assertEquals("generation.completed", types[types.size - 2])
        assertEquals("text.delta", types.last())
    }
}

private fun buildTextDelta(text: String) = kotlinx.serialization.json.buildJsonObject {
    kotlinx.serialization.json.put("text", text)
}
