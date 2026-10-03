package me.rerere.ai.provider.providers

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.claudep.ClaudePEventType
import me.rerere.ai.provider.claudep.ClaudePGenerationStartBody
import me.rerere.ai.provider.claudep.ClaudePGatewayClient
import me.rerere.ai.provider.claudep.ClaudePGatewayException
import me.rerere.ai.provider.claudep.ClaudePErrorCode
import me.rerere.ai.provider.claudep.FakeClaudePGatewayClient
import me.rerere.ai.provider.claudep.FakeFrame
import me.rerere.ai.provider.claudep.UnpairedClaudePGatewayClient
import me.rerere.ai.ui.FinishCategory
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Provider-facing behaviour: catalog mapping, chunk shaping and the dispatch boundary.
 *
 * Everything runs against the deterministic fake, so there are no sleeps, no timing assumptions
 * and no network. Assertions are on observable output — chunk order, terminal count, dispatch
 * counters — rather than on internal state.
 */
class ClaudePProviderStreamTest {

    private val setting = ProviderSetting.ClaudeP()

    private val sonnet = Model(modelId = "sonnet", displayName = "Claude Sonnet")

    private fun provider(
        gateway: ClaudePGatewayClient,
        requestId: String = "req-fixed",
    ) = ClaudePProvider(gateway = gateway, requestIdFactory = { requestId })

    private fun userMessage(text: String = "hello") = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun params(model: Model = sonnet) = TextGenerationParams(model = model)

    private fun textOf(chunks: List<MessageChunk>): String = chunks
        .flatMap { it.choices.firstOrNull()?.delta?.parts.orEmpty() }
        .filterIsInstance<UIMessagePart.Text>()
        .joinToString("") { it.text }

    private fun reasoningOf(chunks: List<MessageChunk>): String = chunks
        .flatMap { it.choices.firstOrNull()?.delta?.parts.orEmpty() }
        .filterIsInstance<UIMessagePart.Reasoning>()
        .joinToString("") { it.reasoning }

    // ---------------------------------------------------------------------------------------
    // Catalog
    // ---------------------------------------------------------------------------------------

    @Test
    fun `the model catalog maps enabled aliases only`() = runBlocking {
        val gateway = FakeClaudePGatewayClient()
        val models = provider(gateway).listModels(setting)

        assertEquals(listOf("sonnet", "haiku", "opus"), models.map { it.modelId })
        assertEquals(listOf("Claude Sonnet", "Claude Haiku", "Claude Opus"), models.map { it.displayName })
        // "retired" is present in the catalog but disabled, so it is not offered.
        assertFalse(models.any { it.modelId == "retired" })
    }

    /**
     * A Claude Code build string is metadata about the runtime, not a model. Letting it become a
     * model id would let the user "select" something the gateway can never dispatch.
     */
    @Test
    fun `a CLI version string is never a model`() = runBlocking {
        val gateway = FakeClaudePGatewayClient(claudeCodeVersion = "2.0.1")
        val instance = provider(gateway)
        val models = instance.listModels(setting)

        assertFalse(models.any { it.modelId == "2.0.1" })
        assertFalse(models.any { it.modelId.contains("claude-code") })
        models.forEach { model ->
            assertFalse(model.modelId.contains("2.0.1"))
            assertFalse(model.displayName.contains("2.0.1"))
        }

        // The version is still reported, just as handshake metadata rather than a model.
        // (JUnit 4's assertNotNull returns void, so it cannot be chained into this assertion.)
        val hello = requireNotNull(instance.negotiatedServerHello)
        assertEquals("2.0.1", hello.claudeCodeVersion)
    }

    @Test
    fun `phase 1 advertises text and reasoning but never tools or multimodal input`() = runBlocking {
        val models = provider(FakeClaudePGatewayClient()).listModels(setting)

        models.forEach { model ->
            assertFalse(
                "Claude P must not advertise TOOL during Phase 1",
                model.abilities.contains(ModelAbility.TOOL),
            )
            assertEquals(listOf(Modality.TEXT), model.inputModalities)
            assertEquals(listOf(Modality.TEXT), model.outputModalities)
        }
        // sonnet/opus declare reasoning_summary; haiku does not.
        assertTrue(models.first { it.modelId == "sonnet" }.abilities.contains(ModelAbility.REASONING))
        assertFalse(models.first { it.modelId == "haiku" }.abilities.contains(ModelAbility.REASONING))
    }

    // ---------------------------------------------------------------------------------------
    // Streaming
    // ---------------------------------------------------------------------------------------

