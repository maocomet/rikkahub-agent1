package me.rerere.rikkahub.data.ai.prompt

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.claudep.ClaudePGenerationHandle
import me.rerere.ai.provider.claudep.ClaudePGenerationStartBody
import me.rerere.ai.provider.claudep.ClaudePGatewayClient
import me.rerere.ai.provider.claudep.FakeClaudePGatewayClient
import me.rerere.ai.provider.providers.ClaudePProvider
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.ProviderSystemPromptLayout
import me.rerere.rikkahub.data.ai.SystemPromptBuilder
import me.rerere.rikkahub.data.ai.transformers.neutralizeStableSystemMessage
import me.rerere.rikkahub.data.ai.transformers.substitutingRegisteredPlaceholders
import me.rerere.rikkahub.data.ai.transformers.transformMessages
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * The composition, driven through the real components and out to a real `generation.start` body.
 *
 * ## What is real here, and what is substituted
 *
 * Real: `SystemPromptBuilder.buildSections`, `ProviderSystemPromptLayout` (create, volatile
 * anchoring, the additional-context append), `PromptInjectionTransformer.transformMessages`,
 * `neutralizeStableSystemMessage`, `PromptReferencePolicy`, `StableSystemPromptSession`, and
 * `ClaudePProvider` dispatching into a recording gateway that wraps the real fake.
 *
 * Substituted: the placeholder *resolvers*. In production they read the system clock, the battery
 * and the settings store; here they return the values a turn would have produced. That is the point
 * of the substitution — the test supplies the turn, and everything that decides what happens to the
 * value is the production code.
 *
 * Not real: `GenerationHandler` itself, which needs an Android `Context`. The freeze step it
 * performs is reproduced here by `frozenSystemPrompt`, which applies the same function to the same
 * layout; the wire-side comparison it feeds is asserted against the real provider below.
 *
 * ## Why this test exists rather than a unit test per seam
 *
 * Each seam has its own unit test. This one is the only place that answers the question the M3
 * server work actually depends on: *does the byte string Claude P sends as `system_prompt` stay
 * identical across two turns that differ in every runtime value?* That is a property of the
 * composition, and no single seam can demonstrate it.
 */
class StableSystemPromptCompositionTest {

    private val setting = ProviderSetting.ClaudeP()
    private val sonnet = Model(modelId = "sonnet", displayName = "Claude Sonnet")

    /** The shipped default assistant prompt, verbatim. */
    private val defaultAssistantPrompt = """
        You are a helpful assistant, called {{char}}, based on model {{model_name}}.

        ## Info
        - Date: {{cur_date}}
        - Locale: {{locale}}
        - Timezone: {{timezone}}
        - Device Info: {{device_info}}
        - System Version: {{system_version}}
        - User Nickname: {{user}}

        ## Hint
        - If the user does not specify a language, reply in the user's primary language.
    """.trimIndent()

    private val lorebookId: Uuid = Uuid.random()

    private fun assistant(
        name: String = "Rikka",
        lorebookIds: Set<Uuid> = setOf(lorebookId),
    ) = Assistant(name = name, lorebookIds = lorebookIds)

    /** One lorebook entry, triggered by the word "Aldermere" in the conversation. */
    private fun lorebook(): Lorebook = Lorebook(
        id = lorebookId,
        name = "Places",
        enabled = true,
        entries = listOf(
            PromptInjection.RegexInjection(
                name = "Aldermere",
                enabled = true,
                position = InjectionPosition.AFTER_SYSTEM_PROMPT,
                content = "Aldermere is a harbour town on the north coast.",
                keywords = listOf("Aldermere"),
                scanDepth = 8,
            ),
        ),
    )

    /** The runtime values a turn resolves. Everything the resolvers would have read. */
    private data class RuntimeValues(
        val date: String,
        val time: String,
        val battery: String,
        val nickname: String,
        val modelName: String = "Claude Sonnet",
    )

    private val firstTurn = RuntimeValues(
        date = "Sep 27, 2026",
        time = "9:14:02 PM",
        battery = "87",
        nickname = "Ada",
    )

