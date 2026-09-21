package me.rerere.ai.provider.claudep

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transport-level tests for [WssClaudePGatewayClient].
 *
 * ### Why nothing here sleeps
 *
 * The client's background work runs on `Dispatchers.Unconfined`, and the fake connector answers
 * frames *synchronously* from inside `send`. Together those two make delivery inline: when
 * `FakeClaudePWebSocketSession.deliver` puts a frame on the channel, the reader, the router and the
 * generation stream all run on the calling thread before it returns.
 *
 * That is what lets this file assert "exactly one dispatch" and "the client is now in
 * PROTOCOL_ERROR" *immediately after* the triggering call, with no `sleep`, no `yield` loop, no
 * polling and no timeout tuning. A timing-based test could not reliably assert those properties;
 * here they are consequences of the code being synchronous.
 *
 * `runBlocking` is only the entry point for `suspend` code — the client itself never depends on it.
 */
class ClaudePWssTransportTest {

    // ---------------------------------------------------------------------------------------
    // Handshake and negotiation
    // ---------------------------------------------------------------------------------------

    @Test
    fun `the client connects to the derived wss url with the credential and one subprotocol`() =
        withClient { client, connector ->
            client.hello(helloRequest())

            assertEquals(1, connector.connectCount)
            val request = connector.connectRequests.single()
            assertEquals("wss://gateway.example.com/v1/claude-p/stream", request.url)
            assertEquals(listOf(ClaudePProtocol.SUBPROTOCOL), request.subprotocols)
            assertEquals("credential-value", request.credential.value)
        }

    @Test
    fun `a server that selects no subprotocol is refused before hello`() =
        withClient(connector = { FakeClaudePWebSocketConnector(selectedSubprotocol = null) }) { client, connector ->
            val failure = gatewayFailure { hello(helloRequest()) }

            assertEquals(ClaudePErrorCode.PROTOCOL_MISMATCH, failure.code)
            assertEquals(ClaudePConnectionState.PROTOCOL_ERROR, client.connectionState.value)
            // The socket was rejected before the gateway was ever asked to authenticate a device.
            assertEquals(0, connector.server.helloCount)
        }

    @Test
    fun `a server that selects a different subprotocol is refused`() =
        withClient(connector = { FakeClaudePWebSocketConnector(selectedSubprotocol = "rikkahub.claude-p.v2") }) { client, connector ->
            val failure = gatewayFailure { hello(helloRequest()) }

            assertEquals(ClaudePErrorCode.PROTOCOL_MISMATCH, failure.code)
            assertEquals(0, connector.server.helloCount)
        }

    @Test
    fun `a tls failure is fail-closed and never reaches hello`() =
        withClient(connector = { FakeClaudePWebSocketConnector(connectFailure = ClaudePTransportFailure.TLS_FAILED) }) { client, connector ->
            gatewayFailure { hello(helloRequest()) }

            assertEquals(0, connector.server.helloCount)
            assertEquals(0, client.remoteDispatchCount)
            assertEquals(ClaudePConnectionState.OFFLINE, client.connectionState.value)
        }

    @Test
    fun `a protocol version the client does not speak stops the connection`() =
        withClient(connector = { FakeClaudePWebSocketConnector(server = FakeClaudePFrameServer(serverProtocolVersion = "v2")) }) { client, _ ->
            val failure = gatewayFailure { hello(helloRequest()) }

            assertEquals(ClaudePErrorCode.PROTOCOL_MISMATCH, failure.code)
            assertEquals(ClaudePConnectionState.PROTOCOL_ERROR, client.connectionState.value)
        }

    @Test
    fun `a refused handshake reports its own bounded error code`() =
        withClient(connector = {
            FakeClaudePWebSocketConnector(
                server = FakeClaudePFrameServer(handshakeRejection = ClaudePErrorCode.DEVICE_REVOKED),
            )
        }) { client, _ ->
            val failure = gatewayFailure { hello(helloRequest()) }

            assertEquals(ClaudePErrorCode.DEVICE_REVOKED, failure.code)
            // Revoked credentials and protocol mismatches are different problems: only the first
            // tells the user to re-pair.
            assertEquals(ClaudePConnectionState.CREDENTIAL_INVALID, client.connectionState.value)
        }