    @Test
    fun `reasoning precedes the answer and text deltas concatenate in order`() = runBlocking {
        val chunks = provider(FakeClaudePGatewayClient())
            .streamText(setting, listOf(userMessage()), params())
            .toList()

        assertEquals("Considering the request.", reasoningOf(chunks))
        assertEquals("Hello, world", textOf(chunks))

        // Reasoning must arrive before any answer text, so the UI renders them in that order.
        val firstTextIndex = chunks.indexOfFirst { chunk ->
            chunk.choices.firstOrNull()?.delta?.parts
                ?.any { it is UIMessagePart.Text } == true
        }
        val lastReasoningIndex = chunks.indexOfLast { chunk ->
            chunk.choices.firstOrNull()?.delta?.parts
                ?.any { it is UIMessagePart.Reasoning } == true
        }
        assertTrue(lastReasoningIndex < firstTextIndex)
    }

    @Test
    fun `usage is reported and exactly one terminal is produced`() = runBlocking {
        val chunks = provider(FakeClaudePGatewayClient())
            .streamText(setting, listOf(userMessage()), params())
            .toList()

        val terminals = chunks.mapNotNull { it.resolvedTerminal() }
        assertEquals(1, terminals.size)
        assertEquals(FinishCategory.STOP, terminals.single().category)
        assertTrue(terminals.single().terminalSeen)

        val usage = chunks.mapNotNull { it.usage }.single()
        assertEquals(11, usage.promptTokens)
        assertEquals(7, usage.completionTokens)
    }

    /**
     * A reconnect can replay a duplicate terminal, and a cancel can race a completion. Neither may
     * produce a second terminal on the client.
     */
    @Test
    fun `a duplicate terminal from the gateway is ignored`() = runBlocking {
        val gateway = FakeClaudePGatewayClient(
            extraFramesAfterTerminal = listOf(
                FakeFrame(ClaudePEventType.GENERATION_CANCELLED, body = buildJsonObject {
                    put("reason", "user_requested")
                }),
            ),
        )

        val chunks = provider(gateway).streamText(setting, listOf(userMessage()), params()).toList()

        val terminals = chunks.mapNotNull { it.resolvedTerminal() }
        assertEquals(1, terminals.size)
        assertEquals(FinishCategory.STOP, terminals.single().category)
    }

    @Test
    fun `content after the terminal is dropped`() = runBlocking {
        val gateway = FakeClaudePGatewayClient(
            extraFramesAfterTerminal = listOf(
                FakeFrame(ClaudePEventType.TEXT_DELTA, body = buildJsonObject {
                    put("text", "late content that must not appear")
                    put("index", 0)
                }),
                FakeFrame(ClaudePEventType.USAGE_UPDATED, body = buildJsonObject {
                    put("prompt_tokens", 999)
                }),
            ),
        )

        val chunks = provider(gateway).streamText(setting, listOf(userMessage()), params()).toList()

        assertEquals("Hello, world", textOf(chunks))
        assertFalse(textOf(chunks).contains("late content"))
        // The usage that arrived after the terminal is rejected too.
        assertEquals(1, chunks.mapNotNull { it.usage }.size)
    }

    @Test
    fun `an unknown optional event is ignored without disturbing the stream`() = runBlocking {
        val gateway = FakeClaudePGatewayClient(
            midStreamFrames = listOf(
                FakeFrame("tool.requested", body = buildJsonObject {
                    put("call_id", "call-1")
                    put("name", "write_file")
                }),
                FakeFrame("some.future.event", body = buildJsonObject {
                    put("payload", "unrecognised")
                }),
            ),
        )

        val chunks = provider(gateway).streamText(setting, listOf(userMessage()), params()).toList()

        // The deltas around the unknown events are intact...
        assertEquals("Hello, world", textOf(chunks))
        // ...and nothing from the unknown events leaked into the answer or ended the stream.
        val rendered = chunks.flatMap { it.choices.firstOrNull()?.delta?.parts.orEmpty() }
            .joinToString("")
        assertFalse(rendered.contains("write_file"))
        assertFalse(rendered.contains("unrecognised"))
        assertEquals(1, chunks.mapNotNull { it.resolvedTerminal() }.size)
    }

    // ---------------------------------------------------------------------------------------
    // Dispatch boundary
    // ---------------------------------------------------------------------------------------