    /** The same conversation a day later, with a different battery reading. */
    private val nextTurn = RuntimeValues(
        date = "Sep 28, 2026",
        time = "7:02:11 AM",
        battery = "41",
        nickname = "Ada",
    )

    private fun valueOf(key: String, values: RuntimeValues): String? = when (key) {
        "cur_date" -> values.date
        "cur_time" -> values.time
        "cur_datetime" -> "${values.date} ${values.time}"
        "model_name" -> values.modelName
        "model_id" -> "sonnet"
        "locale" -> "English (United States)"
        "timezone" -> "Pacific Daylight Time"
        "system_version" -> "Android SDK v35 (15)"
        "device_info" -> "Google Pixel 9"
        "battery_level" -> values.battery
        "nickname", "user" -> values.nickname
        else -> null
    }

    /** A gateway that records what was dispatched and delegates everything else. */
    private class CapturingGateway(
        private val delegate: ClaudePGatewayClient,
    ) : ClaudePGatewayClient by delegate {
        val startBodies = mutableListOf<ClaudePGenerationStartBody>()
        var startCalls = 0
            private set

        override suspend fun startGeneration(
            requestId: String,
            fingerprint: String,
            body: ClaudePGenerationStartBody,
        ): ClaudePGenerationHandle {
            startCalls += 1
            startBodies += body
            return delegate.startGeneration(requestId, fingerprint, body)
        }
    }

    private data class Turn(
        /** What Claude P actually put in `generation.start.system_prompt`. */
        val dispatchedSystemPrompt: String?,
        /** What the app froze before the transformer pass. */
        val frozenSystemPrompt: String?,
        /** Exactly what was handed to the provider. */
        val providerMessages: List<UIMessage>,
        val startCalls: Int,
    ) {
        val systemText: String = providerMessages
            .filter { it.role == MessageRole.SYSTEM }
            .flatMap { it.parts.filterIsInstance<UIMessagePart.Text>() }
            .joinToString("") { it.text }

        val lastUserText: String = providerMessages.last { it.role == MessageRole.USER }
            .parts.filterIsInstance<UIMessagePart.Text>()
            .joinToString("") { it.text }
    }

