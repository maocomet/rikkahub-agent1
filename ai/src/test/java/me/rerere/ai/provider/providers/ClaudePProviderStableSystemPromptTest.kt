package me.rerere.ai.provider.providers

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.claudep.BridgeToolExecution
import me.rerere.ai.provider.claudep.ClaudePGatewayClient
import me.rerere.ai.provider.claudep.ClaudePGenerationHandle
import me.rerere.ai.provider.claudep.ClaudePGenerationStartBody
import me.rerere.ai.provider.claudep.ClaudePToolBridgeHost
import me.rerere.ai.provider.claudep.ClaudePToolGenerationContext
import me.rerere.ai.provider.claudep.ClaudePToolPreparation
import me.rerere.ai.provider.claudep.ClaudePToolStatusSink
import me.rerere.ai.provider.claudep.FakeClaudePGatewayClient
import me.rerere.ai.provider.claudep.bridge.BridgeCatalogBuild
import me.rerere.ai.provider.claudep.bridge.BridgeExecutionClaimant
import me.rerere.ai.provider.claudep.bridge.BridgeExecutionHost
import me.rerere.ai.provider.claudep.bridge.BridgeInvocation
import me.rerere.ai.provider.claudep.bridge.BridgeToolCandidate
import me.rerere.ai.provider.claudep.bridge.BridgeToolCatalog
import me.rerere.ai.provider.claudep.bridge.ToolSource
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire-side half of the stable-system-prompt contract: the provider refuses what the app did
 * not freeze, and refuses it *before* anything is dispatched.
 *
 * ## Why the check lives here rather than only in the app
 *
 * The app can freeze a value and can lay out a prompt, but only the provider knows what it is about
 * to put on the wire. A check that lived upstream would be a statement about the app's intention; a
 * check at the dispatch boundary is a statement about the bytes. The three refusals below are the
 * ones that make the difference — each asserts `startGenerationCallCount == 0`, because "the
 * request failed" is far too weak a claim when the cost being avoided is a dispatched generation.
 */
class ClaudePProviderStableSystemPromptTest {

    private val setting = ProviderSetting.ClaudeP()
    private val sonnet = Model(modelId = "sonnet", displayName = "Claude Sonnet")

    private fun systemMessage(text: String) = UIMessage(
        role = MessageRole.SYSTEM,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun userMessage(text: String = "hello") = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun params(expectation: String?, tools: List<Tool> = emptyList()) = TextGenerationParams(
        model = sonnet,
        tools = tools,
        stableSystemPromptExpectation = expectation,
    )

    /** Records what was dispatched, and delegates everything else to the deterministic fake. */
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

    private fun declaredTools() = listOf(
        Tool(
            name = "read_file",
            description = "Read a file",
            parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
            execute = { emptyList() },
        ),
    )

    private fun catalogBuild(): BridgeCatalogBuild = BridgeToolCatalog.build(
        listOf(
            BridgeToolCandidate.tool(
                name = "read_file",
                description = "Read a file",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", JsonObject(emptyMap()))
                },
                source = ToolSource.LOCAL,
            ),
        ),
    )

    /** A bridge host that always answers with one tool, so the catalog path is genuinely open. */
    private class StaticHost(private val build: BridgeCatalogBuild) : ClaudePToolBridgeHost {
        override suspend fun prepare(
            tools: List<Tool>,
            context: ClaudePToolGenerationContext?,
        ): ClaudePToolPreparation = ClaudePToolPreparation(
            deviceRef = "device-1",
            assistantId = context?.assistantId.orEmpty(),
            conversationId = context?.conversationId.orEmpty(),
            branchId = context?.branchId.orEmpty(),
            timeoutMs = 60_000L,
            catalog = build.catalog,
            snapshot = build.snapshot,
            executionRef = "prep-token-1",
        )

        override suspend fun openGeneration(
            generationId: String,
            preparation: ClaudePToolPreparation,
        ): Boolean = true

