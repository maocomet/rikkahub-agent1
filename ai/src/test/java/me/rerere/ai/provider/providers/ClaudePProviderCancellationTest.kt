package me.rerere.ai.provider.providers

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.claudep.ClaudePCancelReason
import me.rerere.ai.provider.claudep.ClaudePClientHelloBody
import me.rerere.ai.provider.claudep.ClaudePGatewayException
import me.rerere.ai.provider.claudep.ClaudePGenerationState
import me.rerere.ai.provider.claudep.ClaudePGenerationStartBody
import me.rerere.ai.provider.claudep.ClaudePResumeKind
import me.rerere.ai.provider.claudep.ClaudePTurn
import me.rerere.ai.provider.claudep.ClaudePTurnPart
import me.rerere.ai.provider.claudep.FakeClaudePGatewayClient
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cancellation, idempotency and reconnect.
 *
 * These are the paths where a mistake costs the user money or duplicates a side effect, so every
 * case here is driven by explicit, deterministic events — no sleeps, no timing races. Where the
 * property is "exactly once", the assertion is on the gateway's own RPC counter rather than on the
 * client's internal flags.
 */
class ClaudePProviderCancellationTest {

    private val setting = ProviderSetting.ClaudeP()
    private val sonnet = Model(modelId = "sonnet", displayName = "Claude Sonnet")

    private val hello = ClaudePClientHelloBody(
        appVersion = "test",
        deviceId = "device-1",
        nonce = "nonce",
        signature = "signature",
    )

    private fun provider(
        gateway: FakeClaudePGatewayClient,
        requestId: String = "req-fixed",
    ) = ClaudePProvider(gateway = gateway, requestIdFactory = { requestId })

