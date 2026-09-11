package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.CONVERSATION_SEARCH_TOOL_NAME
import me.rerere.rikkahub.data.ai.tools.TRANSIENT_CONVERSATION_READER_TOOL_NAMES
import me.rerere.rikkahub.data.ai.tools.TRANSIENT_CONVERSATION_SEARCH_TOOL_NAME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransientConversationToolSanitizerTest {

    /** The set an ordinary (non-privileged) assistant session resolves to. */
    private val noTransientSurface: Set<String> = emptySet()

    /** The set an active Second-User history-read session resolves to. */
    private val transientSurface: Set<String> = TRANSIENT_CONVERSATION_READER_TOOL_NAMES

    @Test
    fun `raw cross conversation output and query are removed while pairing survives`() {
        val rawSecret = "UNIQUE_OTHER_CONVERSATION_SECRET"
        val tool = UIMessagePart.Tool(
            toolCallId = "call-42",
            toolName = "conversation_read_recent",
            input = """{"conversation_id":"abc","query":"$rawSecret"}""",
            output = listOf(
                UIMessagePart.Text(
                    """{"ok":true,"code":"OK","operation":"read","source_conversation_id":"abc","source_title":"Source","count":1,"character_count":32,"truncated":false,"data":[{"text":"$rawSecret"}]}"""
                )
            ),
        )
        val sanitized = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool)))
            .sanitizeTransientConversationToolResults(transientSurface)
        val result = sanitized.single().parts.single() as UIMessagePart.Tool
        val serialized = result.toString()

        assertEquals("call-42", result.toolCallId)
        assertEquals("conversation_read_recent", result.toolName)
        assertFalse(serialized.contains(rawSecret))
        assertTrue(result.input.contains("[redacted after current task]"))
        assertTrue((result.output.single() as UIMessagePart.Text).text.contains("raw_content_saved\":false"))
        assertTrue((result.output.single() as UIMessagePart.Text).text.contains("Source"))
    }

    @Test
    fun `ordinary tool output is untouched`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "ordinary",
            toolName = "get_time_info",
            input = "{}",
            output = listOf(UIMessagePart.Text("keep me")),
        )
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))
        assertEquals(
            listOf(message),
            listOf(message).sanitizeTransientConversationToolResults(transientSurface),
        )
    }

    @Test
    fun `query is redacted even in execution started breadcrumb`() {
        val pending = UIMessagePart.Tool(
            toolCallId = "pending",
            toolName = TRANSIENT_CONVERSATION_SEARCH_TOOL_NAME,
            input = """{"conversation_id":"abc","query":"private phrase"}""",
            executionStartedAt = 123L,
        )
        val sanitized = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(pending)))
            .sanitizeTransientConversationToolResults(transientSurface)
            .single().parts.single() as UIMessagePart.Tool
        assertFalse(sanitized.input.contains("private phrase"))
        assertTrue(sanitized.output.isEmpty())
        assertEquals(123L, sanitized.executionStartedAt)
    }

    // ── Regression: the ordinary assistant's conversation_search is NOT transient ──────────
    //
    // The Second-User reader's search tool used to be named `conversation_search`, the same
    // literal the ordinary assistant's global search tool uses. Because the sanitizer matched on
    // tool name alone, an ordinary assistant's real search results were replaced by the redacted
    // envelope and persisted that way to Room and FTS. Two different tools must never share a
    // name; this test is the executable form of that rule.

    @Test
    fun `ordinary conversation search results survive the sanitizer`() {
        val realSnippet = "ORDINARY_ASSISTANT_REAL_SEARCH_HIT"
        val tool = UIMessagePart.Tool(
            toolCallId = "ordinary-search",
            toolName = CONVERSATION_SEARCH_TOOL_NAME,
            input = """{"query":"holiday plans","limit":15}""",
            output = listOf(
                UIMessagePart.Text(
                    """[{"conversation_id":"c1","title":"Trip","snippet":"$realSnippet","date":"2026-01-02"}]"""
                )
            ),
        )

        val result = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool)))
            .sanitizeTransientConversationToolResults(noTransientSurface)
            .single().parts.single() as UIMessagePart.Tool
        val serialized = result.toString()

        // The real payload — including the snippet and the original query — must be preserved.
        assertTrue(serialized.contains(realSnippet))
        assertTrue(result.input.contains("holiday plans"))
        assertFalse(serialized.contains("raw_content_saved"))
        assertFalse(serialized.contains("REDACTED"))
        assertEquals(tool, result)
    }

    @Test
    fun `transient reader search results are still redacted under its own name`() {
        val rawSecret = "OTHER_CONVERSATION_SECRET_STILL_REDACTED"
        val tool = UIMessagePart.Tool(
            toolCallId = "reader-search",
            toolName = TRANSIENT_CONVERSATION_SEARCH_TOOL_NAME,
            input = """{"conversation_id":"abc","query":"$rawSecret"}""",
            output = listOf(
                UIMessagePart.Text(
                    """{"ok":true,"code":"OK","operation":"search","source_conversation_id":"abc","count":1,"data":[{"snippet":"$rawSecret"}]}"""
                )
            ),
        )

        val result = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool)))
            .sanitizeTransientConversationToolResults(transientSurface)
            .single().parts.single() as UIMessagePart.Tool
        val serialized = result.toString()

        assertFalse(serialized.contains(rawSecret))
        assertTrue(serialized.contains("raw_content_saved\":false"))
    }

    @Test
    fun `nothing is redacted when the transient surface was not active`() {
        // Even a tool that IS a transient reader name must survive untouched when this session
        // never activated the reader surface: the discriminator is runtime session state, not
        // the name alone.
        val tool = UIMessagePart.Tool(
            toolCallId = "reader-search",
            toolName = TRANSIENT_CONVERSATION_SEARCH_TOOL_NAME,
            input = """{"conversation_id":"abc","query":"kept"}""",
            output = listOf(UIMessagePart.Text("""{"ok":true,"data":[{"snippet":"kept"}]}""")),
        )

        val result = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool)))
            .sanitizeTransientConversationToolResults(noTransientSurface)
            .single().parts.single() as UIMessagePart.Tool

        assertEquals(tool, result)
    }

    @Test
    fun `oversized transient conversation output is never spilled to disk`() {
        assertFalse(shouldSpillToolOutputToFile("conversation_read_recent", 100_000, true))
        assertFalse(shouldSpillToolOutputToFile(TRANSIENT_CONVERSATION_SEARCH_TOOL_NAME, 100_000, true))
        assertTrue(shouldSpillToolOutputToFile("workspace_shell", 100_000, true))
    }

    @Test
    fun `oversized ordinary conversation search output is still spilled to disk`() {
        // Companion to the test above: the ordinary tool's name must no longer be caught by the
        // transient spill exemption, or a large ordinary result would blow the context window.
        assertTrue(
            shouldSpillToolOutputToFile(CONVERSATION_SEARCH_TOOL_NAME, 100_000, true)
        )
    }

    @Test
    fun `secret owner input and ephemeral token never reach the persisted conversation`() {
        val secret = "secret-that-must-not-persist"
        val token = "ephemeral-token-that-must-not-persist"
        val tool = UIMessagePart.Tool(
            toolCallId = "owner-secret",
            toolName = "owner_secret_manage",
            input = """{"request_id":"secret-request","actions":[{"type":"secret_replace","arguments":{"slot_id":"slot","find":"$secret","replacement":"new-secret"}}]}""",
            output = listOf(
                UIMessagePart.Text(
                    """{"ok":true,"code":"SECRET_REVEALED","value":"$secret","_ephemeral_secret_token":"$token"}""",
                ),
            ),
        )

        val persisted = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool)))
            .sanitizeTransientConversationToolResults(transientSurface)
            .toString()

        assertFalse(persisted.contains(secret))
        assertFalse(persisted.contains(token))
        assertTrue(persisted.contains("SECRET_ARGUMENT_REDACTED"))
        assertTrue(persisted.contains("SECRET_REVEALED"))
    }

    @Test
    fun `owner redaction is independent of the transient reader surface`() {
        // Owner-tool redaction keys off its own name sets and must keep working even in a session
        // where no transient reader surface was ever active.
        val secret = "owner-secret-with-no-transient-surface"
        val tool = UIMessagePart.Tool(
            toolCallId = "owner-secret",
            toolName = "owner_secret_manage",
            input = """{"request_id":"r","actions":[{"type":"secret_plaintext_reveal","arguments":{"slot_id":"slot","find":"$secret"}}]}""",
        )

        val persisted = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool)))
            .sanitizeTransientConversationToolResults(noTransientSurface)
            .toString()

        assertFalse(persisted.contains(secret))
        assertTrue(persisted.contains("SECRET_ARGUMENT_REDACTED"))
    }

    @Test
    fun `provider credential inventory endpoint and keys stay transient`() {
        val secret = "provider-secret-that-must-not-persist"
        val endpoint = "https://private-provider.example/v1"
        val token = "provider-ephemeral-token"
        val tool = UIMessagePart.Tool(
            toolCallId = "owner-provider-credentials",
            toolName = "owner_secret_manage",
            input = """{"request_id":"provider-credentials","actions":[{"type":"secret_provider_credentials_reveal","arguments":{"provider_ids":["provider-1"]}}]}""",
            output = listOf(UIMessagePart.Text(
                """{"ok":true,"actions":[{"type":"secret_provider_credentials_reveal","data":{"providers":[{"provider_id":"provider-1","base_url":"$endpoint","value":"$secret","_ephemeral_secret_token":"$token"}]}}]}""",
            )),
        )

        val persisted = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool)))
            .sanitizeTransientConversationToolResults(transientSurface)
            .toString()

        assertFalse(persisted.contains(secret))
        assertFalse(persisted.contains(endpoint))
        assertFalse(persisted.contains(token))
        assertTrue(persisted.contains("PROVIDER_URL_REDACTED"))
        assertTrue(persisted.contains("SECRET_ARGUMENT_REDACTED"))
    }

    @Test
    fun `owner operation bodies commands paths and headers are removed before persistence`() {
        val privateValue = "OWNER_PRIVATE_VALUE_9137"
        val tools = listOf(
            UIMessagePart.Tool(
                toolCallId = "service",
                toolName = "owner_service_manage",
                input = """{"request_id":"request-service","actions":[{"type":"service_register","arguments":{"command":"$privateValue","working_dir":"/$privateValue"}}]}""",
            ),
            UIMessagePart.Tool(
                toolCallId = "tts",
                toolName = "owner_tts_manage",
                input = """{"request_id":"request-tts","actions":[{"type":"tts_create_generic_http","arguments":{"body_template":"$privateValue","headers":[{"name":"X-Test","value_template":"$privateValue"}]}}]}""",
            ),
            UIMessagePart.Tool(
                toolCallId = "workflow",
                toolName = "owner_workflow_manage",
                input = """{"request_id":"request-workflow","actions":[{"type":"workflow_create","arguments":{"definition":{"private":"$privateValue"}}}]}""",
            ),
        )

        val persisted = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = tools))
            .sanitizeTransientConversationToolResults(transientSurface)
            .toString()

        assertFalse(persisted.contains(privateValue))
        assertTrue(persisted.contains("OWNER_ARGUMENT_REDACTED"))
    }
}