    @Test
    fun `the client signs the hello with its device key rather than trusting the caller`() =
        withClient { client, connector ->
            client.hello(helloRequest())

            val sentHello = connector.lastSession!!.lastSentOfType(ClaudePEventType.CLIENT_HELLO)!!
            val deviceId = sentHello.body["device_id"].toString().trim('"')
            val nonce = sentHello.body["nonce"].toString().trim('"')
            val signature = sentHello.body["signature"].toString().trim('"')

            // The device id comes from the credential store, not from the caller's placeholder.
            assertEquals("device-1", deviceId)
            assertNotEquals("ignored", nonce)
            assertNotEquals("ignored", signature)
        }

    // ---------------------------------------------------------------------------------------
    // Protocol violations
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a malformed frame moves the client into a stable protocol error`() =
        withClient { client, connector ->
            client.hello(helloRequest())

            connector.lastSession!!.deliver("{not json")

            assertEquals(ClaudePConnectionState.PROTOCOL_ERROR, client.connectionState.value)
            assertEquals(ClaudePErrorCode.PROTOCOL_MISMATCH, client.lastError.value)
        }

    @Test
    fun `a frame with no protocol field is rejected instead of assuming v1`() =
        withClient { client, connector ->
            client.hello(helloRequest())

            // No `protocol` key at all — the fail-open path CP1-A's review specifically closed.
            connector.lastSession!!.deliver("""{"type":"text.delta","body":{"text":"hi"}}""")

            assertEquals(ClaudePConnectionState.PROTOCOL_ERROR, client.connectionState.value)
        }

    @Test
    fun `an oversized frame is refused by the transport`() =
        withClient { client, connector ->
            client.hello(helloRequest())

            connector.lastSession!!.deliverFrameRejected(ClaudePFrameRejection.TOO_LARGE)

            assertEquals(ClaudePConnectionState.PROTOCOL_ERROR, client.connectionState.value)
        }

    @Test
    fun `a binary frame is refused by the transport`() =
        withClient { client, connector ->
            client.hello(helloRequest())

            connector.lastSession!!.deliverFrameRejected(ClaudePFrameRejection.BINARY_FRAME)

            assertEquals(ClaudePConnectionState.PROTOCOL_ERROR, client.connectionState.value)
        }

    @Test
    fun `an unknown event in the generation namespace is refused rather than ignored`() =
        withClient { client, connector ->
            client.hello(helloRequest())
            val handle = client.startGeneration("req-1", "fp-1", startBody())
            val collected = async { handle.frames().toList() }

            // A future `generation.terminated` could be the terminal. Dropping it would hang the
            // generation forever, so it is refused and the generation is ended instead.
            connector.server.emitRaw(connector.lastSession!!, handle.generationId, "generation.terminated")

            assertEquals(ClaudePConnectionState.PROTOCOL_ERROR, client.connectionState.value)
            val frames = collected.await()
            assertEquals(ClaudePErrorCode.PROTOCOL_MISMATCH, frames.last().failureCode())
        }

    @Test
    fun `an unknown optional event is tolerated and does not end the generation`() =
        withClient { client, connector ->
            client.hello(helloRequest())
            val handle = client.startGeneration("req-1", "fp-1", startBody())
            val session = connector.lastSession!!
            val collected = async { handle.frames().toList() }

            connector.server.emitText(session, handle.generationId, "one")
            // A Phase 3 tool event: additive, and must neither become text nor end the stream.
            connector.server.emitRaw(session, handle.generationId, "tool.requested")
            connector.server.emitText(session, handle.generationId, "two")
            connector.server.emitTerminal(session, handle.generationId, ClaudePTerminalKind.COMPLETED)

            val frames = collected.await()

            assertEquals(ClaudePConnectionState.READY, client.connectionState.value)
            assertEquals(listOf("one", "two"), frames.mapNotNull { it.textDelta() })
            assertEquals(1, frames.count { it.isTerminal() })
        }

    @Test
    fun `a sequence regression ends the stream with a bounded terminal`() =
        withClient { client, connector ->
            client.hello(helloRequest())
            val handle = client.startGeneration("req-1", "fp-1", startBody())
            val session = connector.lastSession!!
            val collected = async { handle.frames().toList() }

            connector.server.emitText(session, handle.generationId, "one")
            connector.server.emitWithRepeatedSequence(session, handle.generationId, "replayed")

            val frames = collected.await()

            // The consumer sees the prefix it can trust, then a bounded failure — never a silent
            // stop, and never the replayed frame.
            assertEquals(listOf("one"), frames.mapNotNull { it.textDelta() })
            assertEquals(ClaudePErrorCode.PROTOCOL_MISMATCH, frames.last().failureCode())
        }

    @Test
    fun `a duplicate terminal reaches the consumer exactly once`() =
        withClient { client, connector ->
            client.hello(helloRequest())
            val handle = client.startGeneration("req-1", "fp-1", startBody())
            val session = connector.lastSession!!
            val collected = async { handle.frames().toList() }

            connector.server.emitTerminal(session, handle.generationId, ClaudePTerminalKind.COMPLETED)
            connector.server.emitTerminal(session, handle.generationId, ClaudePTerminalKind.FAILED)

            val frames = collected.await()

            assertEquals(1, frames.count { it.isTerminal() })
            assertTrue(frames.any { it.envelope()?.type == ClaudePEventType.GENERATION_COMPLETED })
        }

    @Test
    fun `a frame for an untracked generation is dropped rather than delivered`() =
        withClient { client, connector ->
            client.hello(helloRequest())

            // A generation this connection never accepted. It must not create a stream, and it must
            // not be mistaken for a protocol violation either.
            connector.server.emitRaw(connector.lastSession!!, "gen-unknown", ClaudePEventType.TEXT_DELTA)

            assertEquals(ClaudePConnectionState.READY, client.connectionState.value)
        }

    // ---------------------------------------------------------------------------------------
    // Dispatch, idempotency and reconnect
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a generation start dispatches once and reports the accepted sequence`() =
        withClient { client, connector ->
            client.hello(helloRequest())

            val handle = client.startGeneration("req-1", "fp-1", startBody())

            assertEquals(1, connector.server.startCount)
            assertEquals(1, connector.server.dispatchCount)
            assertEquals(1, client.remoteDispatchCount)
            assertEquals(1L, handle.acceptedEventSeq)
        }

