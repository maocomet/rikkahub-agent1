package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.ai.tools.CONVERSATION_SEARCH_TOOL_NAME
import me.rerere.rikkahub.data.ai.tools.RECENT_CHATS_TOOL_NAME
import me.rerere.rikkahub.data.ai.tools.ordinaryConversationToolNames
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANTS
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ordinary assistant must not carry cross-conversation read tools unless it opted in.
 *
 * `recent_chats` / `conversation_search` used to be added for every non-privileged assistant
 * regardless of configuration, which is what made the fork's request payload diverge from a
 * plain assistant with no history capability.
 */
class OrdinaryAssistantConversationToolSurfaceTest {

    private fun namedTools(assistant: Assistant, origin: ToolCallOrigin): Set<String> =
        ordinaryConversationToolNames(
            historyToolsEnabled = assistant.allowConversationHistoryTools,
            callOrigin = origin,
        )

    @Test
    fun `factory default assistant exposes no conversation history tools`() {
        DEFAULT_ASSISTANTS.forEach { assistant ->
            assertFalse(
                "${assistant.id} must default to no history tools",
                assistant.allowConversationHistoryTools,
            )
            ToolCallOrigin.entries.forEach { origin ->
                assertTrue(
                    "${assistant.id}@$origin must expose nothing",
                    namedTools(assistant, origin).isEmpty(),
                )
            }
        }
    }

    @Test
    fun `opt in exposes both tools on the local chat surface`() {
        assertEquals(
            setOf(RECENT_CHATS_TOOL_NAME, CONVERSATION_SEARCH_TOOL_NAME),
            ordinaryConversationToolNames(
                historyToolsEnabled = true,
                callOrigin = ToolCallOrigin.LocalChat,
            ),
        )
    }

    @Test
    fun `opt in keeps conversation search off every non local origin`() {
        val nonLocal = ToolCallOrigin.entries.filterNot { it == ToolCallOrigin.LocalChat }
        assertTrue("expected at least one non-local origin", nonLocal.isNotEmpty())

        nonLocal.forEach { origin ->
            assertEquals(
                "origin=$origin must not reach cross-conversation search",
                setOf(RECENT_CHATS_TOOL_NAME),
                ordinaryConversationToolNames(historyToolsEnabled = true, callOrigin = origin),
            )
        }
    }

    @Test
    fun `opting in never yields a tool outside the declared conversation tool set`() {
        val declared = setOf(RECENT_CHATS_TOOL_NAME, CONVERSATION_SEARCH_TOOL_NAME)

        ToolCallOrigin.entries.forEach { origin ->
            val names = ordinaryConversationToolNames(
                historyToolsEnabled = true,
                callOrigin = origin,
            )
            assertTrue(
                "origin=$origin yielded unexpected tools: ${names - declared}",
                declared.containsAll(names),
            )
        }
    }

    @Test
    fun `the static recent chats prompt flag is not this gate`() {
        val assistant = Assistant(enableRecentChatsReference = true)

        assertTrue(
            "turning on the static prompt block must not expose the tools",
            namedTools(assistant, ToolCallOrigin.LocalChat).isEmpty(),
        )
    }
}