    /**
     * One turn, through the real pipeline, out to the real provider.
     *
     * The order mirrors `GenerationHandler`: layout first, then the transformer pass, then the
     * volatile anchoring — with the stable expectation frozen from the layout *before* the pass, so
     * anything the pass does to the system message is caught rather than absorbed.
     */
    private fun runTurn(
        values: RuntimeValues,
        assistantPrompt: String = defaultAssistantPrompt,
        assistantName: String = "Rikka",
        memory: String = "",
        recentChats: String = "",
        userTurn: String = "Where is Aldermere?",
        fireLorebook: Boolean = true,
        userIdentity: String = "The user is called ${values.nickname}.",
    ): Turn {
        val assistant = assistant(name = assistantName)

        val (stableSystem, volatileSystem) = SystemPromptBuilder().buildSections(
            assistantPrompt = assistantPrompt,
            memoryPrompt = memory,
            recentChatsPrompt = recentChats,
            userIdentityPrompt = userIdentity,
        )

        val layout = ProviderSystemPromptLayout.create(
            stableSystem = stableSystem,
            volatileSystem = volatileSystem,
            conversationMessages = listOf(UIMessage.user(userTurn)),
            useAnchoredVolatileContext = true,
            reserveRuntimeContextEnvelope = true,
        )

        // The freeze, reproduced from GenerationHandler: computed from the layout, before anything
        // can rewrite it, and never read back out of the messages being sent.
        val frozen = layout.initialMessages
            .filter { it.role == MessageRole.SYSTEM }
            .flatMap { it.parts.filterIsInstance<UIMessagePart.Text>() }
            .map { it.text }
            .filter { it.isNotEmpty() }
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n\n")
            ?.let { text ->
                PromptReferencePolicy.neutralizeAppControlledTemplate(text) { key ->
                    PromptReferencePolicy.stableValueOf(key, assistantName)
                }.text
            }

        // Mirrors the production resolver: `char` is the one stable key, answered from assistant
        // configuration, and the rest come from the turn's platform inputs.
        val resolve: (String) -> String? = { key ->
            if (key == "char") {
                PromptReferencePolicy.stableValueOf(key, assistantName)
            } else {
                valueOf(key, values)
            }
        }

        val session = StableSystemPromptSession()
        val transformed = transformMessages(
            messages = layout.initialMessages,
            assistant = assistant,
            modeInjections = emptyList(),
            lorebooks = if (fireLorebook) listOf(lorebook()) else emptyList(),
            stableSystemPromptSession = session,
        )

        val neutralized = transformed.map { message ->
            if (message.role != MessageRole.SYSTEM) {
                message
            } else {
                val result = neutralizeStableSystemMessage(message, resolve)
                session.recordValues(PromptReferencePolicy.resolveDynamicValues(result.referencedKeys, resolve))
                result.message
            }
        }
        session.rewriteSectionContent { content ->
            PromptReferencePolicy.resolveAppControlledTemplate(content, resolve)
        }

        val providerMessages = layout
            .withAdditionalVolatileContext(session.render())
            .applyVolatileContext(neutralized)

        val gateway = CapturingGateway(FakeClaudePGatewayClient())
        val provider = ClaudePProvider(gateway = gateway, requestIdFactory = { "req-composition" })
        runBlocking {
            provider.streamText(
                providerSetting = setting,
                messages = providerMessages,
                params = TextGenerationParams(
                    model = sonnet,
                    stableSystemPromptExpectation = frozen,
                ),
            ).toList()
        }

        return Turn(
            dispatchedSystemPrompt = gateway.startBodies.singleOrNull()?.systemPrompt,
            frozenSystemPrompt = frozen,
            providerMessages = providerMessages,
            startCalls = gateway.startCalls,
        )
    }

    // ---------------------------------------------------------------------------------------
    // The property the server work depends on
    // ---------------------------------------------------------------------------------------

    @Test
    fun `two turns a day apart send byte-identical system prompts`() {
        val before = runTurn(firstTurn)
        val after = runTurn(nextTurn)

        assertEquals(1, before.startCalls)
        assertEquals(1, after.startCalls)
        assertNotNull(before.dispatchedSystemPrompt)
        assertEquals(
            "the system instruction is part of the session identity, so it must not move",
            before.dispatchedSystemPrompt,
            after.dispatchedSystemPrompt,
        )

        // ...and the equality above has to be earned. Two turns whose runtime values were identical
        // would satisfy it trivially, so the values themselves are asserted to differ, and each
        // turn to carry its own.
        assertTrue(before.lastUserText.contains("Sep 27, 2026"))
        assertTrue(after.lastUserText.contains("Sep 28, 2026"))
        assertFalse(before.lastUserText.contains("Sep 28, 2026"))
        assertFalse(after.lastUserText.contains("Sep 27, 2026"))
        assertNotEquals(before.lastUserText, after.lastUserText)
    }

    @Test
    fun `the dispatched system prompt is exactly the bytes the app froze`() {
        val turn = runTurn(firstTurn)

        assertEquals(1, turn.startCalls)
        assertEquals(turn.frozenSystemPrompt, turn.dispatchedSystemPrompt)
    }