    @Test
    fun `the same request id and fingerprint never dispatches a second time`() =
        withClient { client, connector ->
            client.hello(helloRequest())

            val first = client.startGeneration("req-1", "fp-1", startBody())
            val second = client.startGeneration("req-1", "fp-1", startBody())

            assertEquals(first.generationId, second.generationId)
            // The socket was not touched the second time, so no second model run can have started.
            assertEquals(1, connector.server.startCount)
            assertEquals(1, client.remoteDispatchCount)
        }

    @Test
    fun `the same request id with a different fingerprint is a hard conflict`() =
        withClient { client, connector ->
            client.hello(helloRequest())
            client.startGeneration("req-1", "fp-1", startBody())

            val failure = gatewayFailure { startGeneration("req-1", "fp-different", startBody()) }

            assertEquals(ClaudePErrorCode.IDEMPOTENCY_CONFLICT, failure.code)
            assertEquals(1, connector.server.dispatchCount)
        }

    @Test
    fun `a model outside the catalog is refused with its own code and no dispatch`() =
        withClient { client, connector ->
            client.hello(helloRequest())

            val failure = gatewayFailure { startGeneration("req-1", "fp-1", startBody("retired")) }

            assertEquals(ClaudePErrorCode.MODEL_NOT_ALLOWED, failure.code)
            assertEquals(0, client.remoteDispatchCount)
        }

    @Test
    fun `a dropped socket reconnects and replays without a second dispatch`() =
        withClient { client, connector ->
            client.hello(helloRequest())
            val handle = client.startGeneration("req-1", "fp-1", startBody())
            val firstSession = connector.lastSession!!
            val collected = async { handle.frames().toList() }

            connector.server.emitText(firstSession, handle.generationId, "before")
            firstSession.dropConnection()

            // The client reconnected on a *new* socket rather than reusing the dead one.
            assertEquals(2, connector.connectCount)
            assertNotEquals(firstSession, connector.lastSession)
            // Recovery is `stream.resume` only. A second `generation.start` would be a second model
            // run, which is exactly what §7 forbids.
            assertEquals(1, connector.server.startCount)
            assertEquals(1, client.remoteDispatchCount)
            assertEquals(1, connector.server.resumeCount)

            val secondSession = connector.lastSession!!
            connector.server.emitText(secondSession, handle.generationId, "after")
            connector.server.emitTerminal(secondSession, handle.generationId, ClaudePTerminalKind.COMPLETED)

            val frames = collected.await()
            assertEquals(listOf("before", "after"), frames.mapNotNull { it.textDelta() })
        }