        override fun closeGeneration(generationId: String) = Unit

        override suspend fun execute(
            invocation: BridgeInvocation,
            status: ClaudePToolStatusSink,
            claims: BridgeExecutionClaimant,
        ): BridgeToolExecution = error("this host is never asked to execute anything")

        override val executions: BridgeExecutionHost = BridgeExecutionHost.NONE
    }

    /**
     * Runs one request and returns the refusal it produced, or `null` when it dispatched instead.
     *
     * A hand-rolled catch rather than `assertThrows`: the call being made is suspending, and a
     * lambda that is not a coroutine body cannot make it.
     */
    private fun refusalOf(
        provider: ClaudePProvider,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): ClaudePUnsupportedInputException? = runBlocking {
        try {
            provider.streamText(setting, messages, params).toList()
            null
        } catch (failure: ClaudePUnsupportedInputException) {
            failure
        }
    }

    // ---------------------------------------------------------------------------------------
    // The happy path, so the refusals below are about the check rather than about the setup
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a system instruction that matches the frozen expectation dispatches exactly once`() {
        val gateway = CapturingGateway(FakeClaudePGatewayClient())
        val provider = ClaudePProvider(gateway = gateway, requestIdFactory = { "req-1" })
        val frozen = "<runtime_value_ref name=\"cur_date\"/>"

        runBlocking {
            provider.streamText(
                setting,
                listOf(systemMessage(frozen), userMessage()),
                params(expectation = frozen),
            ).toList()
        }

        assertEquals(1, gateway.startCalls)
        assertEquals(frozen, gateway.startBodies.single().systemPrompt)
    }

    @Test
    fun `a request with no system instruction at all is not refused`() {
        val gateway = CapturingGateway(FakeClaudePGatewayClient())
        val provider = ClaudePProvider(gateway = gateway, requestIdFactory = { "req-2" })

        runBlocking {
            provider.streamText(setting, listOf(userMessage()), params(expectation = null)).toList()
        }

        assertEquals(1, gateway.startCalls)
        assertNull(gateway.startBodies.single().systemPrompt)
    }

    // ---------------------------------------------------------------------------------------
    // The refusals
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a system message changed after the freeze is refused before any dispatch`() {
        val fake = FakeClaudePGatewayClient()
        val gateway = CapturingGateway(fake)
        val provider = ClaudePProvider(gateway = gateway, requestIdFactory = { "req-3" })

        val frozen = "stable instructions"
        // Something wrote to the system message after the app froze it — a work-space prompt for an
        // assistant that has one, a non-default template, a transformer added later.
        val drifted = "stable instructions\n\n<workspace>a sandbox appeared</workspace>"

        val failure = refusalOf(
            provider,
            listOf(systemMessage(drifted), userMessage()),
            params(expectation = frozen),
        )

        assertNotNull("the drifted system message must be refused", failure)
        assertEquals(ClaudePUnsupportedInput.UNSTABLE_SYSTEM_PROMPT, failure!!.input)
        assertEquals(0, gateway.startCalls)
        assertEquals(0, gateway.startBodies.size)
        assertEquals("nothing reached the transport either", 0, fake.remoteDispatchCount)
    }

    @Test
    fun `a system instruction with no frozen expectation is refused`() {
        val fake = FakeClaudePGatewayClient()
        val gateway = CapturingGateway(fake)
        val provider = ClaudePProvider(gateway = gateway, requestIdFactory = { "req-4" })

        val failure = refusalOf(
            provider,
            listOf(systemMessage("unfrozen instructions"), userMessage()),
            params(expectation = null),
        )

        // Distinguished from a drift so an operator can tell "the layout never ran" from "the layout
        // ran and something afterwards disagreed with it".
        assertNotNull(failure)
        assertEquals(ClaudePUnsupportedInput.MISSING_SYSTEM_PROMPT_EXPECTATION, failure!!.input)
        assertEquals(0, gateway.startCalls)
        assertEquals(0, fake.remoteDispatchCount)
    }