    @Test
    fun `the system prompt carries no value, no reference and no runtime envelope`() {
        val turn = runTurn(nextTurn)

        assertFalse("no concrete date", turn.systemText.contains("Sep 28, 2026"))
        assertFalse("no time either", turn.systemText.contains("7:02:11 AM"))
        assertFalse("no battery reading", turn.systemText.contains("41"))
        assertFalse("no nickname", turn.systemText.contains("Ada"))

        // The *envelope* must stay out, and this is asserted on the envelope's rendered shape
        // rather than on the bare name. The system prompt does contain the constant
        // `PROVIDER_RUNTIME_CONTEXT_POLICY`, which names the envelope in order to tell the model
        // what the suffix on its turn means — a fixed sentence, identical on every turn, and not
        // runtime context. What must not appear is a boundary with content behind it.
        assertFalse("no opening boundary", turn.systemText.contains("<provider_runtime_context>\n"))
        assertFalse("no closing boundary", turn.systemText.contains("</provider_runtime_context>"))
        assertFalse("no runtime values block", turn.systemText.contains("<runtime_values>"))
        assertTrue("the reference is what remains", turn.systemText.contains("<runtime_value_ref name=\"cur_date\"/>"))
    }

    /**
     * No **registered** key survives in raw form, in any brace form and any case.
     *
     * Written as a scan over the vocabulary rather than as a list of literals on purpose: the
     * defect this catches is a token that is neither substituted nor refused, and a hardcoded list
     * would have to be extended by whoever adds the next key — which is exactly the moment the hole
     * would reopen.
     */
    private fun assertNoRawRegisteredPlaceholder(turn: Turn) {
        val tokenForms = listOf(
            Regex("\\{\\{([A-Za-z0-9_]{1,64})\\}\\}"),
            Regex("\\{([A-Za-z0-9_]{1,64})\\}"),
        )
        for (message in turn.providerMessages) {
            val text = message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
            for (form in tokenForms) {
                for (match in form.findAll(text)) {
                    val key = PromptReferencePolicy.canonicalKey(match.groupValues[1])
                    assertFalse(
                        "a raw '${match.value}' reached the request, and '{{cur_date}}' spelled any " +
                            "way is the placeholder this whole change removes",
                        PromptReferencePolicy.REGISTERED_KEYS.contains(key),
                    )
                }
            }
        }
    }

    @Test
    fun `no unresolved placeholder reaches the model anywhere`() {
        assertNoRawRegisteredPlaceholder(runTurn(nextTurn))
    }

    @Test
    fun `a mixed-case assistant prompt still sends canonical bytes`() {
        // The R1 regression, end to end. `{Battery_Level}` is the shape that used to slip through
        // silently: the single-brace form would not match, would not be refused, and would sit in
        // both the system message and the frozen expectation — so the wire check agreed with
        // itself and dispatched a raw placeholder as the system instruction.
        val mixedCasePrompt = """
            You are a helpful assistant, called {{CHAR}}, based on model {{MODEL_NAME}}.

            ## Info
            - Date: {{CUR_DATE}}
            - Battery: {Battery_Level}
            - Time: {cur_TIME}
        """.trimIndent()

        val turn = runTurn(nextTurn, assistantPrompt = mixedCasePrompt)

        assertEquals(1, turn.startCalls)
        assertEquals(
            "the freeze and the wire agree, and both are canonical",
            turn.frozenSystemPrompt,
            turn.dispatchedSystemPrompt,
        )

        // The stable key substitutes however it was spelled...
        assertTrue(turn.systemText.contains("called Rikka"))
        // ...and every dynamic one becomes the canonical marker.
        for (key in listOf("model_name", "cur_date", "battery_level", "cur_time")) {
            assertTrue(
                "expected a canonical marker for $key in: ${turn.systemText}",
                turn.systemText.contains("<runtime_value_ref name=\"$key\"/>"),
            )
        }

        // And the values arrive under their canonical names.
        for (key in listOf("cur_date", "battery_level", "cur_time")) {
            assertTrue(
                "expected a canonical runtime value for $key in: ${turn.lastUserText}",
                turn.lastUserText.contains("<runtime_value name=\"$key\">"),
            )
        }

        assertNoRawRegisteredPlaceholder(turn)
    }

