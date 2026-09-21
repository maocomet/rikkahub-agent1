package me.rerere.ai.provider.claudep

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The transport resolver's fail-closed behaviour.
 *
 * `ResolvingClaudePGatewayClient` is what keeps an unpaired device visible-but-unreachable: it
 * returns the real transport once one exists and the fail-closed singleton otherwise. The case that
 * matters is a resolver that **throws** — an unreadable credential store, a Keystore that refuses.
 * That must produce `NOT_PAIRED`, not a crash and not an unauthenticated attempt.
 */
class ClaudePResolvingClientTest {

    @Test
    fun `a resolver returning a transport delegates every call to it`() = runBlocking {
        val delegate = FakeClaudePGatewayClient()
        val client = ResolvingClaudePGatewayClient(resolve = { delegate })

        client.hello(hello())

        assertEquals(1, delegate.helloCount)
    }

    @Test
    fun `a resolver returning null falls back to the fail-closed client`() = runBlocking {
        val client = ResolvingClaudePGatewayClient(resolve = { null })

        val failure = try {
            client.hello(hello())
            throw AssertionError("expected NOT_PAIRED")
        } catch (expected: ClaudePGatewayException) {
            expected
        }

        assertEquals(ClaudePErrorCode.NOT_PAIRED, failure.code)
    }

    @Test
    fun `a resolver that throws is treated exactly like one returning null`() = runBlocking {
        // An unreadable credential store must not become an unauthenticated request, and must not
        // become a crash either.
        val client = ResolvingClaudePGatewayClient(resolve = { error("credential store unreadable") })

        val failure = try {
            client.startGeneration("req-1", "fp-1", startBody())
            throw AssertionError("expected NOT_PAIRED")
        } catch (expected: ClaudePGatewayException) {
            expected
        }

        assertEquals(ClaudePErrorCode.NOT_PAIRED, failure.code)
    }

    @Test
    fun `an unpaired resolver reports zero dispatches`() = runBlocking {
        val client = ResolvingClaudePGatewayClient(resolve = { null })

        // The counters report the fallback's zeros, which is the honest answer: nothing was
        // dispatched.
        assertEquals(0, client.remoteDispatchCount)
        assertEquals(0, client.startGenerationCallCount)
        assertEquals(0, client.cancelCallCount)
    }

    private fun hello() = ClaudePClientHelloBody(
        appVersion = "1.0-test",
        deviceId = "device-1",
        nonce = "n",
        signature = "s",
    )

    private fun startBody() = ClaudePGenerationStartBody(
        remoteThreadId = "t",
        remoteBranchId = "b",
        mode = "new",
        modelAlias = "sonnet",
        turn = ClaudePTurn(role = "user", parts = listOf(ClaudePTurnPart(type = "text", text = "hi"))),
    )
}
