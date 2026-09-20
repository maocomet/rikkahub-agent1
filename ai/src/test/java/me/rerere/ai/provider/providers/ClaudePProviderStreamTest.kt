package me.rerere.ai.provider.providers

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
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
import me.rerere.ai.provider.claudep.ClaudePGatewayClient
import me.rerere.ai.provider.claudep.ClaudePGatewayException
import me.rerere.ai.provider.claudep.ClaudePErrorCode
import me.rerere.ai.provider.claudep.FakeClaudePGatewayClient
import me.rerere.ai.provider.claudep.FakeFrame
import me.rerere.ai.provider.claudep.UnpairedClaudePGatewayClient
import me.rerere.ai.ui.FinishCategory
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        assertEquals("2.0.1", assertNotNull(instance.negotiatedServerHello).claudeCodeVersion)
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
}