    @Test
    fun `reconnect attempts are bounded and end in a stable offline state`() =
        withClient(
            connector = { FakeClaudePWebSocketConnector(connectFailure = ClaudePTransportFailure.CONNECT_FAILED) },
            reconnectPolicy = ClaudePReconnectPolicy(maxAttempts = 3),
        ) { client, connector ->
            gatewayFailure { hello(helloRequest()) }

            // One initial attempt plus three retries — never unbounded, never a fourth retry.
            assertEquals(4, connector.connectCount)
            assertEquals(ClaudePConnectionState.OFFLINE, client.connectionState.value)
        }

    @Test
    fun `a stable failure state is not retried by the next request`() =
        withClient(connector = { FakeClaudePWebSocketConnector(server = FakeClaudePFrameServer(serverProtocolVersion = "v2")) }) { client, connector ->
            gatewayFailure { hello(helloRequest()) }
            val afterFirst = connector.connectCount

            gatewayFailure { hello(helloRequest()) }

            // A gateway speaking another protocol will not start speaking it because we asked again.
            assertEquals(afterFirst, connector.connectCount)
            assertEquals(ClaudePConnectionState.PROTOCOL_ERROR, client.connectionState.value)
        }

    @Test
    fun `an exhausted reconnect budget fails in-flight generations with a bounded terminal`() =
        withClient(
            connector = { FakeClaudePWebSocketConnector() },
            reconnectPolicy = ClaudePReconnectPolicy(maxAttempts = 1),
        ) { client, connector ->
            client.hello(helloRequest())
            val handle = client.startGeneration("req-1", "fp-1", startBody())
            val collected = async { handle.frames().toList() }

            // Every future connect attempt now fails.
            connector.failConnectAfter = 1
            connector.lastSession!!.dropConnection()

            val frames = collected.await()
            assertEquals(ClaudePErrorCode.STREAM_INTERRUPTED, frames.last().failureCode())
            assertEquals(ClaudePConnectionState.OFFLINE, client.connectionState.value)
        }

    // ---------------------------------------------------------------------------------------
    // Cancellation
    // ---------------------------------------------------------------------------------------

    @Test
    fun `cancel is sent at most once for a generation`() =
        withClient { client, connector ->
            client.hello(helloRequest())
            val handle = client.startGeneration("req-1", "fp-1", startBody())

            client.cancel(handle.generationId, ClaudePCancelReason.USER_REQUESTED)
            client.cancel(handle.generationId, ClaudePCancelReason.USER_REQUESTED)
            client.cancel(handle.generationId, ClaudePCancelReason.USER_REQUESTED)

            assertEquals(1, connector.server.cancelCount)
            assertEquals(1, client.cancelCallCount)
        }

    @Test
    fun `cancelling an already-terminal generation sends nothing and returns the original terminal`() =
        withClient { client, connector ->
            client.hello(helloRequest())
            val handle = client.startGeneration("req-1", "fp-1", startBody())
            val collected = async { handle.frames().toList() }

            connector.server.emitTerminal(connector.lastSession!!, handle.generationId, ClaudePTerminalKind.COMPLETED)
            collected.await()

            val outcome = client.cancel(handle.generationId, ClaudePCancelReason.USER_REQUESTED)

            // §8: an already-terminal generation returns the original terminal and adds no event.
            assertEquals(ClaudePCancelOutcome.Terminal(ClaudePTerminalKind.COMPLETED), outcome)
            assertEquals(0, connector.server.cancelCount)
        }

    @Test
    fun `cancelling before a terminal reports pending when the gateway cannot prove it stopped`() =
        withClient(
            connector = {
                FakeClaudePWebSocketConnector(
                    server = FakeClaudePFrameServer(cancelAcknowledgesWithoutTerminal = true),
                )
            },
        ) { client, connector ->
            client.hello(helloRequest())
            val handle = client.startGeneration("req-1", "fp-1", startBody())

            val outcome = client.cancel(handle.generationId, ClaudePCancelReason.USER_REQUESTED)

            // §8 forbids reporting a terminal we cannot prove. The gateway asked the worker to stop
            // but has not confirmed the child is gone, so it emits nothing — and the client must not
            // manufacture a cancellation on its behalf.
            assertEquals(ClaudePCancelOutcome.Pending, outcome)
            assertEquals(1, connector.server.cancelCount)
        }