    @Test
    fun `a drift is reported as a drift, not as a missing expectation`() {
        val gateway = CapturingGateway(FakeClaudePGatewayClient())
        val provider = ClaudePProvider(gateway = gateway, requestIdFactory = { "req-5" })

        val failure = refusalOf(
            provider,
            listOf(systemMessage("frozen"), userMessage()),
            params(expectation = "something else"),
        )

        assertNotNull(failure)
        assertEquals(ClaudePUnsupportedInput.UNSTABLE_SYSTEM_PROMPT, failure!!.input)
        assertEquals(0, gateway.startCalls)
    }

    @Test
    fun `a non-default message template that names the clock is refused, and refused by name`() {
        // The template case, tested as itself rather than as an incidental mismatch.
        //
        // `TemplateTransformer` rewrites every message through the assistant's `messageTemplate`,
        // and its `time`/`date` come from the message's `createdAt` — which for the system message
        // is the moment the turn was built, because the layout constructs a fresh one each time. A
        // template containing `{{ time }}` therefore renders different system text every turn.
        //
        // The exact bytes Pebble would produce are not reproduced here: its SLF4J binding is
        // Android-only and cannot initialise in a JVM unit test. What is reproduced is the shape
        // the freeze acts on, which is all it ever sees.
        val gateway = CapturingGateway(FakeClaudePGatewayClient())
        val provider = ClaudePProvider(gateway = gateway, requestIdFactory = { "req-template" })

        val frozen = "You are a helpful assistant."
        val renderedByATemplateThatNamesTheClock = "You are a helpful assistant.\n[current time: 9:14:02 PM]"

        val failure = refusalOf(
            provider,
            listOf(systemMessage(renderedByATemplateThatNamesTheClock), userMessage()),
            params(expectation = frozen),
        )

        assertNotNull("a template that moves the system message must be refused", failure)
        assertEquals(ClaudePUnsupportedInput.UNSTABLE_SYSTEM_PROMPT, failure!!.input)
        assertEquals(0, gateway.startCalls)
    }

    @Test
    fun `a refusal never tries again`() {
        val fake = FakeClaudePGatewayClient()
        val gateway = CapturingGateway(fake)
        val provider = ClaudePProvider(gateway = gateway, requestIdFactory = { "req-6" })

        val failure = refusalOf(
            provider,
            listOf(systemMessage("drifted"), userMessage()),
            params(expectation = "frozen"),
        )

        assertNotNull(failure)
        // One attempt, no retry, no second request of any kind: the failure is a refusal, and a
        // refusal that silently re-tried would be a model call the client cannot bind.
        assertEquals(0, gateway.startCalls)
        assertEquals(0, fake.startGenerationCallCount)
        assertEquals(0, fake.remoteDispatchCount)
    }

    // ---------------------------------------------------------------------------------------
    // The check must not close a path that was open
    // ---------------------------------------------------------------------------------------

    @Test
    fun `the tool catalog path still dispatches under a frozen expectation`() {
        val gateway = CapturingGateway(FakeClaudePGatewayClient())
        val provider = ClaudePProvider(
            gateway = gateway,
            requestIdFactory = { "req-7" },
            toolHost = StaticHost(catalogBuild()),
        )
        val frozen = "<runtime_value_ref name=\"cur_date\"/>"

        runBlocking {
            provider.streamText(
                setting,
                listOf(systemMessage(frozen), userMessage()),
                params(expectation = frozen, tools = declaredTools()),
            ).toList()
        }

        assertEquals(1, gateway.startCalls)
        val body = gateway.startBodies.single()
        assertEquals(frozen, body.systemPrompt)
        assertNotNull("the catalog must still travel in generation.start", body.toolSnapshot)
        assertTrue(body.toolSnapshot.toString().contains("read_file"))
    }
}
