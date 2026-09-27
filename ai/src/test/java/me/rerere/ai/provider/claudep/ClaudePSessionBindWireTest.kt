package me.rerere.ai.provider.claudep

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.ClaudePProvider
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `deferred` half of the wire: `binding_intent`, an absent `remote_branch_id`, and the
 * `session.bind` frame that settles it.
 *
 * The shapes are checked as **encoded JSON**, not as Kotlin values, because §5.3's rule is about
 * the frame on the wire: `deferred` is expressed by the field being *absent*, and a JSON null or
 * an empty string are both different bytes that the Server would read differently.
 */
class ClaudePSessionBindWireTest {

    private val setting = ProviderSetting.ClaudeP()
    private val sonnet = Model(modelId = "sonnet", displayName = "Claude Sonnet")
    private val branch = "a".repeat(64)

    private fun userMessage() = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text("hello")),
    )

    private fun startBodyJson(body: ClaudePGenerationStartBody): JsonObject =
        ClaudePProtocol.json.encodeToJsonElement(body).jsonObject

    private fun startBody(
        mode: String = ClaudePSessionMode.NEW,
        remoteBranchId: String? = "opaque-branch",
        bindingIntent: String? = null,
        assistantId: String? = null,
    ) = ClaudePGenerationStartBody(
        remoteThreadId = "thread-1",
        remoteBranchId = remoteBranchId,
        mode = mode,
        modelAlias = "sonnet",
        turn = ClaudePTurn(role = "user", parts = listOf(ClaudePTurnPart("text", "hello"))),
        bindingIntent = bindingIntent,
        assistantId = assistantId,
    )

    // ---------------------------------------------------------------------------------------
    // The request shape
    // ---------------------------------------------------------------------------------------

    /**
     * §5.3: a deferred request expresses "this branch does not exist yet" by the field being
     * **absent**. A JSON null or an `""` would both be *present*, and the protocol refuses both —
     * and each would produce a different fingerprint for the same request shape.
     */
    @Test
    fun `a deferred request carries no branch key at all`() {
        val json = startBodyJson(
            startBody(
                mode = ClaudePSessionMode.AUTO,
                remoteBranchId = null,
                bindingIntent = ClaudePSessionBindingIntent.DEFERRED.wireValue,
                assistantId = "assistant-1",
            ),
        )

        assertFalse("remote_branch_id must not appear", json.containsKey("remote_branch_id"))
        assertEquals("deferred", json["binding_intent"]?.toString()?.trim('"'))
        assertEquals("auto", json["mode"]?.toString()?.trim('"'))
    }

    @Test
    fun `an immediate request carries the branch digest and its intent`() {
        val json = startBodyJson(
            startBody(
                mode = ClaudePSessionMode.AUTO,
                remoteBranchId = branch,
                bindingIntent = ClaudePSessionBindingIntent.IMMEDIATE.wireValue,
                assistantId = "assistant-1",
            ),
        )

        assertEquals(branch, json["remote_branch_id"]?.toString()?.trim('"'))
        assertEquals("immediate", json["binding_intent"]?.toString()?.trim('"'))
    }

    /**
     * §5.4's illegal shapes, refused where they are built rather than where they are sent: a
     * request the Server rejects costs the whole connection, not one request.
     */
    @Test
    fun `the illegal request shapes cannot be constructed`() {
        // Row 5: immediate with no branch.
        assertTrue(
            runCatching {
                startBody(
                    mode = ClaudePSessionMode.AUTO,
                    remoteBranchId = null,
                    bindingIntent = "immediate",
                    assistantId = "assistant-1",
                )
            }.isFailure,
        )
        // Row 6: deferred with a branch.
        assertTrue(
            runCatching {
                startBody(
                    mode = ClaudePSessionMode.AUTO,
                    remoteBranchId = branch,
                    bindingIntent = "deferred",
                    assistantId = "assistant-1",
                )
            }.isFailure,
        )
        // Row 7: auto without an assistant. The Worker resolves a continuation per assistant, so
        // a request that named none would be asking it to guess.
        assertTrue(
            runCatching {
                startBody(
                    mode = ClaudePSessionMode.AUTO,
                    remoteBranchId = branch,
                    bindingIntent = "immediate",
                )
            }.isFailure,
        )
        // Row 8: an intent under the legacy mode — the two shapes must not be mixed.
        assertTrue(
            runCatching {
                startBody(
                    mode = ClaudePSessionMode.NEW,
                    bindingIntent = "immediate",
                    assistantId = "assistant-1",
                )
            }.isFailure,
        )
        // An intent this build does not know is not a shape either.
        assertTrue(
            runCatching {
                startBody(
                    mode = ClaudePSessionMode.AUTO,
                    remoteBranchId = null,
                    bindingIntent = "resume",
                    assistantId = "assistant-1",
                )
            }.isFailure,
        )
    }

    /** The legacy shape is untouched: no tail fields, so the frozen v1-r3 digest still applies. */
    @Test
    fun `a legacy request carries neither tail field`() {
        val json = startBodyJson(startBody())

        assertFalse(json.containsKey("binding_intent"))
        assertEquals("opaque-branch", json["remote_branch_id"]?.toString()?.trim('"'))
        assertEquals("new", json["mode"]?.toString()?.trim('"'))
    }

    // ---------------------------------------------------------------------------------------
    // The provider's use of the shape
    // ---------------------------------------------------------------------------------------

    private fun provider(gateway: ClaudePGatewayClient) =
        ClaudePProvider(
            gateway = gateway,
            requestIdFactory = { "req-fixed" },
            remoteThreadId = "thread-1",
            remoteBranchId = "opaque-branch",
        )

    private fun params(binding: ClaudePSessionBindingRequest?) = TextGenerationParams(
        model = sonnet,
        claudePSessionBindingRequest = binding,
    )

    private fun startBodiesFrom(binding: ClaudePSessionBindingRequest?): JsonObject = runBlocking {
        val gateway = FakeClaudePGatewayClient()
        provider(gateway).streamText(setting, listOf(userMessage()), params(binding)).toList()
        startBodyJson(gateway.startBodies.single())
    }

    @Test
    fun `a deferred binding request produces an auto deferred frame`() {
        val json = startBodiesFrom(
            ClaudePSessionBindingRequest(
                assistantId = "assistant-1",
                intent = ClaudePSessionBindingIntent.DEFERRED,
                branchId = null,
            ),
        )

        assertEquals("auto", json["mode"]?.toString()?.trim('"'))
        assertEquals("deferred", json["binding_intent"]?.toString()?.trim('"'))
        assertEquals("assistant-1", json["assistant_id"]?.toString()?.trim('"'))
        assertFalse(json.containsKey("remote_branch_id"))
    }

    @Test
    fun `an immediate binding request produces an auto immediate frame`() {
        val json = startBodiesFrom(
            ClaudePSessionBindingRequest(
                assistantId = "assistant-1",
                intent = ClaudePSessionBindingIntent.IMMEDIATE,
                branchId = branch,
            ),
        )

        assertEquals("auto", json["mode"]?.toString()?.trim('"'))
        assertEquals("immediate", json["binding_intent"]?.toString()?.trim('"'))
        assertEquals(branch, json["remote_branch_id"]?.toString()?.trim('"'))
    }

    /** No binding request is the legacy path, and it must stay byte-for-byte what it was. */
    @Test
    fun `no binding request keeps the legacy frame`() {
        val json = startBodiesFrom(null)

        assertEquals("new", json["mode"]?.toString()?.trim('"'))
        assertFalse(json.containsKey("binding_intent"))
        assertFalse(json.containsKey("assistant_id"))
    }

    /**
     * The generation id rides on the chunks of a deferred generation so the app can write its
     * `BIND_PENDING` record in the same transaction that commits the branch. A legacy generation
     * carries nothing, which is what keeps every non-M3 path unchanged.
     */
    @Test
    fun `only a deferred generation stamps the generation id on its chunks`() = runBlocking {
        val deferredGateway = FakeClaudePGatewayClient()
        val deferredChunks = provider(deferredGateway).streamText(
            setting,
            listOf(userMessage()),
            params(
                ClaudePSessionBindingRequest(
                    assistantId = "assistant-1",
                    intent = ClaudePSessionBindingIntent.DEFERRED,
                    branchId = null,
                ),
            ),
        ).toList()

        assertTrue(deferredChunks.isNotEmpty())
        assertTrue(
            "every chunk of a deferred generation must carry its id",
            deferredChunks.all { it.claudePGenerationId == deferredGateway.lastGenerationId },
        )

        val legacyGateway = FakeClaudePGatewayClient()
        val legacyChunks = provider(legacyGateway)
            .streamText(setting, listOf(userMessage()), params(null))
            .toList()

        assertTrue(legacyChunks.isNotEmpty())
        assertTrue(legacyChunks.all { it.claudePGenerationId == null })
    }

    // ---------------------------------------------------------------------------------------
    // session.bind
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a bind sends exactly one frame and never dispatches a generation`() = runBlocking {
        val gateway = FakeClaudePGatewayClient()
        val provider = provider(gateway)

        val outcome = provider.bindSession(
            generationId = "gen-1",
            branchId = branch,
            assistantId = "assistant-1",
        )

        assertEquals(ClaudePSessionBindOutcome.Bound, outcome)
        assertEquals(1, gateway.bindSessionCallCount)
        assertEquals(0, gateway.startGenerationCallCount)
        assertEquals(0, gateway.remoteDispatchCount)

        val sent = gateway.bindRequests.single()
        assertEquals("gen-1", sent.first)
        assertEquals("gen-1", sent.second.generationId)
        assertEquals(branch, sent.second.remoteBranchId)
        assertEquals("assistant-1", sent.second.assistantId)
        assertEquals("thread-1", sent.second.remoteThreadId)
    }

    @Test
    fun `a repeat bind is reported as already bound, not as a second write`() = runBlocking {
        val gateway = FakeClaudePGatewayClient()
        gateway.bindResult = { body ->
            ClaudePSessionBindResultBody(
                generationId = body.generationId,
                state = ClaudePSessionBindState.ALREADY_BOUND.wireValue,
            )
        }
        val provider = provider(gateway)

        val outcome = provider.bindSession("gen-1", branch, "assistant-1")

        assertEquals(ClaudePSessionBindOutcome.AlreadyBound, outcome)
        assertTrue(outcome.settlesAsBound)
    }

    /** §6.1's three settled refusals are kept apart so a reader is sent to the right place. */
    @Test
    fun `each settled refusal maps to its own outcome`() = runBlocking {
        for (state in listOf(
            ClaudePSessionBindState.CONFLICT,
            ClaudePSessionBindState.CANDIDATE_UNAVAILABLE,
            ClaudePSessionBindState.REFUSED,
        )) {
            val gateway = FakeClaudePGatewayClient()
            gateway.bindResult = { body ->
                ClaudePSessionBindResultBody(generationId = body.generationId, state = state.wireValue)
            }
            val provider = provider(gateway)

            val outcome = provider.bindSession("gen-1", branch, "assistant-1")

            assertEquals(ClaudePSessionBindOutcome.Refused(state), outcome)
            assertFalse(outcome.settlesAsBound)
        }
    }

    /** An answer nobody understands is not evidence that anything was written. */
    @Test
    fun `an unrecognised state is malformed, never assumed bound`() = runBlocking {
        val gateway = FakeClaudePGatewayClient()
        gateway.bindResult = { body ->
            ClaudePSessionBindResultBody(generationId = body.generationId, state = "resumed")
        }
        val provider = provider(gateway)

        assertEquals(
            ClaudePSessionBindOutcome.Malformed,
            provider.bindSession("gen-1", branch, "assistant-1"),
        )
    }

    /**
     * An answer about a *different* generation must not settle this branch. Crediting it would
     * mark a branch resumable on the strength of somebody else's binding.
     */
    @Test
    fun `an answer naming another generation is malformed`() = runBlocking {
        val gateway = FakeClaudePGatewayClient()
        gateway.bindResult = { body ->
            ClaudePSessionBindResultBody(generationId = "gen-other", state = "bound")
        }
        val provider = provider(gateway)

        assertEquals(
            ClaudePSessionBindOutcome.Malformed,
            provider.bindSession("gen-1", branch, "assistant-1"),
        )
    }

    /**
     * A dropped connection is the *absence* of an answer. The Server may have applied the bind
     * before the socket died, so this must not close the branch — and it must not count as a
     * proven binding either.
     */
    @Test
    fun `a transport failure is unproven, never a refusal`() = runBlocking {
        val provider = provider(ThrowingBindGateway(FakeClaudePGatewayClient()))

        val outcome = provider.bindSession("gen-1", branch, "assistant-1")

        assertEquals(ClaudePSessionBindOutcome.Unproven, outcome)
        assertFalse(outcome.settlesAsBound)
    }

    /** Every input a bind is built from is required to be real, never defaulted. */
    @Test
    fun `a bind with an unusable identity is refused before it is sent`() = runBlocking {
        val gateway = FakeClaudePGatewayClient()
        val provider = provider(gateway)

        assertTrue(runCatching { provider.bindSession("", branch, "assistant-1") }.isFailure)
        assertTrue(runCatching { provider.bindSession("gen-1", "nope", "assistant-1") }.isFailure)
        assertTrue(runCatching { provider.bindSession("gen-1", branch, "") }.isFailure)
        assertEquals(0, gateway.bindSessionCallCount)
    }

    // ---------------------------------------------------------------------------------------
    // Routing
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a session bind result is routed rather than dropped as unknown`() {
        val raw = """
            {"protocol":"rikkahub.claude-p.v1","type":"session.bind.result","request_id":"r1",
             "body":{"generation_id":"gen-1","state":"bound"}}
        """.trimIndent()

        val event = (ClaudePProtocol.parseInbound(raw) as ClaudePInbound.Event).event

        assertEquals(
            ClaudePSessionBindState.BOUND,
            (event as ClaudePServerEvent.SessionBindResult).body.safeState,
        )
    }

    @Test
    fun `an unknown bind state parses but does not resolve`() {
        val raw = """
            {"protocol":"rikkahub.claude-p.v1","type":"session.bind.result","request_id":"r1",
             "body":{"generation_id":"gen-1","state":"someday"}}
        """.trimIndent()

        val event = (ClaudePProtocol.parseInbound(raw) as ClaudePInbound.Event).event

        assertNull((event as ClaudePServerEvent.SessionBindResult).body.safeState)
    }

    /** `session.bind` is a type this build may send, and the guard set has to say so. */
    @Test
    fun `session bind is a known outbound type`() {
        assertTrue(ClaudePEventType.SESSION_BIND in ClaudePEventType.CLIENT_TYPES)
        assertTrue(ClaudePEventType.SESSION_BIND_RESULT in ClaudePProtocol.KNOWN_SERVER_EVENT_TYPES)
    }

    /** A bind frame on a generation's own stream carries no content and ends nothing. */
    @Test
    fun `a bind answer on a generation stream is neither text nor a terminal`() = runBlocking {
        val gateway = FakeClaudePGatewayClient()
        gateway.bindResult = { body ->
            ClaudePSessionBindResultBody(generationId = body.generationId, state = "bound")
        }
        val provider = provider(gateway)
        provider.bindSession("gen-1", branch, "assistant-1")

        // The RPC answered from the reply; nothing was emitted as a chunk, and no second
        // generation was dispatched to carry it.
        assertEquals(1, gateway.bindSessionCallCount)
        assertEquals(0, gateway.startGenerationCallCount)
    }

    /**
     * A gateway whose bind transport dies, for the unproven case.
     *
     * Everything except the bind is the real fake, so the handshake the bind goes through is a
     * real one and the only thing under test is what a dropped answer is taken to mean.
     */
    private class ThrowingBindGateway(
        delegate: ClaudePGatewayClient,
    ) : ClaudePGatewayClient by delegate {
        override suspend fun bindSession(
            generationId: String,
            body: ClaudePSessionBindBody,
        ): ClaudePSessionBindResultBody =
            throw ClaudePGatewayException(ClaudePErrorCode.STREAM_INTERRUPTED)
    }
}