    @Test
    fun `the resolved date travels in the last user turn and nowhere else`() {
        val turn = runTurn(nextTurn)

        assertTrue(turn.lastUserText.contains("Sep 28, 2026"))
        assertTrue(turn.lastUserText.contains("<runtime_values>"))
        assertTrue(turn.lastUserText.contains("<runtime_value name=\"cur_date\">Sep 28, 2026</runtime_value>"))

        // Every other message is untouched: the value is a runtime fact about this turn, not a
        // prefix the whole history has to carry.
        for (message in turn.providerMessages.dropLast(1)) {
            val text = message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
            assertFalse(text.contains("Sep 28, 2026"))
        }
    }

    // ---------------------------------------------------------------------------------------
    // What must still move the system prompt, and what must not
    // ---------------------------------------------------------------------------------------

    @Test
    fun `editing the assistant prompt does move the system prompt`() {
        val before = runTurn(nextTurn)
        val after = runTurn(nextTurn, assistantPrompt = defaultAssistantPrompt + "\nBe concise.")

        assertNotEquals(before.dispatchedSystemPrompt, after.dispatchedSystemPrompt)
    }

    @Test
    fun `renaming the assistant moves the system prompt, because its name is part of configuration`() {
        val before = runTurn(nextTurn, assistantName = "Rikka")
        val after = runTurn(nextTurn, assistantName = "Mira")

        assertNotEquals(before.dispatchedSystemPrompt, after.dispatchedSystemPrompt)
        assertTrue(after.systemText.contains("Mira"))
    }

    @Test
    fun `memory and recent chats move the user turn and never the system prompt`() {
        val lean = runTurn(nextTurn, memory = "", recentChats = "")
        val rich = runTurn(nextTurn, memory = "The user prefers short answers.", recentChats = "Earlier: a chat about tides.")

        assertEquals(lean.dispatchedSystemPrompt, rich.dispatchedSystemPrompt)
        assertNotEquals(lean.lastUserText, rich.lastUserText)
        assertTrue(rich.lastUserText.contains("The user prefers short answers."))
        assertTrue(rich.lastUserText.contains("Earlier: a chat about tides."))
    }

    @Test
    fun `a lorebook entry firing moves the user turn and never the system prompt`() {
        val quiet = runTurn(nextTurn, fireLorebook = false)
        val fired = runTurn(nextTurn, fireLorebook = true, userTurn = "Tell me about Aldermere.")

        assertEquals(quiet.dispatchedSystemPrompt, fired.dispatchedSystemPrompt)
        assertTrue(quiet.lastUserText.contains("Aldermere"))
        assertFalse(quiet.lastUserText.contains("harbour town"))

        assertTrue(fired.lastUserText.contains("harbour town"))
        assertTrue(
            "the placement it was destined for is kept as metadata, not acted on",
            fired.lastUserText.contains("placement=\"after_system_prompt\""),
        )
        assertTrue(fired.lastUserText.contains("origin=\"lorebook\""))
    }

    // ---------------------------------------------------------------------------------------
    // Nothing to say means nothing is said
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a turn with no dynamic values adds no runtime block at all`() {
        val turn = runTurn(
            nextTurn,
            assistantPrompt = "You are a concise assistant.",
            memory = "",
            recentChats = "",
            userTurn = "Hello there.",
            fireLorebook = false,
            assistantName = "Rikka",
            userIdentity = "",
        )

        assertFalse(turn.lastUserText.contains("<runtime_values>"))
        assertFalse(turn.lastUserText.contains("provider_runtime_context"))
        assertEquals("Hello there.", turn.lastUserText)
        assertEquals(turn.frozenSystemPrompt, turn.dispatchedSystemPrompt)
    }

    // ---------------------------------------------------------------------------------------
    // The user's own text is the user's own text
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a placeholder a user typed is left exactly as they typed it`() {
        // Never refused, never reinterpreted. The classification applies to text the app composes.
        val message = UIMessage.user("what does {{mystery}} mean in this template?")

        val substituted = message.substitutingRegisteredPlaceholders { key ->
            if (key == "cur_date") "Sep 28, 2026" else null
        }

        assertEquals(
            "what does {{mystery}} mean in this template?",
            substituted.parts.filterIsInstance<UIMessagePart.Text>().single().text,
        )
    }
}