    @Test
    fun `cancelling a running generation returns the terminal the gateway proved`() =
        withClient { client, connector ->
            client.hello(helloRequest())
            val handle = client.startGeneration("req-1", "fp-1", startBody())
            val collected = async { handle.frames().toList() }

            val outcome = client.cancel(handle.generationId, ClaudePCancelReason.USER_REQUESTED)

            assertEquals(ClaudePCancelOutcome.Terminal(ClaudePTerminalKind.CANCELLED), outcome)
            assertEquals(1, connector.server.cancelCount)
            assertEquals(1, collected.await().count { it.isTerminal() })
        }

    @Test
    fun `a cancel racing a completion still yields exactly one terminal`() =
        withClient { client, connector ->
            client.hello(helloRequest())
            val handle = client.startGeneration("req-1", "fp-1", startBody())
            val session = connector.lastSession!!
            val collected = async { handle.frames().toList() }

            // The gateway completes first, and the cancel arrives to find it already terminal.
            connector.server.emitTerminal(session, handle.generationId, ClaudePTerminalKind.COMPLETED)
            val outcome = client.cancel(handle.generationId, ClaudePCancelReason.USER_REQUESTED)

            assertEquals(ClaudePCancelOutcome.Terminal(ClaudePTerminalKind.COMPLETED), outcome)
            assertEquals(0, connector.server.cancelCount)
            assertEquals(1, collected.await().count { it.isTerminal() })
        }

    @Test
    fun `a receipt reports a terminal without exposing content`() =
        withClient { client, connector ->
            client.hello(helloRequest())
            val handle = client.startGeneration("req-1", "fp-1", startBody())

            connector.server.emitTerminal(connector.lastSession!!, handle.generationId, ClaudePTerminalKind.COMPLETED)

            val receipt = client.receipt(handle.generationId)

            assertEquals(ClaudePGenerationState.COMPLETED, receipt.safeState)
            assertNull(receipt.safeErrorCode)
            assertEquals(1, connector.server.receiptCount)
        }

    @Test
    fun `shutdown releases the socket and stops tracking generations`() =
        withClient { client, connector ->
            client.hello(helloRequest())
            client.startGeneration("req-1", "fp-1", startBody())
            val session = connector.lastSession!!

            client.shutdown()

            assertTrue(session.isClosed)
            assertTrue(session.closeCodes.isNotEmpty())
            assertEquals(ClaudePConnectionState.DISCONNECTED, client.connectionState.value)
        }