    @Test
    fun `an incompatible protocol major produces zero dispatch`() = runBlocking {
        val gateway = FakeClaudePGatewayClient(serverProtocolVersion = "v2")
        val instance = provider(gateway)

        val failure = assertThrows(ClaudePGatewayException::class.java) {
            runBlocking { instance.streamText(setting, listOf(userMessage()), params()).toList() }
        }

        assertEquals(ClaudePErrorCode.PROTOCOL_MISMATCH, failure.code)
        assertEquals(0, gateway.startGenerationCallCount)
        assertEquals(0, gateway.remoteDispatchCount)
    }

    @Test
    fun `an image input is rejected before dispatch`() = runBlocking {
        assertRejectedBeforeDispatch(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Image(url = "data:image/png;base64,AAAA")),
            ),
            ClaudePUnsupportedInput.IMAGE,
        )
    }

    @Test
    fun `a document input is rejected before dispatch`() = runBlocking {
        assertRejectedBeforeDispatch(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(
                    UIMessagePart.Document(
                        url = "data:application/pdf;base64,AAAA",
                        fileName = "report.pdf",
                        mime = "application/pdf",
                    ),
                ),
            ),
            ClaudePUnsupportedInput.DOCUMENT,
        )
    }

    @Test
    fun `an audio input is rejected before dispatch`() = runBlocking {
        assertRejectedBeforeDispatch(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Audio(url = "data:audio/mp4;base64,AAAA")),
            ),
            ClaudePUnsupportedInput.AUDIO,
        )
    }

    @Test
    fun `a video input is rejected before dispatch`() = runBlocking {
        assertRejectedBeforeDispatch(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Video(url = "data:video/mp4;base64,AAAA")),
            ),
            ClaudePUnsupportedInput.VIDEO,
        )
    }

    @Test
    fun `declaring tools is rejected before dispatch`() = runBlocking {
        val gateway = FakeClaudePGatewayClient()
        val instance = provider(gateway)

        val failure = assertThrows(ClaudePUnsupportedInputException::class.java) {
            runBlocking {
                instance.streamText(
                    setting,
                    listOf(userMessage()),
                    TextGenerationParams(
                        model = sonnet,
                        tools = listOf(
                            Tool(
                                name = "write_file",
                                description = "writes a file",
                                needsApproval = { true },
                                execute = { emptyList() },
                            ),
                        ),
                    ),
                ).toList()
            }
        }

        assertEquals(ClaudePUnsupportedInput.TOOL_DEFINITION, failure.input)
        assertEquals(0, gateway.startGenerationCallCount)
        assertEquals(0, gateway.remoteDispatchCount)
    }

    @Test
    fun `a turn with no text is rejected before dispatch`() = runBlocking {
        val gateway = FakeClaudePGatewayClient()
        val instance = provider(gateway)

        assertThrows(ClaudePUnsupportedInputException::class.java) {
            runBlocking {
                instance.streamText(
                    setting,
                    listOf(UIMessage(role = MessageRole.USER, parts = emptyList())),
                    params(),
                ).toList()
            }
        }
        assertEquals(0, gateway.remoteDispatchCount)
    }

    @Test
    fun `an unpaired production transport fails closed without reaching anything`() = runBlocking {
        val instance = ClaudePProvider(gateway = UnpairedClaudePGatewayClient)

        val failure = assertThrows(ClaudePGatewayException::class.java) {
            runBlocking { instance.streamText(setting, listOf(userMessage()), params()).toList() }
        }

        assertEquals(ClaudePErrorCode.NOT_PAIRED, failure.code)
    }

    @Test
    fun `generateText aggregates one stream into one dispatch`() = runBlocking {
        val gateway = FakeClaudePGatewayClient()
        val chunk = provider(gateway).generateText(setting, listOf(userMessage()), params())

        assertEquals(1, gateway.remoteDispatchCount)
        assertEquals(1, gateway.startGenerationCallCount)

        val parts = chunk.choices.single().message?.parts.orEmpty()
        assertEquals("Hello, world", parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text })
        assertEquals(
            "Considering the request.",
            parts.filterIsInstance<UIMessagePart.Reasoning>().joinToString("") { it.reasoning },
        )
        assertNotNull(chunk.resolvedTerminal())
        assertEquals(FinishCategory.STOP, chunk.resolvedTerminal()?.category)
    }

    private suspend fun assertRejectedBeforeDispatch(
        message: UIMessage,
        expected: ClaudePUnsupportedInput,
    ) {
        val gateway = FakeClaudePGatewayClient()
        val instance = provider(gateway)

        val failure = assertThrows(ClaudePUnsupportedInputException::class.java) {
            runBlocking { instance.streamText(setting, listOf(message), params()).toList() }
        }

        assertEquals(expected, failure.input)
        assertEquals(0, gateway.startGenerationCallCount)
        assertEquals(0, gateway.remoteDispatchCount)
    }

    // ---------------------------------------------------------------------------------------
    // The tool card the app persists after a tool turn
    // ---------------------------------------------------------------------------------------

    /**
     * A tool card exactly as the app writes one, with values chosen to be findable.
     *
     * The arguments and the output are distinctive strings so that "this did not reach the
     * request" is a search rather than an inference. The name is the local tool from the on-device
     * reproduction, and the call id is the one it really carried.
     *
     * `approvalState = Auto` is the state the app writes for a call that needed no decision, which
     * is the case that reproduced.
     */
    private fun toolCard() = UIMessagePart.Tool(
        toolCallId = "mcpcall_85c2bd4b7f8c8850e990c4aa2d50d94e",
        toolName = "get_time_info",
        input = """{"secret_argument":"/data/local/tmp/never-sent"}""",
        output = listOf(UIMessagePart.Text("TOOL-OUTPUT-MUST-NOT-TRAVEL")),
        approvalState = ToolApprovalState.Auto,
    )

    /**
     * The conversation the device left behind: a user turn, an assistant turn that ran a tool, and
     * a following user turn.
     *
     * The assistant turn has text *around* the card, because that is what the app really produces
     * (a lead-in, the card, the final answer) and because the interesting assertion is that the
     * surrounding text survives while the card does not.
     *
     * No system message: `requireStableSystemPrompt` deliberately refuses a system instruction with
     * no frozen expectation, and that is a different subject with its own suite.
     */
    private fun toolTurnConversation(withCard: Boolean = true): List<UIMessage> = listOf(
        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("what time is it?"))),
        UIMessage(
            role = MessageRole.ASSISTANT,
            parts = buildList {
                add(UIMessagePart.Text("Let me look that up."))
                if (withCard) add(toolCard())
                add(UIMessagePart.Text("It is 00:06 on 2026-10-03."))
            },
        ),
        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("thanks"))),
    )

    /** Dispatches one request and hands back the body the gateway actually received. */
    private suspend fun dispatchedBody(messages: List<UIMessage>): ClaudePGenerationStartBody {
        val gateway = FakeClaudePGatewayClient()
        provider(gateway).streamText(setting, messages, params()).toList()
        assertEquals("the request must have reached the gateway", 1, gateway.remoteDispatchCount)
        return gateway.startBodies.single()
    }

    /**
     * A conversation that already used a tool can still be sent.
     *
     * This is the on-device defect, pinned: the assistant message carries the tool card the app
     * itself wrote, and every later message in that conversation was refused with
     * `claude_p_unsupported_input: TOOL_CALL` — so the conversation was permanently unusable after
     * its first tool call, and the refusal named a part the provider's own turn builders drop.
     */
    @Test
    fun `a history tool card is not refused and the next user turn dispatches`() = runBlocking {
        val gateway = FakeClaudePGatewayClient()

        val chunks = provider(gateway)
            .streamText(setting, toolTurnConversation(), params())
            .toList()

        assertEquals("the turn must reach the gateway exactly once", 1, gateway.startGenerationCallCount)
        assertEquals(1, gateway.remoteDispatchCount)
        assertEquals("Hello, world", textOf(chunks))
    }

    /**
     * The rebuilt history keeps the text around the card and carries nothing of the card.
     *
     * Asserted on the body the gateway received rather than on a helper, because the claim is about
     * what Claude is sent. The surrounding texts are checked to be *present* for the same reason
     * the card is checked absent: dropping the whole assistant turn would also satisfy "no tool
     * identity travels", and would silently lose the conversation.
     */
    @Test
    fun `the rebuilt history keeps the surrounding text and carries no tool identity`() = runBlocking {
        val body = dispatchedBody(toolTurnConversation())

        assertEquals("new", body.mode)
        assertEquals("user", body.turn.role)
        assertEquals(listOf("thanks"), body.turn.parts.map { it.text })

        val history = body.rebuildHistory
            ?: error("a new mobile turn with prior turns must send its rebuilt history")
        assertEquals(listOf("user", "assistant"), history.map { it.role })
        assertEquals(
            listOf("what time is it?"),
            history[0].parts.map { it.text },
        )
        assertEquals(
            "the text before and after the card must both survive, in order",
            listOf("Let me look that up.", "It is 00:06 on 2026-10-03."),
            history[1].parts.map { it.text },
        )
        assertTrue(
            "every rebuilt part is text, which is the only kind the wire can carry",
            (body.turn.parts + history.flatMap { it.parts }).all { it.type == "text" },
        )

        // The structural claim above is only as good as the type it rests on, so it is stated
        // directly: a part has a `type` and a `text`, and nowhere for a name, an argument or a
        // result to travel in. Then the serialized body is searched for the card's own values, so
        // a future field would have to defeat both checks rather than slip past one.
        val wire = Json.encodeToString(ClaudePGenerationStartBody.serializer(), body)
        assertFalse("the tool name must not travel", wire.contains("get_time_info"))
        assertFalse("the tool call id must not travel", wire.contains("mcpcall_85c2bd4b"))
        assertFalse("the tool arguments must not travel", wire.contains("secret_argument"))
        assertFalse("the tool arguments must not travel", wire.contains("/data/local/tmp/never-sent"))
        assertFalse("the tool output must not travel", wire.contains("TOOL-OUTPUT-MUST-NOT-TRAVEL"))
    }

    /**
     * A persisted card changes nothing about the request — including the cache identity over it.
     *
     * Two conversations differing only by the card produce byte-identical `generation.start`
     * bodies. Since the request fingerprint is computed from exactly these fields (the turn, the
     * rebuilt history, the system prompt, the identities, the frozen catalog) and the app's
     * `providerCacheIdentity` is built from the conversation, assistant and memory projections
     * rather than from message parts, an equal body means an equal cache identity: a tool card
     * cannot re-key a prompt that is otherwise unchanged.
     */
    @Test
    fun `a persisted tool card changes nothing about the request Claude is sent`() = runBlocking {
        val withCard = dispatchedBody(toolTurnConversation(withCard = true))
        val withoutCard = dispatchedBody(toolTurnConversation(withCard = false))

        assertEquals(
            "the rebuilt history must be identical with and without the card",
            withoutCard.rebuildHistory,
            withCard.rebuildHistory,
        )
        assertEquals(withoutCard.turn, withCard.turn)
        assertEquals(
            "and the whole body, which is what the fingerprint is taken over",
            Json.encodeToString(ClaudePGenerationStartBody.serializer(), withoutCard),
            Json.encodeToString(ClaudePGenerationStartBody.serializer(), withCard),
        )
    }

    /**
     * The plain, no-tool conversation is unchanged: one user turn, no history, no snapshot.
     *
     * Pinned separately from the comparison above because "the card makes no difference" would
     * still hold if *both* shapes had drifted. This says what the shape is.
     */
    @Test
    fun `a plain conversation dispatches the frozen text-only shape`() = runBlocking {
        val body = dispatchedBody(listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hi")))))

        assertEquals("new", body.mode)
        assertEquals("user", body.turn.role)
        assertEquals(listOf("hi"), body.turn.parts.map { it.text })
        assertNull("a first turn has nothing before it to rebuild", body.rebuildHistory)
        assertNull("no tools were declared, so nothing was frozen", body.toolSnapshot)
        assertNull("and no binding intent is sent for a new mobile turn", body.bindingIntent)
    }

    /**
     * A legacy `ToolCall` is still refused, wherever it appears in the conversation.
     *
     * These are not this app's current shape — they are what older builds and the importers
     * persisted — and a legacy tool turn replayed as text-only history would silently lose the
     * fact that it happened. That is the case the refusal exists for, and it is deliberately
     * untouched by the change that lets a current tool card through.
     */
    @Test
    @Suppress("DEPRECATION")
    fun `a legacy tool call in history is still refused`() = runBlocking {
        val legacy = UIMessagePart.ToolCall(
            toolCallId = "call-legacy",
            toolName = "read_file",
            arguments = """{"path":"/tmp/x"}""",
        )
        assertRejectedBeforeDispatch(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("reading"), legacy),
            ),
            ClaudePUnsupportedInput.TOOL_CALL,
        )
    }

    /** A legacy `ToolResult` is refused for the same reason, and independently of the call. */
    @Test
    @Suppress("DEPRECATION")
    fun `a legacy tool result in history is still refused`() = runBlocking {
        val legacy = UIMessagePart.ToolResult(
            toolCallId = "call-legacy",
            toolName = "read_file",
            content = JsonPrimitive("legacy content"),
            arguments = JsonObject(emptyMap()),
        )
        assertRejectedBeforeDispatch(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("read it"), legacy),
            ),
            ClaudePUnsupportedInput.TOOL_CALL,
        )
    }
}
