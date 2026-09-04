package me.rerere.rikkahub.service

import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.service.chat.CommandOrigin
import me.rerere.rikkahub.service.chat.RawUserContent
import me.rerere.rikkahub.service.chat.SendMessageCommand
import me.rerere.rikkahub.service.chat.StopCommand
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for "Conversation not found" on the first message of a brand-new ordinary
 * chat: durable command admission requires the conversation row to already exist, but a fresh
 * chat is only held in the in-memory ConversationSession until its first message executes.
 * Admission therefore materializes (persists) the unsaved new conversation when the very first
 * user send arrives. This pins exactly when that materialization is allowed.
 */
class ShouldMaterializeConversationAtFirstSendTest {

    private fun userMessage(): SendMessageCommand =
        SendMessageCommand(RawUserContent(listOf(UIMessagePart.Text("hello"))))

    @Test
    fun `first message of a brand-new ordinary chat may materialize the conversation`() {
        assertTrue(
            shouldMaterializeConversationAtFirstSend(
                origin = CommandOrigin.APP_UI,
                command = userMessage(),
                isNewConversationDraft = true,
                assistantExists = true,
            )
        )
        assertTrue(
            shouldMaterializeConversationAtFirstSend(
                origin = CommandOrigin.WEB_API,
                command = userMessage(),
                isNewConversationDraft = true,
                assistantExists = true,
            )
        )
    }

    @Test
    fun `non user surfaces do not materialize an unsaved conversation`() {
        for (origin in listOf(
            CommandOrigin.TELEGRAM,
            CommandOrigin.CRON,
            CommandOrigin.SYSTEM_ASSISTANT,
            CommandOrigin.QUICK_CAPTURE,
            CommandOrigin.PET_INTERACTION,
        )) {
            assertFalse(
                "origin $origin must stay gated",
                shouldMaterializeConversationAtFirstSend(
                    origin = origin,
                    command = userMessage(),
                    isNewConversationDraft = true,
                    assistantExists = true,
                )
            )
        }
    }

    @Test
    fun `non send-message commands never materialize a conversation`() {
        assertFalse(
            shouldMaterializeConversationAtFirstSend(
                origin = CommandOrigin.APP_UI,
                command = StopCommand(),
                isNewConversationDraft = true,
                assistantExists = true,
            )
        )
    }

    @Test
    fun `conversations that are no longer an unsaved draft stay gated`() {
        assertFalse(
            shouldMaterializeConversationAtFirstSend(
                origin = CommandOrigin.APP_UI,
                command = userMessage(),
                isNewConversationDraft = false, // already persisted, or a deleted/opaque id
                assistantExists = true,
            )
        )
    }

    @Test
    fun `missing assistant stays gated`() {
        assertFalse(
            shouldMaterializeConversationAtFirstSend(
                origin = CommandOrigin.APP_UI,
                command = userMessage(),
                isNewConversationDraft = true,
                assistantExists = false,
            )
        )
    }
}