    // ---------------------------------------------------------------------------------------
    // Credential gating
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a missing credential fails closed without opening a socket`() =
        withClient(accessProvider = { null }) { client, connector ->
            gatewayFailure { hello(helloRequest()) }

            assertEquals(0, connector.connectCount)
            assertEquals(0, client.remoteDispatchCount)
            assertEquals(ClaudePConnectionState.CREDENTIAL_INVALID, client.connectionState.value)
        }

    @Test
    fun `an expired credential fails closed without opening a socket`() =
        withClient(accessProvider = { now -> ClaudePDeviceAccess("device-1", ClaudePAccessCredential("c"), now - 1) }) { client, connector ->
            gatewayFailure { hello(helloRequest()) }

            assertEquals(0, connector.connectCount)
            assertEquals(ClaudePConnectionState.CREDENTIAL_INVALID, client.connectionState.value)
        }

    @Test
    fun `a blank credential fails closed rather than connecting unauthenticated`() =
        withClient(accessProvider = { now -> ClaudePDeviceAccess("device-1", ClaudePAccessCredential(""), now + 60) }) { client, connector ->
            gatewayFailure { hello(helloRequest()) }

            assertEquals(0, connector.connectCount)
        }

    @Test
    fun `an unusable device key fails closed without opening a socket`() =
        withClient(keyStore = { InMemoryClaudePDeviceKeyStore().apply { loadFails = true } }) { client, connector ->
            gatewayFailure { hello(helloRequest()) }

            assertEquals(0, connector.connectCount)
            assertEquals(ClaudePConnectionState.CREDENTIAL_INVALID, client.connectionState.value)
        }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    /**
     * Runs [block] against a client whose background work is inline.
     *
     * The client's scope is `Unconfined` and cancelled in `finally`, so no coroutine outlives its
     * test and nothing leaks between tests.
     */
    private fun withClient(
        connector: () -> FakeClaudePWebSocketConnector = { FakeClaudePWebSocketConnector() },
        keyStore: () -> ClaudePDeviceKeyStore = { InMemoryClaudePDeviceKeyStore() },
        accessProvider: (Long) -> ClaudePDeviceAccess? = { now ->
            ClaudePDeviceAccess("device-1", ClaudePAccessCredential("credential-value"), now + 3600)
        },
        reconnectPolicy: ClaudePReconnectPolicy = ClaudePReconnectPolicy(maxAttempts = 0),
        block: suspend TestContext.(client: WssClaudePGatewayClient, connector: FakeClaudePWebSocketConnector) -> Unit,
    ) = runBlocking {
        val fakeConnector = connector()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val client = WssClaudePGatewayClient(
            connector = fakeConnector,
            endpoint = ENDPOINT,
            accessProvider = { now -> accessProvider(now) },
            deviceKeyStore = keyStore(),
            keyAlias = "test-key",
            scope = scope,
            appVersion = "1.0-test",
            reconnectPolicy = reconnectPolicy,
            // Backoff costs no wall-clock time; the policy itself is asserted directly.
            sleeper = { },
        )
        val context = TestContext(scope, client)
        try {
            context.block(client, fakeConnector)
        } finally {
            client.shutdown()
            scope.cancel()
        }
    }

    /**
     * Receiver for the helpers below, so a test body reads as a sequence of statements.
     *
     * It is a [CoroutineScope] so tests can `async` a collector without threading the scope through
     * every call site.
     */
    class TestContext(
        scope: CoroutineScope,
        private val client: WssClaudePGatewayClient,
    ) : CoroutineScope by scope {
        suspend fun hello(request: ClaudePClientHelloBody) = client.hello(request)

        suspend fun startGeneration(requestId: String, fingerprint: String, body: ClaudePGenerationStartBody) =
            client.startGeneration(requestId, fingerprint, body)

        /**
         * Asserts the call fails, and returns the typed failure.
         *
         * Used instead of JUnit's `assertThrows` because the body is `suspend`; nesting a second
         * `runBlocking` would block the very event loop the client's reader runs on.
         */
        suspend fun gatewayFailure(block: suspend TestContext.() -> Unit): ClaudePGatewayException =
            try {
                block()
                throw AssertionError("expected a ClaudePGatewayException, but the call succeeded")
            } catch (expected: ClaudePGatewayException) {
                expected
            }
    }

    private fun helloRequest() = ClaudePClientHelloBody(
        appVersion = "1.0-test",
        protocolVersions = listOf("v1"),
        // Deliberately wrong: the transport must replace these with real values.
        deviceId = "ignored",
        nonce = "ignored",
        signature = "ignored",
        capabilities = listOf("text_stream", "cancel", "receipt_query"),
    )

    private fun startBody(alias: String = "sonnet") = ClaudePGenerationStartBody(
        remoteThreadId = "thread-1",
        remoteBranchId = "branch-1",
        mode = "new",
        modelAlias = alias,
        turn = ClaudePTurn(role = "user", parts = listOf(ClaudePTurnPart(type = "text", text = "hi"))),
    )

    private companion object {
        val ENDPOINT: ClaudePEndpoint =
            (ClaudePEndpoint.parse("https://gateway.example.com") as ClaudePEndpointResult.Accepted)
                .endpoint
    }
}

// ---------------------------------------------------------------------------------------------
// Frame inspection helpers — assertions read the wire, never an intermediate object
// ---------------------------------------------------------------------------------------------

internal fun String.textDelta(): String? =
    ((ClaudePProtocol.parseInbound(this) as? ClaudePInbound.Event)?.event as? ClaudePServerEvent.TextDelta)
        ?.body
        ?.text

internal fun String.isTerminal(): Boolean =
    (ClaudePProtocol.parseInbound(this) as? ClaudePInbound.Event)
        ?.event
        ?.terminalKind != null

internal fun String.failureCode(): ClaudePErrorCode? =
    ((ClaudePProtocol.parseInbound(this) as? ClaudePInbound.Event)?.event as? ClaudePServerEvent.Failed)
        ?.body
        ?.safeCode

internal fun String.envelope(): ClaudePEnvelope? = decodeEnvelope(this)