    private fun userMessage() = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text("hello")),
    )

    private fun params() = TextGenerationParams(model = sonnet)

    // ---------------------------------------------------------------------------------------
    // Cancellation
    // ---------------------------------------------------------------------------------------

    @Test
    fun `cancelling mid-stream sends exactly one cancel`() = runBlocking {
        val gateway = FakeClaudePGatewayClient()
        val chunks = provider(gateway)
            .streamText(setting, listOf(userMessage()), params())
            // Stops collecting while the generation is still in flight.
            .take(3)
            .toList()

        assertEquals(3, chunks.size)
        assertEquals(1, gateway.cancelCallCount)
    }

    @Test
    fun `repeated cancellation signals still send exactly one cancel`() = runBlocking {
        val gateway = FakeClaudePGatewayClient()
        gateway.hello(hello)
        val handle = gateway.startGeneration(
            requestId = "req-1",
            fingerprint = "fp-1",
            body = ClaudePGenerationStartBody(
                remoteThreadId = "thread-1",
                remoteBranchId = "branch-1",
                mode = "new",
                modelAlias = "sonnet",
                turn = ClaudePTurn("user", listOf(ClaudePTurnPart("text", "hi"))),
            ),
        )

        val attempt = ClaudePGenerationAttempt(gateway)
        attempt.bind(handle)

        // Cancellation can legitimately be observed more than once (flow cancellation plus an
        // upstream watchdog). The gateway is idempotent, but the client must not spam it.
        assertTrue(attempt.cancelOnce())
        assertFalse(attempt.cancelOnce())
        assertFalse(attempt.cancelOnce())

        assertEquals(1, gateway.cancelCallCount)
    }

    /**
     * The race this guards: the user taps stop at almost the same moment the answer completes.
     * The completion already won, so there is nothing to cancel — and cancelling anyway would
     * invite a second terminal.
     */
    @Test
    fun `a cancel racing a completed terminal sends no cancel and yields one terminal`() = runBlocking {
        // Discover how many chunks a full run produces, so the second run stops exactly on the
        // terminal rather than on a guess.
        val total = provider(FakeClaudePGatewayClient())
            .streamText(setting, listOf(userMessage()), params())
            .toList()
            .size

        val gateway = FakeClaudePGatewayClient()
        val chunks = provider(gateway)
            .streamText(setting, listOf(userMessage()), params())
            .take(total)
            .toList()

        assertEquals(total, chunks.size)
        assertEquals(1, chunks.mapNotNull { it.resolvedTerminal() }.size)
        assertEquals(0, gateway.cancelCallCount)
    }

    @Test
    fun `an attempt whose terminal was observed does not cancel`() = runBlocking {
        val gateway = FakeClaudePGatewayClient()
        gateway.hello(hello)
        val handle = gateway.startGeneration(
            requestId = "req-1",
            fingerprint = "fp-1",
            body = ClaudePGenerationStartBody(
                remoteThreadId = "thread-1",
                remoteBranchId = "branch-1",
                mode = "new",
                modelAlias = "sonnet",
                turn = ClaudePTurn("user", listOf(ClaudePTurnPart("text", "hi"))),
            ),
        )
        handle.frames().toList()

        val attempt = ClaudePGenerationAttempt(gateway)
        attempt.bind(handle)
        attempt.markTerminal()

        assertFalse(attempt.cancelOnce())
        assertEquals(0, gateway.cancelCallCount)
    }

    @Test
    fun `cancelling an unbound attempt is a no-op`() = runBlocking {
        val gateway = FakeClaudePGatewayClient()

        assertFalse(ClaudePGenerationAttempt(gateway).cancelOnce())
        assertEquals(0, gateway.cancelCallCount)
    }

    // ---------------------------------------------------------------------------------------
    // Reconnect
    // ---------------------------------------------------------------------------------------

    @Test
    fun `reconnect replays events without a second generation`() = runBlocking {
        val gateway = FakeClaudePGatewayClient()
        val instance = provider(gateway)
        instance.streamText(setting, listOf(userMessage()), params()).toList()

        val generationId = requireNotNull(gateway.lastGenerationId)
        val dispatchesBefore = gateway.remoteDispatchCount
        val startsBefore = gateway.startGenerationCallCount

        val outcomes = instance.resumeStream(generationId, lastEventSeq = 3).toList()

        // The whole point of reconnect: replay only. A dropped connection must never buy a
        // second model invocation.
        assertEquals(dispatchesBefore, gateway.remoteDispatchCount)
        assertEquals(startsBefore, gateway.startGenerationCallCount)

        assertTrue(outcomes.first() is ClaudePResumeOutcome.Replaying)
        assertTrue(outcomes.any { it is ClaudePResumeOutcome.Chunk })
        assertTrue(
            outcomes.filterIsInstance<ClaudePResumeOutcome.Chunk>()
                .mapNotNull { it.chunk.resolvedTerminal() }
                .size <= 1,
        )
    }

    @Test
    fun `reconnect into an expired buffer reports the terminal instead of replaying`() = runBlocking {
        val gateway = FakeClaudePGatewayClient(replayBufferSize = 2)
        val instance = provider(gateway)
        instance.streamText(setting, listOf(userMessage()), params()).toList()

        val outcomes = instance.resumeStream(
            requireNotNull(gateway.lastGenerationId),
            lastEventSeq = 0,
        ).toList()

        val terminal = outcomes.single() as ClaudePResumeOutcome.Terminal
        assertEquals(ClaudePGenerationState.COMPLETED, terminal.state)
        assertEquals(1, gateway.remoteDispatchCount)
    }

    @Test
    fun `reconnect with nothing provable reports unknown and never retries`() = runBlocking {
        val gateway = FakeClaudePGatewayClient(replayBufferSize = 1)
        gateway.hello(hello)
        val handle = gateway.startGeneration(
            requestId = "req-1",
            fingerprint = "fp-1",
            body = ClaudePGenerationStartBody(
                remoteThreadId = "thread-1",
                remoteBranchId = "branch-1",
                mode = "new",
                modelAlias = "sonnet",
                turn = ClaudePTurn("user", listOf(ClaudePTurnPart("text", "hi"))),
            ),
        )
        val instance = provider(gateway)
        val dispatchesBefore = gateway.remoteDispatchCount

        val outcomes = instance.resumeStream(handle.generationId, lastEventSeq = 0).toList()

        assertEquals(listOf(ClaudePResumeOutcome.StateUnknown), outcomes)
        // D-007: an unprovable state is handed to the user, never auto-retried.
        assertEquals(dispatchesBefore, gateway.remoteDispatchCount)
    }

    @Test
    fun `reconnect surfaces a replaying outcome that marks itself as such`() = runBlocking {
        val gateway = FakeClaudePGatewayClient()
        val instance = provider(gateway)
        instance.streamText(setting, listOf(userMessage()), params()).toList()

        val first = instance.resumeStream(
            requireNotNull(gateway.lastGenerationId),
            lastEventSeq = 0,
        ).toList().first()

        assertEquals(
            ClaudePResumeKind.REPLAYED,
            (first as ClaudePResumeOutcome.Replaying).kind,
        )
    }

    // ---------------------------------------------------------------------------------------
    // Single-shot streams
    // ---------------------------------------------------------------------------------------

    @Test
    fun `collecting the same stream twice does not dispatch a second generation`() = runBlocking {
        val gateway = FakeClaudePGatewayClient()
        val flow = provider(gateway).streamText(setting, listOf(userMessage()), params())

        flow.toList()
        assertEquals(1, gateway.remoteDispatchCount)

        // The second collection must fail loudly rather than silently re-running the generation.
        assertThrows(IllegalStateException::class.java) {
            runBlocking { flow.toList() }
        }
        assertEquals(1, gateway.remoteDispatchCount)
        assertEquals(1, gateway.startGenerationCallCount)
    }

    @Test
    fun `a failed generation is not retried automatically`() = runBlocking {
        val gateway = FakeClaudePGatewayClient(
            terminalKind = me.rerere.ai.provider.claudep.ClaudePTerminalKind.FAILED,
        )
        val instance = provider(gateway)

        val chunks = instance.streamText(setting, listOf(userMessage()), params()).toList()

        assertEquals(1, chunks.mapNotNull { it.resolvedTerminal() }.size)
        assertEquals(1, gateway.remoteDispatchCount)
    }

    @Test
    fun `an interrupted stream does not resurrect the generation`() = runBlocking {
        val gateway = FakeClaudePGatewayClient(disconnectAfterFrames = 2)
        val instance = provider(gateway)

        assertThrows(ClaudePGatewayException::class.java) {
            runBlocking { instance.streamText(setting, listOf(userMessage()), params()).toList() }
        }

        assertEquals(1, gateway.remoteDispatchCount)
    }

    @Test
    fun `a cancel reason is carried on the wire`() = runBlocking {
        val gateway = FakeClaudePGatewayClient()
        gateway.hello(hello)
        val handle = gateway.startGeneration(
            requestId = "req-1",
            fingerprint = "fp-1",
            body = ClaudePGenerationStartBody(
                remoteThreadId = "thread-1",
                remoteBranchId = "branch-1",
                mode = "new",
                modelAlias = "sonnet",
                turn = ClaudePTurn("user", listOf(ClaudePTurnPart("text", "hi"))),
            ),
        )

        gateway.cancel(handle.generationId, ClaudePCancelReason.APP_SHUTDOWN)

        assertEquals(1, gateway.cancelCallCount)
        assertTrue(ClaudePCancelReason.APP_SHUTDOWN.wireValue == "app_shutdown")
    }
}
