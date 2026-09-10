package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantConversationHistoryToolsSerializationTest {
    @Test
    fun `legacy assistant defaults conversation history tools off`() {
        assertFalse(
            JsonInstant.decodeFromString<Assistant>("""{"name":"legacy"}""")
                .allowConversationHistoryTools
        )
    }

    @Test
    fun `conversation history tools stay independent of the recent chats reference flag`() {
        val assistant = Assistant(
            name = "ordinary",
            enableRecentChatsReference = true,
            allowConversationHistoryTools = false,
        )

        val restored = JsonInstant.decodeFromString<Assistant>(JsonInstant.encodeToString(assistant))

        assertTrue("the static prompt flag is a separate concern", restored.enableRecentChatsReference)
        assertFalse("it must not turn the history tools on", restored.allowConversationHistoryTools)
    }

    @Test
    fun `explicit conversation history tools opt in survives serialization`() {
        val assistant = Assistant(name = "reader", allowConversationHistoryTools = true)

        val restored = JsonInstant.decodeFromString<Assistant>(JsonInstant.encodeToString(assistant))

        assertTrue(restored.allowConversationHistoryTools)
        assertFalse(
            "the Second-User reader flag is untouched",
            restored.allowConversationHistoryRead,
        )
    }
}
